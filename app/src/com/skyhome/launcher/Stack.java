package com.skyhome.launcher;

import android.graphics.Canvas;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Nodes one after another, left to right (`row`) or top to bottom (`column`), `gap` apart. Fixed sizes are kept; flex
 * nodes share what is left. Across the axis a child fills the stack unless it has its own size, then `align` places it.
 * Moving one child moves the ones after it: nothing to recompute by hand, and they can't overlap.
 */
final class Stack extends Node {
    static final int START = 0, CENTER = 1, END = 2;

    private final boolean row;
    private final float gap;
    private final List<Node> items;
    private int align = START;

    private Stack(String name, boolean row, float gap, Node... items) {
        super(name);
        this.row = row;
        this.gap = gap;
        this.items = new ArrayList<>(Arrays.asList(items));
    }

    static Stack row(String name, float gap, Node... items) {
        return new Stack(name, true, gap, items);
    }

    static Stack column(String name, float gap, Node... items) {
        return new Stack(name, false, gap, items);
    }

    Stack align(int align) {
        this.align = align;
        return this;
    }

    @Override float prefW() {
        if (prefW != FILL) return prefW;
        return row ? along() : across(true);
    }

    @Override float prefH() {
        if (prefH != FILL) return prefH;
        return row ? across(false) : along();
    }

    /** The children's length along the axis, gaps included; FILL when one of them takes what is left. */
    private float along() {
        float sum = 0;
        int n = 0;
        for (Node c : items) {
            if (!c.visible()) continue;
            float s = row ? c.prefW() : c.prefH();
            if (c.flex || s == FILL) return FILL;
            sum += s;
            n++;
        }
        return sum + Math.max(0, n - 1) * gap;
    }

    /** The largest child across the axis; FILL when one fills the stack. */
    private float across(boolean width) {
        float most = 0;
        for (Node c : items) {
            if (!c.visible()) continue;
            float s = width ? c.prefW() : c.prefH();
            if (s == FILL) return FILL;
            most = Math.max(most, s);
        }
        return most;
    }

    @Override void placed() {
        float main = row ? frame.width() : frame.height(), cross = row ? frame.height() : frame.width();
        float fixed = 0;
        int n = 0, flexes = 0;
        for (Node c : items) {
            if (!c.visible()) continue;
            n++;
            float s = row ? c.prefW() : c.prefH();
            if (c.flex || s == FILL) flexes++;
            else fixed += s;
        }
        float share = flexes == 0 ? 0 : Math.max(0, main - fixed - Math.max(0, n - 1) * gap) / flexes;
        float at = row ? frame.left : frame.top;
        for (Node c : items) {
            if (!c.visible()) continue;
            float s = row ? c.prefW() : c.prefH();
            float length = c.flex || s == FILL ? share : s;
            float want = row ? c.prefH() : c.prefW();
            float thick = want == FILL ? cross : Math.min(want, cross);
            float offset = align == CENTER ? (cross - thick) / 2 : align == END ? cross - thick : 0;
            if (row) c.place(at, frame.top + offset, length, thick);
            else c.place(frame.left + offset, at, thick, length);
            at += length + gap;
        }
    }

    @Override void draw(Canvas c) {
        for (Node child : items) if (child.visible()) child.draw(c);
    }

    @Override List<Node> children() {
        return items;
    }
}
