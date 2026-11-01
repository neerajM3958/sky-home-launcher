package com.skyhome.launcher;

import com.skyhome.launcher.api.Http;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.database.ContentObserver;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The poster rows under the drawer's weather and quotes cards (config.properties "catalogs"): one TMDB list request per
 * row, once a day, with posters loaded only when a row is near the screen. A day's new rows are prepared in the
 * background (their first posters on disk) and shown when the browse screen isn't open ({@link #publish}). A title
 * opens through Launcher (its own app, its Play Store page, or Stremio), like every other card.
 */
final class Catalogs {
    static final class Item {
        int id;
        String kind;        // "movie" or "tv"
        String title, poster;
        double rating;
        String imdb;        // Trakt's rows know it already: Stremio's page needs no TMDB request
        int traktId;        // Trakt's own id, for its episode list
        long playbackId;    // Trakt's id of a paused play (Continue Watching), to take it off the list; 0 for the rest
        int markSeason, markEpisode;    // the episode a Continue Watching tile stands for, to mark it watched; 0 for the rest
        int season, episode, wnKind;
        String episodeTitle = "";
        boolean episodes;   // OK opens the show's episodes ("Shows you watch" and watchlist shows) instead of the title's page
        String stars;       // "★ 7.8", or null
        String posterUrl;   // Trakt's own poster (a whole address); null: `poster` is a TMDB path
        String sub;         // under the title instead of the stars ("S2 · E5"), or null
        float progress;     // 0 to 1: a bar on the poster, when it is part-way through
        long at;            // when it was last watched (Continue Watching's order), 0 when unknown
        android.content.Intent launch;      // Watch Next's: OK reopens that app where it was left (not saved)
        String appPkg;      // ... and the app that published it
        String servicePkg;  // the app it opens in, for the card's icon: null not known yet, "" none (UI thread)
        int serviceAsked;   // 1: looked in what is kept, 2: asked TMDB too (UI thread)
    }

    static final class Row {
        String key, title;
        final List<Item> items = new ArrayList<>();
    }

    interface Listener { void onCatalogs(); }

    private static final long EVERY = 24 * 3600_000L;
    private static final int VERSION = 1, MAX_POSTERS = 40;
    private static final String IMAGES = "https://image.tmdb.org/t/p/w342";
    private static final int ANIMATION = 16;

    private static Catalogs instance;

