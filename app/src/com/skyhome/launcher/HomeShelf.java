package com.skyhome.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Page 1's shelf, like the tvOS 27 Dock: the apps in a glass bar, icons in each brand's color, 48 apart, the bar hugging
 * them with 40 of padding (the focused icon grows 20% without touching its edge). The icons are 16:9, the shape of the
 * apps' TV banners, so those are never stretched: up to 224 × 126, 178 × 100 with eight apps. Tile corners are
 * concentric with the glass (glass radius = tile radius + padding).
 *
 * Moving apps (as on Apple TV): hold OK on a tile, then ← → move it, OK drops it, Back cancels. The lifted tile wiggles;
 * the others slide to their new places (each slot's x offset is a spring that returns to 0).
 */
final class HomeShelf {
    static final float PAD = 40, TILE_RADIUS = 22;
    private static final float TILE_H = 126;
    private static final long WIGGLE_MS = 300;
    private static final float LIFT_GROW = 0.08f, WIGGLE_DEG = 1.5f;
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

    private final HomeTopPage page;
    private final HomeView home;
    List<HomeApps.Item> apps = new ArrayList<>();
    Spring[] focus = new Spring[0];
    RectF[] rects = new RectF[0];
    /** The glass bar. */
    final RectF glass = new RectF();
    int index;
    private float k = 1, tileW, tileH;
    private boolean dirty = true;

    private Spring[] slide = new Spring[0];
    private boolean reordering, reorderAnim;
    private int lifted = -1;
    private long reorderAt;
    private float wiggle;
    private final Spring lift = new Spring(0.3f, 0.72f, 0);
    private List<HomeApps.Item> origin;

    private final Map<String, Bitmap> crispLogos = new HashMap<>();   // logos already sized for the focused tile
    private final android.graphics.PorterDuffColorFilter whiteLogo =
            new android.graphics.PorterDuffColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN);
    private LinearGradient[] shaders = new LinearGradient[0];
    private LinearGradient glow;
    private float shaderH;
    private final Paint tileFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF cell = new RectF(), tmp = new RectF();

    HomeShelf(HomeTopPage page) {
        this.page = page;
        this.home = page.home;
    }

    /** New apps; true when they aren't the same ones (the tiles are made and placed again). */
    boolean set(List<HomeApps.Item> list) {
        cancel(true);    // the list changed under the move (an app installed or removed)
        shaders = new LinearGradient[0];
        boolean same = list.size() == apps.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i).key.equals(apps.get(i).key);
        apps = list;
        dirty = true;
        if (same && focus.length == list.size()) return false;
        focus = new Spring[list.size()];
        slide = new Spring[list.size()];
        rects = new RectF[list.size()];
        for (int i = 0; i < focus.length; i++) {
            focus[i] = new Spring(0.34f, 0.74f, 0);
            slide[i] = new Spring(0.32f, 0.86f, 0);
            rects[i] = new RectF();
        }
        index = Math.min(index, Math.max(0, list.size() - 1));
        return true;
    }

    /** The tiles' size for this many apps: in the TV banners' own 16:9, never taller than `TILE_H`. */
    void size(float k) {
        this.k = k;
        Look.Metrics m = home.look.m;
        int n = apps.size();
        float gap = m.shelfGap * k, full = (HomeGrid.CONTENT_R - HomeGrid.MARGIN) * k;
        float fit = (full - Math.max(0, n - 1) * gap) / Math.max(1, n);
        tileW = m.shelfFill ? Math.min(300 * k, fit) : Math.min(TILE_H * 16f / 9 * k, fit);
        tileH = tileW * 9f / 16;
        crispLogos.clear();
    }

    /** The glass bar's height (its node's). */
    float height() {
        return apps.isEmpty() ? 0 : tileH + 2 * PAD * k;
    }

    /** The glass at `node`'s top, the tiles inside it, centred (or from the margin when they fill it). */
    void place(RectF node, int w) {
        Look.Metrics m = home.look.m;
        int n = apps.size();
        float pad = PAD * k, side = m.shelfPad * k, gap = m.shelfGap * k;
        float width = n * tileW + Math.max(0, n - 1) * gap, left = m.shelfFill ? HomeGrid.MARGIN * k : (w - width) / 2f;
        glass.set(left - side, node.top, left + width + side, node.top + tileH + 2 * pad);
        float x = left, y = node.top + pad;
        for (int i = 0; i < n && i < rects.length; i++) {
            rects[i].set(x, y, x + tileW, y + tileH);
            x += tileW + gap;
        }
        dirty = true;
    }

    HomeApps.Item focused() {
        return apps.isEmpty() ? null : apps.get(index);
    }

    /** ← →: false at either end. */
    boolean move(int dx) {
        int next = Math.max(0, Math.min(apps.size() - 1, index + dx));
        if (next == index) return false;
        index = next;
        return true;
    }

    /** The tile nearest `x` across. */
    int nearest(float x) {
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

    /** Aims the tiles' focus at `focused` (-1: none); true if that changed. */
    boolean aim(int focused, boolean snap) {
        boolean changed = HomeTopPage.aim(focus, focused, snap);
        dirty |= snap || changed;
        return changed;
    }

    /** One frame: the focus, and while an app moves the slides, the lift and the wiggle. True while any moves. */
    boolean tick(float dt, long up) {
        boolean moving = false;
        for (Spring s : focus) moving |= s.step(dt);
        if (reorderAnim) moving |= stepReorder(dt, up);
        dirty |= moving;
        return moving;
    }

    void invalidate() {
        dirty = true;
    }

    boolean takeDirty() {
        boolean d = dirty;
        dirty = false;
        return d;
    }

    // MARK: - Drawing

    /** The glass bar and its shadow (it only changes with the background). */
    void drawGlass(Canvas c) {
        if (apps.isEmpty()) return;
        home.look.platter(c, glass, (TILE_RADIUS + PAD) * k, home.dark());
    }

    void draw(Canvas c) {
        // The focused one last: it grows over its neighbors without being covered by them.
        int top = -1;
        float most = 0.02f;
        for (int i = 0; i < apps.size(); i++) {
            if (focus[i].value > most) {
                most = focus[i].value;
                top = i;
            }
        }
        if (lifted >= 0 && lifted < apps.size() && lift.value > 0.02f) top = lifted;
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < apps.size(); i++) {
                if ((pass == 0) == (i == top)) continue;
                // Moving apps: the tile slides from where it was and the lifted one wiggles (only then: no extra work
                // otherwise).
                float dx = i < slide.length ? slide[i].value : 0;
                float angle = i == lifted ? wiggle * lift.value : 0;
                if (dx == 0 && angle == 0) {
                    drawTile(c, i);
                    continue;
                }
                c.save();
                c.translate(dx, 0);
                c.rotate(angle, rects[i].centerX(), rects[i].centerY());
                drawTile(c, i);
                c.restore();
            }
        }
    }

    /**
     * App icon like the ones in the tvOS 27 Dock: the brand color edge to edge (a soft gradient) with the logo.
     * Focused: it grows 20% from the center, with a deep shadow (it "rises" toward the viewer) and a subtle glow at the
     * top. No border and no reflection, as Apple does it.
     */
    private void drawTile(Canvas c, int i) {
        Look look = home.look;
        HomeApps.Item it = apps.get(i);
        // Lifted to be moved: a little bigger than focused, with a deeper shadow.
        float l = i == lifted ? Math.max(0, lift.value) : 0;
        // Only the targeted tile grows (as the catalog/poster rows already do): with every tile's own Spring
        // animating independently, the outgoing and incoming tiles' shadows both need a fresh, differently-sized
        // offscreen blur surface almost every frame, which Skia's GPU scratch cache can't reuse (#memory-management).
        float f = (i == index ? focus[i].value : 0), fc = Ui.clamp(f), s = 1 + look.m.shelfGrow * f + LIFT_GROW * l;
        RectF base = rects[i];
        float bw = base.width(), bh = base.height(), r0 = TILE_RADIUS * k;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        // Shadow at the already-scaled bounds (cell/r, not a canvas scale): matches drawCard's shape, so repeated
        // frames at the same size let the GPU reuse its offscreen blur surface instead of allocating a new one.
        tmp.set(cell);
        tmp.offset(0, (4 + 14 * f + 6 * l) * k);
        home.painter.dropShadow(c, tmp, r, home.tone(0.14f, 0.3f) + home.tone(0.18f, 0.3f) * fc + 0.14f * Ui.clamp(l));
        int[] style = TILE_STYLE.get(it.key);
        float dark = home.dark();
        if (it.logo != null && style != null) {
            if (shaders.length != apps.size() || shaderH != bh) {
                shaders = new LinearGradient[apps.size()];
                shaderH = bh;
                glow = new LinearGradient(0, -bh / 2, 0, 0, 0x14FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
            }
            if (shaders[i] == null) {
                shaders[i] = new LinearGradient(0, -bh / 2, 0, bh / 2, style[0], style[1], Shader.TileMode.CLAMP);
            }
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
            tileFill.setShader(shaders[i]);
            tileFill.setAlpha(255);
            c.drawRoundRect(tmp, r0, r0, tileFill);
            if (fc > 0.01f) {
                tileFill.setShader(glow);
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
        } else if (it.kind == HomeApps.ALL_APPS && look.allAppsTile(c, cell, r, it.label, fc, dark)) {
            // drawn by the look
        } else {
            if (it.tile != null) home.painter.roundImage(c, it.tile, cell, r);
        }
        look.focusRing(c, cell, r, fc, dark, 1);
    }

    /**
     * Logo at its "optical" size (all with the same amount of ink, plus each one's own adjustment, never past 80% × 62%
     * of the tile), centered and already filtered down to the focused tile size and to the resting one: focused or at
     * rest it is drawn pixel for pixel, without resampling (on a 720p UI, shrinking at draw time made it look jagged).
     */
    private void drawLogo(Canvas c, HomeApps.Item it, RectF box, float s, int mode) {
        float grow = home.look.m.shelfGrow;
        boolean rest = s < 1 + grow / 2;
        Bitmap logo = crispLogo(it, mode == 2 && it.logoDark != null, rest);
        if (logo == null) return;
        float scale = rest ? s : s / (1 + grow);
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
        if (rects.length == 0) return src;
        float grow = home.look.m.shelfGrow;
        String key = it.key + "@" + System.identityHashCode(src) + "@" + Math.round(rects[0].width()) + (rest ? "@rest" : "");
        Bitmap cached = crispLogos.get(key);
        if (cached != null) return cached;
        float bw = rects[0].width() * (1 + grow), bh = rects[0].height() * (1 + grow);
        float lw = src.getWidth(), lh = src.getHeight();
        float scale = (float) Math.sqrt(bw * bh * 0.085f / (lw * lh * Math.max(0.08f, it.logoInk))) * it.logoScale;
        scale = Math.min(scale, Math.min(bw * 0.76f / lw, bh * 0.58f / lh));
        if (rest) scale /= 1 + grow;
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

    // MARK: - Moving apps

    /** An app is lifted and being moved. */
    boolean reordering() {
        return reordering;
    }

    /** The focused tile can be lifted: an app (not All Apps), with another to swap with. */
    boolean canLift() {
        if (index >= apps.size() || apps.size() < 2) return false;
        HomeApps.Item it = apps.get(index);
        return it.kind == HomeApps.APP && it.pkg != null;
    }

    /** Holding OK: the focused app is lifted to be moved; false if it can't be. */
    boolean lift() {
        if (reordering || !canLift()) return false;
        home.wake();
        reordering = reorderAnim = true;
        origin = new ArrayList<>(apps);
        lifted = index;
        reorderAt = SystemClock.uptimeMillis();
        lift.target = 1;
        home.click.open();
        home.showToast(Ui.tr("← → to move · OK to drop · Back to cancel"));
        page.retarget(false);    // TalkBack reads "Moving <app>, position i of n"
        return true;
    }

    /** Swaps the lifted app with its neighbor; both slide from where they are to their new slots. */
    void shift(int dx) {
        int i = index, j = i + dx;
        if (j < 0 || j >= apps.size()) return;
        if (apps.get(j).kind != HomeApps.APP) return;      // "All Apps" stays the last tile
        HomeApps.Item it = apps.get(i);
        apps.set(i, apps.get(j));
        apps.set(j, it);
        Spring f = focus[i];
        focus[i] = focus[j];
        focus[j] = f;
        Spring s = slide[i];
        slide[i] = slide[j];
        slide[j] = s;
        float d = rects[i].left - rects[j].left;
        slide[j].value += d;
        slide[i].value -= d;
        if (shaders.length == apps.size()) {
            LinearGradient g = shaders[i];
            shaders[i] = shaders[j];
            shaders[j] = g;
        }
        index = lifted = j;
        reorderAnim = true;
        home.wake();
        page.retarget(false);
        home.click.move();
    }

    /** OK: drops the app where it is and saves the shelf's new order. */
    void drop() {
        if (!reordering) return;
        reordering = false;
        lift.target = 0;
        boolean changed = false;
        for (int i = 0; i < apps.size() && !changed; i++) changed = apps.get(i) != origin.get(i);
        origin = null;
        home.click.open();
        page.retarget(false);
        if (!changed) return;
        final List<String> order = new ArrayList<>();
        for (HomeApps.Item it : apps) if (it.kind == HomeApps.APP && it.pkg != null) order.add(it.pkg);
        final Context c = home.getContext().getApplicationContext();
        HomeView.LOADER.execute(() -> {
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
            home.handler.post(() -> Prefs.setShelf(save));
        });
    }

    /** Back, or leaving the home screen: the original order comes back (sliding, unless `snap`). */
    void cancel(boolean snap) {
        if (!reordering && !reorderAnim) return;
        if (reordering) {
            reordering = false;
            lift.target = 0;
            int n = apps.size();
            if (origin != null && origin.size() == n) {
                HomeApps.Item up = apps.get(index);
                Spring[] focus = new Spring[n], slide = new Spring[n];
                LinearGradient[] shaders = this.shaders.length == n ? new LinearGradient[n] : this.shaders;
                for (int a = 0; a < n; a++) {
                    int b = apps.indexOf(origin.get(a));
                    focus[a] = this.focus[b];
                    slide[a] = this.slide[b];
                    slide[a].value += rects[b].left - rects[a].left;
                    if (shaders != this.shaders) shaders[a] = this.shaders[b];
                }
                apps = origin;
                this.focus = focus;
                this.slide = slide;
                this.shaders = shaders;
                index = lifted = apps.indexOf(up);
            }
            origin = null;
            page.retarget(snap);
        }
        if (snap) {
            lift.snap(0);
            for (Spring s : slide) s.snap(0);
            wiggle = 0;
            lifted = -1;
            reorderAnim = false;
            dirty = true;
        } else {
            home.wake();
        }
    }

    /** One frame of the move: the slides, the lift and the wiggle. Returns true while any of them moves. */
    private boolean stepReorder(float dt, long up) {
        boolean moving = lift.step(dt);
        for (Spring s : slide) moving |= s.step(dt);
        if (reordering || lift.value > 0.001f) {
            wiggle = WIGGLE_DEG * (float) Math.sin((up - reorderAt) * (2 * Math.PI / WIGGLE_MS));
            moving = true;
        } else {
            wiggle = 0;
        }
        reorderAnim = moving;
        return moving;
    }
}
