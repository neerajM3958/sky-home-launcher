package com.skyhome.launcher;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Debug only (config.properties: show_memory_card): a small card, top right, over any app, with free and total RAM
 * (read straight off this process, no ADB needed) and the app in front's own memory (needs ADB, so it reads "—" until
 * that's been granted, same as SleepOverlay's pill). Its own process (:memcard), like SleepOverlay, and not tied to
 * Sky Home Launcher mode: once the overlay permission is granted it keeps running under the TV's own launcher too.
 */
public class MemoryCard extends Service {
    private static final int REFRESH_MS = 2_000;
    // Android 12 and older: "mResumedActivity: ActivityRecord{... u0 pkg/.Cls}". Android 13+: "topResumedActivity=...".
    private static final Pattern RESUMED = Pattern.compile("(?:mResumedActivity|topResumedActivity)[:=]\\s*ActivityRecord\\{\\S+\\s+\\S+\\s+([\\w.]+)/");
    // "Total PSS by process:" lines: "    219,009K: com.skyhome.launcher:home (pid 11287 / activities)". A package with
    // several processes (ours among them) needs every one of them summed, not just the one named exactly like the package.
    private static final Pattern PSS_LINE = Pattern.compile("([\\d,]+)K: (\\S+) \\(pid");

    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView view;
    private volatile boolean running;
    private Thread worker;

    /** Starts it if config.properties turned it on; grants the overlay permission through ADB first, if needed. */
    static void start(Context c) {
        if (!Config.SHOW_MEMORY_CARD) return;
        Intent i = new Intent(c, MemoryCard.class);
        if (Settings.canDrawOverlays(c)) {
            c.startForegroundService(i);
        } else if (Capabilities.adb(c)) {
            allow(c.getApplicationContext(), i);
        }   // else: no overlay permission yet and no ADB to grant it now; tried again next boot or switch
    }

    private static final AtomicBoolean allowing = new AtomicBoolean();

    private static void allow(Context c, Intent toStart) {
        if (!allowing.compareAndSet(false, true)) return;
        new Thread(() -> {
            try (Adb adb = Adb.connect(c, 10_000, null)) {
                adb.shell("appops set " + HomeMode.PKG + " SYSTEM_ALERT_WINDOW allow");
            } catch (IOException e) {
                AppLog.w("memory card overlay: " + e.getMessage());
                allowing.set(false);
                return;
            }
            boolean works = Settings.canDrawOverlays(c);
            for (int i = 0; i < 5 && !works; i++) {
                SystemClock.sleep(200);
                works = Settings.canDrawOverlays(c);
            }
            allowing.set(false);
            if (works) c.startForegroundService(toStart);
        }).start();
    }

    @Override public void onCreate() {
        super.onCreate();
        Ui.init(this);   // its own process: the app's font and translations aren't loaded here yet
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("memcard", Ui.tr("Memory"), NotificationManager.IMPORTANCE_MIN));
        startForeground(2, new Notification.Builder(this, "memcard").setSmallIcon(R.drawable.icon)
                .setContentTitle(Ui.tr("Memory")).build());
        float k = getResources().getDisplayMetrics().widthPixels / 1920f;
        view = new TextView(this);
        view.setTypeface(Ui.regular);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, 22 * k);
        view.setTextColor(0xFFFFFFFF);
        view.setLineSpacing(6 * k, 1);
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(16 * k);
        pill.setColor(0xCC1C1C1E);
        view.setBackground(pill);
        view.setPadding(Math.round(20 * k), Math.round(14 * k), Math.round(20 * k), Math.round(14 * k));
        WindowManager.LayoutParams layout = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        layout.gravity = Gravity.TOP | Gravity.RIGHT;
        layout.x = layout.y = Math.round(32 * k);
        getSystemService(WindowManager.class).addView(view, layout);
        startPolling();
    }

    private void startPolling() {
        running = true;
        worker = new Thread(() -> {
            Adb adb = null;
            while (running) {
                long start = SystemClock.elapsedRealtime();
                long[] freeTotal = freeTotalMB();
                List<String> processLines = new ArrayList<>();
                String appLine = "—";
                if (adb == null) {
                    try { adb = Adb.connect(this, 3_000, null); } catch (IOException e) { adb = null; }
                }
                if (adb != null) {
                    try {
                        long total = appUsage(adb, processLines);
                        if (total >= 0) appLine = Ui.trf("{n} MB", "n", total);
                    } catch (IOException e) {
                        adb.close();
                        adb = null;
                    }
                }
                StringBuilder text = new StringBuilder();
                for (String line : processLines) text.append(line).append('\n');
                text.append(Ui.trf("App: {app}", "app", appLine)).append('\n')
                        .append(Ui.trf("Free: {n} MB", "n", freeTotal[0])).append('\n')
                        .append(Ui.trf("Total: {n} MB", "n", freeTotal[1]));
                String shown = text.toString();
                main.post(() -> { if (view != null) view.setText(shown); });
                long elapsed = SystemClock.elapsedRealtime() - start;
                if (running) SystemClock.sleep(Math.max(0, REFRESH_MS - elapsed));
            }
            if (adb != null) adb.close();
        }, "memory-card");
        worker.start();
    }

    /** Straight from this process, no ADB: always accurate, in any mode. */
    private long[] freeTotalMB() {
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        getSystemService(ActivityManager.class).getMemoryInfo(info);
        return new long[] {info.availMem / (1024 * 1024), info.totalMem / (1024 * 1024)};
    }

    /**
     * Whichever app is in front: one line per process it has (the label after "pkg:", or "main" for the bare
     * package), appended to `out`, and its total PSS across all of them in MB (-1: couldn't tell). Needs ADB: root
     * sees other apps' memory.
     */
    private long appUsage(Adb adb, List<String> out) throws IOException {
        Matcher r = RESUMED.matcher(adb.shell("dumpsys activity activities"));
        if (!r.find()) return -1;
        String pkg = r.group(1);
        String dump = adb.shell("dumpsys meminfo");
        int start = dump.indexOf("Total PSS by process:");
        if (start < 0) return -1;
        int end = dump.indexOf("\n\n", start);
        Matcher m = PSS_LINE.matcher(end < 0 ? dump.substring(start) : dump.substring(start, end));
        long total = 0;
        boolean found = false;
        while (m.find()) {
            String proc = m.group(2);
            String label = proc.equals(pkg) ? "main" : proc.startsWith(pkg + ":") ? proc.substring(pkg.length() + 1) : null;
            if (label == null) continue;
            long mb = Long.parseLong(m.group(1).replace(",", "")) / 1024;
            out.add(Ui.trf("{proc}: {n} MB", "proc", label, "n", mb));
            total += mb;
            found = true;
        }
        return found ? total : -1;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        if (worker != null) worker.interrupt();
        if (view != null) getSystemService(WindowManager.class).removeView(view);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