    static synchronized Catalogs get(Context c) {
        if (instance == null) instance = new Catalogs(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final SharedPreferences cache;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService lists = Executors.newSingleThreadExecutor(r -> daemon(r, "catalogs"));
    /** Posters missing from disk: downloaded and saved, two at a time (never in the way of a read from disk). */
    private final ExecutorService fetch = Executors.newFixedThreadPool(2, r -> daemon(r, "posters"));
    /** Posters read from disk, the last asked first (after a quick scroll, the ones on screen now come first). */
    private final ExecutorService decode = new java.util.concurrent.ThreadPoolExecutor(1, 1, 0, java.util.concurrent.TimeUnit.MILLISECONDS,
            new java.util.concurrent.LinkedBlockingDeque<Runnable>() {
                @Override public boolean offer(Runnable r) { return offerFirst(r); }
            }, r -> daemon(r, "poster-decode"));
    private final ExecutorService watching = Executors.newSingleThreadExecutor(r -> daemon(r, "watch-next"));
    private volatile List<Item> watchNext = new ArrayList<>();    // what the TV's apps say you were watching, newest first
    private volatile List<Item> watchShows = new ArrayList<>();
    private volatile boolean watchNextBusy, watchNextAgain;
    private volatile long watchNextAt;
    private final List<Listener> listeners = new ArrayList<>();
    /** Decoded posters (GPU memory), by path and width. Not recycled when dropped: a row may be drawing one this very frame. */
    private final Map<String, Bitmap> posters = new LinkedHashMap<String, Bitmap>(32, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
            return size() > MAX_POSTERS;
        }
    };
    private final Set<String> pending = new HashSet<>();
    /** When each poster was last asked for (a frame drew it): a read waiting in the queue is skipped once it's off screen. */
    private final Map<String, Long> asked = new LinkedHashMap<String, Long>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > 200;
        }
    };
    /** The posters on disk (`cacheDir/posters`, about 20 KB each at the size drawn); past this the least recently used go. */
    private static final long DISK_BUDGET = 20L << 20;
    private volatile List<Row> rows = new ArrayList<>();      // TMDB's
    private volatile List<Row> next;                          // TMDB's next rows, ready and waiting for publish()
    private volatile List<Row> shown = new ArrayList<>();     // Trakt's rows (when connected) and Continue Watching
    private volatile List<Row> browse = new ArrayList<>();    // TMDB's rows
    private volatile String shownFor = "";                   // what wanted() was when `shown` was built
    private volatile boolean fetching;
    private volatile long at, failedAt;
    private volatile String language = "";

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    private Catalogs(Context c) {
        context = c;
        cache = c.getSharedPreferences("catalogs", Context.MODE_PRIVATE);
        if (cache.getInt("version", 0) != VERSION) cache.edit().clear().putInt("version", VERSION).apply();
        try {
            String saved = cache.getString("rows", null);
            if (saved != null && wanted().equals(cache.getString("wanted", ""))) rows = parse(new JSONArray(saved));
            String waiting = cache.getString("next", null);
            if (waiting != null && !rows.isEmpty()) next = parse(new JSONArray(waiting));
            at = cache.getLong("at", 0);
            language = cache.getString("language", "");
        } catch (Exception ignored) {
        }
        fetch.execute(this::removeOldCache);
        Trakt.get(c).setListener(() -> { rebuild(); notifyListeners(); });
        rebuild();
        try {       // an app changes its entries: read them again
            c.getContentResolver().registerContentObserver(TvContract.WatchNextPrograms.CONTENT_URI, true, new ContentObserver(main) {
                @Override public void onChange(boolean selfChange) {
                    refreshWatchNext(true);
                }
            });
        } catch (RuntimeException e) {
            AppLog.w("watch next: " + e.getMessage());
        }
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    /** The rows under the drawer: Trakt's when connected, with Continue Watching (TMDB's are in browseRows). A card opens through Launcher. */
    List<Row> rows() {
        return shown;
    }

    /** TMDB's catalogs, for the browse screen under Trakt's rows: one row at a time, the details of the focused title above it. */
    List<Row> browseRows() {
        return browse;
    }

    /** A new list each time the rows change: the home screen compares it by reference. TMDB's follow the order the person set, and a row turned off goes at once. */
    private void rebuild() {
        // Hidden last: a hidden Trakt tile must not let its Watch Next twin (dropped by listed()) fill the gap.
        List<Row> all = withoutHidden(withWatchShows(withWatchNext(new ArrayList<>(Trakt.get(context).rows()))));
        List<Row> tmdb = new ArrayList<>();
        if (Recs.hasKey(context)) {
            for (String k : keys()) for (Row r : rows) if (r.key.equals(k)) tmdb.add(r);
        }
        browse = tmdb;
        shown = all;
        shownFor = wanted();
    }

    // MARK: - Continue Watching: Trakt's, and what the TV's apps say (Watch Next)

    /**
     * Trakt's Continue Watching with the Watch Next entries Trakt doesn't list, by most recent first; with no Trakt
     * list, the entries alone. `trakt` is a copy the rows of which are not touched.
     */
    private List<Row> withWatchNext(List<Row> trakt) {
        List<Item> mine = watchNext;
        if (mine.isEmpty() || !Prefs.watchNext()) return trakt;
        int at = -1;
        for (int i = 0; i < trakt.size() && at < 0; i++) if (Trakt.CONTINUE.equals(trakt.get(i).key)) at = i;
        List<Item> theirs = at < 0 ? new ArrayList<>() : trakt.get(at).items;
        Row row = new Row();
        row.key = Trakt.CONTINUE;
        row.title = Ui.tr("Continue Watching");
        row.items.addAll(theirs);
        int added = 0;
        for (Item it : mine) {
            if (added >= WatchNext.MAX) break;
            if (!listed(it, theirs)) {
                row.items.add(it);
                added++;
            }
        }
        if (added == 0) return trakt;
        java.util.Collections.sort(row.items, (a, b) -> Long.compare(b.at, a.at));     // stable: equal times keep Trakt's order
        if (at < 0) trakt.add(0, row); else trakt.set(at, row);
        return trakt;
    }

    /** Adds Watch Next shows to Trakt's Shows you watch row, keeping their resume intents for the episodes page. */
    private List<Row> withWatchShows(List<Row> trakt) {
        if (!Prefs.watchNext() || !WatchNext.permitted(context)) return trakt;
        List<Item> mine = watchShows;
        if (mine.isEmpty()) return trakt;
        int at = -1;
        for (int i=0;i<trakt.size();i++) if (Trakt.SHOWS.equals(trakt.get(i).key)) { at=i; break; }
        List<Item> theirs = at < 0 ? new ArrayList<>() : trakt.get(at).items;
        Row row = new Row(); row.key=Trakt.SHOWS; row.title=Ui.tr("Shows you watch"); row.items.addAll(theirs);
        int added=0;
        for (Item source : mine) {
            if (added >= WatchNext.MAX || row.items.size() >= 20) break;
            if (listedShow(source, theirs)) continue;
            Item it=new Item(); it.kind=source.kind; it.title=source.title; it.id=source.id; it.poster=source.poster; it.at=source.at;
            it.launch=source.launch; it.appPkg=source.appPkg; it.episodes=true; it.season=source.season; it.episode=source.episode;
            it.episodeTitle=source.episodeTitle; it.wnKind=source.wnKind; row.items.add(it); added++;
        }
        if (added == 0) return trakt;
        java.util.Collections.sort(row.items, (a,b)->Long.compare(b.at,a.at));
        if (at < 0) { int ci=-1; for(int i=0;i<trakt.size();i++) if(Trakt.CONTINUE.equals(trakt.get(i).key)){ci=i;break;} trakt.add(ci<0?0:ci+1,row); }
        else trakt.set(at,row);
        return trakt;
    }

    // MARK: - Tile actions: Remove (Trakt takes it off the list) and Hide (this launcher forgets it)

    static final int HIDE = 0, REMOVE = 1;
    /** The answer to an action beyond Trakt's DONE, DENIED and FAILED: the tile was hidden here. */
    static final int HIDDEN = 3;

    /** The row's key and the tile's identity, so hiding a title from one row leaves it in the others. */
    static String hideKey(String rowKey, Item it) {
        String who;
        if (it.launch != null) who = "wn:" + it.appPkg + "|" + WatchNext.normalize(it.title) + "|" + it.kind;
        else if (it.traktId > 0) who = it.kind + ":t" + it.traktId;
        else if (it.id > 0) who = it.kind + ":m" + it.id;
        else who = it.kind + ":n" + WatchNext.normalize(it.title);
        return rowKey + "|" + who;
    }

    /** REMOVE where Trakt has a plain "take it off" for this tile (the watchlist, a paused play); HIDE for the rest. */
    int actionFor(String rowKey, Item it) {
        if (it.launch != null || it.traktId <= 0 || !Trakt.get(context).canWrite()) return HIDE;
        if (Trakt.WATCHLIST.equals(rowKey)) return REMOVE;
        if (Trakt.CONTINUE.equals(rowKey) && it.playbackId > 0) return REMOVE;
        return HIDE;
    }

    /**
     * Does what actionFor says. `done` (main thread) gets Trakt.DONE, Trakt.FAILED, or HIDDEN when the tile was hidden
     * here, including when Trakt refused the removal (then it won't offer Remove again).
     */
    void act(String rowKey, Item it, java.util.function.IntConsumer done) {
        if (actionFor(rowKey, it) == HIDE) {
            hide(rowKey, it);
            done.accept(HIDDEN);
            return;
        }
        java.util.function.IntConsumer after = r -> {
            if (r == Trakt.DENIED) {
                hide(rowKey, it);
                done.accept(HIDDEN);
                return;
            }
            // A paused play off the list may leave the show as its next episode; hiding it keeps it gone until it is watched again.
            if (r == Trakt.DONE && Trakt.CONTINUE.equals(rowKey)) hide(rowKey, it);
            done.accept(r);
        };
        if (Trakt.WATCHLIST.equals(rowKey)) Trakt.get(context).removeFromWatchlist(it, after);
        else Trakt.get(context).removePlayback(it, after);
    }

    /**
     * Whether "Mark as watched" is offered: a movie in Continue Watching or the Watchlist, or the episode a Continue Watching
     * tile stands for. Not whole shows (that would mark every episode), nor the TV's apps' tiles (they can't be told).
     */
    boolean canMark(String rowKey, Item it) {
        if (it.launch != null || it.traktId <= 0 || !Trakt.get(context).canWrite()) return false;
        if ("tv".equals(it.kind)) return Trakt.CONTINUE.equals(rowKey) && it.markSeason > 0 && it.markEpisode > 0;
        return Trakt.CONTINUE.equals(rowKey) || Trakt.WATCHLIST.equals(rowKey);
    }

    /** Marks the tile watched on Trakt. `done` (main thread) gets Trakt.DONE, DENIED or FAILED. */
    void mark(String rowKey, Item it, java.util.function.IntConsumer done) {
        Trakt.get(context).markWatched(rowKey, it, done);
    }

    /** Whether a catalog title can go on or off the watchlist: connected to Trakt, a write not refused, and a TMDB id. */
    boolean canWatchlist(Item it) {
        return it.id > 0 && Trakt.get(context).canWrite();
    }

    boolean onWatchlist(Item it) {
        return Trakt.get(context).onWatchlist(it) != null;
    }

    /** Adds the title to the watchlist, or takes it off if it is there. `done` (main thread) gets Trakt.DONE, DENIED or FAILED. */
    void toggleWatchlist(Item it, java.util.function.IntConsumer done) {
        Item listed = Trakt.get(context).onWatchlist(it);
        if (listed != null) Trakt.get(context).removeFromWatchlist(listed, done);
        else Trakt.get(context).addToWatchlist(it, done);
    }

    private void hide(String rowKey, Item it) {
        // Not before the tile's own last activity (Trakt's clock, not the TV's): only newer activity brings it back.
        Prefs.hideTile(hideKey(rowKey, it), Math.max(System.currentTimeMillis(), it.at));
        rebuild();
        notifyListeners();
    }

    void unhideAll() {
        Prefs.unhideAllTiles();
        rebuild();
        notifyListeners();
    }

    /** The rows without what the person hid, unless it has been watched since. New rows: the ones given are not touched. */
    private List<Row> withoutHidden(List<Row> all) {
        Map<String, Long> hidden = Prefs.hiddenTiles();
        if (hidden.isEmpty()) return all;
        List<Row> out = new ArrayList<>();
        for (Row row : all) {
            Row kept = new Row();
            kept.key = row.key;
            kept.title = row.title;
            for (Item it : row.items) {
                Long at = hidden.get(hideKey(row.key, it));
                if (at == null || it.at > at) kept.items.add(it);
            }
            if (!kept.items.isEmpty()) out.add(kept);
        }
        return out;
    }

    /** Trakt's list already has the title: by TMDB id when both know it, else by name. */
    private static boolean listed(Item it, List<Item> trakt) {
        String name = WatchNext.normalize(it.title);
        for (Item t : trakt) {
            if (it.id > 0 && t.id == it.id && it.kind.equals(t.kind)) return true;
            if (name.equals(WatchNext.normalize(t.title))) return true;
        }
        return false;
    }

    /** Shows row identity prefers two known TMDB ids; names are the fallback only when an id is missing. */
    private static boolean listedShow(Item it,List<Item> trakt){
        String name=WatchNext.normalize(it.title);
        for(Item t:trakt){
            if(!it.kind.equals(t.kind))continue;
            if(it.id>0&&t.id>0){if(it.id==t.id)return true;continue;}
            if(name.equals(WatchNext.normalize(t.title)))return true;
        }
        return false;
    }

    /** Reads Watch Next again (a local query, TMDB lookups kept): when the drawer opens, and when an app changes its entries. */
    void refreshWatchNext(boolean changed) {
        if (!Prefs.watchNext() || !WatchNext.permitted(context)) {
            if (!watchNext.isEmpty() || !watchShows.isEmpty()) {
                watchNext = new ArrayList<>();
                watchShows = new ArrayList<>();
                rebuild();
                notifyListeners();
            }
            return;
        }
        if (watchNextBusy) {
            watchNextAgain |= changed;
            return;
        }
        if (SystemClock.elapsedRealtime() - watchNextAt < (changed ? 3_000L : 20_000L)) return;
        watchNextBusy = true;
        watching.execute(() -> {
            try {
                do {
                    watchNextAgain = false;
                    List<Item> next = WatchNext.items(context);
                    List<Item> shows = WatchNext.showItems(context);
                    if (!same(next, watchNext) || !same(shows, watchShows)) {
                        AppLog.w("watch next: " + next.size() + " from the TV's apps");
                        watchNext = next;
                        watchShows = shows;
                        rebuild();
                        notifyListeners();
                    }
                } while (watchNextAgain);
            } catch (RuntimeException e) {
                AppLog.w("watch next: " + e.getMessage());
            } finally {
                watchNextAt = SystemClock.elapsedRealtime();
                watchNextBusy = false;
            }
        });
    }

    private static boolean same(List<Item> a, List<Item> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            Item x = a.get(i), y = b.get(i);
            if (!x.title.equals(y.title) || x.id != y.id || x.at != y.at || x.progress != y.progress || x.wnKind != y.wnKind
                    || x.season != y.season || x.episode != y.episode || !java.util.Objects.equals(x.episodeTitle,y.episodeTitle) || !java.util.Objects.equals(x.sub, y.sub)
                    || !java.util.Objects.equals(x.poster, y.poster) || !java.util.Objects.equals(x.appPkg,y.appPkg)
                    || !java.util.Objects.equals(x.launch==null?null:x.launch.toUri(0),y.launch==null?null:y.launch.toUri(0))) return false;
        }
        return true;
    }

    /** Every row TMDB can fill, in the order they are offered (Sky Home Launcher > TMDB > Poster rows). */
    static final String[] ALL = {"trending", "popular_movies", "popular_shows", "hindi", "top_rated", "now_playing", "upcoming"};

    /** The rows that are on (Sky Home Launcher > TMDB > Poster rows; catalogs= in config.properties gives the first ones), in order. */
    private static List<String> keys() {
        List<String> out = new ArrayList<>();
        for (String k : Prefs.catalogRows()) if (title(k) != null && !out.contains(k)) out.add(k);
        return out;
    }

    private static String wanted() {
        return String.join(",", keys()) + (Prefs.hideAnimation() ? "|noanimation" : "")
                + "|" + (Prefs.recommendFromApps() ? "apps" : "any") + (Prefs.flatrateOnly() ? "S" : "") + (Prefs.flatrateOnly() && Prefs.includeRentBuy() ? "R" : "")
                + Recs.region() + Prefs.languages() + Prefs.hiddenServices();
    }

    static String title(String key) {
        switch (key) {
            case "trending": return Ui.tr("Trending today");
            case "popular_movies": return Ui.tr("Popular movies");
            case "popular_shows": return Ui.tr("Popular shows");
            case "top_rated": return Ui.tr("Top rated");
            case "now_playing": return Ui.tr("Now playing");
            case "upcoming": return Ui.tr("Upcoming");
            case "hindi": return Ui.tr("Hindi picks");
            default: return null;
        }
    }

    /**
     * The discover filter of the TMDB section's recommendation settings (what streams in the region, on the TV's apps),
     * or null when neither is on: then the rows are TMDB's plain lists.
     */
    private static String filter(Recs recs) throws Exception {
        boolean apps = Prefs.recommendFromApps();
        if (!apps && !Prefs.flatrateOnly() && Prefs.languages().isEmpty()) return null;
        return "&watch_region=" + Recs.region() + Recs.monetization()
                + (apps ? "&with_watch_providers=" + java.net.URLEncoder.encode(recs.installedProviderIds(), "UTF-8") : "");
    }

    /**
     * The TMDB requests of a row, each {kind of its titles ("movie", "tv", or "" when the answer says), path}. Popularity
     * is worldwide; titles come in the TV's language. With a filter every row is a discover query, since TMDB's own
     * trending, top rated, now playing and upcoming lists take none: trending is the popular movies and shows
     * together, top rated the best rated ones, now playing the releases of the past six weeks, upcoming the next six
     * months (which mostly stream nowhere yet, so with the filter on that row usually has nothing and goes).
     */
    private static List<String[]> requests(String key, String lang, String filter) {
        String q = "?language=" + lang + "&include_adult=false" + (filter == null ? "" : filter);
        // The Hindi row is Hindi whatever the languages say.
        String languages = filter == null || key.equals("hindi") ? "" : Recs.languageFilter();
        q += languages;
        String popular = "&sort_by=popularity.desc";
        List<String[]> out = new ArrayList<>();
        if (filter == null) {
            switch (key) {
                case "trending": out.add(new String[] {"", "/trending/all/day" + q}); break;
                case "popular_movies": out.add(new String[] {"movie", "/discover/movie" + q + popular + "&vote_count.gte=100"}); break;
                case "popular_shows": out.add(new String[] {"tv", "/discover/tv" + q + popular + "&vote_count.gte=50"}); break;
                case "top_rated": out.add(new String[] {"movie", "/movie/top_rated" + q}); break;
                case "now_playing": out.add(new String[] {"movie", "/movie/now_playing" + q}); break;
                case "upcoming": out.add(new String[] {"movie", "/movie/upcoming" + q}); break;
                default: out.add(new String[] {"movie", "/discover/movie" + q + popular + "&vote_count.gte=20&with_original_language=hi"});
            }
            return out;
        }
        switch (key) {
            case "trending":
                out.add(new String[] {"movie", "/discover/movie" + q + popular + "&vote_count.gte=100"});
                out.add(new String[] {"tv", "/discover/tv" + q + popular + "&vote_count.gte=50"});
                break;
            case "popular_movies": out.add(new String[] {"movie", "/discover/movie" + q + popular + "&vote_count.gte=100"}); break;
            case "popular_shows": out.add(new String[] {"tv", "/discover/tv" + q + popular + "&vote_count.gte=50"}); break;
            case "top_rated": out.add(new String[] {"movie", "/discover/movie" + q + "&sort_by=vote_average.desc&vote_count.gte=500"}); break;
            case "now_playing":
                out.add(new String[] {"movie", "/discover/movie" + q + popular + "&primary_release_date.gte=" + Recs.day(-45) + "&primary_release_date.lte=" + Recs.day(0)});
                break;
            case "upcoming":
                out.add(new String[] {"movie", "/discover/movie" + q + popular + "&primary_release_date.gte=" + Recs.day(1) + "&primary_release_date.lte=" + Recs.day(180)});
                break;
            default: out.add(new String[] {"movie", "/discover/movie" + q + popular + "&vote_count.gte=20&with_original_language=hi"});
        }
        return out;
    }

    /** Fetches the rows when they are older than a few hours, in another language or not the ones asked for (the home screen calls this when the drawer opens). */
    void refreshIfStale() {
        refreshWatchNext(false);
        if (!wanted().equals(shownFor)) {      // the rows or the animation choice changed in the settings
            failedAt = 0;       // a new try, even if the last one failed a minute ago
            rebuild();
            notifyListeners();
        }
        Trakt.get(context).refreshIfStale();
        if (fetching || !Recs.hasKey(context)) return;
        List<String> keys = keys();
        if (keys.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (failedAt != 0 && now - failedAt >= 0 && now - failedAt < 10 * 60_000L) return;
        boolean fresh = now >= at && now - at < EVERY && language.equals(Ui.language) && wanted().equals(cache.getString("wanted", ""))
                && !rows.isEmpty();
        if (fresh) return;
        fetching = true;
        lists.execute(() -> {
            try {
                String lang = Recs.lang();
                String filter = filter(Recs.get(context));
                List<String> paths = new ArrayList<>();
                List<List<String[]>> asked = new ArrayList<>();
                for (String key : keys) {
                    List<String[]> r = requests(key, lang, filter);
                    asked.add(r);
                    for (String[] one : r) paths.add(one[1]);
                }
                org.json.JSONObject[] answers = Recs.get(context).getAll(paths.toArray(new String[0]));
                List<Row> next = new ArrayList<>();
                int n = 0;
                for (int i = 0; i < keys.size(); i++) {
                    Row row = new Row();
                    row.key = keys.get(i);
                    row.title = title(row.key);
                    List<String[]> r = asked.get(i);
                    // A row with two requests (trending, with a filter) takes their titles in turn.
                    for (int j = 0; j < ROW_SIZE; j++) {
                        for (int a = 0; a < r.size() && row.items.size() < ROW_SIZE; a++) {
                            JSONArray results = answers[n + a].optJSONArray("results");
                            if (results != null && j < results.length()) add(row, results.optJSONObject(j), r.get(a)[0]);
                        }
                    }
                    n += r.size();
                    if (!row.items.isEmpty()) next.add(row);
                }
                if (next.isEmpty()) throw new IOException("TMDB returned no titles");
                boolean changed = rows.isEmpty() || !language.equals(Ui.language) || !wanted().equals(cache.getString("wanted", ""));
                if (changed) {      // nothing shown yet, or the settings changed: at once
                    rows = next;
                    this.next = null;
                    rebuild();
                } else {            // the day's new rows: their first posters on disk, then shown when the browse screen isn't open
                    int width = rowPosterWidth();
                    for (Row row : next) {
                        for (int i = 0; i < row.items.size() && i < 10; i++) {
                            Item it = row.items.get(i);
                            File f = posterFile(it, width);
                            if (f.exists()) continue;
                            try {
                                save(it, width, f).recycle();
                            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                                AppLog.w("poster: " + e.getMessage());
                            }
                        }
                    }
                    this.next = next;
                    AppLog.w("catalogs: next rows ready");
                }
                at = System.currentTimeMillis();
                language = Ui.language;
                failedAt = 0;
                save(keys);
            } catch (Exception e) {
                failedAt = System.currentTimeMillis();
                AppLog.w("catalogs: " + e.getMessage());
                if (!wanted().equals(cache.getString("wanted", ""))) {      // the settings changed and nothing fits them
                    rows = new ArrayList<>();
                    rebuild();
                }
            } finally {
                fetching = false;
                notifyListeners();
            }
        });
    }

    private static final int ROW_SIZE = 20;

    /** `kind`: "movie" or "tv" when the request was for one, "" when each title says (media_type). */
    private static void add(Row row, JSONObject o, String kind) {
        if (o == null) return;
        String poster = o.optString("poster_path", "");
        if (poster.isEmpty() || "null".equals(poster)) return;
        String type = o.optString("media_type", "");
        String own = kind.equals("tv") || type.equals("tv") ? "tv" : type.equals("person") ? null : "movie";
        if (own == null) return;
        kind = own;
        if (Prefs.hideAnimation()) {
            JSONArray genres = o.optJSONArray("genre_ids");
            for (int i = 0; genres != null && i < genres.length(); i++) if (genres.optInt(i) == ANIMATION) return;
        }
        Item it = new Item();
        it.id = o.optInt("id");
        it.kind = kind;
        it.title = o.optString(kind.equals("movie") ? "title" : "name");
        it.poster = poster;
        it.rating = o.optInt("vote_count") >= 10 ? o.optDouble("vote_average", 0) : 0;
        it.stars = it.rating > 0 ? String.format(Ui.locale(), "★ %.1f", it.rating) : null;
        row.items.add(it);
    }

    private void save(List<String> keys) {
        try {
            SharedPreferences.Editor e = cache.edit().putString("rows", toJson(rows).toString()).putString("wanted", wanted())
                    .putLong("at", at).putString("language", language);
            List<Row> waiting = next;
            if (waiting != null) e.putString("next", toJson(waiting).toString());
            else e.remove("next");
            e.apply();
        } catch (Exception ignored) {
        }
    }

    private static JSONArray toJson(List<Row> list) throws org.json.JSONException {
        JSONArray a = new JSONArray();
        for (Row row : list) {
            JSONArray items = new JSONArray();
            for (Item it : row.items) {
                items.put(new JSONObject().put("id", it.id).put("kind", it.kind).put("title", it.title).put("poster", it.poster)
                        .put("rating", it.rating));
            }
            a.put(new JSONObject().put("key", row.key).put("items", items));
        }
        return a;
    }

    /**
     * Shows the day's new TMDB rows, if they're waiting (HomeView calls it only while the browse screen isn't open, so
     * no title changes under the focus).
     */
    void publish() {
        List<Row> waiting = next;
        if (waiting == null) return;
        next = null;
        rows = waiting;
        rebuild();
        lists.execute(() -> save(keys()));
        AppLog.w("catalogs: new rows shown");
        notifyListeners();
    }

    /** The width the rows draw a poster at (HomeView: CAT_POSTER_W × 1.25 at the UI's scale), for posters saved ahead. */
    static int rowPosterWidth() {
        return Math.round(Backdrop.W / 1920f * 180 * 1.25f);
    }

    private static List<Row> parse(JSONArray a) {
        List<Row> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Row row = new Row();
            row.key = o.optString("key");
            row.title = title(row.key);
            JSONArray items = o.optJSONArray("items");
            for (int j = 0; row.title != null && items != null && j < items.length(); j++) {
                JSONObject p = items.optJSONObject(j);
                if (p == null) continue;
                Item it = new Item();
                it.id = p.optInt("id");
                it.kind = p.optString("kind");
                it.title = p.optString("title");
                it.poster = p.optString("poster");
                it.rating = p.optDouble("rating", 0);
                it.stars = it.rating > 0 ? String.format(Ui.locale(), "★ %.1f", it.rating) : null;
                row.items.add(it);
            }
            if (!row.items.isEmpty()) out.add(row);
        }
        return out;
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onCatalogs(); });
    }

    // MARK: - Posters (saved at the size drawn; the ones on screen stay decoded in GPU memory)

    /**
     * The decoded poster at `width` pixels, or null: it is read from disk (or downloaded and saved at that size) in the
     * background and the listeners are told when it is ready. Each width is its own file and entry (the rows draw them
     * at 225 px at 1080p, the episodes screen at 400).
     */
    Bitmap poster(Item it, int width) {
        String key = it.poster + "@" + width;
        synchronized (posters) {
            asked.put(key, SystemClock.uptimeMillis());
            Bitmap b = posters.get(key);
            if (b != null) return b;
            if (!pending.add(key)) return null;
        }
        decode.execute(() -> {
            boolean handed = false;
            try {
                // Scrolled past while it waited (no frame drew it lately): skipped; a frame that still shows it asks again.
                Long last;
                synchronized (posters) { last = asked.get(key); }
                if (last == null || SystemClock.uptimeMillis() - last > 500) {
                    notifyListeners();
                    return;
                }
                File f = posterFile(it, width);
                if (f.exists()) {
                    Bitmap b = hardware(f);
                    if (b != null) {
                        touch(f);
                        keep(key, b);
                        return;
                    }
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();     // unreadable: made again
                }
                handed = true;
                fetch.execute(() -> {
                    try {
                        keep(key, Backdrop.gpu(save(it, width, f)));
                    } catch (IOException | RuntimeException | OutOfMemoryError e) {
                        AppLog.w("poster: " + e.getMessage());
                    } finally {
                        synchronized (posters) { pending.remove(key); }
                    }
                });
            } finally {
                if (!handed) synchronized (posters) { pending.remove(key); }
            }
        });
        return null;
    }

    /** The drawer has been closed a while, or memory is short: the decoded posters go (read back from disk when shown). */
    void trim() {
        synchronized (posters) { posters.clear(); }
    }

    private void keep(String key, Bitmap b) {
        synchronized (posters) { posters.put(key, b); }
        notifyListeners();
    }

    private File dir() {
        return new File(context.getCacheDir(), "posters");
    }

    private File posterFile(Item it, int width) {
        return new File(dir(), it.poster.replace("/", "") + "-" + width + ".jpg");
    }

    private static Bitmap hardware(File f) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.HARDWARE;
        return BitmapFactory.decodeFile(f.getPath(), opts);
    }

    /**
     * Downloads (or copies, for an app's own picture) the poster, cuts a wide app picture to the portrait card, scales it
     * to `width` and saves it as `f`; returns it decoded (software). The download itself isn't kept.
     */
    private Bitmap save(Item it, int width, File f) throws IOException {
        //noinspection ResultOfMethodCallIgnored
        dir().mkdirs();
        File raw = new File(f.getPath() + ".src." + Thread.currentThread().getId() + ".part");
        try {
            if (it.posterUrl != null && !it.posterUrl.startsWith("http")) copy(it.posterUrl, raw);      // content:, android.resource: (an app's own picture)
            else download(it.posterUrl != null ? it.posterUrl : IMAGES + it.poster, raw);
            boolean appArt = it.poster.startsWith("wn-");      // an app's own picture, often wide
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(raw.getPath(), bounds);
            BitmapFactory.Options decode = new BitmapFactory.Options();
            int sample = 1;
            // Trakt's posters are larger than TMDB's w342; a wide picture is cut to the portrait card, so what counts is its height.
            while (sample < 8 && (appArt ? bounds.outHeight / (sample * 2) >= width * 3 / 2 : bounds.outWidth / (sample * 2) >= width)) sample *= 2;
            decode.inSampleSize = sample;
            Bitmap b = BitmapFactory.decodeFile(raw.getPath(), decode);
            if (b == null) throw new IOException("unreadable poster");
            if (appArt && b.getWidth() * 3 > b.getHeight() * 2 + 2) {
                int cut = b.getHeight() * 2 / 3;
                Bitmap tall = Bitmap.createBitmap(b, (b.getWidth() - cut) / 2, 0, cut, b.getHeight());
                if (tall != b) b.recycle();
                b = tall;
            }
            if (b.getWidth() > width) {     // kept at the size it is drawn at
                Bitmap small = Bitmap.createScaledBitmap(b, width, Math.round(b.getHeight() * (float) width / b.getWidth()), true);
                if (small != b) b.recycle();
                b = small;
            }
            File part = new File(f.getPath() + ".part");
            try (FileOutputStream out = new FileOutputStream(part)) {
                if (!b.compress(Bitmap.CompressFormat.JPEG, 88, out)) throw new IOException("couldn't encode the poster");
            }
            if (!part.renameTo(f)) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
                throw new IOException("couldn't save the poster");
            }
            prune();
            return b;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            raw.delete();
        }
    }

    /** An app's picture from its own address (Watch Next art: content: or android.resource:). */
    private void copy(String uri, File to) throws IOException {
        try (InputStream in = context.getContentResolver().openInputStream(Uri.parse(uri));
             FileOutputStream out = new FileOutputStream(to)) {
            if (in == null) throw new IOException("no picture at " + uri);
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        } catch (RuntimeException e) {
            throw new IOException(e.getMessage());
        }
    }

    private static void download(String url, File to) throws IOException {
        Http.to(url).timeouts(8000, 20000).label("poster").download(to);
    }

    /** The poster was just drawn: the disk cache drops the least recently used first. */
    static void touch(File f) {
        long now = System.currentTimeMillis();
        //noinspection ResultOfMethodCallIgnored
        if (now - f.lastModified() > 3600_000L) f.setLastModified(now);
    }

    /** Keeps the posters on disk under DISK_BUDGET, the least recently used going first; a .part left by a stopped download goes after 10 minutes. */
    private synchronized void prune() {
        prune(dir(), DISK_BUDGET);
    }

    /** Keeps `dir` under `budget` bytes, the least recently used files going first (Details uses it too). */
    static void prune(File dir, long budget) {
        File[] files = dir.listFiles();
        if (files == null) return;
        long stale = System.currentTimeMillis() - 10 * 60_000L, total = 0;
        List<File> kept = new ArrayList<>();
        for (File f : files) {
            if (f.getName().endsWith(".part")) {
                //noinspection ResultOfMethodCallIgnored
                if (f.lastModified() < stale) f.delete();
                continue;
            }
            kept.add(f);
            total += f.length();
        }
        if (total <= budget) return;
        java.util.Collections.sort(kept, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < kept.size() && total > budget; i++) {
            total -= kept.get(i).length();
            //noinspection ResultOfMethodCallIgnored
            kept.get(i).delete();
        }
    }

    /** The folder of the earlier cache (posters as downloaded): replaced by `posters`. */
    private void removeOldCache() {
        File old = new File(context.getCacheDir(), "catalogs");
        File[] files = old.listFiles();
        if (files == null) return;
        //noinspection ResultOfMethodCallIgnored
        for (File f : files) f.delete();
        //noinspection ResultOfMethodCallIgnored
        old.delete();
    }
}
