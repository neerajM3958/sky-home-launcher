package com.skyhome.launcher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.TextUtils;

/**
 * Picks as Cards: tabs (Explore, Movies, Shows) under the weather and three cards with the titles recommended for right
 * now in the selected tab; the background is the first card's photo heavily blurred (Backdrop). ← → move the picks
 * through the cards, in a circle (`carousel`: the row's position in cards, on a spring with a little overshoot,
 * following `carouselTarget`, the steps taken; the pick `a` sits `a - position` cards from the first).
 */
final class PicksCards extends PicksSection {
    private static String[] tabNames;
    private static int tabsGeneration = -1;

    private int tab;
    private Node tabsNode, cardsNode;
    private final RectF[] cardRects = {new RectF(), new RectF(), new RectF()};
    private final Recs.Pick[] prevCards = new Recs.Pick[3];     // the picks the cards showed before the change in progress
    private final RectF fadeBounds = new RectF();
    private final Spring carousel = new Spring(0.44f, 0.8f, 0);
    private int carouselTarget;
    private final RectF slotA = new RectF(), slotB = new RectF(), travel = new RectF();
    private long cardMixAt;         // when the cards last changed their picks (0: never)
    private int cardMixDir;         // 1: the next picks (slide left), -1: the previous ones, 0: just fade
    private boolean cardsAnimating; // a picture is fading in (set while drawing)
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private LinearGradient[] sheens = new LinearGradient[3], rims = new LinearGradient[3];
    private final float[] shaderW = new float[3];

    PicksCards(HomeTopPage page, Recs recs) {
        super(page, recs);
    }

    /** The tab names, made again only when the language changes (they are drawn on every frame). */
    private static String[] tabNames() {
        if (tabsGeneration != Ui.generation) {
            tabNames = new String[] {Ui.tr("Explore"), Ui.tr("Movies"), Ui.tr("Shows")};
            tabsGeneration = Ui.generation;
        }
        return tabNames;
    }

    /** The tabs, then the cards under them on the 5-column grid (the wide one spans 3). */
    @Override Node[] nodes(float k) {
        Look.Metrics m = home.look.m;
        tabsNode = new Node("tabs").size(Node.FILL, m.tabH * k);
        cardsNode = new Node("cards").size(Node.FILL, m.cardH * k);
        return new Node[] {tabsNode, Node.space(0, (m.cardsTop - m.tabTop - m.tabH) * k), cardsNode, Node.space(0, 0).flex()};
    }

    /** Text tabs from the margin (the focus pill extends past the text), and the cards. */
    @Override void placed() {
        Look.Metrics m = home.look.m;
        float pad = m.tabPad * k, tabTop = tabsNode.frame.top, tabBottom = tabsNode.frame.bottom;
        float icon = m.tabIcon > 0 ? (m.tabIcon + 14) * k : 0;
        float x = HomeGrid.MARGIN * k - pad;
        for (int i = 0; i < 3; i++) {
            float tw = Math.max(page.lTabOn.measureText(tabNames()[i]), page.lTab.measureText(tabNames()[i]));
            page.topRects[i].set(x, tabTop, x + icon + tw + 2 * pad, tabBottom);
            x = page.topRects[i].right + m.tabGap * k;
        }
        RectF f = cardsNode.frame;
        float g = m.cardGap * k, tall = m.tallW * k, wide = f.width() - 2 * tall - 2 * g;
        cardRects[0].set(f.left, f.top, f.left + wide, f.bottom);
        cardRects[1].set(cardRects[0].right + g, f.top, cardRects[0].right + g + tall, f.bottom);
        cardRects[2].set(cardRects[1].right + g, f.top, cardRects[1].right + g + tall, f.bottom);
    }

    @Override String kind() {
        return TAB_KIND[tab];
    }

    @Override void changing() {
        startCardTransition(0);
    }

    @Override void restart() {
        cardStart = 0;
        carouselTarget = 0;
        carousel.snap(0);
    }

    @Override int tabs() {
        return 3;
    }

    @Override int tab() {
        return tab;
    }

    @Override void selectTab(int i) {
        if (i == tab) return;
        tab = i;
        cardStart = 0;              // another tab's list: its first card
        carouselTarget = 0;
        carousel.snap(0);
        update();
        recs.refreshIfStale();      // the new tab's cards may still need their details
        page.invalidate();
    }

