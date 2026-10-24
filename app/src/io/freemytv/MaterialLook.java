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

    // Deep navy and indigo by night, near-white with pale blue by day (the tokens of the design guide).
    private static final int[][] LIGHT = {
        {0xFFF7F8FF, 0xFFEEF0FF, 0xFFE3E7FF, 0xFF171936, 0xFF626984, 0xFF6875E8, 0xFFFFFFFF,
                0xFFBEC6FE, 0xFF0B0F2E, 0xFFE3E7FF, 0xFF3B3F8F, 0xFF9B6200, 0xFFD9DDF0}
    };
    private static final int[][] DARK = {
        {0xFF080D1B, 0xFF171F38, 0xFF252C4A, 0xFFF5F5FC, 0xFFB8BDD2, 0xFFB5BEFF, 0xFF1A1F5C,
                0xFF9DA5FD, 0xFF0B0F2E, 0xFF252C4A, 0xFFC9B8F5, 0xFFFFC15C, 0xFF303752}
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
    private final Paint wavePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint vignettePaint = new Paint();
    private static final int STAR = 0xFFFFC21A;
    private static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL),
            MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL), BOLD = Typeface.create("sans-serif", Typeface.BOLD);
    private final Path reel = new Path();
    private final Shader scrimLight = new LinearGradient(0, 0, 0, 1, 0x00F7F8FF, 0xF2F7F8FF, Shader.TileMode.CLAMP);
    private final RectF waveRect = new RectF();
    private final Paint focusGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float focusX, focusY, focusStrength;
    private final float[] hsv = new float[3];
    private Bitmap waves;
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
        buildWaves();
    }

    /** The flowing shapes of the background, once, as a white mask on a small bitmap (tinted and scaled up when drawn). */
    private void buildWaves() {
        int bw = Math.max(64, width / 3), bh = Math.max(36, height / 3);
        if (waves != null) waves.recycle();
        waves = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(waves);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setMaskFilter(new BlurMaskFilter(bw * 0.01f, BlurMaskFilter.Blur.NORMAL));
        Path path = new Path();
        path.moveTo(0.50f * bw, -0.1f * bh);
        path.cubicTo(0.18f * bw, 0.30f * bh, 0.78f * bw, 0.52f * bh, 0.52f * bw, 1.1f * bh);
        path.lineTo(1.1f * bw, 1.1f * bh);
        path.lineTo(1.1f * bw, -0.1f * bh);
        path.close();
        p.setColor(0x30FFFFFF);
        cv.drawPath(path, p);
        path.reset();
        path.moveTo(0.78f * bw, -0.1f * bh);
        path.cubicTo(0.55f * bw, 0.25f * bh, 0.98f * bw, 0.55f * bh, 0.80f * bw, 1.1f * bh);
        path.lineTo(1.1f * bw, 1.1f * bh);
        path.lineTo(1.1f * bw, -0.1f * bh);
        path.close();
        p.setColor(0x26FFFFFF);
        cv.drawPath(path, p);
        path.reset();
        path.moveTo(-0.1f * bw, 0.62f * bh);
        path.cubicTo(0.25f * bw, 0.45f * bh, 0.35f * bw, 0.85f * bh, 0.70f * bw, 1.1f * bh);
        path.lineTo(-0.1f * bw, 1.1f * bh);
        path.close();
        p.setColor(0x1CFFFFFF);
        cv.drawPath(path, p);
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
        if (focusStrength > 0.01f) {
            // A soft light behind whatever has the focus.
            focusGlowPaint.setColorFilter(filter);
            focusGlowPaint.setAlpha(Math.round(255 * 0.42f * (1 - 0.1f * day) * focusStrength));
            c.save();
            c.translate(focusX, focusY);
            c.drawCircle(0, 0, 640 * k, focusGlowPaint);
            c.restore();
        }
        if (waves != null) {
            // Two copies drifting against each other (still on older TVs).
            float t = Ui.LEGACY ? 0 : now / 1000f;
            float dx = (float) Math.sin(t * 2 * Math.PI / 36) * 0.06f * width;
            float dy = (float) Math.cos(t * 2 * Math.PI / 52) * 0.045f * height;
            wavePaint.setColorFilter(filter);
            wavePaint.setAlpha(Math.round(255 * (0.34f + 0.56f * night)));
            waveRect.set(-0.06f * width + dx, -0.06f * height + dy, 1.06f * width + dx, 1.06f * height + dy);
            c.drawBitmap(waves, null, waveRect, wavePaint);
            c.save();
            c.scale(-1, 1, width / 2f, height / 2f);
            waveRect.set(-0.06f * width - dx, -0.06f * height - dy, 1.06f * width - dx, 1.06f * height - dy);
            wavePaint.setAlpha(Math.round(255 * 0.3f * (1 - 0.1f * day)));
            c.drawBitmap(waves, null, waveRect, wavePaint);
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

    @Override boolean cardCaption(Canvas c, RectF cell, float radius, int index, String title, String meta, float f, float night) {
        boolean wide = index == 0;
        if (!wide && meta != null) {
            // Room for the genre and the rating only.
            String[] parts = meta.split(" · ");
            if (parts.length > 2) meta = parts[parts.length - 2] + " · " + parts[parts.length - 1];
        }
        float pad = (wide ? 32 : 20) * k, width = cell.width() - 2 * pad;
        Paint t = wide ? heroTitle : cardTitle, d = wide ? heroMeta : cardMeta;
        // A scrim over the bottom of the artwork keeps the text readable on any picture: dark, but on the small cards
        // by day a light fade with dark text.
        float top = cell.bottom - cell.height() * (wide ? 0.62f : 0.5f);
        scrimMatrix.setScale(1, cell.bottom - top);
        scrimMatrix.postTranslate(0, top);
        scrim.setLocalMatrix(scrimMatrix);
        scrimLight.setLocalMatrix(scrimMatrix);
        float dayAmt = wide ? 0 : 1 - night;
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
        int ink = blend(0xFFFFFFFF, palette(ON_SURFACE, night), dayAmt), inkDim = blend(0xCCFFFFFF, palette(ON_VARIANT, night), dayAmt);
        float base = cell.bottom - pad;
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
                d.setColor(STAR);
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
