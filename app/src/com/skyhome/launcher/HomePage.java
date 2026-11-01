package com.skyhome.launcher;

import android.graphics.Canvas;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.List;

/**
 * One page of the home screen. It lays itself out in its own space (0 at its top, `height()` screens tall) as a tree of
 * nodes built by `build()`, draws them, and moves the focus through its `rows` (top to bottom). It never reads another
 * page's positions. `move` returns false at the page's edges, and the home screen decides where the focus goes next.
 */
abstract class HomePage {
    final HomeView home;
    final String name;
    protected float k = 1;
    protected int w, h;
    protected Node root;
    /** Its rows of focusable items, top to bottom, and the focused one. */
    protected final List<FocusRow> rows = new ArrayList<>();
    protected int row;
    private final RectF safe = new RectF();
    private boolean dirty = true;

    HomePage(HomeView home, String name) {
        this.home = home;
        this.name = name;
    }

    /** Nothing to show: the page is hidden. */
    abstract boolean isEmpty();

    /** How tall the page is, in screens (1 to 3). */
    float height() {
        return 1;
    }

    /** The page's nodes for the current size, data and prefs; called by `layout`, never while drawing. */
    protected abstract Node build();

    /** Makes the page's paints for this size; called before `build`. */
    protected void makePaints() {}

    /** Size changed (or the look, the language, the prefs): paints and nodes are made again and checked. */
    final void layout(float k, int w, int h) {
        this.k = k;
        this.w = w;
        this.h = h;
        relayout();
    }

    /** Builds and places the nodes again at the current size. */
    final void relayout() {
        if (w == 0) return;
        makePaints();
        root = build();
        if (root != null) {
            root.place(0, 0, w, h * height());
            safe.set(HomeGrid.MARGIN * k, HomeGrid.SAFE_TOP * k, HomeGrid.CONTENT_R * k, (HomeGrid.SAFE_BOTTOM + (height() - 1) * HomeGrid.H) * k);
            LayoutCheck.check(name, root, safe);
            placed();
        }
        dirty = true;
    }

    /** After the nodes are placed: what the page keeps from their frames. */
    protected void placed() {}

    void draw(Canvas c) {
        if (root == null) return;
        root.draw(c);
        LayoutCheck.drawBounds(c, root, k);
    }

    /** Something on the page changed: its layer is drawn again on the next frame. */
    final void invalidate() {
        dirty = true;
        home.wake();
    }

    /** True once after `invalidate` (the frame takes it). */
    final boolean takeDirty() {
        boolean d = dirty;
        dirty = false;
        return d;
    }

    /** Advances the page's springs by `dt` seconds; true while something still moves. */
    boolean tick(float dt) {
        boolean moving = false;
        for (FocusRow r : rows) moving |= r.shift.step(dt);
        return moving;
    }

    /**
     * The arrows: ← → along the focused row, ↑ ↓ to the row above or below (which keeps its own focused item). False at
     * the page's edges: nothing moved.
     */
    boolean move(int dx, int dy) {
        if (rows.isEmpty()) return false;
        if (dx != 0) {
            if (!rows.get(row).move(dx)) return false;
            focusChanged(false);
            return true;
        }
        int next = row + dy;
        if (next < 0 || next >= rows.size()) return false;
        row = next;
        focusChanged(true);
        return true;
    }

    /** The focus moved inside the page (`rowChanged`: to another row). */
    protected void focusChanged(boolean rowChanged) {
        invalidate();
    }

    /** The focus comes onto the page: from the page above (`fromAbove`) or the one below. */
    void enter(boolean fromAbove) {}

    /** The focus leaves the page. */
    void leave() {}

    /** OK on the focused item; false if there was nothing to do. */
    boolean activate() {
        return false;
    }

    /** OK held on the focused item; false if holding does nothing here. */
    boolean hold() {
        return false;
    }

    /** The home screen goes away (another app opens): stop what plays, drop what can be made again. */
    void pause() {}
}
