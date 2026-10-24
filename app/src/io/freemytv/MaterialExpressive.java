package io.freemytv;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

/** Material 3 Expressive color roles and the shared tonal surfaces used by TV screens. */
final class MaterialExpressive {
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

    MaterialExpressive() {
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

    int color(int role, float night) {
        int i = Math.max(0, Math.min(LIGHT[0].length - 1, role));
        int a = paletteColor(i, false), b = paletteColor(i, true);
        float f = Math.max(0, Math.min(1, night));
        int r = Math.round(((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
        int g = Math.round(((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
        int bl = Math.round((a & 255) * (1 - f) + (b & 255) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    void background(Canvas c, RectF bounds, float night) {
        paint.setShader(null);
        paint.setColor(color(SURFACE, night));
        c.drawRect(bounds, paint);
        glowA.setAlpha(Math.round(255 * (1 - 0.35f * night)));
        glowB.setAlpha(Math.round(255 * (1 - 0.30f * night)));
        c.drawRect(bounds, glowA);
        c.drawRect(bounds, glowB);
        glowA.setAlpha(255);
        glowB.setAlpha(255);
    }

    void panel(Canvas c, RectF r, float radius, float night, boolean elevated) {
        paint.setShader(null);
        paint.setColor(color(elevated ? SURFACE_HIGH : SURFACE_CONTAINER, night));
        c.drawRoundRect(r, radius, radius, paint);
        outline.setColor(withAlpha(color(OUTLINE, night), 0.18f));
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
        paint.setColor(withAlpha(color(PRIMARY_CONTAINER, night), f));
        c.drawRoundRect(tmp, radius + pad, radius + pad, paint);
        focusRing.setColor(withAlpha(color(PRIMARY, night), f));
        focusRing.setStrokeWidth(3.0f * f);
        tmp.inset(1.5f * f, 1.5f * f);
        c.drawRoundRect(tmp, radius + pad, radius + pad, focusRing);
    }

    void ring(Canvas c, RectF r, float radius, float amount, float night) {
        float f = Math.max(0, Math.min(1, amount));
        if (f <= 0.002f) return;
        focusRing.setColor(withAlpha(color(PRIMARY, night), f));
        focusRing.setStrokeWidth((3.5f + 1.5f * f));
        tmp.set(r);
        tmp.inset(2.25f, 2.25f);
        c.drawRoundRect(tmp, Math.max(0, radius - 2.25f), Math.max(0, radius - 2.25f), focusRing);
    }

    private static int withAlpha(int color, float alpha) {
        return (Math.max(0, Math.min(255, Math.round(255 * alpha))) << 24) | (color & 0x00FFFFFF);
    }
}