    @Override boolean move(int dx) {
        if (cards.size() < 2) return false;
        rotateCards(dx);        // the focused card stays first; the picks move through the cards
        return true;
    }

    @Override void autoRotate() {
        rotateCards(1);
    }

    @Override RectF focusedRect() {
        return cardRects[index];
    }

    @Override Recs.Pick focusedPick() {
        return cardAt(index);
    }

    @Override boolean tick(float dt, long up) {
        boolean moving = super.tick(dt, up);
        moving |= cardsAnimating || (cardMixAt != 0 && up - cardMixAt < home.look.m.cardFadeMs + 50);
        cardsAnimating = false;
        return moving | carousel.step(dt);
    }

    @Override void glow(float[] acc) {
        for (int i = 0; i < 3; i++) {
            float v = Ui.clamp(focus[i].value);
            acc[0] += v;
            acc[1] += v * cardRects[i].centerX();
            acc[2] += v * cardRects[i].centerY();
        }
    }

    @Override int a11yCount() {
        return Math.min(3, cards.size());
    }

    @Override boolean a11yRect(int i, RectF out) {
        if (i >= a11yCount()) return false;
        out.set(cardRects[i]);
        return true;
    }

    /** Moves the picks by `step` through the cards, in a circle. */
    private void rotateCards(int step) {
        int n = cards.size();
        if (n < 2) return;
        carouselTarget += step;
        carousel.target = carouselTarget;
        if (Ui.LEGACY) carousel.snap(carouselTarget);
        cardStart = ((carouselTarget % n) + n) % n;
        show();
        home.invalidateAll();
    }

    /** Keeps what the cards show now, to fade it out while the new picks come in (called before they change). */
    private void startCardTransition(int dir) {
        if (Ui.LEGACY) return;
        for (int i = 0; i < prevCards.length; i++) prevCards[i] = cardAt(i);
        cardMixAt = SystemClock.uptimeMillis();
        cardMixDir = dir;
        home.wake();
    }

    // MARK: - Drawing

