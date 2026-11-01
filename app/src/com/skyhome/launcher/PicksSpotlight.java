package com.skyhome.launcher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextPaint;
import android.text.TextUtils;

import java.util.List;

/**
 * Picks as Spotlight (stock Google TV's "Top picks for you"): no tabs; a full-screen sharp photo of the focused pick
 * (`Look.spotlightPhoto`, each look's own corner treatment), its title and meta over the dark side, a heading, and a
 * poster row that drives it, resting on the shelf. Picks come from Explore's pool. The row scrolls (`rowShift`, the
 * catalog rows' pattern) instead of re-sorting the focused pick to the front, in a circle: `cardStart` is the focused
 * column, never clamped (only lookups wrap), so there's always a previous and a next pick. One column more than
 * `VISIBLE` is drawn at each end, so the next one peeks in. Positions tuned on the TV (1920-wide units).
 */
final class PicksSpotlight extends PicksSection {
    private static final float TITLE_ABOVE = 150, META_GAP = 46, HEADING_ABOVE = 24, TITLE_W = 900,
            POSTER_W = 140, POSTER_H = 210, POSTER_GAP = 20, POSTER_RADIUS = 22;
    /** The block (title, heading, row), its row's foot `SHELF_GAP` above the shelf's glass. */
    private static final float BLOCK_H = 500, SHELF_GAP = 52;
    private static final int VISIBLE = 11;

    private Node block;
    /** The poster row's top: the heading and the title hang from it. */
    private float rowTop;
    private final Spring rowShift = new Spring(0.34f, 1f, 0);
    private final RectF focused = new RectF(), poster = new RectF(), screen = new RectF();
    private Paint title, meta, heading;
    private Backdrop lastBackdrop;
    private int warmFor = Integer.MIN_VALUE;
    private Shader corner;
    private final Paint cornerPaint = new Paint();
    private float cornerW;
    private String wrapSrc;
    private float wrapW;
    private int wrapGen = -1, wrapLineCount = 1;
    private final String[] wrapLines = new String[2];

    PicksSpotlight(HomeTopPage page, Recs recs) {
        super(page, recs);
    }

    @Override Node[] nodes(float k) {
        block = new Node("spotlight").size(Node.FILL, BLOCK_H * k);
        return new Node[] {Node.space(0, 0).flex(), block, Node.space(0, SHELF_GAP * k)};
    }

    @Override void placed() {
        rowTop = block.frame.bottom - POSTER_H * k;
        screen.set(0, 0, page.w, page.h);
    }

    /**
     * The title, meta and heading sit straight on the photo (inside its dark corner treatment, in both looks, by day or
     * by night), not on the page's background the look's colors are for: fixed white.
     */
    @Override void makePaints(float k) {
        super.makePaints(k);
        title = Ui.text(Ui.displaySemibold, 64 * k, 0xFFFFFFFF);
        title.setLetterSpacing(0.004f);
        meta = Ui.text(Ui.medium, 30 * k, 0xD1FFFFFF);
        heading = Ui.text(Ui.semibold, 30 * k, 0xE8FFFFFF);
        for (Paint p : new Paint[] {title, meta, heading}) p.setShadowLayer(22 * k, 0, 2 * k, 0x59000000);
    }

    /** Only the full-screen photos of the focus ±1 are kept (Recs.nearby); the posters load through `Recs.poster`. */
    @Override void preload() {
        for (int i = cardStart - 1; i <= cardStart + 1; i++) {
            Recs.Pick p = posterAt(i);
            if (p != null) recs.backdrop(p);
        }
    }

    @Override void restart() {
        cardStart = 0;
        rowShift.snap(0);
    }

    /** Column `col` of the row: always the same pick (the row doesn't re-sort), wrapping around. */
    private Recs.Pick posterAt(int col) {
        int i = wrap(col);
        return i < 0 ? null : cards.get(i);
    }

    @Override boolean move(int dx) {
        if (cards.size() < 2) return false;
        scroll(dx);
        return true;
    }

    @Override void autoRotate() {
        if (cards.size() >= 2) scroll(1);
    }

    /** The row slides to the next column with the catalog rows' smoothness; only the spring's target jumps. */
    private void scroll(int dx) {
        cardStart += dx;
        rowShift.target = cardStart;
        show();
        home.invalidateAll();
    }

    @Override RectF focusedRect() {
        return focused;
    }

    @Override Recs.Pick focusedPick() {
        return posterAt(cardStart);
    }

    @Override boolean tick(float dt, long up) {
        return super.tick(dt, up) | rowShift.step(dt);
    }

    @Override int a11yCount() {
        return cards.isEmpty() ? 0 : 1;
    }

    @Override String a11yText(int i) {
        return pickText(focusedPick());
    }

    @Override boolean a11yRect(int i, RectF out) {
        if (cards.isEmpty()) return false;
        out.set(focused);
        return true;
    }

    /** Breaks `text` at spaces into at most two lines no wider than `maxW` (the second ellipsized if it still overflows). */
    private void wrapTitle(String text, TextPaint p, float maxW) {
        int cut = p.breakText(text, true, maxW, null);
        if (cut >= text.length()) {
            wrapLines[0] = text;
            wrapLineCount = 1;
            return;
        }
        int sp = text.lastIndexOf(' ', cut);
        if (sp <= 0) sp = cut;
        wrapLines[0] = text.substring(0, sp).trim();
        wrapLines[1] = TextUtils.ellipsize(text.substring(sp).trim(), p, maxW, TextUtils.TruncateAt.END).toString();
        wrapLineCount = 2;
    }

