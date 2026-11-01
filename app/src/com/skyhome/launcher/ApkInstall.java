package com.skyhome.launcher;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Hands a downloaded APK to Android's installer, which asks the person to confirm. The receiver gets the answer. */
final class ApkInstall {
    private ApkInstall() {}

    static void commit(Context c, File apk, String packageName, Class<? extends BroadcastReceiver> receiver, String action) throws IOException {
        PackageInstaller installer = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(packageName);
        int id = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = session.openWrite("app.apk", 0, apk.length())) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                session.fsync(out);
            }
            Intent done = new Intent(c, receiver).setAction(action).setPackage(c.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            session.commit(PendingIntent.getBroadcast(c, id, done, flags).getIntentSender());
        }
    }

    /** The "confirm on the TV" step; false when Android gave nothing to open. */
    static boolean confirm(Context context, Intent result) {
        Intent confirm = result.getParcelableExtra(Intent.EXTRA_INTENT);
        if (confirm == null) return false;
        try {
            context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            AppLog.w("install confirm: " + e);
        }
        return true;
    }
}
