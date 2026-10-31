package com.skyhome.launcher;

import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * The home screen's pages, one under another, a screen apart. One spring (`pos`, in pages) says which one shows; each
 * visible page's layers move by translation only (the GPU moves what it already has), and a page more than a screen
 * away is hidden with its layers off. A page with nothing to show is left out, so the arrows never land on it.
 */
final class HomePager {
    /** One page: its layers, and whether it has anything to show now. */
    static final class Slot {
        final String name;
        final View[] layers;
        /** Its layers keep their GPU layer only while they show (the first page's keep theirs always). */
        final boolean dropsLayers;
        private final BooleanSupplier shows;

        Slot(String name, boolean dropsLayers, BooleanSupplier shows, View... layers) {
            this.name = name;
            this.dropsLayers = dropsLayers;
            this.shows = shows;
            this.layers = layers;
        }
    }

    private final List<Slot> slots = new ArrayList<>();
    private final List<Slot> shown = new ArrayList<>();
    final Spring pos = new Spring(0.46f, 0.92f, 0);
    private Slot current;

    HomePager(Slot... slots) {
        for (Slot s : slots) this.slots.add(s);
        current = slots[0];
        refresh();
    }

    Slot current() {
        return current;
    }

    /** Reads again which pages have something to show; the current page keeps its place on screen. */
    void refresh() {
        int before = shown.indexOf(current);
        shown.clear();
        for (Slot s : slots) if (s == slots.get(0) || s.shows.getAsBoolean()) shown.add(s);
        int after = shown.indexOf(current);
        if (before >= 0 && after >= 0 && before != after) {
            pos.value += after - before;
            pos.target += after - before;
        }
    }

    /** Where `s` is in the pages' order (whether it shows or not). */
    int order(Slot s) {
        return slots.indexOf(s);
    }

    boolean shows(Slot s) {
        return shown.contains(s);
    }

    /** The shown page `dir` pages from the current one (1: below, -1: above), or null. */
    Slot next(int dir) {
        int i = shown.indexOf(current);
        if (i < 0) return null;
        int j = i + dir;
        return j >= 0 && j < shown.size() ? shown.get(j) : null;
    }

    /** The nearest shown page above `s` in the pages' order (the first page shows always). */
    Slot above(Slot s) {
        Slot best = slots.get(0);
        for (Slot x : slots) {
            if (x == s) break;
            if (shown.contains(x)) best = x;
        }
        return best;
    }

    /** `s` becomes the current page; `snap`: at once, without sliding. */
    void show(Slot s, boolean snap) {
        current = s;
        int i = Math.max(0, shown.indexOf(s));
        if (snap) pos.snap(i);
        else pos.target = i;
    }

    /** How far from the first page the screen is (0 there, 1 a page or more away). */
    float away() {
        return Ui.clamp(pos.value);
    }

    /** Places every page's layers for `pos`: a screen (`h`) apart, at `alpha` (All Apps fades them). */
    void place(int h, float alpha) {
        // The current page may have just been left out (its data went): keep its place until the slide ends.
        for (Slot s : slots) {
            int i = shown.indexOf(s);
            float d = i - pos.value;
            boolean on = i >= 0 && Math.abs(d) < 1 && alpha > 0.002f;
            for (View v : s.layers) {
                v.setAlpha(alpha);
                v.setTranslationY(on ? d * h : 0);
                if (s.dropsLayers) HomeView.showLayer(v, on);
                else v.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
            }
        }
    }
}
