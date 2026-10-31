package com.skyhome.launcher;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * The home screen background made from the recommendation's image, built once and kept on disk (Recs' picks cache), so
 * a later start only decodes it:
 *  - the photo cropped to 16:9 at the TV's UI size (1920×1080; 1280×720 on TVs that draw their UI at 720p, like TCL's
 *    1 GB models, where every bitmap here is 2/3 as wide): Spotlight's full-screen background (`image`; each look draws
 *    its own corner treatment live, see Look.spotlightPhoto) and the screensaver's photo;
 *  - the frosted version (192×108, heavily blurred): the background of the frosted glass and of the bottom drawer, and
 *    the home background (lightened by day, darkened at night);
 *  - the Liquid Glass version (480×270, lightly blurred): the photo shows through the shelf and the cards;
 *  - in Cards style, the cards (the photo cropped wide and tall, already at the focused card size).
 * Only what the style draws is built (Spotlight: no cards; Cards: no full photo). The large images live only in GPU
 * memory (HARDWARE): drawing one costs one texture, and the process doesn't hold the pixels.
 *
 * On disk, next to each other (`base` + suffix): `.jpg` the photo, `.frost.png` and `.glass.png` the blurred versions
 * before toning, `.wide.jpg` and `.tall.jpg` the cards. Each is written to a `.part` first, then renamed.
 */
final class Backdrop {
    /** The UI's size against 1920×1080 (at most 1): nothing is built bigger than the screen can show. */
    private static final float UI_SCALE = uiScale();
    static final int W = ui(1920), H = ui(1080), FW = 192, FH = 108, GW = 480, GH = 270;
    /** The cards at their focused size (1.05×): the wide one and the two tall ones. */
    static final int WIDE_W = ui(1092), WIDE_H = ui(464), TALL_W = ui(336), TALL_H = ui(464);

    private static float uiScale() {
        android.util.DisplayMetrics m = android.content.res.Resources.getSystem().getDisplayMetrics();
        int longest = Math.max(m.widthPixels, m.heightPixels);
        return longest > 0 ? Math.min(1f, longest / 1920f) : 1f;
    }

    private static int ui(int px) {
        return Math.round(px * UI_SCALE);
    }
    final Bitmap image;                 // Spotlight: the sharp photo
    final int[] frost;                  // heavily blurred (frosted glass, bottom drawer)
    final Bitmap wide, tall;            // Cards: the photo on the cards
    final int[] lightGlass;             // by day: the glass version, lightened
    final Bitmap lightImage;            // by day: the background (the lightened frosted version, scaled up on screen)
    final int[] nightFrost, nightGlass; // at night: frosted (the bottom drawer's frosted glass) and glass, darkened
    final Bitmap nightImage;            // at night: the background (the darkened frosted version)
    private final Bitmap glassBitmap;   // the lightly blurred version as it is (Spotlight's soft blur, a look's sharp home)
    final Bitmap frostImage;            // the frosted version as it is: the home background of a look that shows the picture
    /** Built from a download in this run (it fades in when it first shows); false when it came from disk. */
    final boolean fresh;
    long shownAt;                       // when it was first drawn on a card (UI thread)

    private Backdrop(Bitmap image, Bitmap wide, Bitmap tall, int[] frost, int[] glass, boolean fresh) {
        this.image = image;
        this.wide = wide;
        this.tall = tall;
        this.frost = frost;
        this.fresh = fresh;
        int[] light = whiten(frost.clone(), 0.82f);
        lightGlass = whiten(glass.clone(), 0.74f);
        nightFrost = darken(frost.clone(), 0.76f);
        nightGlass = darken(glass.clone(), 0.56f);
        lightImage = Bitmap.createBitmap(light, FW, FH, Bitmap.Config.ARGB_8888);
        nightImage = Bitmap.createBitmap(nightFrost, FW, FH, Bitmap.Config.ARGB_8888);
        frostImage = Bitmap.createBitmap(frost, FW, FH, Bitmap.Config.ARGB_8888);
        glassBitmap = gpu(Bitmap.createBitmap(glass, GW, GH, Bitmap.Config.ARGB_8888));
    }

