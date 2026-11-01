package com.skyhome.launcher;

import com.skyhome.launcher.api.Http;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Updates Sky Home Launcher from this project's GitHub releases (Settings > Check for Updates), and looks once a day in
 * the background. The newest published release is the one marked Latest: drafts and pre-releases never count.
 * A release build carries its tag as versionName ("v1.3.0"), compared number by number. A local build carries
 * "local-<UTC time it was built>", compared with when the release was published, so a build made after the release is
 * never offered the release back. The file must be this app, signed with the same key as the installed one, before it
 * goes to Android's installer, which asks the person to confirm.
 * What is known (the newest tag) is kept in a small file, because the job runs in the main process and the settings
 * screen in :home.
 */
final class UpdateCheck {
    private UpdateCheck() {}

    private static final String REPO = "neerajM3958/sky-home-launcher";
    private static final String ASSET = "SkyHomeLauncher.apk";
    private static final String ACTION = "com.skyhome.launcher.UPDATE_INSTALLED";
    private static final String CHANNEL = "updates";
    private static final int JOB_ID = 77, NOTIFICATION_ID = 77;
    private static final long DAY_MS = 24 * 3600_000L;

    /** What the person sees on the row while something is going on; "" otherwise. */
    private static volatile String line = "";
    private static volatile boolean running;
    private static volatile String latestTag = "";      // the newest release, as last read from disk
    private static volatile boolean available, checked;

    // MARK: - What the row shows

    static boolean busy() {
        return running;
    }

    /** The row's value: how the work is going, or what the last check found. */
    static String status() {
        if (!line.isEmpty()) return line;
        if (available) return Ui.trf("{version} available", "version", latestTag);
        return checked ? Ui.tr("Up to date") : "";
    }

    private static void say(String text) {
        line = text;     // the settings screen reads it a few times a second
    }

    // MARK: - The newest release

    private static final class Latest {
        String tag, url;
        long publishedAt, size;
    }

    private static Latest fetch() throws IOException {
        String body = Http.to("https://api.github.com/repos/" + REPO + "/releases/latest").label("GitHub")
                .header("Accept", "application/vnd.github+json").timeouts(10_000, 20_000).get();
        try {
            JSONObject release = new JSONObject(body);
            Latest l = new Latest();
            l.tag = release.getString("tag_name");
            l.publishedAt = utc("yyyy-MM-dd'T'HH:mm:ss'Z'", release.getString("published_at"));
            JSONArray assets = release.optJSONArray("assets");
            for (int i = 0; assets != null && i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if (ASSET.equals(a.optString("name"))) {
                    l.url = a.optString("browser_download_url");
                    l.size = a.optLong("size");
                }
            }
            // Only this project's own release files, whatever the answer says.
            if (l.url == null || !l.url.startsWith("https://github.com/" + REPO + "/releases/download/")) {
                throw new IOException(Ui.tr("The newest release has no app file"));
            }
            return l;
        } catch (JSONException | ParseException e) {
            throw new IOException(Ui.tr("GitHub's answer couldn't be read"));
        }
    }

    private static long utc(String pattern, String text) throws ParseException {
        SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.parse(text).getTime();
    }

