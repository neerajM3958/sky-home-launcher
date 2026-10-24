package io.freemytv;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

/** Material 3 Expressive color roles and the shared tonal surfaces used by TV screens. */
final class MaterialLook extends Look {
    static final int SURFACE = 0;
    static final int SURFACE_CONTAINER = 1;
    static final int SURFACE_HIGH = 2;
    static final int ON_SURFACE = 3;
    static final int ON_VARIANT = 4;
    static final int PRIMARY = 5;
    static final int ON_PRIMARY = 6;
    static final int PRIMARY_CONTAINER = 7;
    static final int ON_PRIMARY_CONTAINER = 8;
    static final int SECONDARY_CONTAINER = 9;
    static final int ON_SECONDARY_CONTAINER = 10;
    static final int TERTIARY = 11;
    static final int OUTLINE = 12;

    private static final int[][] LIGHT = {
        {0xFFFEF7FF, 0xFFF3EDF7, 0xFFECE6F0, 0xFF1D1B20, 0xFF49454F, 0xFF6750A4, 0xFFFFFFFF,
                0xFFEADDFF, 0xFF21005D, 0xFFE8DEF8, 0xFF1D192B, 0xFF7D5260, 0xFF79747E}
    };
    private static final int[][] DARK = {
        {0xFF141218, 0xFF211F26, 0xFF2B2930, 0xFFE6E0E9, 0xFFCAC4D0, 0xFFD0BCFF, 0xFF381E72,
                0xFF4F378B, 0xFFEADDFF, 0xFF4A4458, 0xFFE8DEF8, 0xFFEFB8C8, 0xFF938F99}
    };

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowA = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowB = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint focusRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();
    private int width = 1, height = 1;

    static int paletteColor(int role, boolean dark) {
        int i = Math.max(0, Math.min(LIGHT[0].length - 1, role));
        return dark ? DARK[0][i] : LIGHT[0][i];
    }

    MaterialLook() {
        outline.setStyle(Paint.Style.STROKE);
        focusRing.setStyle(Paint.Style.STROKE);
    }

    void setSize(int width, int height) {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        glowA.setShader(new RadialGradient(this.width * 0.12f, this.height * 0.02f,
                Math.max(this.width, this.height) * 0.85f,
                new int[] {0x446750A4, 0x006750A4}, null, Shader.TileMode.CLAMP));
        glowB.setShader(new RadialGradient(this.width * 0.94f, this.height * 0.42f,
                Math.max(this.width, this.height) * 0.72f,
                new int[] {0x22B26F98, 0x00B26F98}, null, Shader.TileMode.CLAMP));
    }

