package com.skyhome.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Quick Settings: a card that slides in from the right over any app when a remote button set to it is pressed
 * (RemoteKeys). It is a window of the key service (an accessibility overlay), in the :keys process, so it draws by
 * hand and loads next to nothing. The window takes the focus: the app below pauses and the D-pad is the card's.
 * Tiles open the TV's own screens (nothing is changed from here); the notifications are listed and cleared through
 * QuickNotifications.
 */
final class QuickCard {
    private QuickCard() {}

    private static final int TITLE = 0, TILE = 1, HEADER = 2, NOTE = 3, CLEAR = 4, EMPTY = 5;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static CardView view;
    private static WindowManager windows;
    private static long openedAt, lastToggle;
    private static String lastLanguage;

    static boolean isOpen() {
        return view != null;
    }

    /** Sent from ADB (`am broadcast -a com.skyhome.launcher.QUICK_SETTINGS --es do show|hide|toggle`): see RemoteKeyService. */
    static final String ACTION = "com.skyhome.launcher.QUICK_SETTINGS";

    /** The remote button: opens the card, or closes it when it is open. Call on the main thread. */
    static void toggle(Context c) {
        long now = System.currentTimeMillis();
        if (now - lastToggle < 500) return;     // the remote can send one press twice
        lastToggle = now;
        if (view != null) close(); else show(c);
    }

