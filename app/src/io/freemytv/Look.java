package io.freemytv;

import android.graphics.Canvas;
import android.graphics.RectF;

/**
 * A look: what the screens draw their colours, surfaces and focus with. The screens keep their layout, their text and
 * their animations; everything that makes the glass one glass and the Material one Material is behind this class, so a
 * third look is one more subclass (see Themes for the list, plans/plan-impl-theming.md for the design).
 *
 * `night` is the colour scheme's answer, 0 by day and 1 at night (in between during the transition), so Theme
 * (automatic, light, dark) works the same in every look.
 */
abstract class Look {
    /**
     * Where things sit on the home screen and how far they move, in 1920-wide units (every number is multiplied by the
     * screen's `k`). The defaults are the glass's; a look changes the ones it wants in its constructor.
     */
    static final class Metrics {
        float titleBase = 130;                          // the weather line
        float tabTop = 158, tabH = 68, tabPad = 26, tabGap = 4;
        float tabIcon = 0;                              // size of the icon before a tab's name (0: none, see tabSymbol)
        float buttonSize = 68, buttonGap = 20, buttonCy = 103;
        float cardsTop = 262, cardH = 442, tallW = 320, cardGap = 40;
        float cardGrow = 0.05f, cardLift = 6;           // a focused card grows by this much and rises by this many px
        float tabGrow = 0.05f, buttonGrow = 0.1f;
        // When the picks change (the next ones, another tab): the old cards slide out and fade, the new ones slide in
        // and fade, hero first. Not on Android 8 to 11.
        float cardSlide = 56;                           // how far, in px, the cards travel (0: they only fade)
        int cardFadeMs = 420;
        float cardStagger = 0.14f;                      // share of the time the next card starts after the one before
        int imageFadeMs = 250;                          // a picture fades in over its placeholder when it is ready
        float shelfPad = 40, shelfGap = 48;             // the shelf bar's side padding; the gap between app tiles
        float shelfGrow = 0.2f;                         // a focused shelf tile grows by this much
        boolean focusGlow = false;                      // the background glows under the focused item (redrawn while focus moves)
        boolean animatedBackground = false;             // the background moves by itself (the screen redraws it a few times a second)
        boolean shelfFill = false;                      // tiles span the content width from the margin, instead of centered
    }

    final Metrics m = new Metrics();

    // MARK: - Colour roles of the home screen (the text, symbols and fills it draws with)

    static final int TITLE = 0, TAB = 1, TAB_ON = 2, TIP = 3, META = 4, QUOTE = 5, FAINT = 6, COIN_DIM = 7, INK_PURE = 8,
            DRAWER_ROW = 9, DRAWER_DIM = 10, COIN_GLYPH = 11, UP = 12, DOWN = 13, PLACEHOLDER = 14, ITEM_ON = 15,
            ITEM_OFF = 16, BUTTON_ICON = 17, WEATHER = 18;

    /** The colour for a role, blended for `night`. */
    abstract int color(int role, float night);

    /** Where the focus is (the centre of the focused item, `strength` 0 when nothing is focused), for a look that glows there. */
    void setFocus(float x, float y, float strength) {}

    /** The colour of the first pick's artwork (opaque), or 0 for none: a look may tint its background with it. */
    void setAccent(int argb) {}

    /** The average colour of blurred pixels (a sample of them), opaque. */
    static int average(int[] pixels) {
        if (pixels == null || pixels.length == 0) return 0;
        long r = 0, g = 0, b = 0;
        int n = 0, step = Math.max(1, pixels.length / 512);
        for (int i = 0; i < pixels.length; i += step) {
            r += (pixels[i] >> 16) & 255;
            g += (pixels[i] >> 8) & 255;
            b += pixels[i] & 255;
            n++;
        }
        return 0xFF000000 | ((int) (r / n) << 16) | ((int) (g / n) << 8) | (int) (b / n);
    }

    /** The screen's context, for a look that draws symbols (called before setSize). */
    void attach(android.content.Context context) {}

    /** Called when the screen's size is known: `k` is its size over 1920, as every layout number is multiplied by. */
    void setSize(int width, int height, float k) {}

    // MARK: - Surfaces of the home screen

    /** The bar behind the shelf. */
    abstract void platter(Canvas c, RectF r, float radius, float night);

    /** A card or round button of the drawer; `lightTint`, `shadowColor` and `drop` are the glass's, a flat look ignores them. */
    abstract void drawerGlass(Canvas c, RectF r, float radius, float lightTint, int shadowColor, float drop, float night);

