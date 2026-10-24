package io.freemytv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.BlurMaskFilter;
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
 * Free My TV's settings (the app's icon, "All Apps", or the gear on the home screen), in the style of Apple TV's
 * settings: the big symbol and title on the left, the list on the right with the focused row in white. "Home screen"
 * opens the Free My TV / Original choice (with a check on the current one); the switch runs in the main process
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
        view = new SettingsView(this);
        setContentView(view);
    }

    @Override protected void onResume() {
        super.onResume();
        view.resume();
        // From ADB: am start -n io.freemytv/.SettingsActivity --ez screensaver true (straight to the preview).
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
        private static final int MAIN = 0, HOME_CHOICE = 1, HOME_SETTINGS = 2, REMOTE_KEYS = 3, PICKER = 4, SHELF_EDIT = 5, TRAKT = 6, TMDB = 7, STREMIO = 8, LANGUAGES = 9;
        private static final int TRAKT_ROW = 6, TMDB_ROW = 7, STREMIO_ROW = 8;
        private static final int TMDB_FROM = 1, TMDB_STREAM = 2, RENT_BUY_ROW = 3, TMDB_LANGS = 4, TMDB_ANIMATION = 5, TMDB_CATALOGS = 6;   // the TMDB screen's rows after Region   // the main menu's rows that open them
        private static final float LIST_ROW_H = 68, LIST_ROW_GAP = 6;
        private static final int PICK_VISIBLE = 9;
        private static final float LIST_X = 1000, LIST_R = 1824, LIST_TOP = 186, ROW_GAP = 8, CHOICE_H = 104;
        // The main menu: ten rows, then the comparison, the picks (with the Watchmode credit) and two notices, all on
        // one 1080 px screen.
        private static final float MAIN_TOP = 122, ROW_H = 56, MAIN_GAP = 4;
        private static final int AMBER = 0xFFFFC266, INK = 0xFF1C1C1E;

        private final SettingsActivity activity;
        private final Scene scene = new Scene();
        private final MaterialExpressive material = new MaterialExpressive();
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Spring[] pop = new Spring[12];
        private final List<String> appPkgs = new ArrayList<>(), appLabels = new ArrayList<>();
        // The list picker (the app of a remote button): what it shows, which one is in use, where we are.
        private final List<String> pickLabels = new ArrayList<>();
        // The shelf editor (also the top buttons' one, with editingTop): what it can hold (selected ones first, in
        // order) and the selection.
        private final List<String[]> shelfRows = new ArrayList<>();
        private final List<String> shelfOrder = new ArrayList<>();
        private boolean editingTop, editingRows;     // editingRows: the poster rows (Free My TV > TMDB)
        private int pickFor, pickCurrent, pickScroll;   // pickFor: the remote button (index + 1)
        private static final int PICK_CITY = 0, PICK_LANGUAGE = -1, PICK_REGION = -2;
        private final List<String> regionCodes = new ArrayList<>();      // the region picker: "auto", then the countries
        private final List<String[]> cities = new ArrayList<>();
        private String cityStatus = "";
        private final RectF tmp = new RectF(), rowRect = new RectF(), focusBox = new RectF();
        private JSONObject state = new JSONObject(), recs = new JSONObject();
        private boolean hasKey, stremioOn;      // stremioOn: the Stremio app is on the TV (looked up every few seconds, not per frame)
        private final List<String[]> langRows = new ArrayList<>();     // the Languages screen: {code, name}, "all" first
        private final List<String[]> stremioRows = new ArrayList<>();     // the Stremio screen: {key, name}, "missing" first
        private int screen = MAIN, focus;
        private boolean running, okDown, backDown, wasBusy;
        private StaticLayout errorLayout;   // the last error, wrapped (rebuilt only when it changes)
        private String errorShown;
        private final Spoken spoken;
        private long startedAt, lastNanos, pendingAt, lastSky;
        private int w, h;
        private float k = 1;
        private float currentNight;
        private Paint title, subtitle, version, rowLabel, rowValue, rowSub, header, cellLabel, cellValue, warn, spinner, white, shadow;
        private TextPaint notice;

        private final Runnable poll = new Runnable() {
            @Override public void run() {
                if (!running) return;
                state = HomeMode.state(getContext());
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
                handler.postDelayed(this, busy() ? 500 : 2000);
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
            setContentDescription("Free My TV");
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
            if (screen == TMDB) return row == RENT_BUY_ROW && !Prefs.flatrateOnly();     // everything is included then
            return screen == MAIN && ((row == TRAKT_ROW && !Trakt.configured()) || (row == TMDB_ROW && !Recs.hasKey(getContext()))
                    || (row == STREMIO_ROW && !stremioOn));
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
                                : freeActive() ? "Free My TV" : Ui.tr("Original");
                        return Ui.trf("Home Screen, {mode}", "mode", value);
                    }
                    String[] names = {Ui.tr("Screensaver, preview"), Ui.tr("Home Settings"),
                            Ui.tr("Shelf"), Ui.tr("Top Buttons"), Ui.tr("Remote Buttons"),
                            Ui.tr("Trakt") + ", " + traktStatus(), Ui.tr("TMDB") + ", " + tmdbStatus(),
                            Ui.tr("Stremio") + ", " + stremioStatus(), Ui.tr("TV Settings")};
                    return row - 1 < names.length ? names[row - 1] : "";
                }
                case TRAKT:
                    return traktAction();
                case TMDB: {
                    String[] labels = tmdbLabels(), values = tmdbValues();
                    return row < labels.length ? labels[row] + ", " + values[row] : "";
                }
                case LANGUAGES: {
                    int at = pickScroll + row;
                    return at < langRows.size() ? langRows.get(at)[1] + (langChecked(at) ? Ui.tr(", selected") : "") : "";
                }
                case STREMIO: {
                    int at = pickScroll + row;
                    return at < stremioRows.size() ? stremioRows.get(at)[1] + ", " + stremioValue(at) : "";
                }
                case HOME_CHOICE: {
                    String name = row == 0 ? "Free My TV, " + Ui.tr("no ads, with the new screensaver")
                            : "Original, " + Ui.tr("the TV's factory home screen");
                    boolean inUse = (row == 0) == freeActive();
                    return name + (inUse ? Ui.tr(". In use") : "") + ", " + Ui.trf("{i} of {n}", "i", row + 1, "n", 2);
                }
                case HOME_SETTINGS:
                case REMOTE_KEYS: {
                    boolean keys = screen == REMOTE_KEYS;
                    String[] labels = keys ? keyLabels() : settingLabels(), values = keys ? keyValues() : settingValues();
                    return row < labels.length ? labels[row] + ", " + values[row] : "";
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
            material.setSize(w, h);
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
            white = new Paint(Paint.ANTI_ALIAS_FLAG);
            white.setColor(0xFFF5F5F7);
            shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadow.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
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
                case MAIN: return 10;
                case TRAKT: return 1;
                case TMDB: return tmdbLabels().length;
                case STREMIO: return Math.min(PICK_VISIBLE, stremioRows.size());
                case LANGUAGES: return Math.min(PICK_VISIBLE, langRows.size());
                case HOME_CHOICE: return 2;
                case HOME_SETTINGS: return settingLabels().length;
                case PICKER: return Math.min(PICK_VISIBLE, pickLabels.size());
                case SHELF_EDIT: return Math.min(PICK_VISIBLE, shelfRows.size());
                default: return Prefs.KEYS.length;
            }
        }

        @Override protected void onDraw(Canvas c) {
            if (w == 0) return;
            currentNight = Prefs.theme().equals("light") ? 0 : Prefs.theme().equals("dark") ? 1
                    : (HomeData.get(getContext()).isNight(System.currentTimeMillis()) ? 1 : 0);
            if (Themes.expressive()) {
                title.setColor(material.color(MaterialExpressive.ON_SURFACE, currentNight));
                subtitle.setColor(material.color(MaterialExpressive.ON_VARIANT, currentNight));
                version.setColor(material.color(MaterialExpressive.ON_VARIANT, currentNight));
                header.setColor(material.color(MaterialExpressive.ON_VARIANT, currentNight));
                cellLabel.setColor(material.color(MaterialExpressive.ON_VARIANT, currentNight));
                cellValue.setColor(material.color(MaterialExpressive.ON_SURFACE, currentNight));
                rowSub.setColor(material.color(MaterialExpressive.ON_VARIANT, currentNight));
                notice.setColor(material.color(MaterialExpressive.ON_VARIANT, currentNight));
                warn.setColor(material.color(MaterialExpressive.TERTIARY, currentNight));
                white.setColor(material.color(MaterialExpressive.PRIMARY_CONTAINER, currentNight));
            } else {
                title.setColor(0xFFFFFFFF);
                subtitle.setColor(0x99FFFFFF);
                version.setColor(0x66FFFFFF);
                header.setColor(0x73FFFFFF);
                cellLabel.setColor(0xD9FFFFFF);
                cellValue.setColor(0xD9FFFFFF);
                rowSub.setColor(0x8CFFFFFF);
                notice.setColor(0xB3FFFFFF);
                warn.setColor(AMBER);
                white.setColor(0xFFF5F5F7);
            }
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
            if (Themes.expressive()) {
                tmp.set(0, 0, w, h);
                material.background(c, tmp, currentNight);
            } else scene.drawBackground(c);

            // On the left: big symbol, title, description.
            float cx = 500 * k;
            Symbols.draw(getContext(), c, "sparkles", cx, 400 * k, 150 * k, 0xEBFFFFFF);
            c.drawText("Free My TV", cx, 612 * k, title);
            c.drawText(Ui.tr("Your TV's home screen and screensaver"), cx, 660 * k, subtitle);
            c.drawText(versionName(getContext()), cx, 704 * k, version);

            if (screen == MAIN) drawMain(c, seconds); else if (screen == HOME_CHOICE) drawChoice(c); else if (screen == PICKER) drawPicker(c); else if (screen == SHELF_EDIT) drawShelfEditor(c); else if (screen == TRAKT) drawTrakt(c); else if (screen == TMDB) drawTmdb(c); else if (screen == STREMIO) drawStremio(c); else if (screen == LANGUAGES) drawLanguages(c); else drawList(c);
        }

        private void drawMain(Canvas c, float seconds) {
            boolean working = busy();
            String busyText = busyText();
            String mode = freeActive() ? "Free My TV" : Ui.tr("Original");
            String[] labels = {Ui.tr("Home Screen"), Ui.tr("Screensaver"),
                    Ui.tr("Home Settings"), Ui.tr("Shelf"), Ui.tr("Top Buttons"),
                    Ui.tr("Remote Buttons"), Ui.tr("Trakt"), Ui.tr("TMDB"), Ui.tr("Stremio"), Ui.tr("TV Settings")};
            String[] values = {working ? "" : mode, Ui.tr("Preview"), "", "", "", "", traktStatus(), tmdbStatus(), stremioStatus(), ""};
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
                    // Nothing to open: no chevron, and the reason where the value goes.
                    rowValue.setColor(blend(0x73FFFFFF, 0x731C1C1E, f));
                    c.drawText(values[i], right, base, rowValue);
                } else if (i >= 2) {
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
            y = drawComparison(c, y);
            drawNotices(c, y + 40 * k);
        }

        /** The picks' status, and the credits TMDB and Watchmode ask for (on the TMDB screen). */
        private float drawRecs(Canvas c, float y) {
            float x = LIST_X * k + 30 * k;
            c.drawText(Ui.tr("RECOMMENDATIONS"), x, y, header);
            String line;
            String error = recs.optString("error");
            int count = recs.optInt("count");
            if (!hasKey) line = Ui.tr("No TMDB key: see the README (config.properties).");
            else if (error.equals(Recs.NO_KNOWN_APPS)) line = Ui.tr("None of the TV's streaming apps can give picks.");
            else if (error.equals(Recs.NO_APPS)) line = Ui.trf("TMDB has none of the TV's apps in {region}.", "region", Regions.name(Recs.region()));
            else if (!error.isEmpty()) line = Ui.trf("Error: {error}.", "error", error);
            else if (count > 0 && !Prefs.recommendFromApps()) line = Ui.trf("On · {n} titles, 3 new ones every hour.", "n", count);
            else if (count > 0) line = Ui.trf("On · {n} titles from the TV's apps, 3 new ones every hour.", "n", count);
            else line = Ui.tr("Fetching titles…");
            c.drawText(TextUtils.ellipsize(line, notice, 790 * k, TextUtils.TruncateAt.END).toString(), x, y + 42 * k, notice);
            version.setTextAlign(Paint.Align.LEFT);
            c.drawText(Ui.tr("This product uses the TMDB API but is not endorsed or certified by TMDB."), x, y + 78 * k, version);
            if (!Config.WATCHMODE_KEY.trim().isEmpty()) {
                c.drawText(Ui.tr("Links to the titles by Watchmode."), x, y + 108 * k, version);
            }
            version.setTextAlign(Paint.Align.CENTER);
            return y + (Config.WATCHMODE_KEY.trim().isEmpty() ? 78 : 108) * k;
        }

        /** List row: no background; focused, white with a shadow (the Apple TV focus). Returns how focused it is. */
        private float drawRowBackground(Canvas c, int i) {
            float f = Ui.clamp(pop[i].value);
            if (f > 0.01f) {
                float s = 1 + 0.02f * f;
                float cx = rowRect.centerX(), cy = rowRect.centerY();
                tmp.set(cx - rowRect.width() * s / 2, cy - rowRect.height() * s / 2, cx + rowRect.width() * s / 2, cy + rowRect.height() * s / 2);
                float r = 18 * k * s;
                if (Themes.expressive()) {
                    material.panel(c, tmp, r, currentNight, true);
                    material.focused(c, tmp, r, f, currentNight);
                } else {
                    tmp.offset(0, 10 * k * f);
                    shadow.setColor(Ui.alpha(0xFF000000, 0.4f * f));
                    c.drawRoundRect(tmp, r, r, shadow);
                    tmp.offset(0, -10 * k * f);
                    white.setAlpha(Math.round(255 * f));
                    c.drawRoundRect(tmp, r, r, white);
                }
                rowRect.set(tmp);
            }
            return f;
        }

        private float drawComparison(Canvas c, float y) {
            float x = LIST_X * k + 30 * k, colA = LIST_R * k - 220 * k, colB = LIST_R * k - 30 * k;
            c.drawText(Ui.tr("COMPARISON"), x, y, header);
            cellValue.setColor(Themes.expressive() ? Ui.alpha(material.color(MaterialExpressive.ON_VARIANT, currentNight), 0.45f) : 0x73FFFFFF);
            c.drawText(Ui.tr("Original"), colA, y, cellValue);
            c.drawText("Free My TV", colB, y, cellValue);
            cellValue.setColor(Themes.expressive() ? material.color(MaterialExpressive.ON_SURFACE, currentNight) : 0xD9FFFFFF);
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

        private void drawNotices(Canvas c, float y) {
            float x = LIST_X * k + 30 * k;
            c.drawText(Ui.tr("NOTICES"), x, y, header);
            JSONArray notices = state.optJSONArray("notices");
            int shown = 0;
            for (int i = 0; notices != null && i < notices.length() && shown < 2; i++) {
                JSONObject o = notices.optJSONObject(i);
                if (o == null) continue;
                String line = ago(o.optLong("at")) + " · " + o.optString("text");
                c.drawText(TextUtils.ellipsize(line, notice, 790 * k, TextUtils.TruncateAt.END).toString(), x, y + (42 + 38 * shown) * k, notice);
                shown++;
            }
            if (shown == 0) c.drawText(Ui.tr("None yet."), x, y + 42 * k, notice);
        }

        /** Home screen choice: Free My TV or the original, with a check on the one in use. */
        private void drawChoice(Canvas c) {
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, headingColor());
            c.drawText(Ui.tr("Home Screen"), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            String[][] options = {{"Free My TV", Ui.tr("No ads, with the new screensaver")},
                    {Ui.tr("Original"), Ui.tr("The TV's factory home screen")}};
            int current = freeActive() ? 0 : 1;
            for (int i = 0; i < 2; i++) {
                float y = (LIST_TOP + 20 + i * (CHOICE_H + ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + CHOICE_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(0xF2FFFFFF, INK, f));
                rowSub.setColor(blend(0x8CFFFFFF, 0x991C1C1E, f));
                c.drawText(options[i][0], rowRect.left + 30 * k, rowRect.top + 44 * k, rowLabel);
                c.drawText(options[i][1], rowRect.left + 30 * k, rowRect.top + 80 * k, rowSub);
                if (i == current) {
                    float cw = Symbols.width(getContext(), "check", 26 * k);
                    Symbols.draw(getContext(), c, "check", rowRect.right - 30 * k - cw / 2, rowRect.centerY(), 26 * k, blend(0xFFFFFFFF, INK, f));
                }
            }
            Paint hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            c.drawText(Ui.tr("Switching takes a few seconds; the screen blinks while it measures."), LIST_X * k + 30 * k,
                    (LIST_TOP + 20 + 2 * (CHOICE_H + ROW_GAP) + 40) * k, hint);
        }

        private int blend(int a, int b, float f) {
            if (Themes.expressive() && (a & 0x00FFFFFF) == 0x00FFFFFF && (b & 0x00FFFFFF) == (INK & 0x00FFFFFF)) {
                int role = (a >>> 24) < 0xC0 ? MaterialExpressive.ON_VARIANT : MaterialExpressive.ON_SURFACE;
                a = (a & 0xFF000000) | (material.color(role, currentNight) & 0x00FFFFFF);
                b = (b & 0xFF000000) | (material.color(MaterialExpressive.ON_PRIMARY_CONTAINER, currentNight) & 0x00FFFFFF);
            }
            int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
            int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
            return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                    | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
        }

        private int headingColor() {
            return Themes.expressive() ? material.color(MaterialExpressive.ON_SURFACE, currentNight) : 0xFFFFFFFF;
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
                return "Free My TV " + c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                return "Free My TV";
            }
        }

        boolean onKey(KeyEvent e) {
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
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
                    } else if (down && (screen == HOME_SETTINGS || screen == REMOTE_KEYS || screen == TMDB || screen == STREMIO || screen == LANGUAGES)) {
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
                            } else if (screen == PICKER && (pickFor == PICK_CITY || pickFor == PICK_LANGUAGE)) {
                                focus = pickFor == PICK_CITY ? CITY_ROW : 0;
                                screen = HOME_SETTINGS;
                            } else if (screen == PICKER) {
                                focus = pickFor - 1;
                                screen = REMOTE_KEYS;
                            } else if (screen == TRAKT) {
                                Trakt.get(getContext()).cancelLogin();
                                focus = TRAKT_ROW;
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

        // The sign-in code as a QR code (the address with the code in it, so the phone's camera needs no typing).
        private final Paint qrInk = new Paint(), qrBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private String qrFor = "";
        private android.graphics.Path qrPath;
        private int qrModules;

        private void drawQr(Canvas c, String code, float x, float top) {
            if (!code.equals(qrFor)) {
                qrFor = code;
                boolean[][] m = Qr.encode("https://trakt.tv/activate/" + code);
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
                case Trakt.ERR_REFUSED: return Ui.tr("Trakt refused the Client ID: check trakt_client_id in config.properties.");
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
                drawQr(c, t.loginCode(), x, y + 178 * k);
            } else if (state == Trakt.STARTING) {
                c.drawText(Ui.tr("Asking Trakt for a code…"), x, y, notice);
            } else {
                String error = traktError(t.loginError());
                if (!error.isEmpty()) y = drawWrapped(c, error, x, y, 24 * k);
                else if (t.needsReconnect() && !t.connected()) y = drawWrapped(c, Ui.tr("Trakt asked you to sign in again."), x, y, 24 * k);
                if (t.connected()) {
                    y = drawWrapped(c, Ui.tr("Continue Watching and Watchlist show under the cards on the home screen."), x, y, 14 * k);
                    drawWrapped(c, Ui.tr("Free My TV only reads your Trakt lists."), x, y, 0);
                } else {
                    y = drawWrapped(c, Ui.tr("Connect your Trakt account to see Continue Watching and Watchlist under the home screen's cards."), x, y, 14 * k);
                    drawWrapped(c, Ui.tr("You sign in on your phone or computer with a short code. Free My TV only reads your lists."), x, y, 0);
                }
            }
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
            return new String[] {Ui.tr("Region"), Ui.tr("Recommend from"), Ui.tr("Only titles I can stream"), Ui.tr("Include rent or buy"),
                    Ui.tr("Languages"), Ui.tr("Hide cartoons and anime"), Ui.tr("Catalogs")};
        }

        private String[] tmdbValues() {
            int on = 0;
            for (String key : Prefs.catalogRows()) if (Catalogs.title(key) != null) on++;
            return new String[] {regionValue(), Prefs.recommendFromApps() ? Ui.tr("My apps") : Ui.tr("Anything popular"),
                    onOff(Prefs.flatrateOnly()), onOff(Prefs.flatrateOnly() && Prefs.includeRentBuy()), languagesValue(),
                    onOff(Prefs.hideAnimation()), Ui.trf("{i} of {n}", "i", on, "n", Catalogs.ALL.length)};
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

        // MARK: - Languages (Free My TV > TMDB)

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
            Paint hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            c.drawText(Ui.tr("The language a title was made in. OK picks or drops one."), LIST_X * k + 30 * k,
                    (LIST_TOP + 20 + rows * (LIST_ROW_H + LIST_ROW_GAP) + 36) * k, hint);
        }

        // MARK: - Stremio

        /** The main menu's value: whether Stremio is there, and whether anything is sent to it. */
        private String stremioStatus() {
            if (!stremioOn) return Ui.tr("Not installed");
            boolean any = Prefs.stremioForMissing();
            for (String[] s : Recs.serviceNames()) any |= Prefs.toStremio(s[0]);
            return onOff(any || Prefs.toStremio(Routing.OTHER));
        }

        /** The first row is "use Stremio for what is missing", then each service we know, then the ones we don't. */
        private void openStremio() {
            stremioRows.clear();
            stremioRows.add(new String[] {"", Ui.tr("Use Stremio for what is missing")});
            for (String[] s : Recs.serviceNames()) stremioRows.add(s);
            stremioRows.add(new String[] {Routing.OTHER, Ui.tr("Other services")});
            pickScroll = 0;
            focus = 0;
            screen = STREMIO;
            for (Spring s : pop) s.snap(0);
        }

        private String stremioValue(int at) {
            String key = stremioRows.get(at)[0];
            return key.isEmpty() ? onOff(Prefs.stremioForMissing()) : Prefs.toStremio(key) ? Ui.tr("Stremio") : Ui.tr("Its app");
        }

        private void toggleStremio() {
            String key = stremioRows.get(pickScroll + focus)[0];
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
                rowLabel.setColor(blend(0xF2FFFFFF, INK, f));
                rowValue.setColor(blend(0xB3FFFFFF, 0xB31C1C1E, f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(TextUtils.ellipsize(stremioRows.get(pickScroll + i)[1], new TextPaint(rowLabel), 560 * k, TextUtils.TruncateAt.END).toString(),
                        rowRect.left + 30 * k, base, rowLabel);
                c.drawText(stremioValue(pickScroll + i), rowRect.right - 30 * k, base, rowValue);
            }
            Paint hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            c.drawText(Ui.tr("Each service opens in its own app or in Stremio. OK changes it."), LIST_X * k + 30 * k,
                    (LIST_TOP + 20 + rows * (LIST_ROW_H + LIST_ROW_GAP) + 36) * k, hint);
        }

        // MARK: - Home Settings and Remote Buttons

        private static final String[] UNITS = {"auto", "C", "F"}, THEMES = {"auto", "light", "dark"};
        /** Home Settings: color scheme and visual theme are independent controls. */
        private static final int LOOK_ROW = 3, CITY_ROW = 6, WATCH_NEXT_ROW = 7, COIN_ROWS = 9;

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
            l.add(Ui.tr("Quotes widget"));
            l.add("Bitcoin");
            l.add("Ethereum");
            l.add("Solana");
            if (dollar()) l.add(Ui.tr("Dollar"));
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
            v[CITY_ROW] = !cityStatus.isEmpty() ? cityStatus : Prefs.city().isEmpty() ? "—" : Prefs.city();
            v[WATCH_NEXT_ROW] = onOff(Prefs.watchNext() && WatchNext.permitted(getContext()));
            v[8] = onOff(Prefs.quotes());
            // No quotes widget, nowhere to show prices: the coins read as off (and stay locked) until it is back on.
            for (int i = COIN_ROWS; i < v.length; i++) v[i] = onOff(Prefs.quotes() && Prefs.coin(i - COIN_ROWS));
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

        private String[] keyLabels() {
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
            String[] v = new String[Prefs.KEYS.length];
            for (int i = 0; i < v.length; i++) {
                int at = appPkgs.indexOf(Prefs.keyApp(Prefs.KEYS[i]));
                v[i] = at >= 0 ? appLabels.get(at) : Ui.tr("Not installed");
            }
            return v;
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
            if (RemoteKeys.SKYWORTH) {      // the gear button: Android's own Settings (the stock launcher's panel is off)
                appPkgs.add(Prefs.TV_SETTINGS);
                appLabels.add(Ui.tr("TV Settings"));
            }
            for (String[] f : found) {
                appPkgs.add(f[0]);
                appLabels.add(f[1]);
            }
        }

        /** Changes the focused row's value one step (OK and → go forward, ← goes back). */
        private void adjust(int dir) {
            if (screen == LANGUAGES) {
                toggleLanguage();
                return;
            }
            if (screen == STREMIO) {
                toggleStremio();
                return;
            }
            if (screen == TMDB) {
                if (focus == TMDB_FROM) Prefs.setRecommendFromApps(!Prefs.recommendFromApps());
                else if (focus == TMDB_STREAM) Prefs.setFlatrateOnly(!Prefs.flatrateOnly());
                else if (focus == RENT_BUY_ROW && !disabledRow(RENT_BUY_ROW)) Prefs.setIncludeRentBuy(!Prefs.includeRentBuy());
                else if (focus == TMDB_ANIMATION) Prefs.setHideAnimation(!Prefs.hideAnimation());
                return;
            }
            if (screen == REMOTE_KEYS) {
                int n = appPkgs.size();
                int at = appPkgs.indexOf(Prefs.keyApp(Prefs.KEYS[focus]));
                int next = at < 0 ? (dir > 0 ? 0 : n - 1) : (at + dir + n) % n;
                Prefs.setKeyApp(Prefs.KEYS[focus], appPkgs.get(next));
                return;
            }
            switch (focus) {
                case 0: break;   // OK opens the language picker.
                case 1: {
                    int at = java.util.Arrays.asList(UNITS).indexOf(Prefs.temperature());
                    Prefs.setTemperature(UNITS[(at + dir + UNITS.length) % UNITS.length]);
                    break;
                }
                case 2: {
                    int at = java.util.Arrays.asList(THEMES).indexOf(Prefs.theme());
                    Prefs.setTheme(THEMES[(at + dir + THEMES.length) % THEMES.length]);
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
                case 8: Prefs.setQuotes(!Prefs.quotes()); break;
                default:
                    if (!Prefs.quotes()) return;
                    Prefs.setCoin(focus - COIN_ROWS, !Prefs.coin(focus - COIN_ROWS));
                    break;
            }
            HomeData.get(getContext()).settingsChanged();
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

        private boolean scrolling() {
            return screen == PICKER || screen == SHELF_EDIT || screen == STREMIO || screen == LANGUAGES;
        }

        private int listSize() {
            return screen == SHELF_EDIT ? shelfRows.size() : screen == STREMIO ? stremioRows.size() : screen == LANGUAGES ? langRows.size() : pickLabels.size();
        }

        // MARK: - Shelf editor

        private void openShelfEditor(boolean top) {
            editingTop = top;
            editingRows = false;
            shelfOrder.clear();
            if (top) {
                shelfOrder.addAll(HomeApps.topButtonKeys());
                rebuildShelfRows(HomeApps.topCandidates());
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

        /** The poster rows (Free My TV > TMDB): every row TMDB can fill, the ones that are on first, in their order. */
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
            Paint hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            float hy = (LIST_TOP + 20 + rows * (LIST_ROW_H + LIST_ROW_GAP) + 36) * k;
            c.drawText(editingRows ? Ui.tr("OK turns a row on or off. ← → change the order.")
                    : editingTop ? Ui.tr("OK adds or removes a button. ← → change the order.")
                    : Ui.trf("OK adds or removes (up to {n}). ← → change the order.", "n", HomeApps.MAX_SHELF),
                    LIST_X * k + 30 * k, hy, hint);
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
            } else {
                pickLabels.addAll(appLabels);
                pickCurrent = appPkgs.indexOf(Prefs.keyApp(Prefs.KEYS[which - 1]));
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
                    : pickFor == PICK_LANGUAGE ? Ui.tr("Language") : keyLabels()[pickFor - 1];
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
            Paint hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            float hy = (LIST_TOP + 20 + rows * (LIST_ROW_H + LIST_ROW_GAP) + 36) * k;
            c.drawText((pickScroll + focus + 1) + " / " + pickLabels.size() + "  ·  " + Ui.tr("OK picks, Back cancels."),
                    LIST_X * k + 30 * k, hy, hint);
        }

        private void drawList(Canvas c) {
            boolean keys = screen == REMOTE_KEYS;
            String[] labels = keys ? keyLabels() : settingLabels();
            String[] values = keys ? keyValues() : settingValues();
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, headingColor());
            c.drawText(keys ? Ui.tr("Remote Buttons") : Ui.tr("Home Settings"),
                    LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            for (int i = 0; i < labels.length; i++) {
                float y = (LIST_TOP + 20 + i * (LIST_ROW_H + LIST_ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(0xF2FFFFFF, INK, f));
                boolean locked = !keys && i >= COIN_ROWS && !Prefs.quotes();
                rowValue.setColor(blend(locked ? 0x59FFFFFF : 0xB3FFFFFF, locked ? 0x591C1C1E : 0xB31C1C1E, f));
                if (locked) rowLabel.setColor(blend(0x73FFFFFF, 0x731C1C1E, f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(labels[i], rowRect.left + 30 * k, base, rowLabel);
                c.drawText(values[i], rowRect.right - 30 * k, base, rowValue);
            }
            Paint hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            float hy = (LIST_TOP + 20 + labels.length * (LIST_ROW_H + LIST_ROW_GAP) + 36) * k;
            c.drawText(Ui.tr("OK or the arrows change the value."), LIST_X * k + 30 * k, hy, hint);
            if (keys && !RemoteKeys.SKYWORTH) {
                c.drawText(RemoteKeys.VESTEL ? Ui.tr("YouTube, Prime Video and Google Play: the TV itself controls those buttons.")
                        : RemoteKeys.KTC ? Ui.tr("Netflix, YouTube and Prime Video: the TV itself controls those buttons.")
                        : RemoteKeys.SONY ? Ui.tr("Sony default: the button opens what it always did.")
                        : RemoteKeys.FORMOVIE ? Ui.tr("The other buttons: the TV itself controls them.")
                        : RemoteKeys.supported() ? Ui.tr("Disney and Globoplay: the TV itself controls those buttons.")
                        : Ui.tr("TCL remote buttons: on this TV they work as always."), LIST_X * k + 30 * k, hy + 38 * k, hint);
            }
        }

        private void activate() {
            Context c = getContext();
            if (screen == HOME_CHOICE) {
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
                focus = CITY_ROW;
                screen = HOME_SETTINGS;
            } else if (screen == PICKER && pickFor == PICK_LANGUAGE) {
                Prefs.setLanguage(languageChoices()[pickScroll + focus]);
                focus = 0;
                screen = HOME_SETTINGS;
            } else if (screen == PICKER && pickFor == PICK_REGION) {
                Prefs.setRegion(regionCodes.get(pickScroll + focus));
                focus = 0;
                screen = TMDB;
            } else if (screen == TMDB) {
                if (focus == 0) {
                    openPicker(PICK_REGION);
                } else if (focus == TMDB_CATALOGS) {
                    openRowsEditor();
                } else if (focus == TMDB_LANGS) {
                    openLanguages();
                } else {
                    adjust(1);
                    invalidate();
                    return;
                }
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
                Prefs.setKeyApp(Prefs.KEYS[pickFor - 1], appPkgs.get(at));
                focus = pickFor - 1;
                screen = REMOTE_KEYS;
            } else if (screen == SHELF_EDIT) {
                toggleShelfApp();
                invalidate();
                return;
            } else if (screen == TRAKT) {
                traktPressed();
                invalidate();
                spoken.focusMoved();
                return;
            } else if (screen == REMOTE_KEYS) {
                openPicker(focus + 1);
            } else if (screen == HOME_SETTINGS && focus == 0) {
                openPicker(PICK_LANGUAGE);
            } else if (screen == HOME_SETTINGS && focus == CITY_ROW) {
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
                focus = 0;
            } else if (focus == 3) {
                openShelfEditor(false);
            } else if (focus == 4) {
                openShelfEditor(true);
            } else if (focus == 5) {
                HomeMode.ensureRemoteService(c, 0);   // the buttons work from inside other apps through this service
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
                if (disabledRow(STREMIO_ROW)) return;   // Stremio isn't on the TV: nothing to open
                openStremio();
            } else {
                activity.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            for (Spring s : pop) s.snap(0);
            invalidate();
            spoken.focusMoved();
        }
    }
}
