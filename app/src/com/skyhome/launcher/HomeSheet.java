package com.skyhome.launcher;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * "All Apps": a sheet over the home screen with every app not on the shelf, five to a row, three rows in view (the
 * grid scrolls to keep the focused one there). It grows in from slightly larger as it fades in.
 */
final class HomeSheet {
    private static final int COLUMNS = 5, VISIBLE_ROWS = 3;
    // 1920-wide units: an app's tile (16:9), the gaps, one row to the next, the first row's top.
    private static final float TILE_W = 300, TILE_H = 169, GAP = 40, ROW = 239, TOP = 196, RADIUS = 22;

    private final HomeView home;
    /** How far the sheet has come in (0 to 1), and how far the grid has scrolled, in rows. */
    final Spring in = new Spring(0.38f, 0.9f, 0);
    private final Spring scroll = new Spring(0.34f, 1f, 0);
    private List<HomeApps.Item> apps = new ArrayList<>();
    private Spring[] focus = new Spring[0];
    private String[] labels;
    private int index;
    private boolean open, dirty;
    private final RectF tile = new RectF(), focused = new RectF();
    private Paint title;
    private TextPaint label;
    private float k = 1;
    private int w, h;

    HomeSheet(HomeView home) {
        this.home = home;
    }

    void layout(float k, int w, int h) {
        this.k = k;
        this.w = w;
        this.h = h;
        title = Ui.text(Ui.displaySemibold, 40 * k, 0xFFFFFFFF);
        title.setTextAlign(Paint.Align.CENTER);
        label = new TextPaint(Ui.text(Ui.medium, 24 * k, 0x99FFFFFF));
        label.setTextAlign(Paint.Align.CENTER);
        labels = null;
    }

    boolean isOpen() {
        return open;
    }

    List<HomeApps.Item> apps() {
        return apps;
    }

    int index() {
        return index;
    }

    /** The focused tile as last drawn (grown with the focus): where an app opening from here starts. */
    RectF focusedTile() {
        return focused;
    }

    void setApps(List<HomeApps.Item> list) {
        apps = list;
        focus = new Spring[list.size()];
        for (int i = 0; i < focus.length; i++) focus[i] = new Spring(0.3f, 0.82f, 0);
        labels = null;
        index = Math.min(index, Math.max(0, list.size() - 1));
        aim(true);
    }

    void open() {
        open = true;
        index = 0;
        scroll.snap(0);
        aim(true);
        in.target = 1;
    }

    void close() {
        open = false;
        in.target = 0;
        aim(false);
    }

    /** The arrows: along a row, or to the same column of the row above or below; false at an edge. */
    boolean move(int dx, int dy) {
        int n = apps.size();
        if (n == 0) return false;
        int r = index / COLUMNS, col = index % COLUMNS;
        int rows = (n + COLUMNS - 1) / COLUMNS;
        int next = index;
        if (dx != 0) {
            int candidate = index + dx;
            if (candidate >= 0 && candidate < n && candidate / COLUMNS == r) next = candidate;
        } else {
            int targetRow = r + dy;
            if (targetRow >= 0 && targetRow < rows) next = Math.min(n - 1, targetRow * COLUMNS + col);
        }
        if (next == index) return false;
        index = next;
        aim(false);
        return true;
    }

    /** TalkBack moved to app `i`. */
    boolean focus(int i) {
        if (!open || i >= apps.size()) return false;
        index = i;
        aim(false);
        return true;
    }

    /** Where app `i`'s tile rests once scrolled to (for TalkBack). */
    void tileRect(int i, RectF out) {
        float gridWidth = COLUMNS * TILE_W + (COLUMNS - 1) * GAP;
        float x = ((HomeGrid.W - gridWidth) / 2 + (i % COLUMNS) * (TILE_W + GAP)) * k;
        float y = (TOP + (i / COLUMNS - scroll.target) * ROW) * k;
        out.set(x, y, x + TILE_W * k, y + TILE_H * k);
    }

    /** Only the focused tile's spring aims at 1; the grid scrolls to keep its row in view. */
    private void aim(boolean snap) {
        for (int i = 0; i < focus.length; i++) {
            focus[i].target = open && i == index ? 1 : 0;
            if (snap) focus[i].snap(focus[i].target);
        }
        int rows = Math.max(1, (apps.size() + COLUMNS - 1) / COLUMNS);
        int visible = Math.min(rows, VISIBLE_ROWS);
        int r = index / COLUMNS;
        float target = scroll.target;
        if (r < target) target = r;
        else if (r > target + visible - 1) target = r - visible + 1;
        scroll.target = Math.max(0, Math.min(rows - visible, target));
        if (snap) scroll.snap(scroll.target);
        dirty = true;
    }

    /** The grid's springs; true while the grid moves (the sheet's own `in` is the home screen's to step). */
    boolean tick(float dt) {
        boolean moving = scroll.step(dt);
        for (Spring s : focus) moving |= s.step(dt);
        return moving;
    }

    boolean takeDirty() {
        boolean d = dirty;
        dirty = false;
        return d;
    }

    void draw(Canvas c) {
        if (title == null) return;
        Look look = home.look;
        float dark = home.dark();
        tile.set(0, 0, w, h);
        look.sheetBackground(c, tile, dark);
        title.setColor(look.sheetTitleInk(dark));
        float shift = scroll.value * ROW * k;
        c.drawText(Ui.tr("All Apps"), w / 2f, 124 * k - shift, title);
        int n = apps.size();
        if (labels == null || labels.length != n) {
            labels = new String[n];
            for (int i = 0; i < n; i++) {
                labels[i] = TextUtils.ellipsize(apps.get(i).label, label, (TILE_W - 10) * k, TextUtils.TruncateAt.END).toString();
            }
        }
        float gridWidth = COLUMNS * TILE_W + (COLUMNS - 1) * GAP;
        float left = (HomeGrid.W - gridWidth) / 2 * k;
        for (int i = 0; i < n; i++) {
            int r = i / COLUMNS, col = i % COLUMNS;
            float y = TOP * k + r * ROW * k - shift;
            if (y > h || y + ROW * k < 0) continue;
            float x = left + col * (TILE_W + GAP) * k;
            tile.set(x, y, x + TILE_W * k, y + TILE_H * k);
            // Only the targeted tile grows (#memory-management, as on the shelf).
            float f = i == index ? focus[i].value : 0;
            home.painter.drawCard(c, apps.get(i).tile, tile, RADIUS * k, f, 0.08f);
            if (open && i == index) focused.set(home.painter.lastCard());
            label.setColor(look.gridLabelInk(f, dark));
            c.drawText(labels[i], tile.centerX(), tile.bottom + (40 + 6 * f) * k, label);
        }
    }
}
