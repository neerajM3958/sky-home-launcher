package com.skyhome.launcher;

import android.util.Log;

/** The app's log (adb logcat -s SkyHomeLauncher). Never logs keys. */
final class AppLog {
    private AppLog() {}

    static void w(String message) {
        Log.i("SkyHomeLauncher", message);
    }
}
