package com.skyhome.launcher;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;

/**
 * Guard rails for the home pages' layout: after every layout `check` logs any two sibling nodes that overlap and any
 * node outside the safe area that isn't marked bleed, so a change that pushes something into its neighbour shows up in
 * `adb logcat -s SkyHomeLauncher` at once. `adb shell setprop debug.skyhome.bounds 1` (then reinstall) outlines every
 * node on the TV with its name, to check alignment by eye.
 */
final class LayoutCheck {
    /** Outlines every node (debug.skyhome.bounds), read once when the home screen starts. */
    static volatile boolean bounds;
    private static Paint line, label;

    private LayoutCheck() {}

    /** Reads debug.skyhome.bounds; call off the UI thread (it runs getprop). */
    static void readFlags() {
        try {
            Process p = Runtime.getRuntime().exec(new String[] {"getprop", "debug.skyhome.bounds"});
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String v = r.readLine();
                bounds = "1".equals(v == null ? "" : v.trim()) || "true".equals(v == null ? "" : v.trim());
            }
        } catch (Exception e) {
            bounds = false;
        }
    }

    /** Logs overlapping siblings and nodes outside `safe` (pixels, the page's own space). */
    static void check(String page, Node root, RectF safe) {
        if (root == null) return;
        walk(page, root, safe);
    }

    private static void walk(String page, Node n, RectF safe) {
        List<Node> kids = n.children();
        if (kids.isEmpty()) {
            if (!n.bleed && !inside(n.frame, safe)) {
                AppLog.w("layout " + page + ": " + n.name + " " + n.frame.toShortString() + " is outside the safe area");
            }
            return;
        }
        for (int i = 0; i < kids.size(); i++) {
            Node a = kids.get(i);
            if (!a.visible() || a.frame.isEmpty()) continue;
            for (int j = i + 1; j < kids.size(); j++) {
                Node b = kids.get(j);
                if (!b.visible() || b.frame.isEmpty()) continue;
                if (overlap(a.frame, b.frame)) AppLog.w("layout " + page + ": " + a.name + " overlaps " + b.name);
            }
            if (!n.bleed || a.bleed) walk(page, a, safe);
        }
    }

    /** Half a pixel of slack: rounding is not an overlap. */
    private static boolean overlap(RectF a, RectF b) {
        return a.left < b.right - 0.5f && b.left < a.right - 0.5f && a.top < b.bottom - 0.5f && b.top < a.bottom - 0.5f;
    }

    private static boolean inside(RectF r, RectF safe) {
        return r.isEmpty() || (r.left >= safe.left - 0.5f && r.right <= safe.right + 0.5f
                && r.top >= safe.top - 0.5f && r.bottom <= safe.bottom + 0.5f);
    }

    /** With debug.skyhome.bounds on: each node's frame and name. */
    static void drawBounds(Canvas c, Node root, float k) {
        if (!bounds || root == null) return;
        if (line == null) {
            line = new Paint(Paint.ANTI_ALIAS_FLAG);
            line.setStyle(Paint.Style.STROKE);
            label = new Paint(Paint.ANTI_ALIAS_FLAG);
        }
        line.setStrokeWidth(Math.max(1, 2 * k));
        label.setTextSize(18 * k);
        outline(c, root, k, 0);
    }

    private static void outline(Canvas c, Node n, float k, int depth) {
        if (!n.visible()) return;
        int color = depth % 2 == 0 ? 0xFFFF3B30 : 0xFF34C759;
        line.setColor(color);
        label.setColor(color);
        c.drawRect(n.frame, line);
        if (!"space".equals(n.name)) c.drawText(n.name, n.frame.left + 4 * k, n.frame.top + 20 * k, label);
        for (Node child : n.children()) outline(c, child, k, depth + 1);
    }
}
