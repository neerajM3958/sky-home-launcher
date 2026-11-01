package com.skyhome.launcher;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * The popover: a glass panel with a title and a few rows, ↑ ↓ and OK. Under the HDMI and AV input buttons (a row for
 * each input of that kind), or beside a poster (its menu: Remove or Hide, Mark as watched). It sits on its own layer,
 * just big enough for the panel and its shadow, which follows the panel.
 */
final class HomePopover {
    // 1920-wide units: the panel's width, its rows, and the layer that holds it with room for the shadow.
    private static final float W = 520, ROW_H = 72, TITLE_H = 64, PAD = 14, TOP = 168, LAYER_W = 600, LAYER_H = 800, LAYER_TOP = 130;
    private static final int WHITE = 0xFFF5F5F7, INK = 0xFF1C1C1E;

    private final HomeView home;
    private final View layer;
    private final Spring in = new Spring(0.34f, 0.88f, 0);
    private Spring[] focus = new Spring[0];
    private List<HomeApps.Item> items = new ArrayList<>();
    /** A poster's menu: OK runs the focused row's; null for the inputs, where OK opens the one chosen. */
    private Runnable[] actions;
    private String title = "";
    private int index;
    private boolean open, dirty;
    private final RectF panel = new RectF(), row = new RectF(), tmp = new RectF();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint fit = new TextPaint();
    private Paint label, heading;
    private float k = 1;
    private int w, h;

    HomePopover(HomeView home, View layer) {
        this.home = home;
        this.layer = layer;
    }

    void layout(float k, int w, int h) {
        this.k = k;
        this.w = w;
        this.h = h;
        label = Ui.text(Ui.medium, 27 * k, 0xEBFFFFFF);
        heading = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
    }

    /** The layer's size, for onMeasure. */
    int layerWidth(float kk) {
        return Math.round(LAYER_W * kk);
    }

    int layerHeight(float kk) {
        return Math.round(LAYER_H * kk);
    }

    /** Places the layer (onLayout): where the panel is, or at the top right before it first opens. */
    void place(float kk) {
        if (panel.isEmpty()) {
            int x = Math.round((HomeGrid.CONTENT_R - W - 40) * kk), y = Math.round(LAYER_TOP * kk);
            layer.layout(x, y, x + layer.getMeasuredWidth(), y + layer.getMeasuredHeight());
        } else {
            placeLayer();
        }
    }

    boolean isOpen() {
        return open;
    }

    List<HomeApps.Item> items() {
        return items;
    }

    int index() {
        return index;
    }

    /** Opens with `list`, focus on row `at`; beside `anchor` (a poster), or at the top right when null. */
    void show(String title, List<HomeApps.Item> list, int at, Runnable[] actions, RectF anchor) {
        this.title = title;
        this.items = list;
        this.index = at;
        this.actions = actions;
        if (focus.length != items.size()) {
            focus = new Spring[items.size()];
            for (int i = 0; i < focus.length; i++) focus[i] = new Spring(0.3f, 0.82f, 0);
        }
        aim(true);
        layoutPanel(anchor);
        open = true;
        in.target = 1;
        layer.setVisibility(View.VISIBLE);
        dirty = true;
    }

    /** Closes (`snap`: at once); false if it was already closed. */
    boolean close(boolean snap) {
        if (!open && in.value <= 0.001f) return false;
        open = false;
        in.target = 0;
        if (snap) {
            in.snap(0);
            layer.setVisibility(View.INVISIBLE);
        }
        dirty = true;
        return true;
    }

    /** ↑ ↓: false at either end. */
    boolean move(int dy) {
        if (dy == 0) return false;
        int next = Math.max(0, Math.min(items.size() - 1, index + dy));
        if (next == index) return false;
        index = next;
        aim(false);
        return true;
    }

    /** TalkBack moved to row `i`. */
    boolean focus(int i) {
        if (!open || i >= items.size()) return false;
        index = i;
        aim(false);
        return true;
    }

    /**
     * OK: closes, and runs the focused row's action (a poster's menu; returns null) or returns the input to open, with
     * its row in `from`.
     */
    HomeApps.Item choose(RectF from) {
        if (index >= items.size()) {
            close(false);
            return null;
        }
        if (actions != null) {
            Runnable action = actions[index];
            close(false);
            action.run();
            return null;
        }
        rowRect(index, from);
        close(false);
        return items.get(index);
    }

