package com.skyhome.launcher;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The home screen, styled after the Apple TV app with the macOS 27 glass. The picks carousel (Settings > TMDB >
 * "Picks carousel") has three styles, `Prefs.picksStyle()`:
 *  - **Off**: no tabs, no cards, just the weather, the icon buttons and the shelf.
 *  - **Cards** (the look this class was first built around): tabs (Explore, Movies, Shows) under the weather, three
 *    cards with the titles recommended for right now in the selected tab (Recs, from TMDB), the background the first
 *    card's photo heavily blurred and almost white (Backdrop); without one, a light gradient.
 *  - **Spotlight** (stock Google TV's "Top picks for you" style): no tabs, a full-screen sharp photo of the focused
 *    pick from the top of the screen down (`Look.spotlightPhoto`, each look's own corner/scrim treatment), its title
 *    and meta over the dark side, and a poster row that drives it (`drawSpotlight`) — the same `cards` list Cards mode
 *    uses, always from Explore's pool, but scrolled (not re-sorted to the front) via `spotlightRowShift`, the same
 *    pattern the catalog rows' `catShift` already uses. The shelf doesn't live on this screen at all: Down from the
 *    row pages straight to the drawer/catalog screen (below), landing on the shelf as its first row.
 * In every style: at the top right, icon-only glass buttons (Home Settings, Settings, Live TV, AV and HDMI input),
 * with the focused one's name below it, and the small crypto quotes (logo + price) at the right end of that row; at
 * the bottom, the glass shelf with the apps (icons in each brand's color, like the tvOS Dock; the focused one grows).
 * ↓ on the shelf: everything moves up and the bottom drawer appears
 * (inputs, shortcuts, weather for the next hours and quotes with the 24 h change); the background lightens (darkens
 * at night). ↑ or Back closes it. From sunset to sunrise, a night look: dark background, smoked glass and white text.
 * In Spotlight, Down from the poster row goes straight into this drawer/catalog screen in one press (skipping the
 * shelf-at-the-bottom step Cards mode has), with the shelf as the first thing on it instead of living on the hero
 * screen; Up from the shelf (or Back) closes it and returns to the poster row, same as Cards' shelf↔row behavior.
 * Measurements are in a 1920×1080 space (k = width / 1920). The screen is split into layers so the TV's weak GPU only
 * redraws what changed.
 */
final class HomeView extends ViewGroup implements Choreographer.FrameCallback, HomeData.Listener, Recs.Listener, Catalogs.Listener, Details.Listener {
    // tvOS grid (Human Interface Guidelines): content inside the safe area, 80 px from the sides and 60 from top and
    // bottom; 40 px between items; 7 columns of 217 px (the apps). The shelf glass extends 36 px past the tiles (the
    // same padding on all four sides) and the tile corners are concentric with it (glass radius = radius + padding).
    private static final float MARGIN = 80, CONTENT_R = 1840;
    // Spotlight's title: at most this wide (a two-line title grows upward).
    private static final float HERO_TITLE_W = 900;
    // Shelf: tiles with corners concentric with the glass (glass radius = tile radius + padding).
    // Shelf like the tvOS 27 Dock: icons in each brand's color, 48 apart, and the glass bar hugging them with 40 of
    // padding (the focused icon grows 20% without touching its edge). The icons are 16:9, the shape of the apps' TV
    // banners, so those are never stretched: up to 224 × 126, 178 × 100 with eight apps.
    private static final float SHELF_PAD = 40, TILE_GAP = 48;
    private static final float TILE_RADIUS = 22;
    // Bottom drawer (measured with it open; everything moves up by SHIFT).
    private static final float SHIFT = 560, CARDS_TOP = 708, CARDS_H = 300;
    // Spotlight (the full-screen picks carousel): a full-bleed photo from the top of the screen, the title and a
    // poster row near the bottom. Positions tuned on-TV, not fixed by a grid.
    // Round 2: posters are TMDB poster art (2:3, the title baked in), not landscape backdrop crops, so they're taller
    // and the whole block moved up to leave them real room.
    // Round 3: the shelf no longer lives on this screen at all (Down pages to the drawer/catalog screen, the shelf as
    // its first row — see moveLight's Z_CARDS case and updateMotion); the row scrolls (spotlightRowShift) instead of
    // re-sorting the focused pick to the front, and draws one column past SPOTLIGHT_VISIBLE so the next one peeks in.
    private static final float SPOTLIGHT_TITLE_Y = 526, SPOTLIGHT_META_GAP = 46, SPOTLIGHT_HEADING_Y = 652,
            SPOTLIGHT_ROW_TOP = 676, SPOTLIGHT_POSTER_W = 140, SPOTLIGHT_POSTER_H = 210, SPOTLIGHT_POSTER_GAP = 20,
            SPOTLIGHT_SHELF_GAP = 60;
    private static final int SPOTLIGHT_VISIBLE = 11;
    // "All Apps" sheet.
    private static final int COLUMNS = 5, VISIBLE_ROWS = 3;
    private static final float GRID_W = 300, GRID_H = 169, GRID_GAP = 40, GRID_ROW = 239, GRID_TOP = 196, GRID_RADIUS = 22;
    private static final int SMOKE = 0xFF111113, INK = 0xFF1C1C1E, WHITE = 0xFFF5F5F7;
    private static final int UP_COLOR = 0xFF30D158, DOWN_COLOR = 0xFFFF453A;
    private static final long TOAST_MS = 6000, FADE_MS = 800;
    /** Blur radii of the shadow and shadowDeep paints (Shadows needs them to draw the same blur on older TVs). */
    private static final float SHADOW_BLUR = 26, DEEP_BLUR = 30;
    // The input popover (1920-wide units): the panel's width, its rows, and the layer that holds it with room for the shadow.
    private static final float POP_W = 520, POP_ROW_H = 72, POP_TITLE_H = 64, POP_PAD = 14, POP_TOP = 168,
            POP_LAYER_W = 600, POP_LAYER_H = 800, POP_LAYER_TOP = 130;
    private static final int SKY = 0, HERO = 1, STATUS = 2, PLATTER = 3, TILES = 4, DRAWER = 5, SHEET = 6, TOAST = 7, CATALOG = 8, BROWSE = 9, POP = 11;
    private static final int Z_SHELF = 2, Z_DRAWER = 3;
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    // Light design (the L_ constants): title, tab bar, three recommendation cards and the shelf.
    // The tab bar is a 68 px capsule; the cards sit on the 5-column grid (the wide one spans 3).
    private static final float L_PLATTER_TOP = 814, L_TILE_H = 126;
    private static final float L_DATE_BASE = 330, L_CLOCK_BASE = 540, L_WEATHER_BASE = 626, L_QUOTES_BASE = 694;
    private static final int L_INK = 0xFF1D1D1F, L_DIM = 0xFF6E6E73, L_FAINT = 0xFF8E8E93;
    private static String[] tabs;
    private static int tabsGeneration = -1;
    private static final String[] TAB_KIND = {null, "movie", "tv"};
    private static final int Z_TOP = 4, Z_CARDS = 5, Z_CATALOG = 6, Z_BROWSE = 10;
    // Poster rows under the drawer's cards (measured in the drawer's resting space): row r starts at CAT_Y0 + r * CAT_PITCH; pressing
    // Down from the drawer lifts everything by CAT_LIFT (the first row then sits at 400), each row after it by CAT_PITCH.
    private static final float CAT_Y0 = 1040, CAT_LIFT = 640, CAT_PITCH = 440,
            CAT_POSTER_W = PosterRow.POSTER_W, CAT_POSTER_H = PosterRow.POSTER_H, CAT_GAP = PosterRow.GAP;
    // The blank space between one row's posters and the next row's title (CAT_PITCH minus a poster's own 50-above/
    // 30-below offsets): how far "Shows you watch" sits below "Continue Watching"'s posters.
    private static final float CAT_ROW_GAP = CAT_PITCH - 50 - CAT_POSTER_H + 30;

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
    private final Layer sky, heroLayer, status, platter, tiles, drawerLayer, sheetLayer, popLayer, toastLayer;
    private int zone = Z_SHELF;

    // Night look (like Apple's "Automatic" appearance): darker from sunset to sunrise, with a 2 s transition.
    private static final long NIGHT_MS = 2000;
    private boolean nightTarget;
    private float night, nightFrom;     // 0 = day, 1 = night
    private long nightAt;
    private final int[] glassNight = new int[Backdrop.GW * Backdrop.GH], frostNight = new int[Backdrop.FW * Backdrop.FH];
    private final int[] neutralGlassPx = new int[Backdrop.GW * Backdrop.GH], neutralFrostPx = new int[Backdrop.FW * Backdrop.FH];
    private boolean nightPair;          // the glass holds both photos (day and night), blended by `night`
    private Shader nightGradient;

    // At the top: tabs (0 to 2) and icon-only buttons (3 to 5); the three cards
    private int tab, topIndex, cardIndex;
    private final Recs.Pick[] prevCards = new Recs.Pick[3];     // the picks the cards showed before the change in progress
    private final RectF fadeBounds = new RectF();
    // The picks travel through the cards: `carousel` is the position of the row in cards (it follows `carouselTarget`, the
    // number of steps taken, on a spring with a little overshoot), the pick `a` sits `a - position` cards from the first.
    private final Spring carousel = new Spring(0.44f, 0.8f, 0);
    private int carouselTarget;
    private final RectF slotA = new RectF(), slotB = new RectF(), travel = new RectF();
    private final Rect imageSrc = new Rect();
    private long cardMixAt;         // when the cards last changed their picks (0: never)
    private int cardMixDir;         // 1: the next picks (slide left), -1: the previous ones, 0: just fade
    private boolean cardsAnimating; // a picture is fading in (set while drawing)
    private int cardStart;      // with more than 3 picks: the one in the first card; the others follow, in a circle
    private int widgetIndex;    // with TalkBack on, the drawer's weather (0) or quotes (1) card can take the focus
    private List<Recs.Pick> cards = new ArrayList<>();
    // The three tabs, then the round buttons at the top right (up to HomeApps.MAX_TOP).
    private final Spring[] topFocus = new Spring[3 + HomeApps.MAX_TOP];
    private final RectF[] topRects = new RectF[3 + HomeApps.MAX_TOP];
    private final Spring[] cardFocus = new Spring[3];
    private final RectF[] cardRects = new RectF[3];
    private final List<HomeApps.Item> lightButtons = new ArrayList<>();
    // Spotlight's poster row: the same fixed `cards` list Cards mode uses, but unreordered and scrolled (not
    // re-sorted to put focus at slot 0) — `cardStart` is the focused *column* (an absolute index into `cards`, not a
    // rotation offset), `spotlightRowShift` is the first visible column (a Spring, same pattern as the catalog rows'
    // `catShift`). The focused column also drives the full-screen background, via `cardFocus[0]` (reused).
    private final Spring spotlightRowShift = new Spring(0.34f, 1f, 0);
    private final RectF spotlightFocusedRect = new RectF();
    private Paint lTitle, lTab, lTabOn, lCardTitle, lCardMeta, lQuote, lTip, lDate, lClock, lWeather, lClockQuote,
            lSpotlightTitle, lSpotlightMeta, lSpotlightHeading;
    private Shader lightGradient;

    // Shelf
    private List<HomeApps.Item> shelf = new ArrayList<>();
    private Spring[] tileFocus = new Spring[0];
    private RectF[] tileRects = new RectF[0];
    private final Map<String, Bitmap> crispLogos = new HashMap<>();   // logos already sized for the focused tile
    /** Each app's tile like its Apple TV icon: brand colors (top, bottom) and logo (0 = as is, 1 = white, 2 = dark). */
    private static final Map<String, int[]> TILE_STYLE = new HashMap<>();
    static {
        TILE_STYLE.put("netflix", new int[] {0xFF0A0A0A, 0xFF000000, 0});
        TILE_STYLE.put("youtube", new int[] {0xFF1C1C1E, 0xFF09090A, 0});
        TILE_STYLE.put("globoplay", new int[] {0xFFFF8A1F, 0xFFFF2D5A, 1});
        TILE_STYLE.put("prime", new int[] {0xFF2AA8FF, 0xFF0A68D4, 1});
        TILE_STYLE.put("disney", new int[] {0xFF173584, 0xFF08143F, 0});
        TILE_STYLE.put("max", new int[] {0xFF5C30E8, 0xFF221684, 0});
        TILE_STYLE.put("appletv", new int[] {0xFF1C1C1E, 0xFF000000, 0});
    }
    private final android.graphics.PorterDuffColorFilter whiteLogo =
            new android.graphics.PorterDuffColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN);
    private final RectF platterRect = new RectF();
    private int shelfIndex;

    // Moving apps on the shelf (as on Apple TV): hold OK on a tile, then ← → move it, OK drops it, Back cancels. The
    // lifted tile wiggles; the others slide to their new places (each slot's x offset is a spring that returns to 0).
    private static final long HOLD_MS = 500, WIGGLE_MS = 300;
    private static final float LIFT_GROW = 0.08f, WIGGLE_DEG = 1.5f;
    private boolean reordering, reorderAnim, okHeld, holdArmed;
    private int liftIndex = -1;
    private long reorderAt;
    private float wiggle;
    private Spring[] tileSlide = new Spring[0];
    private final Spring moveLift = new Spring(0.3f, 0.72f, 0);
    private List<HomeApps.Item> reorderOrigin;
    private final Runnable holdStart = () -> { if (this.okDown && this.holdArmed) hold(); };

    // Background
    private long bgFadeAt;
    private Backdrop bgFrom, bgTo;     // null = gradient (no photo)
    private final int[] frostMix = new int[Backdrop.FW * Backdrop.FH];

    // Bottom drawer
    private final RectF quotesCard = new RectF();
    private final Spring drawer = new Spring(0.46f, 0.92f, 0);
    private boolean drawerOpen;

    // The input popover: under the HDMI input and AV input top buttons, one row for each input of that kind.
    private final Spring popIn = new Spring(0.34f, 0.88f, 0);
    private Spring[] popFocus = new Spring[0];
    private List<HomeApps.Item> popItems = new ArrayList<>();
    private final RectF popPanel = new RectF(), popRow = new RectF();
    private String popTitle = "";
    private Runnable[] popActions;     // set for a poster's menu: OK runs the focused row's; null for the inputs, where OK opens the one chosen
    private final RectF tileAnchor = new RectF();
    private int popIndex;
    private boolean popOpen, popDirty;
    private Paint popLabel;
    private final Catalogs catalogs;
    private final Layer catalogLayer;
    private final Spring catIn = new Spring(0.5f, 0.92f, 0), catRow = new Spring(0.42f, 0.92f, 0), catFocus = new Spring(0.3f, 0.82f, 0);
    private List<Catalogs.Row> catRows = new ArrayList<>();
    private Spring[] catShift = new Spring[0];     // per row: the first poster shown, in posters
    private int[] catCol = new int[0];
    private int catRowIndex;
    private boolean catDirty, catWasMoving;
    private Paint lCatTitle, lCatLabel;
    // Page 4, TMDB's catalogs (HomeCatalogPage), under Trakt's rows; it comes in as `browseIn` goes to 1.
    private final Layer browseLayer;
    private final Details details;
    private final HomeCatalogPage catalogPage;
    private final Spring browseIn = new Spring(0.5f, 0.92f, 0);
    private final RectF cardRect = new RectF(), barRect = new RectF();

    // "All Apps" sheet
    private List<HomeApps.Item> others = new ArrayList<>();
    private Spring[] gridFocus = new Spring[0];
    private String[] gridLabels;
    private final Spring sheet = new Spring(0.38f, 0.9f, 0);
    private final Spring scroll = new Spring(0.34f, 1f, 0);
    private final RectF focusedCell = new RectF();
    private boolean sheetOpen, shelfEdited;
    private int gridIndex;
    private long toggledAt;

    // Toasts
    private final ArrayDeque<String> toasts = new ArrayDeque<>();
    private StaticLayout toast;
    private long toastAt;

    // Frames
    private boolean running, tilesDirty, statusDirty, heroDirty, drawerDirty, gridDirty, motionDirty = true;
    private boolean drawerWasMoving, idle;
    private long lastNanos, resumedAt, lastStep;
    private static final long BURST_MS = 1500;
    private int w, h;
    private float k = 1;
    private String clockText = "", dateText = "", weatherLine = "";
    /** "Off in 42 min" next to the round buttons while the sleep timer runs (Settings > Sleep Timer); "" otherwise. */
    private String sleepText = "";
    private long weatherLineAt = -1;
    private int weatherLinePrefs = -1;
    private String weatherLineLanguage = "";
    private long clockMinute = -1;
    private boolean okDown, backDown;

    private Paint labelPaint, metaPaint, clockPaint, buttonPaint, tipPaint, quotePaint, staleQuote;
    private Paint rowTitle, cardTitle, cardText, cardDim, cardDimRight, bigTemp, hourPaint, hourTemp;
    private Paint pricePaint, changePaint, sheetTitle;
    private TextPaint gridLabel, toastPaint;
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowSoft = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowDeep = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Shadows shadows = new Shadows();
    private final Map<Bitmap, BitmapShader> imageShaders = new WeakHashMap<>();
    private final Matrix imageMatrix = new Matrix();
    private final Paint imageFill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tileFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF tmp = new RectF(), cell = new RectF(), screen = new RectF();
    private final TextPaint fitPaint = new TextPaint();
    private final RectF gridCell = new RectF();
    private LinearGradient[] tileShaders = new LinearGradient[0];
    private LinearGradient tileGlow, cardSheen;
    private float tileShaderH, cardSheenH;
    private LinearGradient[] lightSheens = new LinearGradient[0], lightRims = new LinearGradient[0];
    private float[] lightShaderW = new float[0];
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

        @Override protected void onDraw(Canvas c) {
            if (labelPaint == null) return;
            c.translate(-getLeft(), -getTop());
            // The layer may be shifted (bottom drawer): the glass reads the background from where it really is. The
            // drawer and the top buttons are always drawn as if resting in place (they are only visible there and are
            // not redrawn while they move).
            scene.setOffset(0, kind == DRAWER || kind == STATUS ? 0 : getTranslationY());
            shadows.dither = dark() > 0.5f;
            switch (kind) {
                case SKY: drawSky(c); break;
                case HERO: drawLightTop(c); break;
                case STATUS: drawLightButtons(c); break;
                case PLATTER: drawPlatter(c); break;
                case TILES: drawTiles(c); break;
                case DRAWER: drawDrawer(c); break;
                case CATALOG: drawCatalog(c); break;
                case BROWSE: if (browseIn.value >= 0.002f) catalogPage.draw(c); break;
                case SHEET: drawSheet(c); break;
                case POP: drawPopover(c); break;
                default: drawToast(c); break;
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
            if (zone != Z_CARDS && !sheetOpen && !drawerOpen && cards.size() > 3) {
                if (Prefs.spotlightOn()) autoAdvanceSpotlight();
                else rotateCards(1);
            }
            handler.postDelayed(this, Config.PICK_ROTATE_SECONDS * 1000L);
        }
    };

    /** A look with a moving background: the sky layer is redrawn ten times a second (the drift is slow). */
    private final Runnable drift = new Runnable() {
        @Override public void run() {
            if (!running) return;
            sky.invalidate();
            handler.postDelayed(this, 100);
        }
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            wake();
            data.refreshIfStale();
            recs.refreshIfStale();
            catalogs.refreshWatchNext(false);       // the row may be the only one: nothing else would open the drawer for it
            if (drawerOpen) catalogs.refreshIfStale();
            checkNotices();
            updatePalette();
            checkNight(true);
            if (!Prefs.spotlightOn() && zone != Z_CARDS) recs.publish();   // a new daily list: not under the focus
            if (zone != Z_BROWSE) catalogs.publish();      // the day's TMDB rows: only while the browse screen isn't open
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
        heroLayer = new Layer(a, HERO);
        status = new Layer(a, STATUS);
        platter = new Layer(a, PLATTER);
        tiles = new Layer(a, TILES);
        drawerLayer = new Layer(a, DRAWER);
        catalogLayer = new Layer(a, CATALOG);
        browseLayer = new Layer(a, BROWSE);
        catalogs = Catalogs.get(a);
        details = Details.get(a);
        catalogPage = new HomeCatalogPage(this, catalogs, details);
        LOADER.execute(LayoutCheck::readFlags);
        sheetLayer = new Layer(a, SHEET);
        popLayer = new Layer(a, POP);
        toastLayer = new Layer(a, TOAST);
        // Hardware layers on the GPU: only the one that changed is redrawn; moving, fading and scaling stay cheap.
        // Not on Android 8 to 11: on older GPUs (a Mi Box 3's Mali-450) redrawing and compositing a big layer on every
        // frame cost more than drawing straight to the screen (measured: focus on the picks 33 → 12 ms a frame, the
        // drawer 19 → 17 ms). heroLayer/status/platter/tiles/popLayer are page-1 content (the resting screen), so they
        // stay hardware-accelerated always; drawerLayer/catalogLayer/browseLayer/sheetLayer are other pages' content
        // and start off (they're also INVISIBLE below) — updateMotion() turns each one's GPU layer on only while its
        // own page is actually showing (#memory-management, item 3: ~25 MB of persistent GPU memory otherwise sat
        // behind pages that may never be opened in a session).
        for (View v : new View[] {heroLayer, status, platter, tiles, popLayer}) v.setLayerType(Ui.LEGACY ? LAYER_TYPE_NONE : LAYER_TYPE_HARDWARE, null);
        for (View v : new View[] {sky, heroLayer, status, platter, tiles, drawerLayer, catalogLayer, browseLayer, sheetLayer, popLayer, toastLayer}) {
            v.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);   // TalkBack reads the items (Spoken)
            addView(v);
        }
        setContentDescription("Sky Home Launcher");
        drawerLayer.setVisibility(INVISIBLE);
        popLayer.setVisibility(INVISIBLE);
        catalogLayer.setVisibility(INVISIBLE);
        browseLayer.setVisibility(INVISIBLE);
        for (int i = 0; i < topFocus.length; i++) {
            topFocus[i] = new Spring(0.26f, 0.85f, 0);
            topRects[i] = new RectF();
        }
        for (int i = 0; i < 3; i++) {
            cardFocus[i] = new Spring(0.32f, 0.78f, 0);
            cardRects[i] = new RectF();
        }
        setLightButtons(HomeApps.topButtons(a));
        // Spotlight's shelf doesn't live on this screen (it's the drawer/catalog page's first row, not the bottom of
        // the hero screen) — starting focus there, as every other style does, would land on something invisible.
        if (Prefs.spotlightOn()) zone = Z_CARDS;
        checkNight(false);
        sheetLayer.setVisibility(INVISIBLE);
        scene.setMotion(0.6f, 0f);
        updatePalette();
        // First time: right away (a cold start opens with the apps already there); missing logos come from reload().
        setShelf(HomeApps.shelf(a, false));
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
        lastStep = 0;
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
        if (sheetOpen) closeSheet();
        sheet.snap(sheet.target);
        motionDirty = true;
        invalidateAll();
        idle = false;
        Choreographer.getInstance().postFrameCallback(this);
        spoken.readAgain();
    }

    void pause() {
        if (!running) return;
        running = false;
        cancelReorder(true);
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

    /** Starting state (Home pressed while the home screen is showing): first app focused, drawer and sheet closed. */
    void reset(boolean animated) {
        wake();
        cancelReorder(!animated);
        zone = Prefs.spotlightOn() ? Z_CARDS : Z_SHELF;   // Spotlight's shelf isn't on this screen (see constructor)
        shelfIndex = 0;
        sheetOpen = false;
        if (drawerOpen) catalogsClosed();
        drawerOpen = false;
        if (Prefs.spotlightOn()) { drawer.target = 0; catIn.target = 0; catRow.target = 0; }   // close its page too
        closePop(!animated);
        if (animated) {
            sheet.target = 0;
            drawer.target = 0;
            catIn.target = 0;
            catRow.target = 0;
            browseIn.target = 0;
        } else {
            sheet.snap(0);
            drawer.snap(0);
            catIn.snap(0);
            catRow.snap(0);
            browseIn.snap(0);
        }
        if (tab != 0) selectTab(0);
        retarget(!animated);
        motionDirty = true;
    }

    /** The round buttons at the top right (Settings > Top Buttons), with their names in the current language. */
    private void setLightButtons(List<HomeApps.Item> list) {
        lightButtons.clear();
        lightButtons.addAll(list);
        if (zone == Z_TOP && topIndex >= 3 + lightButtons.size()) {
            topIndex = 2 + lightButtons.size();
            // No tab slots to land on: without picks, or in Spotlight (no tabs at all there), there's nothing in
            // Z_TOP to focus — back to the poster row in Spotlight (its shelf isn't on this screen), the shelf elsewhere.
            if (topIndex < 3 && !picksOn()) zone = Z_SHELF;
            else if (topIndex < 3 && Prefs.spotlightOn()) zone = cards.isEmpty() ? Z_SHELF : Z_CARDS;
            else if (topIndex < 3) selectTab(topIndex);
            retarget(true);
        }
        layoutLight();
        statusDirty = true;
    }

    /** The tab names, made again only when the language changes (they are drawn on every frame). */
    private static String[] tabs() {
        if (tabsGeneration != Ui.generation) {
            tabs = new String[] {Ui.tr("Explore"), Ui.tr("Movies"), Ui.tr("Shows")};
            tabsGeneration = Ui.generation;
        }
        return tabs;
    }

    @Override public void onData() {
        wake();
        updatePalette();
        checkNight(true);
        heroDirty = statusDirty = drawerDirty = true;
        platter.invalidate();
    }

    @Override public void onRecs() {
        wake();
        updatePicks();
    }

    private void invalidateAll() {
        for (View v : new View[] {sky, heroLayer, status, platter, tiles, drawerLayer}) v.invalidate();
    }

    // MARK: - Size and layers

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        float kk = width / 1920f;
        measure(sky, width, height);
        // Spotlight's poster row and heading sit below Cards mode's fixed 790px hero height, so it needs the full screen.
        measure(heroLayer, width, Prefs.spotlightOn() ? height : Math.round(790 * kk));
        measure(status, Math.round(1160 * kk), Math.round(240 * kk));
        // Sized to what they draw (the glass and its shadow; the grown tiles and their shadows): smaller layers are
        // cheaper for the GPU to composite on every frame of the drawer animation. Spotlight moves the shelf across
        // most of the screen's height (a sliver near the bottom, "row -1" up near the catalog rows once that page
        // opens, scrolling away further still as you browse deeper) — a small, Cards-sized box would clip it the same
        // way the hero layer used to clip the poster row (see the 790px note above), so it gets the full screen too.
        measure(platter, width, Prefs.spotlightOn() ? height : Math.round(320 * kk));
        measure(tiles, width, Prefs.spotlightOn() ? height : Math.round(270 * kk));
        measure(drawerLayer, width, Math.round(600 * kk));
        measure(catalogLayer, width, height);
        measure(browseLayer, width, height);
        measure(sheetLayer, width, height);
        measure(popLayer, Math.round(POP_LAYER_W * kk), Math.round(POP_LAYER_H * kk));
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
        place(heroLayer, 0, 0);
        place(status, width - status.getMeasuredWidth(), 0);
        place(platter, 0, Prefs.spotlightOn() ? 0 : Math.round(750 * kk));
        place(tiles, 0, Prefs.spotlightOn() ? 0 : Math.round(790 * kk));
        place(drawerLayer, 0, Math.round(470 * kk));
        catalogLayer.layout(0, 0, width, height);
        browseLayer.layout(0, 0, width, height);
        sheetLayer.layout(0, 0, width, height);
        sheetLayer.setPivotX(width / 2f);
        sheetLayer.setPivotY(height / 2f);
        if (popPanel.isEmpty()) place(popLayer, Math.round((CONTENT_R - POP_W - 40) * kk), Math.round(POP_LAYER_TOP * kk));
        else placePop();
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
        gridLabels = null;
        clockMinute = -1;
        updateClock();
        layoutShelf();
        layoutDrawer();
        layoutLight();
        catalogPage.layout(k, w, h);
        updatePicks();
        motionDirty = true;
        wake();
    }

    private void makePaints() {
        labelPaint = Ui.text(Ui.medium, 25 * k, 0xC7FFFFFF);
        labelPaint.setLetterSpacing(0.1f);
        metaPaint = Ui.text(Ui.medium, 29 * k, 0xD1FFFFFF);
        metaPaint.setLetterSpacing(0.014f);
        clockPaint = Ui.numbers(Ui.thin, 176 * k, 0xFFFFFFFF);
        clockPaint.setLetterSpacing(-0.01f);
        // Spotlight's title/meta/heading sit directly on the full-screen photo (inside its dark corner treatment, both
        // looks, day or night), not on the page background applyTone()'s roles are meant for — fixed white, like the
        // old Hero title this replaces, not a look role.
        lSpotlightTitle = Ui.text(Ui.displaySemibold, 64 * k, 0xFFFFFFFF);
        lSpotlightTitle.setLetterSpacing(0.004f);
        lSpotlightMeta = Ui.text(Ui.medium, 30 * k, 0xD1FFFFFF);
        lSpotlightHeading = Ui.text(Ui.semibold, 30 * k, 0xE8FFFFFF);
        for (Paint p : new Paint[] {labelPaint, metaPaint, clockPaint, lSpotlightTitle, lSpotlightMeta, lSpotlightHeading})
            p.setShadowLayer(22 * k, 0, 2 * k, 0x59000000);
        lTitle = Ui.text(look.typeface(Ui.displaySemibold), 76 * k, L_INK);
        lTitle.setLetterSpacing(0.004f);
        lTab = Ui.text(look.typeface(Ui.medium), 30 * k, L_DIM);
        lTabOn = Ui.text(look.typeface(Ui.semibold), 30 * k, L_INK);
        lCardTitle = Ui.text(look.typeface(Ui.semibold), 31 * k, L_INK);
        lCardTitle.setLetterSpacing(0.012f);
        lCardMeta = Ui.text(look.typeface(Ui.medium), 25 * k, L_DIM);
        lQuote = Ui.numbers(Ui.regular, 21 * k, L_FAINT);
        lCatTitle = Ui.text(look.typeface(Ui.semibold), 32 * k, L_INK);
        lCatLabel = Ui.text(look.typeface(Ui.medium), 25 * k, L_INK);
        lTip = Ui.text(look.typeface(Ui.semibold), 25 * k, L_INK);
        lTip.setTextAlign(Paint.Align.CENTER);
        lDate = Ui.text(look.typeface(Ui.semibold), 34 * k, L_DIM);
        lClock = Ui.numbers(Ui.light, 200 * k, L_INK);
        lClock.setLetterSpacing(-0.01f);
        lWeather = Ui.text(look.typeface(Ui.medium), 34 * k, L_DIM);
        lClockQuote = Ui.numbers(Ui.medium, 28 * k, L_DIM);
        lightGradient = null;
        buttonPaint = Ui.text(Ui.semibold, 29 * k, INK);
        tipPaint = Ui.text(Ui.semibold, 25 * k, 0xF2FFFFFF);
        tipPaint.setTextAlign(Paint.Align.CENTER);
        tipPaint.setShadowLayer(16 * k, 0, 1 * k, 0x99000000);
        // Quotes: a subtle detail, smaller and dimmer than the rest.
        quotePaint = Ui.numbers(Ui.regular, 21 * k, 0xB8FFFFFF);
        quotePaint.setShadowLayer(14 * k, 0, 1 * k, 0x99000000);
        staleQuote = Ui.text(Ui.medium, 21 * k, 0x99FFFFFF);
        staleQuote.setTextAlign(Paint.Align.RIGHT);
        rowTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        popLabel = Ui.text(Ui.medium, 27 * k, 0xEBFFFFFF);
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
        sheetTitle = Ui.text(Ui.displaySemibold, 40 * k, 0xFFFFFFFF);
        sheetTitle.setTextAlign(Paint.Align.CENTER);
        gridLabel = new TextPaint(Ui.text(Ui.medium, 24 * k, 0x99FFFFFF));
        gridLabel.setTextAlign(Paint.Align.CENTER);
        toastPaint = new TextPaint(Ui.text(Ui.medium, 26 * k, 0xF2FFFFFF));
        shadow.setMaskFilter(new BlurMaskFilter(SHADOW_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        shadowSoft.setMaskFilter(new BlurMaskFilter(12 * k, BlurMaskFilter.Blur.NORMAL));
        shadowDeep.setMaskFilter(new BlurMaskFilter(DEEP_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        halo.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(2 * k);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(2.5f * k);
    }

    // MARK: - Frames: 60 per second while something moves; nothing is redrawn when still

    @Override public void doFrame(long frameTimeNanos) {
        if (!running) return;
        float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (frameTimeNanos - lastNanos) / 1e9f);
        lastNanos = frameTimeNanos;
        long up = SystemClock.uptimeMillis();

        boolean tilesMoving = step(tileFocus, dt);
        if (reorderAnim) tilesMoving |= stepReorder(dt, up);
        boolean topMoving = step(topFocus, dt), cardsMoving = step(cardFocus, dt);
        boolean spotlightRowMoving = spotlightRowShift.step(dt);
        if (topMoving) status.invalidate();
        boolean heroMoving = topMoving | cardsMoving | spotlightRowMoving;
        boolean gridMoving = step(gridFocus, dt) | scroll.step(dt);
        boolean drawerMoving = drawer.step(dt);
        boolean sheetMoving = sheet.step(dt);
        boolean popMoving = step(popFocus, dt) | popIn.step(dt);
        boolean catMoving = catIn.step(dt) | catRow.step(dt) | catFocus.step(dt) | step(catShift, dt);
        boolean browseMoving = browseIn.step(dt) | catalogPage.tick(dt);
        boolean toastFading = tickToast(up);
        heroMoving |= tickCards(up);
        heroMoving |= carousel.step(dt);
        boolean bgFading = tickBackgroundFade(up);
        boolean nightMoving = tickNight(up);

        if (drawerMoving || sheetMoving || catMoving || browseMoving || motionDirty) updateMotion();
        // Opening and closing the drawer only moves the cached layers: redrawing the glass on every frame made the TV
        // drop frames (measured: ~50 ms of GPU per frame). The app tiles have no glass; the top buttons fade out as
        // they rise; the drawer is drawn resting in place; the glass shelf is redrawn once the drawer stops, so the
        // glass shows the background where it ended up.
        // Spotlight's shelf is pinned to row 0 of the catalog (see layoutShelf): its position is recomputed from
        // catIn/catRow/drawer every frame they animate, so (unlike Cards, where the shelf only moves via its own
        // translationY) it needs its layers invalidated while those are moving too, not just once they settle.
        boolean spotlightShelfMoving = Prefs.spotlightOn() && (catMoving || drawerMoving);
        if (tilesMoving || tilesDirty || spotlightShelfMoving) tiles.invalidate();
        if (statusDirty || bgFading) status.invalidate();
        if (heroMoving || heroDirty) heroLayer.invalidate();
        if (drawerDirty || bgFading) drawerLayer.invalidate();
        if (gridMoving || gridDirty) sheetLayer.invalidate();
        if (popMoving || popDirty) popLayer.invalidate();
        if (!popOpen && popIn.value <= 0.001f && popLayer.getVisibility() == VISIBLE) popLayer.setVisibility(INVISIBLE);
        if (openingItem != null && up - openingAt >= OPEN_LOADER_MS) {      // the waiting dots move
            catDirty = true;
            catalogPage.invalidate();
        }
        if (catMoving || catDirty) catalogLayer.invalidate();
        if (catalogPage.takeDirty() | browseMoving) browseLayer.invalidate();
        if (drawerMoving || bgFading || ((look.m.focusGlow || look.m.homeBackdrop) && (tilesMoving || topMoving || cardsMoving || catMoving || browseMoving))) sky.invalidate();
        if (bgFading || (drawerWasMoving && !drawerMoving) || spotlightShelfMoving) platter.invalidate();
        drawerWasMoving = drawerMoving;
        if (catWasMoving && !catMoving) platter.invalidate();     // the glass shows the background where it ended up
        catWasMoving = catMoving;
        tilesDirty = statusDirty = heroDirty = drawerDirty = gridDirty = motionDirty = catDirty = popDirty = false;
        if (updateClock()) {
            status.invalidate();
            if (!picksOn()) heroLayer.invalidate();
        }

        // Busy: something is moving, or the sheet or the drawer is open.
        boolean busy = tilesMoving || heroMoving || gridMoving || drawerMoving
                || sheetMoving || popMoving || popOpen || catMoving || browseMoving || toastFading || bgFading || nightMoving || sheetOpen || sheet.value > 0.001f
                || drawerOpen || drawer.value > 0.001f || toast != null || !toasts.isEmpty();
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

    private static boolean step(Spring[] springs, float dt) {
        boolean moving = false;
        for (Spring s : springs) moving |= s.step(dt);
        return moving;
    }

    /** Drawer and sheet: the layers' positions and opacity (without redrawing their content). */
    private void updateMotion() {
        float d = drawer.value, dc = Ui.clamp(d), s = Ui.clamp(sheet.value);
        float b = smoothstep(0, 0.5f, Ui.clamp(browseIn.value));      // the browse screen takes the whole screen
        float home = (1 - s) * (1 - b), top = home * (1 - smoothstep(0, 0.32f, dc));
        float shift = SHIFT * k;
        float catLift = (catIn.value * catLift() + catRow.value * CAT_PITCH) * k;     // the poster rows pull everything further up
        float lift = -d * shift - catLift;
        show(heroLayer, top, lift);
        show(status, top, lift);
        float da = home * smoothstep(0.1f, 0.6f, dc);
        // The shelf is visible (at full alpha) on both pages — a sliver on the hero page, "row -1" of the catalog's
        // own coordinate system once the drawer opens. `layoutShelf()`'s own position formula now bakes in the
        // catIn/catRow transform directly (so it's pinned to Continue Watching, not independently animated), so the
        // shelf layer needs no extra translateY of its own in Spotlight — only Cards mode still uses `lift`.
        float shelfLift = Prefs.spotlightOn() ? 0 : lift;
        if (Prefs.spotlightOn()) layoutShelf();
        show(platter, home, shelfLift);
        show(tiles, home, shelfLift);
        drawerLayer.setTranslationY((1 - d) * shift - catLift);
        drawerLayer.setAlpha(da);
        showLayer(drawerLayer, da > 0.002f);
        float ca = home * smoothstep(0, 0.35f, Ui.clamp(catIn.value));
        catalogLayer.setAlpha(ca);
        showLayer(catalogLayer, ca > 0.002f);
        browseLayer.setAlpha(b);
        browseLayer.setTranslationY((1 - Ui.clamp(browseIn.value)) * 60 * k);
        showLayer(browseLayer, b > 0.002f);
        showLayer(sheetLayer, s > 0.001f || sheetOpen);
        sheetLayer.setAlpha(s);
        float scale = 1.04f - 0.04f * s;
        sheetLayer.setScaleX(scale);
        sheetLayer.setScaleY(scale);
    }

    private static void show(View v, float alpha, float translationY) {
        v.setAlpha(alpha);
        v.setTranslationY(translationY);
        v.setVisibility(alpha > 0.002f ? VISIBLE : INVISIBLE);
    }

    /**
     * Visibility and the GPU layer together, for a page whose content doesn't need its hardware layer to exist at
     * all while the page is fully closed (#memory-management, item 3): the layer comes back the moment it's needed
     * again, same as Android's own documented pattern for a hardware layer as a temporary aid, not a permanent canvas.
     */
    private static void showLayer(View v, boolean visible) {
        v.setVisibility(visible ? VISIBLE : INVISIBLE);
        if (!Ui.LEGACY) {
            int want = visible ? LAYER_TYPE_HARDWARE : LAYER_TYPE_NONE;
            if (v.getLayerType() != want) v.setLayerType(want, null);
        }
    }

    private void updateSleep() {
        boolean here = Prefs.sleepShown() && !android.provider.Settings.canDrawOverlays(getContext());   // else SleepOverlay
        int left = here ? SleepTimer.minutesLeft(getContext()) : 0;
        String text = left == 0 ? "" : Ui.trf("Off in {time}", "time", SleepTimer.duration(left));
        if (text.equals(sleepText)) return;
        sleepText = text;
        statusDirty = true;
    }

    private boolean updateClock() {
        long now = System.currentTimeMillis();
        long minute = now / 60_000;
        if (minute == clockMinute || clockPaint == null) return false;
        clockMinute = minute;
        clockText = Ui.clock(now);
        dateText = Ui.date(now);
        return true;
    }

    private void updatePalette() {
        HomeData.Weather wx = data.weather;
        Shaders.sky(scene.palette, System.currentTimeMillis(), wx.sunrise, wx.sunset);
        scene.applyPalette();
    }

    // MARK: - Recommendations: the picks and the background

    /** This hour's picks: the cards and the background image (called when Recs notifies and every 15 s). */
    private void updatePicks() {
        if (labelPaint == null) return;
        updateCards();
    }

    private static boolean samePicks(List<Recs.Pick> a, List<Recs.Pick> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!Recs.key(a.get(i)).equals(Recs.key(b.get(i)))) return false;
        return true;
    }

    /** Swaps the background with a cross-fade (from one image to another, from the gradient to an image, or back). */
    private void showBackdrop(Backdrop b) {
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
        heroDirty = statusDirty = tilesDirty = drawerDirty = true;
        sky.invalidate();
        platter.invalidate();
    }

    /** How dark it is: 0 = day, 1 = night (in between during the transition). Spotlight's chrome (weather, buttons,
     * shelf) always sits over a photo with a dark corner treatment, by day or by night (Look.spotlightPhoto), so it
     * leans toward the night palette even in the day theme instead of using low-contrast ink on a bright scrim. That
     * is only the hero page: the drawer and catalog pages sit on the look's own background, so by the time they are
     * open this is the real day/night value again (a white Material page stays white by day). */
    float dark() {
        if (!Prefs.spotlightOn() || !picksOn()) return night;
        return night + (Math.max(night, 0.7f) - night) * (1 - Ui.clamp(drawer.value));
    }

    /** The day or the night value of a glass surface (until those move into GlassLook). */
    private int tone(int onLight, int onDark) {
        return blend(onLight, onDark, dark());
    }

    float tone(float onLight, float onDark) {
        return onLight + (onDark - onLight) * dark();
    }

    /** Text colors for the current night value (dark by day, white at night). */
    private void applyTone() {
        lTitle.setColor(look.color(Look.TITLE, dark()));
        lTab.setColor(look.color(Look.TAB, dark()));
        lTabOn.setColor(look.color(Look.TAB_ON, dark()));
        lCardTitle.setColor(look.color(Look.TITLE, dark()));
        lCardMeta.setColor(look.color(Look.META, dark()));
        lQuote.setColor(look.color(Look.QUOTE, dark()));
        lTip.setColor(look.color(Look.TIP, dark()));
        lDate.setColor(look.color(Look.META, dark()));
        lClock.setColor(look.color(Look.TITLE, dark()));
        lWeather.setColor(look.color(Look.WEATHER, dark()));
        lClockQuote.setColor(look.color(Look.META, dark()));
        staleQuote.setColor(look.color(Look.FAINT, dark()));
    }

    private boolean tickBackgroundFade(long up) {
        if (bgFadeAt == 0) return false;
        float e = fadeEase(bgFadeAt, up);
        if (bgTo != null && bgFrom != null && glassOf(bgFrom) != null && glassOf(bgTo) != null) {
            if (drawerLayer.getVisibility() == VISIBLE || toast != null) {
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

    // MARK: - Text

    private static void text(Canvas c, String s, float x, float y, Paint p, int color, float alpha) {
        if (s == null || s.isEmpty()) return;
        p.setColor(Ui.alpha(color, alpha));
        c.drawText(s, x, y, p);
    }

    // MARK: - Quotes and shelf

    private void setShelf(List<HomeApps.Item> list) {
        wake();
        cancelReorder(true);    // the list changed under the move (an app installed or removed)
        tileShaders = new LinearGradient[0];
        boolean same = list.size() == shelf.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i).key.equals(shelf.get(i).key);
        shelf = list;
        if (!same || tileFocus.length != list.size()) {
            tileFocus = new Spring[list.size()];
            tileSlide = new Spring[list.size()];
            tileRects = new RectF[list.size()];
            for (int i = 0; i < tileFocus.length; i++) {
                tileFocus[i] = new Spring(0.34f, 0.74f, 0);
                tileSlide[i] = new Spring(0.32f, 0.86f, 0);
                tileRects[i] = new RectF();
            }
            shelfIndex = Math.min(shelfIndex, Math.max(0, list.size() - 1));
            retarget(true);
            layoutShelf();
        }
        tilesDirty = true;
        platter.invalidate();
    }

    private void layoutShelf() {
        int n = shelf.size();
        float pad = SHELF_PAD * k, side = look.m.shelfPad * k, gap = look.m.shelfGap * k;
        float full = (CONTENT_R - MARGIN) * k;
        // Tiles in the TV banners' own 16:9, so an app's art is never stretched; never taller than L_TILE_H. The glass
        // keeps its bottom edge, so with more apps it only gets lower.
        float fit = (full - Math.max(0, n - 1) * gap) / Math.max(1, n);
        float tw = look.m.shelfFill ? Math.min(300 * k, fit) : Math.min(L_TILE_H * 16f / 9 * k, fit);
        float th = tw * 9f / 16;
        // Looks without a visible glass pill (tiles fill the width, no side padding) keep a slim bar above Continue Watching;
        // the others keep Cards' own shelf padding, so the shelf is the same size in both styles.
        if (Prefs.spotlightOn() && look.m.shelfFill) pad = 10 * k;
        float width = n * tw + Math.max(0, n - 1) * gap, left = look.m.shelfFill ? MARGIN * k : (w - width) / 2f;
        float top = (L_PLATTER_TOP + L_TILE_H) * k - th;
        // Spotlight shows a sliver of the shelf on the hero page (about half the platter block, as a hint there's
        // more below), and once the page opens, the shelf is "row -1" of the catalog's own coordinate system —
        // positioned just above row 0, with the exact same `catIn`/`catRow` transform `drawCatalog` applies to every
        // real row — so it's guaranteed to sit immediately above Continue Watching, never overlapping it, and
        // scrolls away with the same motion as everything else once you move deeper into the catalog. The two
        // states are blended by `drawer.value` (already animating every frame it moves).
        if (Prefs.spotlightOn()) {
            float platterH = th + 2 * pad;
            float hiddenTop = h - platterH * 0.5f;
            // Row 0's own top (same formula `drawCatalog` uses for `y0`), minus the same gap every catalog row
            // keeps from the one above it (CAT_ROW_GAP), minus the shelf's own height: the shelf's *bottom* lands
            // just above Continue Watching's *top*, with the same rhythm as Continue Watching has above Shows you watch.
            float row0Top = (catY0() - catIn.value * catLift() - catRow.value * CAT_PITCH) * k;
            // The gap shrinks only to fit the resting layout (row 0 at its docked spot, catRow 0); it must not be a
            // clamp on the live position, or the shelf would stop while row 0 keeps scrolling up over it.
            float restRow0Top = (catY0() - catLift()) * k;
            float rowGap = Math.min(CAT_ROW_GAP * k, Math.max(0, restRow0Top - platterH - 24 * k));
            // Always row 0's live position minus an offset, so the two move in lockstep (blending by another spring
            // let Continue Watching slide over the shelf mid-transition). The offset eases from the hidden sliver's to
            // the docked one while the catalog is still fading in (see `ca` in updateMotion).
            float hiddenOff = (catY0() * k) - hiddenTop;
            float dockedOff = rowGap + platterH;
            float e = smoothstep(0, 0.35f, Ui.clamp(catIn.value));
            top = row0Top - (hiddenOff + (dockedOff - hiddenOff) * e);
        }
        platterRect.set(left - side, top, left + width + side, top + th + 2 * pad);
        float x = left, y = top + pad;
        for (int i = 0; i < n; i++) {
            tileRects[i].set(x, y, x + tw, y + th);
            x += tw + gap;
        }
        crispLogos.clear();
    }

    /** Shelf layer: the glass bar and its shadow (it only changes with the background). */
    private void drawPlatter(Canvas c) {
        if (shelf.isEmpty()) return;
        look.platter(c, platterRect, (TILE_RADIUS + SHELF_PAD) * k, dark());
    }

    /** The quotes row's text and sizes, for one set of coins, prices and text size. */
    private final class Quotes {
        final int[] coins;
        final double[] prices;
        final float size, cap, halfCap;
        final String[] values;
        final float[] glyphs, widths;

        Quotes(int[] coins, double[] all, Paint text) {
            this.coins = coins;
            size = text.getTextSize();
            cap = Ui.cap(text);
            halfCap = Ui.halfCap(text);
            int n = coins.length;
            prices = new double[n];
            values = new String[n];
            glyphs = new float[n];
            widths = new float[n];
            for (int i = 0; i < n; i++) {
                prices[i] = all[coins[i]];
                values[i] = HomeData.compact(prices[i]);
                glyphs[i] = Glyphs.width(getContext(), HomeData.COINS[coins[i]], cap);
                widths[i] = text.measureText(values[i]);
            }
        }

        boolean matches(int[] now, double[] all, float textSize) {
            if (now != coins || textSize != size) return false;
            for (int i = 0; i < coins.length; i++) if (Double.compare(all[coins[i]], prices[i]) != 0) return false;
            return true;
        }
    }

    private Quotes quotes;

    /** Coin logo + price in the configured currency, in a row at the right end of the tab bar. */
    private void drawQuotes(Canvas c) {
        boolean clock = !picksOn();
        // At the right margin, under the round buttons: the shelf's glass is narrower and centred with few apps.
        float right = CONTENT_R * k;
        applyTone();
        Paint text = clock ? lClockQuote : lQuote;
        // The corner buttons' names appear in this row: the quotes fade out while one of them has focus.
        float fade = 1;
        for (int i = 3; i < 3 + lightButtons.size() && !clock; i++) fade = Math.min(fade, 1 - Ui.clamp(topFocus[i].value));
        if (fade <= 0.01f) return;
        int textColor = text.getColor();
        text.setColor(Ui.alpha(textColor, fade));
        // One read of the list, so n always matches the coins drawn below.
        int[] coins = HomeData.active();
        int n = coins.length;
        if (n == 0) { text.setColor(textColor); return; }
        // Measured once per set of prices: this row is redrawn on every frame of the cards' focus animation, and
        // measuring text there cost ~3 ms a frame on a Mi Box 3.
        Quotes q = quotes;
        if (q == null || !q.matches(coins, data.prices, text.getTextSize())) {
            q = quotes = new Quotes(coins, data.prices, text);
        }
        // On the tab row, at the right.
        float base = clock ? L_QUOTES_BASE * k : (look.m.tabTop + look.m.tabH / 2) * k + q.halfCap;
        float cap = q.cap, gap = (clock ? 8 : 6) * k, spacing = (clock ? 32 : 24) * k;
        float total = spacing * (n - 1);
        for (int i = 0; i < n; i++) total += q.glyphs[i] + gap + q.widths[i];
        float x = clock ? MARGIN * k : right - total;
        int coinColor = clock ? text.getColor() : Ui.alpha(look.color(Look.COIN_DIM, dark()), fade);
        for (int i = 0; i < n; i++) {
            Glyphs.coin(getContext(), c, HomeData.COINS[coins[i]], x + q.glyphs[i] / 2, base - cap / 2, cap, coinColor);
            x += q.glyphs[i] + gap;
            c.drawText(q.values[i], x, base, text);
            x += q.widths[i] + spacing;
        }
        text.setColor(textColor);
        String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
        if (stale.isEmpty()) return;
        if (clock) c.drawText(stale, x - spacing + 24 * k + staleQuote.measureText(stale), base, staleQuote);
        else c.drawText(stale, right - total - 24 * k, base, staleQuote);
    }

    private void drawTiles(Canvas c) {
        // The focused one last: it grows over its neighbors without being covered by them.
        int top = -1;
        float most = 0.02f;
        for (int i = 0; i < shelf.size(); i++) {
            if (tileFocus[i].value > most) {
                most = tileFocus[i].value;
                top = i;
            }
        }
        if (liftIndex >= 0 && liftIndex < shelf.size() && moveLift.value > 0.02f) top = liftIndex;
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < shelf.size(); i++) {
                if ((pass == 0) == (i == top)) continue;
                // Moving apps: the tile slides from where it was and the lifted one wiggles (only then: no extra work
                // otherwise).
                float dx = i < tileSlide.length ? tileSlide[i].value : 0;
                float angle = i == liftIndex ? wiggle * moveLift.value : 0;
                if (dx == 0 && angle == 0) {
                    drawShelfTile(c, i);
                    continue;
                }
                c.save();
                c.translate(dx, 0);
                c.rotate(angle, tileRects[i].centerX(), tileRects[i].centerY());
                drawShelfTile(c, i);
                c.restore();
            }
        }
    }

    /**
     * App icon like the ones in the tvOS 27 Dock: the brand color edge to edge (a soft gradient) with the logo.
     * Focused: it grows 20% from the center, with a deep shadow (it "rises" toward the viewer) and a subtle glow at the
     * top. No border and no reflection, as Apple does it.
     */
    private void drawShelfTile(Canvas c, int i) {
        HomeApps.Item it = shelf.get(i);
        // Lifted to be moved: a little bigger than focused, with a deeper shadow.
        float l = i == liftIndex ? Math.max(0, moveLift.value) : 0;
        // Only the targeted tile grows (as the catalog/poster rows already do): with every tile's own Spring
        // animating independently, the outgoing and incoming tiles' shadows both need a fresh, differently-sized
        // offscreen blur surface almost every frame, which Skia's GPU scratch cache can't reuse (#memory-management).
        float f = (i == shelfIndex ? tileFocus[i].value : 0), fc = Ui.clamp(f), s = 1 + look.m.shelfGrow * f + LIFT_GROW * l;
        RectF base = tileRects[i];
        float bw = base.width(), bh = base.height(), r0 = TILE_RADIUS * k;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        // Shadow at the already-scaled bounds (cell/r, not a canvas scale): matches drawCard's shape, so repeated
        // frames at the same size let the GPU reuse its offscreen blur surface instead of allocating a new one.
        tmp.set(cell);
        tmp.offset(0, (4 + 14 * f + 6 * l) * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.14f, 0.3f) + tone(0.18f, 0.3f) * fc + 0.14f * Ui.clamp(l)));
        shadows.draw(c, tmp, r, DEEP_BLUR * k, shadowDeep);
        int[] style = TILE_STYLE.get(it.key);
        if (it.logo != null && style != null) {
            if (tileShaders.length != shelf.size() || tileShaderH != bh) {
                tileShaders = new LinearGradient[shelf.size()];
                tileShaderH = bh;
                tileGlow = new LinearGradient(0, -bh / 2, 0, 0, 0x14FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
            }
            if (tileShaders[i] == null) {
                tileShaders[i] = new LinearGradient(0, -bh / 2, 0, bh / 2, style[0], style[1], Shader.TileMode.CLAMP);
            }
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
            tileFill.setShader(tileShaders[i]);
            tileFill.setAlpha(255);
            c.drawRoundRect(tmp, r0, r0, tileFill);
            if (fc > 0.01f) {
                tileFill.setShader(tileGlow);
                tileFill.setAlpha(Math.round(255 * fc));
                c.drawRoundRect(tmp, r0, r0, tileFill);
                tileFill.setAlpha(255);
            }
            tileFill.setShader(null);
            c.restore();
            drawLogo(c, it, cell, s, style[2]);
        } else if (it.logo != null) {
            tileFill.setShader(null);
            tileFill.setColor(0xFF161618);
            c.drawRoundRect(cell, r, r, tileFill);
            drawLogo(c, it, cell, s, 0);
        } else if (it.kind == HomeApps.ALL_APPS && look.allAppsTile(c, cell, r, it.label, fc, dark())) {
            // drawn by the look
        } else {
            if (it.tile != null) roundImage(c, it.tile, cell, r);
        }
        look.focusRing(c, cell, r, fc, dark(), 1);
    }

    /**
     * `image` stretched over `cell`, with corners of radius `r`. Android 12+ clips to the rounded shape; on Android 8 to
     * 11 that anti-aliased clip costs the GPU a mask on every frame of a focus animation (measured on a Mi Box 3: most of
     * a 49 ms frame), so there the image is the shader of a rounded rectangle instead, which looks the same.
     */
    private void roundImage(Canvas c, Bitmap image, RectF cell, float r) {
        roundImage(c, image, cell, r, 0);
    }

    /** As above; the picture is cropped to the shape of `cell` (never stretched), `shift` moving the crop sideways (a share of its width). */
    private void roundImage(Canvas c, Bitmap image, RectF cell, float r, float shift) {
        if (!Ui.LEGACY) {
            int iw = image.getWidth(), ih = image.getHeight();
            float want = cell.width() / cell.height(), sw = iw, sh = ih;
            if ((float) iw / ih > want) sw = ih * want;
            else sh = iw / want;
            float sx = Math.max(0, Math.min(iw - sw, (iw - sw) / 2 + shift * sw)), sy = (ih - sh) / 2;
            imageSrc.set(Math.round(sx), Math.round(sy), Math.round(sx + sw), Math.round(sy + sh));
            clip.reset();
            clip.addRoundRect(cell, r, r, Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            c.drawBitmap(image, imageSrc, cell, bitmapPaint);
            c.restore();
            return;
        }
        BitmapShader shader = imageShaders.get(image);
        if (shader == null) {
            shader = new BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            imageShaders.put(image, shader);
        }
        imageMatrix.setScale(cell.width() / image.getWidth(), cell.height() / image.getHeight());
        imageMatrix.postTranslate(cell.left, cell.top);
        shader.setLocalMatrix(imageMatrix);
        imageFill.setShader(shader);
        c.drawRoundRect(cell, r, r, imageFill);
        imageFill.setShader(null);
    }

    /**
     * Logo at its "optical" size (all with the same amount of ink, plus each one's own adjustment, never past 80% × 62%
     * of the tile), centered and already filtered down to the focused tile size and to the resting one: focused or at
     * rest it is drawn pixel for pixel, without resampling (on a 720p UI, shrinking at draw time made it look jagged).
     */
    private void drawLogo(Canvas c, HomeApps.Item it, RectF box, float s, int mode) {
        boolean rest = s < 1 + look.m.shelfGrow / 2;
        Bitmap logo = crispLogo(it, mode == 2 && it.logoDark != null, rest);
        if (logo == null) return;
        float scale = rest ? s : s / (1 + look.m.shelfGrow);
        float dw = logo.getWidth() * scale, dh = logo.getHeight() * scale;
        float left = box.centerX() - dw / 2, top = box.centerY() - dh / 2;
        if (rest ? Math.abs(scale - 1) < 0.002f : scale > 0.998f) {
            left = Math.round(left);
            top = Math.round(top);
        }
        tmp.set(left, top, left + dw, top + dh);
        bitmapPaint.setColorFilter(mode == 1 ? whiteLogo : null);
        c.drawBitmap(logo, null, tmp, bitmapPaint);
        bitmapPaint.setColorFilter(null);
    }

    private Bitmap crispLogo(HomeApps.Item it, boolean dark, boolean rest) {
        Bitmap src = dark ? it.logoDark : it.logo;
        if (tileRects.length == 0) return src;
        String key = it.key + "@" + System.identityHashCode(src) + "@" + Math.round(tileRects[0].width()) + (rest ? "@rest" : "");
        Bitmap cached = crispLogos.get(key);
        if (cached != null) return cached;
        float bw = tileRects[0].width() * (1 + look.m.shelfGrow), bh = tileRects[0].height() * (1 + look.m.shelfGrow);
        float lw = src.getWidth(), lh = src.getHeight();
        float scale = (float) Math.sqrt(bw * bh * 0.085f / (lw * lh * Math.max(0.08f, it.logoInk))) * it.logoScale;
        scale = Math.min(scale, Math.min(bw * 0.76f / lw, bh * 0.58f / lh));
        if (rest) scale /= 1 + look.m.shelfGrow;
        Bitmap out = downscale(src, Math.round(lw * scale), Math.round(lh * scale));
        crispLogos.put(key, out);
        return out;
    }

    /** Halves the size while it can (a 2×2 average, no aliasing), then finishes at the exact size. */
    private static Bitmap downscale(Bitmap src, int w, int h) {
        w = Math.max(1, w);
        h = Math.max(1, h);
        Bitmap b = src;
        while (b.getWidth() / 2 >= w && b.getHeight() / 2 >= h) {
            Bitmap half = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
            if (b != src) b.recycle();
            b = half;
        }
        Bitmap out = Bitmap.createScaledBitmap(b, w, h, true);
        if (b != src && b != out) b.recycle();
        return out;
    }

    // MARK: - Bottom drawer

    private void layoutDrawer() {
        float left = MARGIN * k, right = CONTENT_R * k;
        float cardsTop = CARDS_TOP * k;
        quotesCard.set(left, cardsTop, right, cardsTop + CARDS_H * k);     // the weather is at the top of the home screen now
        drawerDirty = true;
    }

    /** How long the catalog posters stay decoded after the drawer closes: a quick close and reopen stays instant. */
    private static final long POSTERS_KEEP_MS = 10_000;
    private final Runnable dropPosters = this::dropPosters;

    /** The drawer (and the browse screen in it) has been closed a while: its posters go; they're read back from disk. */
    private void dropPosters() {
        if (drawerOpen) return;
        catalogs.trim();
        AppLog.w("catalog posters dropped (drawer closed)");
    }

    /** The drawer closed: its posters are dropped in a few seconds, unless it opens again first. */
    private void catalogsClosed() {
        handler.removeCallbacks(dropPosters);
        handler.postDelayed(dropPosters, POSTERS_KEEP_MS);
    }

    private void openDrawer() {
        handler.removeCallbacks(dropPosters);
        catalogs.refreshIfStale();      // the poster rows under the cards (a request each, once in a few hours)
        syncRows();
        catalogPage.sync();
        // The drawer opens on the poster rows (Trakt's, else TMDB's catalogs). Neither has loaded yet: nothing to open
        // onto, so stay put; the next press (or the fetch finishing) tries again.
        if (catRows.isEmpty() && catalogPage.isEmpty()) return;
        drawerOpen = true;
        drawer.target = 1;
        drawerDirty = true;
        if (!enterCatalog()) enterBrowse();
    }

    private void closeDrawer() {
        drawerOpen = false;
        catalogsClosed();
        drawer.target = 0;
        catIn.target = 0;
        catRow.target = 0;
        zone = Z_SHELF;
    }

    // MARK: - Spotlight's own paging (the shelf doesn't live on its hero screen: Down pages straight to the
    // drawer/catalog screen in one press, landing on the shelf as that page's first row; Cards mode needs two presses
    // — Down to the shelf, Down again to open the drawer — and keeps its own shelf position/openDrawer() untouched).

    /** Down from the poster row: one page, not two — the shelf rises as this page's first row *and* the catalog rows
     * (Continue Watching etc.) come in right below it in the same motion, not a second Down-press away. Focus lands
     * on the shelf (`zone = Z_SHELF`); a further Down moves focus into the already-visible catalog rows via the
     * ordinary `Z_SHELF` → `openDrawer()` → `enterCatalog()` path (harmless to call again — `catIn` is already at 1,
     * so that's just a focus change, not a second "opening" animation). */
    private void openSpotlightPage() {
        handler.removeCallbacks(dropPosters);
        catalogs.refreshIfStale();
        syncRows();
        catalogPage.sync();
        drawerOpen = true;
        drawer.target = 1;
        zone = Z_SHELF;
        if (!shelf.isEmpty()) shelfIndex = nearest(tileRects, tileRects.length, spotlightFocusedRect.centerX());
        if (!catRows.isEmpty()) {
            catRowIndex = 0;
            catIn.target = 1;
            catRow.target = 0;
        }
    }

    /** Up from the shelf (or Back, from anywhere on the drawer/catalog page): closes that page and returns straight
     * to the poster row, undoing what `openSpotlightPage()` started. */
    private void closeSpotlightPage() {
        drawerOpen = false;
        catalogsClosed();
        drawer.target = 0;
        catIn.target = 0;
        catRow.target = 0;
        zone = Z_CARDS;
        cardIndex = 0;
    }

    // MARK: - The input popover (HDMI input, AV input): a choice among the inputs of one kind

    /** OK on an input button: one input opens at once; several open the popover, with the first one that has a device on it. */
    private void openInputs(HomeApps.Item group, RectF from) {
        List<HomeApps.Item> list = HomeApps.inputGroup(getContext().getApplicationContext(), group.key);
        if (list.isEmpty()) return;
        if (list.size() == 1) {
            launch(list.get(0), from);
            return;
        }
        popActions = null;
        popItems = list;
        popTitle = group.label;
        popIndex = 0;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).connected) {
                popIndex = i;
                break;
            }
        }
        showPop(null);
    }

    /** Opens the popover with `popItems` and `popIndex` set; beside `anchor` (a poster), or at the top right when null. */
    private void showPop(RectF anchor) {
        if (popFocus.length != popItems.size()) {
            popFocus = new Spring[popItems.size()];
            for (int i = 0; i < popFocus.length; i++) popFocus[i] = new Spring(0.3f, 0.82f, 0);
        }
        aim(popFocus, popIndex, true);
        layoutPop(anchor);
        popOpen = true;
        popIn.target = 1;
        popLayer.setVisibility(VISIBLE);
        popDirty = statusDirty = true;
        wake();
        spoken.focusMoved();
    }

    // MARK: - A poster's menu (hold OK on Continue Watching, Shows you watch, Watchlist)

    private boolean tileMenuReady() {
        return !sheetOpen && zone == Z_CATALOG && !catRows.isEmpty() && catRowIndex < catRows.size()
                && catCol[catRowIndex] < catRows.get(catRowIndex).items.size();
    }

    /** One action for the focused poster: Remove where Trakt takes it off the list, else Hide (only this launcher forgets it). */
    private void openTileMenu() {
        handler.removeCallbacks(holdStart);
        holdArmed = false;
        if (popOpen || opening != null || !tileMenuReady()) return;
        okHeld = true;      // the release of this press opens nothing
        final Catalogs.Row row = catRows.get(catRowIndex);
        final Catalogs.Item it = row.items.get(catCol[catRowIndex]);
        final boolean remove = catalogs.actionFor(row.key, it) == Catalogs.REMOVE;
        final String rowTitle = row.title;
        final String label = !remove ? Ui.tr("Hide from this row")
                : Trakt.WATCHLIST.equals(row.key) ? Ui.tr("Remove from Watchlist") : Ui.tr("Remove");
        final List<Runnable> actions = new ArrayList<>();
        popItems = new ArrayList<>();
        popItems.add(HomeApps.action(HomeApps.APP, "tile", label, null));
        actions.add(() -> catalogs.act(row.key, it, r -> {
            if (r == Trakt.FAILED) showToast(Ui.tr("Couldn't remove it. Try again."));
            else if (r == Catalogs.HIDDEN) showToast(Ui.trf("Hidden from {row}: {title}", "row", rowTitle, "title", it.title));
            else showToast(Ui.trf("Removed from {row}: {title}", "row", rowTitle, "title", it.title));
        }));
        if (catalogs.canMark(row.key, it)) {
            popItems.add(HomeApps.action(HomeApps.APP, "mark", Ui.tr("Mark as watched"), null));
            actions.add(() -> catalogs.mark(row.key, it, r -> {
                if (r == Trakt.DONE) showToast(Ui.trf("Marked as watched: {title}", "title", it.title));
                else showToast(Ui.tr("Couldn't mark it as watched. Try again."));
            }));
        }
        popActions = actions.toArray(new Runnable[0]);
        popTitle = it.title;
        popIndex = 0;
        posterRect(catRowIndex, catCol[catRowIndex], tileAnchor);
        click.open();
        showPop(tileAnchor);
    }

    private void closePop(boolean snap) {
        if (!popOpen && popIn.value <= 0.001f) return;
        popOpen = false;
        popIn.target = 0;
        if (snap) {
            popIn.snap(0);
            popLayer.setVisibility(INVISIBLE);
        }
        popDirty = statusDirty = true;
        wake();
        spoken.focusMoved();
    }

    private void movePop(int dy) {
        if (dy == 0) return;
        int next = Math.max(0, Math.min(popItems.size() - 1, popIndex + dy));
        if (next == popIndex) return;
        popIndex = next;
        aim(popFocus, popIndex, false);
        popDirty = true;
        click.move();
        spoken.focusMoved();
    }

    private void choosePop() {
        if (popIndex >= popItems.size()) {
            closePop(false);
            return;
        }
        if (popActions != null) {
            Runnable action = popActions[popIndex];
            closePop(false);
            click.open();
            action.run();
            return;
        }
        HomeApps.Item it = popItems.get(popIndex);
        popRowRect(popIndex, popRow);
        final RectF from = new RectF(popRow);
        closePop(false);
        click.open();
        launch(it, from);
    }

    /** The panel at the top right (the inputs), or beside `anchor`: to its right, to its left when that doesn't fit. */
    private void layoutPop(RectF anchor) {
        float pw = POP_W * k, ph = (POP_TITLE_H + POP_PAD) * k + popItems.size() * POP_ROW_H * k;
        float left, top;
        if (anchor == null) {
            left = CONTENT_R * k - pw;
            top = POP_TOP * k;
        } else {
            float gap = 24 * k, edge = 24 * k;
            left = anchor.right + gap;
            if (left + pw > w - edge) left = anchor.left - gap - pw;
            left = Math.max(edge, left);
            top = Math.max(edge, Math.min(anchor.top, h - ph - edge));
        }
        popPanel.set(left, top, left + pw, top + ph);
        placePop();
    }

    /** The popover's layer is just big enough for the panel and its shadow: it follows the panel. */
    private void placePop() {
        int x = Math.round(popPanel.left - 40 * k), y = Math.round(popPanel.top - 38 * k);
        popLayer.layout(x, y, x + popLayer.getMeasuredWidth(), y + popLayer.getMeasuredHeight());
    }

    /** Row `i` of the popover in screen coordinates. */
    private void popRowRect(int i, RectF out) {
        float y = popPanel.top + (POP_TITLE_H + POP_PAD / 2f) * k + i * POP_ROW_H * k;
        out.set(popPanel.left + POP_PAD * k, y, popPanel.right - POP_PAD * k, y + (POP_ROW_H - 6) * k);
    }

    /** Glass panel with the kind's name and a row for each input: the focused one white with dark text, one with no device dimmed. */
    private void drawPopover(Canvas c) {
        float a = Ui.clamp(popIn.value);
        if (a <= 0.01f || popItems.isEmpty()) return;
        applyTone();
        float s = 0.95f + 0.05f * a;
        int save = c.saveLayerAlpha(popPanel.left - 60 * k, popPanel.top - 40 * k, popPanel.right + 60 * k, popPanel.bottom + 80 * k,
                Math.round(255 * a));
        c.scale(s, s, popPanel.right - 60 * k, popPanel.top);
        drawerGlass(c, popPanel, 34 * k, 0.8f, 0x22000000, 14 * k);
        rowTitle.setColor(look.color(Look.DRAWER_ROW, dark()));
        fitPaint.set(rowTitle);
        c.drawText(TextUtils.ellipsize(popTitle, fitPaint, popPanel.width() - (2 * POP_PAD + 28) * k, TextUtils.TruncateAt.END).toString(),
                popPanel.left + (POP_PAD + 14) * k, popPanel.top + 44 * k, rowTitle);
        for (int i = 0; i < popItems.size() && i < popFocus.length; i++) {
            HomeApps.Item it = popItems.get(i);
            float f = Ui.clamp(popFocus[i].value), r = 24 * k;
            popRowRect(i, popRow);
            if (f > 0.01f) {
                tmp.set(popRow);
                tmp.offset(0, 8 * k * f);
                shadow.setColor(Ui.alpha(0xFF000000, 0.35f * f));
                shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
                fill.setShader(null);
                fill.setColor(Ui.alpha(WHITE, f));
                c.drawRoundRect(popRow, r, r, fill);
            }
            int ink = blend(look.color(it.connected ? Look.ITEM_ON : Look.ITEM_OFF, dark()), INK, f);
            if (it.symbol != null) Symbols.draw(getContext(), c, it.symbol, popRow.left + 44 * k, popRow.centerY(), 32 * k, ink);
            float textX = it.symbol != null ? 88 : 28;     // a poster's menu has no icons
            popLabel.setColor(ink);
            fitPaint.set(popLabel);
            String label = TextUtils.ellipsize(it.label, fitPaint, popRow.width() - (textX + 12) * k, TextUtils.TruncateAt.END).toString();
            c.drawText(label, popRow.left + textX * k, popRow.centerY() + Ui.halfCap(popLabel), popLabel);
        }
        c.restoreToCount(save);
    }

    /** Color for the drawer's text and symbols: dark by day, white at night. */
    private int drawerInk(float alpha) {
        return Ui.alpha(look.color(Look.INK_PURE, dark()), alpha);
    }

    /** Glass for a drawer card or button: white by day, smoked at night (both during the transition). */
    private void drawerGlass(Canvas c, RectF r, float radius, float lightTint, int shadowColor, float drop) {
        look.drawerGlass(c, r, radius, lightTint, shadowColor, drop, dark());
    }

    private void drawerCard(Canvas c, RectF r) {
        drawerGlass(c, r, 30 * k, 0.72f, 0x1A000000, 10 * k);
    }

    private void drawDrawer(Canvas c) {
        rowTitle.setColor(look.color(Look.DRAWER_ROW, dark()));
        cardTitle.setColor(look.color(Look.DRAWER_ROW, dark()));
        cardText.setColor(drawerInk(0.95f));
        cardDim.setColor(look.color(Look.DRAWER_DIM, dark()));
        cardDimRight.setColor(look.color(Look.FAINT, dark()));
        bigTemp.setColor(drawerInk(1));
        hourPaint.setColor(look.color(Look.DRAWER_DIM, dark()));
        hourTemp.setColor(drawerInk(0.95f));
        pricePaint.setColor(drawerInk(0.95f));
        if (Prefs.quotes() && HomeData.active().length > 0) drawQuotesCard(c, quotesCard);
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
        // Only the group whose focus changed is redrawn (moving along the shelf doesn't redraw the picks or the buttons).
        boolean tilesChanged = aim(tileFocus, !sheetOpen && zone == Z_SHELF ? shelfIndex : -1, snap);
        boolean topChanged = aim(topFocus, !sheetOpen && zone == Z_TOP ? topIndex : -1, snap);
        boolean heroChanged = aim(cardFocus, !sheetOpen && zone == Z_CARDS ? cardIndex : -1, snap) | topChanged;
        tilesDirty |= snap || tilesChanged;
        statusDirty |= snap || topChanged;
        heroDirty |= snap || heroChanged;
        drawerDirty |= snap;
        spoken.focusMoved();
    }

    /** Aims a group's springs (only `focused` at 1); returns true if any target changed. */
    private static boolean aim(Spring[] springs, int focused, boolean snap) {
        boolean changed = false;
        for (int i = 0; i < springs.length; i++) {
            float target = i == focused ? 1 : 0;
            changed |= springs[i].target != target;
            springs[i].target = target;
            if (snap) springs[i].snap(target);
        }
        return changed;
    }

    // MARK: - "All Apps" sheet

    private void setOthers(List<HomeApps.Item> list) {
        others = list;
        gridFocus = new Spring[list.size()];
        for (int i = 0; i < gridFocus.length; i++) gridFocus[i] = new Spring(0.3f, 0.82f, 0);
        gridLabels = null;
        gridIndex = Math.min(gridIndex, Math.max(0, list.size() - 1));
        retargetGrid(true);
        gridDirty = true;
    }

    private void openSheet() {
        sheetOpen = true;
        gridIndex = 0;
        scroll.snap(0);
        retargetGrid(true);
        retarget(false);
        sheet.target = 1;
        gridDirty = true;
    }

    private void closeSheet() {
        sheetOpen = false;
        sheet.target = 0;
        retarget(false);
        if (shelfEdited) {     // the grid was kept as it was while open: apps that went to the shelf leave it now
            shelfEdited = false;
            reload();
        }
    }

    private void retargetGrid(boolean snap) {
        for (int i = 0; i < gridFocus.length; i++) {
            gridFocus[i].target = sheetOpen && i == gridIndex ? 1 : 0;
            if (snap) gridFocus[i].snap(gridFocus[i].target);
        }
        int rows = Math.max(1, (others.size() + COLUMNS - 1) / COLUMNS);
        int visible = Math.min(rows, VISIBLE_ROWS);
        int r = gridIndex / COLUMNS;
        float target = scroll.target;
        if (r < target) target = r;
        else if (r > target + visible - 1) target = r - visible + 1;
        scroll.target = Math.max(0, Math.min(rows - visible, target));
        if (snap) scroll.snap(scroll.target);
        spoken.focusMoved();
    }

    private void drawSheet(Canvas c) {
        tmp.set(0, 0, w, h);
        look.sheetBackground(c, tmp, dark());
        sheetTitle.setColor(look.sheetTitleInk(dark()));
        float shift = scroll.value * GRID_ROW * k;
        c.drawText(Ui.tr("All Apps"), w / 2f, 124 * k - shift, sheetTitle);
        int n = others.size();
        if (gridLabels == null || gridLabels.length != n) {
            gridLabels = new String[n];
            for (int i = 0; i < n; i++) {
                gridLabels[i] = TextUtils.ellipsize(others.get(i).label, gridLabel, (GRID_W - 10) * k, TextUtils.TruncateAt.END).toString();
            }
        }
        float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
        float left = (1920 - gridWidth) / 2 * k;
        float gridTop = GRID_TOP * k;
        for (int i = 0; i < n; i++) {
            int r = i / COLUMNS, col = i % COLUMNS;
            float y = gridTop + r * GRID_ROW * k - shift;
            if (y > h || y + GRID_ROW * k < 0) continue;
            float x = left + col * (GRID_W + GRID_GAP) * k;
            gridCell.set(x, y, x + GRID_W * k, y + GRID_H * k);
            // Only the targeted tile grows (#memory-management, same as the shelf): doesn't explain this screen's
            // own GPU growth (measured separately, cause unidentified), but still the correct behavior on its own
            // merits — matches every other fixed section, and costs nothing since it's still provably no worse.
            float f = i == gridIndex ? gridFocus[i].value : 0;
            drawCard(c, others.get(i).tile, gridCell, GRID_RADIUS * k, f, 0.08f);
            if (sheetOpen && i == gridIndex) focusedCell.set(cell);
            gridLabel.setColor(look.gridLabelInk(f, dark()));
            c.drawText(gridLabels[i], gridCell.centerX(), gridCell.bottom + (40 + 6 * f) * k, gridLabel);
        }
    }

    /** 16:9 sheet card. Focused (the shelf's subtle focus): it grows, with a shadow, a soft sheen and a thin border. */
    private void drawCard(Canvas c, Bitmap image, RectF base, float radius, float f, float grow) {
        float s = 1 + grow * f;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = radius * s;
        float fc = Ui.clamp(f);
        if (f > 0.01f) {
            tmp.set(cell);
            tmp.offset(0, 16 * k * f);
            shadow.setColor(Ui.alpha(0xFF000000, 0.55f * fc));
            shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
        }
        if (image != null) roundImage(c, image, cell, r);
        if (f > 0.01f) {
            float bh = base.height();
            if (cardSheen == null || cardSheenH != bh) {
                cardSheen = new LinearGradient(0, -bh / 2, 0, 0, 0x1AFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
                cardSheenH = bh;
            }
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-base.width() / 2, -bh / 2, base.width() / 2, bh / 2);
            sheen.setShader(cardSheen);
            sheen.setAlpha(Math.round(255 * fc));
            c.drawRoundRect(tmp, radius, radius, sheen);
            sheen.setAlpha(255);
            c.restore();
        }
        if (fc < 0.99f) {
            veil.setColor(Ui.alpha(0xFF000000, 0.08f * (1 - fc)));
            c.drawRoundRect(cell, r, r, veil);
        }
        if (!look.focusRing(c, cell, r, fc, dark(), 1)) {
            border.setColor(0x14FFFFFF);
            c.drawRoundRect(cell, r, r, border);
            if (f > 0.01f) {
                rim.setShader(null);
                rim.setColor(Ui.alpha(0xFFFFFFFF, 0.4f * fc));
                rim.setStrokeWidth(1.5f * k);
                c.drawRoundRect(cell, r, r, rim);
                rim.setStrokeWidth(2.5f * k);
            }
        }
    }

    // MARK: - Poster rows (under the drawer's weather and quotes; Down from the drawer's buttons)

    private static final int CAT_VISIBLE = 8;
    private static final float CAT_ROW_FOOT = 400, CAT_BOTTOM = 1040;      // a row ends 400 below its top; the screen 40 short of 1080

    /** The quotes card is the only card left in the drawer; without it the rows start where the cards would, and higher up. */
    private boolean cardsOn() {
        return Prefs.quotes() && HomeData.active().length > 0;
    }

    private float catY0() {
        return cardsOn() ? CAT_Y0 : CARDS_TOP;
    }

    private float catLift() {
        float lift = cardsOn() ? CAT_LIFT : CARDS_TOP - 140;
        // Spotlight's shelf is docked just above row 0 (see layoutShelf): row 0 needs to rest a bit lower than Cards'
        // does, so there's room for the shelf's full glass pill (not just its tiles) between it and the screen's top edge.
        // A shelf with Cards' full padding is taller by 2 * (SHELF_PAD - 10): row 0 sits that much lower again.
        return Prefs.spotlightOn() ? lift - SPOTLIGHT_SHELF_GAP - (look.m.shelfFill ? 0 : 2 * (SHELF_PAD - 10)) : lift;
    }

    @Override public void onCatalogs() {
        wake();
        syncRows();
        catalogPage.sync();
        catDirty = true;
        catalogPage.invalidate();
    }

    @Override public void onDetails() {
        catalogPage.onDetails();
    }

    /** Takes the catalog's current rows (a new list after a fetch), keeping the focus on the same title when it can. */
    private void syncRows() {
        List<Catalogs.Row> rows = catalogs.rows();
        if (rows == catRows) return;
        List<Catalogs.Row> old = catRows;
        catRows = rows;
        catShift = new Spring[rows.size()];
        for (int i = 0; i < catShift.length; i++) catShift[i] = new Spring(0.34f, 1f, 0);
        int focusedRow = PosterRow.sameRow(old, catRowIndex, rows);
        catCol = PosterRow.sameTitles(old, catCol, rows);
        int[] cols = catCol;
        for (int i = 0; i < cols.length; i++) catShift[i].snap(Math.max(0, cols[i] - CAT_VISIBLE + 1));
        catRowIndex = focusedRow;
        if (zone == Z_CATALOG && rows.isEmpty()) leaveCatalog();
        else if (zone == Z_CATALOG) catRow.target = catScrollTarget();
    }

    /**
     * How far the rows have scrolled (in rows) with the focus on row `catRowIndex`: the focused row rises with each press,
     * but never past the point where the last row's foot reaches the bottom of the screen, so a few rows don't leave it empty.
     */
    private float catScrollTarget() {
        float firstTop = catY0() - catLift();
        float lastFoot = firstTop + (catRows.size() - 1) * CAT_PITCH + CAT_ROW_FOOT;
        float most = Math.max(0, lastFoot - CAT_BOTTOM) / CAT_PITCH;
        return Math.min(catRowIndex, most);
    }

    private boolean enterCatalog() {
        syncRows();
        if (catRows.isEmpty()) return false;
        zone = Z_CATALOG;
        catRowIndex = 0;
        catIn.target = 1;
        catRow.target = 0;
        popCatalogFocus();
        return true;
    }

    private void leaveCatalog() {
        catIn.target = 0;
        catRow.target = 0;
        catDirty = true;
        closeDrawer();       // nothing in the drawer to land on: back to the shelf
        wake();
    }

    private void popCatalogFocus() {
        catFocus.snap(0);
        catFocus.target = 1;
        catDirty = true;
    }

    /** Arrows inside the rows: left and right along a row, up and down between rows (up from the first goes back to the drawer). */
    private boolean moveCatalog(int dx, int dy) {
        if (catRows.isEmpty()) return false;
        int r = catRowIndex;
        if (dx != 0) {
            int next = Math.max(0, Math.min(catRows.get(r).items.size() - 1, catCol[r] + dx));
            if (next == catCol[r]) return false;
            catCol[r] = next;
            float first = catShift[r].target;
            if (next < first) first = next;
            else if (next > first + CAT_VISIBLE - 1) first = next - CAT_VISIBLE + 1;
            catShift[r].target = first;
        } else if (dy < 0) {
            if (r == 0) {
                leaveCatalog();
                return true;
            }
            catRowIndex--;
            catRow.target = catScrollTarget();
        } else {
            if (r >= catRows.size() - 1) return enterBrowse();      // past the last row: TMDB's catalogs
            catRowIndex++;
            catRow.target = catScrollTarget();
        }
        popCatalogFocus();
        return true;
    }

    /** Where poster `i` of row `r` is on screen now. */
    private void posterRect(int r, int i, RectF out) {
        float y0 = (catY0() + r * CAT_PITCH - catIn.value * catLift() - catRow.value * CAT_PITCH) * k;
        float x = MARGIN * k + (i - catShift[r].value) * (CAT_POSTER_W + CAT_GAP) * k;
        out.set(x, y0 + 50 * k, x + CAT_POSTER_W * k, y0 + (50 + CAT_POSTER_H) * k);
    }

    private void drawCatalog(Canvas c) {
        if (catRows.isEmpty() || catIn.value < 0.002f || lCatTitle == null) return;
        lCatTitle.setColor(look.color(Look.TITLE, dark()));
        lCatLabel.setColor(look.color(Look.TITLE, dark()));
        lCardMeta.setColor(look.color(Look.META, dark()));
        for (int r = 0; r < catRows.size(); r++) {
            float y0 = (catY0() + r * CAT_PITCH - catIn.value * catLift() - catRow.value * CAT_PITCH) * k;
            if (y0 > h || y0 + CAT_PITCH * k < 0) continue;
            Catalogs.Row row = catRows.get(r);
            c.drawText(row.title, MARGIN * k + 4 * k, y0 + 30 * k, lCatTitle);
            int first = Math.max(0, (int) Math.floor(catShift[r].value)), last = Math.min(row.items.size() - 1, first + CAT_VISIBLE + 1);
            for (int i = first; i <= last; i++) {
                posterRect(r, i, cardRect);
                if (cardRect.right < 0 || cardRect.left > w) continue;
                Catalogs.Item it = row.items.get(i);
                drawPoster(c, cardRect, it, zone == Z_CATALOG && r == catRowIndex && i == catCol[r] ? Ui.clamp(catFocus.value) : 0, y0, true);
            }
        }
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
    private String pickService(Recs.Pick p) {
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
        catDirty = heroDirty = true;
        catalogPage.invalidate();
        wake();
    }

    /** The service's round icon in the corner of a card or poster (`r`, as drawn now). */
    private void drawBadge(Canvas c, RectF r, String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        int px = Math.round(34 * k);
        Bitmap b = ServiceBadge.get(getContext(), pkg, px, badgesReady);
        if (b != null) c.drawBitmap(b, r.left + 10 * k, r.top + 10 * k, null);
    }

    /** One poster of a row (Trakt's under the drawer, TMDB's on the browse screen) with its badge (`badge`), bar, loader and captions; `y0` is the row's top. */
    void drawPoster(Canvas c, RectF cardRect, Catalogs.Item it, float f, float y0, boolean badge) {
        Bitmap poster = catalogs.poster(it, Math.round(CAT_POSTER_W * k * 1.25f));
        if (poster == null) {
            fill.setShader(null);
            fill.setColor(look.color(Look.PLACEHOLDER, dark()));
            c.drawRoundRect(cardRect, 14 * k, 14 * k, fill);
        }
        drawCard(c, poster, cardRect, 14 * k, f, 0.1f);
        if (badge && poster != null) drawBadge(c, cell, catalogService(it, f > 0.5f));
        if (it.progress > 0 && poster != null) {      // part-way through: a thin bar along the poster's foot (drawCard left its size in `cell`)
            barRect.set(cell.left + 12 * k, cell.bottom - 16 * k, cell.right - 12 * k, cell.bottom - 10 * k);
            fill.setShader(null);
            fill.setColor(0x73FFFFFF);
            c.drawRoundRect(barRect, 3 * k, 3 * k, fill);
            barRect.right = barRect.left + barRect.width() * Math.min(1f, it.progress);
            fill.setColor(0xFFFFFFFF);
            c.drawRoundRect(barRect, 3 * k, 3 * k, fill);
        }
        if (it == openingItem && SystemClock.uptimeMillis() - openingAt >= OPEN_LOADER_MS) {
            // Waiting for where it streams: the poster dims a little and three dots pulse over it.
            fill.setShader(null);
            fill.setColor(0x80000000);
            c.drawRoundRect(cell, 14 * k, 14 * k, fill);
            fill.setColor(0xFFFFFFFF);
            Ui.dots(c, cell.centerX(), cell.centerY(), 7 * k, (SystemClock.uptimeMillis() - openingAt) / 1000f, fill);
        }
        fitPaint.set(lCatLabel);
        c.drawText(TextUtils.ellipsize(it.title, fitPaint, cardRect.width() + 16 * k, TextUtils.TruncateAt.END).toString(),
                cardRect.left, y0 + (50 + CAT_POSTER_H + 40) * k, lCatLabel);
        String meta = it.sub != null ? it.sub : it.stars;
        if (meta != null) c.drawText(meta, cardRect.left, y0 + (50 + CAT_POSTER_H + 72) * k, lCardMeta);
    }

    // MARK: - Page 4, TMDB's catalogs (HomeCatalogPage): coming in and going out, and what the page asks of the home screen

    /** A badge picture is ready: the layers that show badges are drawn again (once; the picture then stays). */
    private final Runnable markBadgesRun = this::markBadges;
    final Runnable badgesReady = () -> post(markBadgesRun);

    /** Down from the last of Trakt's rows (or from the shelf when there are none): the catalogs. */
    private boolean enterBrowse() {
        catalogPage.sync();
        if (catalogPage.isEmpty()) return false;
        zone = Z_BROWSE;
        browseIn.target = 1;
        catalogPage.enter();
        return true;
    }

    /** Up from the first catalog (or the catalogs went away): back to Trakt's rows, else the shelf. */
    void leaveBrowse() {
        catalogPage.leave();
        browseIn.target = 0;
        if (!catRows.isEmpty()) zone = Z_CATALOG;
        else closeDrawer();
        wake();
    }

    /** The focus is on page 4. */
    boolean onCatalogPage() {
        return zone == Z_BROWSE;
    }

    /** Nothing else is going on (All Apps, an open waiting for its answer, OK held): a trailer may start by itself. */
    boolean trailerMayStart() {
        return !sheetOpen && opening == null && !okDown;
    }

    /** The deep shadow under a card, `opacity` of black. */
    void dropShadow(Canvas c, RectF r, float radius, float opacity) {
        shadowDeep.setColor(Ui.alpha(0xFF000000, opacity));
        shadows.draw(c, r, radius, DEEP_BLUR * k, shadowDeep);
    }

    /** As `roundImage`, at `alpha` (0 to 255). */
    void roundImageAlpha(Canvas c, Bitmap image, RectF cell, float r, int alpha) {
        bitmapPaint.setAlpha(alpha);
        imageFill.setAlpha(alpha);
        roundImage(c, image, cell, r);
        bitmapPaint.setAlpha(255);
        imageFill.setAlpha(255);
    }

    /** The colors `drawPoster`'s captions take in this look, day or night. */
    void posterColors() {
        lCatLabel.setColor(look.color(Look.TITLE, dark()));
        lCardMeta.setColor(look.color(Look.META, dark()));
    }

    // MARK: - Toasts

    void showToast(String text) {
        toasts.add(text);
        announceForAccessibility(text);
        wake();
    }

    /** Advances the toast queue; returns true while a toast fades in or out (animation). */
    private boolean tickToast(long up) {
        if (toast != null && up - toastAt > TOAST_MS) {
            toast = null;
            toastLayer.invalidate();
        }
        if (toast == null && toastPaint != null) {
            String next = toasts.poll();
            if (next != null) {
                toast = layout(next);
                toastAt = up;
            }
        }
        if (toast == null) return false;
        long age = up - toastAt;
        boolean fading = age < 350 || age > TOAST_MS - 450;
        if (fading) toastLayer.invalidate();
        return fading;
    }

    private void drawToast(Canvas c) {
        if (toast == null) return;
        float age = (SystemClock.uptimeMillis() - toastAt) / 1000f;
        float a = Math.min(Ui.clamp(age / 0.3f), Ui.clamp((TOAST_MS / 1000f - age) / 0.4f));
        if (a <= 0) return;
        float padX = 28 * k, padY = 18 * k, icon = 26 * k, iconW = Symbols.width(getContext(), "bell", icon);
        float tw = toast.getWidth() + iconW + 16 * k, th = toast.getHeight();
        float toastTop = 64 * k - (1 - a) * 16 * k;
        tmp.set((w - tw) / 2f - padX, toastTop, (w + tw) / 2f + padX, toastTop + th + 2 * padY);
        int save = c.saveLayerAlpha(tmp.left - 4, tmp.top - 4, tmp.right + 4, tmp.bottom + 4, Math.round(255 * a));
        float radius = Math.min(tmp.height() / 2, 32 * k);
        if (!look.toast(c, tmp, radius, dark())) scene.glass(c, tmp, radius, SMOKE, 0.55f, 0, 0, 0.02f, 1);
        toastPaint.setColor(look.toastInk(dark()));
        Symbols.draw(getContext(), c, "bell", tmp.left + padX + iconW / 2, tmp.centerY(), icon, toastPaint.getColor());
        c.translate(tmp.left + padX + iconW + 16 * k, tmp.top + padY);
        toast.draw(c);
        c.restoreToCount(save);
    }

    private StaticLayout layout(String text) {
        int max = Math.round(720 * k);
        int width = (int) Math.min(max, Math.ceil(Layout.getDesiredWidth(text, toastPaint)));
        return StaticLayout.Builder.obtain(text, 0, text.length(), toastPaint, Math.max(1, width))
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
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
            final List<HomeApps.Item> top = apps ? HomeApps.topButtons(c) : null;   // they change with Prefs too
            handler.post(() -> {
                if (top != null) setLightButtons(top);
                if (s != null) setShelf(s);
                if (o != null && !sheetOpen) setOthers(o);
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
        if (reordering) return reorderKey(e, down);
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
                        holdArmed = sheetOpen ? canToggleShelf(gridIndex) : zone == Z_BROWSE || tileMenuReady() || canReorder();
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
                if (zone != Z_BROWSE || sheetOpen) return false;
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
        if (popOpen) {
            closePop(false);
        } else if (sheetOpen) {
            closeSheet();
        } else if (catalogPage.trailerOn()) {      // Back stops the trailer first
            catalogPage.stopTrailer();
        } else if (Prefs.spotlightOn() && drawer.value > 0.001f
                && (zone == Z_CATALOG || zone == Z_DRAWER || zone == WIDGETS || zone == Z_SHELF)) {
            // Spotlight's drawer/catalog page is one unit (opened and closed together, unlike Cards' shelf, which
            // stays visible either way) — Back from anywhere on it closes the whole page in one press, straight back
            // to the poster row, instead of stepping zone-by-zone the way Cards mode's always-visible shelf allows.
            closeSpotlightPage();
            retarget(false);
        } else if (zone == Z_BROWSE) {
            leaveBrowse();
            retarget(false);
        } else if (zone == Z_CATALOG) {
            leaveCatalog();
            retarget(false);
        } else if (zone == Z_DRAWER || zone == WIDGETS) {
            closeDrawer();
            retarget(false);
        } else if (zone != Z_SHELF) {
            zone = Z_SHELF;
            retarget(false);
        } else {
            return;
        }
        click.move();
    }

    private void move(int dx, int dy) {
        catalogPage.stopTrailer();      // an arrow ends the trailer, and moves on
        if (popOpen) {
            movePop(dy);
            return;
        }
        if (sheetOpen) {
            moveGrid(dx, dy);
            return;
        }
        if (moveLight(dx, dy)) return;
        switch (zone) {
            case Z_SHELF:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(shelf.size() - 1, shelfIndex + dx));
                    if (next == shelfIndex) return;
                    shelfIndex = next;
                } else {
                    openDrawer();       // Up from the shelf is moveLight's
                }
                break;
            case Z_CATALOG:
                if (!moveCatalog(dx, dy)) return;
                break;
            case Z_BROWSE:
                if (!catalogPage.move(dx, dy)) {
                    if (dy >= 0) return;
                    leaveBrowse();      // up from the first catalog
                }
                break;
            case WIDGETS: {
                List<Integer> on = widgets();
                int at = on.indexOf(widgetIndex);
                if (dy < 0 || at < 0) {
                    zone = Z_DRAWER;
                } else if (dx != 0 && at + dx >= 0 && at + dx < on.size()) {
                    widgetIndex = on.get(at + dx);
                } else {
                    return;
                }
                break;
            }
            default:
                if (dx != 0) {
                    return;
                } else if (dy < 0) {
                    closeDrawer();
                } else if (enterCatalog()) {
                    // Down from the drawer: the poster rows come in below the quotes
                } else if (enterBrowse()) {
                    // No Trakt rows to land on: straight into TMDB's catalogs
                } else if (talkBack() && !widgets().isEmpty()) {
                    // Only with TalkBack: the widgets are there to be read, and this is how it gets to them.
                    zone = WIDGETS;
                    widgetIndex = widgets().get(0);
                } else {
                    return;
                }
                break;
        }
        retarget(false);
        click.move();
    }

    private static int nearest(RectF[] rects, float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < rects.length; i++) {
            float d = Math.abs(rects[i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    private void moveGrid(int dx, int dy) {
        int n = others.size();
        if (n == 0) return;
        int r = gridIndex / COLUMNS, col = gridIndex % COLUMNS;
        int rows = (n + COLUMNS - 1) / COLUMNS;
        int next = gridIndex;
        if (dx != 0) {
            int candidate = gridIndex + dx;
            if (candidate >= 0 && candidate < n && candidate / COLUMNS == r) next = candidate;
        } else {
            int targetRow = r + dy;
            if (targetRow >= 0 && targetRow < rows) next = Math.min(n - 1, targetRow * COLUMNS + col);
        }
        if (next == gridIndex) return;
        gridIndex = next;
        retargetGrid(false);
        click.move();
        gridDirty = true;
    }

    private void activate() {
        wake();
        if (reordering) {   // TalkBack's click while moving an app: drop it
            dropReorder();
            return;
        }
        if (popOpen) {
            choosePop();
            return;
        }
        if (opening != null) return;      // still looking up where a title streams
        HomeApps.Item it;
        RectF r;
        // A widget is only read; the drawer has nothing else to open.
        if (!sheetOpen && (zone == WIDGETS || zone == Z_DRAWER)) return;
        if (sheetOpen) {
            if (others.isEmpty()) return;
            it = others.get(gridIndex);
            r = focusedCell;
        } else if (zone == Z_TOP) {
            if (topIndex < 3) {
                selectTab(topIndex);
                return;
            }
            it = lightButtons.get(topIndex - 3);
            r = topRects[topIndex];
        } else if (zone == Z_CARDS) {
            Recs.Pick p = Prefs.spotlightOn() ? posterAt(cardStart) : cardAt(cardIndex);
            if (p == null || p.pkg == null) return;
            click.open();
            openPick(p, Prefs.spotlightOn() ? spotlightFocusedRect : cardRects[cardIndex]);
            return;
        } else if (zone == Z_BROWSE) {
            catalogPage.activate();
            return;
        } else if (zone == Z_CATALOG) {
            if (catRows.isEmpty()) return;
            Catalogs.Item listed = catRows.get(catRowIndex).items.get(catCol[catRowIndex]);
            click.open();
            if (listed.episodes) {      // a show you watch: its episodes, not the app
                activity.startActivity(EpisodesActivity.intent(activity, listed));
            } else if (listed.launch != null) {        // what an app says you were watching: back into that app
                openWatchNext(listed);
            } else {
                posterRect(catRowIndex, catCol[catRowIndex], cardRect);
                openTitle(listed, cardRect);
            }
            return;
        } else {
            if (shelf.isEmpty()) return;
            it = shelf.get(shelfIndex);
            r = tileRects[shelfIndex];
        }
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

    private static final long OPEN_LOADER_MS = 150;      // a cached answer opens at once; only a wait shows the dots
    private Launcher.Handle opening;
    private Catalogs.Item openingItem;
    private long openingAt;

    void openTitle(Catalogs.Item item, RectF poster) {
        final RectF from = new RectF(poster);
        openingItem = item;
        openingAt = SystemClock.uptimeMillis();
        catDirty = true;
        catalogPage.invalidate();
        opening = Launcher.get(activity).open(item.kind, item.id, item.imdb, target -> {
            opening = null;
            openingItem = null;
            catDirty = true;
            catalogPage.invalidate();
            show(target, from);
        });
    }

    /** A Continue Watching card from the TV's apps (Watch Next): its own intent resumes it; the entry was checked when read. */
    private void openWatchNext(Catalogs.Item item) {
        try {
            activity.startActivity(new android.content.Intent(item.launch));
        } catch (RuntimeException e) {
            AppLog.w("watch next " + item.title + ": " + e);
            showToast(Ui.trf("Couldn't open {app}.", "app", Launcher.get(activity).appName(item.appPkg)));
        }
    }

    /** A pick, like a catalog or Trakt title: no dots on it, but the answer is usually already there (Recs looks it up ahead). */
    private void openPick(Recs.Pick p, RectF r) {
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
        catDirty = true;
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
        if (popOpen) return;
        if (!sheetOpen && zone == Z_BROWSE) {       // the trailer of the focused title
            handler.removeCallbacks(holdStart);
            holdArmed = false;
            okHeld = true;
            catalogPage.hold();
            return;
        }
        if (sheetOpen) toggleShelf();
        else if (zone == Z_CATALOG) openTileMenu();     // a poster's menu: Remove or Hide
        else startReorder();
    }

    /** TalkBack's long click on an app in All Apps (once per press, if the remote's hold also arrives). */
    private void a11yHold() {
        if (SystemClock.uptimeMillis() - toggledAt < 1000) return;
        if (okDown) okHeld = true;     // the remote's release must not open the app
        toggleShelf();
    }

    private boolean canToggleShelf(int i) {
        if (!sheetOpen || i >= others.size()) return false;
        HomeApps.Item it = others.get(i);
        return it.kind == HomeApps.APP && it.pkg != null;
    }

    /** Puts the focused app at the end of the shelf, or takes it off if it's there; a toast says which. */
    private void toggleShelf() {
        handler.removeCallbacks(holdStart);
        holdArmed = false;
        if (!canToggleShelf(gridIndex)) return;
        okHeld = true;
        toggledAt = SystemClock.uptimeMillis();
        final HomeApps.Item it = others.get(gridIndex);
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

    // MARK: - Moving apps on the shelf

    private boolean canReorder() {
        if (sheetOpen || zone != Z_SHELF || shelfIndex >= shelf.size() || shelf.size() < 2) return false;
        HomeApps.Item it = shelf.get(shelfIndex);
        return it.kind == HomeApps.APP && it.pkg != null;
    }

    private void startReorder() {
        handler.removeCallbacks(holdStart);
        holdArmed = false;
        if (reordering || !canReorder()) return;
        wake();
        reordering = reorderAnim = okHeld = true;
        reorderOrigin = new ArrayList<>(shelf);
        liftIndex = shelfIndex;
        reorderAt = SystemClock.uptimeMillis();
        moveLift.target = 1;
        click.open();
        showToast(Ui.tr("← → to move · OK to drop · Back to cancel"));
        retarget(false);    // TalkBack reads "Moving <app>, position i of n"
    }

    /** Keys while moving an app: nothing else on the home screen gets them (system keys still go to the system). */
    private boolean reorderKey(KeyEvent e, boolean down) {
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) shiftReorder(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
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
                    dropReorder();
                } else if (!down) {
                    okDown = okHeld = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) {
                        cancelReorder(false);
                        click.move();
                    }
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    /** Swaps the lifted app with its neighbor; both slide from where they are to their new slots. */
    private void shiftReorder(int dx) {
        int i = shelfIndex, j = i + dx;
        if (j < 0 || j >= shelf.size()) return;
        if (shelf.get(j).kind != HomeApps.APP) return;      // "All Apps" stays the last tile
        HomeApps.Item it = shelf.get(i);
        shelf.set(i, shelf.get(j));
        shelf.set(j, it);
        Spring f = tileFocus[i];
        tileFocus[i] = tileFocus[j];
        tileFocus[j] = f;
        Spring s = tileSlide[i];
        tileSlide[i] = tileSlide[j];
        tileSlide[j] = s;
        float d = tileRects[i].left - tileRects[j].left;
        tileSlide[j].value += d;
        tileSlide[i].value -= d;
        if (tileShaders.length == shelf.size()) {
            LinearGradient g = tileShaders[i];
            tileShaders[i] = tileShaders[j];
            tileShaders[j] = g;
        }
        shelfIndex = liftIndex = j;
        reorderAnim = true;
        wake();
        retarget(false);
        click.move();
    }

    /** OK: drops the app where it is and saves the shelf's new order. */
    private void dropReorder() {
        if (!reordering) return;
        reordering = false;
        moveLift.target = 0;
        boolean changed = false;
        for (int i = 0; i < shelf.size() && !changed; i++) changed = shelf.get(i) != reorderOrigin.get(i);
        reorderOrigin = null;
        click.open();
        retarget(false);
        if (!changed) return;
        final List<String> order = new ArrayList<>();
        for (HomeApps.Item it : shelf) if (it.kind == HomeApps.APP && it.pkg != null) order.add(it.pkg);
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            // The saved list may hold more than the shelf shows (apps not installed, past the limit): the shown ones
            // take their new order in the same slots, the others stay where they were.
            List<String> all = HomeApps.shelfPackages(c), out = new ArrayList<>(all);
            int next = 0;
            for (int s = 0; s < out.size() && next < order.size(); s++) {
                if (order.contains(out.get(s))) out.set(s, order.get(next++));
            }
            if (next < order.size()) {
                out = new ArrayList<>(order);
                for (String p : all) if (!order.contains(p)) out.add(p);
            }
            final List<String> save = out;
            handler.post(() -> Prefs.setShelf(save));
        });
    }

    /** Back, or leaving the home screen: the original order comes back (sliding, unless `snap`). */
    private void cancelReorder(boolean snap) {
        if (!reordering && !reorderAnim) return;
        if (reordering) {
            reordering = false;
            moveLift.target = 0;
            int n = shelf.size();
            if (reorderOrigin != null && reorderOrigin.size() == n) {
                HomeApps.Item lifted = shelf.get(shelfIndex);
                Spring[] focus = new Spring[n], slide = new Spring[n];
                LinearGradient[] shaders = tileShaders.length == n ? new LinearGradient[n] : tileShaders;
                for (int a = 0; a < n; a++) {
                    int b = shelf.indexOf(reorderOrigin.get(a));
                    focus[a] = tileFocus[b];
                    slide[a] = tileSlide[b];
                    slide[a].value += tileRects[b].left - tileRects[a].left;
                    if (shaders != tileShaders) shaders[a] = tileShaders[b];
                }
                shelf = reorderOrigin;
                tileFocus = focus;
                tileSlide = slide;
                tileShaders = shaders;
                shelfIndex = liftIndex = shelf.indexOf(lifted);
            }
            reorderOrigin = null;
            retarget(snap);
        }
        if (snap) {
            moveLift.snap(0);
            for (Spring s : tileSlide) s.snap(0);
            wiggle = 0;
            liftIndex = -1;
            reorderAnim = false;
            tilesDirty = true;
        } else {
            wake();
        }
    }

    /** One frame of the move: the slides, the lift and the wiggle. Returns true while any of them moves. */
    private boolean stepReorder(float dt, long up) {
        boolean moving = step(tileSlide, dt) | moveLift.step(dt);
        if (reordering || moveLift.value > 0.001f) {
            wiggle = WIGGLE_DEG * (float) Math.sin((up - reorderAt) * (2 * Math.PI / WIGGLE_MS));
            moving = true;
        } else {
            wiggle = 0;
        }
        reorderAnim = moving;
        return moving;
    }

    // MARK: - TalkBack: every reachable item as a virtual node (Spoken). Id = group * 1000 + index + 1.

    /** The drawer's weather (0) and quotes (1) cards: read by TalkBack, never focused by the remote. */
    private static final int GRID = 9, WIDGETS = 8, POPUP = 12;

    private static int a11yId(int group, int index) {
        return group * 1000 + index + 1;
    }

    @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return spoken;
    }

    private void a11yItems(List<Integer> out) {
        if (popOpen) {
            for (int i = 0; i < popItems.size(); i++) out.add(a11yId(POPUP, i));
            return;
        }
        if (sheetOpen) {
            for (int i = 0; i < others.size(); i++) out.add(a11yId(GRID, i));
            return;
        }
        for (int i = picksOn() ? 0 : 3; i < 3 + lightButtons.size(); i++) out.add(a11yId(Z_TOP, i));
        for (int i = 0; i < Math.min(3, cards.size()); i++) out.add(a11yId(Z_CARDS, i));
        for (int i = 0; i < shelf.size(); i++) out.add(a11yId(Z_SHELF, i));
        if (drawerOpen && Prefs.quotes() && HomeData.active().length > 0) out.add(a11yId(WIDGETS, 1));
    }

    private String a11yText(int id) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        switch (group) {
            case POPUP: return label(popItems, i);
            case GRID:
                if (i >= others.size()) return "";
                return label(others, i) + ", " + Ui.trf("{i} of {n}", "i", i + 1, "n", others.size());
            case Z_TOP:
                if (i >= 3) return label(lightButtons, i - 3);
                return Ui.trf("{name}, tab {i} of {n}", "name", tabs()[i], "i", i + 1, "n", 3);
            case Z_CARDS: return cardAt(i) != null ? pickText(cardAt(i)) : "";
            case Z_SHELF:
                if (reordering && i == shelfIndex) {
                    return Ui.trf("Moving {app}, position {n} of {total}",
                            "app", label(shelf, i), "n", i + 1, "total", shelf.size());
                }
                return label(shelf, i);
            case WIDGETS: return quotesText();
            default: return "";
        }
    }

    /**
     * Where the item is now. Items off screen (the closed drawer, the sheet's hidden rows, the top while the drawer is
     * open) get a thin strip at that edge, so TalkBack's arrows can still reach them; going there brings them in.
     */
    private boolean a11yBounds(int id, Rect out) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        float dy;
        switch (group) {
            case POPUP:
                if (!popOpen || i >= popItems.size()) return false;
                popRowRect(i, a11yRect);
                dy = 0;
                break;
            case GRID: {
                if (!sheetOpen || i >= others.size()) return false;
                float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
                float x = ((1920 - gridWidth) / 2 + (i % COLUMNS) * (GRID_W + GRID_GAP)) * k;
                float y = (GRID_TOP + (i / COLUMNS - scroll.target) * GRID_ROW) * k;
                a11yRect.set(x, y, x + GRID_W * k, y + GRID_H * k);
                dy = 0;
                break;
            }
            case Z_TOP:
                if (i >= 3 + lightButtons.size()) return false;
                a11yRect.set(topRects[i]);
                dy = (i < 3 ? heroLayer : status).getTranslationY();
                break;
            case Z_CARDS:
                if (i >= Math.min(3, cards.size())) return false;
                a11yRect.set(cardRects[i]);
                dy = heroLayer.getTranslationY();
                break;
            case Z_SHELF:
                if (i >= tileRects.length) return false;
                a11yRect.set(tileRects[i]);
                dy = tiles.getTranslationY();
                break;
            case WIDGETS:
                if (!drawerOpen) return false;
                a11yRect.set(quotesCard);
                dy = 0;
                break;
            default:
                return false;
        }
        a11yRect.offset(0, dy);
        float edge = 24 * k;
        if (a11yRect.top >= h - edge) a11yRect.set(a11yRect.left, h - edge, a11yRect.right, h);
        else if (a11yRect.bottom <= edge) a11yRect.set(a11yRect.left, 0, a11yRect.right, edge);
        a11yRect.round(out);
        return out.width() > 0 && out.height() > 0;
    }

    private int a11yFocused() {
        if (popOpen) return popItems.isEmpty() ? -1 : a11yId(POPUP, popIndex);
        if (sheetOpen) return others.isEmpty() ? -1 : a11yId(GRID, gridIndex);
        switch (zone) {
            case Z_TOP: return a11yId(Z_TOP, topIndex);
            case Z_CARDS: return cardAt(cardIndex) != null ? a11yId(Z_CARDS, cardIndex) : -1;
            case Z_SHELF: return shelf.isEmpty() ? -1 : a11yId(Z_SHELF, shelfIndex);
            case WIDGETS: return a11yId(WIDGETS, widgetIndex);
            default: return -1;
        }
    }

    /** The drawer's widgets that are on (0 weather, 1 quotes), with the drawer open. */
    private List<Integer> widgets() {
        List<Integer> out = new ArrayList<>();
        if (!drawerOpen) return out;
        if (Prefs.quotes() && HomeData.active().length > 0) out.add(1);
        return out;
    }

    /** A spoken-feedback service (TalkBack) is on. */
    private boolean talkBack() {
        AccessibilityManager am = (AccessibilityManager) getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
        return am != null && am.isEnabled()
                && !am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN).isEmpty();
    }

    /** TalkBack moved to an item: the remote's focus goes there (opening or closing the drawer on the way). */
    private void a11yFocus(int id) {
        wake();
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        if (group == GRID) {
            if (!sheetOpen || i >= others.size()) return;
            gridIndex = i;
            retargetGrid(false);
            gridDirty = true;
            click.move();
            return;
        }
        if (group == POPUP) {
            if (!popOpen || i >= popItems.size()) return;
            popIndex = i;
            aim(popFocus, popIndex, false);
            popDirty = true;
            click.move();
            return;
        }
        if (sheetOpen || reordering) return;
        switch (group) {
            case WIDGETS:
                if (!widgets().contains(i)) return;
                widgetIndex = i;
                break;
            case Z_TOP:
                if (i >= 3 + lightButtons.size() || (i < 3 && !picksOn())) return;
                if (drawerOpen) closeDrawer();
                topIndex = i;
                if (i < 3) selectTab(i);
                break;
            case Z_CARDS:
                if (i >= Math.min(3, cards.size())) return;
                if (drawerOpen) closeDrawer();
                cardIndex = i;
                break;
            case Z_SHELF:
                if (i >= shelf.size()) return;
                if (drawerOpen) closeDrawer();
                shelfIndex = i;
                break;
            default:
                return;
        }
        zone = group;
        retarget(false);
        click.move();
    }

    private static String label(List<HomeApps.Item> list, int i) {
        return i >= 0 && i < list.size() && list.get(i).label != null ? list.get(i).label : "";
    }

    /** A recommendation: title, details and the app that plays it. */
    private static String pickText(Recs.Pick p) {
        if (p == null) return "";
        StringBuilder s = new StringBuilder(p.title == null ? "" : p.title);
        if (p.meta != null && !p.meta.isEmpty()) s.append(", ").append(p.meta);
        String app = Recs.serviceName(p);
        if (app != null && !app.isEmpty()) s.append(". App: ").append(app);
        return s.toString();
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

    // MARK: - Light design: title, tabs, cards and icon-only buttons

    private void layoutLight() {
        if (lTab == null) return;
        // Text tabs; the focus pill extends 26 px past the text (the first tab's text starts at the margin).
        Look.Metrics m = look.m;
        float pad = m.tabPad * k, tabTop = m.tabTop * k, tabBottom = (m.tabTop + m.tabH) * k;
        float icon = m.tabIcon > 0 ? (m.tabIcon + 14) * k : 0;
        float x = MARGIN * k - pad;
        for (int i = 0; i < 3; i++) {
            float tw = Math.max(lTabOn.measureText(tabs()[i]), lTab.measureText(tabs()[i]));
            topRects[i].set(x, tabTop, x + icon + tw + 2 * pad, tabBottom);
            x = topRects[i].right + m.tabGap * k;
        }
        float b = m.buttonSize * k, right = CONTENT_R * k, cy = m.buttonCy * k;
        for (int i = lightButtons.size() - 1; i >= 0; i--) {
            topRects[3 + i].set(right - b, cy - b / 2, right, cy + b / 2);
            right -= b + m.buttonGap * k;
        }
        float top = m.cardsTop * k, ch = m.cardH * k, g = m.cardGap * k, left = MARGIN * k, tall = m.tallW * k;
        float wide = (CONTENT_R - MARGIN) * k - 2 * tall - 2 * g;
        cardRects[0].set(left, top, left + wide, top + ch);
        cardRects[1].set(cardRects[0].right + g, top, cardRects[0].right + g + tall, top + ch);
        cardRects[2].set(cardRects[1].right + g, top, cardRects[1].right + g + tall, top + ch);
        heroDirty = statusDirty = true;
    }

    /** The three cards of the current tab (Explore, Movies, Shows) and the background taken from the first one.
     * Spotlight has no tabs: its poster row always pulls from Explore's pool (tab 0), whatever `tab` last was. */
    private void updateCards() {
        List<Recs.Pick> list = Prefs.spotlightOn() ? recs.now(TAB_KIND[0], Recs.SPOTLIGHT_POOL) : recs.now(TAB_KIND[tab]);
        if (!samePicks(list, cards)) {
            startCardTransition(0);
            // The daily list keeps each staying title in its place (Recs.publish): the row stays where it was.
            if (cards.isEmpty() || list.isEmpty()) {
                cardStart = 0;
                spotlightRowShift.snap(0);
                carouselTarget = 0;
                carousel.snap(0);
            }
            cards = list;
            cardIndex = Math.min(cardIndex, Math.max(0, Math.min(3, list.size()) - 1));
            if (zone == Z_CARDS && list.isEmpty()) {
                zone = Z_SHELF;
                retarget(false);
            }
        }
        // All three tabs preloaded: switching tabs is instant. With more picks (pick_count) only this tab's: the
        // images are kept in memory for the cards that slide in. Spotlight keeps only the full-screen photos of the
        // focus ±1 in memory (Recs.nearby); the row's posters load through the separate, lighter `Recs.poster` cache
        // (drawSpotlight).
        recs.setView(TAB_KIND[Prefs.spotlightOn() ? 0 : tab], cardStart);
        if (Prefs.spotlightOn()) {
            for (int i = cardStart - 1; i <= cardStart + 1; i++) {
                Recs.Pick p = posterAt(i);
                if (p != null) recs.backdrop(p);
            }
        } else {
            for (Recs.Pick p : Config.PICK_COUNT > 3 ? cards : recs.wanted()) recs.backdrop(p);
        }
        showCards();
    }

    /** The first card's photo as the background, and the cards drawn again. */
    private void showCards() {
        recs.setView(TAB_KIND[Prefs.spotlightOn() ? 0 : tab], cardStart);     // these cards and the next page get their details
        Backdrop b = cards.isEmpty() ? null : recs.backdrop(cardAt(0));
        if (cards.isEmpty() || b != null) showBackdrop(b);
        heroDirty = true;
        spoken.focusMoved();    // new picks under the focused card
    }

    /** `i` wrapped into `[0, cards.size())` — safe for a negative `i` too (Java's `%` can return negative). Spotlight's
     * `cardStart` is an unbounded virtual index (never clamped, never specially wrapped on its own) so that circular
     * scrolling never needs to "jump" the index or the Spring back around; only lookups wrap. */
    private int wrapCard(int i) {
        int n = cards.size();
        if (n == 0) return -1;
        return ((i % n) + n) % n;
    }

    /** The pick in card `slot` (0 to 2): with more than three picks they take turns, wrapping around. */
    private Recs.Pick cardAt(int slot) {
        int n = cards.size();
        if (slot < 0 || slot >= Math.min(3, n)) return null;
        int i = wrapCard(cardStart + slot);
        return i < 0 ? null : cards.get(i);
    }

    /** Spotlight's poster row: `cards`, unreordered — column `col` is always the same pick, unlike `cardAt`'s slots,
     * which rotate. `cardStart` is which column has focus (an unbounded virtual index, not a rotation offset and not
     * clamped to the pool's real size — `col` wraps circularly via {@link #wrapCard}, the same way `cardAt(0)` already
     * wraps `cardStart` itself, so `showCards()`/`rotateCards`'s background-driving code needs no Spotlight-specific
     * branch). */
    private Recs.Pick posterAt(int col) {
        int i = wrapCard(col);
        return i < 0 ? null : cards.get(i);
    }

    /** Left/right in Spotlight's poster row: the focused pick is always drawn in slot 0 (not a moving focus ring
     * across a stationary row) — but unlike Cards' instant `rotateCards`, the row slides to it with the catalog poster
     * rows' smoothness (`spotlightRowShift` is a `Spring`, stepped every frame; only the *target* ever jumps). The row
     * is circular (infinite scroll): `cardStart` is never clamped, so there's always a previous and a next pick to
     * scroll to, in either direction, forever — `posterAt`/`wrapCard` map it onto the real pool. */
    private void scrollSpotlight(int dx) {
        if (cards.isEmpty()) return;
        cardStart += dx;
        spotlightRowShift.target = cardStart;
        showCards();
        invalidateAll();
    }

    /** pick_rotate_seconds, Spotlight: advances to the next column like `scrollSpotlight(1)` — circular, so there's
     * no "end" to loop back from any more; it just keeps counting up, same as a manual scroll would. */
    private void autoAdvanceSpotlight() {
        if (cards.size() < 2) return;
        cardStart++;
        spotlightRowShift.target = cardStart;
        showCards();
        invalidateAll();
    }

    /** Keeps what the cards show now, to fade it out while the new picks come in (called before they change). */
    private void startCardTransition(int dir) {
        if (Ui.LEGACY) return;
        for (int i = 0; i < prevCards.length; i++) prevCards[i] = cardAt(i);
        cardMixAt = SystemClock.uptimeMillis();
        cardMixDir = dir;
        wake();
    }

    /** True while the cards change their picks (the frames go on until it is done). */
    private boolean tickCards(long up) {
        boolean on = cardsAnimating || (cardMixAt != 0 && up - cardMixAt < look.m.cardFadeMs + 50);
        cardsAnimating = false;
        return on;
    }

    /** Card `i`: when the picks just changed, the old one slides out and fades while the new one slides in and fades. */
    private void drawCardSlot(Canvas c, int i) {
        long up = SystemClock.uptimeMillis();
        float t = cardMixAt == 0 ? 1 : (up - cardMixAt) / (float) look.m.cardFadeMs;
        if (t >= 1) {
            drawLightCard(c, i, cardAt(i));
            return;
        }
        cardsAnimating = true;
        float stagger = look.m.cardStagger;
        float u = Ui.clamp((t - i * stagger) / (1 - 2 * stagger));
        u = u * u * (3 - 2 * u);
        Recs.Pick old = prevCards[i], now = cardAt(i);
        float d = look.m.cardSlide * k * cardMixDir;
        boolean same = old == now;
        if (old != null && !same) drawFaded(c, i, old, 1 - u, -d * u);
        if (now != null) drawFaded(c, i, now, same ? 1 : u, same ? 0 : d * (1 - u));
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** The rectangle of card slot `j` (0 to 2 are the cards; -1 is where a card leaves to, 3 where one comes from). */
    private void slotRect(int j, RectF out) {
        RectF r0 = cardRects[0], r1 = cardRects[1], r2 = cardRects[2];
        float tall = r1.width(), gap = r1.left - r0.right;
        // Further than the inter-card gap (#picks-carousel): already fully off the visible screen from here, but
        // the extra distance means the eased edge-fade (drawTravelCard) has more travel to work with before the
        // entering/exiting card reaches the row, instead of becoming a half-formed, near-opaque card shortly in.
        float stage = gap + tall;
        if (j < 0) out.set(r0.left - stage - tall, r0.top, r0.left - stage, r0.bottom);
        else if (j > 2) out.set(r2.right + stage, r2.top, r2.right + stage + tall, r2.bottom);
        else out.set(cardRects[j]);
    }

    /** The rectangle of a card `x` slots from the first: between the two slots it is moving between. */
    private void slotAt(float x, RectF out) {
        int j = Math.max(-1, Math.min(2, (int) Math.floor(x)));
        float t = x - j;
        slotRect(j, slotA);
        slotRect(j + 1, slotB);
        out.set(lerp(slotA.left, slotB.left, t), lerp(slotA.top, slotB.top, t), lerp(slotA.right, slotB.right, t), lerp(slotA.bottom, slotB.bottom, t));
    }

    /**
     * The corner radius of a card `x` slots from the first, matching `slotAt`'s own slot-to-slot interpolation
     * instead of the picture's wide/tall aspect ratio: slots 1 and 2 are both "tall" (same aspect), but
     * `look.cardRadius` gives them different radii (28 vs 48 in MaterialLook) — interpolating by aspect alone
     * (as this used to) collapses that distinction and makes whichever card settles into slot 2 snap to the wrong
     * radius the instant it stops sliding and switches from this draw path to the resting one (`drawLightCard`).
     */
    private float cardRadiusAt(float x) {
        int j = Math.max(-1, Math.min(2, (int) Math.floor(x)));
        float t = x - j;
        return lerp(look.cardRadius(Math.max(0, Math.min(2, j))), look.cardRadius(Math.max(0, Math.min(2, j + 1))), t);
    }

    /** The cards while the picks travel: each pick moves from its slot to the next, changing shape on the way. */
    private void drawCarousel(Canvas c) {
        float pos = carousel.value;
        int first = (int) Math.floor(pos) - 1, last = (int) Math.floor(pos) + 3, nearest = first;
        for (int a = first; a <= last; a++) if (Math.abs(a - pos) < Math.abs(nearest - pos)) nearest = a;
        for (int a = first; a <= last; a++) if (a != nearest) drawTravelCard(c, a, pos);
        drawTravelCard(c, nearest, pos);
    }

    private void drawTravelCard(Canvas c, int a, float pos) {
        float x = a - pos;
        if (x <= -1 || x >= 3) return;
        int n = cards.size();
        Recs.Pick p = cards.get(((a % n) + n) % n);
        slotAt(x, travel);
        float wideRatio = cardRects[0].width() / cardRects[0].height(), tallRatio = cardRects[1].width() / cardRects[1].height();
        float wide = Ui.clamp((travel.width() / travel.height() - tallRatio) / (wideRatio - tallRatio));
        // Eased, not linear: a card that's still mostly off to the side (entering or leaving) stays close to fully
        // transparent for most of its travel, instead of being visibly see-through the whole way across — it only
        // really shows up right as it reaches the row.
        float edge = x < 0 ? 1 + x : x > 2 ? 3 - x : 1;
        float alpha = x < 0 ? 1 - (1 - edge) * (1 - edge) : x > 2 ? edge * edge : 1;
        float f = Ui.clamp(cardFocus[0].value) * Math.max(0, 1 - Math.abs(x));
        float left = pos - carouselTarget;       // how far the row still has to go
        float reveal = a == carouselTarget ? Ui.clamp(1 - Math.abs(left) * 1.6f) : 1;
        float s = 1 + look.m.cardGrow * f, bw = travel.width(), bh = travel.height();
        float cx = travel.centerX(), cy = travel.centerY() - look.m.cardLift * k * f;
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r0 = cardRadiusAt(x) * k, r = r0 * s;
        // The card's shape morphs continuously as it slides, so an offscreen layer sized to it is almost never the
        // same two frames running (and several cards, each a different size, can be on screen at once): that defeats
        // the GPU's render-target cache (#memory-management). So the edge fade is `alpha` applied to each paint below
        // instead of one saveLayerAlpha over the whole card.
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        tmp.offset(0, (10 + 14 * f) * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, (tone(0.14f, 0.32f) + tone(0.2f, 0.28f) * f) * alpha));
        shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep);
        c.restore();
        Backdrop b = recs.backdrop(p);
        if (b != null && (b.tall != null || b.wide != null)) {
            // The small card's picture and the hero's cross-fade as the shape changes; each is cropped to fit, and lags a
            // little behind its card while the row moves.
            Bitmap lo = b.tall != null ? b.tall : b.wide, hi = b.wide != null ? b.wide : b.tall;
            float shift = -left * 0.12f;
            bitmapPaint.setAlpha(Math.round(255 * alpha));
            roundImage(c, lo, cell, r, shift);
            if (hi != lo && wide > 0.01f) {
                bitmapPaint.setAlpha(Math.round(255 * wide * alpha));
                roundImage(c, hi, cell, r, shift);
            }
            bitmapPaint.setAlpha(255);
            drawBadge(c, cell, pickService(p));
        } else {
            fill.setShader(null);
            fill.setColor(Ui.alpha(look.color(Look.PLACEHOLDER, dark()), alpha));
            c.drawRoundRect(cell, r, r, fill);
        }
        if (!look.focusRing(c, cell, r, f, dark(), alpha) && f > 0.01f) {
            rim.setShader(null);
            rim.setColor(Ui.alpha(0xFFFFFFFF, 0.7f * f * alpha));
            c.drawRoundRect(cell, r, r, rim);
        }
        if (!look.cardCaption(c, cell, r, wide, p.title, p.meta, f, dark(), reveal, alpha)) {
            int titleAlpha = lCardTitle.getAlpha();
            lCardTitle.setAlpha(Math.round(titleAlpha * alpha));
            fitPaint.set(lCardTitle);
            c.drawText(TextUtils.ellipsize(p.title, fitPaint, bw, TextUtils.TruncateAt.END).toString(), travel.left, travel.bottom + 46 * k, lCardTitle);
            lCardTitle.setAlpha(titleAlpha);
            if (wide > 0.5f && p.meta != null && !p.meta.isEmpty()) {
                int old = lCardMeta.getAlpha();
                lCardMeta.setAlpha(Math.round(old * (wide - 0.5f) * 2 * alpha));
                fitPaint.set(lCardMeta);
                c.drawText(TextUtils.ellipsize(p.meta, fitPaint, bw, TextUtils.TruncateAt.END).toString(), travel.left, travel.bottom + 80 * k, lCardMeta);
                lCardMeta.setAlpha(old);
            }
        }
    }

    private void drawFaded(Canvas c, int i, Recs.Pick pick, float alpha, float dx) {
        if (alpha <= 0.01f) return;
        if (alpha >= 0.995f && dx == 0) {
            drawLightCard(c, i, pick);
            return;
        }
        RectF r = cardRects[i];
        fadeBounds.set(r.left - 70 * k + dx, r.top - 70 * k, r.right + 70 * k + dx, r.bottom + 160 * k);
        int saved = c.saveLayerAlpha(fadeBounds, Math.round(255 * alpha));
        c.translate(dx, 0);
        drawLightCard(c, i, pick);
        c.restoreToCount(saved);
    }

    /** Moves the picks by `step` through the cards, in a circle. */
    private void rotateCards(int step) {
        int n = cards.size();
        if (n < 2) return;
        carouselTarget += step;
        carousel.target = carouselTarget;
        if (Ui.LEGACY) carousel.snap(carouselTarget);
        cardStart = ((carouselTarget % n) + n) % n;
        showCards();
        invalidateAll();
    }

    private void selectTab(int index) {
        if (index == tab) return;
        tab = index;
        cardStart = 0;              // another tab's list: its first card
        carouselTarget = 0;
        carousel.snap(0);
        updateCards();
        recs.refreshIfStale();      // the new tab's cards may still need their details
        heroDirty = true;
    }

    /**
     * Background: the first card's photo, heavily blurred and almost white; without it, a light gradient. At night, the
     * same photo almost black (or a dark gradient), drawn on top during the transition.
     */
    private void drawLightSky(Canvas c) {
        if (look.m.focusGlow) updateFocusGlow();
        if (look.background(c, screen, dark())) {
            if (look.m.homeBackdrop) drawHomeBackdrop(c);
            look.overlay(c, screen, dark());
            return;
        }
        long up = SystemClock.uptimeMillis();
        float e = bgFadeAt == 0 ? 1 : fadeEase(bgFadeAt, up);
        boolean fading = bgFadeAt != 0;
        // Drawer open: the background lightens (by day) or darkens (at night), as a filter on the drawing itself.
        android.graphics.ColorFilter v = veil(tone(0.32f, 0.42f) * Ui.clamp(drawer.value), blend(0xFFFFFFFF, 0xFF000000, night));
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
     * look's scrim. It fades away as the catalogs come in, so they (and every other screen) keep the look's background.
     */
    private void drawHomeBackdrop(Canvas c) {
        float amount = 1 - Ui.clamp(catIn.value * 1.4f);
        if (amount < 0.01f || (bgTo == null && bgFrom == null)) return;
        long up = SystemClock.uptimeMillis();
        boolean fading = bgFadeAt != 0;
        float e = fading ? fadeEase(bgFadeAt, up) : 1;
        imagePaint.setColorFilter(null);
        if (fading && bgFrom != null) drawHomePicture(c, bgFrom, amount * (bgTo == null ? 1 - e : 1));
        if (bgTo != null) drawHomePicture(c, bgTo, amount * (fading ? e : 1));
        int scrim = look.homeScrim(dark());
        if (scrim != 0) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(scrim, amount));
            c.drawRect(screen, fill);
        }
    }

    /** The picture of the home background: heavily blurred, with some of the lightly blurred one over it (the look's `homeSharp`). */
    private void drawHomePicture(Canvas c, Backdrop b, float alpha) {
        drawImage(c, b.frostImage, alpha);
        if (look.m.homeSharp > 0) drawImage(c, b.glassImage(), alpha * look.m.homeSharp);
    }

    /** Tells the look where the focus is: the centre of the focused item, blended by how focused each one is. */
    private final RectF focusRect = new RectF();

    private void updateFocusGlow() {
        float weight = 0, x = 0, y = 0;
        for (int i = 0; i < Math.min(tileFocus.length, tileRects.length); i++) {
            float v = Ui.clamp(tileFocus[i].value);
            weight += v;
            x += v * tileRects[i].centerX();
            y += v * tileRects[i].centerY();
        }
        for (int i = 0; i < Math.min(topFocus.length, topRects.length); i++) {
            float v = Ui.clamp(topFocus[i].value);
            weight += v;
            x += v * topRects[i].centerX();
            y += v * topRects[i].centerY();
        }
        for (int i = 0; i < Math.min(cardFocus.length, cardRects.length); i++) {
            float v = Ui.clamp(cardFocus[i].value);
            weight += v;
            x += v * cardRects[i].centerX();
            y += v * cardRects[i].centerY();
        }
        if (zone == Z_CATALOG && catRowIndex < catRows.size() && catRowIndex < catCol.length) {
            float v = Ui.clamp(catFocus.value);
            posterRect(catRowIndex, catCol[catRowIndex], focusRect);
            weight += v;
            x += v * focusRect.centerX();
            y += v * focusRect.centerY();
        }
        if (weight > 0.001f) look.setFocus(x / weight, y / weight, Math.min(1, weight));
        else look.setFocus(w / 2f, h / 2f, 0);
    }

    private void drawLightImage(Canvas c, Backdrop b, float alpha) {
        if (night < 0.999f || b.nightImage == null) drawImage(c, b.lightImage, alpha);
        if (night > 0.001f) drawImage(c, b.nightImage, alpha * night);
    }

    private void drawLightTop(Canvas c) {
        applyTone();
        if (!picksOn()) {
            drawClock(c);
            if (HomeData.coinCount() > 0) drawQuotes(c);
            return;
        }
        if (Prefs.spotlightOn()) {
            // drawSpotlight paints the full-bleed photo first: weather has to come after it on this same canvas, or
            // the photo paints straight over it (unlike Cards mode, where the photo is a separate layer underneath).
            drawSpotlight(c);
            drawTopWeather(c);
            if (HomeData.coinCount() > 0) drawQuotes(c);
            return;
        }
        drawTopWeather(c);
        // Text tabs (the selected one in bold, in the main text color); with focus on the tabs, the selected one gets
        // the white pill with a shadow and grows a little (the tvOS focus).
        float focus = Ui.clamp(topFocus[tab].value);
        for (int i = 0; i < 3; i++) {
            RectF r = topRects[i];
            if (look.tab(c, r, i == tab, focus, dark())) {
                // drawn by the look
            } else if (i == tab && focus > 0.01f) {
                float s = 1 + look.m.tabGrow * focus;
                cell.set(r.centerX() - r.width() * s / 2, r.centerY() - r.height() * s / 2,
                        r.centerX() + r.width() * s / 2, r.centerY() + r.height() * s / 2);
                float rr = cell.height() / 2;
                if (focus > 0.01f) {
                    tmp.set(cell);
                    tmp.offset(0, 8 * k * focus);
                    shadow.setColor(Ui.alpha(0xFF000000, tone(0.22f, 0.4f) * focus));
                    shadows.draw(c, tmp, rr, SHADOW_BLUR * k, shadow);
                }
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFFFFFFFF, focus));
                c.drawRoundRect(cell, rr, rr, fill);
            }
            Paint p = i == tab ? lTabOn : lTab;
            if (i == tab) lTabOn.setColor(look.tabInk(focus, dark()));
            float icon = look.m.tabIcon > 0 ? (look.m.tabIcon + 14) * k : 0, text = p.measureText(tabs()[i]);
            float left = r.centerX() - (icon + text) / 2;
            if (icon > 0) look.tabIcon(c, i, left + look.m.tabIcon * k / 2, r.centerY(), look.m.tabIcon * k, p.getColor());
            c.drawText(tabs()[i], left + icon, r.centerY() + Ui.halfCap(p), p);
        }
        int top = -1;
        for (int i = 0; i < 3; i++) if (cardFocus[i].value > 0.02f && (top < 0 || cardFocus[i].value > cardFocus[top].value)) top = i;
        if (!(cards.isEmpty() && recs.noPicks())) {      // no picks to show and none coming: no empty cards either
            if (!Ui.LEGACY && cards.size() > 1 && carousel.value != carousel.target) {
                drawCarousel(c);
            } else {
                for (int i = 0; i < 3; i++) if (i != top) drawCardSlot(c, i);
                if (top >= 0) drawCardSlot(c, top);
            }
        }
        if (HomeData.coinCount() > 0) drawQuotes(c);
    }

    /**
     * Spotlight: a full-screen photo of the focused pick (`look.spotlightPhoto`, each look's own corner treatment),
     * its title and meta over the dark side, a heading, and a poster row that drives it — moving focus in the row
     * (Z_CARDS, `scrollSpotlight`) swaps the photo and title instantly. The row scrolls (`spotlightRowShift`, the
     * catalog rows' `catShift` pattern) instead of re-sorting the focused pick to the front, and draws one column
     * past `SPOTLIGHT_VISIBLE` so the next one peeks in at the edge, the same way `drawCatalog` already does.
     */
    private Backdrop spotlightLastBackdrop;
    private int spotlightWarmFor = Integer.MIN_VALUE;
    private Shader spotlightCorner;
    private final Paint spotlightCornerPaint = new Paint();
    private float spotlightCornerW;
    private String wrapSrc;
    private float wrapW;
    private int wrapGen = -1, wrapLineCount = 1;
    private final String[] wrapLines = new String[2];

    /** Breaks `text` at spaces into at most two lines no wider than `maxW` (the second ellipsized if it still overflows). */
    private void wrapTitle(String text, android.text.TextPaint p, float maxW) {
        int cut = p.breakText(text, true, maxW, null);
        if (cut >= text.length()) {
            wrapLines[0] = text;
            wrapLineCount = 1;
            return;
        }
        int sp = text.lastIndexOf(' ', cut);
        if (sp <= 0) sp = cut;
        wrapLines[0] = text.substring(0, sp).trim();
        wrapLines[1] = TextUtils.ellipsize(text.substring(sp).trim(), p, maxW, TextUtils.TruncateAt.END).toString();
        wrapLineCount = 2;
    }

    private void drawSpotlight(Canvas c) {
        Recs.Pick focused = posterAt(cardStart);
        Backdrop b = focused == null ? null : recs.backdrop(focused);
        // Neighbours load ahead (backdrop() starts the load and returns null until it's ready), and while the focused
        // pick's own photo is still loading the previous one stays up rather than a blank background.
        if (focused != null && cardStart != spotlightWarmFor) {
            spotlightWarmFor = cardStart;
            for (int d = -1; d <= 1; d++) if (d != 0) recs.backdrop(posterAt(cardStart + d));
        }
        if (b != null) spotlightLastBackdrop = b;
        else if (spotlightLastBackdrop != null) b = spotlightLastBackdrop;
        look.spotlightPhoto(c, screen, b, dark());
        // A dark corner behind the top-right buttons, so they stay readable over any photo.
        if (spotlightCorner == null || spotlightCornerW != w) {
            spotlightCornerW = w;
            spotlightCorner = new RadialGradient(w, 0, 760 * k, new int[] {0xB8000000, 0x70000000, 0},
                    new float[] {0, 0.5f, 1}, Shader.TileMode.CLAMP);
        }
        spotlightCornerPaint.setShader(spotlightCorner);
        c.drawRect(screen, spotlightCornerPaint);
        if (focused != null) {
            float titleW = Math.min(HERO_TITLE_W, CONTENT_R - MARGIN) * k;
            fitPaint.set(lSpotlightTitle);
            if (!focused.title.equals(wrapSrc) || wrapW != titleW || wrapGen != Ui.generation) {
                wrapSrc = focused.title;
                wrapW = titleW;
                wrapGen = Ui.generation;
                wrapTitle(wrapSrc, fitPaint, titleW);
            }
            // A long title wraps to a second line (the last line stays on the baseline the meta hangs from, so the
            // block grows upward); only a title too long for two lines is cut with an ellipsis.
            float lineH = 72 * k;
            for (int i = 0; i < wrapLineCount; i++)
                c.drawText(wrapLines[i], MARGIN * k, SPOTLIGHT_TITLE_Y * k - (wrapLineCount - 1 - i) * lineH, lSpotlightTitle);
            if (focused.meta != null && !focused.meta.isEmpty()) {
                fitPaint.set(lSpotlightMeta);
                String meta = TextUtils.ellipsize(focused.meta, fitPaint, titleW, TextUtils.TruncateAt.END).toString();
                c.drawText(meta, MARGIN * k, (SPOTLIGHT_TITLE_Y + SPOTLIGHT_META_GAP) * k, lSpotlightMeta);
            }
        }
        c.drawText(Ui.tr("Top picks for you"), MARGIN * k, SPOTLIGHT_HEADING_Y * k, lSpotlightHeading);
        float x0 = MARGIN * k, top = SPOTLIGHT_ROW_TOP * k, pw = SPOTLIGHT_POSTER_W * k, ph = SPOTLIGHT_POSTER_H * k,
                gap = SPOTLIGHT_POSTER_GAP * k, r = TILE_RADIUS * k;
        // Circular: one column peeks in at the *start* too, not just the end (the row never runs out in either
        // direction), so the window is one wider on the left than Cards-era code needed.
        int first = (int) Math.floor(spotlightRowShift.value) - 1, last = first + SPOTLIGHT_VISIBLE + 2;
        for (int col = first; col <= last; col++) {
            Recs.Pick p = posterAt(col);
            if (p == null) continue;
            float x = x0 + (col - spotlightRowShift.value) * (pw + gap);
            cardRect.set(x, top, x + pw, top + ph);
            if (cardRect.right < 0 || cardRect.left > w) continue;      // fully off-screen: not even partially drawn
            if (col == cardStart) spotlightFocusedRect.set(cardRect);
            float f = col == cardStart ? Ui.clamp(cardFocus[0].value) : 0;
            float s = 1 + look.m.cardGrow * f;
            float cx = cardRect.centerX(), cy = cardRect.centerY() - look.m.cardLift * k * f;
            cell.set(cx - pw * s / 2, cy - ph * s / 2, cx + pw * s / 2, cy + ph * s / 2);
            float rr = r * s;
            // TMDB's poster art, not the textless backdrop: the title is already baked into the image, so there's no
            // caption to draw over it (unlike the Cards-mode cards, which caption a textless backdrop).
            Bitmap img = recs.poster(p, Math.round(pw));
            if (img != null) {
                roundImage(c, img, cell, rr);
            } else {
                fill.setShader(null);
                fill.setColor(look.color(Look.PLACEHOLDER, dark()));
                c.drawRoundRect(cell, rr, rr, fill);
            }
            if (!look.focusRing(c, cell, rr, f, dark(), 1) && f > 0.01f) {
                rim.setShader(null);
                rim.setColor(Ui.alpha(0xFFFFFFFF, 0.7f * f));
                c.drawRoundRect(cell, rr, rr, rim);
            }
        }
    }

    /** The picks carousel and its tabs: needs the TMDB key, and the person hasn't turned it off (Settings > TMDB). */
    private boolean picksOn() {
        return Recs.hasKey(getContext()) && Prefs.picksOn();
    }

    private long topWeatherAt = -1;
    private int topWeatherPrefs = -1;
    private String topWeatherLanguage = "", topTemp = "", topCond = "", topSub = "";

    /** The weather where the title used to be: the symbol and the temperature large, the condition and today's high and low beside them. */
    private void drawTopWeather(Canvas c) {
        HomeData.Weather wx = data.weather;
        if (!Prefs.weather() || Double.isNaN(wx.temp)) return;
        if (data.weatherAt != topWeatherAt || Prefs.version != topWeatherPrefs || !Ui.language.equals(topWeatherLanguage)) {
            topWeatherAt = data.weatherAt;
            topWeatherPrefs = Prefs.version;
            topWeatherLanguage = Ui.language;
            topTemp = HomeData.degrees(wx.temp);
            topCond = HomeData.condition(wx.code, wx.isDay);
            String city = Prefs.city();
            topSub = HomeData.highLow(wx, "  ") + (city.isEmpty() ? "" : "  ·  " + city);
        }
        float x = MARGIN * k, base = look.m.titleBase * k;
        String symbol = Symbols.weather(wx.code, wx.isDay);
        float size = 64 * k, sw = Symbols.width(getContext(), symbol, size);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, base - Ui.halfCap(lTitle), size, look.symbolInk(symbol, lTitle.getColor()));
        float tx = x + sw + 20 * k;
        c.drawText(topTemp, tx - Ui.leftBearing(lTitle, topTemp), base, lTitle);
        float bx = tx + lTitle.measureText(topTemp) + 30 * k;
        c.drawText(topCond, bx, base - 34 * k, lWeather);
        c.drawText(topSub, bx, base, lCardMeta);
    }

    private void drawClock(Canvas c) {
        float x = MARGIN * k;
        c.drawText(dateText, x - Ui.leftBearing(lDate, dateText), L_DATE_BASE * k, lDate);
        c.drawText(clockText, x - Ui.leftBearing(lClock, clockText), L_CLOCK_BASE * k, lClock);
        HomeData.Weather wx = data.weather;
        if (!Prefs.weather() || Double.isNaN(wx.temp)) return;
        if (data.weatherAt != weatherLineAt || Prefs.version != weatherLinePrefs || !Ui.language.equals(weatherLineLanguage)) {
            weatherLineAt = data.weatherAt;
            weatherLinePrefs = Prefs.version;
            weatherLineLanguage = Ui.language;
            weatherLine = HomeData.degrees(wx.temp) + "  " + HomeData.condition(wx.code, wx.isDay) + "   "
                    + HomeData.highLow(wx, "  ");
        }
        float size = 40 * k, base = L_WEATHER_BASE * k;
        String symbol = Symbols.weather(wx.code, wx.isDay);
        float sw = Symbols.width(getContext(), symbol, size);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, base - Ui.halfCap(lWeather), size, lWeather.getColor());
        c.drawText(weatherLine, x + sw + 16 * k, base, lWeather);
    }

    /**
     * Recommendation card: the title's photo (already at the focused card size, so it stays sharp), rounded corners and
     * a soft shadow; when focused, it grows 5%, rises and lights up its border. Below it, the title (and on the large
     * card, genre · runtime · service).
     */
    private void drawLightCard(Canvas c, int i, Recs.Pick p) {
        RectF base = cardRects[i];
        // Only the targeted card grows (#memory-management, same fix as the shelf and All Apps grid): a per-slot
        // Spring animating every slot at once would need a fresh GPU blur surface for each, every frame.
        float v = i == cardIndex ? cardFocus[i].value : 0, f = Ui.clamp(v), s = 1 + look.m.cardGrow * v;
        float bw = base.width(), bh = base.height(), r0 = look.cardRadius(i) * k;
        float cx = base.centerX(), cy = base.centerY() - look.m.cardLift * k * v;
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        tmp.set(cell);
        tmp.offset(0, (10 + 14 * f) * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.14f, 0.32f) + tone(0.2f, 0.28f) * f));
        shadows.draw(c, tmp, r, DEEP_BLUR * k, shadowDeep);
        Backdrop b = p == null ? null : recs.backdrop(p);
        Bitmap img = b == null ? null : (i == 0 ? b.wide : b.tall);
        float shown = 1;
        if (img != null && b.fresh) {
            // A picture just downloaded fades in over its placeholder (not again while the whole card is already fading
            // in); one read from disk shows at once.
            long now = SystemClock.uptimeMillis();
            if (b.shownAt == 0) b.shownAt = cardMixAt != 0 && now - cardMixAt < look.m.cardFadeMs ? now - look.m.imageFadeMs : now;
            if (!Ui.LEGACY && look.m.imageFadeMs > 0) shown = Ui.clamp((now - b.shownAt) / (float) look.m.imageFadeMs);
            if (shown < 1) {
                cardsAnimating = true;
                wake();
            }
        }
        if (img == null || shown < 1) {
            fill.setShader(null);
            fill.setColor(look.color(Look.PLACEHOLDER, dark()));
            c.drawRoundRect(cell, r, r, fill);
        }
        if (img != null) {
            int alpha = Math.round(255 * shown);
            bitmapPaint.setAlpha(alpha);
            imageFill.setAlpha(alpha);
            roundImage(c, img, cell, r);
            bitmapPaint.setAlpha(255);
            imageFill.setAlpha(255);
            if (shown >= 1) drawBadge(c, cell, pickService(p));
        }
        if (!look.focusRing(c, cell, r, f, dark(), 1) && f > 0.01f) {
            if (lightSheens.length != cardRects.length) {
                lightSheens = new LinearGradient[cardRects.length];
                lightRims = new LinearGradient[cardRects.length];
                lightShaderW = new float[cardRects.length];
            }
            if (lightSheens[i] == null || lightShaderW[i] != bw) {
                lightSheens[i] = new LinearGradient(-bw / 2, -bh / 2, 0, bh / 2, 0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
                lightRims[i] = new LinearGradient(0, -bh / 2, 0, bh / 2, 0xE6FFFFFF, 0x59FFFFFF, Shader.TileMode.CLAMP);
                lightShaderW[i] = bw;
            }
            int alpha = Math.round(255 * f);
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
            sheen.setShader(lightSheens[i]);
            sheen.setAlpha(alpha);
            c.drawRoundRect(tmp, r0, r0, sheen);
            sheen.setAlpha(255);
            rim.setShader(lightRims[i]);
            rim.setAlpha(alpha);
            rim.setStrokeWidth(2.5f * k / s);
            c.drawRoundRect(tmp, r0, r0, rim);
            rim.setStrokeWidth(2.5f * k);
            rim.setAlpha(255);
            c.restore();
        }
        if (p == null) return;
        if (look.cardCaption(c, cell, r, i == 0 ? 1 : 0, p.title, p.meta, f, dark(), 1, 1)) return;
        fitPaint.set(lCardTitle);
        c.drawText(TextUtils.ellipsize(p.title, fitPaint, bw, TextUtils.TruncateAt.END).toString(), base.left, base.bottom + 46 * k, lCardTitle);
        if (i == 0 && p.meta != null && !p.meta.isEmpty()) {
            fitPaint.set(lCardMeta);
            c.drawText(TextUtils.ellipsize(p.meta, fitPaint, bw, TextUtils.TruncateAt.END).toString(), base.left, base.bottom + 80 * k, lCardMeta);
        }
    }

    /**
     * The round buttons (Screensaver, Home Settings and Settings by default; also Live TV, AV input and HDMI input): icon-only glass circles (light by day, smoked at
     * night); the focused one turns white with a dark icon (the tvOS focus) and its name appears below it.
     */
    private void drawLightButtons(Canvas c) {
        applyTone();
        for (int i = 0; i < lightButtons.size(); i++) {
            HomeApps.Item it = lightButtons.get(i);
            RectF r = topRects[3 + i];
            float f = Ui.clamp(topFocus[3 + i].value), s = 1 + look.m.buttonGrow * f, rad = r.width() / 2 * s;
            cell.set(r.centerX() - rad, r.centerY() - rad, r.centerX() + rad, r.centerY() + rad);
            tmp.set(cell);
            tmp.offset(0, (6 + 8 * f) * k);
            shadow.setColor(Ui.alpha(0xFF000000, tone(0.1f, 0.3f) + tone(0.14f, 0.2f) * f));
            shadows.draw(c, tmp, rad, SHADOW_BLUR * k, shadow);
            look.roundButton(c, cell, rad, f, dark());
            Symbols.draw(getContext(), c, it.symbol, cell.centerX(), cell.centerY(), 27 * k * s, look.roundButtonInk(f, dark()));
            if (f > 0.01f && !popOpen) {
                String label = it.label;
                float half = lTip.measureText(label) / 2;
                float x = Math.min(r.centerX(), CONTENT_R * k - half);
                lTip.setAlpha(Math.round(255 * f));
                c.drawText(label, x, r.bottom + (40 + 4 * f) * k, lTip);
            }
        }
        if (sleepText.isEmpty()) return;
        float right = (lightButtons.isEmpty() ? CONTENT_R * k : topRects[3].left) - 28 * k, cy = look.m.buttonCy * k;
        float tw = lQuote.measureText(sleepText), size = lQuote.getTextSize();
        c.drawText(sleepText, right - tw, cy + Ui.halfCap(lQuote), lQuote);
        Symbols.draw(getContext(), c, "bedtime", right - tw - size * 0.9f, cy, size, lQuote.getColor());
    }

    private boolean moveLight(int dx, int dy) {
        switch (zone) {
            case Z_SHELF:
                // Up from an empty shelf too: move()'s older zones (the hero, the corner capsule) aren't drawn any more.
                if (dy >= 0) return false;      // down/sideways: move()'s own Z_SHELF case (left-right, openDrawer()) handles it
                if (cards.isEmpty()) {
                    if ((!picksOn() || Prefs.spotlightOn()) && lightButtons.isEmpty()) return true;   // no tabs and no buttons above
                    zone = Z_TOP;
                    topIndex = picksOn() && !Prefs.spotlightOn() ? tab : 3;
                } else if (Prefs.spotlightOn()) {
                    closeSpotlightPage();
                } else {
                    zone = Z_CARDS;
                    cardIndex = 0;
                }
                break;
            case Z_CARDS:
                if (dx != 0) {
                    if (cards.size() < 2) return true;
                    if (Prefs.spotlightOn()) scrollSpotlight(dx);    // the row scrolls; focus doesn't re-sort to the front
                    else rotateCards(dx);                            // Cards' 3 cards rotate the focused one to the front
                } else if (dy < 0) {
                    zone = Z_TOP;
                    // As on Apple TV: moving up to the tabs focuses the selected tab. Spotlight has no tabs, so Up
                    // from the poster row goes straight to the corner buttons (if any; else moveLight(Z_TOP) with no
                    // buttons and dy<0 already returns true below, keeping focus put).
                    topIndex = Prefs.spotlightOn() ? 3 : tab;
                } else if (Prefs.spotlightOn()) {
                    openSpotlightPage();
                } else {
                    zone = Z_SHELF;
                    if (!shelf.isEmpty()) shelfIndex = nearest(tileRects, tileRects.length, cardRects[cardIndex].centerX());
                }
                break;
            case Z_TOP:
                if (dx != 0) {
                    int next = Math.max(picksOn() && !Prefs.spotlightOn() ? 0 : 3, Math.min(2 + lightButtons.size(), topIndex + dx));
                    if (next == topIndex) return true;
                    topIndex = next;
                    if (topIndex < 3) selectTab(topIndex);   // like Apple TV tabs: focusing a tab selects it
                } else if (dy > 0) {
                    if (cards.isEmpty()) {
                        zone = Z_SHELF;
                    } else {
                        zone = Z_CARDS;
                        cardIndex = 0;
                    }
                } else if (topIndex < 3 && !lightButtons.isEmpty()) {
                    topIndex = 3;   // up from the tabs reaches the corner buttons without walking the row
                } else {
                    return true;
                }
                break;
            default:
                return false;
        }
        retarget(false);
        click.move();
        return true;
    }

    private static int nearest(RectF[] rects, int count, float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < Math.min(count, rects.length); i++) {
            float d = Math.abs(rects[i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    // MARK: - Helpers

    private static float smoothstep(float e0, float e1, float x) {
        float t = Ui.clamp((x - e0) / (e1 - e0));
        return t * t * (3 - 2 * t);
    }

    private static int blend(int a, int b, float f) {
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
    }
}