    /** Opens the card (nothing happens when it is already open). Call on the main thread. */
    static void show(Context c) {
        if (view != null) return;
        try {
            Context app = c.getApplicationContext();
            QuickLook look = QuickLook.load(app);
            Ui.init(app);
            if (!look.language.equals(lastLanguage)) {
                lastLanguage = look.language;
                Ui.applyLanguage(look.language);
            }
            windows = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
            CardView v = new CardView(c, look);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(v.panelWidth, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.END | Gravity.TOP;
            windows.addView(v, lp);
            v.setTranslationX(v.panelWidth);
            v.animate().translationX(0).setDuration(180).start();
            v.requestFocus();
            view = v;
            openedAt = System.currentTimeMillis();
            AppLog.w("quick settings opened");
        } catch (RuntimeException e) {
            AppLog.w("quick settings: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            view = null;
        }
    }

    static void close() {
        CardView v = view;
        view = null;
        if (v == null) return;
        try {
            windows.removeView(v);
        } catch (RuntimeException ignored) {
        }
        v.release();
    }

    /** Another app came to the front (the key service saw its window): the card doesn't stay over it. */
    static void closeFor(String pkg) {
        if (view != null && System.currentTimeMillis() - openedAt > 800 && !HomeMode.PKG.equals(pkg)) {
            AppLog.w("quick settings closed: a window of " + pkg + " came to the front");
            close();
        }
    }

    /** From the listener's thread: a notification came or went. */
    static void notificationsChanged() {
        MAIN.post(() -> {
            if (view != null) view.reload();
        });
    }

    private static final class Row {
        int type;
        String icon, label, key, l1, l2, l3;
        Intent intent;
        android.graphics.drawable.Drawable drawable;   // an added app's icon
        float y, h;
    }

    private static final class CardView extends View {
        final int panelWidth;
        private final Context ctx;
        final float k;
        private final float tileH, tileGap, pillRadius;
        private final int screenH;
        private final List<Row> tiles = new ArrayList<>();
        private final List<Row> rows = new ArrayList<>();
        private int focus;
        private float scroll, target, contentH;
        private boolean released, showNotifications;

        // The background and the focus are the Settings screens' own: the same Scene (the moving waves, the sky of the
        // time of day) and the same Look, drawn the same way.
        private final Scene scene = new Scene(false);
        private final Look look;
        private final float night;
        private final int sw;
        private long startedAt, lastSky;
        private final int ink, inkFocus, muted, noteFill;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint titleText, tileText, small, noteTitle, noteText, value;
        private final RectF rect = new RectF();
        private final Paint edge = new Paint();
        private final Path bluetooth = new Path();

        CardView(Context c, QuickLook q) {
            super(c);
            ctx = c;
            sw = c.getResources().getDisplayMetrics().widthPixels;
            k = sw / 1920f;
            screenH = c.getResources().getDisplayMetrics().heightPixels;
            panelWidth = Math.round(560 * k);
            long now = System.currentTimeMillis();
            night = HomeData.isNight(q.theme, now, q.sunrise, q.sunset) ? 1 : 0;
            Shaders.sky(scene.palette, now, q.sunrise, q.sunset);
            scene.setMotion(0.5f, 0.2f);
            scene.applyPalette();
            scene.setSize(sw, screenH);
            look = Themes.create(q.look, scene);
            look.setSize(sw, screenH, k);
            startedAt = SystemClock.uptimeMillis();
            // The text takes the Look's own ink, as Settings does (white on the glass, dark on Material's light surface).
            ink = look.inkAlpha(0xF2FFFFFF, night);
            inkFocus = look.inkFocusedAlpha(0xFF1C1C1E, night);
            boolean lightSurface = Color.luminance(look.ink(0xFFFFFFFF, night)) < 0.5f;
            muted = look.inkAlpha(lightSurface ? 0xCCFFFFFF : 0x8CFFFFFF, night);      // stronger on a light surface, where grey is faint
            noteFill = lightSurface ? 0x14000000 : 0x1AFFFFFF;
            edge.setColor(lightSurface ? 0x26000000 : 0x26FFFFFF);
            // Rows as in Settings: 68 high, 6 apart, a pill of radius 18 when focused.
            tileH = 68 * k;
            tileGap = 6 * k;
            pillRadius = 18 * k;
            titleText = Ui.text(Ui.displaySemibold, 34 * k, look.ink(0xFFFFFFFF, night));
            tileText = Ui.text(Ui.medium, 30 * k, ink);
            small = Ui.text(Ui.medium, 24 * k, muted);
            value = Ui.text(Ui.medium, 24 * k, muted);
            noteTitle = Ui.text(Ui.semibold, 28 * k, ink);
            noteText = Ui.text(Ui.regular, 24 * k, muted);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(3.2f * k);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            float s = 14 * k;
            bluetooth.moveTo(-0.55f * s, -0.5f * s);
            bluetooth.lineTo(0.55f * s, 0.5f * s);
            bluetooth.lineTo(0, s);
            bluetooth.lineTo(0, -s);
            bluetooth.lineTo(0.55f * s, -0.5f * s);
            bluetooth.lineTo(-0.55f * s, 0.5f * s);
            setFocusable(true);
            setFocusableInTouchMode(true);
            buildTiles(q.tiles);
            reload();
        }

        void release() {
            released = true;
            rows.clear();
            tiles.clear();
        }

        // MARK: - Content

        private void tile(String icon, String label, Intent intent) {
            Row r = new Row();
            r.type = TILE;
            r.icon = icon;
            r.label = label;
            r.l1 = r.label;
            r.intent = intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            tiles.add(r);
        }

        /** The tiles the person chose (Home Settings > Quick Settings tiles), in their order. */
        private void buildTiles(String keys) {
            PackageManager pm = ctx.getPackageManager();
            for (String key : keys.split(",")) {
                switch (key) {
                    case "settings":
                        tile("settings", Ui.tr("Settings"), new Intent(Settings.ACTION_SETTINGS));
                        break;
                    case "wifi": {
                        Intent wifi = new Intent(Settings.ACTION_WIFI_SETTINGS);
                        if (pm.resolveActivity(wifi, 0) == null) wifi = new Intent(Settings.ACTION_WIRELESS_SETTINGS);
                        if (pm.resolveActivity(wifi, 0) != null) tile("wifi", Ui.tr("Wi-Fi"), wifi);
                        break;
                    }
                    case "bluetooth": {
                        // Google TV's list of paired and connected devices (with "Pair remote or accessory") answers this action;
                        // Android's own Bluetooth screen is the fallback.
                        Intent bt = new Intent("com.android.tv.settings.CONNECTED_DEVICES");
                        if (pm.resolveActivity(bt, 0) == null) bt = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
                        if (pm.resolveActivity(bt, 0) != null) tile("bluetooth", Ui.tr("Bluetooth"), bt);
                        break;
                    }
                    case "inputs":
                        inputs();
                        break;
                    case "home":
                        tile("home", Ui.tr("Home"), new Intent().setClassName(ctx, "com.skyhome.launcher.HomeActivity"));
                        break;
                    case "apps":
                        tile("apps", Ui.tr("Apps"), new Intent().setClassName(ctx, "com.skyhome.launcher.HomeActivity").putExtra("apps", true));
                        break;
                    case "notifications":
                        showNotifications = true;
                        break;
                    default:
                        if (key.startsWith("app:")) appTile(pm, key.substring(4));
                        break;
                }
            }
        }

        /** A tile the person added: opens an app, with the app's own icon and name. Left out when it is gone. */
        private void appTile(PackageManager pm, String pkg) {
            Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
            if (launch == null) return;
            try {
                Row r = new Row();
                r.type = TILE;
                r.icon = "app";
                r.label = r.l1 = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
                r.drawable = pm.getApplicationIcon(pkg);
                r.intent = launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                tiles.add(r);
            } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            }
        }

        /** One tile per input the TV has (HDMI ports, AV…), by the name the TV gives it. */
        private void inputs() {
            TvInputManager tim = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
            if (tim == null) return;
            List<TvInputInfo> list = new ArrayList<>();
            try {
                for (TvInputInfo info : tim.getTvInputList()) {
                    if (info.isPassthroughInput() && info.getParentId() == null && !info.isHidden(ctx)) list.add(info);
                }
            } catch (RuntimeException e) {
                return;
            }
            Collections.sort(list, (a, b) -> a.getId().compareTo(b.getId()));
            for (int i = 0; i < list.size(); i++) {
                TvInputInfo info = list.get(i);
                CharSequence custom = null;
                try {
                    custom = info.loadCustomLabel(ctx);
                } catch (RuntimeException ignored) {
                }
                String name = custom != null && custom.length() > 0 ? custom.toString() : String.valueOf(info.loadLabel(ctx)).trim();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (name.isEmpty()) name = (hdmi ? "HDMI " : "AV ") + (i + 1);
                Row r = new Row();
                r.type = TILE;
                r.icon = hdmi ? "hdmi" : "av";
                r.label = r.l1 = name;
                r.intent = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(info.getId()))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                tiles.add(r);
            }
        }

        /** Lays the list out again with the notifications as they are now, keeping the focus on the same row. */
        void reload() {
            if (released) return;
            String focused = focus >= 0 && focus < rows.size() ? rows.get(focus).key : null;
            String focusedLabel = focus >= 0 && focus < rows.size() ? rows.get(focus).label : null;
            rows.clear();
            float inner = panelWidth - 2 * 32 * k;
            float y = 28 * k;
            y = add(TITLE, Ui.tr("Quick Settings"), y, 84 * k, titleText, inner);
            for (Row t : tiles) {
                t.y = y;
                t.h = tileH;
                y += t.h + tileGap;
                rows.add(t);
            }
            if (showNotifications) y = notificationSection(y + 14 * k, inner);
            contentH = y + 28 * k;
            focus = -1;
            for (int i = 0; i < rows.size() && focus < 0; i++) {
                Row r = rows.get(i);
                if (focusable(r) && (focused != null ? focused.equals(r.key) : focusedLabel != null && focusedLabel.equals(r.label))) focus = i;
            }
            if (focus < 0) focus = firstFocusable();
            updateTarget(false);
            invalidate();
        }

        /** The header, the notifications (or why there are none) and the Clear button, from `y`; returns where they end. */
        private float notificationSection(float y, float inner) {
            y = add(HEADER, Ui.tr("Notifications"), y, 64 * k, small, inner);
            if (!QuickNotifications.connected()) {
                y = add(EMPTY, Ui.tr("Notifications are unavailable"), y, 70 * k, noteText, inner);
            } else {
                List<QuickNotifications.Row> notes = QuickNotifications.snapshot();
                if (notes.isEmpty()) {
                    y = add(EMPTY, Ui.tr("No notifications"), y, 70 * k, noteText, inner);
                } else {
                    for (QuickNotifications.Row n : notes) {
                        Row r = new Row();
                        r.type = NOTE;
                        r.key = n.key;
                        r.label = n.key;
                        float w = inner - 48 * k;
                        r.l1 = fit(n.app, small, w);
                        r.l2 = fit(n.title.isEmpty() ? n.text : n.title, noteTitle, w);
                        r.l3 = n.title.isEmpty() ? "" : fit(n.text, noteText, w);
                        r.y = y;
                        r.h = 136 * k;
                        y += r.h + 10 * k;
                        rows.add(r);
                    }
                    y = add(CLEAR, Ui.tr("Clear"), y + 6 * k, 84 * k, tileText, inner);
                }
            }
            return y;
        }

        private float add(int type, String label, float y, float h, Paint p, float width) {
            Row r = new Row();
            r.type = type;
            r.label = label;
            r.l1 = fit(label, p, width);
            r.y = y;
            r.h = h;
            rows.add(r);
            return y + h;
        }

        private String fit(String s, Paint p, float width) {
            if (s == null || s.isEmpty() || p.measureText(s) <= width) return s == null ? "" : s;
            int n = p.breakText(s, true, width - p.measureText("…"), null);
            return s.substring(0, Math.max(0, n)).trim() + "…";
        }

        private boolean focusable(Row r) {
            return r.type == TILE || r.type == NOTE || r.type == CLEAR;
        }

        private int firstFocusable() {
            for (int i = 0; i < rows.size(); i++) if (focusable(rows.get(i))) return i;
            return 0;
        }

        private void move(int dir) {
            for (int i = focus + dir; i >= 0 && i < rows.size(); i += dir) {
                if (focusable(rows.get(i))) {
                    focus = i;
                    updateTarget(true);
                    invalidate();
                    return;
                }
            }
        }

        private void updateTarget(boolean animate) {
            if (focus < 0 || focus >= rows.size()) return;
            Row r = rows.get(focus);
            float max = Math.max(0, contentH - screenH);
            target = Math.max(0, Math.min(max, r.y + r.h / 2 - screenH / 2f));
            if (!animate) scroll = target;
        }

        private void activate() {
            if (focus < 0 || focus >= rows.size()) return;
            Row r = rows.get(focus);
            if (r.type == TILE) {
                Intent intent = r.intent;
                close();
                try {
                    ctx.startActivity(intent);
                } catch (RuntimeException e) {
                    AppLog.w("quick settings: " + r.l1 + " did not open: " + e.getClass().getSimpleName());
                }
            } else if (r.type == NOTE) {
                String key = r.key;
                close();
                QuickNotifications.open(key);
            } else if (r.type == CLEAR) {
                QuickNotifications.clearAll();
                MAIN.postDelayed(this::reload, 250);
            }
        }

        // MARK: - Keys

        @Override public boolean dispatchKeyEvent(KeyEvent e) {
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (down) move(-1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down) move(1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (down && e.getRepeatCount() == 0) activate();
                    return true;
                case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_ESCAPE:
                    if (down) close();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT:
                    return true;
                default:
                    return super.dispatchKeyEvent(e);
            }
        }

        // MARK: - Drawing

        @Override protected void onDraw(Canvas canvas) {
            float d = target - scroll;
            if (Math.abs(d) > 0.5f) {
                scroll += d * 0.25f;
                postInvalidateOnAnimation();
            } else {
                scroll = target;
            }
            current = canvas;
            long up = SystemClock.uptimeMillis();
            if (up - lastSky >= 100) {          // the waves move in steps, as in Settings
                lastSky = up;
                scene.advance((up - startedAt) / 1000f);
            }
            canvas.save();
            canvas.translate(-(sw - getWidth()), 0);       // this is the right edge of the screen's background
            rect.set(0, 0, sw, screenH);
            if (!look.background(canvas, rect, night)) scene.drawBackground(canvas);
            canvas.restore();
            canvas.drawRect(0, 0, 2 * k, getHeight(), edge);     // the card's edge over the app
            if (!released) postInvalidateDelayed(100);
            fill.setStyle(Paint.Style.FILL);
            float left = 32 * k, right = getWidth() - 32 * k;
            canvas.save();
            canvas.translate(0, -scroll);
            for (int i = 0; i < rows.size(); i++) {
                Row r = rows.get(i);
                if (r.y + r.h < scroll || r.y > scroll + getHeight()) continue;
                boolean on = i == focus;
                switch (r.type) {
                    case TITLE:
                        canvas.drawText(r.l1, left, r.y + r.h * 0.7f, titleText);
                        break;
                    case HEADER:
                        canvas.drawText(r.l1, left + 4 * k, r.y + r.h * 0.7f, small);
                        break;
                    case EMPTY:
                        canvas.drawText(r.l1, left + 4 * k, r.y + r.h * 0.6f, noteText);
                        break;
                    case TILE:
                        box(r, left, right, on);
                        icon(r, left + 44 * k, r.y + r.h / 2, on ? inkFocus : ink);
                        tileText.setColor(on ? inkFocus : ink);
                        canvas.drawText(r.l1, left + 92 * k, r.y + r.h / 2 + 10 * k, tileText);
                        tileValue(r, left + 92 * k + tileText.measureText(r.l1), right - 24 * k, on);
                        break;
                    case NOTE:
                        box(r, left, right, on);
                        small.setColor(on ? inkFocus : muted);
                        noteTitle.setColor(on ? inkFocus : ink);
                        noteText.setColor(on ? inkFocus : muted);
                        canvas.drawText(r.l1, left + 24 * k, r.y + 36 * k, small);
                        canvas.drawText(r.l2, left + 24 * k, r.y + 72 * k, noteTitle);
                        if (!r.l3.isEmpty()) canvas.drawText(r.l3, left + 24 * k, r.y + 106 * k, noteText);
                        break;
                    case CLEAR:
                        box(r, left, right, on);
                        tileText.setColor(on ? inkFocus : ink);
                        canvas.drawText(r.l1, (left + right) / 2 - tileText.measureText(r.l1) / 2, r.y + r.h / 2 + 10 * k, tileText);
                        break;
                    default:
                        break;
                }
            }
            canvas.restore();
            current = null;
            small.setColor(muted);
            noteTitle.setColor(ink);
            noteText.setColor(muted);
        }

        private void box(Row r, float left, float right, boolean on) {
            rect.set(left, r.y, right, r.y + r.h);
            if (on) {
                look.rowFocus(current, rect, pillRadius, 1, night);       // the focus of a Settings row
            } else if (r.type != TILE) {
                fill.setColor(noteFill);
                current.drawRoundRect(rect, pillRadius, pillRadius, fill);
            }
        }

        /** The Wi-Fi and Bluetooth tiles show what they are connected to at the row's right end, when it fits after the label. */
        private void tileValue(Row r, float labelEnd, float right, boolean on) {
            boolean wifi = "wifi".equals(r.icon);
            if (!wifi && !"bluetooth".equals(r.icon)) return;
            ConnectionStatus.State s = ConnectionStatus.get(ctx).state();
            value.setColor(on ? inkFocus : muted);
            float base = r.y + r.h / 2 + 8 * k;
            float w = wifi ? ConnectionStatus.drawWifi(null, s, right, base, value) : ConnectionStatus.drawBluetooth(null, s, right, base, value, false);
            if (w == 0 || right - w < labelEnd + 24 * k) return;
            if (wifi) ConnectionStatus.drawWifi(current, s, right, base, value);
            else ConnectionStatus.drawBluetooth(current, s, right, base, value, false);
        }

        private final Runnable statusChanged = this::postInvalidate;

        // The values follow the connection while the card is open.
        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            ConnectionStatus.get(ctx).addListener(statusChanged);
        }