    @Override void draw(Canvas c) {
        page.drawWeather(c);
        Look look = home.look;
        float dark = home.dark();
        // Text tabs (the selected one in bold, in the main text color); with focus on the tabs, the selected one gets
        // the white pill with a shadow and grows a little (the tvOS focus).
        float focus = Ui.clamp(page.topFocus[tab].value);
        for (int i = 0; i < 3; i++) {
            RectF r = page.topRects[i];
            if (look.tab(c, r, i == tab, focus, dark)) {
                // drawn by the look
            } else if (i == tab && focus > 0.01f) {
                float s = 1 + look.m.tabGrow * focus;
                cell.set(r.centerX() - r.width() * s / 2, r.centerY() - r.height() * s / 2,
                        r.centerX() + r.width() * s / 2, r.centerY() + r.height() * s / 2);
                float rr = cell.height() / 2;
                tmp.set(cell);
                tmp.offset(0, 8 * k * focus);
                home.painter.shadow(c, tmp, rr, home.tone(0.22f, 0.4f) * focus);
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFFFFFFFF, focus));
                c.drawRoundRect(cell, rr, rr, fill);
            }
            Paint p = i == tab ? page.lTabOn : page.lTab;
            if (i == tab) page.lTabOn.setColor(look.tabInk(focus, dark));
            float icon = look.m.tabIcon > 0 ? (look.m.tabIcon + 14) * k : 0, text = p.measureText(tabNames()[i]);
            float left = r.centerX() - (icon + text) / 2;
            if (icon > 0) look.tabIcon(c, i, left + look.m.tabIcon * k / 2, r.centerY(), look.m.tabIcon * k, p.getColor());
            c.drawText(tabNames()[i], left + icon, r.centerY() + Ui.halfCap(p), p);
        }
        int top = -1;
        for (int i = 0; i < 3; i++) if (this.focus[i].value > 0.02f && (top < 0 || this.focus[i].value > this.focus[top].value)) top = i;
        if (cards.isEmpty() && recs.noPicks()) return;      // no picks to show and none coming: no empty cards either
        if (!Ui.LEGACY && cards.size() > 1 && carousel.value != carousel.target) {
            drawCarousel(c);
        } else {
            for (int i = 0; i < 3; i++) if (i != top) drawCardSlot(c, i);
            if (top >= 0) drawCardSlot(c, top);
        }
    }

    /** Card `i`: when the picks just changed, the old one slides out and fades while the new one slides in and fades. */
    private void drawCardSlot(Canvas c, int i) {
        long up = SystemClock.uptimeMillis();
        float t = cardMixAt == 0 ? 1 : (up - cardMixAt) / (float) home.look.m.cardFadeMs;
        if (t >= 1) {
            drawCard(c, i, cardAt(i));
            return;
        }
        cardsAnimating = true;
        float stagger = home.look.m.cardStagger;
        float u = Ui.clamp((t - i * stagger) / (1 - 2 * stagger));
        u = u * u * (3 - 2 * u);
        Recs.Pick old = prevCards[i], now = cardAt(i);
        float d = home.look.m.cardSlide * k * cardMixDir;
        boolean same = old == now;
        if (old != null && !same) drawFaded(c, i, old, 1 - u, -d * u);
        if (now != null) drawFaded(c, i, now, same ? 1 : u, same ? 0 : d * (1 - u));
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** The rectangle of card slot `j` (0 to 2 are the cards; -1 is where a card leaves to, 3 where one comes from). */
    private void slotRect(int j, RectF out) {
        RectF r0 = cardRects[0], r1 = cardRects[1], r2 = cardRects[2];
        float tall = r1.width(), gap = r1.left - r0.right;
        // Further than the inter-card gap (#picks-carousel): already fully off the visible screen from here, but
        // the extra distance means the eased edge-fade (drawTravelCard) has more travel to work with before the
        // entering/exiting card reaches the row, instead of becoming a half-formed, near-opaque card shortly in.
        float stage = gap + tall;
        if (j < 0) out.set(r0.left - stage - tall, r0.top, r0.left - stage, r0.bottom);
        else if (j > 2) out.set(r2.right + stage, r2.top, r2.right + stage + tall, r2.bottom);
        else out.set(cardRects[j]);
    }

    /** The rectangle of a card `x` slots from the first: between the two slots it is moving between. */
    private void slotAt(float x, RectF out) {
        int j = Math.max(-1, Math.min(2, (int) Math.floor(x)));
        float t = x - j;
        slotRect(j, slotA);
        slotRect(j + 1, slotB);
        out.set(lerp(slotA.left, slotB.left, t), lerp(slotA.top, slotB.top, t), lerp(slotA.right, slotB.right, t), lerp(slotA.bottom, slotB.bottom, t));
    }

    /**
     * The corner radius of a card `x` slots from the first, matching `slotAt`'s own slot-to-slot interpolation
     * instead of the picture's wide/tall aspect ratio: slots 1 and 2 are both "tall" (same aspect), but
     * `look.cardRadius` gives them different radii (28 vs 48 in MaterialLook) — interpolating by aspect alone
     * collapses that distinction and makes whichever card settles into slot 2 snap to the wrong radius the instant it
     * stops sliding and switches from this draw path to the resting one (`drawCard`).
     */
    private float cardRadiusAt(float x) {
        int j = Math.max(-1, Math.min(2, (int) Math.floor(x)));
        float t = x - j;
        return lerp(home.look.cardRadius(Math.max(0, Math.min(2, j))), home.look.cardRadius(Math.max(0, Math.min(2, j + 1))), t);
    }

    /** The cards while the picks travel: each pick moves from its slot to the next, changing shape on the way. */
    private void drawCarousel(Canvas c) {
        float pos = carousel.value;
        int first = (int) Math.floor(pos) - 1, last = (int) Math.floor(pos) + 3, nearest = first;
        for (int a = first; a <= last; a++) if (Math.abs(a - pos) < Math.abs(nearest - pos)) nearest = a;
        for (int a = first; a <= last; a++) if (a != nearest) drawTravelCard(c, a, pos);
        drawTravelCard(c, nearest, pos);
    }

    private void drawTravelCard(Canvas c, int a, float pos) {
        float x = a - pos;
        if (x <= -1 || x >= 3) return;
        Look look = home.look;
        float dark = home.dark();
        int n = cards.size();
        Recs.Pick p = cards.get(((a % n) + n) % n);
        slotAt(x, travel);
        float wideRatio = cardRects[0].width() / cardRects[0].height(), tallRatio = cardRects[1].width() / cardRects[1].height();
        float wide = Ui.clamp((travel.width() / travel.height() - tallRatio) / (wideRatio - tallRatio));
        // Eased, not linear: a card that's still mostly off to the side (entering or leaving) stays close to fully
        // transparent for most of its travel, instead of being visibly see-through the whole way across — it only
        // really shows up right as it reaches the row.
        float edge = x < 0 ? 1 + x : x > 2 ? 3 - x : 1;
        float alpha = x < 0 ? 1 - (1 - edge) * (1 - edge) : x > 2 ? edge * edge : 1;
        float f = Ui.clamp(focus[0].value) * Math.max(0, 1 - Math.abs(x));
        float left = pos - carouselTarget;       // how far the row still has to go
        float reveal = a == carouselTarget ? Ui.clamp(1 - Math.abs(left) * 1.6f) : 1;
        float s = 1 + look.m.cardGrow * f, bw = travel.width(), bh = travel.height();
        float cx = travel.centerX(), cy = travel.centerY() - look.m.cardLift * k * f;
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r0 = cardRadiusAt(x) * k, r = r0 * s;
        // The card's shape morphs continuously as it slides, so an offscreen layer sized to it is almost never the
        // same two frames running (and several cards, each a different size, can be on screen at once): that defeats
        // the GPU's render-target cache (#memory-management). So the edge fade is `alpha` applied to each paint below
        // instead of one saveLayerAlpha over the whole card.
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        tmp.offset(0, (10 + 14 * f) * k);
        home.painter.dropShadow(c, tmp, r0, (home.tone(0.14f, 0.32f) + home.tone(0.2f, 0.28f) * f) * alpha);
        c.restore();
        Backdrop b = recs.backdrop(p);
        if (b != null && (b.tall != null || b.wide != null)) {
            // The small card's picture and the hero's cross-fade as the shape changes; each is cropped to fit, and lags a
            // little behind its card while the row moves.
            Bitmap lo = b.tall != null ? b.tall : b.wide, hi = b.wide != null ? b.wide : b.tall;
            float shift = -left * 0.12f;
            home.painter.imageAlpha(Math.round(255 * alpha));
            home.painter.roundImage(c, lo, cell, r, shift);
            if (hi != lo && wide > 0.01f) {
                home.painter.imageAlpha(Math.round(255 * wide * alpha));
                home.painter.roundImage(c, hi, cell, r, shift);
            }
            home.painter.imageAlpha(255);
            home.painter.drawBadge(c, cell, home.pickService(p));
        } else {
            fill.setShader(null);
            fill.setColor(Ui.alpha(look.color(Look.PLACEHOLDER, dark), alpha));
            c.drawRoundRect(cell, r, r, fill);
        }
        if (!look.focusRing(c, cell, r, f, dark, alpha) && f > 0.01f) {
            rim.setShader(null);
            rim.setColor(Ui.alpha(0xFFFFFFFF, 0.7f * f * alpha));
            c.drawRoundRect(cell, r, r, rim);
        }
        if (!look.cardCaption(c, cell, r, wide, p.title, p.meta, f, dark, reveal, alpha)) {
            Paint title = page.lCardTitle, meta = page.lCardMeta;
            int titleAlpha = title.getAlpha();
            title.setAlpha(Math.round(titleAlpha * alpha));
            fitPaint.set(title);
            c.drawText(TextUtils.ellipsize(p.title, fitPaint, bw, TextUtils.TruncateAt.END).toString(), travel.left, travel.bottom + 46 * k, title);
            title.setAlpha(titleAlpha);
            if (wide > 0.5f && p.meta != null && !p.meta.isEmpty()) {
                int old = meta.getAlpha();
                meta.setAlpha(Math.round(old * (wide - 0.5f) * 2 * alpha));
                fitPaint.set(meta);
                c.drawText(TextUtils.ellipsize(p.meta, fitPaint, bw, TextUtils.TruncateAt.END).toString(), travel.left, travel.bottom + 80 * k, meta);
                meta.setAlpha(old);
            }
        }
    }

    private void drawFaded(Canvas c, int i, Recs.Pick pick, float alpha, float dx) {
        if (alpha <= 0.01f) return;
        if (alpha >= 0.995f && dx == 0) {
            drawCard(c, i, pick);
            return;
        }
        RectF r = cardRects[i];
        fadeBounds.set(r.left - 70 * k + dx, r.top - 70 * k, r.right + 70 * k + dx, r.bottom + 160 * k);
        int saved = c.saveLayerAlpha(fadeBounds, Math.round(255 * alpha));
        c.translate(dx, 0);
        drawCard(c, i, pick);
        c.restoreToCount(saved);
    }

    /**
     * Recommendation card: the title's photo (already at the focused card size, so it stays sharp), rounded corners and
     * a soft shadow; when focused, it grows 5%, rises and lights up its border. Below it, the title (and on the large
     * card, genre · runtime · service).
     */
    private void drawCard(Canvas c, int i, Recs.Pick p) {
        Look look = home.look;
        float dark = home.dark();
        RectF base = cardRects[i];
        // Only the targeted card grows (#memory-management, same fix as the shelf and All Apps grid): a per-slot
        // Spring animating every slot at once would need a fresh GPU blur surface for each, every frame.
        float v = i == index ? focus[i].value : 0, f = Ui.clamp(v), s = 1 + look.m.cardGrow * v;
        float bw = base.width(), bh = base.height(), r0 = look.cardRadius(i) * k;
        float cx = base.centerX(), cy = base.centerY() - look.m.cardLift * k * v;
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        tmp.set(cell);
        tmp.offset(0, (10 + 14 * f) * k);
        home.painter.dropShadow(c, tmp, r, home.tone(0.14f, 0.32f) + home.tone(0.2f, 0.28f) * f);
        Backdrop b = p == null ? null : recs.backdrop(p);
        Bitmap img = b == null ? null : (i == 0 ? b.wide : b.tall);
        float shown = 1;
        if (img != null && b.fresh) {
            // A picture just downloaded fades in over its placeholder (not again while the whole card is already fading
            // in); one read from disk shows at once.
            long now = SystemClock.uptimeMillis();
            if (b.shownAt == 0) b.shownAt = cardMixAt != 0 && now - cardMixAt < look.m.cardFadeMs ? now - look.m.imageFadeMs : now;
            if (!Ui.LEGACY && look.m.imageFadeMs > 0) shown = Ui.clamp((now - b.shownAt) / (float) look.m.imageFadeMs);
            if (shown < 1) {
                cardsAnimating = true;
                home.wake();
            }
        }
        if (img == null || shown < 1) {
            fill.setShader(null);
            fill.setColor(look.color(Look.PLACEHOLDER, dark));
            c.drawRoundRect(cell, r, r, fill);
        }
        if (img != null) {
            home.painter.roundImageAlpha(c, img, cell, r, Math.round(255 * shown));
            if (shown >= 1) home.painter.drawBadge(c, cell, home.pickService(p));
        }
        if (!look.focusRing(c, cell, r, f, dark, 1) && f > 0.01f) {
            if (sheens[i] == null || shaderW[i] != bw) {
                sheens[i] = new LinearGradient(-bw / 2, -bh / 2, 0, bh / 2, 0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
                rims[i] = new LinearGradient(0, -bh / 2, 0, bh / 2, 0xE6FFFFFF, 0x59FFFFFF, Shader.TileMode.CLAMP);
                shaderW[i] = bw;
            }
            int alpha = Math.round(255 * f);
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
            sheen.setShader(sheens[i]);
            sheen.setAlpha(alpha);
            c.drawRoundRect(tmp, r0, r0, sheen);
            sheen.setAlpha(255);
            rim.setShader(rims[i]);
            rim.setAlpha(alpha);
            rim.setStrokeWidth(2.5f * k / s);
            c.drawRoundRect(tmp, r0, r0, rim);
            rim.setStrokeWidth(2.5f * k);
            rim.setAlpha(255);
            rim.setShader(null);
            c.restore();
        }
        if (p == null) return;
        if (look.cardCaption(c, cell, r, i == 0 ? 1 : 0, p.title, p.meta, f, dark, 1, 1)) return;
        fitPaint.set(page.lCardTitle);
        c.drawText(TextUtils.ellipsize(p.title, fitPaint, bw, TextUtils.TruncateAt.END).toString(), base.left, base.bottom + 46 * k, page.lCardTitle);
        if (i == 0 && p.meta != null && !p.meta.isEmpty()) {
            fitPaint.set(page.lCardMeta);
            c.drawText(TextUtils.ellipsize(p.meta, fitPaint, bw, TextUtils.TruncateAt.END).toString(), base.left, base.bottom + 80 * k, page.lCardMeta);
        }
    }
}