    /** The icon before the name of top tab `i` (centered at cx, cy; `size` is a symbol's point size). Needs `m.tabIcon` > 0. */
    void tabIcon(Canvas c, int i, float cx, float cy, float size, int color) {}

    /** The colour of a symbol where the look gives it one of its own (the sun), else `fallback`. */
    int symbolInk(String symbol, int fallback) { return fallback; }

    /** The face for text the screen draws in `glass`'s (Inter's weights). */
    android.graphics.Typeface typeface(android.graphics.Typeface glass) { return glass; }

    /**
     * The title and details of a pick card, drawn by the look over the card (`cell` is the card now, `f` how focused
     * it is). `wide` is 0 for a small card and 1 for the hero (a card on its way from one to the other is in between), and
     * `reveal` how far its text has come in (0 to 1: it arrives a beat after the picture). Returns false when the look
     * draws none, and the screen writes them under the card.
     */
    boolean cardCaption(Canvas c, RectF cell, float radius, float wide, String title, String meta, float f, float night, float reveal) {
        return false;
    }

    /** The All Apps tile of the shelf. Returns false when the look draws none of its own (the tile's picture is used). */
    boolean allAppsTile(Canvas c, RectF cell, float radius, String label, float f, float night) { return false; }

    /** Corner radius of pick card `i` (0 the wide one), in 1920-wide units. */
    float cardRadius(int i) { return 18; }

    /**
     * The focus of a tile or card as this look draws it, on top of the picture. Returns false when the look has none of
     * its own, and the screen draws the glass's (sheen and rim).
     */
    boolean focusRing(Canvas c, RectF cell, float radius, float f, float night) { return false; }

    /** A top tab's surface. Returns false when the look draws none (the screen draws the glass pill for the selected one). */
    boolean tab(Canvas c, RectF r, boolean selected, float focus, float night) { return false; }

    /** The ink of the selected tab, `focus` being how far the tab is focused. */
    abstract int tabInk(float focus, float night);

    /** A round button of the top row (cell: its size now, f: focus 0..1). */
    abstract void roundButton(Canvas c, RectF cell, float radius, float f, float night);

    abstract int roundButtonInk(float f, float night);

    /** The name under an app of All Apps. */
    abstract int gridLabelInk(float f, float night);

    /** Fills the screen behind All Apps. */
    abstract void sheetBackground(Canvas c, RectF screen, float night);

    abstract int sheetTitleInk(float night);

    /** The whole screen's background. Returns false when the look has none of its own (the glass draws the photo and sky). */
    boolean background(Canvas c, RectF screen, float night) { return false; }

    // MARK: - Settings, episodes and screensaver (the screens drawn in white over the sky)

    /**
     * The ink for a colour the glass draws in white at some strength (`0xD9FFFFFF`...): the glass keeps it, a look with
     * a light surface answers with its own. Opaque for a look that recolours; use inkAlpha() to keep the strength.
     */
    int ink(int glass, float night) { return glass; }

    /** `ink()` with the alpha of `glass` kept (a row fading in or out). */
    final int inkAlpha(int glass, float night) {
        return (glass & 0xFF000000) | (ink(glass, night) & 0x00FFFFFF);
    }

    /** The ink a row takes when it is focused, for a glass ink that is dark (0x1C1C1E) on the glass's white focus. */
    int inkFocused(int glass, float night) { return glass; }

    final int inkFocusedAlpha(int glass, float night) {
        return (glass & 0xFF000000) | (inkFocused(glass, night) & 0x00FFFFFF);
    }

    /** An accent in the ink of a symbol (the glass keeps its own colour). */
    int accent(int glass, float night) { return glass; }

    /** The colour of a warning (new episode, offline). */
    abstract int warnInk(float night);

    /** The colour the focus fills with (settings rows, episode rows). */
    abstract int focusFill(float night);

    /** The focus of a settings row: `r` is the row's rectangle now, `f` how focused it is. */
    abstract void rowFocus(Canvas c, RectF r, float radius, float f, float night);

    /** The card behind the screensaver's clock or corner. Returns false when the look has none (the glass clock is the surface). */
    boolean saverCard(Canvas c, RectF card, float radius, float night) { return false; }

    /** Whether the screensaver's clock sits in the middle of the photo (darker corners around it), instead of the glass clock in the corner. */
    boolean centeredClock() { return false; }

    /** Whether the screensaver's clock is drawn as glass. */
    boolean glassClock() { return false; }

    /** ARGB colours blended (the same blend the screens always used). */
    static int blend(int a, int b, float f) {
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
    }
}