        @Override protected void onDetachedFromWindow() {
            ConnectionStatus.get(ctx).removeListener(statusChanged);
            super.onDetachedFromWindow();
        }

        private Canvas current;   // the canvas of the frame being drawn

        private void icon(Row row, float cx, float cy, int color) {
            String name = row.icon;
            if (name.equals("wifi")) {
                stroke.setColor(color);
                float base = cy + 12 * k;
                for (int i = 1; i <= 3; i++) {
                    float rad = 8 * k * i;
                    rect.set(cx - rad, base - rad, cx + rad, base + rad);
                    current.drawArc(rect, 225, 90, false, stroke);
                }
                fill.setColor(color);
                current.drawCircle(cx, base, 3 * k, fill);
            } else if (name.equals("bluetooth")) {
                stroke.setColor(color);
                current.save();
                current.translate(cx, cy);
                current.drawPath(bluetooth, stroke);
                current.restore();
            } else if (row.drawable != null) {      // an added app: its own icon, in its own colours
                int half = Math.round(22 * k);
                row.drawable.setBounds(Math.round(cx) - half, Math.round(cy) - half, Math.round(cx) + half, Math.round(cy) + half);
                row.drawable.draw(current);
            } else {
                Symbols.draw(ctx, current, name, cx, cy, 30 * k, color);
            }
        }
    }
}
