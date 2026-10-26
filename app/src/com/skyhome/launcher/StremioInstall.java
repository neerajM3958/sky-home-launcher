package com.skyhome.launcher;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.os.Build;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Installs Stremio's Android TV app from Stremio's own site (not the Play Store). Nothing about the app's address is
 * written down here: Stremio's download list (api.strem.io) says the version and the build for each processor, and the
 * link is made from that. The file must really be Stremio's app before it goes to Android's installer, which asks the
 * person to confirm (and, the first time, to allow installs from Sky Home Launcher).
 * The state is read by the settings screen, which runs in the same process as the install receiver (:home).
 */
final class StremioInstall {
    private StremioInstall() {}

    private static final String CONFIG = "https://api.strem.io/downloads/config";
    private static final String ACTION = "com.skyhome.launcher.STREMIO_INSTALLED";
    /** What the person sees: one short line, "" when nothing is going on. */
    private static volatile String line = "";
    private static volatile boolean running;
    static volatile Runnable listener;       // the settings screen: redraw

    /** The row's value while Stremio isn't installed. */
    static String status() {
        return line.isEmpty() ? Ui.tr("Install") : line;
    }

    static boolean busy() {
        return running;
    }

    private static void say(String text) {
        line = text;
        Runnable l = listener;
        if (l != null) l.run();
    }

    /** Starts the download and install (once at a time). */
    static void start(Context context) {
        if (running) return;
        running = true;
        Context c = context.getApplicationContext();
        new Thread(() -> {
            try {
                say(Ui.tr("Looking for Stremio…"));
                File apk = download(c);
                say(Ui.tr("Installing…"));
                install(c, apk);
            } catch (IOException | RuntimeException e) {
                AppLog.w("stremio install: " + e);
                running = false;
                say("");
                HomeMode.notice(c, Ui.trf("Couldn't install Stremio: {error}", "error", String.valueOf(e.getMessage())));
            }
        }, "stremio-install").start();
    }

    // MARK: - The link, from Stremio's download list

    private static final Pattern WORD = Pattern.compile("[0-9A-Za-z._-]{1,40}");
    private static final Pattern DIGITS = Pattern.compile("[0-9]{1,12}");

    /** {link, version} of the Android TV build for this TV's processor. */
    static String[] link() throws IOException {
        JSONObject entry = null;
        try {
            JSONObject all = new JSONObject(get(CONFIG));
            for (int i = 0; i < all.getJSONArray("entries").length(); i++) {
                JSONObject e = all.getJSONArray("entries").getJSONObject(i);
                if ("android_tv".equals(e.optString("id"))) entry = e.getJSONObject("value");
            }
        } catch (org.json.JSONException e) {
            throw new IOException(Ui.tr("Stremio's download list couldn't be read"));
        }
        if (entry == null) throw new IOException(Ui.tr("Stremio's download list has no Android TV app"));
        // Android's names for the processor, and Stremio's key for each in the list.
        String[][] abis = {{"armeabi-v7a", "arm"}, {"arm64-v8a", "arm64"}, {"x86", "x86"}, {"x86_64", "x86_64"}};
        JSONObject codes = entry.optJSONObject("versionCode");
        for (String mine : Build.SUPPORTED_ABIS) {
            for (String[] abi : abis) {
                if (!abi[0].equals(mine) || codes == null || !codes.has(abi[1])) continue;
                String tag = entry.optString("tag"), version = entry.optString("versionName"), code = codes.optString(abi[1]);
                if (!WORD.matcher(tag).matches() || !WORD.matcher(version).matches() || !DIGITS.matcher(code).matches()) {
                    throw new IOException(Ui.tr("Stremio's download list looks wrong"));
                }
                return new String[] {"https://dl.strem.io/android/" + tag + "-androidTV/com.stremio.one-" + version + "-" + code
                        + "-" + abi[0] + ".apk", version};
            }
        }
        throw new IOException(Ui.tr("no download for this TV's processor"));
    }

    private static String get(String address) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(address).openConnection();
        h.setRequestProperty("User-Agent", "SkyHomeLauncher");
        h.setConnectTimeout(10_000);
        h.setReadTimeout(20_000);
        try (InputStream in = h.getInputStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toString("UTF-8");
        } finally {
            h.disconnect();
        }
    }

    private static File download(Context c) throws IOException {
        String[] found = link();
        File file = new File(c.getCacheDir(), "stremio.apk");
        HttpURLConnection h = (HttpURLConnection) new URL(found[0]).openConnection();
        h.setRequestProperty("User-Agent", "SkyHomeLauncher");
        h.setConnectTimeout(10_000);
        h.setReadTimeout(30_000);
        File part = new File(c.getCacheDir(), "stremio.apk.part");
        try (InputStream in = h.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
            long total = h.getContentLengthLong(), got = 0;
            int shown = -1;
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
                got += n;
                int percent = total > 0 ? (int) (got * 100 / total) : 0;
                if (percent / 2 != shown) {
                    shown = percent / 2;
                    say(Ui.trf("Downloading {n}%", "n", percent));
                }
            }
        } finally {
            h.disconnect();
        }
        PackageInfo info = c.getPackageManager().getPackageArchiveInfo(part.getPath(), 0);
        if (info == null || !Routing.STREMIO.equals(info.packageName)) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw new IOException(Ui.tr("the file isn't Stremio's app"));
        }
        if (!part.renameTo(file)) throw new IOException("couldn't save the download");
        return file;
    }

    // MARK: - Android's installer

    private static void install(Context c, File apk) throws IOException {
        PackageInstaller installer = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(Routing.STREMIO);
        int id = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = session.openWrite("stremio.apk", 0, apk.length())) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                session.fsync(out);
            }
            Intent done = new Intent(c, Result.class).setAction(ACTION).setPackage(c.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            session.commit(PendingIntent.getBroadcast(c, id, done, flags).getIntentSender());
        }
    }

    /** What Android's installer says: asks the person to confirm, then success or why not. */
    public static final class Result extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    say(Ui.tr("Confirm on the TV"));
                    try {
                        context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (RuntimeException e) {
                        AppLog.w("stremio install: " + e);
                    }
                }
                return;
            }
            running = false;
            say("");
            if (status == PackageInstaller.STATUS_SUCCESS) {
                HomeMode.notice(context, Ui.tr("Stremio is installed."));
            } else if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
                HomeMode.notice(context, Ui.tr("Stremio wasn't installed (cancelled, or installs from Sky Home Launcher aren't allowed)."));
            } else {
                String why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                // Not allowed yet ("install unknown apps"), or the person said no: the file stays for the next try.
                HomeMode.notice(context, Ui.trf("Couldn't install Stremio: {error}", "error", why == null ? "?" : why));
            }
        }
    }
}