    /** Whether the release is newer than what is installed (see the class comment). */
    static boolean newer(String installed, String tag, long publishedAt) {
        if (installed.startsWith("local-")) {
            try {
                return publishedAt > utc("yyyyMMddHHmmss", installed.substring(6));
            } catch (ParseException e) {
                return false;
            }
        }
        String[] a = digits(installed), b = digits(tag);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            long x = number(a, i), y = number(b, i);
            if (x != y) return y > x;
        }
        return false;
    }

    /** "v1.3.0" is {1, 3, 0}. */
    private static String[] digits(String version) {
        return version.replaceFirst("^[^0-9]+", "").split("[^0-9]+");
    }

    private static long number(String[] parts, int i) {
        if (i >= parts.length || parts[i].isEmpty() || parts[i].length() > 9) return 0;
        return Long.parseLong(parts[i]);
    }

    private static String installedVersion(Context c) {
        try {
            String v = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            return v == null ? "" : v;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    // MARK: - The little file

    private static File file(Context c) {
        return new File(c.getFilesDir(), "update-check.json");
    }

    private static JSONObject read(Context c) {
        File f = file(c);
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] data = new byte[(int) f.length()];
            int got = 0, n;
            while (got < data.length && (n = in.read(data, got, data.length - got)) > 0) got += n;
            return new JSONObject(new String(data, 0, got, StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            return new JSONObject();
        }
    }

    private static void write(Context c, JSONObject o) {
        File part = new File(c.getFilesDir(), "update-check.json.part");
        try (FileOutputStream out = new FileOutputStream(part)) {
            out.write(o.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        part.renameTo(file(c));
    }

    /** Reads what the last check found (the settings screen asks every couple of seconds). */
    static void refresh(Context c) {
        JSONObject o = read(c);
        latestTag = o.optString("tag");
        checked = o.optLong("checkedAt") > 0;
        available = !latestTag.isEmpty() && newer(installedVersion(c), latestTag, o.optLong("publishedAt"));
    }

    /** Asks GitHub, remembers the answer, and returns the release when it is newer than what is installed. */
    private static Latest check(Context c) throws IOException {
        Latest l = fetch();
        JSONObject o = read(c);
        try {
            o.put("tag", l.tag).put("publishedAt", l.publishedAt).put("checkedAt", System.currentTimeMillis());
        } catch (JSONException ignored) {}
        write(c, o);
        refresh(c);
        return newer(installedVersion(c), l.tag, l.publishedAt) ? l : null;
    }

    // MARK: - Settings > Check for Updates

    /** Checks now; when there is a newer release, downloads it and hands it to the installer (once at a time). */
    static void start(Context context) {
        if (running) return;
        running = true;
        Context c = context.getApplicationContext();
        new Thread(() -> {
            try {
                say(Ui.tr("Checking…"));
                Latest l = check(c);
                if (l == null) {
                    running = false;
                    say("");
                    return;
                }
                File apk = download(c, l);
                say(Ui.tr("Installing…"));
                ApkInstall.commit(c, apk, c.getPackageName(), Result.class, ACTION);
            } catch (IOException | RuntimeException e) {
                AppLog.w("update: " + e);
                running = false;
                say("");
                HomeMode.notice(c, Ui.trf("Couldn't update: {error}", "error", String.valueOf(e.getMessage())));
            }
        }, "update-check").start();
    }

    private static File download(Context c, Latest l) throws IOException {
        File file = new File(c.getCacheDir(), "update.apk");
        File part = new File(c.getCacheDir(), "update.apk.part");
        int[] shown = {-1};
        Http.to(l.url).timeouts(10_000, 30_000).download(part, (got, total) -> {
            int percent = total > 0 ? (int) (got * 100 / total) : 0;
            if (percent / 2 != shown[0]) {
                shown[0] = percent / 2;
                say(Ui.trf("Downloading {n}%", "n", percent));
            }
        });
        try {
            verify(c, part, l);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        }
        if (!part.renameTo(file)) throw new IOException("couldn't save the download");
        return file;
    }

    @SuppressWarnings("deprecation")
    private static void verify(Context c, File apk, Latest l) throws IOException {
        if (l.size > 0 && apk.length() != l.size) throw new IOException(Ui.tr("the download is incomplete"));
        PackageManager pm = c.getPackageManager();
        PackageInfo file = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNATURES);
        if (file == null || !c.getPackageName().equals(file.packageName)) throw new IOException(Ui.tr("the file isn't Sky Home Launcher"));
        try {
            PackageInfo mine = pm.getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNATURES);
            if (!sameKey(mine.signatures, file.signatures)) {
                // A build made on a computer is signed with that computer's key; Android refuses to replace it with the release.
                throw new IOException(Ui.tr("this copy was signed with another key, so the release can't replace it. Install the release with adb instead"));
            }
        } catch (PackageManager.NameNotFoundException e) {
            throw new IOException(e.getMessage());
        }
    }

    private static boolean sameKey(Signature[] a, Signature[] b) {
        return a != null && b != null && a.length > 0 && Arrays.equals(a, b);
    }

    /** What Android's installer says: asks the person to confirm. On success the app is replaced and restarts. */
    public static final class Result extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                if (ApkInstall.confirm(context, intent)) say(Ui.tr("Confirm on the TV"));
                return;
            }
            running = false;
            say("");
            if (status == PackageInstaller.STATUS_SUCCESS) return;
            if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
                HomeMode.notice(context, Ui.tr("The update wasn't installed (cancelled, or installs from Sky Home Launcher aren't allowed)."));
            } else {
                String why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                HomeMode.notice(context, Ui.trf("Couldn't update: {error}", "error", why == null ? "?" : why));
            }
        }
    }

    // MARK: - Once a day

    /** Plans the daily look (kept across reboots); a second call leaves the plan as it is. */
    static void schedule(Context c) {
        JobScheduler scheduler = c.getSystemService(JobScheduler.class);
        if (scheduler == null || scheduler.getPendingJob(JOB_ID) != null) return;
        scheduler.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, Job.class))
                .setPeriodic(DAY_MS).setPersisted(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build());
    }

    /** The daily look, in the main process. A new release is announced once: a toast on the home screen and a notification. */
    public static final class Job extends JobService {
        @Override public boolean onStartJob(JobParameters params) {
            final Context app = getApplicationContext();
            new Thread(() -> {
                try {
                    Latest l = check(app);
                    if (l != null) announce(app, l.tag);
                } catch (IOException | RuntimeException e) {
                    AppLog.w("update check: " + e);
                }
                jobFinished(params, false);
            }, "update-daily").start();
            return true;
        }

        @Override public boolean onStopJob(JobParameters params) {
            return true;    // the network went: try again later
        }
    }

    private static void announce(Context c, String tag) {
        JSONObject o = read(c);
        if (tag.equals(o.optString("announced"))) return;
        try {
            write(c, o.put("announced", tag));
        } catch (JSONException ignored) {}
        String text = Ui.trf("Update available: {version}. Open Settings › Check for Updates.", "version", tag);
        HomeMode.notice(c, text);     // the home screen shows it as a toast
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, Ui.tr("Updates"), NotificationManager.IMPORTANCE_DEFAULT));
            PendingIntent open = PendingIntent.getActivity(c, 0, new Intent(c, SettingsActivity.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            nm.notify(NOTIFICATION_ID, new Notification.Builder(c, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("Sky Home Launcher").setContentText(text)
                    .setContentIntent(open).setAutoCancel(true).build());
        } catch (RuntimeException e) {
            AppLog.w("update notification: " + e);
        }
    }
}
