package com.skyhome.launcher;

/**
 * A row of focusable items, left to right: which one has the focus, and how far the row has scrolled (`shift`, the
 * first item shown, on a spring) so the focused one stays among the `visible` ones on screen.
 */
abstract class FocusRow {
    final int visible;
    final Spring shift = new Spring(0.34f, 1f, 0);
    int index;

    FocusRow(int visible) {
        this.visible = visible;
    }

    abstract int count();

    /** ← or →: false at either end (the focus stays). */
    boolean move(int dx) {
        int next = Math.max(0, Math.min(count() - 1, index + dx));
        if (next == index) return false;
        index = next;
        float first = shift.target;
        if (next < first) first = next;
        else if (next > first + visible - 1) first = next - visible + 1;
        shift.target = first;
        return true;
    }

    /** Puts the focus on item `i` at once, scrolled just enough to show it. */
    void focus(int i) {
        index = Math.max(0, Math.min(count() - 1, i));
        shift.snap(Math.max(0, index - visible + 1));
    }
}
