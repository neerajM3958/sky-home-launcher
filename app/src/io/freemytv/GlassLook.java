package io.freemytv;

import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/** The glass look: frosted glass over the animated sky (and, on the home screen, the first pick's photo), in light or dark. */
final class GlassLook extends Look {
    private static final int WHITE = 0xFFF5F5F7, L_INK = 0xFF1D1D1F, L_DIM = 0xFF6E6E73, L_FAINT = 0xFF8E8E93;
    private static final int UP_COLOR = 0xFF30D158, DOWN_COLOR = 0xFFFF453A, SMOKE = 0xFF111113;
    /** Blur radius of the shadow paint (Shadows needs it to draw the same blur on older TVs). */
    private static final float SHADOW_BLUR = 26;

    /** {by day, at night}, in the order of the role constants. */
    private static final int[][] ROLES = {
        {L_INK, WHITE},             // TITLE
        {L_DIM, 0xFF8E8E93},        // TAB
        {L_INK, WHITE},             // TAB_ON
        {L_INK, WHITE},             // TIP
        {L_DIM, 0xFF98989D},        // META
        {L_FAINT, 0xB8FFFFFF},      // QUOTE
        {L_FAINT, 0x99FFFFFF},      // FAINT
        {0xB38E8E93, 0x8CFFFFFF},   // COIN_DIM
        {L_INK, 0xFFFFFFFF},        // INK_PURE
        {L_DIM, 0xB3FFFFFF},        // DRAWER_ROW
        {L_DIM, 0xADFFFFFF},        // DRAWER_DIM
        {L_DIM, 0xCCFFFFFF},        // COIN_GLYPH
        {0xFF248A3D, UP_COLOR},     // UP
        {0xFFD70015, DOWN_COLOR},   // DOWN
        {0xFFDADADF, 0xFF2C2C2E},   // PLACEHOLDER
        {L_INK, 0xF2FFFFFF},        // ITEM_ON
        {0xFF8E8E93, 0x6BFFFFFF},   // ITEM_OFF
        {L_INK, 0xEBFFFFFF},        // BUTTON_ICON
        {L_DIM, 0xFF98989D},        // WEATHER
    };

    private final Scene scene;
    private final Shadows shadows = new Shadows();
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rowShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();
    private float k = 1;

    GlassLook(Scene scene) {
        this.scene = scene;
    }

    @Override void setSize(int width, int height, float k) {
        this.k = k;
        shadow.setMaskFilter(new BlurMaskFilter(SHADOW_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        rowShadow.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
    }

    @Override int color(int role, float night) {
        int[] pair = ROLES[role];
        return blend(pair[0], pair[1], night);
    }

    /** The day value or the night value of a number, blended. */
    private static float mix(float day, float night, float f) {
        return day + (night - day) * f;
    }

    @Override void platter(Canvas c, RectF r, float radius, float night) {
        shadows.dither = night > 0.5f;
        tmp.set(r);
        tmp.offset(0, 18 * k);
        shadow.setColor(blend(0x1F000000, 0x52000000, night));
        shadows.draw(c, tmp, radius, SHADOW_BLUR * k, shadow);
        // White glass by day; smoked glass at night.
        scene.liquid(c, r, radius, blend(0xFFFFFFFF, SMOKE, night), mix(0.42f, 0.12f, night), mix(0.03f, 0.08f, night),
                mix(30, 34, night) * k, mix(22, 28, night) * k, mix(0.65f, 0.95f, night), 1);
    }

    @Override int warnInk(float night) {
        return 0xFFFFC266;
    }

    @Override int focusFill(float night) {
        return WHITE;
    }

    @Override void rowFocus(Canvas c, RectF r, float radius, float f, float night) {
        tmp.set(r);
        tmp.offset(0, 10 * k * f);
        rowShadow.setColor(Ui.alpha(0xFF000000, 0.4f * f));
        c.drawRoundRect(tmp, radius, radius, rowShadow);
        fill.setShader(null);
        fill.setColor(WHITE);
        fill.setAlpha(Math.round(255 * f));
        c.drawRoundRect(r, radius, radius, fill);
    }

    @Override boolean glassClock() {
        return true;
    }

    @Override int tabInk(float focus, float night) {
        return blend(color(TITLE, night), L_INK, focus);
    }

    @Override void roundButton(Canvas c, RectF cell, float radius, float f, float night) {
        scene.liquid(c, cell, radius, blend(0xFFFFFFFF, SMOKE, night), mix(0.55f, 0.5f, night), mix(0.02f, 0.01f, night), 12 * k, 8 * k,
                mix(0.6f, 0.45f, night), 1 - f);
        if (f > 0.01f) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFFFFFFFF, f));
            c.drawCircle(cell.centerX(), cell.centerY(), radius, fill);
        }
    }

    @Override int roundButtonInk(float f, float night) {
        return blend(color(BUTTON_ICON, night), L_INK, f);
    }

    @Override int gridLabelInk(float f, float night) {
        return blend(0x99FFFFFF, 0xFFFFFFFF, Ui.clamp(f));
    }

    @Override void sheetBackground(Canvas c, RectF screen, float night) {
        c.drawColor(0xA8000000);
    }

    @Override int sheetTitleInk(float night) {
        return 0xFFFFFFFF;
    }

    @Override void drawerGlass(Canvas c, RectF r, float radius, float lightTint, int shadowColor, float drop, float night) {
        shadows.dither = night > 0.5f;
        if (night < 0.999f) {
            tmp.set(r);
            tmp.offset(0, drop);
            shadow.setColor(Ui.alpha(shadowColor, 1 - night));
            shadows.draw(c, tmp, radius, SHADOW_BLUR * k, shadow);
            scene.glass(c, r, radius, 0xFFFFFFFF, lightTint, 0, 0, 0.02f, 1, 1 - night);
        }
        if (night > 0.001f) scene.glass(c, r, radius, SMOKE, 0.4f, 0, 0, 0.02f, 1, night);
    }
}
