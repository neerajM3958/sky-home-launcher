package com.skyhome.launcher;

import android.graphics.Canvas;
import android.graphics.RectF;

import java.util.Collections;
import java.util.List;

/**
 * A rectangle of a home page with one job: it has a preferred size, its parent (a `Stack`, or the page) places it, and
 * it draws inside its frame. Sizes and frames are pixels in the page's own space (0 at the page's top). Nodes are built
 * when a page lays out, never while drawing, and a node never reads another node's frame except through its parent.
 */
class Node {
    /** A size the parent fills: the stack's whole cross size, or (for a flex node) its share of what is left. */
    static final float FILL = -1;

    /** How a node draws itself: `frame` is where it was placed. */
    interface Painter {
        void draw(Canvas c, RectF frame);
    }

    final String name;
    final RectF frame = new RectF();
    float prefW = FILL, prefH = FILL;
    /** Takes the parent stack's leftover room along its axis (shared with the other flex nodes). */
    boolean flex;
    /** May reach outside the safe area: a full-screen backdrop, a row that slides off screen. */
    boolean bleed;
    private Painter painter;

    Node(String name) {
        this.name = name;
    }

    /** An empty node that only takes room. */
    static Node space(float w, float h) {
        return new Node("space").size(w, h);
    }

    /** A leaf that draws with `painter`. */
    static Node of(String name, Painter painter) {
        Node n = new Node(name);
        n.painter = painter;
        return n;
    }

    Node size(float w, float h) {
        prefW = w;
        prefH = h;
        return this;
    }

    Node flex() {
        flex = true;
        return this;
    }

    Node bleed() {
        bleed = true;
        return this;
    }

    /** A hidden node takes no room and draws nothing, so its neighbours need no `if` for it. */
    boolean visible() {
        return true;
    }

    float prefW() {
        return prefW;
    }

    float prefH() {
        return prefH;
    }

    void place(float left, float top, float width, float height) {
        frame.set(left, top, left + width, top + height);
        placed();
    }

    /** After `frame` is set: a container places its children here. */
    void placed() {}

    void draw(Canvas c) {
        if (painter != null) painter.draw(c, frame);
    }

    List<Node> children() {
        return Collections.emptyList();
    }
}
