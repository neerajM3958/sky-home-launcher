package com.skyhome.launcher;

import android.graphics.Canvas;
import android.graphics.RectF;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

import java.util.ArrayDeque;

/** The home screen's toasts: one at a time at the top, in glass, each for a few seconds; the others wait their turn. */
final class HomeToasts {
    private static final long SHOW_MS = 6000;
    private static final int SMOKE = 0xFF111113;

    private final HomeView home;
    private final Scene scene;
    private final View layer;
    private final ArrayDeque<String> queue = new ArrayDeque<>();
    private final RectF box = new RectF();
    private StaticLayout shown;
    private long shownAt;
    private TextPaint paint;
    private float k = 1;
    private int w;

    HomeToasts(HomeView home, Scene scene, View layer) {
        this.home = home;
        this.scene = scene;
        this.layer = layer;
    }

    void layout(float k, int w) {
        this.k = k;
        this.w = w;
        paint = new TextPaint(Ui.text(Ui.medium, 26 * k, 0xF2FFFFFF));
    }

    void show(String text) {
        queue.add(text);
    }

    /** A toast shows or waits (the frames go on). */
    boolean busy() {
        return shown != null || !queue.isEmpty();
    }

    /** A toast is on screen (the glass behind it follows the background). */
    boolean onScreen() {
        return shown != null;
    }

    /** Advances the queue; true while a toast fades in or out. */
    boolean tick(long up) {
        if (shown != null && up - shownAt > SHOW_MS) {
            shown = null;
            layer.invalidate();
        }
        if (shown == null && paint != null) {
            String next = queue.poll();
            if (next != null) {
                shown = layoutText(next);
                shownAt = up;
            }
        }
        if (shown == null) return false;
        long age = up - shownAt;
        boolean fading = age < 350 || age > SHOW_MS - 450;
        if (fading) layer.invalidate();
        return fading;
    }

    void draw(Canvas c) {
        if (shown == null) return;
        float age = (SystemClock.uptimeMillis() - shownAt) / 1000f;
        float a = Math.min(Ui.clamp(age / 0.3f), Ui.clamp((SHOW_MS / 1000f - age) / 0.4f));
        if (a <= 0) return;
        float dark = home.dark();
        float padX = 28 * k, padY = 18 * k, icon = 26 * k, iconW = Symbols.width(home.getContext(), "bell", icon);
        float tw = shown.getWidth() + iconW + 16 * k, th = shown.getHeight();
        float top = 64 * k - (1 - a) * 16 * k;
        box.set((w - tw) / 2f - padX, top, (w + tw) / 2f + padX, top + th + 2 * padY);
        int save = c.saveLayerAlpha(box.left - 4, box.top - 4, box.right + 4, box.bottom + 4, Math.round(255 * a));
        float radius = Math.min(box.height() / 2, 32 * k);
        if (!home.look.toast(c, box, radius, dark)) scene.glass(c, box, radius, SMOKE, 0.55f, 0, 0, 0.02f, 1);
        paint.setColor(home.look.toastInk(dark));
        Symbols.draw(home.getContext(), c, "bell", box.left + padX + iconW / 2, box.centerY(), icon, paint.getColor());
        c.translate(box.left + padX + iconW + 16 * k, box.top + padY);
        shown.draw(c);
        c.restoreToCount(save);
    }

    private StaticLayout layoutText(String text) {
        int max = Math.round(720 * k);
        int width = (int) Math.min(max, Math.ceil(Layout.getDesiredWidth(text, paint)));
        return StaticLayout.Builder.obtain(text, 0, text.length(), paint, Math.max(1, width))
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
    }
}
