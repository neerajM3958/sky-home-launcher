package com.skyhome.launcher;

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
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * What every page draws the same way: rounded pictures, cards (a picture that grows with the focus, with its shadow,
 * sheen and ring), posters (a card with its service badge, progress bar, captions and the dots while it opens), and
 * shadows. Its own paints, made for the size (`layout`); nothing is allocated while drawing.
 */
final class HomePainter {
    /** Blur radii of the soft and the deep shadow (Shadows needs them to draw the same blur on older TVs). */
    static final float SHADOW_BLUR = 26, DEEP_BLUR = 30;

    private final HomeView home;
    private final Catalogs catalogs;
    private final Shadows shadows;
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint imageFill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG), shadowDeep = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG), veil = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG), rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Map<Bitmap, BitmapShader> imageShaders = new WeakHashMap<>();
    private final Matrix imageMatrix = new Matrix();
    private final Rect imageSrc = new Rect();
    private final Path clip = new Path();
    private final RectF cell = new RectF(), tmp = new RectF(), bar = new RectF();
    private final TextPaint fit = new TextPaint();
    private Paint caption, meta;
    private LinearGradient cardSheen;
    private float cardSheenH;
    private float k = 1;

    HomePainter(HomeView home, Catalogs catalogs, Shadows shadows) {
        this.home = home;
        this.catalogs = catalogs;
        this.shadows = shadows;
        border.setStyle(Paint.Style.STROKE);
        rim.setStyle(Paint.Style.STROKE);
    }

    void layout(float k) {
        this.k = k;
        shadow.setMaskFilter(new BlurMaskFilter(SHADOW_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        shadowDeep.setMaskFilter(new BlurMaskFilter(DEEP_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        border.setStrokeWidth(2 * k);
        rim.setStrokeWidth(2.5f * k);
        Look look = home.look;
        caption = Ui.text(look.typeface(Ui.medium), 25 * k, 0xFF1D1D1F);
        meta = Ui.text(look.typeface(Ui.medium), 25 * k, 0xFF6E6E73);
        cardSheen = null;
    }

    // MARK: - Shadows

    /** The soft shadow under a focused row or tile, `opacity` of black. */
    void shadow(Canvas c, RectF r, float radius, float opacity) {
        shadow.setColor(Ui.alpha(0xFF000000, opacity));
        shadows.draw(c, r, radius, SHADOW_BLUR * k, shadow);
    }

    /** The deep shadow under a card, `opacity` of black. */
    void dropShadow(Canvas c, RectF r, float radius, float opacity) {
        shadowDeep.setColor(Ui.alpha(0xFF000000, opacity));
        shadows.draw(c, r, radius, DEEP_BLUR * k, shadowDeep);
    }

    // MARK: - Pictures

    /**
     * `image` over `cell`, cropped to its shape (never stretched), with corners of radius `r`. Android 12+ clips to
     * the rounded shape; on Android 8 to 11 that anti-aliased clip costs the GPU a mask on every frame of a focus
     * animation (measured on a Mi Box 3: most of a 49 ms frame), so there the image is the shader of a rounded
     * rectangle instead, which looks the same.
     */
    void roundImage(Canvas c, Bitmap image, RectF cell, float r) {
        roundImage(c, image, cell, r, 0f);
    }

    /** As above, `shift` moving the crop sideways (a share of its width). */
    void roundImage(Canvas c, Bitmap image, RectF cell, float r, float shift) {
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

    /** Every picture drawn from now on at `alpha` (0 to 255), until it is set back to 255. */
    void imageAlpha(int alpha) {
        bitmapPaint.setAlpha(alpha);
        imageFill.setAlpha(alpha);
    }

    /** As `roundImage`, at `alpha` (0 to 255). */
    void roundImageAlpha(Canvas c, Bitmap image, RectF cell, float r, int alpha) {
        bitmapPaint.setAlpha(alpha);
        imageFill.setAlpha(alpha);
        roundImage(c, image, cell, r);
        bitmapPaint.setAlpha(255);
        imageFill.setAlpha(255);
    }

    // MARK: - Cards and posters

    /** Where `drawCard` last drew, grown with the focus. */
    RectF lastCard() {
        return cell;
    }

    /** A card (16:9, a poster, an app's tile). Focused: it grows by `grow`, with a shadow, a soft sheen and a ring. */
    void drawCard(Canvas c, Bitmap image, RectF base, float radius, float f, float grow) {
        float s = 1 + grow * f;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = radius * s;
        float fc = Ui.clamp(f);
        if (f > 0.01f) {
            tmp.set(cell);
            tmp.offset(0, 16 * k * f);
            shadow(c, tmp, r, 0.55f * fc);
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
        if (!home.look.focusRing(c, cell, r, fc, home.dark(), 1)) {
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

    /** The service's round icon in the corner of a card or poster (`r`, as drawn now). */
    void drawBadge(Canvas c, RectF r, String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        int px = Math.round(34 * k);
        Bitmap b = ServiceBadge.get(home.getContext(), pkg, px, home.badgesReady);
        if (b != null) c.drawBitmap(b, r.left + 10 * k, r.top + 10 * k, null);
    }

    /**
     * One poster of a row (page 3's, page 4's) with its badge (`badge`), progress bar, waiting dots and captions, at
     * focus `f`; `y0` is the row's top. The captions take the look's colors for now (light or dark).
     */
    void drawPoster(Canvas c, RectF poster, Catalogs.Item it, float f, float y0, boolean badge) {
        Look look = home.look;
        float dark = home.dark();
        caption.setColor(look.color(Look.TITLE, dark));
        meta.setColor(look.color(Look.META, dark));
        Bitmap image = catalogs.poster(it, Math.round(PosterRow.POSTER_W * k * 1.25f));
        if (image == null) {
            fill.setShader(null);
            fill.setColor(look.color(Look.PLACEHOLDER, dark));
            c.drawRoundRect(poster, 14 * k, 14 * k, fill);
        }
        drawCard(c, image, poster, 14 * k, f, 0.1f);
        if (badge && image != null) drawBadge(c, cell, home.catalogService(it, f > 0.5f));
        if (it.progress > 0 && image != null) {      // part-way through: a thin bar along the poster's foot
            bar.set(cell.left + 12 * k, cell.bottom - 16 * k, cell.right - 12 * k, cell.bottom - 10 * k);
            fill.setShader(null);
            fill.setColor(0x73FFFFFF);
            c.drawRoundRect(bar, 3 * k, 3 * k, fill);
            bar.right = bar.left + bar.width() * Math.min(1f, it.progress);
            fill.setColor(0xFFFFFFFF);
            c.drawRoundRect(bar, 3 * k, 3 * k, fill);
        }
        long waited = it == home.openingItem() ? SystemClock.uptimeMillis() - home.openingAt() : -1;
        if (waited >= HomeView.OPEN_LOADER_MS) {
            // Waiting for where it streams: the poster dims a little and three dots pulse over it.
            fill.setShader(null);
            fill.setColor(0x80000000);
            c.drawRoundRect(cell, 14 * k, 14 * k, fill);
            fill.setColor(0xFFFFFFFF);
            Ui.dots(c, cell.centerX(), cell.centerY(), 7 * k, waited / 1000f, fill);
        }
        fit.set(caption);
        c.drawText(TextUtils.ellipsize(it.title, fit, poster.width() + 16 * k, TextUtils.TruncateAt.END).toString(),
                poster.left, y0 + (50 + PosterRow.POSTER_H + 40) * k, caption);
        String sub = it.sub != null ? it.sub : it.stars;
        if (sub != null) c.drawText(sub, poster.left, y0 + (50 + PosterRow.POSTER_H + 72) * k, meta);
    }
}
