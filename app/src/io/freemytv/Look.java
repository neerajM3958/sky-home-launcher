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
    // MARK: - Colour roles of the home screen (the text, symbols and fills it draws with)

    static final int TITLE = 0, TAB = 1, TAB_ON = 2, TIP = 3, META = 4, QUOTE = 5, FAINT = 6, COIN_DIM = 7, INK_PURE = 8,
            DRAWER_ROW = 9, DRAWER_DIM = 10, COIN_GLYPH = 11, UP = 12, DOWN = 13, PLACEHOLDER = 14, ITEM_ON = 15,
            ITEM_OFF = 16, BUTTON_ICON = 17;

    /** The colour for a role, blended for `night`. */
    abstract int color(int role, float night);

    /** Called when the screen's size is known: `k` is its size over 1920, as every layout number is multiplied by. */
    void setSize(int width, int height, float k) {}

    // MARK: - Surfaces of the home screen

    /** The bar behind the shelf. */
    abstract void platter(Canvas c, RectF r, float radius, float night);

    /** A card or round button of the drawer; `lightTint`, `shadowColor` and `drop` are the glass's, a flat look ignores them. */
    abstract void drawerGlass(Canvas c, RectF r, float radius, float lightTint, int shadowColor, float drop, float night);

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

    /** ARGB colours blended (the same blend the screens always used). */
    static int blend(int a, int b, float f) {
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
    }
}
