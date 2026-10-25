package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextPaint;
import android.text.TextUtils;

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
    static final int INVERSE_SURFACE = 13;
    static final int ON_INVERSE_SURFACE = 14;

    // Deep navy and indigo by night, near-white with pale blue by day (the tokens of the design guide).
    private static final int[][] LIGHT = {
        {0xFFF7F8FF, 0xFFEEF0FF, 0xFFE3E7FF, 0xFF171936, 0xFF626984, 0xFF6875E8, 0xFFFFFFFF,
                0xFFBEC6FE, 0xFF0B0F2E, 0xFFE3E7FF, 0xFF3B3F8F, 0xFF9B6200, 0xFFD9DDF0, 0xFF2E3050, 0xFFF1F1FB}
    };
    private static final int[][] DARK = {
        {0xFF080D1B, 0xFF171F38, 0xFF252C4A, 0xFFF5F5FC, 0xFFB8BDD2, 0xFFB5BEFF, 0xFF1A1F5C,
                0xFF9DA5FD, 0xFF0B0F2E, 0xFF252C4A, 0xFFC9B8F5, 0xFFFFC15C, 0xFF303752, 0xFFE3E7FF, 0xFF171936}
    };

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowA = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowB = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint focusRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();
    private final Matrix scrimMatrix = new Matrix();
    private final Shader scrim = new LinearGradient(0, 0, 0, 1, 0x00000000, 0xD9000000, Shader.TileMode.CLAMP);
    private final TextPaint fit = new TextPaint();
    private static final int DEFAULT_TINT = 0xFF6875E8;
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shapePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint vignettePaint = new Paint();
    private static final int STAR = 0xFFFFC21A;
    // The background's big faint shapes (Material 3 Expressive's: circle, squircle, cookie, clover, arch). Each row:
    // kind, centre x (of the width), centre y (of the height), size (of the height), strength, angle, degrees a second,
    // drift x (of the width), drift y (of the height), seconds a drift takes.
    private static final float[][] SHAPES = {
        {0, 0.90f, 0.10f, 0.36f, 0.20f, 0, 0, 0.03f, 0.03f, 47},
        {1, 0.06f, 0.84f, 0.32f, 0.16f, 14, 1.2f, 0.025f, 0.03f, 59},
        {2, 0.80f, 0.82f, 0.24f, 0.15f, 0, 2.4f, 0.03f, 0.025f, 53},
        {3, 0.46f, 0.22f, 0.15f, 0.12f, 0, -3f, 0.04f, 0.03f, 41},
        {4, 0.30f, 1.02f, 0.30f, 0.12f, 0, 0, 0.03f, 0.02f, 67},
    };
    private final Path[] shapes = buildShapes();
    private static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL),
            MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL), BOLD = Typeface.create("sans-serif", Typeface.BOLD);
    private final Path reel = new Path();
    private final Shader scrimLight = new LinearGradient(0, 0, 0, 1, 0x00F7F8FF, 0xF2F7F8FF, Shader.TileMode.CLAMP);
    private final Paint focusGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float focusX, focusY, focusStrength;
    private final float[] hsv = new float[3];
    private int tintFrom = DEFAULT_TINT, tintTo = DEFAULT_TINT, filterColor;
    private long tintAt;
    private ColorFilter filter;
    private Paint heroTitle, heroMeta, cardTitle, cardMeta, tileLabel;
    private Context ctx;
    private float k = 1;
    private int width = 1, height = 1;

    static int paletteColor(int role, boolean dark) {
        int i = Math.max(0, Math.min(LIGHT[0].length - 1, role));
        return dark ? DARK[0][i] : LIGHT[0][i];
    }

    MaterialLook() {
        outline.setStyle(Paint.Style.STROKE);
        focusRing.setStyle(Paint.Style.STROKE);
        // Taller tabs with an icon, bigger cards that carry their own title, and a gentler focus.
        m.tabTop = 150;
        m.tabH = 80;
        m.tabPad = 30;
        m.tabGap = 12;
        m.tabIcon = 34;
        m.cardsTop = 270;
        m.cardH = 460;
        m.tallW = 316;
        m.cardGap = 32;
        m.cardGrow = 0.04f;
        m.cardLift = 4;
        m.buttonGrow = 0.06f;
        m.shelfPad = 0;
        m.shelfGap = 28;
        m.shelfFill = true;
        m.shelfGrow = 0.08f;
        m.animatedBackground = !Ui.LEGACY;
        m.focusGlow = !Ui.LEGACY;
        m.homeBackdrop = !Ui.LEGACY;
        m.homeSharp = 0.55f;
    }

    void setSize(int width, int height) {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        glowA.setShader(new RadialGradient(this.width * 0.12f, this.height * 0.02f,
                Math.max(this.width, this.height) * 0.85f,
                new int[] {0xFFFFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
        glowB.setShader(new RadialGradient(this.width * 0.94f, this.height * 0.42f,
                Math.max(this.width, this.height) * 0.72f,
                new int[] {0xFFFFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
        vignettePaint.setShader(new RadialGradient(this.width / 2f, this.height / 2f,
                (float) Math.hypot(this.width, this.height) * 0.55f,
                new int[] {0x00000000, 0x00000000, 0xFF000000}, new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP));
        focusGlowPaint.setShader(new RadialGradient(0, 0, 640 * k, new int[] {0xFFFFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
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

    @Override void setFocus(float x, float y, float strength) {
        focusX = x;
        focusY = y;
        focusStrength = strength;
    }

    private static Path[] buildShapes() {
        Path circle = new Path(), squircle = new Path(), cookie = new Path(), clover = new Path(), arch = new Path();
        circle.addCircle(0, 0, 1, Path.Direction.CW);
        squircle.addRoundRect(new RectF(-1, -1, 1, 1), 0.45f, 0.45f, Path.Direction.CW);
        for (int i = 0; i <= 96; i++) {
            double a = i * 2 * Math.PI / 96;
            float r = (float) (1 + 0.09 * Math.cos(8 * a));
            float x = (float) Math.cos(a) * r, y = (float) Math.sin(a) * r;
            if (i == 0) cookie.moveTo(x, y);
            else cookie.lineTo(x, y);
        }
        cookie.close();
        clover.addCircle(0.5f, 0, 0.55f, Path.Direction.CW);
        clover.addCircle(-0.5f, 0, 0.55f, Path.Direction.CW);
        clover.addCircle(0, 0.5f, 0.55f, Path.Direction.CW);
        clover.addCircle(0, -0.5f, 0.55f, Path.Direction.CW);
        arch.addArc(new RectF(-1, -1, 1, 1), 180, 180);
        arch.close();
        return new Path[] {circle, squircle, cookie, clover, arch};
    }

    /** The first pick's artwork colour, eased in over a moment (0: back to the palette's indigo). */
    @Override void setAccent(int argb) {
        long now = SystemClock.uptimeMillis();
        tintFrom = tint(now);
        tintTo = argb == 0 ? DEFAULT_TINT : blend(vivid(argb), DEFAULT_TINT, 0.35f);
        tintAt = now;
    }

    private int tint(long now) {
        float f = Math.max(0, Math.min(1, (now - tintAt) / 700f));
        return blend(tintFrom, tintTo, f * f * (3 - 2 * f));
    }

    /** The artwork's hue, at a saturation and brightness that glow on navy. */
    private int vivid(int argb) {
        Color.colorToHSV(argb, hsv);
        hsv[1] = Math.max(0.35f, Math.min(0.85f, hsv[1] * 1.2f));
        hsv[2] = 0.75f;
        return Color.HSVToColor(hsv);
    }

    private void paintBackground(Canvas c, RectF bounds, float night) {
        paint.setShader(null);
        paint.setColor(palette(SURFACE, night));
        c.drawRect(bounds, paint);
        long now = SystemClock.uptimeMillis();
        // By day the tint is paler.
        int tint = tint(now);
        float day = 1 - night;      // by day the shapes stay pale on the near-white background
        tint = blend(tint, DEFAULT_TINT, 0.55f * day);      // and stay closer to the palette's indigo
        if (filter == null || filterColor != tint) {
            filter = new PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN);
            filterColor = tint;
        }
        glowPaint.setColorFilter(filter);
        glowPaint.setShader(glowA.getShader());
        glowPaint.setAlpha(Math.round(255 * 0.27f * (1 - 0.5f * day) * (1 - 0.3f * night)));
        c.drawRect(bounds, glowPaint);
        glowPaint.setShader(glowB.getShader());
        glowPaint.setAlpha(Math.round(255 * 0.17f * (1 - 0.5f * day) * (1 - 0.3f * night)));
        c.drawRect(bounds, glowPaint);
        // Big faint shapes drifting and turning slowly (standing still on older TVs).
        float t = Ui.LEGACY ? 0 : now / 1000f;
        shapePaint.setColorFilter(filter);
        for (int i = 0; i < SHAPES.length; i++) {
            float[] sh = SHAPES[i];
            float phase = t * 2 * (float) Math.PI / sh[9] + i * 1.7f;
            shapePaint.setColor(Ui.alpha(0xFFFFFFFF, sh[4] * (0.9f + 0.1f * night)));
            c.save();
            c.translate(sh[1] * width + (float) Math.sin(phase) * sh[7] * width, sh[2] * height + (float) Math.cos(phase * 1.3f) * sh[8] * height);
            c.rotate(sh[5] + t * sh[6]);
            c.scale(sh[3] * height, sh[3] * height);
            c.drawPath(shapes[(int) sh[0]], shapePaint);
            c.restore();
        }
        if (!Ui.LEGACY) {
            vignettePaint.setAlpha(Math.round(255 * (0.02f + 0.3f * night)));
            c.drawRect(bounds, vignettePaint);
        }
    }

    @Override void attach(Context context) {
        ctx = context;
    }

    @Override void setSize(int width, int height, float k) {
        this.k = k;
        heroTitle = Ui.text(typeface(Ui.displaySemibold), 56 * k, 0xFFFFFFFF);
        heroMeta = Ui.text(typeface(Ui.medium), 30 * k, 0xCCFFFFFF);
        cardTitle = Ui.text(typeface(Ui.displaySemibold), 34 * k, 0xFFFFFFFF);
        cardMeta = Ui.text(typeface(Ui.medium), 23 * k, 0xCCFFFFFF);
        tileLabel = Ui.text(typeface(Ui.semibold), 24 * k, 0xFFFFFFFF);
        setSize(width, height);
    }

    @Override void drawerGlass(Canvas c, RectF r, float radius, float lightTint, int shadowColor, float drop, float night) {
        panel(c, r, radius, night, true);
    }

    @Override int ink(int glass, float night) {
        if ((glass & 0x00FFFFFF) != 0x00FFFFFF) return glass;
        return palette((glass >>> 24) < 0xC0 ? ON_VARIANT : ON_SURFACE, night);
    }

    @Override int inkFocused(int glass, float night) {
        return (glass & 0x00FFFFFF) == 0x001C1C1E ? palette(ON_PRIMARY_CONTAINER, night) : glass;
    }

    @Override int accent(int glass, float night) {
        return palette(PRIMARY, night);
    }

    @Override int warnInk(float night) {
        return palette(TERTIARY, night);
    }

    @Override int focusFill(float night) {
        return palette(PRIMARY_CONTAINER, night);
    }

    @Override void rowFocus(Canvas c, RectF r, float radius, float f, float night) {
        panel(c, r, radius, night, true);
        focused(c, r, radius, f, night);
    }

    /** A Material snackbar: the inverse surface (dark by day, light at night) with a small shadow, corners rounder than the glass's. */
    @Override boolean toast(Canvas c, RectF r, float radius, float night) {
        float corner = Math.min(radius, 20 * k);
        tmp.set(r);
        tmp.offset(0, 4 * k);
        paint.setShader(null);
        paint.setColor(Ui.alpha(0xFF000000, 0.22f));
        c.drawRoundRect(tmp, corner, corner, paint);
        paint.setColor(palette(INVERSE_SURFACE, night));
        c.drawRoundRect(r, corner, corner, paint);
        return true;
    }

    @Override int toastInk(float night) {
        return palette(ON_INVERSE_SURFACE, night);
    }

    @Override boolean saverCard(Canvas c, RectF card, float radius, float night) {
        paint.setShader(null);
        paint.setColor(Ui.alpha(palette(SURFACE_HIGH, night), 0.90f));
        c.drawRoundRect(card, radius, radius, paint);
        return true;
    }

    private TextPaint fitted(Paint p) {
        fit.set(p);
        return fit;
    }

    @Override void tabIcon(Canvas c, int i, float cx, float cy, float size, int color) {
        if (i != 1) {
            Symbols.draw(ctx, c, i == 0 ? "home" : "tv", cx, cy, size, color);
            return;
        }
        // A film reel: a disc with four holes around a hub.
        float r = size * 0.5f;
        reel.reset();
        reel.setFillType(Path.FillType.EVEN_ODD);
        reel.addCircle(cx, cy, r, Path.Direction.CW);
        reel.addCircle(cx, cy, r * 0.16f, Path.Direction.CW);
        for (int h = 0; h < 4; h++) {
            double a = Math.PI / 4 + h * Math.PI / 2;
            reel.addCircle(cx + (float) Math.cos(a) * r * 0.55f, cy + (float) Math.sin(a) * r * 0.55f, r * 0.23f, Path.Direction.CW);
        }
        paint.setShader(null);
        paint.setColor(color);
        c.drawPath(reel, paint);
    }

    /** Bright yellow, as the sun is in the design. */
    @Override int symbolInk(String symbol, int fallback) {
        return "sunny".equals(symbol) ? STAR : fallback;
    }

    /** Roboto, Material's own face (the system's, so nothing extra is bundled). */
    @Override Typeface typeface(Typeface glass) {
        if (glass == Ui.displaySemibold) return BOLD;
        if (glass == Ui.medium || glass == Ui.semibold || glass == Ui.displayMedium) return MEDIUM;
        return REGULAR;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static int fade(int color, float a) {
        return (Math.round((color >>> 24) * Math.max(0, Math.min(1, a))) << 24) | (color & 0x00FFFFFF);
    }

    @Override boolean cardCaption(Canvas c, RectF cell, float radius, float wide, String title, String meta, float f, float night, float reveal) {
        if (wide < 0.5f && meta != null) {
            // Room for the genre and the rating only.
            String[] parts = meta.split(" · ");
            if (parts.length > 2) meta = parts[parts.length - 2] + " · " + parts[parts.length - 1];
        }
        float pad = lerp(20, 32, wide) * k, width = cell.width() - 2 * pad;
        Paint t = heroTitle, d = heroMeta;
        t.setTextSize(lerp(34, 56, wide) * k);
        d.setTextSize(lerp(23, 30, wide) * k);
        // A scrim over the bottom of the artwork keeps the text readable on any picture: dark, but on the small cards
        // by day a light fade with dark text.
        float top = cell.bottom - cell.height() * lerp(0.5f, 0.62f, wide);
        scrimMatrix.setScale(1, cell.bottom - top);
        scrimMatrix.postTranslate(0, top);
        scrim.setLocalMatrix(scrimMatrix);
        scrimLight.setLocalMatrix(scrimMatrix);
        float dayAmt = (1 - wide) * (1 - night);
        if (dayAmt < 0.999f) {
            paint.setShader(scrim);
            paint.setAlpha(Math.round(255 * (1 - dayAmt)));
            c.drawRoundRect(cell.left, top, cell.right, cell.bottom, radius, radius, paint);
        }
        if (dayAmt > 0.001f) {
            paint.setShader(scrimLight);
            paint.setAlpha(Math.round(255 * dayAmt));
            c.drawRoundRect(cell.left, top, cell.right, cell.bottom, radius, radius, paint);
        }
        paint.setShader(null);
        paint.setAlpha(255);
        int ink = fade(blend(0xFFFFFFFF, palette(ON_SURFACE, night), dayAmt), reveal);
        int inkDim = fade(blend(0xCCFFFFFF, palette(ON_VARIANT, night), dayAmt), reveal);
        // The text comes in a beat after the picture: it rises a little as it appears.
        float base = cell.bottom - pad + (1 - reveal) * 18 * k;
        if (meta != null && !meta.isEmpty()) {
            d.setColor(inkDim);
            // The rating's star in yellow, the rest of the line in the secondary ink.
            String line = TextUtils.ellipsize(meta, fitted(d), width, TextUtils.TruncateAt.END).toString();
            int star = line.indexOf('★');
            float x = cell.left + pad;
            if (star < 0) {
                c.drawText(line, x, base, d);
            } else {
                String before = line.substring(0, star);
                c.drawText(before, x, base, d);
                x += d.measureText(before);
                d.setColor(fade(STAR, reveal));
                c.drawText("★", x, base, d);
                x += d.measureText("★");
                d.setColor(inkDim);
                c.drawText(line.substring(star + 1), x, base, d);
            }
            base -= Ui.cap(d) + 12 * k;
        }
        t.setColor(ink);
        c.drawText(TextUtils.ellipsize(title, fitted(t), width, TextUtils.TruncateAt.END).toString(), cell.left + pad, base, t);
        return true;
    }

    /** Navy by night, near-white by day, over the picture: it stays soft and the palette stays in charge. */
    @Override int homeScrim(float night) {
        return fade(palette(SURFACE, night), 0.58f);
    }

    /** A soft light behind whatever has the focus (over the home background's picture too). */
    @Override void overlay(Canvas c, RectF screen, float night) {
        if (focusStrength <= 0.01f || filter == null) return;
        focusGlowPaint.setColorFilter(filter);
        focusGlowPaint.setAlpha(Math.round(255 * 0.42f * (1 - 0.1f * (1 - night)) * focusStrength));
        c.save();
        c.translate(focusX, focusY);
        c.drawCircle(0, 0, 640 * k, focusGlowPaint);
        c.restore();
    }

    @Override boolean centeredClock() {
        return true;
    }

    /** No bar behind the shelf: its tiles are the surfaces. */
    @Override void platter(Canvas c, RectF r, float radius, float night) {}

    @Override boolean allAppsTile(Canvas c, RectF cell, float radius, String label, float f, float night) {
        paint.setShader(null);
        paint.setColor(palette(f > 0.5f ? PRIMARY_CONTAINER : SURFACE_CONTAINER, night));
        c.drawRoundRect(cell, radius, radius, paint);
        float size = cell.height() * 0.3f;
        Paint t = tileLabel;
        t.setTextSize(size * 0.8f);
        t.setColor(palette(f > 0.5f ? ON_PRIMARY_CONTAINER : ON_SURFACE, night));
        float icon = size * 1.1f, gap = size * 0.4f, text = t.measureText(label);
        float left = cell.centerX() - (icon + gap + text) / 2;
        Symbols.draw(ctx, c, "apps", left + icon / 2, cell.centerY(), size, t.getColor());
        c.drawText(label, left + icon + gap, cell.centerY() + Ui.halfCap(t), t);
        return true;
    }

    @Override float cardRadius(int i) {
        return i == 0 ? 40 : i == 1 ? 28 : 48;
    }

    @Override boolean focusRing(Canvas c, RectF cell, float radius, float f, float night) {
        if (f > 0.01f) ring(c, cell, radius, f, night);
        return true;
    }

    @Override boolean tab(Canvas c, RectF r, boolean selected, float focus, float night) {
        if (!selected) return true;
        float rr = r.height() * 0.42f;
        paint.setShader(null);
        paint.setColor(palette(PRIMARY_CONTAINER, night));
        c.drawRoundRect(r, rr, rr, paint);
        focused(c, r, rr, focus, night);
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
            case TAB: return blend(0xFF11163D, 0xFFCCD2F0, night);
            case TITLE: return blend(0xFF0B0F2E, 0xFFFFFFFF, night);
            case WEATHER: return blend(0xFF11163D, 0xFFFFFFFF, night);
            case META: return blend(0xFF3C4169, 0xFFD8DFFE, night);
            case INK_PURE: case ITEM_ON: case BUTTON_ICON: case TIP: return palette(ON_SURFACE, night);
            case TAB_ON: return palette(PRIMARY, night);
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