    /** The lightly blurred version as a bitmap. */
    Bitmap glassImage() {
        return glassBitmap;
    }

    /** What it holds (bitmaps, GPU ones included, and the pixel arrays): the picks cache is limited by this. */
    long bytes() {
        long bytes = 0;
        for (Bitmap b : new Bitmap[] {image, wide, tall, lightImage, nightImage, glassBitmap, frostImage})
            if (b != null) bytes += b.getAllocationByteCount();
        for (int[] a : new int[][] {frost, lightGlass, nightFrost, nightGlass})
            if (a != null) bytes += (long) a.length * 4;
        return bytes;
    }

    // MARK: - On disk

    static File photoFile(File base) { return new File(base.getPath() + ".jpg"); }
    private static File frostFile(File base) { return new File(base.getPath() + ".frost.png"); }
    private static File glassFile(File base) { return new File(base.getPath() + ".glass.png"); }
    private static File wideFile(File base) { return new File(base.getPath() + ".wide.jpg"); }
    private static File tallFile(File base) { return new File(base.getPath() + ".tall.jpg"); }

    static void delete(File base) {
        for (File f : new File[] {photoFile(base), frostFile(base), glassFile(base), wideFile(base), tallFile(base)}) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    /** Everything this style draws is on disk: {@link #load} needs no building. */
    static boolean ready(File base, boolean spotlight) {
        return photoFile(base).exists() && frostFile(base).exists() && glassFile(base).exists()
                && (spotlight || wideFile(base).exists() && tallFile(base).exists());
    }

    /** Reads it from disk with no scaling or blurring (background thread), or null when something is missing or unreadable. */
    static Backdrop load(File base, boolean spotlight) {
        if (!ready(base, spotlight)) return null;
        int[] frost = pixels(frostFile(base), FW, FH), glass = pixels(glassFile(base), GW, GH);
        if (frost == null || glass == null) return null;
        Bitmap image = null, wide = null, tall = null;
        if (spotlight) {
            image = hardware(photoFile(base), W, H);
            if (image == null) return null;
        } else {
            wide = hardware(wideFile(base), WIDE_W, WIDE_H);
            tall = hardware(tallFile(base), TALL_W, TALL_H);
            if (wide == null || tall == null) return null;
        }
        return new Backdrop(image, wide, tall, frost, glass, false);
    }

    /** Decodes straight into GPU memory, or null when the file isn't the expected size (built for another UI size). */
    private static Bitmap hardware(File f, int w, int h) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.HARDWARE;
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), opts);
        if (b != null && (b.getWidth() != w || b.getHeight() != h)) b = null;
        return b;
    }

    private static int[] pixels(File f, int w, int h) {
        Bitmap b = BitmapFactory.decodeFile(f.getPath());
        if (b == null || b.getWidth() != w || b.getHeight() != h) return null;
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        b.recycle();
        return px;
    }

    private static void save(Bitmap b, File f, boolean png) throws IOException {
        File part = new File(f.getPath() + ".part");
        try (FileOutputStream out = new FileOutputStream(part)) {
            if (!b.compress(png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 88, out)) throw new IOException("couldn't encode the image");
        }
        if (!part.renameTo(f)) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw new IOException("couldn't save the image");
        }
    }

    private static void save(int[] px, int w, int h, File f) throws IOException {
        Bitmap b = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        try {
            save(b, f, true);
        } finally {
            b.recycle();
        }
    }

    // MARK: - Building

    /**
     * Builds it from the photo (any size: TMDB's original, or the saved 16:9 copy), writes to disk what this style
     * needs and isn't there yet, and returns it ready to draw, or (`keep` false) only saves it and returns null.
     * Recycles `src`.
     */
    static Backdrop make(Bitmap src, boolean spotlight, File base, boolean keep) throws IOException {
        Bitmap out;
        if (src.getWidth() == W && src.getHeight() == H) {
            out = src;
        } else {
            out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            Paint photo = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
            float scale = Math.max((float) W / src.getWidth(), (float) H / src.getHeight());
            float sw = src.getWidth() * scale, sh = src.getHeight() * scale;
            c.drawBitmap(src, null, new RectF((W - sw) / 2, (H - sh) / 2, (W + sw) / 2, (H + sh) / 2), photo);
            src.recycle();
        }
        try {
            if (!photoFile(base).exists()) save(out, photoFile(base), false);
            int[] frost = reduce(out, FW, FH, 3, 3), glass = reduce(out, GW, GH, 2, 2);
            if (!frostFile(base).exists()) save(frost, FW, FH, frostFile(base));
            if (!glassFile(base).exists()) save(glass, GW, GH, glassFile(base));
            Bitmap wide = null, tall = null;
            if (!spotlight) {
                wide = card(out, WIDE_W, WIDE_H);
                tall = card(out, TALL_W, TALL_H);
                if (!wideFile(base).exists()) save(wide, wideFile(base), false);
                if (!tallFile(base).exists()) save(tall, tallFile(base), false);
            }
            if (!keep) {
                if (wide != null) wide.recycle();
                if (tall != null) tall.recycle();
                return null;
            }
            Bitmap image = null;
            if (spotlight) {
                image = gpu(out);
                out = null;
            } else {
                wide = gpu(wide);
                tall = gpu(tall);
            }
            return new Backdrop(image, wide, tall, frost, glass, true);
        } finally {
            if (out != null) out.recycle();
        }
    }

    /** How many times smaller the screensaver photo's frosted version is (the glass math uses the same number). */
    static final int PHOTO_FROST = 6;

    /**
     * Screensaver: the whole original from disk at w × h (center-cropped to that aspect ratio), filtered down, plus the
     * frosted version (1/6, blurred) for the glass clock. `light` receives the luminance (0 to 1) where the caption
     * (bottom left) and the clock (top right) sit, for the scrims. Returns {photo (mutable), frosted}.
     */
    static Bitmap[] photo(java.io.File f, int w, int h, float[] light) throws java.io.IOException {
        android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(f.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new java.io.IOException("unreadable image");
        android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
        opts.inSampleSize = 1;
        while (bounds.outWidth / (opts.inSampleSize * 2) >= w && bounds.outHeight / (opts.inSampleSize * 2) >= h) opts.inSampleSize *= 2;
        Bitmap src = android.graphics.BitmapFactory.decodeFile(f.getPath(), opts);
        if (src == null) throw new java.io.IOException("unreadable image");
        float ratio = (float) w / h;
        int cw = Math.min(src.getWidth(), Math.round(src.getHeight() * ratio));
        int ch = Math.min(src.getHeight(), Math.round(cw / ratio));
        Bitmap b = Bitmap.createBitmap(src, (src.getWidth() - cw) / 2, (src.getHeight() - ch) / 2, cw, ch);
        if (b != src) src.recycle();
        while (b.getWidth() / 2 >= w) {
            Bitmap half = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
            b.recycle();
            b = half;
        }
        Bitmap out = b.getWidth() == w && b.getHeight() == h ? b : Bitmap.createScaledBitmap(b, w, h, true);
        if (out != b) b.recycle();
        if (light != null && light.length >= 2) {
            light[0] = luminance(out, 0, 0.6f, 0.5f, 0.95f);
            light[1] = luminance(out, 0.62f, 0.04f, 0.96f, 0.36f);
        }
        int fw = w / PHOTO_FROST, fh = h / PHOTO_FROST;
        Bitmap frost = Bitmap.createBitmap(reduce(out, fw, fh, 3, 2), fw, fh, Bitmap.Config.ARGB_8888);
        if (!out.isMutable()) {
            Bitmap copy = out.copy(Bitmap.Config.ARGB_8888, true);
            out.recycle();
            out = copy;
        }
        return new Bitmap[] {out, frost};
    }

    /** The center of the photo at the card's aspect ratio, filtered down (halving while possible) to the exact size. */
    private static Bitmap card(Bitmap full, int w, int h) {
        float ratio = (float) w / h;
        int cw = Math.min(full.getWidth(), Math.round(full.getHeight() * ratio));
        int ch = Math.min(full.getHeight(), Math.round(cw / ratio));
        Bitmap b = Bitmap.createBitmap(full, (full.getWidth() - cw) / 2, (full.getHeight() - ch) / 2, cw, ch);
        while (b.getWidth() / 2 >= w) {
            Bitmap half = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
            if (b != full) b.recycle();
            b = half;
        }
        Bitmap out = Bitmap.createScaledBitmap(b, w, h, true);
        if (b != full && b != out) b.recycle();
        return out;
    }

    /** Copies it to GPU memory (cheap to draw; the process doesn't keep the pixels). */
    static Bitmap gpu(Bitmap b) {
        Bitmap g = b.copy(Bitmap.Config.HARDWARE, false);
        if (g == null) return b;
        b.recycle();
        return g;
    }

    /** Blends with a near-black (the darkest gray of Apple's dark-mode backgrounds), keeping the photo's tint. */
    private static int[] darken(int[] px, float amount) {
        float keep = 1 - amount, base = 11 * amount;
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = Math.round(((p >> 16) & 255) * keep + base);
            int g = Math.round(((p >> 8) & 255) * keep + base);
            int b = Math.round((p & 255) * keep + base + 2 * amount);
            px[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        return px;
    }

    private static int[] whiten(int[] px, float amount) {
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = Math.round(((p >> 16) & 255) * (1 - amount) + 255 * amount);
            int g = Math.round(((p >> 8) & 255) * (1 - amount) + 255 * amount);
            int b = Math.round((p & 255) * (1 - amount) + 255 * amount);
            px[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        return px;
    }

    /** Halved at each step (a true average, no aliasing), then blurred (box passes of radius r). */
    private static int[] reduce(Bitmap full, int w, int h, int radius, int passes) {
        Bitmap b = full;
        while (b.getWidth() / 2 >= w * 1.5f) {
            Bitmap half = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
            if (b != full) b.recycle();
            b = half;
        }
        Bitmap small = Bitmap.createScaledBitmap(b, w, h, true);
        if (b != full) b.recycle();
        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        small.recycle();
        int[] tmp = new int[px.length];
        for (int pass = 0; pass < passes; pass++) {
            box(px, tmp, w, h, radius, true);
            box(tmp, px, w, h, radius, false);
        }
        return px;
    }

    /** Moving average of radius r in one direction, repeating the edge pixels. */
    private static void box(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int n = horizontal ? w : h, lines = horizontal ? h : w, step = horizontal ? 1 : w, lineStep = horizontal ? w : 1;
        float k = 1f / (2 * r + 1);
        for (int line = 0; line < lines; line++) {
            int base = line * lineStep;
            float sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int p = in[base + Math.max(0, Math.min(n - 1, i)) * step];
                sr += (p >> 16) & 255;
                sg += (p >> 8) & 255;
                sb += p & 255;
            }
            for (int i = 0; i < n; i++) {
                out[base + i * step] = 0xFF000000 | (Math.round(sr * k) << 16) | (Math.round(sg * k) << 8) | Math.round(sb * k);
                int add = in[base + Math.min(n - 1, i + r + 1) * step], sub = in[base + Math.max(0, i - r) * step];
                sr += ((add >> 16) & 255) - ((sub >> 16) & 255);
                sg += ((add >> 8) & 255) - ((sub >> 8) & 255);
                sb += (add & 255) - (sub & 255);
            }
        }
    }

    /** Average luminance (0 to 1) of a rectangle given as fractions of the image, by sampling. */
    private static float luminance(Bitmap b, float x0, float y0, float x1, float y1) {
        float sum = 0;
        int count = 0;
        for (int j = 0; j < 24; j++) {
            for (int i = 0; i < 32; i++) {
                int x = Math.round((x0 + (x1 - x0) * (i + 0.5f) / 32) * (b.getWidth() - 1));
                int y = Math.round((y0 + (y1 - y0) * (j + 0.5f) / 24) * (b.getHeight() - 1));
                int p = b.getPixel(x, y);
                sum += (0.2126f * ((p >> 16) & 255) + 0.7152f * ((p >> 8) & 255) + 0.0722f * (p & 255)) / 255f;
                count++;
            }
        }
        return sum / count;
    }
}
