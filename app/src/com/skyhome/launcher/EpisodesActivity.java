package com.skyhome.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapShader;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeProvider;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The episodes of one show (OK on a card of the "Shows you watch" row): every episode with its number and air date,
 * sorted and filtered the way the person picks (season, date, watched), and OK opens the episode in the row's app
 * (Stremio on the episode's page). Drawn by hand like the settings, and for the remote only: a bar of four buttons
 * on top, the list below, and a small list of choices when a button is pressed.
 */
public class EpisodesActivity extends Activity {
    private EpisodesView view;

    /** The intent that opens the episodes of the show on `item` (a card of the "Shows you watch" row). */
    static Intent intent(Context c, Catalogs.Item item) {
        return new Intent(c, EpisodesActivity.class).putExtra("id", item.traktId).putExtra("title", item.title)
                .putExtra("imdb", item.imdb == null ? "" : item.imdb).putExtra("poster", item.poster)
                .putExtra("posterUrl", item.posterUrl == null ? "" : item.posterUrl).putExtra("tmdb", item.id)
                .putExtra("season",item.season).putExtra("episode",item.episode).putExtra("episodeTitle",item.episodeTitle)
                .putExtra("resume",item.launch==null?"":item.launch.toUri(0)).putExtra("app",item.appPkg==null?"":item.appPkg).putExtra("wnKind",item.wnKind);
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        view = new EpisodesView(this, getIntent());
        setContentView(view);
    }

    @Override protected void onResume() {
        super.onResume();
        view.resume();
    }

    @Override protected void onPause() {
        view.pause();
        super.onPause();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (view.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    static final class EpisodesView extends View implements Choreographer.FrameCallback, Catalogs.Listener, Trakt.EpisodesListener {
        private static final int BAR = 0, LIST = 1, CHOOSER = 2;
        private static final int B_SORT = 0, B_SEASON = 1, B_DATE = 2, B_WATCHED = 3, B_CLEAR = 4;
        // Measured on a 1920 wide screen (k scales them): the left column, the bar, the count line and the list.
        private static final float POSTER_X = 100, POSTER_Y = 110, POSTER_W = 320, POSTER_H = 480;
        private static final float LIST_X = 600, LIST_R = 1840, BAR_Y = 64, BAR_H = 88, BTN_GAP = 14;
        private static final float[] BTN_WIDTHS = {300, 230, 230, 230};
        private static final float COUNT_Y = 202, LIST_TOP = 232, ROW_H = 66, PITCH = 72, CHOICE_H = 58, CHOICE_VISIBLE = 9;
        private static final int AMBER = 0xFFFFC266, INK = 0xFF1C1C1E;

        private final EpisodesActivity activity;
        private final Trakt trakt;
        private final Catalogs catalogs;
        private final Scene scene = new Scene();
        private final Look look = Themes.create(Prefs.look(), scene);
        private final Spoken spoken;
        private int traktId;
        private final String title;
        private String imdb;
        private final int tmdbId;
        private final TmdbEpisodes tmdbEpisodes;
        private final boolean hasResume;
        private final int resumeSeason,resumeEpisode,resumeKind;
        private final String resumeTitle,resumeApp;
        private Intent resumeIntent;
        private boolean hasMarks,resolvingTrakt;
        // Where OK opens an episode (Launcher: the show's own app, its Play Store page, the "*" app, or nothing), looked
        // up once when the screen opens so a press is instant; `waiting` is a press that came before the answer.
        private Routing.Target target;
        private Launcher.Handle waiting;
        private long waitingAt;
        private final Catalogs.Item posterItem = new Catalogs.Item();

        private final EpisodeList list = new EpisodeList();
        private List<EpisodeList.Episode> shown = new ArrayList<>();
        private String[] labelText = new String[0], dateText = new String[0], titleText = new String[0];
        private boolean[] fresh = new boolean[0], unaired = new boolean[0];
        private String summary = "";
        private boolean loaded, failed, running;
        private boolean waitingImdb;

        private int zone = LIST, bar, sel, top;
        private final Spring scroll = new Spring(0.34f, 1f, 0), pop = new Spring(0.28f, 0.85f, 0);

        // The choices of the bar button that is open.
        private final List<String> choiceLabels = new ArrayList<>();
        private int[] choiceCodes = new int[0];
        private int choiceFor, choiceCursor, choiceActive, choiceTop;

        private final String[] barLabels = new String[4];
        private final String[] barValues = new String[5];
        private final boolean[] barActive = new boolean[5];

        private int w, h, visible = 8;
        private float k = 1;
        private float currentNight;
        private long startedAt, lastNanos, lastSky;
        private boolean okDown, backDown;

        private final RectF tmp = new RectF(), box = new RectF();
        private final Rect bounds = new Rect();
        private Paint pTitle, pLabel, pValue, pCount, pCountRight, pNum, pRowTitle, pDate, pBadge, pHint, pCenter, fill, spinner;
        private TextPaint wrapPaint;
        private StaticLayout titleLayout;
        private final Map<String, StaticLayout> wraps = new HashMap<>();
        private Bitmap posterBitmap;
        private BitmapShader posterShader;
        private final Matrix matrix = new Matrix();
        private final Paint posterPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final DateFormat dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM, Ui.locale());

        EpisodesView(EpisodesActivity a, Intent intent) {
            super(a);
            activity = a;
            trakt = Trakt.get(a);
            tmdbEpisodes = new TmdbEpisodes(a);
            catalogs = Catalogs.get(a);
            traktId = intent.getIntExtra("id", 0);
            hasMarks = traktId > 0;
            title = intent.getStringExtra("title") == null ? "" : intent.getStringExtra("title");
            imdb = intent.getStringExtra("imdb") == null ? "" : intent.getStringExtra("imdb");
            tmdbId = intent.getIntExtra("tmdb", 0);
            resumeSeason=intent.getIntExtra("season",0); resumeEpisode=intent.getIntExtra("episode",0); resumeKind=intent.getIntExtra("wnKind",0);
            resumeTitle=intent.getStringExtra("episodeTitle")==null?"":intent.getStringExtra("episodeTitle");
            String pkg=intent.getStringExtra("app"); resumeApp=Launcher.get(a).appName(pkg==null?"":pkg);
            String uri=intent.getStringExtra("resume"); hasResume=uri!=null&&!uri.isEmpty();
            if(hasResume)try{resumeIntent=Intent.parseUri(uri,0);}catch(Exception ignored){}
            posterItem.poster = intent.getStringExtra("poster") == null ? "" : intent.getStringExtra("poster");
            String url = intent.getStringExtra("posterUrl");
            posterItem.posterUrl = url == null || url.isEmpty() ? null : url;
            list.sort = Prefs.episodeSort();
            pop.snap(0);
            pop.target = 1;       // the focus settles in when the screen opens
            HomeData.Weather wx = HomeData.get(a).weather;
            Shaders.sky(scene.palette, System.currentTimeMillis(), wx.sunrise, wx.sunset);
            scene.setMotion(0.5f, 0.2f);
            scene.applyPalette();
            setFocusable(true);
            setContentDescription(title);
            spoken = new Spoken(this, new Spoken.Source() {
                @Override public void items(List<Integer> out) { spokenItems(out); }
                @Override public String text(int id) { return spokenText(id); }
                @Override public boolean bounds(int id, Rect out) { return spokenBounds(id, out); }
                @Override public int focused() { return spokenFocused(); }
                @Override public void focus(int id) { spokenFocus(id); }
                @Override public void click(int id) { spokenFocus(id); activate(); }
            });
            refreshBar();
        }

        @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
            return spoken;
        }

        void resume() {
            if (running) return;
            running = true;
            startedAt = SystemClock.uptimeMillis();
            lastNanos = 0;
            catalogs.addListener(this);
            Choreographer.getInstance().postFrameCallback(this);
            load();
            Launcher.get(getContext()).open("tv", tmdbId, imdb, found -> {
                target = found;
                invalidate();
            });
            spoken.readAgain();
        }

        void pause() {
            running = false;
            catalogs.removeListener(this);
            Choreographer.getInstance().removeFrameCallback(this);
        }

        private void load() {
            failed = false;
            if(traktId>0) {int sourceId=traktId; trakt.episodes(sourceId,(episodes,fresh)->{if(traktId==sourceId)onEpisodes(episodes,fresh);});} else if(tmdbId>0) {
                tmdbEpisodes.load(tmdbId,(episodes,fresh)->{if(traktId==0)onEpisodes(episodes,fresh);});
                if(!resolvingTrakt && trakt.connected()){resolvingTrakt=true;trakt.resolveTmdb(tmdbId,id->{if(id>0&&running){traktId=id;hasMarks=true;loaded=false;load();}});}
            }
            if(imdb.isEmpty()&&tmdbId>0) Launcher.get(getContext()).imdb("tv",tmdbId,id->{if(!id.isEmpty())imdb=id;});
            invalidate();
        }

        @Override public void onCatalogs() {     // the poster arrived
            invalidate();
        }

        /** Saved episodes first, then Trakt's: the second one keeps where you are in the list. */
        @Override public void onEpisodes(List<EpisodeList.Episode> episodes, boolean isFresh) {
            if (episodes == null) {
                if (!loaded) failed = true;
                invalidate();
                return;
            }
            if (loaded && !isFresh) return;     // the saved copy again, after coming back from the app
            int keep = loaded && sel < shown.size() ? EpisodeList.key(shown.get(sel)) : -1;
            boolean first = !loaded;
            list.all.clear();
            list.all.addAll(episodes);
            loaded = true;
            failed = false;
            rebuild(keep, first);
            if (first) zone = shown.isEmpty() ? BAR : LIST;
            moved();
        }

        // MARK: - The list

        /** Applies the filters and sorting; the selection stays on `keepKey` when it is still there, else the next episode to watch on the first look, else the top. */
        private void rebuild(int keepKey, boolean toNext) {
            long now = System.currentTimeMillis();
            for(EpisodeList.Episode e:list.all)e.resume=false;
            int match=-1;
            if(hasResume && resumeSeason>0 && resumeEpisode>0) for(int i=0;i<list.all.size();i++) if(list.all.get(i).season==resumeSeason&&list.all.get(i).number==resumeEpisode){match=i;break;}
            if(match<0 && hasResume && resumeEpisode>0){int found=-1,count=0;for(int i=0;i<list.all.size();i++)if(list.all.get(i).number==resumeEpisode){found=i;count++;}if(count==1)match=found;}
            if(match>=0)list.all.get(match).resume=true;
            shown = list.view(now);
            if(hasResume){EpisodeList.Episode resume=new EpisodeList.Episode();resume.resume=true;resume.resumePinned=true;resume.title=resumeTitle.isEmpty()?(resumeSeason>0&&resumeEpisode>0?String.format(Ui.locale(),"S%d · E%d",resumeSeason,resumeEpisode):title):resumeTitle;shown.add(0,resume);}
            int n = shown.size();
            labelText = new String[n];
            dateText = new String[n];
            titleText = new String[n];
            fresh = new boolean[n];
            unaired = new boolean[n];
            for (int i = 0; i < n; i++) {
                EpisodeList.Episode e = shown.get(i);
                labelText[i] = e.resumePinned ? (resumeKind==2?Ui.tr("Watchlist"):Ui.tr("Resume")) : String.format(Ui.locale(), "S%d · E%d", e.season, e.number);
                dateText[i] = e.resumePinned ? resumeApp : e.aired == 0 ? "—" : dateFormat.format(new Date(e.aired));
                fresh[i] = EpisodeList.isNew(e, now);
                unaired[i] = e.aired > now;
            }
            int at = hasResume && toNext ? 0 : -1;
            if (toNext && at < 0) {
                EpisodeList.Episode next = list.nextToWatch(now);
                if (next != null) at = indexOf(EpisodeList.key(next));
            } else if (keepKey >= 0) {
                at = indexOf(keepKey);
            }
            sel = Math.max(0, at);
            top = at >= 0 && toNext ? Math.max(0, sel - 2) : 0;
            ensureVisible();
            if (toNext) scroll.snap(top);
            int seasons = list.seasons().size();
            int regular = list.seasons().contains(0) ? seasons - 1 : seasons;
            summary = Ui.plural("plural|one:{n} episode|other:{n} episodes", list.all.size())
                    + (regular > 0 ? " · " + Ui.plural("plural|one:{n} season|other:{n} seasons", regular) : "");
            refreshBar();
        }

        private int indexOf(int key) {
            for (int i = 0; i < shown.size(); i++) if (EpisodeList.key(shown.get(i)) == key) return i;
            return -1;
        }

        private void ensureVisible() {
            int max = Math.max(0, shown.size() - visible);
            if (sel < top) top = sel;
            else if (sel > top + visible - 1) top = sel - visible + 1;
            top = Math.max(0, Math.min(max, top));
            scroll.target = top;
        }

        // MARK: - The bar

        private int clearIndex() { return hasMarks ? B_CLEAR : B_WATCHED; }
        private int barCount() { return (hasMarks?4:3)+(list.filtered()?1:0); }

        private String sortName(int sort) {
            switch (sort) {
                case EpisodeList.NUMBER_ASC: return Ui.tr("Number, oldest first");
                case EpisodeList.DATE_DESC: return Ui.tr("Date, newest first");
                case EpisodeList.DATE_ASC: return Ui.tr("Date, oldest first");
                default: return Ui.tr("Number, newest first");
            }
        }

        private String seasonName(int season) {
            return season == EpisodeList.SEASON_ALL ? Ui.tr("All seasons")
                    : season == 0 ? Ui.tr("Specials") : Ui.trf("Season {n}", "n", season);
        }

        private String dateName(int date) {
            switch (date) {
                case EpisodeList.DATE_ANY: return Ui.tr("Any time");
                case EpisodeList.LAST_7: return Ui.tr("Last 7 days");
                case EpisodeList.LAST_30: return Ui.tr("Last 30 days");
                case EpisodeList.THIS_YEAR: return Ui.tr("This year");
                case EpisodeList.NOT_AIRED: return Ui.tr("Not aired yet");
                default: return String.valueOf(date);
            }
        }

        private String watchedName(int watched) {
            return watched == EpisodeList.UNWATCHED ? Ui.tr("Unwatched") : watched == EpisodeList.WATCHED ? Ui.tr("Watched") : Ui.tr("All");
        }

        /** The words on the buttons (made again when a filter changes, not every frame). */
        private void refreshBar() {
            barLabels[B_SORT] = Ui.tr("Sort").toUpperCase(Ui.locale());
            barLabels[B_SEASON] = Ui.tr("Season").toUpperCase(Ui.locale());
            barLabels[B_DATE] = Ui.tr("Date").toUpperCase(Ui.locale());
            barLabels[B_WATCHED] = Ui.tr("Watched").toUpperCase(Ui.locale());
            barValues[B_SORT] = sortName(list.sort);
            barValues[B_SEASON] = seasonName(list.season);
            barValues[B_DATE] = dateName(list.date);
            barValues[B_WATCHED] = watchedName(list.watched);
            barValues[B_CLEAR] = Ui.tr("Clear filters");
            barActive[B_SORT] = false;
            barActive[B_SEASON] = list.season != EpisodeList.SEASON_ALL;
            barActive[B_DATE] = list.date != EpisodeList.DATE_ANY;
            barActive[B_WATCHED] = list.watched != EpisodeList.WATCHED_ALL;
            barActive[B_CLEAR] = false;
            if (bar >= barCount()) bar = 0;
        }

        private float barLeft(int i) {
            float x = LIST_X;
            for (int j = 0; j < i; j++) x += BTN_WIDTHS[j] + BTN_GAP;
            return x;
        }

        private RectF barBox(int i) {
            float x = barLeft(i);
            float width = i == clearIndex() ? LIST_R - x : BTN_WIDTHS[i];
            box.set(x * k, BAR_Y * k, (x + width) * k, (BAR_Y + BAR_H) * k);
            return box;
        }

        private RectF rowBox(int i) {
            float y = LIST_TOP + (i - scroll.value) * PITCH;
            box.set(LIST_X * k, y * k, LIST_R * k, (y + ROW_H) * k);
            return box;
        }

        // MARK: - Choosing a filter

        private void openChoices(int button) {
            choiceFor = button;
            choiceLabels.clear();
            long now = System.currentTimeMillis();
            List<Integer> codes = new ArrayList<>();
            int active = 0;
            switch (button) {
                case B_SORT:
                    for (int s = EpisodeList.NUMBER_DESC; s <= EpisodeList.DATE_ASC; s++) { codes.add(s); choiceLabels.add(sortName(s)); }
                    active = list.sort;
                    break;
                case B_SEASON:
                    codes.add(EpisodeList.SEASON_ALL);
                    choiceLabels.add(seasonName(EpisodeList.SEASON_ALL));
                    for (int s : list.seasons()) { codes.add(s); choiceLabels.add(seasonName(s)); }
                    active = codes.indexOf(list.season);
                    break;
                case B_DATE: {
                    int[] fixed = {EpisodeList.DATE_ANY, EpisodeList.LAST_7, EpisodeList.LAST_30, EpisodeList.THIS_YEAR};
                    for (int d : fixed) { codes.add(d); choiceLabels.add(dateName(d)); }
                    java.util.Calendar cal = java.util.Calendar.getInstance();
                    cal.setTimeInMillis(now);
                    for (int y : list.years()) if (y != cal.get(java.util.Calendar.YEAR)) { codes.add(y); choiceLabels.add(String.valueOf(y)); }
                    if (list.hasUpcoming(now)) { codes.add(EpisodeList.NOT_AIRED); choiceLabels.add(dateName(EpisodeList.NOT_AIRED)); }
                    active = codes.indexOf(list.date);
                    break;
                }
                default:
                    for (int s = EpisodeList.WATCHED_ALL; s <= EpisodeList.WATCHED; s++) { codes.add(s); choiceLabels.add(watchedName(s)); }
                    active = list.watched;
                    break;
            }
            choiceCodes = new int[codes.size()];
            for (int i = 0; i < choiceCodes.length; i++) choiceCodes[i] = codes.get(i);
            choiceActive = Math.max(0, active);
            choiceCursor = choiceActive;
            choiceTop = Math.max(0, Math.min(choiceCursor - 3, choiceCodes.length - (int) CHOICE_VISIBLE));
            zone = CHOOSER;
        }

        private void applyChoice() {
            int code = choiceCodes[choiceCursor];
            int keep = !shown.isEmpty() && sel < shown.size() ? EpisodeList.key(shown.get(sel)) : -1;
            switch (choiceFor) {
                case B_SORT: list.sort = code; Prefs.setEpisodeSort(code); break;
                case B_SEASON: list.season = code; break;
                case B_DATE: list.date = code; break;
                default: list.watched = code; break;
            }
            zone = BAR;
            rebuild(keep, false);
        }

        private float chooserLeft() {
            float width = chooserWidth();
            return Math.min(barLeft(choiceFor), LIST_R - width);
        }

        private float chooserWidth() { return choiceFor == B_SORT ? 420 : 340; }

        private int choiceRows() { return Math.min((int) CHOICE_VISIBLE, choiceCodes.length); }

        private RectF choiceBox(int row) {
            float x = chooserLeft() + 12, y = BAR_Y + BAR_H + 22 + row * (CHOICE_H + 4);
            box.set(x * k, y * k, (x + chooserWidth() - 24) * k, (y + CHOICE_H) * k);
            return box;
        }

        // MARK: - Keys

        boolean onKey(KeyEvent e) {
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            int key = e.getKeyCode();
            if (waiting != null && key != KeyEvent.KEYCODE_DPAD_CENTER && key != KeyEvent.KEYCODE_ENTER
                    && key != KeyEvent.KEYCODE_NUMPAD_ENTER) {
                // A press is waiting for where the show streams: Back or moving cancels it (Back does only that).
                if (down && key != KeyEvent.KEYCODE_BACK) stopWaiting();
                if (key == KeyEvent.KEYCODE_BACK) {
                    if (!down) stopWaiting();
                    return true;
                }
            }
            switch (key) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (down) move(0, -1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down) move(0, 1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (down) move(-1, 0);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down) move(1, 0);
                    return true;
                case KeyEvent.KEYCODE_PAGE_UP:
                case KeyEvent.KEYCODE_CHANNEL_UP:
                case KeyEvent.KEYCODE_MEDIA_REWIND:
                    if (down) page(-1);
                    return true;
                case KeyEvent.KEYCODE_PAGE_DOWN:
                case KeyEvent.KEYCODE_CHANNEL_DOWN:
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                    if (down) page(1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (down) {
                        if (e.getRepeatCount() == 0) okDown = true;
                    } else {
                        if (okDown && !e.isCanceled() && spoken.keyClick()) activate();
                        okDown = false;
                    }
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    if (zone == BAR) return false;          // leaves the screen; from the list or the choices, Back goes up to the bar first
                    if (down) {
                        if (e.getRepeatCount() == 0) backDown = true;
                    } else {
                        if (backDown && !e.isCanceled()) {
                            zone = BAR;
                            moved();
                        }
                        backDown = false;
                    }
                    return true;
                default:
                    return false;
            }
        }

        private void moved() {
            pop.snap(0);
            pop.target = 1;
            invalidate();
            spoken.focusMoved();
        }

        private void move(int dx, int dy) {
            int n = shown.size();
            if (zone == CHOOSER) {
                if (dy == 0) return;
                int next = Math.max(0, Math.min(choiceCodes.length - 1, choiceCursor + dy));
                if (next == choiceCursor) return;
                choiceCursor = next;
                int rows = choiceRows();
                if (choiceCursor < choiceTop) choiceTop = choiceCursor;
                else if (choiceCursor > choiceTop + rows - 1) choiceTop = choiceCursor - rows + 1;
            } else if (zone == BAR) {
                if (dx != 0) bar = Math.max(0, Math.min(barCount() - 1, bar + dx));
                else if (dy > 0 && n > 0) zone = LIST;
                else return;
            } else {
                if (n == 0) return;
                if (dy < 0 && sel == 0) zone = BAR;
                else if (dy != 0) sel = Math.max(0, Math.min(n - 1, sel + dy));
                else if (list.sort == EpisodeList.NUMBER_DESC || list.sort == EpisodeList.NUMBER_ASC) jumpSeason(dx);
                else page(dx);
                ensureVisible();
            }
            moved();
        }

        /** In the list's own order: right goes to the first episode of the next season shown, left to the start of this one (or of the one before). */
        private void jumpSeason(int dir) {
            int n = shown.size(), season = shown.get(sel).season;
            if (dir > 0) {
                int i = sel;
                while (i < n && shown.get(i).season == season) i++;
                if (i < n) sel = i;
            } else {
                int start = sel;
                while (start > 0 && shown.get(start - 1).season == season) start--;
                if (start < sel) {
                    sel = start;
                } else if (start > 0) {
                    int before = shown.get(start - 1).season, j = start - 1;
                    while (j > 0 && shown.get(j - 1).season == before) j--;
                    sel = j;
                }
            }
        }

        private void page(int dir) {
            if (zone == CHOOSER) {
                choiceCursor = Math.max(0, Math.min(choiceCodes.length - 1, choiceCursor + dir * (choiceRows() - 1)));
                int rows = choiceRows();
                if (choiceCursor < choiceTop) choiceTop = choiceCursor;
                else if (choiceCursor > choiceTop + rows - 1) choiceTop = choiceCursor - rows + 1;
                moved();
                return;
            }
            if (zone != LIST || shown.isEmpty()) return;
            sel = Math.max(0, Math.min(shown.size() - 1, sel + dir * Math.max(1, visible - 1)));
            ensureVisible();
            moved();
        }

        private void activate() {
            if (zone == CHOOSER) {
                applyChoice();
            } else if (zone == BAR) {
                if (bar == clearIndex()) {
                    int keep = !shown.isEmpty() && sel < shown.size() ? EpisodeList.key(shown.get(sel)) : -1;
                    list.clearFilters();
                    rebuild(keep, false);
                    bar = 0;
                } else {
                    openChoices(bar);
                }
            } else if (!shown.isEmpty()) {
                openEpisode(shown.get(sel));
            } else if (failed) {
                load();
            }
            moved();
        }

        private void stopWaiting() {
            if (waiting == null) return;
            waiting.cancel();
            waiting = null;
            invalidate();
        }

        private void openEpisode(EpisodeList.Episode e) {
            if(e.resume){
                try { if(resumeIntent==null)throw new IllegalStateException(); activity.startActivity(resumeIntent); }
                catch(Exception ex){EpisodeList.Episode fallback=e;
                    if(e.resumePinned){for(EpisodeList.Episode candidate:shown)if(!candidate.resumePinned&&candidate.resume){fallback=candidate;break;}}
                    if(fallback.resumePinned){fallback=new EpisodeList.Episode();fallback.season=resumeSeason;fallback.number=resumeEpisode;fallback.title=resumeTitle;}
                    openRouted(fallback);
                }
                return;
            }
            openRouted(e);
        }

        private void openRouted(EpisodeList.Episode e) {
            if (waiting != null) return;
            RectF r = rowBox(sel);
            final RectF from = new RectF(r);
            Routing.Target t = target;
            if (t == null || (t.kind == Routing.NONE && t.reason == Routing.COULDNT_CHECK)) {
                // Not known yet (or the first try timed out): ask now, with the dots on the row if it takes a moment.
                waitingAt = SystemClock.uptimeMillis();
                waiting = Launcher.get(getContext()).open("tv", tmdbId, imdb, found -> {
                    waiting = null;
                    target = found;
                    routeAfterImdb(found,e,from);
                });
                return;
            }
            routeAfterImdb(t,e,from);
        }

        private void routeAfterImdb(Routing.Target t,EpisodeList.Episode e,RectF from){
            if(t.kind==Routing.APP&&Recs.STREMIO.equals(t.pkg)&&imdb.isEmpty()&&tmdbId>0){
                waitingImdb=true; Launcher.get(getContext()).imdb("tv",tmdbId,id->{waitingImdb=false;if(!id.isEmpty())imdb=id;if(running)go(t,e,from);});
            } else go(t,e,from);
        }

        /** Stremio opens the episode itself; another app opens the show (service apps take no episode address). */
        private void go(Routing.Target t, EpisodeList.Episode e, RectF from) {
            Launcher launcher = Launcher.get(getContext());
            if (t.kind == Routing.APP) {
                String uri = Recs.STREMIO.equals(t.pkg) && !imdb.isEmpty() && e.season>0 && e.number>0 ? Recs.stremioEpisode(imdb, e.season, e.number) : t.uri;
                HomeApps.Item item = HomeApps.app(t.pkg, launcher.appName(t.pkg), uri);
                if (!HomeApps.open(activity, item, this, Math.round(from.left), Math.round(from.top), Math.round(from.width()), Math.round(from.height()))) {
                    Toast.makeText(getContext(), Ui.trf("Couldn't open {app}.", "app", item.label), Toast.LENGTH_SHORT).show();
                }
            } else if (t.kind == Routing.STORE) {
                if (!HomeApps.openStore(activity, t.pkg)) Toast.makeText(getContext(), Ui.tr("Couldn't open the Play Store."), Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(getContext(), Launcher.message(t), Toast.LENGTH_SHORT).show();
            }
            invalidate();
        }

        /** Where OK goes, in words (before the answer is in: just that it opens the episode). */
        private String whereOkGoes() {
            if(hasResume&&sel<shown.size()&&shown.get(sel).resume)return resumeHint();
            Routing.Target t = target;
            if (t == null) return Ui.tr("OK opens the episode.");
            Launcher launcher = Launcher.get(getContext());
            if (t.kind == Routing.APP) {
                return Recs.STREMIO.equals(t.pkg) && !imdb.isEmpty() ? Ui.trf("OK opens the episode in {app}.", "app", launcher.appName(t.pkg))
                        : Ui.trf("OK opens the show in {app}.", "app", launcher.appName(t.pkg));
            }
            if (t.kind == Routing.STORE) return Ui.trf("OK opens {app} in the Play Store.", "app", launcher.appName(t.pkg));
            return Launcher.message(t);
        }

        private String resumeHint(){return resumeKind==2?Ui.trf("OK opens {app}.","app",resumeApp):Ui.trf("OK resumes in {app}.","app",resumeApp);}

        // MARK: - Drawing

        @Override public void doFrame(long frameTimeNanos) {
            if (!running) return;
            if (!scroll.settled() || !pop.settled() || waiting != null || waitingImdb || SystemClock.uptimeMillis() - lastSky >= 100) invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            w = width;
            h = height;
            k = w / 1920f;
            wraps.clear();
            scene.setSize(w, h);
            look.setSize(w, h, k);
            visible = Math.max(3, (int) ((h / k - LIST_TOP - 24) / PITCH));
            pTitle = Ui.text(Ui.displaySemibold, 44 * k, 0xFFFFFFFF);
            pLabel = Ui.text(Ui.semibold, 18 * k, 0x99FFFFFF);
            pLabel.setLetterSpacing(0.06f);
            pValue = Ui.text(Ui.medium, 25 * k, 0xF2FFFFFF);
            pCount = Ui.text(Ui.regular, 24 * k, 0xB3FFFFFF);
            pCountRight = Ui.text(Ui.regular, 24 * k, 0xB3FFFFFF);
            pCountRight.setTextAlign(Paint.Align.RIGHT);
            pNum = Ui.numbers(Ui.semibold, 28 * k, 0xF2FFFFFF);
            pRowTitle = Ui.text(Ui.medium, 29 * k, 0xF2FFFFFF);
            pDate = Ui.numbers(Ui.regular, 25 * k, 0xB3FFFFFF);
            pDate.setTextAlign(Paint.Align.RIGHT);
            pBadge = Ui.text(Ui.semibold, 18 * k, AMBER);
            pBadge.setTextAlign(Paint.Align.RIGHT);
            pHint = Ui.text(Ui.regular, 22 * k, 0x8CFFFFFF);
            pCenter = Ui.text(Ui.medium, 30 * k, 0xCCFFFFFF);
            pCenter.setTextAlign(Paint.Align.CENTER);
            wrapPaint = new TextPaint(Ui.text(Ui.regular, 22 * k, 0x99FFFFFF));
            spinner = new Paint(Paint.ANTI_ALIAS_FLAG);
            spinner.setStyle(Paint.Style.STROKE);
            spinner.setStrokeCap(Paint.Cap.ROUND);
            spinner.setStrokeWidth(3.5f * k);
            spinner.setColor(0xCCFFFFFF);
            fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            TextPaint titlePaint = new TextPaint(pTitle);
            titleLayout = StaticLayout.Builder.obtain(title, 0, title.length(), titlePaint, Math.round(470 * k)).setMaxLines(3)
                    .setEllipsize(TextUtils.TruncateAt.END).build();
            titleText = new String[shown.size()];       // the ellipsized titles are made again at the new size
            ensureVisible();
        }

        private int blend(int a, int b, float f) {
            a = look.inkAlpha(a, currentNight);
            b = look.inkFocusedAlpha(b, currentNight);
            int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
            int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
            return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                    | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
        }

        /** The white focus pill of the settings, with its shadow. */
        private void pill(Canvas c, RectF r, float f) {
            float s = 1 + 0.02f * f, cx = r.centerX(), cy = r.centerY();
            tmp.set(cx - r.width() * s / 2, cy - r.height() * s / 2, cx + r.width() * s / 2, cy + r.height() * s / 2);
            float radius = 18 * k * s;
            look.rowFocus(c, tmp, radius, f, currentNight);
        }

        private StaticLayout wrapped(String text, int width) {
            String key = width + ":" + text;
            StaticLayout l = wraps.get(key);
            if (l == null) {
                l = StaticLayout.Builder.obtain(text, 0, text.length(), wrapPaint, width).build();
                wraps.put(key, l);
            }
            return l;
        }

        /** Wrapped text with its first baseline at `y`; returns the y below it. */
        private float drawWrapped(Canvas c, String text, float x, float y, float width) {
            StaticLayout l = wrapped(text, Math.round(width));
            c.save();
            c.translate(x, y - l.getLineBaseline(0));
            l.draw(c);
            c.restore();
            return y + (l.getLineBaseline(l.getLineCount() - 1) - l.getLineBaseline(0)) + 32 * k;
        }

        @Override protected void onDraw(Canvas c) {
            if (w == 0) return;
            currentNight = Prefs.theme().equals("light") ? 0 : Prefs.theme().equals("dark") ? 1
                    : (HomeData.get(getContext()).isNight(System.currentTimeMillis()) ? 1 : 0);
            pTitle.setColor(look.ink(0xFFFFFFFF, currentNight));
            pLabel.setColor(look.ink(0x99FFFFFF, currentNight));
            pValue.setColor(look.ink(0xF2FFFFFF, currentNight));
            pCount.setColor(look.ink(0xB3FFFFFF, currentNight));
            pCountRight.setColor(look.ink(0xB3FFFFFF, currentNight));
            pNum.setColor(look.ink(0xF2FFFFFF, currentNight));
            pRowTitle.setColor(look.ink(0xF2FFFFFF, currentNight));
            pDate.setColor(look.ink(0xB3FFFFFF, currentNight));
            pHint.setColor(look.ink(0x8CFFFFFF, currentNight));
            pCenter.setColor(look.ink(0xCCFFFFFF, currentNight));
            wrapPaint.setColor(look.ink(0x99FFFFFF, currentNight));
            long now = System.nanoTime();
            float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (now - lastNanos) / 1e9f);
            lastNanos = now;
            scroll.step(dt);
            pop.step(dt);
            long up = SystemClock.uptimeMillis();
            if (up - lastSky >= 100) {
                lastSky = up;
                scene.advance((up - startedAt) / 1000f);
            }
            tmp.set(0, 0, w, h);
            if (!look.background(c, tmp, currentNight)) scene.drawBackground(c);
            drawLeft(c);
            drawBar(c);
            drawCount(c);
            drawList(c, (up - startedAt) / 1000f);
            if (zone == CHOOSER) drawChooser(c);
        }

        private void drawLeft(Canvas c) {
            tmp.set(POSTER_X * k, POSTER_Y * k, (POSTER_X + POSTER_W) * k, (POSTER_Y + POSTER_H) * k);
            Bitmap p = posterItem.poster.isEmpty() ? null : catalogs.poster(posterItem, Math.round(POSTER_W * k * 1.25f));
            if (p != null) {
                if (p != posterBitmap) {
                    posterBitmap = p;
                    posterShader = new BitmapShader(p, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                    posterPaint.setShader(posterShader);
                }
                matrix.setScale(tmp.width() / p.getWidth(), tmp.height() / p.getHeight());
                matrix.postTranslate(tmp.left, tmp.top);
                posterShader.setLocalMatrix(matrix);
                c.drawRoundRect(tmp, 18 * k, 18 * k, posterPaint);
            } else {
                fill.setColor(0x33FFFFFF);
                c.drawRoundRect(tmp, 18 * k, 18 * k, fill);
            }
            float y = (POSTER_Y + POSTER_H + 22) * k;
            c.save();
            c.translate(POSTER_X * k, y - titleLayout.getLineBaseline(0) + 44 * k);
            titleLayout.draw(c);
            c.restore();
            y += (titleLayout.getLineBaseline(titleLayout.getLineCount() - 1) - titleLayout.getLineBaseline(0)) + 44 * k + 30 * k;
            if (!summary.isEmpty()) c.drawText(summary, POSTER_X * k, y, pCount);
            float hy = h - (zone == LIST ? 200 : 130) * k;
            if (zone == CHOOSER) {
                drawWrapped(c, Ui.tr("OK picks, Back cancels."), POSTER_X * k, hy, 470 * k);
            } else if (zone == BAR) {
                drawWrapped(c, Ui.tr("OK changes the filter. Down goes to the episodes."), POSTER_X * k, hy, 470 * k);
            } else {
                hy = drawWrapped(c, whereOkGoes(), POSTER_X * k, hy, 470 * k);
                hy = drawWrapped(c, list.sort == EpisodeList.NUMBER_DESC || list.sort == EpisodeList.NUMBER_ASC
                        ? Ui.tr("← → jump a season.") : Ui.tr("← → scroll a page."), POSTER_X * k, hy - 6 * k, 470 * k);
                drawWrapped(c, Ui.tr("Channel + and − scroll a page."), POSTER_X * k, hy - 6 * k, 470 * k);
            }
        }

        private void drawBar(Canvas c) {
            for (int i = 0; i < barCount(); i++) {
                RectF b = barBox(i);
                boolean on = (zone == BAR && bar == i) || (zone == CHOOSER && choiceFor == i);
                float f = on ? (zone == BAR ? Ui.clamp(pop.value) : 1) : 0;
                if (f > 0.01f) {
                    pill(c, b, f);
                } else {
                    fill.setColor(0x26FFFFFF);
                    c.drawRoundRect(b, 18 * k, 18 * k, fill);
                }
                if (i == clearIndex()) {
                    pCenter.setColor(blend(0xE6FFFFFF, INK, f));
                    c.drawText(TextUtils.ellipsize(Ui.tr("Clear filters"), new TextPaint(pCenter), b.width() - 20 * k, TextUtils.TruncateAt.END).toString(),
                            b.centerX(), b.centerY() + Ui.halfCap(pCenter), pCenter);
                    continue;
                }
                pLabel.setColor(blend(0x99FFFFFF, 0x991C1C1E, f));
                c.drawText(barLabels[i], b.left + 22 * k, b.top + 33 * k, pLabel);
                pValue.setColor(blend(barActive[i] ? AMBER : 0xF2FFFFFF, INK, f));
                c.drawText(TextUtils.ellipsize(barValues[i], new TextPaint(pValue), b.width() - 36 * k, TextUtils.TruncateAt.END).toString(),
                        b.left + 22 * k, b.top + 70 * k, pValue);
            }
        }

        private void drawCount(Canvas c) {
            if (!loaded) return;
            int episodeCount=0; for(EpisodeList.Episode e:shown)if(!e.resumePinned)episodeCount++;
            String line = list.filtered() ? Ui.trf("{shown} of {total} episodes", "shown", episodeCount, "total", list.all.size())
                    : Ui.plural("plural|one:{n} episode|other:{n} episodes", episodeCount);
            c.drawText(line, LIST_X * k + 8 * k, COUNT_Y * k, pCount);
            if (zone == LIST && sel < shown.size()) c.drawText(seasonName(shown.get(sel).season), LIST_R * k - 8 * k, COUNT_Y * k, pCountRight);
        }

        private void drawList(Canvas c, float seconds) {
            float cx = (LIST_X + LIST_R) / 2 * k, cy = (LIST_TOP + 300) * k;
            if (!loaded) {
                if (failed) {
                    c.drawText(Ui.tr("Couldn't load the episodes."), cx, cy, pCenter);
                } else {
                    c.drawText(Ui.tr("Loading episodes…"), cx, cy, pCenter);
                    Ui.spinner(c, cx, cy - 70 * k, 16 * k, seconds * 1.3f, spinner);
                }
                return;
            }
            int n = shown.size();
            if (n == 0) {
                c.drawText(Ui.tr("No episodes match these filters."), cx, cy, pCenter);
                return;
            }
            c.save();
            c.clipRect((LIST_X - 40) * k, (LIST_TOP - 6) * k, (LIST_R + 60) * k, h);
            int first = Math.max(0, (int) Math.floor(scroll.value));
            int last = Math.min(n - 1, first + visible + 1);
            for (int i = first; i <= last; i++) {
                RectF r = rowBox(i);
                float f = zone == LIST && i == sel ? Ui.clamp(pop.value) : 0;
                drawRow(c, r, i, f);
            }
            c.restore();
            if (n > visible) {          // where the window is in the whole list
                float trackTop = LIST_TOP * k, trackH = visible * PITCH * k - 6 * k, x = (LIST_R + 22) * k;
                fill.setColor(0x26FFFFFF);
                tmp.set(x, trackTop, x + 5 * k, trackTop + trackH);
                c.drawRoundRect(tmp, 3 * k, 3 * k, fill);
                float thumb = Math.max(36 * k, trackH * visible / n);
                float at = trackTop + (trackH - thumb) * Ui.clamp(scroll.value / Math.max(1, n - visible));
                fill.setColor(0xB3FFFFFF);
                tmp.set(x, at, x + 5 * k, at + thumb);
                c.drawRoundRect(tmp, 3 * k, 3 * k, fill);
            }
        }

        private void drawRow(Canvas c, RectF r, int i, float f) {
            EpisodeList.Episode e = shown.get(i);
            if (f > 0.01f) pill(c, r, f);
            boolean dim = unaired[i];
            int strong = dim ? 0x80FFFFFF : 0xF2FFFFFF;
            float base = r.centerY() + Ui.halfCap(pRowTitle);
            pNum.setColor(blend(strong, INK, f));
            c.drawText(labelText[i], r.left + 30 * k, base, pNum);
            pRowTitle.setColor(blend(e.watched || dim ? 0x99FFFFFF : 0xF2FFFFFF, INK, f));
            if (titleText[i] == null) {
                String t = e.title.isEmpty() ? "—" : e.title;
                titleText[i] = TextUtils.ellipsize(t, new TextPaint(pRowTitle), 600 * k, TextUtils.TruncateAt.END).toString();
            }
            c.drawText(titleText[i], r.left + 215 * k, base, pRowTitle);
            float dateRight = r.right - 86 * k;
            pDate.setColor(blend(fresh[i] ? AMBER : dim ? 0x80FFFFFF : 0xB3FFFFFF, fresh[i] ? 0xFF9A6A00 : 0xB31C1C1E, f));
            c.drawText(dateText[i], dateRight, base, pDate);
            if (fresh[i]) {
                pBadge.setColor(blend(AMBER, 0xFF9A6A00, f));
                c.drawText(Ui.tr("New").toUpperCase(Ui.locale()), dateRight - pDate.measureText(dateText[i]) - 16 * k, base, pBadge);
            }
            if(e.resume){
                float cw=Symbols.width(getContext(),"play",24*k);
                Symbols.draw(getContext(),c,"play",r.right-40*k-cw/2,r.centerY(),24*k,blend(0xCCFFFFFF,INK,f));
            } else if (e.watched) {
                float cw = Symbols.width(getContext(), "check", 24 * k);
                Symbols.draw(getContext(), c, "check", r.right - 40 * k - cw / 2, r.centerY(), 24 * k, blend(0xCCFFFFFF, INK, f));
            }
            long waited = SystemClock.uptimeMillis() - waitingAt;
            if (waiting != null && i == sel && waited >= 150) {      // waiting for where the show streams
                fill.setColor(0x80000000);
                c.drawRoundRect(r, 18 * k, 18 * k, fill);
                fill.setColor(0xFFFFFFFF);
                Ui.dots(c, r.centerX(), r.centerY(), 6 * k, waited / 1000f, fill);
            }
        }

        private void drawChooser(Canvas c) {
            // The list steps back; the choices sit under their button.
            fill.setColor(0x66000000);
            tmp.set((LIST_X - 20) * k, (LIST_TOP - 10) * k, (LIST_R + 40) * k, h);
            c.drawRect(tmp, fill);
            int rows = choiceRows();
            float left = chooserLeft() * k, width = chooserWidth() * k, top = (BAR_Y + BAR_H + 10) * k;
            tmp.set(left, top, left + width, top + (22 + rows * (CHOICE_H + 4)) * k);
            fill.setColor(0xFF1C1C1E);
            c.drawRoundRect(tmp, 24 * k, 24 * k, fill);
            for (int i = 0; i < rows; i++) {
                int at = choiceTop + i;
                RectF r = choiceBox(i);
                boolean cursor = at == choiceCursor;
                if (cursor) pill(c, r, Ui.clamp(pop.value));
                pRowTitle.setColor(cursor ? INK : 0xF2FFFFFF);
                c.drawText(TextUtils.ellipsize(choiceLabels.get(at), new TextPaint(pRowTitle), r.width() - 90 * k, TextUtils.TruncateAt.END).toString(),
                        r.left + 20 * k, r.centerY() + Ui.halfCap(pRowTitle), pRowTitle);
                if (at == choiceActive) {
                    float cw = Symbols.width(getContext(), "check", 24 * k);
                    Symbols.draw(getContext(), c, "check", r.right - 24 * k - cw / 2, r.centerY(), 24 * k, cursor ? INK : 0xFFFFFFFF);
                }
            }
        }

        // MARK: - TalkBack

        private static final int ID_BAR = 1, ID_ROW = 1000, ID_CHOICE = 3000;

        private void spokenItems(List<Integer> out) {
            if (zone == CHOOSER) {
                for (int i = 0; i < choiceRows(); i++) out.add(ID_CHOICE + i);
                return;
            }
            for (int i = 0; i < barCount(); i++) out.add(ID_BAR + i);
            if (!loaded) return;
            int first = Math.max(0, (int) Math.floor(scroll.value));
            for (int i = first; i < Math.min(shown.size(), first + visible); i++) out.add(ID_ROW + i);
        }

        private String spokenText(int id) {
            if (id >= ID_CHOICE) {
                int at = choiceTop + id - ID_CHOICE;
                return at < choiceLabels.size() ? choiceLabels.get(at) + (at == choiceActive ? Ui.tr(", selected") : "") : "";
            }
            if (id >= ID_ROW) {
                int i = id - ID_ROW;
                if (i >= shown.size()) return "";
                EpisodeList.Episode e = shown.get(i);
                return labelText[i] + ", " + e.title + ", " + dateText[i] + (e.resume ? ", " + resumeHint() : e.watched ? ", " + Ui.tr("Watched") : "");
            }
            int i = id - ID_BAR;
            return i == clearIndex() ? Ui.tr("Clear filters") : i >= 0 && i < (hasMarks?4:3) ? barLabels[i] + ", " + barValues[i] : "";
        }

        private boolean spokenBounds(int id, Rect out) {
            RectF r;
            if (id >= ID_CHOICE) r = choiceBox(id - ID_CHOICE);
            else if (id >= ID_ROW) r = rowBox(id - ID_ROW);
            else r = barBox(id - ID_BAR);
            r.round(out);
            return true;
        }

        private int spokenFocused() {
            return zone == CHOOSER ? ID_CHOICE + choiceCursor - choiceTop : zone == BAR ? ID_BAR + bar : ID_ROW + sel;
        }

        private void spokenFocus(int id) {
            if (id >= ID_CHOICE) {
                choiceCursor = choiceTop + id - ID_CHOICE;
            } else if (id >= ID_ROW) {
                zone = LIST;
                sel = id - ID_ROW;
                ensureVisible();
            } else {
                zone = BAR;
                bar = id - ID_BAR;
            }
            invalidate();
        }
    }
}