    /** Row `i` in screen coordinates. */
    void rowRect(int i, RectF out) {
        float y = panel.top + (TITLE_H + PAD / 2f) * k + i * ROW_H * k;
        out.set(panel.left + PAD * k, y, panel.right - PAD * k, y + (ROW_H - 6) * k);
    }

    /** Its springs; true while something moves. Its layer goes once it has faded out. */
    boolean tick(float dt) {
        boolean moving = in.step(dt);
        for (Spring s : focus) moving |= s.step(dt);
        if (moving || dirty) layer.invalidate();
        dirty = false;
        if (!open && in.value <= 0.001f && layer.getVisibility() == View.VISIBLE) layer.setVisibility(View.INVISIBLE);
        return moving;
    }

    private void aim(boolean snap) {
        for (int i = 0; i < focus.length; i++) {
            focus[i].target = i == index ? 1 : 0;
            if (snap) focus[i].snap(focus[i].target);
        }
        dirty = true;
    }

    /** The panel at the top right (the inputs), or beside `anchor`: to its right, to its left when that doesn't fit. */
    private void layoutPanel(RectF anchor) {
        float pw = W * k, ph = (TITLE_H + PAD) * k + items.size() * ROW_H * k;
        float left, top;
        if (anchor == null) {
            left = HomeGrid.CONTENT_R * k - pw;
            top = TOP * k;
        } else {
            float gap = 24 * k, edge = 24 * k;
            left = anchor.right + gap;
            if (left + pw > w - edge) left = anchor.left - gap - pw;
            left = Math.max(edge, left);
            top = Math.max(edge, Math.min(anchor.top, h - ph - edge));
        }
        panel.set(left, top, left + pw, top + ph);
        placeLayer();
    }

    /** The layer is just big enough for the panel and its shadow: it follows the panel. */
    private void placeLayer() {
        int x = Math.round(panel.left - 40 * k), y = Math.round(panel.top - 38 * k);
        layer.layout(x, y, x + layer.getMeasuredWidth(), y + layer.getMeasuredHeight());
    }

    /** Glass panel with the title and the rows: the focused one white with dark text, an input with no device dimmed. */
    void draw(Canvas c) {
        float a = Ui.clamp(in.value);
        if (a <= 0.01f || items.isEmpty() || label == null) return;
        Look look = home.look;
        float dark = home.dark();
        home.applyTone();
        float s = 0.95f + 0.05f * a;
        int save = c.saveLayerAlpha(panel.left - 60 * k, panel.top - 40 * k, panel.right + 60 * k, panel.bottom + 80 * k,
                Math.round(255 * a));
        c.scale(s, s, panel.right - 60 * k, panel.top);
        home.drawerGlass(c, panel, 34 * k, 0.8f, 0x22000000, 14 * k);
        heading.setColor(look.color(Look.DRAWER_ROW, dark));
        fit.set(heading);
        c.drawText(TextUtils.ellipsize(title, fit, panel.width() - (2 * PAD + 28) * k, TextUtils.TruncateAt.END).toString(),
                panel.left + (PAD + 14) * k, panel.top + 44 * k, heading);
        for (int i = 0; i < items.size() && i < focus.length; i++) {
            HomeApps.Item it = items.get(i);
            float f = Ui.clamp(focus[i].value), r = 24 * k;
            rowRect(i, row);
            if (f > 0.01f) {
                tmp.set(row);
                tmp.offset(0, 8 * k * f);
                home.shadow(c, tmp, r, 0.35f * f);
                fill.setShader(null);
                fill.setColor(Ui.alpha(WHITE, f));
                c.drawRoundRect(row, r, r, fill);
            }
            int ink = HomeView.blend(look.color(it.connected ? Look.ITEM_ON : Look.ITEM_OFF, dark), INK, f);
            if (it.symbol != null) Symbols.draw(home.getContext(), c, it.symbol, row.left + 44 * k, row.centerY(), 32 * k, ink);
            float textX = it.symbol != null ? 88 : 28;     // a poster's menu has no icons
            label.setColor(ink);
            fit.set(label);
            String text = TextUtils.ellipsize(it.label, fit, row.width() - (textX + 12) * k, TextUtils.TruncateAt.END).toString();
            c.drawText(text, row.left + textX * k, row.centerY() + Ui.halfCap(label), label);
        }
        c.restoreToCount(save);
    }
}