    int palette(int role, float night) {
        int i = Math.max(0, Math.min(LIGHT[0].length - 1, role));
        int a = paletteColor(i, false), b = paletteColor(i, true);
        float f = Math.max(0, Math.min(1, night));
        int r = Math.round(((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
        int g = Math.round(((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
        int bl = Math.round((a & 255) * (1 - f) + (b & 255) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private void paintBackground(Canvas c, RectF bounds, float night) {
        paint.setShader(null);
        paint.setColor(palette(SURFACE, night));
        c.drawRect(bounds, paint);
        glowA.setAlpha(Math.round(255 * (1 - 0.35f * night)));
        glowB.setAlpha(Math.round(255 * (1 - 0.30f * night)));
        c.drawRect(bounds, glowA);
        c.drawRect(bounds, glowB);
        glowA.setAlpha(255);
        glowB.setAlpha(255);
    }

    @Override void setSize(int width, int height, float k) {
        setSize(width, height);
    }

    @Override void platter(Canvas c, RectF r, float radius, float night) {
        panel(c, r, radius, night, false);
    }

    @Override void drawerGlass(Canvas c, RectF r, float radius, float lightTint, int shadowColor, float drop, float night) {
        panel(c, r, radius, night, true);
    }

    @Override float cardRadius(int i) {
        return i == 0 ? 40 : i == 1 ? 28 : 48;
    }

    @Override boolean focusRing(Canvas c, RectF cell, float radius, float f, float night) {
        if (f > 0.01f) ring(c, cell, radius, f, night);
        return true;
    }

    @Override boolean tab(Canvas c, RectF r, boolean selected, float focus, float night) {
        float rr = r.height() * 0.42f;
        paint.setShader(null);
        paint.setColor(palette(selected ? PRIMARY_CONTAINER : SURFACE_CONTAINER, night));
        c.drawRoundRect(r, rr, rr, paint);
        if (selected) focused(c, r, rr, focus, night);
        return true;
    }

    @Override int tabInk(float focus, float night) {
        return palette(ON_PRIMARY_CONTAINER, night);
    }

    @Override void roundButton(Canvas c, RectF cell, float radius, float f, float night) {
        float rr = radius * (1 - 0.48f * f);
        panel(c, cell, rr, night, true);
        focused(c, cell, rr, f, night);
    }

    @Override int roundButtonInk(float f, float night) {
        return palette(f > 0.5f ? ON_PRIMARY_CONTAINER : ON_SURFACE, night);
    }

    @Override int gridLabelInk(float f, float night) {
        return palette(f > 0.5f ? ON_SURFACE : ON_VARIANT, night);
    }

    @Override void sheetBackground(Canvas c, RectF screen, float night) {
        paintBackground(c, screen, night);
    }

    @Override int sheetTitleInk(float night) {
        return palette(ON_SURFACE, night);
    }

    @Override boolean background(Canvas c, RectF screen, float night) {
        paintBackground(c, screen, night);
        return true;
    }

    void panel(Canvas c, RectF r, float radius, float night, boolean elevated) {
        paint.setShader(null);
        paint.setColor(palette(elevated ? SURFACE_HIGH : SURFACE_CONTAINER, night));
        c.drawRoundRect(r, radius, radius, paint);
        outline.setColor(withAlpha(palette(OUTLINE, night), 0.18f));
        outline.setStrokeWidth(1.25f);
        c.drawRoundRect(r, radius, radius, outline);
    }

    void focused(Canvas c, RectF r, float radius, float amount, float night) {
        float f = Math.max(0, Math.min(1, amount));
        if (f <= 0.002f) return;
        tmp.set(r);
        float pad = 4.0f * f;
        tmp.inset(-pad, -pad);
        paint.setShader(null);
        paint.setColor(withAlpha(palette(PRIMARY_CONTAINER, night), f));
        c.drawRoundRect(tmp, radius + pad, radius + pad, paint);
        focusRing.setColor(withAlpha(palette(PRIMARY, night), f));
        focusRing.setStrokeWidth(3.0f * f);
        tmp.inset(1.5f * f, 1.5f * f);
        c.drawRoundRect(tmp, radius + pad, radius + pad, focusRing);
    }

    void ring(Canvas c, RectF r, float radius, float amount, float night) {
        float f = Math.max(0, Math.min(1, amount));
        if (f <= 0.002f) return;
        focusRing.setColor(withAlpha(palette(PRIMARY, night), f));
        focusRing.setStrokeWidth((3.5f + 1.5f * f));
        tmp.set(r);
        tmp.inset(2.25f, 2.25f);
        c.drawRoundRect(tmp, Math.max(0, radius - 2.25f), Math.max(0, radius - 2.25f), focusRing);
    }

    @Override int color(int role, float night) {
        switch (role) {
            case TITLE: case INK_PURE: case ITEM_ON: case BUTTON_ICON: return palette(ON_SURFACE, night);
            case TAB_ON: return palette(PRIMARY, night);
            case TIP: return palette(ON_PRIMARY_CONTAINER, night);
            case UP: return blend(0xFF248A3D, 0xFF30D158, night);
            case DOWN: return blend(0xFFD70015, 0xFFFF453A, night);
            case PLACEHOLDER: return palette(SURFACE_HIGH, night);
            default: return palette(ON_VARIANT, night);
        }
    }

    private static int withAlpha(int color, float alpha) {
        return (Math.max(0, Math.min(255, Math.round(255 * alpha))) << 24) | (color & 0x00FFFFFF);
    }
}
