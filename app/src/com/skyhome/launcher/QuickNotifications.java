package com.skyhome.launcher;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The notifications the Quick Settings card lists. Android lets an app read them only when the person allowed it; the
 * switch to Sky Home Launcher mode allows it through the TV's own ADB and Original takes it back (HomeMode). Nothing is
 * kept: the card asks when it opens, and what it read is dropped when it closes.
 */
public class QuickNotifications extends NotificationListenerService {
    static final class Row {
        String key, app, title, text;
    }

    private static volatile QuickNotifications instance;

    /** Allowed and connected. */
    static boolean connected() {
        return instance != null;
    }

    /** The notifications the person can clear, newest first. Empty when not allowed. */
    static List<Row> snapshot() {
        QuickNotifications me = instance;
        if (me == null) return Collections.emptyList();
        StatusBarNotification[] all;
        try {
            all = me.getActiveNotifications();
        } catch (RuntimeException e) {
            return Collections.emptyList();
        }
        if (all == null) return Collections.emptyList();
        List<StatusBarNotification> kept = new ArrayList<>();
        for (StatusBarNotification n : all) {
            if (n.isClearable() && (n.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) == 0) kept.add(n);
        }
        Collections.sort(kept, (a, b) -> Long.compare(b.getPostTime(), a.getPostTime()));
        PackageManager pm = me.getPackageManager();
        List<Row> out = new ArrayList<>();
        for (StatusBarNotification n : kept) {
            Bundle extras = n.getNotification().extras;
            Row r = new Row();
            r.key = n.getKey();
            r.title = text(extras, Notification.EXTRA_TITLE);
            r.text = text(extras, Notification.EXTRA_TEXT);
            if (r.title.isEmpty() && r.text.isEmpty()) continue;
            r.app = label(pm, n.getPackageName());
            out.add(r);
        }
        return out;
    }

    private static String text(Bundle extras, String key) {
        CharSequence s = extras == null ? null : extras.getCharSequence(key);
        return s == null ? "" : s.toString().trim();
    }

    private static String label(PackageManager pm, String pkg) {
        try {
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            String last = pkg.substring(pkg.lastIndexOf('.') + 1);
            return last.isEmpty() ? pkg : Character.toUpperCase(last.charAt(0)) + last.substring(1);
        }
    }

    /** Opens what the notification opens (its app), then takes it off the list as Android's own shade would. */
    static void open(String key) {
        QuickNotifications me = instance;
        if (me == null) return;
        try {
            for (StatusBarNotification n : me.getActiveNotifications()) {
                if (!n.getKey().equals(key)) continue;
                PendingIntent pi = n.getNotification().contentIntent;
                boolean sent = false;
                if (pi != null) {
                    try {
                        pi.send();
                        sent = true;
                    } catch (PendingIntent.CanceledException ignored) {
                    }
                }
                if (!sent) {
                    Intent launch = me.getPackageManager().getLaunchIntentForPackage(n.getPackageName());
                    if (launch != null) me.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                }
                if ((n.getNotification().flags & Notification.FLAG_AUTO_CANCEL) != 0) me.cancelNotification(key);
                return;
            }
        } catch (RuntimeException e) {
            AppLog.w("quick settings: could not open a notification: " + e.getClass().getSimpleName());
        }
    }

    static void clearAll() {
        QuickNotifications me = instance;
        if (me == null) return;
        try {
            me.cancelAllNotifications();
        } catch (RuntimeException ignored) {
        }
    }

    @Override public void onListenerConnected() {
        instance = this;
        QuickCard.notificationsChanged();
    }

    @Override public void onListenerDisconnected() {
        if (instance == this) instance = null;
        QuickCard.notificationsChanged();
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        QuickCard.notificationsChanged();
    }

    @Override public void onNotificationRemoved(StatusBarNotification sbn) {
        QuickCard.notificationsChanged();
    }
}
