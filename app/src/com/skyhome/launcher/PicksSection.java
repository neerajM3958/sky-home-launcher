package com.skyhome.launcher;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;

import java.util.ArrayList;
import java.util.List;

/**
 * The picks on page 1, in the style chosen in Settings > TMDB > "Picks carousel" (`Prefs.picksStyle()`, read once: a
 * change of settings makes the home screen again): `PicksOff`, `PicksCards` or `PicksSpotlight`. Each one gives page 1's
 * column its nodes, draws in the top layer, and moves the focus along its row. They share the list of picks (`cards`,
 * from Recs) and which one comes first (`cardStart`); the first one's photo is the background.
 */
abstract class PicksSection {
    static final String[] TAB_KIND = {null, "movie", "tv"};

    final HomeTopPage page;
    final HomeView home;
    final Recs recs;
    List<Recs.Pick> cards = new ArrayList<>();
    /** With more than 3 picks: the one in the first card; the others follow, in a circle. */
    int cardStart;
    /** The focused card (0 to 2). */
    int index;
    final Spring[] focus = new Spring[3];
    protected float k = 1;
    protected final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    protected final RectF cell = new RectF(), tmp = new RectF();
    protected final TextPaint fitPaint = new TextPaint();

    PicksSection(HomeTopPage page, Recs recs) {
        this.page = page;
        this.home = page.home;
        this.recs = recs;
        for (int i = 0; i < focus.length; i++) focus[i] = new Spring(0.32f, 0.78f, 0);
    }

    /** The section for the person's picks style. */
    static PicksSection create(HomeTopPage page, Recs recs) {
        if (!page.home.picksOn()) return new PicksOff(page, recs);
        return Prefs.spotlightOn() ? new PicksSpotlight(page, recs) : new PicksCards(page, recs);
    }

    /** It paints the whole screen, opaque (Spotlight's photo): nothing under page 1 shows while it rests there. */
    boolean coversScreen() {
        return false;
    }

    /** Its nodes in page 1's column, under the top bar and over the shelf; one is flex, so the shelf stays at the foot. */
    abstract Node[] nodes(float k);

    /** After page 1's layout: its own rectangles from its nodes' frames. */
    void placed() {}

    /** Made at layout with page 1's paints. */
    void makePaints(float k) {
        this.k = k;
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(2.5f * k);
    }

    /** Draws in the top layer (with the weather, if this style has it there). */
    abstract void draw(Canvas c);

    /** Recs' kind for the list shown (the selected tab's). */
    String kind() {
        return TAB_KIND[0];
    }

    /** This hour's picks for this style. */
    List<Recs.Pick> list() {
        return recs.now(kind());
    }

    /** Loads the photos the cards need ahead. */
    void preload() {
        for (Recs.Pick p : Config.PICK_COUNT > 3 ? cards : recs.wanted()) recs.backdrop(p);
    }

    /** The picks are about to change (Cards fades the old ones out). */
    void changing() {}

    /** A new list after none (or none after one): the row starts again at its first pick. */
    void restart() {
        cardStart = 0;
    }

    /** This hour's picks: the cards and the background image (when Recs notifies and every 15 s). */
    final void update() {
        List<Recs.Pick> list = list();
        if (!samePicks(list, cards)) {
            changing();
            // The daily list keeps each staying title in its place (Recs.publish): the row stays where it was.
            if (cards.isEmpty() || list.isEmpty()) restart();
            cards = list;
            index = Math.min(index, Math.max(0, Math.min(3, list.size()) - 1));
            if (list.isEmpty()) page.picksEmptied();
        }
        recs.setView(kind(), cardStart);
        preload();
        show();
    }

    /** The first card's photo as the background, and the cards drawn again. */
    final void show() {
        recs.setView(kind(), cardStart);     // these cards and the next page get their details
        Backdrop b = cards.isEmpty() ? null : recs.backdrop(cardAt(0));
        if (cards.isEmpty() || b != null) home.showBackdrop(b);
        page.invalidate();
        home.focusMoved();    // new picks under the focused card
    }

    private static boolean samePicks(List<Recs.Pick> a, List<Recs.Pick> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!Recs.key(a.get(i)).equals(Recs.key(b.get(i)))) return false;
        return true;
    }

    boolean isEmpty() {
        return cards.isEmpty();
    }

    /** `i` wrapped into `[0, cards.size())`, a negative `i` too; -1 without picks. */
    int wrap(int i) {
        int n = cards.size();
        if (n == 0) return -1;
        return ((i % n) + n) % n;
    }

    /** The pick in card `slot` (0 to 2): with more than three picks they take turns, wrapping around. */
    Recs.Pick cardAt(int slot) {
        if (slot < 0 || slot >= Math.min(3, cards.size())) return null;
        int i = wrap(cardStart + slot);
        return i < 0 ? null : cards.get(i);
    }

    /** ← → on the picks: false if they don't move. */
    abstract boolean move(int dx);

    /** pick_rotate_seconds: the picks move on by themselves (focus elsewhere, nothing open). */
    abstract void autoRotate();

    /** Where the focused pick is (the shelf's tile below it takes the focus; an opened app grows from it). */
    abstract RectF focusedRect();

    abstract Recs.Pick focusedPick();

    /** Aims the cards' focus (`on`: the picks have it); true if that changed. */
    boolean aim(boolean on, boolean snap) {
        return HomeTopPage.aim(focus, on ? index : -1, snap);
    }

    /** One frame; true while something still moves. */
    boolean tick(float dt, long up) {
        boolean moving = false;
        for (Spring s : focus) moving |= s.step(dt);
        return moving;
    }

    /** Adds the focused card's centre, weighted by its focus, to `acc` (weight, x, y): the look's focus glow. */
    void glow(float[] acc) {
        RectF r = focusedRect();
        float v = Ui.clamp(focus[index].value);
        acc[0] += v;
        acc[1] += v * r.centerX();
        acc[2] += v * r.centerY();
    }

    // MARK: - Tabs (only Cards has them)

    int tabs() {
        return 0;
    }

    int tab() {
        return 0;
    }

    void selectTab(int i) {}

    // MARK: - TalkBack: the cards, read and focused one by one

    int a11yCount() {
        return 0;
    }

    String a11yText(int i) {
        return pickText(cardAt(i));
    }

    boolean a11yRect(int i, RectF out) {
        return false;
    }

    /** A recommendation: title, details and the app that plays it. */
    static String pickText(Recs.Pick p) {
        if (p == null) return "";
        StringBuilder s = new StringBuilder(p.title == null ? "" : p.title);
        if (p.meta != null && !p.meta.isEmpty()) s.append(", ").append(p.meta);
        String app = Recs.serviceName(p);
        if (app != null && !app.isEmpty()) s.append(". App: ").append(app);
        return s.toString();
    }
}
