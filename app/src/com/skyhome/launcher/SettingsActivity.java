package com.skyhome.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeProvider;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Sky Home Launcher's settings (the app's icon, "All Apps", or the gear on the home screen), in the style of Apple TV's
 * settings: the big symbol and title on the left, the list on the right with the focused row in white. "Home screen"
 * opens the Sky Home Launcher / Original choice (with a check on the current one); the switch runs in the main process
 * (HomeMode) and this screen only asks for it and follows the state file.
 */
public class SettingsActivity extends Activity {
    private SettingsView view;
    private boolean fromHome;    // the preview was opened by the home screen's Screensaver button
    private DreamView preview;   // "Screensaver": the screensaver right here, without changing the system setting

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        SleepTimer.listen(this);   // :home may start here, from the launcher icon
        SleepTimer.check(this);
        // From ADB (the screenshot script): --es look glass|material3-expressive --es theme auto|light|dark set them first.
        String look = getIntent().getStringExtra("look"), theme = getIntent().getStringExtra("theme");
        if (look != null) Prefs.setLook(look);
        if (theme != null && (theme.equals("auto") || theme.equals("light") || theme.equals("dark"))) Prefs.setTheme(theme);
        view = new SettingsView(this);
        setContentView(view);
    }

    @Override protected void onResume() {
        super.onResume();
        view.resume();
        // From ADB: am start -n com.skyhome.launcher/.SettingsActivity --ez screensaver true (straight to the preview).
        if (preview == null && getIntent().getBooleanExtra("screensaver", false)) {
            getIntent().removeExtra("screensaver");
            fromHome = getIntent().getBooleanExtra("fromHome", false);   // the home screen's button: closing goes back there
            view.pause();
            showPreview();
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        fromHome = false;
        setIntent(intent);
    }

    @Override protected void onPause() {
        closePreview();     // before pausing the view: closing the preview resumes it (view.resume)
        view.pause();
        super.onPause();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (preview != null) {
            // Like the real screensaver: arrows, OK or Back close it (other keys go on to the system).
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_BACK:
                    if (event.getAction() == KeyEvent.ACTION_UP) {
                        closePreview();
                        if (fromHome) finish();
                    }
                    return true;
                default:
                    return super.dispatchKeyEvent(event);
            }
        }
        if (view.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    void showPreview() {
        preview = new DreamView(this);
        setContentView(preview);
        preview.start();
    }

    private void closePreview() {
        if (preview == null) return;
        preview.stop();
        preview = null;
        setContentView(view);
        view.resume();
    }

    static final class SettingsView extends View implements Choreographer.FrameCallback {
        private static final int MAIN = 0, HOME_CHOICE = 1, HOME_SETTINGS = 2, REMOTE_KEYS = 3, PICKER = 4, SHELF_EDIT = 5, TRAKT = 6, TMDB = 7, STREMIO = 8, LANGUAGES = 9, HIDDEN = 10, APPS_OFF = 11, TORRENTIO = 12, TOR_LANGS = 13, ADDED_KEYS = 14, LEARN = 15, INTEGRATIONS = 16, POWER = 17;
        private static final int TRAKT_ROW = 6, TMDB_ROW = 7, STREMIO_ROW = 8, INTEGRATIONS_ROW = 9, SLEEP_ROW = 10, RESTART_ROW = 12;
        private static final int TMDB_PICKS = 1, TMDB_FROM = 2, TMDB_STREAM = 3, RENT_BUY_ROW = 4, TMDB_LANGS = 5, TMDB_HIDDEN = 6, TMDB_ANIMATION = 7, TMDB_CATALOGS = 8;   // the TMDB screen's rows after Region   // the main menu's rows that open them
        private static final float LIST_ROW_H = 68, LIST_ROW_GAP = 6;
        private static final int PICK_VISIBLE = 9;
        private static final float LIST_X = 1000, LIST_R = 1824, LIST_TOP = 186, ROW_GAP = 8, CHOICE_H = 104;
        // The left column (logo, title, version): the comparison and notices go underneath, where the main
        // menu's thirteen rows would otherwise crowd them off the bottom of the screen.
        private static final float LEFT_X = 150, LEFT_R = 900;
        private static final float MAIN_TOP = 122, ROW_H = 52, MAIN_GAP = 4;
        /** Sleep Timer's rows, by what they set: the timer, whether it shows on screen and (where it shows over other
         *  apps) where. Values are picked from lists (OK), as on the TV's own settings; ← → step them. */
        private static final int P_SLEEP = 0, P_SHOW = 1, P_WHERE = 2;
        /** Sleep Timer's duration choices, in minutes (0 = off), as listed and stepped through. */
        private static final int[] SLEEP_MINUTES = {0, 5, 10, 15, 20, 30, 45, 60, 90, 120, 180, 240};
        private static final int PICK_SLEEP = -3, PICK_PLACE = -4;
        private static final int AMBER = 0xFFFFC266, INK = 0xFF1C1C1E;

        private final SettingsActivity activity;
        private final Scene scene = new Scene();
        private final Look look = Themes.create(Prefs.look(), scene);
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Spring[] pop = new Spring[16];     // one per row of the longest unscrolled list (MAIN: 11 rows)
        private final List<String> appPkgs = new ArrayList<>(), appLabels = new ArrayList<>();
        // The list picker (the app of a remote button): what it shows, which one is in use, where we are.
        private final List<String> pickLabels = new ArrayList<>();
        // The shelf editor (also the top buttons' one, with editingTop): what it can hold (selected ones first, in
        // order) and the selection.
        private final List<String[]> shelfRows = new ArrayList<>();
        private final List<String> shelfOrder = new ArrayList<>();
        private boolean editingTop, editingRows;     // editingRows: the poster rows (Sky Home Launcher > TMDB)
        private int pickFor, pickCurrent, pickScroll;   // pickFor: the remote button (index + 1)
        private static final int PICK_CITY = 0, PICK_LANGUAGE = -1, PICK_REGION = -2;
        private final List<String> regionCodes = new ArrayList<>();      // the region picker: "auto", then the countries
        private final List<String[]> cities = new ArrayList<>();
        private String cityStatus = "";
        private String integrationsUrl = "", integrationsError = "";   // the Integrations screen's phone address, or why it couldn't start
        private final RectF tmp = new RectF(), rowRect = new RectF(), focusBox = new RectF();
        private JSONObject state = new JSONObject(), recs = new JSONObject();
        private boolean hasKey, stremioOn;      // stremioOn: the Stremio app is on the TV (looked up every few seconds, not per frame)
        private final List<String[]> langRows = new ArrayList<>();     // the Languages screen: {code, name}, "all" first
        private final List<String[]> hiddenRows = new ArrayList<>();     // the Hidden services screen: {key, name}
        private final List<String[]> appsOffRows = new ArrayList<>();    // Apps turned off: {package, name}; "" = the "Show more" row
        private final java.util.Set<String> appsManaged = new java.util.HashSet<>();   // appsOffRows' packages Sky Home Launcher actually tracks (the rest came from "Show more" and aren't picked yet)
        private int[] keyIds = Prefs.KEYS;      // the remote buttons: the brand's, then the ones added (Prefs.allKeys)
        private static final int MAX_ADDED = 8;
        private final List<String> torLangRows = new ArrayList<>();      // the Torrentio languages screen: Torrentio's names
        private final java.util.Set<String> appsKept = new java.util.HashSet<>();   // ... and the ones the person keeps on
        private final java.util.Set<String> stremioInstalled = new java.util.HashSet<>();   // services whose app is on the TV (read when the screen opens)
        private final List<String[]> stremioRows = new ArrayList<>();     // the Stremio screen: {key, name}, "missing" first
        private int screen = MAIN, focus;
        private int sleepLeft;     // the sleep timer's minutes left, read with the state, not on every frame
        private boolean running, okDown, backDown, wasBusy;
        private StaticLayout errorLayout;   // the last error, wrapped (rebuilt only when it changes)
        private String errorShown;
        private final Spoken spoken;
        private long startedAt, lastNanos, pendingAt, lastSky;
        private int w, h;
        private float k = 1;
        private float currentNight;
        private Paint title, subtitle, version, rowLabel, rowValue, rowSub, header, cellLabel, cellValue, warn, spinner;
        private TextPaint notice;

        private final Runnable poll = new Runnable() {
            @Override public void run() {
                if (!running) return;
                state = HomeMode.state(getContext());
                sleepLeft = SleepTimer.minutesLeft(getContext());
                hasKey = Recs.hasKey(getContext());
                stremioOn = Recs.get(getContext()).stremioInstalled();
                recs = Recs.status(getContext());
                boolean nowBusy = !busyText().isEmpty();
                if (nowBusy) pendingAt = 0;
                // A switch just ended: TalkBack reads how it went (the newest notice).
                JSONArray notices = state.optJSONArray("notices");
                if (wasBusy && !nowBusy && notices != null && notices.length() > 0) {
                    JSONObject newest = notices.optJSONObject(0);
                    if (newest != null) announceForAccessibility(newest.optString("text"));
                }
                wasBusy = nowBusy;
                invalidate();
                handler.postDelayed(this, busy() || StremioInstall.busy() ? 500 : 2000);
            }
        };

        SettingsView(SettingsActivity a) {
            super(a);
            activity = a;
            stremioOn = Recs.get(a).stremioInstalled();
            for (int i = 0; i < pop.length; i++) pop[i] = new Spring(0.28f, 0.85f, 0);
            HomeData.Weather wx = HomeData.get(a).weather;
            Shaders.sky(scene.palette, System.currentTimeMillis(), wx.sunrise, wx.sunset);
            scene.setMotion(0.5f, 0.2f);
            scene.applyPalette();
            setFocusable(true);
            setContentDescription("Sky Home Launcher");
            // TalkBack: one item per row, id = row + 1.
            spoken = new Spoken(this, new Spoken.Source() {
                @Override public void items(List<Integer> out) { for (int i = 1; i <= rowCount(); i++) out.add(i); }
                @Override public String text(int id) { return id >= 1 && id <= rowCount() ? rowText(id - 1) : ""; }
                @Override public boolean bounds(int id, Rect out) {
                    if (id < 1 || id > rowCount()) return false;
                    rowBox(id - 1).round(out);
                    return true;
                }
                @Override public int focused() { return focus + 1; }
                @Override public void focus(int id) {
                    focus = id - 1;
                    invalidate();
                }
                @Override public void click(int id) { activate(); }
                @Override public boolean clickable(int id) { return !disabledRow(id - 1); }
            });
        }

        /** A main menu row that needs something it doesn't have (Trakt without a Client ID, TMDB without its key): greyed, and OK does nothing. */
        private boolean disabledRow(int row) {
            if (screen == STREMIO) return pickScroll + row < stremioRows.size() && stremioOff(pickScroll + row);
            if (screen == TMDB) return row == RENT_BUY_ROW && !Prefs.flatrateOnly();     // everything is included then
            if (screen == POWER) {
                int[] rows = powerRows();
                return row < rows.length && rows[row] == P_WHERE && Capabilities.missing(getContext(), Capabilities.NEED_OVERLAY) != null;
            }
            return screen == MAIN && ((row == TRAKT_ROW && !Trakt.configured()) || (row == TMDB_ROW && !Recs.hasKey(getContext()))
                    || (row == SLEEP_ROW && Capabilities.missing(getContext(), Capabilities.NEED_ADB) != null));
        }

        @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
            return spoken;
        }

        /** A row as TalkBack reads it: its name and its value, as on screen. */
        private String rowText(int row) {
            switch (screen) {
                case MAIN: {
                    if (row == 0) {
                        String busyText = busyText();
                        String value = busy() ? (busyText.isEmpty() ? Ui.tr("Starting…") : busyText)
                                : freeActive() ? "Sky Home Launcher" : Ui.tr("Original");
                        return Ui.trf("Home Screen, {mode}", "mode", value);
                    }
                    String[] names = {Ui.tr("Screensaver, preview"), Ui.tr("Home Settings"),
                            Ui.tr("Shelf"), Ui.tr("Top Buttons"), Ui.tr("Remote Buttons"),
                            Ui.tr("Trakt") + ", " + traktStatus(), Ui.tr("TMDB") + ", " + tmdbStatus(),
                            Ui.tr("Stremio") + ", " + stremioStatus(), Ui.tr("Integrations") + ", " + integrationsStatus(),
                            Ui.tr("Sleep Timer") + ", " + sleepStatus(),
                            Ui.tr("TV Settings")};
                    return row - 1 < names.length ? names[row - 1] : "";
                }
                case POWER: {
                    String[] labels = powerLabels(), values = powerValues();
                    return row < labels.length ? labels[row] + ", " + values[row] : "";
                }
                case TRAKT:
                    return traktAction();
                case INTEGRATIONS:
                    return integrationsAction();
                case TMDB: {
                    String[] labels = tmdbLabels(), values = tmdbValues();
                    return row < labels.length ? labels[row] + ", " + values[row] : "";
                }
                case HIDDEN: {
                    int at = pickScroll + row;
                    return at < hiddenRows.size() ? hiddenRows.get(at)[1] + (hiddenChecked(at) ? Ui.tr(", hidden") : "") : "";
                }
                case APPS_OFF: {
                    int at = pickScroll + row;
                    if (at >= appsOffRows.size()) return "";
                    String label = appsOffRows.get(at)[1];
                    return appsManaged.contains(appsOffRows.get(at)[0]) ? label + ", " + appsOffValue(at) : label;
                }
                case TORRENTIO: {
                    String[] labels = torrentioLabels(), values = torrentioValues();
                    return row < labels.length ? labels[row] + ", " + values[row] : "";
                }
                case TOR_LANGS: {
                    int at = pickScroll + row;
                    return at < torLangRows.size() ? Torrentio.label(torLangRows.get(at)) + (torChosen(at) ? Ui.tr(", selected") : "") : "";
                }
                case ADDED_KEYS: {
                    String[] labels = addedLabels(), values = addedValues();
                    return row < labels.length ? labels[row] + (values[row].isEmpty() ? "" : ", " + values[row]) : "";
                }
                case LEARN:
                    return Ui.tr("Press the button on your remote");
                case LANGUAGES: {
                    int at = pickScroll + row;
                    return at < langRows.size() ? langRows.get(at)[1] + (langChecked(at) ? Ui.tr(", selected") : "") : "";
                }
                case STREMIO: {
                    int at = pickScroll + row;
                    return at < stremioRows.size() ? stremioRows.get(at)[1] + ", " + stremioValue(at) : "";
                }
                case HOME_CHOICE: {
                    if (row == 2) return Ui.tr("Apps turned off") + ", " + Ui.tr("Keep the apps you want on");
                    String name = row == 0 ? "Sky Home Launcher, " + Ui.tr("no ads, with the new screensaver")
                            : "Original, " + Ui.tr("the TV's factory home screen");
                    boolean inUse = (row == 0) == freeActive();
                    return name + (inUse ? Ui.tr(". In use") : "") + ", " + Ui.trf("{i} of {n}", "i", row + 1, "n", 3);
                }
                case HOME_SETTINGS:
                case REMOTE_KEYS: {
                    boolean keys = screen == REMOTE_KEYS;
                    String[] labels = keys ? keyLabels() : settingLabels(), values = keys ? keyValues() : settingValues();
                    int at = pickScroll + row;
                    return at < labels.length ? labels[at] + ", " + values[at] : "";
                }
                case PICKER: {
                    int at = pickScroll + row;
                    if (at >= pickLabels.size()) return "";
                    return pickLabels.get(at) + (at == pickCurrent ? Ui.tr(", selected") : "")
                            + ", " + Ui.trf("{i} of {n}", "i", at + 1, "n", pickLabels.size());
                }
                case SHELF_EDIT: {
                    int at = pickScroll + row;
                    if (at >= shelfRows.size()) return "";
                    String[] app = shelfRows.get(at);
                    int place = shelfOrder.indexOf(app[0]);
                    if (editingRows) {
                        return app[1] + ", " + (place >= 0 ? Ui.trf("on, place {n}", "n", place + 1) : Ui.tr("off"));
                    }
                    if (editingTop) {
                        return app[1] + ", " + (place >= 0 ? Ui.trf("in the top buttons, place {n}", "n", place + 1)
                                : Ui.tr("not in the top buttons"));
                    }
                    return app[1] + ", " + (place >= 0 ? Ui.trf("on the shelf, place {n}", "n", place + 1)
                            : Ui.tr("not on the shelf"));
                }
                default:
                    return "";
            }
        }

        private RectF rowBox(int row) {
            float top, height;
            if (screen == MAIN) {
                top = MAIN_TOP + row * (ROW_H + MAIN_GAP);
                height = ROW_H;
            } else if (screen == HOME_CHOICE) {
                top = LIST_TOP + 20 + row * (CHOICE_H + ROW_GAP);
                height = CHOICE_H;
            } else {
                top = LIST_TOP + 20 + row * (LIST_ROW_H + LIST_ROW_GAP);
                height = LIST_ROW_H;
            }
            focusBox.set(LIST_X * k, top * k, LIST_R * k, (top + height) * k);
            return focusBox;
        }

        void resume() {
            if (running) return;
            running = true;
            startedAt = SystemClock.uptimeMillis();
            lastNanos = 0;
            handler.post(poll);
            Choreographer.getInstance().postFrameCallback(this);
            spoken.readAgain();
        }

        void pause() {
            running = false;
            handler.removeCallbacks(poll);
            Choreographer.getInstance().removeFrameCallback(this);
            IntegrationsServer.stop();     // leaving the settings screen (even via Home) must not leave it listening
        }

        @Override public void doFrame(long frameTimeNanos) {
            if (!running) return;
            boolean moving = busy();
            for (Spring s : pop) moving |= !s.settled();
            if (moving || SystemClock.uptimeMillis() - lastSky >= 100) invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            w = width;
            h = height;
            k = w / 1920f;
            wraps.clear();
            scene.setSize(w, h);
            look.setSize(w, h, k);
            title = Ui.text(Ui.displaySemibold, 50 * k, 0xFFFFFFFF);
            title.setTextAlign(Paint.Align.CENTER);
            subtitle = Ui.text(Ui.regular, 26 * k, 0x99FFFFFF);
            subtitle.setTextAlign(Paint.Align.CENTER);
            version = Ui.text(Ui.regular, 22 * k, 0x66FFFFFF);
            version.setTextAlign(Paint.Align.CENTER);
            rowLabel = Ui.text(Ui.medium, 30 * k, 0xF2FFFFFF);
            rowValue = Ui.text(Ui.regular, 28 * k, 0x8CFFFFFF);
            rowValue.setTextAlign(Paint.Align.RIGHT);
            rowSub = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            header = Ui.text(Ui.semibold, 21 * k, 0x73FFFFFF);
            header.setLetterSpacing(0.06f);
            cellLabel = Ui.text(Ui.regular, 25 * k, 0xD9FFFFFF);
            cellValue = Ui.numbers(Ui.regular, 25 * k, 0xD9FFFFFF);
            cellValue.setTextAlign(Paint.Align.RIGHT);
            warn = Ui.text(Ui.regular, 23 * k, AMBER);
            notice = new TextPaint(Ui.text(Ui.regular, 23 * k, 0xB3FFFFFF));
            spinner = new Paint(Paint.ANTI_ALIAS_FLAG);
            spinner.setStyle(Paint.Style.STROKE);
            spinner.setStrokeCap(Paint.Cap.ROUND);
            spinner.setStrokeWidth(3.5f * k);
        }

        private boolean busy() {
            return !busyText().isEmpty() || (pendingAt != 0 && SystemClock.uptimeMillis() - pendingAt < 10_000);
        }

        private String busyText() {
            long at = state.optLong("busyAt"), now = SystemClock.elapsedRealtime();
            boolean stale = now < at || now - at > 10 * 60_000L;
            return stale ? "" : state.optString("busy");
        }

        private boolean freeActive() {
            return HomeMode.FREE.equals(state.optString("mode")) && !state.optBoolean("homeReverted");
        }

        private int rowCount() {
            switch (screen) {
                case MAIN: return 13;
                case TRAKT: return 1;
                case INTEGRATIONS: return 1;
                case POWER: return powerRows().length;
                case TMDB: return tmdbLabels().length;
                case STREMIO: return Math.min(PICK_VISIBLE, stremioRows.size());
                case LANGUAGES: return Math.min(PICK_VISIBLE, langRows.size());
                case HIDDEN: return Math.min(PICK_VISIBLE, hiddenRows.size());
                case APPS_OFF: return Math.min(PICK_VISIBLE, appsOffRows.size());
                case TORRENTIO: return 3;
                case ADDED_KEYS: return addedLabels().length;
                case LEARN: return 1;
                case TOR_LANGS: return Math.min(PICK_VISIBLE, torLangRows.size());
                case HOME_CHOICE: return 3;
                case HOME_SETTINGS: return Math.min(PICK_VISIBLE, settingLabels().length);
                case PICKER: return Math.min(PICK_VISIBLE, pickLabels.size());
                case SHELF_EDIT: return Math.min(PICK_VISIBLE, shelfRows.size());
                default: return Prefs.KEYS.length + 1;      // the remote buttons, then "Added buttons"
            }
        }

        @Override protected void onDraw(Canvas c) {
            if (w == 0) return;
            currentNight = Prefs.theme().equals("light") ? 0 : Prefs.theme().equals("dark") ? 1
                    : (HomeData.get(getContext()).isNight(System.currentTimeMillis()) ? 1 : 0);
            title.setColor(look.ink(0xFFFFFFFF, currentNight));
            subtitle.setColor(look.ink(0x99FFFFFF, currentNight));
            version.setColor(look.ink(0x66FFFFFF, currentNight));
            header.setColor(look.ink(0x73FFFFFF, currentNight));
            cellLabel.setColor(look.ink(0xD9FFFFFF, currentNight));
            cellValue.setColor(look.ink(0xD9FFFFFF, currentNight));
            rowSub.setColor(look.ink(0x8CFFFFFF, currentNight));
            notice.setColor(look.ink(0xB3FFFFFF, currentNight));
            warn.setColor(look.warnInk(currentNight));
            long now = System.nanoTime();
            float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (now - lastNanos) / 1e9f);
            lastNanos = now;
            for (int i = 0; i < pop.length; i++) {
                pop[i].target = i == focus && i < rowCount() ? 1 : 0;
                pop[i].step(dt);
            }
            long up = SystemClock.uptimeMillis();
            float seconds = (up - startedAt) / 1000f;
            if (up - lastSky >= 100) {
                lastSky = up;
                scene.advance(seconds);
            }
            tmp.set(0, 0, w, h);
            if (!look.background(c, tmp, currentNight)) scene.drawBackground(c);

            // On the left: big symbol, title, description.
            float cx = 500 * k;
            Symbols.draw(getContext(), c, "sparkles", cx, 400 * k, 150 * k, look.ink(0xEBFFFFFF, currentNight));
            c.drawText("Sky Home Launcher", cx, 612 * k, title);
            c.drawText(Ui.tr("Your TV's home screen and screensaver"), cx, 660 * k, subtitle);
            c.drawText(versionName(getContext()), cx, 704 * k, version);
            c.drawText(Ui.tr("A fork of Free My TV"), cx, 730 * k, version);
            if (screen == MAIN) {
                float ly = drawComparison(c, 760 * k);
                drawNotices(c, ly + 30 * k);
            }

            if (screen == MAIN) drawMain(c, seconds); else if (screen == HOME_CHOICE) drawChoice(c); else if (screen == PICKER) drawPicker(c); else if (screen == SHELF_EDIT) drawShelfEditor(c); else if (screen == TRAKT) drawTrakt(c); else if (screen == TMDB) drawTmdb(c); else if (screen == STREMIO) drawStremio(c); else if (screen == LANGUAGES) drawLanguages(c); else if (screen == HIDDEN) drawHidden(c); else if (screen == APPS_OFF) drawAppsOff(c); else if (screen == TORRENTIO) drawTorrentio(c); else if (screen == TOR_LANGS) drawTorLangs(c); else if (screen == ADDED_KEYS) drawAdded(c); else if (screen == LEARN) drawLearn(c); else if (screen == INTEGRATIONS) drawIntegrations(c); else if (screen == POWER) drawPower(c); else drawList(c);
        }

        private void drawMain(Canvas c, float seconds) {
            boolean working = busy();
            String busyText = busyText();
            String mode = freeActive() ? "Sky Home Launcher" : Ui.tr("Original");
            String[] labels = {Ui.tr("Home Screen"), Ui.tr("Screensaver"),
                    Ui.tr("Home Settings"), Ui.tr("Shelf"), Ui.tr("Top Buttons"),
                    Ui.tr("Remote Buttons"), Ui.tr("Trakt"), Ui.tr("TMDB"), Ui.tr("Stremio"), Ui.tr("Integrations"),
                    Ui.tr("Sleep Timer"), Ui.tr("TV Settings"), Ui.tr("Restart")};
            String[] values = {working ? "" : mode, Ui.tr("Preview"), "", "", "", "", traktStatus(), tmdbStatus(),
                    stremioStatus(), integrationsStatus(), sleepStatus(), "", ""};
            for (int i = 0; i < labels.length; i++) {
                float y = (MAIN_TOP + i * (ROW_H + MAIN_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + ROW_H * k);
                float f = drawRowBackground(c, i);
                boolean off = disabledRow(i);
                int ink = blend(off ? 0x73FFFFFF : 0xF2FFFFFF, off ? 0x731C1C1E : INK, f);
                rowLabel.setColor(ink);
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(labels[i], rowRect.left + 30 * k, base, rowLabel);
                float right = rowRect.right - 30 * k;
                if (off) {
                    // Nothing to open: no chevron, and the reason where the value goes (ellipsized so a long
                    // reason, e.g. "Needs Sky Home Launcher mode…", never runs into the row's label).
                    rowValue.setColor(blend(0x73FFFFFF, 0x731C1C1E, f));
                    float maxWidth = right - (rowRect.left + 30 * k) - rowLabel.measureText(labels[i]) - 20 * k;
                    CharSequence shownOff = TextUtils.ellipsize(values[i], new TextPaint(rowValue), Math.max(0, maxWidth), TextUtils.TruncateAt.END);
                    c.drawText(shownOff, 0, shownOff.length(), right, base, rowValue);
                } else if (i >= 2 && i != RESTART_ROW) {
                    float cw = Symbols.width(getContext(), "chevron_right", 22 * k);
                    Symbols.draw(getContext(), c, "chevron_right", right - cw / 2, rowRect.centerY(), 22 * k, blend(0x66FFFFFF, 0x991C1C1E, f));
                    if (!values[i].isEmpty()) {
                        rowValue.setColor(blend(0x8CFFFFFF, 0x8C1C1C1E, f));
                        c.drawText(values[i], right - cw - 14 * k, base, rowValue);
                    }
                } else if (i == 0 && working) {
                    // Switching: the current step in place of the value, with the spinning arc.
                    String text = busyText.isEmpty() ? Ui.tr("Starting…") : busyText;
                    rowValue.setColor(blend(0xB3FFFFFF, 0xB31C1C1E, f));
                    String shown = TextUtils.ellipsize(text, new TextPaint(rowValue), 470 * k, TextUtils.TruncateAt.END).toString();
                    c.drawText(shown, right, base, rowValue);
                    float sx = right - rowValue.measureText(shown) - 26 * k;
                    spinner.setColor(blend(0xFFFFFFFF, INK, f));
                    Ui.spinner(c, sx, rowRect.centerY(), 12 * k, seconds * 1.3f, spinner);
                } else {
                    rowValue.setColor(blend(0x8CFFFFFF, 0x8C1C1C1E, f));
                    c.drawText(values[i], right, base, rowValue);
                }
            }

            float y = (MAIN_TOP + labels.length * (ROW_H + MAIN_GAP) + 30) * k;
            String error = state.optString("error");
            if (working && busyText.equals(Ui.tr("Accept “Allow USB debugging?” on the TV (tick “Always allow”)."))) {
                c.drawText(Ui.tr("On the TV: tick “Always allow” and press OK."),
                        LIST_X * k + 30 * k, y, warn);
                y += 46 * k;
            } else if (!working && !error.isEmpty()) {
                // The whole message, on up to five lines: some say what to do (the Wi-Fi one, #14).
                int width = Math.round(760 * k);
                if (errorLayout == null || !error.equals(errorShown) || errorLayout.getWidth() != width) {
                    errorShown = error;
                    errorLayout = StaticLayout.Builder.obtain(error, 0, error.length(), new TextPaint(warn), width)
                            .setMaxLines(5).setEllipsize(TextUtils.TruncateAt.END).build();
                }
                c.save();
                c.translate(LIST_X * k + 30 * k, y - errorLayout.getLineBaseline(0));
                errorLayout.draw(c);
                c.restore();
                y += errorLayout.getLineBaseline(errorLayout.getLineCount() - 1) - errorLayout.getLineBaseline(0) + 46 * k;
            }
        }

        /** The picks' status, and the credits TMDB and Watchmode ask for (on the TMDB screen). */
        private float drawRecs(Canvas c, float y) {
            float x = LIST_X * k + 30 * k;
            c.drawText(Ui.tr("RECOMMENDATIONS"), x, y, header);
            String line;
            String error = recs.optString("error");
            int count = recs.optInt("count");
            if (!hasKey) line = Ui.tr("No TMDB key: set one in Sky Home Launcher › Integrations.");
            else if (!Prefs.picksOn()) line = Prefs.picksStyleOffForMemory() ? Ui.tr("Off by default: this TV is short of memory.") : Ui.tr("Off: the home screen shows no picks.");
            else if (error.equals(Recs.NO_KNOWN_APPS)) line = Ui.tr("None of the TV's streaming apps can give picks.");
            else if (error.equals(Recs.NO_APPS)) line = Ui.trf("TMDB has none of the TV's apps in {region}.", "region", Regions.name(Recs.region()));
            else if (!error.isEmpty()) line = Ui.trf("Error: {error}.", "error", error);
            else if (count > 0 && !Prefs.recommendFromApps()) line = Ui.trf("On · {n} titles, a new list every day.", "n", count);
            else if (count > 0) line = Ui.trf("On · {n} titles from the TV's apps, a new list every day.", "n", count);
            else line = Ui.tr("Fetching titles…");
            c.drawText(TextUtils.ellipsize(line, notice, 790 * k, TextUtils.TruncateAt.END).toString(), x, y + 42 * k, notice);
            version.setTextAlign(Paint.Align.LEFT);
            c.drawText(Ui.tr("This product uses the TMDB API but is not endorsed or certified by TMDB."), x, y + 78 * k, version);
            if (!Prefs.watchmodeKey().isEmpty()) {
                c.drawText(Ui.tr("Links to the titles by Watchmode."), x, y + 108 * k, version);
            }
            version.setTextAlign(Paint.Align.CENTER);
            return y + (Prefs.watchmodeKey().isEmpty() ? 78 : 108) * k;
        }

        /** List row: no background; focused, white with a shadow (the Apple TV focus). Returns how focused it is. */
        private float drawRowBackground(Canvas c, int i) {
            float f = Ui.clamp(pop[i].value);
            if (f > 0.01f) {
                float s = 1 + 0.02f * f;
                float cx = rowRect.centerX(), cy = rowRect.centerY();
                tmp.set(cx - rowRect.width() * s / 2, cy - rowRect.height() * s / 2, cx + rowRect.width() * s / 2, cy + rowRect.height() * s / 2);
                float r = 18 * k * s;
                look.rowFocus(c, tmp, r, f, currentNight);
                rowRect.set(tmp);
            }
            return f;
        }

        private float drawComparison(Canvas c, float y) {
            float x = LEFT_X * k, colA = LEFT_R * k - 220 * k, colB = LEFT_R * k, gap = 16 * k;
            c.drawText(Ui.tr("COMPARISON"), x, y, header);
            cellValue.setColor(look.inkAlpha(0x73FFFFFF, currentNight));
            drawEllipsizedRight(c, Ui.tr("Original"), colA, y, colA - x - gap, cellValue);
            drawEllipsizedRight(c, "Sky Home Launcher", colB, y, colB - colA - gap, cellValue);
            cellValue.setColor(look.ink(0xD9FFFFFF, currentNight));
            JSONObject metrics = state.optJSONObject("metrics");
            JSONObject a = metrics == null ? null : metrics.optJSONObject(HomeMode.ORIGINAL);
            JSONObject b = metrics == null ? null : metrics.optJSONObject(HomeMode.FREE);
            String[][] rows = {{Ui.tr("Home button"), "homeMs"}, {Ui.tr("Free memory"), "memAvailableMB"},
                    {Ui.tr("Home screen memory"), "homePssMB"}};
            for (int i = 0; i < rows.length; i++) {
                float base = y + (46 + 40 * i) * k;
                c.drawText(rows[i][0], x, base, cellLabel);
                c.drawText(metric(a, rows[i][1]), colA, base, cellValue);
                c.drawText(metric(b, rows[i][1]), colB, base, cellValue);
            }
            return y + (46 + 40 * rows.length) * k;
        }

        /** Right-aligned text, shortened with an ellipsis if it wouldn't fit in maxWidth (so two columns never overlap). */
        private static void drawEllipsizedRight(Canvas c, String text, float right, float y, float maxWidth, Paint p) {
            CharSequence out = TextUtils.ellipsize(text, new TextPaint(p), maxWidth, TextUtils.TruncateAt.END);
            c.drawText(out, 0, out.length(), right, y, p);
        }

        private float drawNotices(Canvas c, float y) {
            float x = LEFT_X * k;
            c.drawText(Ui.tr("NOTICES"), x, y, header);
            JSONArray notices = state.optJSONArray("notices");
            int shown = 0;
            for (int i = 0; notices != null && i < notices.length() && shown < 2; i++) {
                JSONObject o = notices.optJSONObject(i);
                if (o == null) continue;
                String line = ago(o.optLong("at")) + " · " + o.optString("text");
                c.drawText(TextUtils.ellipsize(line, notice, (LEFT_R - LEFT_X) * k, TextUtils.TruncateAt.END).toString(), x, y + (42 + 38 * shown) * k, notice);
                shown++;
            }
            if (shown == 0) c.drawText(Ui.tr("None yet."), x, y + 42 * k, notice);
            return y + (42 + 38 * Math.max(1, shown)) * k;
        }

        /** Home screen choice: Sky Home Launcher or the original, with a check on the one in use. */
        private void drawChoice(Canvas c) {
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, headingColor());
            c.drawText(Ui.tr("Home Screen"), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            String[][] options = {{"Sky Home Launcher", Ui.tr("No ads, with the new screensaver")},
                    {Ui.tr("Original"), Ui.tr("The TV's factory home screen")}};
            int current = freeActive() ? 0 : 1;
            options = new String[][] {options[0], options[1], {Ui.tr("Apps turned off"), Ui.tr("Keep the apps you want on")}};
            for (int i = 0; i < 3; i++) {
                float y = (LIST_TOP + 20 + i * (CHOICE_H + ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + CHOICE_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(0xF2FFFFFF, INK, f));
                rowSub.setColor(blend(0x8CFFFFFF, 0x991C1C1E, f));
                c.drawText(options[i][0], rowRect.left + 30 * k, rowRect.top + 44 * k, rowLabel);
                c.drawText(options[i][1], rowRect.left + 30 * k, rowRect.top + 80 * k, rowSub);
                if (i == 2) {
                    float cw = Symbols.width(getContext(), "chevron_right", 22 * k);
                    Symbols.draw(getContext(), c, "chevron_right", rowRect.right - 30 * k - cw / 2, rowRect.centerY(), 22 * k, blend(0x99FFFFFF, INK, f));
                } else if (i == current) {
                    float cw = Symbols.width(getContext(), "check", 26 * k);
                    Symbols.draw(getContext(), c, "check", rowRect.right - 30 * k - cw / 2, rowRect.centerY(), 26 * k, blend(0xFFFFFFFF, INK, f));
                }
            }
            c.drawText(Ui.tr("Switching takes a few seconds; the screen blinks while it measures."), LIST_X * k + 30 * k,
                    (LIST_TOP + 20 + 3 * (CHOICE_H + ROW_GAP) + 40) * k, notice);
        }

        private int blend(int a, int b, float f) {
            a = look.inkAlpha(a, currentNight);
            b = look.inkFocusedAlpha(b, currentNight);
            int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
            int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
            return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                    | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
        }

        private int headingColor() {
            return look.ink(0xFFFFFFFF, currentNight);
        }

        private static String metric(JSONObject m, String key) {
            if (m == null || !m.has(key)) return "—";
            long v = m.optLong(key);
            if (key.equals("homeMs")) return Ui.trf("{n} ms", "n", v);
            if (key.equals("memAvailableMB") && v >= 1024) return Ui.trf("{n} GB", "n", String.format(Ui.locale(), "%.2f", v / 1024.0));
            return Ui.trf("{n} MB", "n", v);
        }

        private static String ago(long at) {
            long minutes = Math.max(0, (System.currentTimeMillis() - at) / 60_000);
            if (minutes < 1) return Ui.tr("now");
            if (minutes < 60) return Ui.trf("{n} min ago", "n", minutes);
            if (minutes < 48 * 60) return Ui.trf("{n} h ago", "n", minutes / 60);
            return Ui.plural("plural|one:{n} day ago|other:{n} days ago", minutes / 1440);
        }

        private static String versionName(Context c) {
            try {
                return "Sky Home Launcher " + c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                return "Sky Home Launcher";
            }
        }

        boolean onKey(KeyEvent e) {
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            if (screen == LEARN && RemoteKeys.recordable(e)) {      // a button that reached the screen (the service sees the others)
                if (down && e.getRepeatCount() == 0) {
                    finishLearn(e.getKeyCode(), e.getScanCode());
                    invalidate();
                }
                return true;
            }
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (down && focus > 0) focus--;
                    else if (down && scrolling() && pickScroll > 0) pickScroll--;
                    invalidate();
                    spoken.focusMoved();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down && focus < rowCount() - 1) focus++;
                    else if (down && scrolling() && pickScroll + rowCount() < listSize()) pickScroll++;
                    invalidate();
                    spoken.focusMoved();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down && screen == SHELF_EDIT) {
                        moveShelfApp(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                        invalidate();
                    } else if (down && (screen == HOME_SETTINGS || screen == REMOTE_KEYS || screen == TMDB || screen == STREMIO || screen == LANGUAGES || screen == HIDDEN || screen == APPS_OFF || screen == TORRENTIO || screen == TOR_LANGS || screen == ADDED_KEYS || screen == POWER)) {
                        adjust(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                        invalidate();
                    }
                    return true;
                case KeyEvent.KEYCODE_PAGE_UP:
                case KeyEvent.KEYCODE_CHANNEL_UP:
                case KeyEvent.KEYCODE_PAGE_DOWN:
                case KeyEvent.KEYCODE_CHANNEL_DOWN:
                    if (!scrolling()) return false;
                    if (down) {
                        boolean up = e.getKeyCode() == KeyEvent.KEYCODE_PAGE_UP || e.getKeyCode() == KeyEvent.KEYCODE_CHANNEL_UP;
                        pickScroll = Math.max(0, Math.min(listSize() - rowCount(), pickScroll + (up ? -1 : 1) * Math.max(1, rowCount() - 1)));
                        invalidate();
                        spoken.focusMoved();
                    }
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
                    if (screen == MAIN) return false;   // leaves the settings
                    if (down) {
                        if (e.getRepeatCount() == 0) backDown = true;
                    } else {
                        if (backDown && !e.isCanceled()) {
                            if (screen == PICKER && pickFor == PICK_REGION) {
                                focus = 0;
                                screen = TMDB;
                            } else if (screen == SHELF_EDIT && editingRows) {
                                focus = TMDB_CATALOGS;
                                screen = TMDB;
                            } else if (screen == TMDB) {
                                focus = TMDB_ROW;
                                screen = MAIN;
                            } else if (screen == STREMIO) {
                                focus = STREMIO_ROW;
                                screen = MAIN;
                            } else if (screen == LANGUAGES) {
                                focus = TMDB_LANGS;
                                screen = TMDB;
                            } else if (screen == HIDDEN) {
                                focus = TMDB_HIDDEN;
                                screen = TMDB;
                            } else if (screen == APPS_OFF) {
                                focus = 2;
                                screen = HOME_CHOICE;
                            } else if (screen == ADDED_KEYS) {
                                focus = Prefs.KEYS.length;
                                screen = REMOTE_KEYS;
                            } else if (screen == LEARN) {
                                Prefs.stopLearning();
                                openAdded(learnedCount());
                            } else if (screen == PICKER && pickFor - 1 >= Prefs.KEYS.length) {
                                openAdded(pickFor - 1 - Prefs.KEYS.length);
                            } else if (screen == TORRENTIO) {
                                focus = 1;
                                screen = STREMIO;
                            } else if (screen == TOR_LANGS) {
                                focus = 0;
                                screen = TORRENTIO;
                            } else if (screen == PICKER && (pickFor == PICK_CITY || pickFor == PICK_LANGUAGE)) {
                                pickScroll = 0;
                                focus = pickFor == PICK_CITY ? CITY_ROW : 0;
                                screen = HOME_SETTINGS;
                            } else if (screen == PICKER && pickFor == PICK_SLEEP) {
                                pickScroll = 0;
                                focus = powerFocus(P_SLEEP);
                                screen = POWER;
                            } else if (screen == PICKER && pickFor == PICK_PLACE) {
                                pickScroll = 0;
                                focus = powerFocus(P_WHERE);
                                screen = POWER;
                            } else if (screen == PICKER) {
                                focus = pickFor - 1;
                                screen = REMOTE_KEYS;
                            } else if (screen == POWER) {
                                focus = SLEEP_ROW;
                                screen = MAIN;
                            } else if (screen == TRAKT) {
                                Trakt.get(getContext()).cancelLogin();
                                focus = TRAKT_ROW;
                                screen = MAIN;
                            } else if (screen == INTEGRATIONS) {
                                IntegrationsServer.stop();
                                integrationsUrl = "";
                                integrationsError = "";
                                focus = INTEGRATIONS_ROW;
                                screen = MAIN;
                            } else {
                                focus = screen == HOME_SETTINGS ? 2 : screen == SHELF_EDIT ? (editingTop ? 4 : 3)
                                        : screen == REMOTE_KEYS ? 5 : 0;
                                screen = MAIN;
                            }
                        }
                        backDown = false;
                    }
                    invalidate();
                    spoken.focusMoved();
                    return true;
                default:
                    return false;
            }
        }

        // MARK: - Trakt

        /** The main menu's value: whether Trakt is connected. */
        private String traktStatus() {
            Trakt t = Trakt.get(getContext());
            if (!Trakt.configured()) return Ui.tr("Needs a Client ID");
            if (t.connected()) return Ui.tr("Connected");
            return t.needsReconnect() ? Ui.tr("Reconnect") : Ui.tr("Not connected");
        }

        /** The one button of the Trakt screen. */
        private String traktAction() {
            Trakt t = Trakt.get(getContext());
            if (t.loginState() != Trakt.IDLE) return Ui.tr("Cancel");
            if (t.connected()) return Ui.tr("Disconnect");
            return t.needsReconnect() ? Ui.tr("Reconnect") : Ui.tr("Connect");
        }

        private void traktPressed() {
            Trakt t = Trakt.get(getContext());
            if (t.loginState() != Trakt.IDLE) t.cancelLogin();
            else if (t.connected()) t.disconnect();
            else t.startLogin();
        }

        private final java.util.Map<String, StaticLayout> wraps = new java.util.HashMap<>();

        /** `text` wrapped to `width`, made once per text (the screen draws it every frame). */
        private StaticLayout wrapped(String text, int width) {
            String key = width + ":" + text;
            StaticLayout l = wraps.get(key);
            if (l == null) {
                l = StaticLayout.Builder.obtain(text, 0, text.length(), notice, width).build();
                wraps.put(key, l);
            }
            return l;
        }

        private float drawWrapped(Canvas c, String text, float x, float y, float gap) {
            return drawWrapped(c, text, x, y, gap, 790 * k);
        }

        // A QR code of the given address or link, so a phone's camera needs no typing.
        private final Paint qrInk = new Paint(), qrBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private String qrFor = "";
        private android.graphics.Path qrPath;
        private int qrModules;

        private void drawQr(Canvas c, String text, float x, float top) {
            if (!text.equals(qrFor)) {
                qrFor = text;
                boolean[][] m = Qr.encode(text);
                qrPath = null;
                if (m != null) {
                    qrModules = m.length;
                    qrPath = new android.graphics.Path();
                    for (int y = 0; y < m.length; y++) for (int i = 0; i < m.length; i++) if (m[y][i]) qrPath.addRect(i, y, i + 1, y + 1, android.graphics.Path.Direction.CW);
                }
            }
            if (qrPath == null) return;
            float side = 330 * k, module = side / (qrModules + 8);      // a four-module quiet zone, as the standard asks
            qrBg.setColor(0xFFFFFFFF);
            tmp.set(x, top, x + side, top + side);
            c.drawRoundRect(tmp, 16 * k, 16 * k, qrBg);
            qrInk.setColor(0xFF000000);
            c.save();
            c.translate(x + 4 * module, top + 4 * module);
            c.scale(module, module);
            c.drawPath(qrPath, qrInk);
            c.restore();
            drawWrapped(c, Ui.tr("Or scan this with your phone's camera."), x + side + 32 * k, top + side / 2 - 12 * k, 0, LIST_R * k - (x + side + 32 * k));
        }

        /** Draws wrapped text with its first baseline at `y`; returns where the next text's first baseline goes, `gap` below this one. */
        private float drawWrapped(Canvas c, String text, float x, float y, float gap, float width) {
            StaticLayout l = wrapped(text, Math.round(width));
            c.save();
            c.translate(x, y - l.getLineBaseline(0));
            l.draw(c);
            c.restore();
            return y + (l.getLineBaseline(l.getLineCount() - 1) - l.getLineBaseline(0)) + 34 * k + gap;
        }

        private String traktError(int error) {
            switch (error) {
                case Trakt.ERR_NETWORK: return Ui.tr("Couldn't reach Trakt. Check the network and try again.");
                case Trakt.ERR_DENIED: return Ui.tr("Trakt said no: the code was denied.");
                case Trakt.ERR_EXPIRED: return Ui.tr("The code expired. Press Connect for a new one.");
                case Trakt.ERR_REFUSED: return Ui.tr("Trakt refused the Client ID: check it in Sky Home Launcher › Integrations.");
                default: return "";
            }
        }

        private void drawTrakt(Canvas c) {
            Trakt t = Trakt.get(getContext());
            float x = LIST_X * k + 30 * k;
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, headingColor());
            c.drawText(Ui.tr("Trakt"), x, (LIST_TOP - 24) * k, heading);
            float y0 = (LIST_TOP + 20) * k;
            rowRect.set(LIST_X * k, y0, LIST_R * k, y0 + LIST_ROW_H * k);
            float f = drawRowBackground(c, 0);
            rowLabel.setColor(blend(0xF2FFFFFF, INK, f));
            c.drawText(traktAction(), rowRect.left + 30 * k, rowRect.centerY() + Ui.halfCap(rowLabel), rowLabel);
            rowValue.setColor(blend(0xB3FFFFFF, 0xB31C1C1E, f));
            c.drawText(traktStatus(), rowRect.right - 30 * k, rowRect.centerY() + Ui.halfCap(rowLabel), rowValue);

            float y = (LIST_TOP + 20 + LIST_ROW_H + 64) * k;
            int state = t.loginState();
            if (state == Trakt.WAITING) {
                y = drawWrapped(c, Ui.trf("On your phone or computer, go to {url} and enter this code:", "url", t.loginUrl()), x, y, 30 * k);
                Paint code = Ui.text(Ui.displaySemibold, 92 * k, headingColor());
                code.setLetterSpacing(0.14f);
                c.drawText(t.loginCode(), x, y + 70 * k, code);
                long left = t.loginSecondsLeft();
                c.drawText(Ui.trf("Expires in {time}", "time", String.format(Ui.locale(), "%d:%02d", left / 60, left % 60)),
                        x, y + 134 * k, notice);
                drawQr(c, "https://trakt.tv/activate/" + t.loginCode(), x, y + 178 * k);
            } else if (state == Trakt.STARTING) {
                c.drawText(Ui.tr("Asking Trakt for a code…"), x, y, notice);
            } else {
                String error = traktError(t.loginError());
                if (!error.isEmpty()) y = drawWrapped(c, error, x, y, 24 * k);
                else if (t.needsReconnect() && !t.connected()) y = drawWrapped(c, Ui.tr("Trakt asked you to sign in again."), x, y, 24 * k);
                if (t.connected()) {
                    y = drawWrapped(c, Ui.tr("Continue Watching and Watchlist show under the cards on the home screen."), x, y, 14 * k);
                    drawWrapped(c, Ui.tr("Sky Home Launcher only reads your Trakt lists."), x, y, 0);
                } else {
                    y = drawWrapped(c, Ui.tr("Connect your Trakt account to see Continue Watching and Watchlist under the home screen's cards."), x, y, 14 * k);
                    drawWrapped(c, Ui.tr("You sign in on your phone or computer with a short code. Sky Home Launcher only reads your lists."), x, y, 0);
                }
            }
        }

        // MARK: - Integrations (Sky Home Launcher > Integrations): set the TMDB key, Watchmode key and Trakt Client ID from a phone

        /** The main menu's value: how many of the three are set. */
        private String integrationsStatus() {
            int n = (Recs.hasKey(getContext()) ? 1 : 0) + (!Prefs.watchmodeKey().isEmpty() ? 1 : 0) + (Trakt.configured() ? 1 : 0);
            return Ui.trf("{n} of 3 set", "n", n);
        }

        /** The one button of the Integrations screen. */
        private String integrationsAction() {
            return IntegrationsServer.running() ? Ui.tr("Stop") : Ui.tr("Set up on your phone");
        }

        private void integrationsPressed() {
            if (IntegrationsServer.running()) {
                IntegrationsServer.stop();
                integrationsUrl = "";
                integrationsError = "";
                return;
            }
            try {
                integrationsUrl = IntegrationsServer.start();
                integrationsError = "";
            } catch (java.io.IOException e) {
                integrationsUrl = "";
                integrationsError = Ui.tr("Couldn't start. Check the TV's Wi-Fi or network connection.");
                AppLog.w("integrations server: " + e.getMessage());
            }
        }

        private void drawIntegrations(Canvas c) {
            float x = LIST_X * k + 30 * k;
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, headingColor());
            c.drawText(Ui.tr("Integrations"), x, (LIST_TOP - 24) * k, heading);
            float y0 = (LIST_TOP + 20) * k;
            rowRect.set(LIST_X * k, y0, LIST_R * k, y0 + LIST_ROW_H * k);
            float f = drawRowBackground(c, 0);
            rowLabel.setColor(blend(0xF2FFFFFF, INK, f));
            c.drawText(integrationsAction(), rowRect.left + 30 * k, rowRect.centerY() + Ui.halfCap(rowLabel), rowLabel);

            float y = (LIST_TOP + 20 + LIST_ROW_H + 64) * k;
            y = drawWrapped(c, Ui.trf("TMDB key: {status}", "status", Recs.hasKey(getContext()) ? Ui.tr("Set") : Ui.tr("Not set")), x, y, 10 * k);
            y = drawWrapped(c, Ui.trf("Watchmode key: {status}", "status", Prefs.watchmodeKey().isEmpty() ? Ui.tr("Not set") : Ui.tr("Set")), x, y, 10 * k);
            y = drawWrapped(c, Ui.trf("Trakt Client ID: {status}", "status", Trakt.configured() ? Ui.tr("Set") : Ui.tr("Not set")), x, y, 30 * k);

            if (!integrationsUrl.isEmpty()) {
                y = drawWrapped(c, Ui.trf("On your phone, go to {url}", "url", integrationsUrl), x, y, 30 * k);
                drawQr(c, integrationsUrl, x, y);
            } else if (!integrationsError.isEmpty()) {
                drawWrapped(c, integrationsError, x, y, 0);
            } else {
                drawWrapped(c, Ui.tr("Scan the QR or open the address on your phone to set these without typing them here."), x, y, 0);
            }
        }

        // MARK: - Sleep Timer

        /** The main menu's value: the time left, Off, or why the row is unavailable. */
        private String sleepStatus() {
            String why = Capabilities.missing(getContext(), Capabilities.NEED_ADB);
            if (why != null) return why;
            return sleepLeft == 0 ? Ui.tr("Off") : Ui.trf("{time} left", "time", SleepTimer.duration(sleepLeft));
        }

        /** Position only while the pill is actually wanted: Show Time Left off, or ADB unavailable, hides it. */
        private int[] powerRows() {
            return Prefs.sleepShown() ? new int[] {P_SLEEP, P_SHOW, P_WHERE} : new int[] {P_SLEEP, P_SHOW};
        }

        private int powerRow() {
            int[] rows = powerRows();
            return rows[Math.min(focus, rows.length - 1)];
        }

        private int powerFocus(int id) {
            int[] rows = powerRows();
            for (int i = 0; i < rows.length; i++) if (rows[i] == id) return i;
            return 0;
        }

        private String[] powerLabels() {
            int[] rows = powerRows();
            String[] out = new String[rows.length];
            for (int i = 0; i < rows.length; i++) {
                switch (rows[i]) {
                    case P_SLEEP: out[i] = Ui.tr("Sleep Timer"); break;
                    case P_SHOW: out[i] = Ui.tr("Show Time Left"); break;
                    default: out[i] = Ui.tr("Position");
                }
            }
            return out;
        }

        private String[] powerValues() {
            int[] rows = powerRows();
            String[] out = new String[rows.length];
            for (int i = 0; i < rows.length; i++) {
                if (rows[i] == P_WHERE && disabledRow(i)) {
                    out[i] = Capabilities.missing(getContext(), Capabilities.NEED_OVERLAY);
                    continue;
                }
                switch (rows[i]) {
                    case P_SLEEP: out[i] = sleepLeft == 0 ? Ui.tr("Off") : Ui.trf("{time} left", "time", SleepTimer.duration(sleepLeft)); break;
                    case P_SHOW: out[i] = onOff(Prefs.sleepShown()); break;
                    default: out[i] = SleepOverlay.placeName(Prefs.sleepPlace());
                }
            }
            return out;
        }

        /** OK on a Sleep Timer row: its list of choices, or (on Show Time Left) the other value. */
        private void activatePower() {
            if (disabledRow(focus)) return;
            switch (powerRow()) {
                case P_SLEEP:
                    openPicker(PICK_SLEEP);
                    return;
                case P_WHERE:
                    openPicker(PICK_PLACE);
                    return;
                default:
                    adjust(1);
            }
        }

        private void drawPower(Canvas c) {
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, headingColor());
            c.drawText(Ui.tr("Sleep Timer"), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            String[] labels = powerLabels(), values = powerValues();
            int rows = rowCount();
            for (int i = 0; i < rows; i++) {
                float y = (LIST_TOP + 20 + i * (LIST_ROW_H + LIST_ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k);
                float f = drawRowBackground(c, i);
                boolean off = disabledRow(i);
                rowLabel.setColor(blend(off ? 0x73FFFFFF : 0xF2FFFFFF, off ? 0x731C1C1E : INK, f));
                rowValue.setColor(blend(off ? 0x59FFFFFF : 0xB3FFFFFF, off ? 0x591C1C1E : 0xB31C1C1E, f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(labels[i], rowRect.left + 30 * k, base, rowLabel);
                c.drawText(values[i], rowRect.right - 30 * k, base, rowValue);
            }
            float hy = (LIST_TOP + 20 + rows * (LIST_ROW_H + LIST_ROW_GAP) + 36) * k;
            c.drawText(powerRow() == P_SHOW || disabledRow(focus) ? Ui.tr("OK or the arrows change the value.")
                    : Ui.tr("OK opens the list. ← → change the value."), LIST_X * k + 30 * k, hy, notice);
        }

        // MARK: - TMDB

        /** The main menu's value: the region, or why the row is greyed. */
        private String tmdbStatus() {
            return Recs.hasKey(getContext()) ? regionValue() : Ui.tr("Needs a TMDB key");
        }

        private String regionValue() {
            String r = Prefs.region();
            return r.equals("auto") ? Ui.tr("Automatic") + " · " + Regions.name(Recs.region()) : Regions.name(r);
        }

        private String[] tmdbLabels() {
            return new String[] {Ui.tr("Region"), Ui.tr("Picks carousel"), Ui.tr("Recommend from"), Ui.tr("Only titles I can stream"), Ui.tr("Include rent or buy"),
                    Ui.tr("Languages"), Ui.tr("Hidden services"), Ui.tr("Hide cartoons and anime"), Ui.tr("Catalogs")};
        }

        private String[] tmdbValues() {
            int on = 0;
            for (String key : Prefs.catalogRows()) if (Catalogs.title(key) != null) on++;
            return new String[] {regionValue(), picksStyleLabel(), Prefs.recommendFromApps() ? Ui.tr("My apps") : Ui.tr("Anything popular"),
                    onOff(Prefs.flatrateOnly()), onOff(Prefs.flatrateOnly() && Prefs.includeRentBuy()), languagesValue(), hiddenValue(),
                    onOff(Prefs.hideAnimation()), Ui.trf("{i} of {n}", "i", on, "n", Catalogs.ALL.length)};
        }

        /** "Picks carousel" row's value: Off, Cards (today's 3-card row) or Spotlight (the full-screen carousel). */
        private String picksStyleLabel() {
            String v = Prefs.picksStyle();
            return v.equals(Prefs.PICKS_SPOTLIGHT) ? Ui.tr("Spotlight") : v.equals(Prefs.PICKS_CARDS) ? Ui.tr("Cards") : Ui.tr("Off");
        }

        private void drawTmdb(Canvas c) {
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, headingColor());
            c.drawText(Ui.tr("TMDB"), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            String[] labels = tmdbLabels(), values = tmdbValues();
            for (int i = 0; i < labels.length; i++) {
                float y = (LIST_TOP + 20 + i * (LIST_ROW_H + LIST_ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k);
                float f = drawRowBackground(c, i);
                boolean off = disabledRow(i);
                rowLabel.setColor(blend(off ? 0x73FFFFFF : 0xF2FFFFFF, off ? 0x731C1C1E : INK, f));
                rowValue.setColor(blend(off ? 0x73FFFFFF : 0xB3FFFFFF, off ? 0x731C1C1E : 0xB31C1C1E, f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(labels[i], rowRect.left + 30 * k, base, rowLabel);
                c.drawText(TextUtils.ellipsize(values[i], new TextPaint(rowValue), 420 * k, TextUtils.TruncateAt.END).toString(),
                        rowRect.right - 30 * k, base, rowValue);
            }
            drawRecs(c, (LIST_TOP + 20 + labels.length * (LIST_ROW_H + LIST_ROW_GAP) + 70) * k);
        }

        // MARK: - Languages (Sky Home Launcher > TMDB)

        /** A language's name in the TV's language; Cantonese is "cn" on TMDB and has no two-letter ISO code. */
        private static String languageName(String code) {
            Locale l = code.equals("cn") ? Locale.forLanguageTag("yue") : new Locale(code);
            String name = l.getDisplayLanguage(Ui.locale());
            return name.isEmpty() ? code : name.substring(0, 1).toUpperCase(Ui.locale()) + name.substring(1);
        }

        /** The TMDB row's value: "All languages", or the chosen ones' names. */
        private String languagesValue() {
            List<String> codes = Prefs.languages();
            if (codes.isEmpty()) return Ui.tr("All languages");
            List<String> names = new ArrayList<>();
            for (String code : codes) names.add(languageName(code));
            return String.join(", ", names);
        }

        private boolean langChecked(int at) {
            String code = langRows.get(at)[0];
            return code.isEmpty() ? Prefs.languages().isEmpty() : Prefs.languages().contains(code);
        }

        /** "All languages" first, then every language TMDB can tell titles apart by, A to Z in the TV's language. */
        private void openLanguages() {
            langRows.clear();
            langRows.add(new String[] {"", Ui.tr("All languages")});
            List<String[]> all = new ArrayList<>();
            for (String code : Locale.getISOLanguages()) if (code.length() == 2) all.add(new String[] {code, languageName(code)});
            all.add(new String[] {"cn", languageName("cn")});
            java.text.Collator collator = java.text.Collator.getInstance(Ui.locale());
            java.util.Collections.sort(all, (a, b) -> collator.compare(a[1], b[1]));
            // The TV's own language and the ones most titles come in first, then the rest A to Z.
            List<String> first = new ArrayList<>(java.util.Arrays.asList(Ui.language.replaceAll("-.*", ""), "en", "hi", "es", "fr", "de", "ja", "ko", "zh", "pt", "ru", "it", "ta", "te"));
            for (String code : first) {
                for (int i = 0; i < all.size(); i++) {
                    if (all.get(i)[0].equals(code)) {
                        langRows.add(all.remove(i));
                        break;
                    }
                }
            }
            langRows.addAll(all);
            pickScroll = 0;
            focus = 0;
            screen = LANGUAGES;
            for (Spring s : pop) s.snap(0);
        }

        private void toggleLanguage() {
            String code = langRows.get(pickScroll + focus)[0];
            if (code.isEmpty()) {
                Prefs.setLanguages(new ArrayList<>());
                return;
            }
            List<String> codes = Prefs.languages();
            if (!codes.remove(code)) codes.add(code);
            Prefs.setLanguages(codes);
        }

        private void drawLanguages(Canvas c) {
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, headingColor());
            c.drawText(Ui.tr("Languages"), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            int rows = rowCount();
            for (int i = 0; i < rows; i++) {
                int at = pickScroll + i;
                float y = (LIST_TOP + 20 + i * (LIST_ROW_H + LIST_ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(0xF2FFFFFF, INK, f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(TextUtils.ellipsize(langRows.get(at)[1], new TextPaint(rowLabel), 620 * k, TextUtils.TruncateAt.END).toString(),
                        rowRect.left + 30 * k, base, rowLabel);
                if (langChecked(at)) {
                    float cw = Symbols.width(getContext(), "check", 26 * k);
                    Symbols.draw(getContext(), c, "check", rowRect.right - 30 * k - cw / 2, rowRect.centerY(), 26 * k, blend(0xFFFFFFFF, INK, f));
                }
            }
            c.drawText(Ui.tr("The language a title was made in. OK picks or drops one."), LIST_X * k + 30 * k,
                    (LIST_TOP + 20 + rows * (LIST_ROW_H + LIST_ROW_GAP) + 36) * k, notice);
        }

        // MARK: - Added buttons (Sky Home Launcher > Remote Buttons): the person presses a button and chooses what it opens

        private int learnedCount() {
            return Math.max(0, keyIds.length - Prefs.KEYS.length);
        }

        private void refreshKeyIds() {
            keyIds = Prefs.allKeys();
        }

        private void openAdded(int at) {
            refreshKeyIds();
            loadApps();
            screen = ADDED_KEYS;
            focus = Math.max(0, Math.min(at, addedLabels().length - 1));
            for (Spring s : pop) s.snap(0);
        }

        /** The added buttons, then the row that adds one (until there are as many as fit). */
        private String[] addedLabels() {
            List<String> l = new ArrayList<>();
            for (int i = Prefs.KEYS.length; i < keyIds.length; i++) l.add(Prefs.learnedName(keyIds[i]));
            if (learnedCount() < MAX_ADDED) l.add(Ui.tr("Add a button"));
            return l.toArray(new String[0]);
        }

        private String[] addedValues() {
            String[] v = new String[addedLabels().length];
            java.util.Arrays.fill(v, "");
            for (int i = 0; i < learnedCount(); i++) v[i] = appOf(keyIds[Prefs.KEYS.length + i]);
            return v;
        }

        private void drawAdded(Canvas c) {
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, headingColor());
            c.drawText(Ui.tr("Added buttons"), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            String[] labels = addedLabels(), values = addedValues();
            for (int i = 0; i < labels.length; i++) {
                float y = (LIST_TOP + 20 + i * (LIST_ROW_H + LIST_ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(0xF2FFFFFF, INK, f));
                rowValue.setColor(blend(0xB3FFFFFF, 0xB31C1C1E, f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(labels[i], rowRect.left + 30 * k, base, rowLabel);
                c.drawText(values[i], rowRect.right - 30 * k, base, rowValue);
            }
            drawWrapped(c, Ui.tr("Press a button on your remote to add it. It opens the app you pick, while Sky Home Launcher is on."),
                    LIST_X * k + 30 * k, (LIST_TOP + 20 + labels.length * (LIST_ROW_H + LIST_ROW_GAP) + 36) * k, 0);
        }

        private void startLearn() {
            Prefs.startLearning();
            HomeMode.ensureRemoteService(getContext(), 0);      // the service sees the buttons no app gets to see
            screen = LEARN;
            focus = 0;
            handler.postDelayed(learnPoll, 250);
        }

        private final Runnable learnPoll = new Runnable() {
            @Override public void run() {
                if (screen != LEARN || !running) return;
                int[] pressed = Prefs.takeLearnResult();
                if (pressed != null) {
                    finishLearn(pressed[0], pressed[1]);
                } else if (!Prefs.learning()) {         // nothing was pressed in time
                    openAdded(learnedCount());
                } else {
                    handler.postDelayed(this, 250);
                }
                invalidate();
            }
        };

        /** A button was pressed: it is added (or found again) and the person picks what it opens. */
        private void finishLearn(int keyCode, int scanCode) {
            Prefs.stopLearning();
            int id = Prefs.addLearned(keyCode, scanCode, keyName(keyCode, scanCode));
            refreshKeyIds();
            loadApps();
            openPicker(id - Prefs.LEARNED + Prefs.KEYS.length + 1);
        }

        /** "Button STEM 1" or "Button 114" for a key that has no name. */
        private static String keyName(int keyCode, int scanCode) {
            String name = KeyEvent.keyCodeToString(keyCode).replace("KEYCODE_", "").replace('_', ' ');
            if (keyCode == KeyEvent.KEYCODE_UNKNOWN || name.matches("\\d+")) name = String.valueOf(scanCode > 0 ? scanCode : keyCode);
            else if (scanCode > 0 && (name.startsWith("STEM") || name.startsWith("BUTTON") || name.startsWith("F"))) name += " (" + scanCode + ")";
            return Ui.trf("Button {n}", "n", name);
        }

        private void drawLearn(Canvas c) {
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, headingColor());
            c.drawText(Ui.tr("Add a button"), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            rowRect.set(LIST_X * k, (LIST_TOP + 20) * k, LIST_R * k, (LIST_TOP + 20 + 140) * k);
            float pulse = 0.5f + 0.5f * (float) Math.sin(SystemClock.uptimeMillis() / 260.0);
            Paint big = Ui.text(Ui.displaySemibold, 40 * k, look.ink(0xF2FFFFFF, currentNight));
            c.drawText(Ui.tr("Press the button on your remote"), LIST_X * k + 30 * k, rowRect.top + 70 * k, big);
            Paint dots = new Paint(Paint.ANTI_ALIAS_FLAG);
            dots.setColor(Ui.alpha(look.ink(0xFFFFFFFF, currentNight), 0.35f + 0.5f * pulse));
            c.drawCircle(LIST_X * k + 46 * k, rowRect.top + 130 * k, 10 * k, dots);
            drawWrapped(c, Ui.tr("The arrows, OK, Back, Home and volume can't be added. Back cancels."), LIST_X * k + 30 * k,
                    (LIST_TOP + 20 + 200) * k, 0);
            postInvalidateDelayed(80);
        }

        // MARK: - Torrentio addon (Sky Home Launcher > Stremio)

        private static final String TORRENTIO_KEY = "torrentio";

        private String torrentioLanguageValue() {
            List<String> names = Prefs.torrentioLanguages();
            if (names.isEmpty()) return Ui.tr("All languages");
            List<String> shown = new ArrayList<>();
            for (String n : names) shown.add(Torrentio.label(n));
            return String.join(", ", shown);
        }

        private String torrentioSummary() {
            return torrentioLanguageValue() + " · " + Torrentio.sizeLabel(Prefs.torrentioSize());
        }

        private String[] torrentioLabels() {
            return new String[] {Ui.tr("Language"), Ui.tr("Size limit"), Ui.tr("Add to Stremio")};
        }

        private String[] torrentioValues() {
            return new String[] {torrentioLanguageValue(), Torrentio.sizeLabel(Prefs.torrentioSize()), ""};
        }

        private void openTorrentio() {
            focus = 0;
            screen = TORRENTIO;
            for (Spring s : pop) s.snap(0);
        }

        private void drawTorrentio(Canvas c) {
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, headingColor());
            c.drawText(Ui.tr("Torrentio addon"), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            String[] labels = torrentioLabels(), values = torrentioValues();
            for (int i = 0; i < labels.length; i++) {
                float y = (LIST_TOP + 20 + i * (LIST_ROW_H + LIST_ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(0xF2FFFFFF, INK, f));
                rowValue.setColor(blend(0xB3FFFFFF, 0xB31C1C1E, f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(labels[i], rowRect.left + 30 * k, base, rowLabel);
                if (i == 2) {
                    float cw = Symbols.width(getContext(), "chevron_right", 22 * k);
                    Symbols.draw(getContext(), c, "chevron_right", rowRect.right - 30 * k - cw / 2, rowRect.centerY(), 22 * k, blend(0x99FFFFFF, INK, f));
                } else {
                    c.drawText(TextUtils.ellipsize(values[i], new TextPaint(rowValue), 640 * k, TextUtils.TruncateAt.END).toString(),
                            rowRect.right - 30 * k, base, rowValue);
                }
            }
            drawWrapped(c, Ui.tr("A third-party addon that finds streams for Stremio. Sky Home Launcher only sends Stremio its install link."),
                    LIST_X * k + 30 * k, (LIST_TOP + 20 + 3 * (LIST_ROW_H + LIST_ROW_GAP) + 36) * k, 0);
        }

        private boolean torChosen(int at) {
            return Prefs.torrentioLanguages().contains(torLangRows.get(at));
        }

        private void openTorLangs() {
            torLangRows.clear();
            for (String[] l : Torrentio.LANGUAGES) torLangRows.add(l[0]);
            pickScroll = 0;
            focus = 0;
            screen = TOR_LANGS;
            for (Spring s : pop) s.snap(0);
        }

        private void toggleTorLang() {
            int at = pickScroll + focus;
            if (at >= torLangRows.size()) return;
            List<String> names = Prefs.torrentioLanguages();
            if (!names.remove(torLangRows.get(at))) names.add(torLangRows.get(at));
            Prefs.setTorrentioLanguages(names);
        }

        private void drawTorLangs(Canvas c) {
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, headingColor());
            c.drawText(Ui.tr("Language"), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            int rows = rowCount();
            for (int i = 0; i < rows; i++) {
                int at = pickScroll + i;
                float y = (LIST_TOP + 20 + i * (LIST_ROW_H + LIST_ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(0xF2FFFFFF, INK, f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(Torrentio.label(torLangRows.get(at)), rowRect.left + 30 * k, base, rowLabel);
                if (torChosen(at)) {
                    float cw = Symbols.width(getContext(), "check", 26 * k);
                    Symbols.draw(getContext(), c, "check", rowRect.right - 30 * k - cw / 2, rowRect.centerY(), 26 * k, blend(0xFFFFFFFF, INK, f));
                }
            }
            c.drawText(Ui.tr("OK picks a language. With none picked, every language is used."), LIST_X * k + 30 * k,
                    (LIST_TOP + 20 + rows * (LIST_ROW_H + LIST_ROW_GAP) + 36) * k, notice);
        }

        // MARK: - Apps turned off (Sky Home Launcher > Home Screen)

        private String appsOffValue(int at) {
            return appsKept.contains(appsOffRows.get(at)[0]) ? Ui.tr("Kept on") : Ui.tr("Turned off");
        }

        private void openAppsOff() {
            appsOffRows.clear();
            appsOffRows.addAll(HomeMode.debloatApps(getContext()));
            appsOffRows.addAll(HomeMode.customOffApps(getContext()));
            appsManaged.clear();
            appsKept.clear();
            for (String[] row : appsOffRows) {
                appsManaged.add(row[0]);
                if (HomeMode.keepsOn(getContext(), row[0])) appsKept.add(row[0]);
            }
            if (!HomeMode.moreApps(getContext()).isEmpty()) appsOffRows.add(new String[] {"", Ui.tr("Show more")});
            pickScroll = 0;
            focus = 0;
            screen = APPS_OFF;
            for (Spring s : pop) s.snap(0);
        }

        /** OK: the "Show more" row reveals every other app safe to turn off; a fresh one from there is picked (starts
         * turned off); an already-managed one (the brand's own, or picked before) toggles Kept on/Turned off. */
        private void toggleAppsOff() {
            int at = pickScroll + focus;
            if (at >= appsOffRows.size()) return;
            String pkg = appsOffRows.get(at)[0];
            if (pkg.isEmpty()) {
                appsOffRows.remove(at);
                appsOffRows.addAll(HomeMode.moreApps(getContext()));
                return;
            }
            if (!appsManaged.contains(pkg)) {
                HomeMode.addTurnOff(getContext(), pkg);        // applied at once, in the background
                appsManaged.add(pkg);
                return;
            }
            boolean keep = appsKept.add(pkg);
            if (!keep) appsKept.remove(pkg);
            HomeMode.setKeepOn(getContext(), pkg, keep);       // applied at once, in the background
        }

        private void drawAppsOff(Canvas c) {
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, headingColor());
            c.drawText(Ui.tr("Apps turned off"), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            int rows = rowCount();
            for (int i = 0; i < rows; i++) {
                int at = pickScroll + i;
                String pkg = appsOffRows.get(at)[0];
                float y = (LIST_TOP + 20 + i * (LIST_ROW_H + LIST_ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(0xF2FFFFFF, INK, f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(appsOffRows.get(at)[1], rowRect.left + 30 * k, base, rowLabel);
                if (appsManaged.contains(pkg)) {
                    boolean kept = appsKept.contains(pkg);
                    rowValue.setColor(blend(kept ? 0xF2FFFFFF : 0x8CFFFFFF, kept ? INK : 0x991C1C1E, f));
                    c.drawText(appsOffValue(at), rowRect.right - 30 * k, base, rowValue);
                }
            }
            String text = appsOffRows.isEmpty() ? Ui.tr("No apps to turn off on this TV.")
                    : Ui.tr("OK keeps an app on, or lets Sky Home Launcher turn it off.");
            c.drawText(text, LIST_X * k + 30 * k, (LIST_TOP + 20 + Math.max(1, rows) * (LIST_ROW_H + LIST_ROW_GAP) + 36) * k, notice);
        }

        // MARK: - Hidden services (Sky Home Launcher > TMDB)

        private String hiddenValue() {
            List<String> names = new ArrayList<>();
            for (String[] s : Recs.serviceNames()) if (Prefs.hiddenServices().contains(s[0])) names.add(s[1]);
            return names.isEmpty() ? Ui.tr("None") : String.join(", ", names);
        }

        private boolean hiddenChecked(int at) {
            return Prefs.hiddenServices().contains(hiddenRows.get(at)[0]);
        }

        private void openHidden() {
            hiddenRows.clear();
            hiddenRows.addAll(Recs.serviceNames());
            pickScroll = 0;
            focus = 0;
            screen = HIDDEN;
            for (Spring s : pop) s.snap(0);
        }

        private void toggleHidden() {
            String key = hiddenRows.get(pickScroll + focus)[0];
            List<String> keys = Prefs.hiddenServices();
            if (!keys.remove(key)) keys.add(key);
            Prefs.setHiddenServices(keys);
        }

        private void drawHidden(Canvas c) {
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF);
            c.drawText(Ui.tr("Hidden services"), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            int rows = rowCount();
            for (int i = 0; i < rows; i++) {
                int at = pickScroll + i;
                float y = (LIST_TOP + 20 + i * (LIST_ROW_H + LIST_ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(0xF2FFFFFF, INK, f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(hiddenRows.get(at)[1], rowRect.left + 30 * k, base, rowLabel);
                if (hiddenChecked(at)) {
                    float cw = Symbols.width(getContext(), "check", 26 * k);
                    Symbols.draw(getContext(), c, "check", rowRect.right - 30 * k - cw / 2, rowRect.centerY(), 26 * k, blend(0xFFFFFFFF, INK, f));
                }
            }
            c.drawText(Ui.tr("OK hides or shows a service. Hidden ones are never used."), LIST_X * k + 30 * k,
                    (LIST_TOP + 20 + rows * (LIST_ROW_H + LIST_ROW_GAP) + 36) * k, notice);
        }

        // MARK: - Stremio

        /** The main menu's value: whether Stremio is there, and whether anything is sent to it. */
        private String stremioStatus() {
            if (!stremioOn) return StremioInstall.status();      // "Install", or how the install is going
            boolean any = Prefs.stremioForMissing();
            for (String[] s : Recs.serviceNames()) any |= Prefs.toStremio(s[0]);
            return onOff(any || Prefs.toStremio(Routing.OTHER));
        }

        /** The first row is "use Stremio for what is missing", then each service we know, then the ones we don't. */
        private void openStremio() {
            stremioRows.clear();
            stremioRows.add(new String[] {"", Ui.tr("Use Stremio as fallback OTT")});
            stremioRows.add(new String[] {TORRENTIO_KEY, Ui.tr("Torrentio addon")});
            for (String[] s : Recs.serviceNames()) stremioRows.add(s);
            stremioRows.add(new String[] {Routing.OTHER, Ui.tr("Other services")});
            stremioInstalled.clear();
            for (String[] service : Recs.serviceNames()) if (Recs.get(getContext()).serviceAppInstalled(service[0])) stremioInstalled.add(service[0]);
            pickScroll = 0;
            focus = 0;
            screen = STREMIO;
            for (Spring s : pop) s.snap(0);
        }

        /** A service whose app isn't on the TV: nothing to choose (its titles follow the fallback). */
        private boolean stremioOff(int at) {
            String key = stremioRows.get(at)[0];
            return !key.isEmpty() && !key.equals(Routing.OTHER) && !key.equals(TORRENTIO_KEY) && !stremioInstalled.contains(key);
        }

        private String stremioValue(int at) {
            String key = stremioRows.get(at)[0];
            if (key.equals(TORRENTIO_KEY)) return torrentioSummary();
            if (stremioOff(at)) return Ui.tr("Not installed");
            return key.isEmpty() ? onOff(Prefs.stremioForMissing()) : Prefs.toStremio(key) ? Ui.tr("Stremio") : Ui.tr("Its app");
        }

        private void toggleStremio() {
            if (stremioOff(pickScroll + focus)) return;
            String key = stremioRows.get(pickScroll + focus)[0];
            if (key.equals(TORRENTIO_KEY)) {
                openTorrentio();
                return;
            }
            if (key.isEmpty()) Prefs.setStremioForMissing(!Prefs.stremioForMissing());
            else Prefs.setToStremio(key, !Prefs.toStremio(key));
        }

        private void drawStremio(Canvas c) {
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, headingColor());
            c.drawText(Ui.tr("Stremio"), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            int rows = rowCount();
            for (int i = 0; i < rows; i++) {
                float y = (LIST_TOP + 20 + i * (LIST_ROW_H + LIST_ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k);
                float f = drawRowBackground(c, i);
                boolean off = stremioOff(pickScroll + i);
                rowLabel.setColor(blend(off ? 0x73FFFFFF : 0xF2FFFFFF, off ? 0x731C1C1E : INK, f));
                rowValue.setColor(blend(off ? 0x73FFFFFF : 0xB3FFFFFF, off ? 0x731C1C1E : 0xB31C1C1E, f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(TextUtils.ellipsize(stremioRows.get(pickScroll + i)[1], new TextPaint(rowLabel), 560 * k, TextUtils.TruncateAt.END).toString(),
                        rowRect.left + 30 * k, base, rowLabel);
                c.drawText(stremioValue(pickScroll + i), rowRect.right - 30 * k, base, rowValue);
            }
            // What the focused row does, in words.
            int at = pickScroll + focus;
            String caption = at >= stremioRows.size() ? "" : stremioRows.get(at)[0].equals(TORRENTIO_KEY)
                    ? Ui.tr("A third-party addon that finds streams for Stremio. Sky Home Launcher only sends Stremio its install link.")
                    : stremioRows.get(at)[0].isEmpty()
                    ? Ui.tr("When a title's app isn't on the TV, or no service we know streams it, open it in Stremio instead of its Play Store page.")
                    : stremioOff(at) ? Ui.tr("This app isn't installed, so its titles follow the fallback above.")
                    : Ui.tr("Each service opens in its own app or in Stremio. OK changes it.");
            drawWrapped(c, caption, LIST_X * k + 30 * k, (LIST_TOP + 20 + rows * (LIST_ROW_H + LIST_ROW_GAP) + 36) * k, 0);
        }

        // MARK: - Home Settings and Remote Buttons

        private static final String[] UNITS = {"auto", "C", "F"}, THEMES = {"auto", "light", "dark"};
        /** Home Settings: color scheme and visual theme are independent controls. */
        private static final int LOOK_ROW = 3, CITY_ROW = 6, WATCH_NEXT_ROW = 7, TRAILER_ROW = 8, QUOTES_ROW = 9, COIN_ROWS = 10;
        /** Debug: Settings > Home Settings > Memory card, after the coin rows (and "Dollar", when shown). */
        private int memCardRow() { return COIN_ROWS + 3 + (dollar() ? 1 : 0); }

        private boolean dollar() {
            return "BRL".equalsIgnoreCase(Config.CURRENCY.trim());
        }

        private String[] settingLabels() {
            List<String> l = new ArrayList<>();
            l.add(Ui.tr("Language"));
            l.add(Ui.tr("Temperature"));
            l.add(Ui.tr("Theme"));
            l.add(Ui.tr("Look"));
            l.add(Ui.tr("Navigation sounds"));
            l.add(Ui.tr("Weather widget"));
            l.add(Ui.tr("City"));
            l.add(Ui.tr("Apps' Continue Watching"));
            l.add(Ui.tr("Trailers on the home screen"));
            l.add(Ui.tr("Quotes widget"));
            l.add("Bitcoin");
            l.add("Ethereum");
            l.add("Solana");
            if (dollar()) l.add(Ui.tr("Dollar"));
            l.add(Ui.tr("Memory card"));
            l.add(Ui.tr("Reset hidden titles"));
            return l.toArray(new String[0]);
        }

        private String[] settingValues() {
            String[] labels = settingLabels();
            String[] v = new String[labels.length];
            String lang = Prefs.language();
            v[0] = lang.equals("auto") ? Ui.tr("Automatic") : Ui.languageName(lang);
            String unit = Prefs.temperature();
            v[1] = unit.equals("C") ? "°C" : unit.equals("F") ? "°F" : Ui.tr("unit|Automatic");
            String theme = Prefs.theme();
            v[2] = theme.equals("light") ? Ui.tr("Light") : theme.equals("dark") ? Ui.tr("Dark") : Ui.tr("Automatic");
            v[LOOK_ROW] = Themes.current().label();
            v[4] = onOff(Prefs.sounds());
            v[5] = onOff(Prefs.weather());
            v[CITY_ROW] = !cityStatus.isEmpty() ? cityStatus
                    : Prefs.autoLocation() ? Ui.tr("Automatic") + (Prefs.city().isEmpty() ? "" : " · " + Prefs.city())
                    : Prefs.city().isEmpty() ? "—" : Prefs.city();
            v[WATCH_NEXT_ROW] = onOff(Prefs.watchNext() && WatchNext.permitted(getContext()));
            v[TRAILER_ROW] = onOff(Prefs.trailers());
            v[QUOTES_ROW] = onOff(Prefs.quotes());
            // No quotes widget, nowhere to show prices: the coins read as off (and stay locked) until it is back on.
            for (int i = COIN_ROWS; i < memCardRow(); i++) v[i] = onOff(Prefs.quotes() && Prefs.coin(i - COIN_ROWS));
            v[memCardRow()] = onOff(Prefs.memoryCard());
            int hidden = Prefs.hiddenTiles().size();      // posters hidden from Continue Watching and the other rows; OK brings them back
            v[memCardRow() + 1] = hidden == 0 ? "—" : String.valueOf(hidden);
            return v;
        }

        /** "auto", then English and every language with a catalogue. */
        private static String[] languageChoices() {
            String[] all = Ui.languages();
            String[] v = new String[all.length + 1];
            v[0] = "auto";
            System.arraycopy(all, 0, v, 1, all.length);
            return v;
        }

        private static String onOff(boolean on) {
            return on ? Ui.tr("On") : Ui.tr("Off");
        }

        /** The remote buttons screen: the brand's buttons, then a row for the ones the person added. */
        private String[] keyLabels() {
            String[] own = ownKeyLabels();
            String[] all = java.util.Arrays.copyOf(own, own.length + 1);
            all[own.length] = Ui.tr("Added buttons");
            return all;
        }

        /** The name of button `i` (an index of keyIds): the brand's label, or what the person's button was called. */
        private String keyLabelOf(int i) {
            return i < Prefs.KEYS.length ? ownKeyLabels()[i] : Prefs.learnedName(keyIds[i]);
        }

        private String[] ownKeyLabels() {
            if (RemoteKeys.SKYWORTH) return new String[] {Ui.tr("Netflix button"), Ui.tr("YouTube button"),
                    Ui.tr("Prime Video button"), Ui.tr("Disney+ button"), Ui.tr("Settings button")};
            if (RemoteKeys.STEM_NETFLIX) return new String[] {Ui.tr("Netflix button")};
            if (RemoteKeys.KTC) return new String[] {Ui.tr("Tabii button")};
            if (RemoteKeys.SONY) return new String[] {Ui.tr("BRAVIA CORE button"),
                    Ui.tr("Netflix button"), Ui.tr("Disney+ button"),
                    Ui.tr("Prime Video button"), Ui.tr("YouTube button"),
                    Ui.tr("Crunchyroll button"), Ui.tr("Google Play button")};
            return new String[] {Ui.tr("Netflix button"), Ui.tr("YouTube button"),
                    Ui.tr("Prime Video button"), Ui.tr("TCL Channel button")};
        }

        private String[] keyValues() {
            String[] v = new String[Prefs.KEYS.length + 1];
            for (int i = 0; i < Prefs.KEYS.length; i++) v[i] = appOf(Prefs.KEYS[i]);
            v[Prefs.KEYS.length] = learnedCount() == 0 ? Ui.tr("None") : String.valueOf(learnedCount());
            return v;
        }

        private String appOf(int id) {
            int at = appPkgs.indexOf(Prefs.keyApp(id));
            return at >= 0 ? appLabels.get(at) : Ui.tr("Not installed");
        }

        /** Installed TV apps, by name, after "Nothing": what a remote button can open. */
        private void loadApps() {
            List<String[]> found = new ArrayList<>();
            for (String[] app : HomeApps.launchable(getContext())) {
                if (!app[0].equals(getContext().getPackageName())) found.add(app);
            }
            Collections.sort(found, (a, b) -> a[1].toLowerCase(Locale.ROOT).compareTo(b[1].toLowerCase(Locale.ROOT)));
            appPkgs.clear();
            appLabels.clear();
            appPkgs.add("");
            // On Sony, "nothing" leaves the key to the TV, which opens its usual app.
            appLabels.add(RemoteKeys.SONY ? Ui.tr("Sony default") : Ui.tr("Nothing"));
            if (RemoteKeys.SKYWORTH || Prefs.hasLearned()) {      // the gear button: Android's own Settings (the stock launcher's panel is off)
                appPkgs.add(Prefs.TV_SETTINGS);
                appLabels.add(Ui.tr("TV Settings"));
            }
            // Every button (built-in or added) can also jump straight to Sky Home Launcher's own Settings.
            appPkgs.add(Prefs.FMT_SETTINGS);
            appLabels.add(Ui.tr("Sky Home Launcher Settings"));
            for (String[] f : found) {
                appPkgs.add(f[0]);
                appLabels.add(f[1]);
            }
        }

        /** Changes the focused row's value one step (OK and → go forward, ← goes back). */
        private void adjust(int dir) {
            if (screen == POWER) {
                if (disabledRow(focus)) return;
                int row = powerRow();
                if (row == P_SLEEP) {
                    SleepTimer.step(getContext(), dir);
                    sleepLeft = SleepTimer.minutesLeft(getContext());
                } else if (row == P_SHOW) {
                    Prefs.setSleepShown(!Prefs.sleepShown());
                    SleepOverlay.update(getContext());
                } else if (row == P_WHERE) {
                    Prefs.setSleepPlace((Prefs.sleepPlace() + dir + SleepOverlay.PLACES) % SleepOverlay.PLACES);
                    SleepOverlay.update(getContext());
                }
                focus = powerFocus(row);   // rows come and go with the switches above them
                return;
            }
            if (screen == HIDDEN) {
                toggleHidden();
                return;
            }
            if (screen == APPS_OFF) {
                toggleAppsOff();
                return;
            }
            if (screen == TOR_LANGS) {
                toggleTorLang();
                return;
            }
            if (screen == TORRENTIO) {
                if (focus == 1) {
                    int at = java.util.Arrays.asList(Torrentio.SIZES).indexOf(Prefs.torrentioSize());
                    Prefs.setTorrentioSize(Torrentio.SIZES[(Math.max(0, at) + dir + Torrentio.SIZES.length) % Torrentio.SIZES.length]);
                } else if (focus == 0 && dir > 0) {
                    openTorLangs();
                }
                return;
            }
            if (screen == LANGUAGES) {
                toggleLanguage();
                return;
            }
            if (screen == STREMIO) {
                toggleStremio();
                return;
            }
            if (screen == TMDB) {
                if (focus == TMDB_PICKS) {
                    String[] styles = {Prefs.PICKS_OFF, Prefs.PICKS_CARDS, Prefs.PICKS_SPOTLIGHT};
                    int at = java.util.Arrays.asList(styles).indexOf(Prefs.picksStyle());
                    Prefs.setPicksStyle(styles[(Math.max(0, at) + dir + styles.length) % styles.length]);
                }
                else if (focus == TMDB_FROM) Prefs.setRecommendFromApps(!Prefs.recommendFromApps());
                else if (focus == TMDB_STREAM) Prefs.setFlatrateOnly(!Prefs.flatrateOnly());
                else if (focus == RENT_BUY_ROW && !disabledRow(RENT_BUY_ROW)) Prefs.setIncludeRentBuy(!Prefs.includeRentBuy());
                else if (focus == TMDB_ANIMATION) Prefs.setHideAnimation(!Prefs.hideAnimation());
                return;
            }
            if (screen == REMOTE_KEYS || screen == ADDED_KEYS) {
                boolean added = screen == ADDED_KEYS;
                if (!added && focus >= Prefs.KEYS.length) {       // the "Added buttons" row
                    if (dir > 0) openAdded(0);
                    return;
                }
                if (added && focus >= learnedCount()) return;       // the "Add a button" row: OK only
                int id = added ? keyIds[Prefs.KEYS.length + focus] : Prefs.KEYS[focus];
                int n = appPkgs.size();
                int at = appPkgs.indexOf(Prefs.keyApp(id));
                int next = at < 0 ? (dir > 0 ? 0 : n - 1) : (at + dir + n) % n;
                Prefs.setKeyApp(id, appPkgs.get(next));
                return;
            }
            int row = pickScroll + focus;
            switch (row) {
                case 0: break;   // OK opens the language picker.
                case 1: {
                    int at = java.util.Arrays.asList(UNITS).indexOf(Prefs.temperature());
                    Prefs.setTemperature(UNITS[(at + dir + UNITS.length) % UNITS.length]);
                    break;
                }
                case 2: {
                    int at = java.util.Arrays.asList(THEMES).indexOf(Prefs.theme());
                    Prefs.setTheme(THEMES[(at + dir + THEMES.length) % THEMES.length]);
                    SleepOverlay.update(getContext());   // the pill's glass follows
                    break;
                }
                case LOOK_ROW: {
                    String[] themes = Themes.ids();
                    int at = java.util.Arrays.asList(themes).indexOf(Prefs.look());
                    Prefs.setLook(themes[(at + dir + themes.length) % themes.length]);
                    break;
                }
                case 4: Prefs.setSounds(!Prefs.sounds()); break;
                case 5: Prefs.setWeather(!Prefs.weather()); break;
                case CITY_ROW: return;
                case WATCH_NEXT_ROW:
                    Prefs.setWatchNext(!Prefs.watchNext());
                    if (Prefs.watchNext() && !WatchNext.permitted(getContext())) {       // the TV's own dialog, if it will still ask
                        activity.requestPermissions(new String[] {WatchNext.PERMISSION}, WatchNext.ASK);
                    }
                    break;
                case TRAILER_ROW: Prefs.setTrailers(!Prefs.trailers()); break;
                case QUOTES_ROW: Prefs.setQuotes(!Prefs.quotes()); break;
                default:
                    if (row == memCardRow() + 1) {
                        if (dir > 0 && !Prefs.hiddenTiles().isEmpty()) Catalogs.get(getContext()).unhideAll();
                        return;
                    }
                    if (row == memCardRow()) {
                        Prefs.setMemoryCard(!Prefs.memoryCard());
                        if (Prefs.memoryCard()) MemoryCard.start(getContext()); else MemoryCard.stop(getContext());
                        break;
                    }
                    if (!Prefs.quotes()) return;
                    Prefs.setCoin(row - COIN_ROWS, !Prefs.coin(row - COIN_ROWS));
                    break;
            }
            HomeData.get(getContext()).settingsChanged();
        }

        /** Lets the person type a name for an added button, in place of the default "Button {n}". */
        private void askRenameButton(int id) {
            EditText field = new EditText(activity);
            field.setSingleLine(true);
            field.setText(Prefs.learnedName(id));
            field.setHint(Ui.tr("Name"));
            field.setImeOptions(EditorInfo.IME_ACTION_DONE);
            AlertDialog dialog = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(Ui.tr("Rename button"))
                    .setView(field)
                    .setPositiveButton(Ui.tr("Save"), (d, which) -> renameButton(id, field.getText().toString()))
                    .setNegativeButton(Ui.tr("Cancel"), null)
                    .create();
            field.setOnEditorActionListener((v, action, event) -> {
                dialog.dismiss();
                renameButton(id, field.getText().toString());
                return true;
            });
            dialog.show();
        }

        private void renameButton(int id, String name) {
            name = name.trim();
            if (!name.isEmpty()) Prefs.renameLearned(id, name);
            refreshKeyIds();
            openAdded(id - Prefs.LEARNED);
        }

        private void askCity() {
            if (Ui.tr("Searching…").equals(cityStatus)) return;
            EditText field = new EditText(activity);
            field.setSingleLine(true);
            field.setHint(Ui.tr("Type the city's name"));
            field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
            AlertDialog dialog = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(Ui.tr("City"))
                    .setView(field)
                    .setPositiveButton(Ui.tr("Search"), (d, which) -> searchCity(field.getText().toString()))
                    .setNeutralButton(Ui.tr("Automatic"), (d, which) -> {        // from the TV's internet address
                        Prefs.setAutoLocation(true);
                        HomeData.get(getContext()).settingsChanged();
                        invalidate();
                    })
                    .setNegativeButton(Ui.tr("Cancel"), null)
                    .create();
            field.setOnEditorActionListener((v, action, event) -> {
                dialog.dismiss();
                searchCity(field.getText().toString());
                return true;
            });
            dialog.show();
        }

        private void searchCity(String name) {
            if (name.trim().isEmpty()) return;
            cityStatus = Ui.tr("Searching…");
            invalidate();
            new Thread(() -> {
                List<String[]> found;
                String error;
                try {
                    found = HomeData.findCity(name);
                    error = found.isEmpty() ? Ui.tr("No city with that name.") : "";
                } catch (Exception e) {
                    found = new ArrayList<>();
                    error = Ui.trf("Couldn't search now: {error}", "error", e.getMessage());
                    AppLog.w("city search: " + e.getMessage());
                }
                List<String[]> result = found;
                String message = error;
                handler.post(() -> {
                    cityStatus = message;
                    if (!result.isEmpty() && screen == HOME_SETTINGS) {
                        cities.clear();
                        cities.addAll(result);
                        openPicker(PICK_CITY);
                        spoken.focusMoved();
                    }
                    invalidate();
                    if (!message.isEmpty()) handler.postDelayed(() -> { cityStatus = ""; invalidate(); }, 4000);
                });
            }).start();
        }

        /** Settings > Restart: confirms, then kills this process (:home) so the system starts it fresh. Not a crash:
         *  no exception is thrown, so the watchdog's crash counter (Watchdog.java) never sees it. */
        private void askRestart() {
            new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(Ui.tr("Restart"))
                    .setMessage(Ui.tr("Sky Home Launcher will close and reopen."))
                    .setPositiveButton(Ui.tr("Restart"), (d, which) -> android.os.Process.killProcess(android.os.Process.myPid()))
                    .setNegativeButton(Ui.tr("Cancel"), null)
                    .create().show();
        }

        private boolean scrolling() {
            return screen == PICKER || screen == SHELF_EDIT || screen == STREMIO || screen == LANGUAGES || screen == HIDDEN || screen == APPS_OFF || screen == TOR_LANGS || screen == HOME_SETTINGS;
        }

        private int listSize() {
            return screen == SHELF_EDIT ? shelfRows.size() : screen == STREMIO ? stremioRows.size() : screen == LANGUAGES ? langRows.size() : screen == HIDDEN ? hiddenRows.size() : screen == APPS_OFF ? appsOffRows.size() : screen == TOR_LANGS ? torLangRows.size() : screen == HOME_SETTINGS ? settingLabels().length : pickLabels.size();
        }

        // MARK: - Shelf editor

        private void openShelfEditor(boolean top) {
            editingTop = top;
            editingRows = false;
            shelfOrder.clear();
            if (top) {
                shelfOrder.addAll(HomeApps.topButtonKeys());
                rebuildShelfRows(HomeApps.topCandidates(getContext()));
            } else {
                // Only the apps the shelf shows: one that was uninstalled would hold a place (and count toward the
                // limit) without a row to take it off.
                PackageManager pm = getContext().getPackageManager();
                for (String pkg : HomeApps.shelfPackages(getContext())) if (HomeApps.launchIntent(pm, pkg) != null) shelfOrder.add(pkg);
                rebuildShelfRows(HomeApps.shelfCandidates(getContext()));
            }
            pickScroll = 0;
            focus = 0;
            screen = SHELF_EDIT;
        }

        /** The poster rows (Sky Home Launcher > TMDB): every row TMDB can fill, the ones that are on first, in their order. */
        private void openRowsEditor() {
            editingTop = false;
            editingRows = true;
            shelfOrder.clear();
            shelfOrder.addAll(Prefs.catalogRows());
            List<String[]> all = new ArrayList<>();
            for (String key : Catalogs.ALL) all.add(new String[] {key, Catalogs.title(key)});
            shelfOrder.retainAll(java.util.Arrays.asList(Catalogs.ALL));
            rebuildShelfRows(all);
            pickScroll = 0;
            focus = 0;
            screen = SHELF_EDIT;
        }

        /** Selected apps first, in shelf order, then the rest in the order they came. */
        private void rebuildShelfRows(List<String[]> candidates) {
            shelfRows.clear();
            for (String pkg : shelfOrder) {
                for (String[] cand : candidates) if (cand[0].equals(pkg)) { shelfRows.add(cand); break; }
            }
            for (String[] cand : candidates) if (!shelfOrder.contains(cand[0])) shelfRows.add(cand);
        }

        /** Same rows after a change: keeps the focus on the app that was focused. */
        private void refreshShelfRows(String focusedPkg) {
            List<String[]> all = new ArrayList<>(shelfRows);
            rebuildShelfRows(all);
            for (int i = 0; i < shelfRows.size(); i++) {
                if (!shelfRows.get(i)[0].equals(focusedPkg)) continue;
                int visible = rowCount();
                if (i < pickScroll) pickScroll = i;
                else if (i >= pickScroll + visible) pickScroll = i - visible + 1;
                focus = i - pickScroll;
                break;
            }
            if (editingRows) Prefs.setCatalogRows(shelfOrder);
            else if (editingTop) Prefs.setTopButtons(shelfOrder);
            else Prefs.setShelf(shelfOrder);
        }

        /** OK: puts the app on the shelf (at the end) or takes it off. */
        private void toggleShelfApp() {
            String pkg = shelfRows.get(pickScroll + focus)[0];
            if (shelfOrder.contains(pkg)) shelfOrder.remove(pkg);
            else if (editingTop || editingRows || shelfOrder.size() < HomeApps.MAX_SHELF) shelfOrder.add(pkg);   // three buttons, seven rows: no limit to reach
            else return;
            refreshShelfRows(pkg);
        }

        /** ← → : moves a shelf app one place earlier or later. */
        private void moveShelfApp(int dir) {
            String pkg = shelfRows.get(pickScroll + focus)[0];
            int at = shelfOrder.indexOf(pkg), to = at + dir;
            if (at < 0 || to < 0 || to >= shelfOrder.size()) return;
            shelfOrder.remove(at);
            shelfOrder.add(to, pkg);
            refreshShelfRows(pkg);
        }

        private void drawShelfEditor(Canvas c) {
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, headingColor());
            c.drawText(editingRows ? Ui.tr("Catalogs") : editingTop ? Ui.tr("Top Buttons") : Ui.tr("Shelf"), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            int rows = rowCount();
            for (int i = 0; i < rows; i++) {
                String[] app = shelfRows.get(pickScroll + i);
                int place = shelfOrder.indexOf(app[0]);
                float y = (LIST_TOP + 20 + i * (LIST_ROW_H + LIST_ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(place >= 0 ? 0xF2FFFFFF : 0x80FFFFFF, place >= 0 ? INK : 0x801C1C1E, f));
                rowValue.setColor(blend(0xB3FFFFFF, 0xB31C1C1E, f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(TextUtils.ellipsize(app[1], new TextPaint(rowLabel), 520 * k, TextUtils.TruncateAt.END).toString(), rowRect.left + 30 * k, base, rowLabel);
                c.drawText(place >= 0 ? "#" + (place + 1) : Ui.tr("shelf|Off"), rowRect.right - 30 * k, base, rowValue);
            }
            float hy = (LIST_TOP + 20 + rows * (LIST_ROW_H + LIST_ROW_GAP) + 36) * k;
            c.drawText(editingRows ? Ui.tr("OK turns a row on or off. ← → change the order.")
                    : editingTop ? Ui.tr("OK adds or removes a button. ← → change the order.")
                    : Ui.trf("OK adds or removes (up to {n}). ← → change the order.", "n", HomeApps.MAX_SHELF),
                    LIST_X * k + 30 * k, hy, notice);
        }

        private void openPicker(int which) {
            pickFor = which;
            pickLabels.clear();
            if (which == PICK_CITY) {
                for (String[] city : cities) pickLabels.add(city[0]);
                pickCurrent = -1;
            } else if (which == PICK_REGION) {
                regionCodes.clear();
                regionCodes.add("auto");
                pickLabels.add(Ui.tr("Automatic"));
                for (Regions.Entry e : Regions.list(getContext())) {
                    regionCodes.add(e.code);
                    pickLabels.add(e.name);
                }
                pickCurrent = regionCodes.indexOf(Prefs.region());
            } else if (which == PICK_LANGUAGE) {
                String[] choices = languageChoices();
                for (String code : choices) {
                    pickLabels.add(code.equals("auto") ? Ui.tr("Automatic") : Ui.languageName(code));
                }
                pickCurrent = java.util.Arrays.asList(choices).indexOf(Prefs.language());
            } else if (which == PICK_SLEEP) {
                for (int m : SLEEP_MINUTES) pickLabels.add(m == 0 ? Ui.tr("Off") : SleepTimer.duration(m));
                pickCurrent = -1;
                for (int i = 0; i < SLEEP_MINUTES.length; i++) if (SLEEP_MINUTES[i] == sleepLeft) pickCurrent = i;
            } else if (which == PICK_PLACE) {
                for (int i = 0; i < SleepOverlay.PLACES; i++) pickLabels.add(SleepOverlay.placeName(i));
                pickCurrent = Prefs.sleepPlace();
            } else {
                pickLabels.addAll(appLabels);
                if (which - 1 >= Prefs.KEYS.length) {
                    pickLabels.add(Ui.tr("Rename this button"));
                    pickLabels.add(Ui.tr("Remove this button"));
                }
                pickCurrent = appPkgs.indexOf(Prefs.keyApp(keyIds[which - 1]));
            }
            int visible = Math.min(PICK_VISIBLE, pickLabels.size());
            int at = Math.max(0, pickCurrent);
            pickScroll = Math.max(0, Math.min(at - 3, pickLabels.size() - visible));
            focus = at - pickScroll;
            screen = PICKER;
        }

        /** A long list (the apps): nine rows at a time, the one in use checked. */
        private void drawPicker(Canvas c) {
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, headingColor());
            String title = pickFor == PICK_CITY ? Ui.tr("City") : pickFor == PICK_REGION ? Ui.tr("Region")
                    : pickFor == PICK_LANGUAGE ? Ui.tr("Language") : pickFor == PICK_SLEEP ? Ui.tr("Sleep Timer")
                    : pickFor == PICK_PLACE ? Ui.tr("Position") : keyLabelOf(pickFor - 1);
            c.drawText(title, LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            int rows = rowCount();
            for (int i = 0; i < rows; i++) {
                int at = pickScroll + i;
                float y = (LIST_TOP + 20 + i * (LIST_ROW_H + LIST_ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(0xF2FFFFFF, INK, f));
                c.drawText(pickLabels.get(at), rowRect.left + 30 * k, rowRect.centerY() + Ui.halfCap(rowLabel), rowLabel);
                if (at == pickCurrent) {
                    float cw = Symbols.width(getContext(), "check", 26 * k);
                    Symbols.draw(getContext(), c, "check", rowRect.right - 30 * k - cw / 2, rowRect.centerY(), 26 * k, blend(0xFFFFFFFF, INK, f));
                }
            }
            float hy = (LIST_TOP + 20 + rows * (LIST_ROW_H + LIST_ROW_GAP) + 36) * k;
            c.drawText((pickScroll + focus + 1) + " / " + pickLabels.size() + "  ·  " + Ui.tr("OK picks, Back cancels."),
                    LIST_X * k + 30 * k, hy, notice);
        }

        private void drawList(Canvas c) {
            boolean keys = screen == REMOTE_KEYS;
            String[] labels = keys ? keyLabels() : settingLabels();
            String[] values = keys ? keyValues() : settingValues();
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, headingColor());
            c.drawText(keys ? Ui.tr("Remote Buttons") : Ui.tr("Home Settings"),
                    LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            int rows = rowCount();
            for (int i = 0; i < rows; i++) {
                int at = pickScroll + i;
                float y = (LIST_TOP + 20 + i * (LIST_ROW_H + LIST_ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(0xF2FFFFFF, INK, f));
                boolean locked = !keys && at >= COIN_ROWS && !Prefs.quotes();
                rowValue.setColor(blend(locked ? 0x59FFFFFF : 0xB3FFFFFF, locked ? 0x591C1C1E : 0xB31C1C1E, f));
                if (locked) rowLabel.setColor(blend(0x73FFFFFF, 0x731C1C1E, f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(labels[at], rowRect.left + 30 * k, base, rowLabel);
                c.drawText(values[at], rowRect.right - 30 * k, base, rowValue);
            }
            float hy = (LIST_TOP + 20 + rows * (LIST_ROW_H + LIST_ROW_GAP) + 36) * k;
            c.drawText(Ui.tr("OK or the arrows change the value."), LIST_X * k + 30 * k, hy, notice);
            if (keys && !RemoteKeys.SKYWORTH) {
                c.drawText(RemoteKeys.VESTEL ? Ui.tr("YouTube, Prime Video and Google Play: the TV itself controls those buttons.")
                        : RemoteKeys.KTC ? Ui.tr("Netflix, YouTube and Prime Video: the TV itself controls those buttons.")
                        : RemoteKeys.SONY ? Ui.tr("Sony default: the button opens what it always did.")
                        : RemoteKeys.FORMOVIE ? Ui.tr("The other buttons: the TV itself controls them.")
                        : RemoteKeys.supported() ? Ui.tr("Disney and Globoplay: the TV itself controls those buttons.")
                        : Ui.tr("TCL remote buttons: on this TV they work as always."), LIST_X * k + 30 * k, hy + 38 * k, notice);
            }
        }

        private void activate() {
            Context c = getContext();
            if (screen == LEARN) return;        // waiting for a button on the remote
            if (screen == HOME_CHOICE && focus == 2) {
                openAppsOff();
            } else if (screen == APPS_OFF) {
                toggleAppsOff();
                invalidate();
                return;
            } else if (screen == TOR_LANGS) {
                toggleTorLang();
                invalidate();
                return;
            } else if (screen == TORRENTIO) {
                if (focus == 0) {
                    openTorLangs();
                } else if (focus == 1) {
                    adjust(1);
                    invalidate();
                    return;
                } else if (!Torrentio.install(c, Prefs.torrentioLanguages(), Prefs.torrentioSize())) {
                    HomeMode.notice(c, Ui.trf("Couldn't open {app}.", "app", "Stremio"));
                }
            } else if (screen == HOME_CHOICE) {
                boolean wantFree = focus == 0;
                boolean already = wantFree ? freeActive() : HomeMode.ORIGINAL.equals(state.optString("mode", HomeMode.ORIGINAL));
                if (!already && !busy()) {
                    HomeMode.request(c, wantFree ? HomeMode.FREE : HomeMode.ORIGINAL);
                    pendingAt = SystemClock.uptimeMillis();
                }
                screen = MAIN;
                focus = 0;
            } else if (screen == PICKER && pickFor == PICK_CITY) {
                String[] city = cities.get(pickScroll + focus);
                Prefs.setCity(city[1], city[2], city[3]);
                HomeData.get(c).settingsChanged();
                pickScroll = 0;
                focus = CITY_ROW;
                screen = HOME_SETTINGS;
            } else if (screen == PICKER && pickFor == PICK_LANGUAGE) {
                Prefs.setLanguage(languageChoices()[pickScroll + focus]);
                SleepOverlay.update(c);   // the pill speaks it too
                pickScroll = 0;
                focus = 0;
                screen = HOME_SETTINGS;
            } else if (screen == PICKER && pickFor == PICK_REGION) {
                Prefs.setRegion(regionCodes.get(pickScroll + focus));
                focus = 0;
                screen = TMDB;
            } else if (screen == PICKER && pickFor == PICK_SLEEP) {
                SleepTimer.set(c, SLEEP_MINUTES[pickScroll + focus]);
                sleepLeft = SleepTimer.minutesLeft(c);
                pickScroll = 0;
                screen = POWER;
                focus = powerFocus(P_SLEEP);
            } else if (screen == PICKER && pickFor == PICK_PLACE) {
                Prefs.setSleepPlace(pickScroll + focus);
                SleepOverlay.update(c);
                pickScroll = 0;
                screen = POWER;
                focus = powerFocus(P_WHERE);
            } else if (screen == POWER) {
                activatePower();
            } else if (screen == TMDB) {
                if (focus == 0) {
                    openPicker(PICK_REGION);
                } else if (focus == TMDB_CATALOGS) {
                    openRowsEditor();
                } else if (focus == TMDB_LANGS) {
                    openLanguages();
                } else if (focus == TMDB_HIDDEN) {
                    openHidden();
                } else {
                    adjust(1);
                    invalidate();
                    return;
                }
            } else if (screen == HIDDEN) {
                toggleHidden();
                invalidate();
                return;
            } else if (screen == LANGUAGES) {
                toggleLanguage();
                invalidate();
                return;
            } else if (screen == STREMIO) {
                toggleStremio();
                invalidate();
                return;
            } else if (screen == PICKER) {
                int at = pickScroll + focus;
                boolean added = pickFor - 1 >= Prefs.KEYS.length;
                if (added && at == appPkgs.size()) {        // "Rename this button"
                    askRenameButton(keyIds[pickFor - 1]);
                } else if (added && at == appPkgs.size() + 1) {    // "Remove this button"
                    Prefs.removeLearned(keyIds[pickFor - 1]);
                    refreshKeyIds();
                    openAdded(Math.min(pickFor - 1 - Prefs.KEYS.length, learnedCount()));
                } else {
                    Prefs.setKeyApp(keyIds[pickFor - 1], appPkgs.get(at));
                    if (added) {
                        openAdded(pickFor - 1 - Prefs.KEYS.length);
                    } else {
                        focus = pickFor - 1;
                        screen = REMOTE_KEYS;
                    }
                }
            } else if (screen == SHELF_EDIT) {
                toggleShelfApp();
                invalidate();
                return;
            } else if (screen == TRAKT) {
                traktPressed();
                invalidate();
                spoken.focusMoved();
                return;
            } else if (screen == INTEGRATIONS) {
                integrationsPressed();
                invalidate();
                spoken.focusMoved();
                return;
            } else if (screen == REMOTE_KEYS) {
                if (focus >= Prefs.KEYS.length) openAdded(0);
                else openPicker(focus + 1);
            } else if (screen == ADDED_KEYS) {
                if (focus < learnedCount()) openPicker(Prefs.KEYS.length + focus + 1);
                else startLearn();
            } else if (screen == HOME_SETTINGS && pickScroll + focus == 0) {
                openPicker(PICK_LANGUAGE);
            } else if (screen == HOME_SETTINGS && pickScroll + focus == CITY_ROW) {
                askCity();
                return;
            } else if (screen == HOME_SETTINGS) {
                adjust(1);
                invalidate();
                return;
            } else if (focus == 0) {
                if (busy()) return;
                screen = HOME_CHOICE;
                focus = freeActive() ? 0 : 1;
            } else if (focus == 1) {
                pause();
                activity.showPreview();
            } else if (focus == 2) {
                screen = HOME_SETTINGS;
                pickScroll = 0;
                focus = 0;
            } else if (focus == 3) {
                openShelfEditor(false);
            } else if (focus == 4) {
                openShelfEditor(true);
            } else if (focus == 5) {
                HomeMode.ensureRemoteService(c, 0);   // the buttons work from inside other apps through this service
                refreshKeyIds();
                loadApps();
                screen = REMOTE_KEYS;
                focus = 0;
            } else if (focus == TRAKT_ROW) {
                if (disabledRow(TRAKT_ROW)) return;     // no Client ID: nothing to open
                screen = TRAKT;
                focus = 0;
            } else if (focus == TMDB_ROW) {
                if (disabledRow(TMDB_ROW)) return;      // no TMDB key: nothing to open
                Regions.refreshIfStale(c);
                screen = TMDB;
                focus = 0;
            } else if (focus == STREMIO_ROW) {
                if (!stremioOn) {       // Stremio isn't on the TV: install it from its own site
                    StremioInstall.start(c);
                    invalidate();
                    return;
                }
                openStremio();
            } else if (focus == INTEGRATIONS_ROW) {
                screen = INTEGRATIONS;
                focus = 0;
            } else if (focus == SLEEP_ROW) {
                if (disabledRow(SLEEP_ROW)) return;     // needs Sky Home Launcher mode on
                pickScroll = 0;
                screen = POWER;
                focus = 0;
            } else if (focus == RESTART_ROW) {
                askRestart();
                return;
            } else {
                activity.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            for (Spring s : pop) s.snap(0);
            invalidate();
            spoken.focusMoved();
        }
    }
}