    /** Explore's bigger pool: the same list Recs loads details and photos for (Recs.nearby), or past pick_count the
     * row and the photos part ways and a title's photo is never loaded. */
    @Override List<Recs.Pick> list() {
        return recs.now(kind(), Recs.SPOTLIGHT_POOL);
    }

    /** The photo, or the look's background while there's none: always the whole screen. */
    @Override boolean coversScreen() {
        return true;
    }

    /** The photo first: the weather comes after it on the same canvas (in Cards the photo is the background's layer). */
    @Override void draw(Canvas c) {
        drawSpotlight(c);
        page.drawWeather(c);
    }

    private void drawSpotlight(Canvas c) {
        Look look = home.look;
        float dark = home.dark(), margin = HomeGrid.MARGIN * k;
        int w = page.w;
        Recs.Pick pick = posterAt(cardStart);
        Backdrop b = pick == null ? null : recs.backdrop(pick);
        // Neighbours load ahead (backdrop() starts the load and returns null until it's ready), and while the focused
        // pick's own photo is still loading the previous one stays up rather than a blank background.
        if (pick != null && cardStart != warmFor) {
            warmFor = cardStart;
            for (int d = -1; d <= 1; d++) if (d != 0) recs.backdrop(posterAt(cardStart + d));
        }
        if (b != null) lastBackdrop = b;
        else if (lastBackdrop != null) b = lastBackdrop;
        look.spotlightPhoto(c, screen, b, dark);
        // A dark corner behind the top-right buttons, so they stay readable over any photo.
        if (corner == null || cornerW != w) {
            cornerW = w;
            corner = new RadialGradient(w, 0, 760 * k, new int[] {0xB8000000, 0x70000000, 0},
                    new float[] {0, 0.5f, 1}, Shader.TileMode.CLAMP);
        }
        cornerPaint.setShader(corner);
        c.drawRect(screen, cornerPaint);
        if (pick != null) {
            float titleW = Math.min(TITLE_W, HomeGrid.CONTENT_R - HomeGrid.MARGIN) * k;
            fitPaint.set(title);
            if (!pick.title.equals(wrapSrc) || wrapW != titleW || wrapGen != Ui.generation) {
                wrapSrc = pick.title;
                wrapW = titleW;
                wrapGen = Ui.generation;
                wrapTitle(wrapSrc, fitPaint, titleW);
            }
            // A long title wraps to a second line (the last line stays on the baseline the meta hangs from, so the
            // block grows upward); only a title too long for two lines is cut with an ellipsis.
            float lineH = 72 * k;
            for (int i = 0; i < wrapLineCount; i++)
                c.drawText(wrapLines[i], margin, rowTop - TITLE_ABOVE * k - (wrapLineCount - 1 - i) * lineH, title);
            if (pick.meta != null && !pick.meta.isEmpty()) {
                fitPaint.set(meta);
                String text = TextUtils.ellipsize(pick.meta, fitPaint, titleW, TextUtils.TruncateAt.END).toString();
                c.drawText(text, margin, rowTop + (META_GAP - TITLE_ABOVE) * k, meta);
            }
        }
        c.drawText(Ui.tr("Top picks for you"), margin, rowTop - HEADING_ABOVE * k, heading);
        float pw = POSTER_W * k, ph = POSTER_H * k, gap = POSTER_GAP * k, r = POSTER_RADIUS * k;
        // Circular: one column peeks in at the start too, not just the end (the row never runs out in either direction).
        int first = (int) Math.floor(rowShift.value) - 1, last = first + VISIBLE + 2;
        for (int col = first; col <= last; col++) {
            Recs.Pick p = posterAt(col);
            if (p == null) continue;
            float x = margin + (col - rowShift.value) * (pw + gap);
            poster.set(x, rowTop, x + pw, rowTop + ph);
            if (poster.right < 0 || poster.left > w) continue;      // fully off-screen: not even partially drawn
            if (col == cardStart) focused.set(poster);
            float f = col == cardStart ? Ui.clamp(focus[0].value) : 0;
            float s = 1 + look.m.cardGrow * f;
            float cx = poster.centerX(), cy = poster.centerY() - look.m.cardLift * k * f;
            cell.set(cx - pw * s / 2, cy - ph * s / 2, cx + pw * s / 2, cy + ph * s / 2);
            float rr = r * s;
            // TMDB's poster art, not the textless backdrop: the title is already in the image, so no caption over it.
            Bitmap img = recs.poster(p, Math.round(pw));
            if (img != null) {
                home.painter.roundImage(c, img, cell, rr);
            } else {
                fill.setShader(null);
                fill.setColor(look.color(Look.PLACEHOLDER, dark));
                c.drawRoundRect(cell, rr, rr, fill);
            }
            if (!look.focusRing(c, cell, rr, f, dark, 1) && f > 0.01f) {
                rim.setShader(null);
                rim.setColor(Ui.alpha(0xFFFFFFFF, 0.7f * f));
                c.drawRoundRect(cell, rr, rr, rim);
            }
        }
    }
}
