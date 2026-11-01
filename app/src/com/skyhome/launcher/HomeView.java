package com.skyhome.launcher;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The home screen, styled after the Apple TV app with the macOS 27 glass: four pages one under another (HomePager),
 * ↓ past a page's last row to the next one that has something to show, ↑ back, Back to the first page's shelf.
 *  1. HomeTopPage: the weather and the icon-only buttons at the top, the picks in one of three styles (PicksOff,
 *     PicksCards, PicksSpotlight) and the shelf (HomeShelf).
 *  2. HomeWidgetsPage: the crypto quotes with their 24 h change (hidden when off).
 *  3. HomeWatchingPage: Continue Watching, Shows you watch, Watchlist (hidden without Trakt or Watch Next items).
 *  4. HomeCatalogPage: TMDB's catalogs, the focused title's details and trailer above one row.
 * Over them: All Apps (HomeSheet), the popover (HomePopover: the inputs, a poster's menu) and the toasts (HomeToasts).
 * This class is the host: the background (the picks' photo, the night look), the layers, the pager, the keys (each to
 * the page with the focus, or to the overlay on top), TalkBack's items, and the data the pages are given.
 * Away from the first page the background lightens (darkens at night). From sunset to sunrise, a night look: dark
 * background, smoked glass and white text. Measurements are in a 1920×1080 space (k = width / 1920; HomeGrid). The
 * screen is split into layers so the TV's weak GPU only redraws what changed, and a page change only moves them.
 */
final class HomeView extends ViewGroup implements Choreographer.FrameCallback, HomeData.Listener, Recs.Listener, Catalogs.Listener, Details.Listener {
    private static final long FADE_MS = 800;
    private static final int SKY = 0, HERO = 1, STATUS = 2, PLATTER = 3, TILES = 4, DRAWER = 5, SHEET = 6, TOAST = 7, CATALOG = 8, BROWSE = 9, POP = 11, GLOW = 12, BACKDROP = 13;
    static final ExecutorService LOADER = Executors.newSingleThreadExecutor();

    final Activity activity;
    private final Scene scene = new Scene();
    final Look look = Themes.create(Prefs.look(), scene);
    private final HomeData data;
    private final Recs recs;
    final Click click;
    final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences ui;
    private final Spoken spoken = new Spoken(this, new Spoken.Source() {
        @Override public void items(List<Integer> out) { a11yItems(out); }
        @Override public String text(int id) { return a11yText(id); }
        @Override public boolean bounds(int id, Rect out) { return a11yBounds(id, out); }
        @Override public int focused() { return a11yFocused(); }
        @Override public void focus(int id) { a11yFocus(id); }
        @Override public void click(int id) { activate(); }
        @Override public boolean clickable(int id) { return (id - 1) / 1000 != WIDGETS; }
        @Override public boolean holdable(int id) { return (id - 1) / 1000 == GRID && canToggleShelf((id - 1) % 1000); }
        @Override public void hold(int id) { a11yHold(); }
    });
    private final RectF a11yRect = new RectF();
    private final Layer sky, backdropLayer, glowLayer, heroLayer, status, platter, tiles, drawerLayer, sheetLayer, popLayer, toastLayer;
    /** With TalkBack on, page 2's quotes card can take the focus (it's only read). */
    private boolean widgetFocus;

    // Night look (like Apple's "Automatic" appearance): darker from sunset to sunrise, with a 2 s transition.
    private static final long NIGHT_MS = 2000;
    private boolean nightTarget;
    private float night, nightFrom;     // 0 = day, 1 = night
    private long nightAt;
    private final int[] glassNight = new int[Backdrop.GW * Backdrop.GH], frostNight = new int[Backdrop.FW * Backdrop.FH];
    private final int[] neutralGlassPx = new int[Backdrop.GW * Backdrop.GH], neutralFrostPx = new int[Backdrop.FW * Backdrop.FH];
    private boolean nightPair;          // the glass holds both photos (day and night), blended by `night`
    private Shader nightGradient;

    private Shader lightGradient;

    private static final long HOLD_MS = 500;
    private boolean okHeld, holdArmed;
    private final Runnable holdStart = () -> { if (this.okDown && this.holdArmed) hold(); };

    // Background
    private long bgFadeAt;
    private Backdrop bgFrom, bgTo;     // null = gradient (no photo)
    private final int[] frostMix = new int[Backdrop.FW * Backdrop.FH];

    // The pages, one under another (HomePager): 1 the top (HomeTopPage), 2 the widgets, 3 Trakt's and the TV's rows,
    // 4 TMDB's catalogs.
    private HomePager pager;
    private HomePager.Slot topSlot, widgetsSlot, watchingSlot, catalogSlot;
    private final HomeTopPage top;
    private final HomeWidgetsPage widgetsPage;
    private boolean pagerWasMoving;

    // The popover (HomePopover): under the input buttons, or beside a poster.
    private final HomePopover popover;
    private final RectF tileAnchor = new RectF(), popRow = new RectF();
    private final Catalogs catalogs;
    private final Layer catalogLayer;
    // Page 3, Trakt's and the TV's rows (HomeWatchingPage).
    private final HomeWatchingPage watchingPage;
    // Page 4, TMDB's catalogs (HomeCatalogPage).
    private final Layer browseLayer;
    private final Details details;
    private final HomeCatalogPage catalogPage;
    // "All Apps" (HomeSheet); holding OK there puts an app on the shelf or takes it off.
    private final HomeSheet sheet;
    private boolean shelfEdited;
    private long toggledAt;

    // Toasts (HomeToasts)
    private final HomeToasts toasts;

    // Frames
    private boolean running, motionDirty = true;
    private boolean idle;
    private long lastNanos, resumedAt;
    private static final long BURST_MS = 1500;
    private int w, h;
    private float k = 1;
    private boolean okDown, backDown;

    private Paint rowTitle, cardTitle, cardText, cardDim, cardDimRight, bigTemp, hourPaint, hourTemp;
    private Paint pricePaint, changePaint;
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Shadows shadows = new Shadows();
    /** What every page draws the same way: pictures, cards, posters, shadows. */
    final HomePainter painter;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF(), screen = new RectF();
    private android.graphics.ColorFilter veilFilter;
    private float veilAmount = -1;
    private int veilColor;
    private long textPricesAt = -1, textMinute = -1;
    private int textPrefs = -1;
    private String textLanguage;
    private String staleText;
    private final String[] priceText = new String[HomeData.COINS.length], changeText = new String[HomeData.COINS.length];

    /** One layer of the screen. It draws in full-screen coordinates: the glass needs each pixel's real position. */
    private final class Layer extends View {
        private final int kind;

        Layer(Context c, int kind) {
            super(c);
            this.kind = kind;
        }

        /** The glow and the home picture fade by their layer's alpha: straight onto what they draw, without an offscreen
         * buffer every frame (the picture already drew each of its parts at the same strength). */
        @Override public boolean hasOverlappingRendering() {
            return kind != GLOW && kind != BACKDROP;
        }

        @Override protected void onDraw(Canvas c) {
            if (rowTitle == null) return;
            c.translate(-getLeft(), -getTop());
            // The layer may be shifted (another page showing): the glass reads the background from where it really is.
            // The widgets and the top buttons are always drawn as if resting in place (they are only seen there and are
            // not redrawn while they slide).
            scene.setOffset(0, kind == DRAWER || kind == STATUS ? 0 : getTranslationY());
            shadows.dither = dark() > 0.5f;
            switch (kind) {
                case SKY: drawSky(c); break;
                case BACKDROP: drawHomeBackdrop(c); break;
                case GLOW: look.focusGlow(c, getWidth() / 2f, getHeight() / 2f, dark()); break;
                case HERO: top.draw(c); break;
                case STATUS: top.drawButtons(c); break;
                case PLATTER: top.shelf.drawGlass(c); break;
                case TILES: top.shelf.draw(c); break;
                case DRAWER: widgetsPage.draw(c); break;
                case CATALOG: watchingPage.draw(c); break;
                case BROWSE: catalogPage.draw(c); break;
                case SHEET: sheet.draw(c); break;
                case POP: popover.draw(c); break;
                default: toasts.draw(c); break;
            }
            scene.setOffset(0, 0);
        }
    }

    private final Runnable reloadSoon = this::reload;
    private static final long APPS_MAX_AGE_MS = 15 * 60_000;
    private final LauncherApps launcherApps;
    private boolean appsStale = true;
    private int appsVersion, appsGeneration;
    private long appsAt;

    private void reloadSoon() {
        handler.removeCallbacks(reloadSoon);
        handler.postDelayed(reloadSoon, 500);
    }

    /** pick_rotate_seconds: the picks move on by themselves, only while focus is elsewhere and nothing is open. */
    private final Runnable rotate = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (!top.onPicks() && !sheet.isOpen() && pager.current() == topSlot && top.picks.cards.size() > 3) top.picks.autoRotate();
            handler.postDelayed(this, Config.PICK_ROTATE_SECONDS * 1000L);
        }
    };

    /** A look with a moving background: the sky layer is redrawn ten times a second (the drift is slow), while it shows. */
    private final Runnable drift = new Runnable() {
        @Override public void run() {
            if (!running) return;
            // Paused while something moves or while it's covered: drawing the shapes again cost this TV ~40 ms a frame
            // (a 4K Google TV Stick). A redraw held back meanwhile happens now, under the cover, ready for when it shows.
            boolean runs = driftRuns();
            look.holdDrift(!runs);
            if (runs || (skyStale && still())) {
                skyStale = false;
                sky.invalidate();
            }
            if (look.m.focusGlow) updateGlowColor();       // it eases in with a new pick
            handler.postDelayed(this, 100);
        }
    };

    private static final long DRIFT_PAUSE_MS = 600;
    private long movedAt;
    /** The look's background is covered (Spotlight's photo, or the home picture); it changed while it couldn't be drawn. */
    private boolean skyCovered, skyStale;

    private boolean still() {
        return SystemClock.uptimeMillis() - movedAt > DRIFT_PAUSE_MS;
    }

    private boolean driftRuns() {
        return still() && !skyCovered;
    }

    /** The look's background draws again: right away, or, kept as a picture (a moving one), once nothing moves. */
    private void redrawSky() {
        if (look.m.animatedBackground && (skyCovered || !still())) skyStale = true;
        else sky.invalidate();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            wake();
            data.refreshIfStale();
            recs.refreshIfStale();
            catalogs.refreshWatchNext(false);       // the row may be the only one: nothing else would bring page 3 for it
            if (pager.current() != topSlot) catalogs.refreshIfStale();
            checkNotices();
            updatePalette();
            checkNight(true);
            if (!Prefs.spotlightOn() && !top.onPicks()) recs.publish();   // a new daily list: not under the focus
            if (!onCatalogPage()) catalogs.publish();      // the day's TMDB rows: only while the browse screen isn't open
            updatePicks();
            updateSleep();
            handler.postDelayed(this, 15_000);
        }
    };

    HomeView(Activity a) {
        super(a);
        activity = a;
        data = HomeData.get(a);
        recs = Recs.get(a);
        click = new Click(a);
        ui = a.getSharedPreferences("home-ui", Context.MODE_PRIVATE);
        launcherApps = (LauncherApps) a.getSystemService(Context.LAUNCHER_APPS_SERVICE);
        if (launcherApps != null) launcherApps.registerCallback(packages, handler);
        setFocusable(true);
        sky = new Layer(a, SKY);
        backdropLayer = new Layer(a, BACKDROP);
        glowLayer = new Layer(a, GLOW);
        heroLayer = new Layer(a, HERO);
        status = new Layer(a, STATUS);
        platter = new Layer(a, PLATTER);
        tiles = new Layer(a, TILES);
        drawerLayer = new Layer(a, DRAWER);
        catalogLayer = new Layer(a, CATALOG);
        browseLayer = new Layer(a, BROWSE);
        catalogs = Catalogs.get(a);
        painter = new HomePainter(this, catalogs, shadows);
        details = Details.get(a);
        top = new HomeTopPage(this, data, recs);
        widgetsPage = new HomeWidgetsPage(this);
        watchingPage = new HomeWatchingPage(this, catalogs);
        catalogPage = new HomeCatalogPage(this, catalogs, details);
        topSlot = new HomePager.Slot("top", false, () -> true, heroLayer, status, platter, tiles);
        widgetsSlot = new HomePager.Slot("widgets", true, () -> !widgetsPage.isEmpty(), drawerLayer);
        watchingSlot = new HomePager.Slot("watching", true, () -> !watchingPage.isEmpty(), catalogLayer);
        catalogSlot = new HomePager.Slot("catalogs", true, () -> !catalogPage.isEmpty(), browseLayer);
        pager = new HomePager(topSlot, widgetsSlot, watchingSlot, catalogSlot);
        LOADER.execute(LayoutCheck::readFlags);
        sheetLayer = new Layer(a, SHEET);
        popLayer = new Layer(a, POP);
        popover = new HomePopover(this, popLayer);
        sheet = new HomeSheet(this);
        toastLayer = new Layer(a, TOAST);
        toasts = new HomeToasts(this, scene, toastLayer);
        // Hardware layers on the GPU: only the one that changed is redrawn; moving, fading and scaling stay cheap.
        // Not on Android 8 to 11: on older GPUs (a Mi Box 3's Mali-450) redrawing and compositing a big layer on every
        // frame cost more than drawing straight to the screen (measured: focus on the picks 33 → 12 ms a frame, the
        // drawer 19 → 17 ms). heroLayer/status/platter/tiles/popLayer are page-1 content (the resting screen), so they
        // stay hardware-accelerated always; the other pages' layers and sheetLayer start off (they're also INVISIBLE
        // below), and the pager turns each one's GPU layer on only while its page shows (#memory-management, item 3:
        // ~25 MB of persistent GPU memory otherwise sat behind pages that may never be opened in a session).
        for (View v : new View[] {heroLayer, status, platter, tiles, popLayer}) v.setLayerType(Ui.LEGACY ? LAYER_TYPE_NONE : LAYER_TYPE_HARDWARE, null);
        // A moving background (gradients and big shapes) cost ~40 ms of GPU to paint: kept as a picture, the frames
        // between two drifts only copy it.
        if (look.m.animatedBackground) sky.setLayerType(LAYER_TYPE_HARDWARE, null);
        for (View v : new View[] {sky, backdropLayer, glowLayer, heroLayer, status, platter, tiles, drawerLayer, catalogLayer, browseLayer, sheetLayer, popLayer, toastLayer}) {
            v.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);   // TalkBack reads the items (Spoken)
            addView(v);
        }
        setContentDescription("Sky Home Launcher");
        drawerLayer.setVisibility(INVISIBLE);
        glowLayer.setVisibility(INVISIBLE);
        backdropLayer.setVisibility(INVISIBLE);
        popLayer.setVisibility(INVISIBLE);
        catalogLayer.setVisibility(INVISIBLE);
        browseLayer.setVisibility(INVISIBLE);
        top.setButtons(HomeApps.topButtons(a));
        checkNight(false);
        sheetLayer.setVisibility(INVISIBLE);
        scene.setMotion(0.6f, 0f);
        updatePalette();
        // First time: right away (a cold start opens with the apps already there); missing logos come from reload().
        top.setShelf(HomeApps.shelf(a, false));
    }

    // MARK: - Lifecycle (called by HomeActivity)

    void resume() {
        if (running) return;
        running = true;
        checkNight(false);
        long now = SystemClock.uptimeMillis();
        // Some remotes (Xiaomi's) send one Home press as a burst of 5 to 8, and each one pauses and resumes the home
        // screen: those after the first change nothing, so the lists are read only once.
        boolean again = resumedAt != 0 && now - resumedAt < BURST_MS;
        resumedAt = now;
        lastNanos = 0;
        data.addListener(this);
        recs.addListener(this);
        catalogs.addListener(this);
        details.addListener(this);
        data.refreshIfStale();
        recs.refreshIfStale();
        catalogs.refreshWatchNext(false);
        handler.postDelayed(tick, 15_000);
        if (look.m.animatedBackground) handler.postDelayed(drift, 100);
        if (Config.PICK_ROTATE_SECONDS > 0) handler.postDelayed(rotate, Config.PICK_ROTATE_SECONDS * 1000L);
        checkNotices();
        if (!again) reload();
        updatePalette();
        updatePicks();
        if (sheet.isOpen()) closeSheet();
        sheet.in.snap(sheet.in.target);
        motionDirty = true;
        invalidateAll();
        idle = false;
        Choreographer.getInstance().postFrameCallback(this);
        spoken.readAgain();
    }

    void pause() {
        if (!running) return;
        running = false;
        top.pause();
        handler.removeCallbacks(holdStart);
        okHeld = holdArmed = false;
        catalogPage.pause();
        Choreographer.getInstance().removeFrameCallback(this);
        handler.removeCallbacks(tick);
        handler.removeCallbacks(drift);
        handler.removeCallbacks(rotate);
        handler.removeCallbacks(reloadSoon);
        data.removeListener(this);
        recs.removeListener(this);
        catalogs.removeListener(this);
        details.removeListener(this);
        closePop(true);
        recs.publish();     // a new daily list waiting: shown while the home screen is away
        catalogs.publish();
    }

    void destroy() {
        pause();
        if (launcherApps != null) launcherApps.unregisterCallback(packages);
        click.release();
    }

    /** Starting state (Home pressed while the home screen is showing): the first page, first app focused, sheet closed. */
    void reset(boolean animated) {
        wake();
        top.reset(!animated);
        goTo(topSlot, !animated);
        if (sheet.isOpen()) sheet.close();
        closePop(!animated);
        if (animated) sheet.in.target = 0;
        else sheet.in.snap(0);
        watchingPage.top(!animated);
        retarget(!animated);
        motionDirty = true;
    }

    @Override public void onData() {
        wake();
        updatePalette();
        checkNight(true);
        top.invalidateAll();
        widgetsPage.invalidate();
        pagesChanged();      // the quotes may have come or gone: page 2 with them
    }

    @Override public void onRecs() {
        wake();
        updatePicks();
    }

    void invalidateAll() {
        for (View v : new View[] {backdropLayer, glowLayer, heroLayer, status, platter, tiles, drawerLayer}) v.invalidate();
        redrawSky();
    }

    // MARK: - Size and layers

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        float kk = width / 1920f;
        measure(sky, width, height);
        measure(backdropLayer, width, height);
        // Just the glow's circle: it follows the focus by moving, not by being drawn again.
        int glow = Math.max(1, Math.round(2 * look.glowRadius() * kk));
        measure(glowLayer, glow, glow);
        // Spotlight's photo fills the whole screen behind the shelf, so its layer does too.
        measure(heroLayer, width, Prefs.spotlightOn() ? height : Math.round(790 * kk));
        measure(status, Math.round(1160 * kk), Math.round(240 * kk));
        // Sized to what they draw (the glass and its shadow; the grown tiles and their shadows): smaller layers are
        // cheaper for the GPU to composite on every frame of a page change.
        measure(platter, width, Math.round(320 * kk));
        measure(tiles, width, Math.round(270 * kk));
        measure(drawerLayer, width, height);
        measure(catalogLayer, width, height);
        measure(browseLayer, width, height);
        measure(sheetLayer, width, height);
        measure(popLayer, popover.layerWidth(kk), popover.layerHeight(kk));
        measure(toastLayer, Math.round(1000 * kk), Math.round(220 * kk));
        setMeasuredDimension(width, height);
    }

    private static void measure(View v, int width, int height) {
        v.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l, height = b - t;
        float kk = width / 1920f;
        sky.layout(0, 0, width, height);
        backdropLayer.layout(0, 0, width, height);
        place(glowLayer, 0, 0);
        place(heroLayer, 0, 0);
        place(status, width - status.getMeasuredWidth(), 0);
        place(platter, 0, Math.round(750 * kk));
        place(tiles, 0, Math.round(790 * kk));
        drawerLayer.layout(0, 0, width, height);
        catalogLayer.layout(0, 0, width, height);
        browseLayer.layout(0, 0, width, height);
        sheetLayer.layout(0, 0, width, height);
        sheetLayer.setPivotX(width / 2f);
        sheetLayer.setPivotY(height / 2f);
        popover.place(kk);
        place(toastLayer, (width - toastLayer.getMeasuredWidth()) / 2, 0);
    }

    private static void place(View v, int x, int y) {
        v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        w = width;
        h = height;
        k = w / 1920f;
        screen.set(0, 0, w, h);
        scene.setSize(w, h);
        look.attach(getContext());
        look.setSize(w, h, k);
        makePaints();
        top.layout(k, w, h);
        widgetsPage.layout(k, w, h);
        watchingPage.layout(k, w, h);
        catalogPage.layout(k, w, h);
        updatePicks();
        motionDirty = true;
        wake();
    }

    /** The paints of what this class still draws: the background and page 2's quotes card. */
    private void makePaints() {
        lightGradient = null;
        rowTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        popover.layout(k, w, h);
        cardTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        cardText = Ui.text(Ui.medium, 29 * k, 0xF2FFFFFF);
        cardDim = Ui.numbers(Ui.medium, 23 * k, 0xADFFFFFF);
        cardDimRight = Ui.text(Ui.medium, 21 * k, 0x99FFFFFF);
        cardDimRight.setTextAlign(Paint.Align.RIGHT);
        bigTemp = Ui.numbers(Ui.light, 100 * k, 0xFFFFFFFF);
        hourPaint = Ui.numbers(Ui.medium, 22 * k, 0xADFFFFFF);
        hourPaint.setTextAlign(Paint.Align.CENTER);
        hourTemp = Ui.numbers(Ui.medium, 26 * k, 0xF2FFFFFF);
        hourTemp.setTextAlign(Paint.Align.CENTER);
        pricePaint = Ui.numbers(Ui.medium, 26 * k, 0xF2FFFFFF);
        pricePaint.setTextAlign(Paint.Align.RIGHT);
        changePaint = Ui.numbers(Ui.semibold, 21 * k, 0xFFFFFFFF);
        changePaint.setTextAlign(Paint.Align.CENTER);
        sheet.layout(k, w, h);
        toasts.layout(k, w);
        painter.layout(k);
    }

    // MARK: - Frames: 60 per second while something moves; nothing is redrawn when still

    @Override public void doFrame(long frameTimeNanos) {
        if (!running) return;
        float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (frameTimeNanos - lastNanos) / 1e9f);
        lastNanos = frameTimeNanos;
        long up = SystemClock.uptimeMillis();

        boolean topMoving = top.tick(dt);
        boolean gridMoving = sheet.tick(dt);
        boolean pagerMoving = pager.pos.step(dt);
        boolean sheetMoving = sheet.in.step(dt);
        boolean popMoving = popover.tick(dt);
        boolean widgetsMoving = widgetsPage.tick(dt);
        boolean catMoving = watchingPage.tick(dt);
        boolean browseMoving = catalogPage.tick(dt);
        boolean toastFading = toasts.tick(up);
        boolean bgFading = tickBackgroundFade(up);
        boolean nightMoving = tickNight(up);

        if (pagerMoving || sheetMoving || motionDirty) updateMotion();
        if (topMoving || gridMoving || pagerMoving || sheetMoving || popMoving || widgetsMoving || catMoving || browseMoving) movedAt = up;
        // Changing page only moves the cached layers: redrawing the glass on every frame made the TV drop frames
        // (measured: ~50 ms of GPU per frame). The app tiles have no glass; the widgets and the top buttons are drawn
        // resting in place; the glass shelf is redrawn once the pages stop, so the glass shows the background where it
        // ended up.
        top.updateClock();
        if (top.shelf.takeDirty()) tiles.invalidate();
        if (top.takeButtons() | bgFading) status.invalidate();
        if (top.takeDirty()) heroLayer.invalidate();
        if (widgetsPage.takeDirty() | widgetsMoving || bgFading) drawerLayer.invalidate();
        if (gridMoving | sheet.takeDirty()) sheetLayer.invalidate();
        if (openingItem != null && up - openingAt >= OPEN_LOADER_MS) {      // the waiting dots move
            watchingPage.invalidate();
            catalogPage.invalidate();
        }
        if (watchingPage.takeDirty() | catMoving) catalogLayer.invalidate();
        if (catalogPage.takeDirty() | browseMoving) browseLayer.invalidate();
        // The focus glow and the home picture are layers of their own: moving the focus or the pages doesn't draw the
        // whole background again, unless its colours follow the pages.
        boolean followsPager = pagerMoving && (!look.m.homeBackdrop || darkFollowsPager());
        if (followsPager) sky.invalidate();
        else if (bgFading) redrawSky();
        if (followsPager || bgFading) backdropLayer.invalidate();
        updateCover();
        if (look.m.animatedBackground) look.holdDrift(!driftRuns());
        boolean glowMoving = look.m.focusGlow && updateFocusGlow(dt);
        if (top.takeGlass() | bgFading || (pagerWasMoving && !pagerMoving)) platter.invalidate();
        pagerWasMoving = pagerMoving;
        motionDirty = false;

        // Busy: something is moving, or the sheet or a page under the first is open.
        boolean busy = topMoving || gridMoving || pagerMoving || glowMoving
                || sheetMoving || popMoving || popover.isOpen() || widgetsMoving || catMoving || browseMoving || toastFading || bgFading || nightMoving || sheet.isOpen() || sheet.in.value > 0.001f
                || pager.current() != topSlot || toasts.busy();
        idle = !busy;
        if (busy) Choreographer.getInstance().postFrameCallback(this);
        else Choreographer.getInstance().postFrameCallbackDelayed(this, 1000);
    }

    void wake() {
        if (!running || !idle) return;
        idle = false;
        lastNanos = 0;
        Choreographer.getInstance().removeFrameCallback(this);
        Choreographer.getInstance().postFrameCallback(this);
    }

    /** Pages and sheet: the layers' positions and opacity (without redrawing their content). */
    private void updateMotion() {
        float s = Ui.clamp(sheet.in.value);
        pager.place(h, 1 - s);
        showLayer(sheetLayer, s > 0.001f || sheet.isOpen());
        sheetLayer.setAlpha(s);
        float scale = 1.04f - 0.04f * s;
        sheetLayer.setScaleX(scale);
        sheetLayer.setScaleY(scale);
    }

    /**
     * Visibility and the GPU layer together, for a page whose content doesn't need its hardware layer to exist at
     * all while the page is fully closed (#memory-management, item 3): the layer comes back the moment it's needed
     * again, same as Android's own documented pattern for a hardware layer as a temporary aid, not a permanent canvas.
     */
    static void showLayer(View v, boolean visible) {
        v.setVisibility(visible ? VISIBLE : INVISIBLE);
        if (!Ui.LEGACY) {
            int want = visible ? LAYER_TYPE_HARDWARE : LAYER_TYPE_NONE;
            if (v.getLayerType() != want) v.setLayerType(want, null);
        }
    }

    private void updateSleep() {
        boolean here = Prefs.sleepShown() && !android.provider.Settings.canDrawOverlays(getContext());   // else SleepOverlay
        int left = here ? SleepTimer.minutesLeft(getContext()) : 0;
        top.setSleep(left == 0 ? "" : Ui.trf("Off in {time}", "time", SleepTimer.duration(left)));
    }

    private void updatePalette() {
        HomeData.Weather wx = data.weather;
        Shaders.sky(scene.palette, System.currentTimeMillis(), wx.sunrise, wx.sunset);
        scene.applyPalette();
    }

    // MARK: - Recommendations: the picks and the background

    /** This hour's picks: the cards and the background image (called when Recs notifies and every 15 s). */
    private void updatePicks() {
        top.updatePicks();
    }

    /** Swaps the background with a cross-fade (from one image to another, from the gradient to an image, or back). */
    void showBackdrop(Backdrop b) {
        if (b == bgTo) return;
        look.setAccent(b == null ? 0 : Look.average(b.frost));
        long up = SystemClock.uptimeMillis();
        // In the middle of another swap, start from whichever is more visible.
        if (bgFadeAt == 0 || fadeEase(bgFadeAt, up) >= 0.5f) bgFrom = bgTo;
        bgTo = b;
        bgFadeAt = up;
        nightPair = false;
        if (b == null || glassOf(b) == null) return;
        if (bgFrom == null || glassOf(bgFrom) == null) {
            scene.setFrost(frostOf(b));
            scene.setGlassImage(glassOf(b));
        } else {
            scene.setGlassImages(glassOf(bgFrom), glassOf(b));
        }
    }

    /** What the glass shows: the photo lightened (by day), darkened (at night) or blended in between. */
    private int[] glassOf(Backdrop b) {
        if (b.nightGlass == null || night <= 0.001f) return b.lightGlass;
        if (night >= 0.999f) return b.nightGlass;
        // During the transition, the blend (only for the current background; a photo fading in uses the nearer side).
        if (b != bgTo) return night < 0.5f ? b.lightGlass : b.nightGlass;
        for (int i = 0; i < glassNight.length; i++) glassNight[i] = mix(b.lightGlass[i], b.nightGlass[i], night);
        return glassNight;
    }

    /** No photo: the glass reads the background's own gray (not Scene's default aurora, which isn't on screen). */
    private void neutralGlass() {
        int color = blend(0xFFEBEBF0, 0xFF141416, night);
        java.util.Arrays.fill(neutralFrostPx, color);
        java.util.Arrays.fill(neutralGlassPx, color);
        scene.setFrost(neutralFrostPx);
        scene.setGlassImage(neutralGlassPx);
        nightPair = false;
    }

    /** What the frosted glass shows (drawer, toasts): the blurred photo, darkened at night. */
    private int[] frostOf(Backdrop b) {
        if (b.nightFrost == null || night <= 0.001f) return b.frost;
        if (night >= 0.999f) return b.nightFrost;
        if (b != bgTo) return night < 0.5f ? b.frost : b.nightFrost;
        for (int i = 0; i < frostNight.length; i++) frostNight[i] = mix(b.frost[i], b.nightFrost[i], night);
        return frostNight;
    }

    // MARK: - Night look

    /** Day or night right now (from the sun at your location); `animate`: with the transition. */
    private void checkNight(boolean animate) {
        boolean want = data.isNight(System.currentTimeMillis());
        if (want == nightTarget) return;
        nightTarget = want;
        if (animate && running) {
            nightFrom = night;
            nightAt = SystemClock.uptimeMillis();
        } else {
            night = want ? 1 : 0;
            nightAt = 0;
            nightChanged();
        }
    }

    /** Advances the day ⇄ night transition; true while it runs. */
    private boolean tickNight(long up) {
        if (nightAt == 0) return false;
        float t = Ui.clamp((up - nightAt) / NIGHT_MS), to = nightTarget ? 1 : 0;
        night = nightFrom + (to - nightFrom) * t * t * (3 - 2 * t);
        if (t >= 1) {
            night = to;
            nightAt = 0;
        }
        nightChanged();
        return true;
    }

    /** Redraws for the current night value (the glass now reads the lightened, blended or darkened photo). */
    private void nightChanged() {
        if (bgFadeAt == 0 && bgTo != null && bgTo.lightGlass != null) {
            scene.setFrost(frostOf(bgTo));
            if (nightAt != 0 && bgTo.nightGlass != null) {
                if (!nightPair) {
                    scene.setGlassImages(bgTo.lightGlass, bgTo.nightGlass);
                    nightPair = true;
                }
                scene.setGlassMix(night);
            } else {
                scene.setGlassImage(glassOf(bgTo));
                nightPair = false;
            }
        } else if (bgFadeAt == 0 && bgTo == null) {
            neutralGlass();
        }
        top.invalidateAll();
        widgetsPage.invalidate();
        redrawSky();
        backdropLayer.invalidate();
        platter.invalidate();
    }

    /** How dark it is: 0 = day, 1 = night (in between during the transition). Spotlight's chrome (weather, buttons,
     * shelf) always sits over a photo with a dark corner treatment, by day or by night (Look.spotlightPhoto), so it
     * leans toward the night palette even in the day theme instead of using low-contrast ink on a bright scrim. That
     * is only the first page: the others sit on the look's own background, so once another page shows this is the
     * real day/night value again (a white Material page stays white by day). */
    float dark() {
        if (!darkFollowsPager()) return night;
        return night + (Math.max(night, 0.7f) - night) * (1 - pager.away());
    }

    private boolean darkFollowsPager() {
        return Prefs.spotlightOn() && picksOn() && night < 0.7f;
    }

    /** The day or the night value of a glass surface's opacity (until those move into GlassLook). */
    float tone(float onLight, float onDark) {
        return onLight + (onDark - onLight) * dark();
    }

    private boolean tickBackgroundFade(long up) {
        if (bgFadeAt == 0) return false;
        float e = fadeEase(bgFadeAt, up);
        if (bgTo != null && bgFrom != null && glassOf(bgFrom) != null && glassOf(bgTo) != null) {
            if (drawerLayer.getVisibility() == VISIBLE || toasts.onScreen()) {
                int[] from = frostOf(bgFrom), to = frostOf(bgTo);
                for (int i = 0; i < frostMix.length; i++) frostMix[i] = mix(from[i], to[i], e);
                scene.setFrost(frostMix);
            }
            scene.setGlassMix(e);
        }
        if (e >= 1) {
            bgFadeAt = 0;
            bgFrom = null;
            if (bgTo == null) {
                neutralGlass();
            } else {
                scene.setFrost(frostOf(bgTo));
                if (glassOf(bgTo) != null) scene.setGlassImage(glassOf(bgTo));
            }
        }
        return true;
    }

    private float fadeEase(long start, long up) {
        return fadeEase(start, up, FADE_MS);
    }

    private static float fadeEase(long start, long up, float duration) {
        float t = Ui.clamp((up - start) / duration);
        return t * t * (3 - 2 * t);
    }

    private static int mix(int a, int b, float f) {
        int r = Math.round(((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
        int g = Math.round(((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
        int bl = Math.round((a & 255) * (1 - f) + (b & 255) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    // MARK: - Background

    private void drawSky(Canvas c) {
        drawLightSky(c);
    }

    /** Blends a color on top (the drawer's veil) as part of drawing the image, without another layer. */
    private android.graphics.ColorFilter veil(float amount, int color) {
        if (amount <= 0.002f) return null;
        amount = Math.round(amount * 255) / 255f;
        if (amount == veilAmount && color == veilColor) return veilFilter;
        veilAmount = amount;
        veilColor = color;
        float keep = 1 - amount;
        return veilFilter = new android.graphics.ColorMatrixColorFilter(new float[] {
            keep, 0, 0, 0, ((color >> 16) & 255) * amount,
            0, keep, 0, 0, ((color >> 8) & 255) * amount,
            0, 0, keep, 0, (color & 255) * amount,
            0, 0, 0, 1, 0});
    }

    private void drawImage(Canvas c, Bitmap image, float alpha) {
        if (image == null || alpha <= 0.004f) return;
        imagePaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawBitmap(image, null, screen, imagePaint);
    }

    // MARK: - Pages (HomePager): Down past a page's last row goes to the next page that has something to show

    /** How long the catalog posters stay decoded after going back to the first page: a quick return stays instant. */
    private static final long POSTERS_KEEP_MS = 10_000;
    private final Runnable dropPosters = this::dropPosters;

    /** Back on the first page a while: the rows' posters go; they're read back from disk. */
    private void dropPosters() {
        if (pager.current() != topSlot) return;
        catalogs.trim();
        AppLog.w("catalog posters dropped (back on the first page)");
    }

    /** Back on the first page: the posters are dropped in a few seconds, unless a page with them shows again first. */
    private void catalogsClosed() {
        handler.removeCallbacks(dropPosters);
        handler.postDelayed(dropPosters, POSTERS_KEEP_MS);
    }

    /** ↓ (`dir` 1) or ↑ (-1) past the focused page's edge: the next page with something to show; false if none. */
    private boolean pageBy(int dir) {
        // Leaving the first page: the rows are read (and, when stale, fetched) first, as their pages may not know them yet.
        if (pager.current() == topSlot) catalogs.refreshIfStale();
        watchingPage.sync();
        catalogPage.sync();
        pager.refresh();
        HomePager.Slot to = pager.next(dir);
        if (to == null) return false;
        goTo(to, false);
        return true;
    }

    /** `to` becomes the page shown, with the focus on it: on its first row coming from above, its last from below. */
    private void goTo(HomePager.Slot to, boolean snap) {
        HomePager.Slot from = pager.current();
        boolean fromAbove = pager.order(from) < pager.order(to);
        if (from != to) {
            if (from == watchingSlot) watchingPage.leave();
            else if (from == catalogSlot) catalogPage.leave();
        }
        pager.refresh();
        pager.show(to, snap);
        widgetFocus = false;
        if (to == topSlot) {
            if (from != to) top.enter(fromAbove);
            catalogsClosed();
        } else {
            if (from == topSlot) {
                handler.removeCallbacks(dropPosters);
                catalogs.refreshIfStale();      // the poster rows (a request each, once in a few hours)
            }
            if (from != to && to == watchingSlot) watchingPage.enter(fromAbove);
            else if (from != to && to == catalogSlot) catalogPage.enter(fromAbove);
        }
        motionDirty = true;
        wake();
    }

    /** A page's data changed: an empty page is left out, and the focus on it goes to the page above. */
    void pagesChanged() {
        if (pager == null) return;
        pager.refresh();
        HomePager.Slot at = pager.current();
        if (!pager.shows(at)) {
            goTo(pager.above(at), false);
            retarget(false);
        }
        motionDirty = true;
        wake();
    }

    /** The quotes card is on (Settings) and has coins to show: page 2 has something. */
    boolean quotesOn() {
        return Prefs.quotes() && HomeData.active().length > 0;
    }

    /** Page 2's quotes card at `r`, in this look's colors. */
    void drawQuotes(Canvas c, RectF r) {
        rowTitle.setColor(look.color(Look.DRAWER_ROW, dark()));
        cardTitle.setColor(look.color(Look.DRAWER_ROW, dark()));
        cardText.setColor(drawerInk(0.95f));
        cardDim.setColor(look.color(Look.DRAWER_DIM, dark()));
        cardDimRight.setColor(look.color(Look.FAINT, dark()));
        bigTemp.setColor(drawerInk(1));
        hourPaint.setColor(look.color(Look.DRAWER_DIM, dark()));
        hourTemp.setColor(drawerInk(0.95f));
        pricePaint.setColor(drawerInk(0.95f));
        drawQuotesCard(c, r);
    }

    // MARK: - The popover (HomePopover): the inputs of one kind, or a poster's menu

    /** OK on an input button: one input opens at once; several open the popover, with the first one that has a device on it. */
    private void openInputs(HomeApps.Item group, RectF from) {
        List<HomeApps.Item> list = HomeApps.inputGroup(getContext().getApplicationContext(), group.key);
        if (list.isEmpty()) return;
        if (list.size() == 1) {
            launch(list.get(0), from);
            return;
        }
        int at = 0;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).connected) {
                at = i;
                break;
            }
        }
        showPop(group.label, list, at, null, null);
    }

    private void showPop(String title, List<HomeApps.Item> list, int at, Runnable[] actions, RectF anchor) {
        popover.show(title, list, at, actions, anchor);
        top.invalidateButtons();
        wake();
        spoken.focusMoved();
    }

    private void closePop(boolean snap) {
        if (!popover.close(snap)) return;
        top.invalidateButtons();
        wake();
        spoken.focusMoved();
    }

    /** OK in the popover: a poster's menu runs its action; an input opens. */
    private void choosePop() {
        HomeApps.Item it = popover.choose(popRow);
        top.invalidateButtons();
        wake();
        spoken.focusMoved();
        click.open();
        if (it != null) launch(it, new RectF(popRow));
    }

    private boolean tileMenuReady() {
        return !sheet.isOpen() && onWatchingPage() && watchingPage.focusedItem() != null;
    }

    /** One action for the focused poster: Remove where Trakt takes it off the list, else Hide (only this launcher forgets it). */
    private void openTileMenu() {
        handler.removeCallbacks(holdStart);
        holdArmed = false;
        if (popover.isOpen() || opening != null || !tileMenuReady()) return;
        okHeld = true;      // the release of this press opens nothing
        final Catalogs.Row row = watchingPage.focusedRow();
        final Catalogs.Item it = watchingPage.focusedItem();
        final boolean remove = catalogs.actionFor(row.key, it) == Catalogs.REMOVE;
        final String rowTitle = row.title;
        final String label = !remove ? Ui.tr("Hide from this row")
                : Trakt.WATCHLIST.equals(row.key) ? Ui.tr("Remove from Watchlist") : Ui.tr("Remove");
        final List<Runnable> actions = new ArrayList<>();
        List<HomeApps.Item> items = new ArrayList<>();
        items.add(HomeApps.action(HomeApps.APP, "tile", label, null));
        actions.add(() -> catalogs.act(row.key, it, r -> {
            if (r == Trakt.FAILED) showToast(Ui.tr("Couldn't remove it. Try again."));
            else if (r == Catalogs.HIDDEN) showToast(Ui.trf("Hidden from {row}: {title}", "row", rowTitle, "title", it.title));
            else showToast(Ui.trf("Removed from {row}: {title}", "row", rowTitle, "title", it.title));
        }));
        if (catalogs.canMark(row.key, it)) {
            items.add(HomeApps.action(HomeApps.APP, "mark", Ui.tr("Mark as watched"), null));
            actions.add(() -> catalogs.mark(row.key, it, r -> {
                if (r == Trakt.DONE) showToast(Ui.trf("Marked as watched: {title}", "title", it.title));
                else showToast(Ui.tr("Couldn't mark it as watched. Try again."));
            }));
        }
        watchingPage.focusedRect(tileAnchor);
        click.open();
        showPop(it.title, items, 0, actions.toArray(new Runnable[0]), tileAnchor);
    }

    /** A catalog title's menu (always shown, even with one row): its trailer, and the watchlist where Trakt allows it. */
    private void openCatalogMenu() {
        handler.removeCallbacks(holdStart);
        holdArmed = false;
        final Catalogs.Item it = catalogPage.focusedItem();
        if (popover.isOpen() || opening != null || it == null) return;
        okHeld = true;      // the release of this press opens nothing
        final List<Runnable> actions = new ArrayList<>();
        List<HomeApps.Item> items = new ArrayList<>();
        final boolean playing = catalogPage.trailerPlaying();
        String trailerLabel = !playing ? Ui.tr("Play trailer") : catalogPage.trailerPaused() ? Ui.tr("Resume trailer") : Ui.tr("Pause trailer");
        items.add(HomeApps.action(HomeApps.APP, "trailer", trailerLabel, null));
        actions.add(catalogPage::trailerKey);
        if (catalogPage.canOpenYoutube()) {
            items.add(HomeApps.action(HomeApps.APP, "youtube", Ui.tr("Open in YouTube"), null));
            actions.add(catalogPage::openTrailerInYoutube);
        }
        if (catalogs.canWatchlist(it)) {
            final boolean listed = catalogs.onWatchlist(it);
            items.add(HomeApps.action(HomeApps.APP, "watchlist", listed ? Ui.tr("Remove from Watchlist") : Ui.tr("Add to Watchlist"), null));
            actions.add(() -> catalogs.toggleWatchlist(it, r -> {
                if (r == Trakt.DONE && listed) showToast(Ui.trf("Removed from Watchlist: {title}", "title", it.title));
                else if (r == Trakt.DONE) showToast(Ui.trf("Added to Watchlist: {title}", "title", it.title));
                else if (listed) showToast(Ui.tr("Couldn't remove it. Try again."));
                else showToast(Ui.tr("Couldn't add it. Try again."));
            }));
        }
        catalogPage.focusedRect(tileAnchor);
        click.open();
        showPop(it.title, items, 0, actions.toArray(new Runnable[0]), tileAnchor);
    }

    /** Color for the drawer's text and symbols: dark by day, white at night. */
    private int drawerInk(float alpha) {
        return Ui.alpha(look.color(Look.INK_PURE, dark()), alpha);
    }

    /** Glass for a drawer card or button: white by day, smoked at night (both during the transition). */
    void drawerGlass(Canvas c, RectF r, float radius, float lightTint, int shadowColor, float drop) {
        look.drawerGlass(c, r, radius, lightTint, shadowColor, drop, dark());
    }

    private void drawerCard(Canvas c, RectF r) {
        drawerGlass(c, r, 30 * k, 0.72f, 0x1A000000, 10 * k);
    }

    private void refreshDrawerText() {
        boolean settings = Prefs.version != textPrefs || !Ui.language.equals(textLanguage);
        textPrefs = Prefs.version;
        textLanguage = Ui.language;
        long minute = System.currentTimeMillis() / 60_000;
        if (data.pricesAt != textPricesAt || minute != textMinute || settings) {
            textPricesAt = data.pricesAt;
            textMinute = minute;
            String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
            staleText = stale.isEmpty() ? Ui.tr("24h change") : Ui.trf("quotes|{ago}", "ago", stale);
            for (int i = 0; i < HomeData.COINS.length; i++) {
                priceText[i] = HomeData.brl(data.prices[i]);
                changeText[i] = HomeData.change(data.changes[i]);
            }
        }
    }

    /** Prices in the configured currency with the 24 h change (green/red, like Apple's Stocks app). */
    private void drawQuotesCard(Canvas c, RectF r) {
        drawerCard(c, r);
        if (Ui.LEGACY) quotesSnapshot.draw(c, r, drawerCardsKey(), cc -> drawPrices(cc, r));
        else drawPrices(c, r);
    }

    /**
     * What the drawer cards show depends on: the data, the settings, the language, day or night, and the minute (the
     * sunrise and sunset, "12 min ago").
     */
    private String drawerCardsKey() {
        return data.weatherAt + "|" + data.pricesAt + "|" + Prefs.version + "|" + Ui.language + "|"
                + Float.floatToIntBits(dark()) + "|" + System.currentTimeMillis() / 60_000;
    }

    /**
     * Android 8 to 11: a drawer card's content (its text and symbols, not its glass) kept as a bitmap. The drawer is
     * redrawn on every frame of its focus animation, and laying that text out again took ~15 ms a frame on a Mi Box 3.
     */
    private static final class Snapshot {
        private Bitmap bitmap;
        private String key = "";

        void draw(Canvas c, RectF r, String key, Consumer<Canvas> content) {
            int left = Math.round(r.left), top = Math.round(r.top);
            int w = Math.max(1, Math.round(r.right) - left), h = Math.max(1, Math.round(r.bottom) - top);
            if (bitmap == null || bitmap.getWidth() != w || bitmap.getHeight() != h) {
                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                this.key = "";
            }
            if (!key.equals(this.key)) {
                bitmap.eraseColor(0);
                Canvas cc = new Canvas(bitmap);
                cc.translate(-left, -top);
                content.accept(cc);
                this.key = key;
            }
            c.drawBitmap(bitmap, left, top, null);
        }
    }

    private final Snapshot quotesSnapshot = new Snapshot();

    private void drawPrices(Canvas c, RectF r) {
        float x = r.left + 34 * k, right = r.right - 34 * k;
        refreshDrawerText();
        c.drawText(Ui.tr("Crypto"), x, r.top + 54 * k, cardTitle);
        c.drawText(staleText, right, r.top + 54 * k, cardDimRight);
        float cap = Ui.cap(cardText);
        int[] coins = HomeData.active();
        for (int j = 0; j < coins.length; j++) {
            int i = coins[j];
            float base = r.top + (110 + 52 * j) * k, mid = base - cap / 2;
            float gw = Glyphs.width(getContext(), HomeData.COINS[i], cap);
            Glyphs.coin(getContext(), c, HomeData.COINS[i], x + 14 * k, mid, cap, look.color(Look.COIN_GLYPH, dark()));
            c.drawText(HomeData.coinName(i), x + Math.max(gw, 28 * k) / 2 + 30 * k, base, cardText);
            c.drawText(priceText[i], right - 128 * k, base, pricePaint);
            double change = data.changes[i];
            if (Double.isNaN(change)) continue;
            int color = look.color(change >= 0 ? Look.UP : Look.DOWN, dark());
            tmp.set(right - 108 * k, mid - 17 * k, right, mid + 17 * k);
            fill.setShader(null);
            fill.setColor(Ui.alpha(color, 0.2f));
            c.drawRoundRect(tmp, 17 * k, 17 * k, fill);
            changePaint.setColor(color);
            c.drawText(changeText[i], tmp.centerX(), mid + Ui.halfCap(changePaint), changePaint);
        }
    }

    // MARK: - Focus

    /** Sets the springs' targets from the current focus (snap: no animation). */
    private void retarget(boolean snap) {
        top.retarget(snap);
        if (snap) widgetsPage.invalidate();
    }

    /** TalkBack reads the newly focused item. */
    void focusMoved() {
        spoken.focusMoved();
    }

    boolean sheetOpen() {
        return sheet.isOpen();
    }

    boolean popoverOpen() {
        return popover.isOpen();
    }

    // MARK: - "All Apps" (HomeSheet)

    void openSheet() {
        sheet.open();
        retarget(false);
        spoken.focusMoved();
    }

    private void closeSheet() {
        sheet.close();
        retarget(false);
        spoken.focusMoved();
        if (shelfEdited) {     // the grid was kept as it was while open: apps that went to the shelf leave it now
            shelfEdited = false;
            reload();
        }
    }

    // MARK: - Pages 3 and 4: the poster rows, and what they ask of the home screen

    @Override public void onCatalogs() {
        wake();
        watchingPage.sync();
        catalogPage.sync();
        watchingPage.invalidate();
        catalogPage.invalidate();
        pagesChanged();
    }

    @Override public void onDetails() {
        catalogPage.onDetails();
    }

    /** The focus is on page 3. */
    boolean onWatchingPage() {
        return pager.current() == watchingSlot;
    }

    /**
     * The app a poster opens in: the one that published it (Watch Next), else what is kept about where it streams,
     * and for the focused poster what TMDB says. Null until known.
     */
    String catalogService(Catalogs.Item it, boolean focused) {
        if (it.appPkg != null) return it.appPkg;
        if (it.id <= 0) return null;
        if (focused) {      // TMDB is asked only once the focus rests on it (holding an arrow asks for nothing on the way)
            String key = it.kind + it.id;
            if (!key.equals(restKey)) {
                restKey = key;
                restAt = SystemClock.uptimeMillis();
                handler.removeCallbacks(rested);
                handler.postDelayed(rested, HomeCatalogPage.REST_MS + 10);
            }
        }
        boolean rest = focused && SystemClock.uptimeMillis() - restAt >= HomeCatalogPage.REST_MS;
        // On the browse screen the details' answer brings where it streams: asked only when it came from disk without it.
        boolean waitDetails = catalogPage.waitsForDetails(it);
        if (it.serviceAsked < 1 || (rest && !waitDetails && it.serviceAsked < 2 && it.servicePkg == null)) {
            boolean fetch = rest && !waitDetails && it.serviceAsked < 2;
            it.serviceAsked = fetch ? 2 : 1;
            Launcher.get(activity).service(it.kind, it.id, fetch, pkg -> {
                it.servicePkg = pkg;
                markBadges();
            });
        }
        return it.servicePkg;
    }

    private String restKey = "";
    private long restAt;
    private final Runnable rested = this::markBadges;

    /** The app a pick opens in: its own, or (picks from anything popular) what Launcher finds, asked once per pick. */
    String pickService(Recs.Pick p) {
        if (!Recs.ROUTED.equals(p.pkg)) return p.pkg;
        if (!p.serviceAsked) {
            p.serviceAsked = true;
            Launcher.get(activity).service(p.kind, p.id, true, pkg -> {
                p.servicePkg = pkg;
                markBadges();
            });
        }
        return p.servicePkg;
    }

    /** A badge arrived: the layers that draw them are drawn again. */
    private void markBadges() {
        top.invalidate();
        watchingPage.invalidate();
        catalogPage.invalidate();
        wake();
    }

    // MARK: - What pages 3 and 4 ask of the home screen

    /** A badge picture is ready: the layers that show badges are drawn again (once; the picture then stays). */
    private final Runnable markBadgesRun = this::markBadges;
    final Runnable badgesReady = () -> post(markBadgesRun);

    /** The focus is on page 4. */
    boolean onCatalogPage() {
        return pager.current() == catalogSlot;
    }

    /** Nothing else is going on (All Apps, an open waiting for its answer, OK held): a trailer may start by itself. */
    boolean trailerMayStart() {
        return !sheet.isOpen() && opening == null && !okDown;
    }

    // MARK: - Toasts

    void showToast(String text) {
        toasts.show(text);
        announceForAccessibility(text);
        wake();
    }

    /** New notices from the main process (mode switches, the check after the TV boots): those from the last 24 h. */
    private void checkNotices() {
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            JSONArray list = HomeMode.state(c).optJSONArray("notices");
            if (list == null) return;
            long seen = ui.getLong("seenNotice", 0), newest = seen;
            long cutoff = System.currentTimeMillis() - 24 * 3600_000L;
            final List<String> fresh = new ArrayList<>();
            for (int i = list.length() - 1; i >= 0; i--) {   // the list comes newest first
                JSONObject o = list.optJSONObject(i);
                if (o == null) continue;
                long at = o.optLong("at");
                if (at <= seen || at < cutoff) continue;
                fresh.add(o.optString("text"));
                newest = Math.max(newest, at);
            }
            if (newest > seen) ui.edit().putLong("seenNotice", newest).apply();
            if (!fresh.isEmpty()) handler.post(() -> { for (String t : fresh) showToast(t); });
        });
    }

    // MARK: - Lists (in the background, so returning to the home screen isn't delayed)

    private void reload() {
        final Context c = getContext().getApplicationContext();
        // The shelf and All Apps (100 to 500 ms of package queries on a TV) only when they can have changed.
        long up = SystemClock.uptimeMillis();
        final boolean apps = appsStale || Prefs.version != appsVersion || Ui.generation != appsGeneration
                || up - appsAt > APPS_MAX_AGE_MS;
        if (apps) {
            appsStale = false;
            appsVersion = Prefs.version;
            appsGeneration = Ui.generation;
            appsAt = up;
        }
        LOADER.execute(() -> {
            final List<HomeApps.Item> s = apps ? HomeApps.shelf(c, true) : null;
            final List<HomeApps.Item> o = apps ? HomeApps.others(c) : null;
            final List<HomeApps.Item> buttons = apps ? HomeApps.topButtons(c) : null;   // they change with Prefs too
            handler.post(() -> {
                if (buttons != null) top.setButtons(buttons);
                if (s != null) top.setShelf(s);
                if (o != null && !sheet.isOpen()) sheet.setApps(o);
                else if (o != null) appsStale = true;   // All Apps is open: its list comes next time
            });
        });
    }

    /** Apps installed, removed or updated (also while another app is open): the next reload reads them again. */
    private final LauncherApps.Callback packages = new LauncherApps.Callback() {
        @Override public void onPackageAdded(String pkg, UserHandle user) { appsChanged(); }
        @Override public void onPackageRemoved(String pkg, UserHandle user) { appsChanged(); }
        @Override public void onPackageChanged(String pkg, UserHandle user) { appsChanged(); }
        @Override public void onPackagesAvailable(String[] pkgs, UserHandle user, boolean replacing) { appsChanged(); }
        @Override public void onPackagesUnavailable(String[] pkgs, UserHandle user, boolean replacing) { appsChanged(); }
    };

    private void appsChanged() {
        appsStale = true;
        if (running) reloadSoon();
    }

    // MARK: - Keys: only the arrows, OK and Back (system keys pass through to the system)

    boolean onKey(KeyEvent e) {
        wake();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (top.shelf.reordering()) return reorderKey(e, down);
        int code = e.getKeyCode();
        if (down && code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER
                && code != KeyEvent.KEYCODE_NUMPAD_ENTER) {
            holdArmed = false;      // another key while OK is held: no move mode
            handler.removeCallbacks(holdStart);
            if (code != KeyEvent.KEYCODE_BACK) cancelOpening();     // moving away: the title doesn't open
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) move(-1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) move(1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) move(0, -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) move(0, 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // Opens on release, and only if the press happened here: the release of an OK pressed inside another
                // app opens nothing.
                // Held (~0.5 s) on a shelf app: move mode; on an app in All Apps: on or off the shelf. A timer, since
                // not every remote repeats a held key, or the long-press repeat, whichever comes first; the release
                // after a hold opens nothing.
                if (down) {
                    if (e.getRepeatCount() == 0) {
                        okDown = true;
                        okHeld = false;
                        handler.removeCallbacks(holdStart);
                        holdArmed = sheet.isOpen() ? canToggleShelf(sheet.index()) : onCatalogPage() || tileMenuReady() || onTopPage() && top.canHold();
                        if (holdArmed) handler.postDelayed(holdStart, HOLD_MS);
                    } else if (okDown && holdArmed && e.isLongPress()) {
                        hold();
                    }
                } else {
                    holdArmed = false;
                    handler.removeCallbacks(holdStart);
                    if (okDown && !okHeld && !e.isCanceled() && spoken.keyClick()) activate();
                    okDown = okHeld = false;
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (!onCatalogPage() || sheet.isOpen()) return false;
                if (down && e.getRepeatCount() == 0) catalogPage.trailerKey();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (opening != null) {      // Back only cancels the open that is waiting for its answer
                    if (!down) cancelOpening();
                    backDown = false;
                    return true;
                }
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) back();
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    private void back() {
        if (popover.isOpen()) {
            closePop(false);
        } else if (sheet.isOpen()) {
            closeSheet();
        } else if (catalogPage.trailerOn()) {      // Back stops the trailer first
            catalogPage.stopTrailer();
        } else if (pager.current() != topSlot) {      // from any other page: the first page's shelf
            goTo(topSlot, false);
            retarget(false);
        } else if (top.toShelf()) {
            retarget(false);
        } else {
            return;
        }
        click.move();
    }

    /** Keys while moving an app: nothing else on the home screen gets them (system keys still go to the system). */
    private boolean reorderKey(KeyEvent e, boolean down) {
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) top.shelf.shift(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // The press that lifted the app is still held: its repeats and release do nothing; a new press drops it.
                if (down && e.getRepeatCount() == 0) {
                    okDown = okHeld = false;
                    top.shelf.drop();
                } else if (!down) {
                    okDown = okHeld = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) {
                        top.shelf.cancel(false);
                        click.move();
                    }
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    /** The focus is on page 1. */
    private boolean onTopPage() {
        return pager.current() == topSlot;
    }

    private void move(int dx, int dy) {
        catalogPage.stopTrailer();      // an arrow ends the trailer, and moves on
        if (popover.isOpen()) {
            if (popover.move(dy)) {
                click.move();
                spoken.focusMoved();
            }
            return;
        }
        if (sheet.isOpen()) {
            moveGrid(dx, dy);
            return;
        }
        // The page moves its own focus; past its first or last row, the page above or below.
        HomePager.Slot at = pager.current();
        if (at == topSlot) {
            if (!top.move(dx, dy) && (dy <= 0 || !pageBy(1))) return;
        } else if (at == watchingSlot) {
            if (!watchingPage.move(dx, dy) && (dx != 0 || !pageBy(dy))) return;
        } else if (at == catalogSlot) {
            if (!catalogPage.move(dx, dy) && (dx != 0 || !pageBy(dy))) return;
        } else if (dx != 0) {
            return;
        } else if (dy > 0 && !widgetFocus && talkBack() && quotesOn()) {
            // Only with TalkBack: the quotes card is there to be read, and this is how it gets to it.
            widgetFocus = true;
        } else if (!pageBy(dy)) {
            return;
        }
        retarget(false);
        click.move();
    }

    private void moveGrid(int dx, int dy) {
        if (!sheet.move(dx, dy)) return;
        click.move();
        spoken.focusMoved();
    }

    private void activate() {
        wake();
        if (top.shelf.reordering()) {   // TalkBack's click while moving an app: drop it
            top.shelf.drop();
            return;
        }
        if (popover.isOpen()) {
            choosePop();
            return;
        }
        if (opening != null) return;      // still looking up where a title streams
        if (sheet.isOpen()) {
            if (!sheet.apps().isEmpty()) openItem(sheet.apps().get(sheet.index()), sheet.focusedTile());
            return;
        }
        HomePager.Slot at = pager.current();
        if (at == topSlot) top.activate();
        else if (at == watchingSlot) watchingPage.activate();
        else if (at == catalogSlot) catalogPage.activate();
        // page 2's widgets are only read
    }

    /** OK on an app, a button or an input: All Apps and the inputs open here, anything else launches. */
    void openItem(HomeApps.Item it, RectF r) {
        click.open();
        if (it.kind == HomeApps.ALL_APPS) {
            openSheet();
            return;
        }
        if (it.kind == HomeApps.INPUT_GROUP) {
            openInputs(it, r);
            return;
        }
        launch(it, r);
    }

    // MARK: - A title from the catalogs or the picks: where it streams decides where it opens (Launcher)

    static final long OPEN_LOADER_MS = 150;      // a cached answer opens at once; only a wait shows the dots
    private Launcher.Handle opening;
    private Catalogs.Item openingItem;
    private long openingAt;

    /** The title waiting to open (its poster shows the dots), and since when. */
    Catalogs.Item openingItem() {
        return openingItem;
    }

    long openingAt() {
        return openingAt;
    }

    void openTitle(Catalogs.Item item, RectF poster) {
        final RectF from = new RectF(poster);
        openingItem = item;
        openingAt = SystemClock.uptimeMillis();
        watchingPage.invalidate();
        catalogPage.invalidate();
        opening = Launcher.get(activity).open(item.kind, item.id, item.imdb, target -> {
            opening = null;
            openingItem = null;
            watchingPage.invalidate();
            catalogPage.invalidate();
            show(target, from);
        });
    }

    /** A Continue Watching card from the TV's apps (Watch Next): its own intent resumes it; the entry was checked when read. */
    void openWatchNext(Catalogs.Item item) {
        try {
            activity.startActivity(new android.content.Intent(item.launch));
        } catch (RuntimeException e) {
            AppLog.w("watch next " + item.title + ": " + e);
            showToast(Ui.trf("Couldn't open {app}.", "app", Launcher.get(activity).appName(item.appPkg)));
        }
    }

    /** A pick, like a catalog or Trakt title: no dots on it, but the answer is usually already there (Recs looks it up ahead). */
    void openPick(Recs.Pick p, RectF r) {
        final RectF from = new RectF(r);
        opening = Launcher.get(activity).open(p.kind, p.id, null, target -> {
            opening = null;
            show(target, from);
        });
    }

    /** Back or moving the focus while the answer is awaited: the title doesn't open. */
    private void cancelOpening() {
        if (opening == null) return;
        opening.cancel();
        opening = null;
        openingItem = null;
        watchingPage.invalidate();
    }

    private void show(Routing.Target target, RectF from) {
        Launcher launcher = Launcher.get(activity);
        if (target.kind == Routing.APP) {
            launch(HomeApps.app(target.pkg, launcher.appName(target.pkg), target.uri), from);
        } else if (target.kind == Routing.STORE) {
            if (!HomeApps.openStore(activity, target.pkg)) showToast(Ui.tr("Couldn't open the Play Store."));
        } else {
            showToast(Launcher.message(target));
        }
    }

    private void launch(HomeApps.Item it, RectF r) {
        boolean opened = HomeApps.open(activity, it, this, Math.round(r.left), Math.round(r.top),
                Math.round(r.width()), Math.round(r.height()));
        if (!opened) showToast(Ui.trf("Couldn't open {app}.", "app", it.label));
    }

    // MARK: - Holding OK: All Apps puts the app on the shelf or takes it off; on the shelf, the app is lifted to move

    private void hold() {
        if (popover.isOpen()) return;
        if (sheet.isOpen()) {
            openAppMenu();
        } else if (onWatchingPage()) {
            openTileMenu();     // a poster's menu: Remove or Hide
        } else {
            handler.removeCallbacks(holdStart);
            holdArmed = false;
            // On page 4 the focused title's menu; on page 1 the focused app is lifted to be moved.
            if (onCatalogPage()) {
                openCatalogMenu();
            } else if (onTopPage() && top.hold()) {
                okHeld = true;
            }
        }
    }

    /** TalkBack's long click on an app in All Apps (once per press, if the remote's hold also arrives). */
    private void a11yHold() {
        if (SystemClock.uptimeMillis() - toggledAt < 1000) return;
        if (okDown) okHeld = true;     // the remote's release must not open the app
        openAppMenu();
    }

    private boolean canToggleShelf(int i) {
        if (!sheet.isOpen() || i >= sheet.apps().size()) return false;
        HomeApps.Item it = sheet.apps().get(i);
        return it.kind == HomeApps.APP && it.pkg != null;
    }

    /** An app of All Apps the person may uninstall: one they added (never a system app, updated or not), and not this launcher. */
    private boolean canUninstall(PackageManager pm, String pkg) {
        if (pkg.equals(getContext().getPackageName())) return false;
        try {
            return (pm.getApplicationInfo(pkg, 0).flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** The focused app's menu (always shown, even with one row): on or off the home screen's shelf, and Uninstall for apps the person added. */
    private void openAppMenu() {
        handler.removeCallbacks(holdStart);
        holdArmed = false;
        if (popover.isOpen() || !canToggleShelf(sheet.index())) return;
        okHeld = true;      // the release of this press opens nothing
        final HomeApps.Item it = sheet.apps().get(sheet.index());
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            PackageManager pm = c.getPackageManager();
            int shown = 0;
            boolean on = false;
            for (String p : HomeApps.shelfPackages(c)) {
                if (shown >= HomeApps.MAX_SHELF) break;
                if (HomeApps.launchIntent(pm, p) == null) continue;
                shown++;
                on |= p.equals(it.pkg);
            }
            final boolean onShelf = on, removable = canUninstall(pm, it.pkg), disableable = HomeMode.canDisable(c, it.pkg);
            handler.post(() -> {
                if (popover.isOpen() || !sheet.isOpen()) return;
                List<Runnable> actions = new ArrayList<>();
                List<HomeApps.Item> items = new ArrayList<>();
                items.add(HomeApps.action(HomeApps.APP, "shelf", onShelf ? Ui.tr("Remove from home screen") : Ui.tr("Add to home screen"), null));
                actions.add(this::toggleShelf);
                if (disableable) {
                    items.add(HomeApps.action(HomeApps.APP, "disable", Ui.tr("Disable"), null));
                    actions.add(() -> {
                        HomeMode.disableApp(c, it.pkg);
                        showToast(Ui.trf("Turning off {app}. Original brings it back.", "app", it.label));
                    });
                }
                if (removable) {
                    items.add(HomeApps.action(HomeApps.APP, "uninstall", Ui.tr("Uninstall"), null));
                    actions.add(() -> uninstall(it));
                }
                click.open();
                showPop(it.label, items, 0, actions.toArray(new Runnable[0]), sheet.focusedTile());
            });
        });
    }

    /** Android's own uninstall screen asks the person to confirm; the sheet reloads when the app is gone (the package receiver). */
    private void uninstall(HomeApps.Item it) {
        try {
            activity.startActivity(new android.content.Intent(android.content.Intent.ACTION_DELETE, android.net.Uri.parse("package:" + it.pkg)));
        } catch (RuntimeException e) {
            AppLog.w("uninstall " + it.pkg + ": " + e.getMessage());
            showToast(Ui.trf("Couldn't uninstall {app}.", "app", it.label));
        }
    }

    /** Puts the focused app at the end of the shelf, or takes it off if it's there; a toast says which. */
    private void toggleShelf() {
        handler.removeCallbacks(holdStart);
        holdArmed = false;
        if (!canToggleShelf(sheet.index())) return;
        okHeld = true;
        toggledAt = SystemClock.uptimeMillis();
        final HomeApps.Item it = sheet.apps().get(sheet.index());
        final Context c = getContext().getApplicationContext();
        click.open();
        LOADER.execute(() -> {
            // On the shelf means shown there: installed and within the limit, as in Settings > Shelf.
            List<String> list = new ArrayList<>(HomeApps.shelfPackages(c));
            PackageManager pm = c.getPackageManager();
            int shown = 0;
            boolean on = false;
            for (String p : list) {
                if (shown >= HomeApps.MAX_SHELF) break;
                if (HomeApps.launchIntent(pm, p) == null) continue;
                shown++;
                on |= p.equals(it.pkg);
            }
            final String text;
            list.remove(it.pkg);
            if (on) {
                text = Ui.trf("Removed from the shelf: {app}", "app", it.label);
            } else {
                if (shown >= HomeApps.MAX_SHELF) {
                    handler.post(() -> showToast(Ui.tr("The shelf is full. Make room in Sky Home Launcher › Shelf.")));
                    return;
                }
                list.add(it.pkg);
                text = Ui.trf("Added to the shelf: {app}", "app", it.label);
            }
            Prefs.setShelf(list);   // here, so the next hold reads the new list
            handler.post(() -> {
                shelfEdited = true;
                showToast(text);
                reload();
            });
        });
    }

    // MARK: - TalkBack: every reachable item as a virtual node (Spoken). Id = group * 1000 + index + 1.

    /** All Apps, page 2's quotes card (read by TalkBack, never focused by the remote) and the popover; page 1's groups are HomeTopPage's. */
    private static final int GRID = 9, WIDGETS = 8, POPUP = 12;

    static int a11yId(int group, int index) {
        return group * 1000 + index + 1;
    }

    @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return spoken;
    }

    private void a11yItems(List<Integer> out) {
        if (popover.isOpen()) {
            for (int i = 0; i < popover.items().size(); i++) out.add(a11yId(POPUP, i));
            return;
        }
        if (sheet.isOpen()) {
            for (int i = 0; i < sheet.apps().size(); i++) out.add(a11yId(GRID, i));
            return;
        }
        top.a11yItems(out);
        if (pager.current() == widgetsSlot && quotesOn()) out.add(a11yId(WIDGETS, 1));
    }

    private String a11yText(int id) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        switch (group) {
            case POPUP: return label(popover.items(), i);
            case GRID:
                if (i >= sheet.apps().size()) return "";
                return label(sheet.apps(), i) + ", " + Ui.trf("{i} of {n}", "i", i + 1, "n", sheet.apps().size());
            case WIDGETS: return quotesText();
            default: return top.a11yText(group, i);
        }
    }

    /**
     * Where the item is now. Items off screen (a page away, the sheet's hidden rows) get a thin strip at that edge, so
     * TalkBack's arrows can still reach them; going there brings them in.
     */
    private boolean a11yBounds(int id, Rect out) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        switch (group) {
            case POPUP:
                if (!popover.isOpen() || i >= popover.items().size()) return false;
                popover.rowRect(i, a11yRect);
                break;
            case GRID:
                if (!sheet.isOpen() || i >= sheet.apps().size()) return false;
                sheet.tileRect(i, a11yRect);
                break;
            case WIDGETS:
                if (pager.current() != widgetsSlot) return false;
                a11yRect.set(widgetsPage.quotesRect());
                break;
            default:
                if (!top.a11yRect(group, i, a11yRect)) return false;
                a11yRect.offset(0, heroLayer.getTranslationY());    // page 1's layers move together
                break;
        }
        float edge = 24 * k;
        if (a11yRect.top >= h - edge) a11yRect.set(a11yRect.left, h - edge, a11yRect.right, h);
        else if (a11yRect.bottom <= edge) a11yRect.set(a11yRect.left, 0, a11yRect.right, edge);
        a11yRect.round(out);
        return out.width() > 0 && out.height() > 0;
    }

    private int a11yFocused() {
        if (popover.isOpen()) return popover.items().isEmpty() ? -1 : a11yId(POPUP, popover.index());
        if (sheet.isOpen()) return sheet.apps().isEmpty() ? -1 : a11yId(GRID, sheet.index());
        HomePager.Slot at = pager.current();
        if (at == topSlot) return top.a11yFocused();
        if (at == widgetsSlot && widgetFocus) return a11yId(WIDGETS, 1);
        return -1;
    }

    /** A spoken-feedback service (TalkBack) is on. */
    private boolean talkBack() {
        AccessibilityManager am = (AccessibilityManager) getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
        return am != null && am.isEnabled()
                && !am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN).isEmpty();
    }

    /** TalkBack moved to an item: the remote's focus goes there (on its page). */
    private void a11yFocus(int id) {
        wake();
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        if (group == GRID) {
            if (!sheet.focus(i)) return;
            spoken.focusMoved();
            click.move();
            return;
        }
        if (group == POPUP) {
            if (!popover.focus(i)) return;
            click.move();
            return;
        }
        if (sheet.isOpen() || top.shelf.reordering()) return;
        if (group == WIDGETS) {
            if (pager.current() != widgetsSlot || !quotesOn()) return;
            widgetFocus = true;
            retarget(false);
        } else {
            if (!top.a11yHas(group, i)) return;
            if (pager.current() != topSlot) goTo(topSlot, false);
            top.a11yFocus(group, i);
        }
        click.move();
    }

    static String label(List<HomeApps.Item> list, int i) {
        return i >= 0 && i < list.size() && list.get(i).label != null ? list.get(i).label : "";
    }

    /** The quotes card as TalkBack reads it: each coin with its price and 24 h change. */
    private String quotesText() {
        StringBuilder s = new StringBuilder(Ui.tr("Crypto"));
        String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
        s.append(", ").append(stale.isEmpty() ? Ui.tr("24h change") : Ui.trf("quotes|{ago}", "ago", stale)).append('.');
        for (int i : HomeData.active()) {
            s.append(' ').append(HomeData.coinName(i)).append(' ').append(HomeData.brl(data.prices[i]));
            if (!Double.isNaN(data.changes[i])) s.append(", ").append(HomeData.change(data.changes[i]));
            s.append('.');
        }
        return s.toString();
    }

    // MARK: - The background (the sky layer)

    /**
     * Background: the first card's photo, heavily blurred and almost white; without it, a light gradient. At night, the
     * same photo almost black (or a dark gradient), drawn on top during the transition.
     */
    private void drawLightSky(Canvas c) {
        if (look.background(c, screen, dark())) return;     // its home picture is a layer of its own (backdropLayer)
        long up = SystemClock.uptimeMillis();
        float e = bgFadeAt == 0 ? 1 : fadeEase(bgFadeAt, up);
        boolean fading = bgFadeAt != 0;
        // Another page showing: the background lightens (by day) or darkens (at night), as a filter on the drawing itself.
        android.graphics.ColorFilter v = veil(tone(0.32f, 0.42f) * pager.away(), blend(0xFFFFFFFF, 0xFF000000, night));
        if (bgTo == null || (fading && bgFrom == null)) {
            if (lightGradient == null) lightGradient = Ui.gradient(h, 0xFFF5F5F7, 0xFFE2E2E7);
            if (nightGradient == null) nightGradient = Ui.gradient(h, 0xFF1C1C1E, 0xFF0B0B0D);
            fill.setColorFilter(v);
            fill.setColor(0xFF000000);
            if (night < 0.999f) {
                fill.setShader(lightGradient);
                c.drawRect(screen, fill);
            }
            if (night > 0.001f) {
                fill.setShader(nightGradient);
                fill.setAlpha(Math.round(255 * night));
                c.drawRect(screen, fill);
                fill.setAlpha(255);
            }
            fill.setShader(null);
            fill.setColorFilter(null);
        }
        imagePaint.setColorFilter(v);
        if (fading && bgFrom != null) drawLightImage(c, bgFrom, bgTo == null ? 1 - e : 1);
        if (bgTo != null) drawLightImage(c, bgTo, fading ? e : 1);
        imagePaint.setColorFilter(null);
    }

    /**
     * The home screen's own background, over the look's: the first pick's picture, softly blurred and toned by the
     * look's scrim. It fades away as another page comes in (its layer's alpha), so the others (and every other screen) keep
     * the look's background.
     */
    private void drawHomeBackdrop(Canvas c) {
        if (bgTo == null && bgFrom == null) return;
        long up = SystemClock.uptimeMillis();
        boolean fading = bgFadeAt != 0;
        float e = fading ? fadeEase(bgFadeAt, up) : 1;
        imagePaint.setColorFilter(null);
        if (fading && bgFrom != null) drawHomePicture(c, bgFrom, bgTo == null ? 1 - e : 1);
        if (bgTo != null) drawHomePicture(c, bgTo, fading ? e : 1);
        int scrim = look.homeScrim(dark());
        if (scrim != 0) {
            fill.setShader(null);
            fill.setColor(scrim);
            c.drawRect(screen, fill);
        }
    }

    private float homeBackdropAmount() {
        return 1 - Ui.clamp(pager.pos.value * 1.4f);
    }

    /**
     * What of the background shows. Spotlight's photo covers the whole screen (page 1 at rest): nothing under it is
     * drawn. The home picture, opaque (page 1, no cross-fade), covers the look's own background (its shapes). A moving
     * background, kept as a picture, stays up under the cover: the page that slides away shows it at once, without
     * drawing it first (~40 ms, a hitch as the slide starts).
     */
    private void updateCover() {
        boolean spotlight = top.picks.coversScreen() && heroLayer.getVisibility() == VISIBLE && heroLayer.getAlpha() >= 0.999f
                && heroLayer.getTranslationY() == 0 && heroLayer.getHeight() >= h;
        float amount = look.m.homeBackdrop && (bgTo != null || bgFrom != null) ? homeBackdropAmount() : 0;
        boolean picture = !spotlight && amount >= 0.01f;
        backdropLayer.setVisibility(picture ? VISIBLE : INVISIBLE);
        if (picture) backdropLayer.setAlpha(amount);
        boolean covered = spotlight || (picture && amount >= 0.999f && bgFadeAt == 0 && bgTo != null && bgTo.frostImage != null);
        coveredAll = spotlight;
        skyCovered = covered;
        if (look.m.animatedBackground) {
            if (!covered && skyStale) {
                skyStale = false;
                sky.invalidate();
            }
            return;
        }
        int v = covered ? INVISIBLE : VISIBLE;
        if (sky.getVisibility() == v) return;
        sky.setVisibility(v);
        if (!covered) sky.invalidate();     // it may have missed a new picture while hidden
    }

    /** The picture of the home background: heavily blurred, with some of the lightly blurred one over it (the look's `homeSharp`). */
    private void drawHomePicture(Canvas c, Backdrop b, float alpha) {
        drawImage(c, b.frostImage, alpha);
        if (look.m.homeSharp > 0) drawImage(c, b.glassImage(), alpha * look.m.homeSharp);
    }

    /** The glow goes where the focus is, on the page that has it (sliding with it): the centre of the focused item,
     * blended by how focused each one is. */
    private final RectF focusRect = new RectF();
    private final float[] focusSum = new float[3];     // weight, x, y
    private int glowDrawn;
    private boolean coveredAll;
    // The glow glides from one item to the next and fades only when nothing has the focus (the pages' focus pops from
    // 0 on every move: followed as is, it went out and on again). In the page's own space; which page it's on.
    private final Spring glowX = new Spring(0.3f, 1, 0), glowY = new Spring(0.3f, 1, 0), glowOn = new Spring(0.25f, 1, 0);
    private HomePager.Slot glowPage;

    /** Returns true while the glow still moves. */
    private boolean updateFocusGlow(float dt) {
        float[] acc = focusSum;
        acc[0] = acc[1] = acc[2] = 0;
        float dy, strength;
        HomePager.Slot page = pager.current();
        if (onWatchingPage()) {
            if (watchingPage.focusedRect(focusRect)) addGlow(acc, 1, focusRect);
            dy = catalogLayer.getTranslationY();
            strength = acc[0];
        } else if (onCatalogPage()) {
            if (catalogPage.focusedRect(focusRect)) addGlow(acc, 1, focusRect);
            dy = browseLayer.getTranslationY();
            strength = acc[0];
        } else {
            if (page == topSlot) top.glow(acc);
            dy = heroLayer.getTranslationY();
            strength = acc[0] > 0.01f ? 1 : 0;      // the tiles hand the focus over to each other: it doesn't go out
        }
        boolean focused = acc[0] > 0.01f;
        glowOn.target = focused && !coveredAll ? strength : 0;
        if (coveredAll) glowOn.snap(0);     // under Spotlight's photo: no fade nobody sees
        if (focused) {
            float x = acc[1] / acc[0], y = acc[2] / acc[0];
            // Out, or on another page: it comes on where the focus is, without sliding over from where it was.
            if (glowOn.value < 0.01f || page != glowPage) {
                glowX.snap(x);
                glowY.snap(y);
            } else {
                glowX.target = x;
                glowY.target = y;
            }
            glowPage = page;
        }
        boolean moving = glowOn.step(dt) | glowX.step(dt) | glowY.step(dt);
        boolean on = glowOn.value > 0.01f;
        glowLayer.setVisibility(on ? VISIBLE : INVISIBLE);
        if (!on) return moving;
        glowLayer.setAlpha(Math.min(1, glowOn.value));
        glowLayer.setTranslationX(glowX.value - glowLayer.getWidth() / 2f);
        glowLayer.setTranslationY(glowY.value + dy - glowLayer.getHeight() / 2f);
        updateGlowColor();
        return moving;
    }

    private void updateGlowColor() {
        if (glowLayer.getVisibility() != VISIBLE) return;
        int color = look.glowColor(dark());
        if (color != glowDrawn) {
            glowDrawn = color;
            glowLayer.invalidate();
        }
    }

    private static void addGlow(float[] acc, float v, RectF r) {
        acc[0] += v;
        acc[1] += v * r.centerX();
        acc[2] += v * r.centerY();
    }

    private void drawLightImage(Canvas c, Backdrop b, float alpha) {
        if (night < 0.999f || b.nightImage == null) drawImage(c, b.lightImage, alpha);
        if (night > 0.001f) drawImage(c, b.nightImage, alpha * night);
    }

    /** The picks carousel and its tabs: needs the TMDB key, and the person hasn't turned it off (Settings > TMDB). */
    boolean picksOn() {
        return Recs.hasKey(getContext()) && Prefs.picksOn();
    }

    // MARK: - Helpers

    static int blend(int a, int b, float f) {
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
    }
}
