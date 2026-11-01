package com.skyhome.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.KeyEvent;

/**
 * The remote's app buttons on TCL TVs. The stock home screen opens the apps for Netflix, YouTube and Prime (and TCL
 * Channel); with it off, the keys still arrive, so we open the app the person picked (Settings > Remote Buttons).
 * Disney and Globoplay are opened by the system itself, so they are not listed. The YouTube button (BUTTON_3) never
 * reaches an app's window; only the accessibility service's key filter (RemoteKeyService) sees it.
 * On Vestel TVs and Formovie (Xiaomi) projectors only the Netflix button is ours: the system opens Netflix itself (a
 * global key, after the filter).
 * On KTC TVs (NextStar) only the Tabii button is ours, a global key too: the TV's hot-key receiver opens Tabii with it.
 * On Skyworth TVs the Netflix, YouTube, Prime Video and Disney+ buttons (STEM_1, STEM_2, F8, F6) and the gear button
 * (NOTIFICATION) are ours: Skyworth's own receiver opens the real apps, which may not be installed, and the gear button
 * opens the Quick Settings card; a button set to "TV Settings" opens Android's Settings.
 * On Sony TVs, the app buttons (and the Google Play button of older remotes) are global keys for Sony's own receiver;
 * the filter sees them first, the same way.
 */
final class RemoteKeys {
    private RemoteKeys() {}

    /** Some TCL remotes (BT_RC833) send TCL's own YouTube key instead of BUTTON_3: the same button. */
    private static final int KEY_YOUTUBE_TCL = 4065;

    /** Vendor id of the Skyworth remote (Vendor_0957_*.kl). */
    private static final int SKYWORTH_REMOTE = 0x0957;

    /**
     * The key codes are the brand's own: on other brands they can be other buttons, so nothing here runs there.
     * Vestel builds TVs sold under other brands (F&U...), so BRAND varies: check the maker too.
     */
    static final boolean TCL = is("TCL"), VESTEL = is("Vestel"), KTC = is("KTC"), SONY = is("Sony"), FORMOVIE = is("Formovie"),
            SKYWORTH = is("Skyworth");
    /** Brands whose Netflix button is STEM_1, a global key (MediaTek): only that button is ours. */
    static final boolean STEM_NETFLIX = VESTEL || FORMOVIE;

    static boolean supported() {
        return TCL || STEM_NETFLIX || KTC || SONY || SKYWORTH;
    }

    /** The key service is needed: this brand has buttons of its own, or the person added some (or is adding one now). */
    static boolean wanted() {
        return supported() || Prefs.hasLearned() || Prefs.learning();
    }

    /** Keys the person needs to move around the TV: never added as a button. */
    static boolean needed(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_DPAD_UP_LEFT:
            case KeyEvent.KEYCODE_DPAD_UP_RIGHT: case KeyEvent.KEYCODE_DPAD_DOWN_LEFT: case KeyEvent.KEYCODE_DPAD_DOWN_RIGHT:
            case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER: case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_MENU: case KeyEvent.KEYCODE_ESCAPE: case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN: case KeyEvent.KEYCODE_VOLUME_MUTE: case KeyEvent.KEYCODE_POWER:
            case KeyEvent.KEYCODE_SLEEP: case KeyEvent.KEYCODE_WAKEUP: case KeyEvent.KEYCODE_TV_POWER:
                return true;
            default:
                return false;
        }
    }

    /** A key the person may add as a button (not one needed to move around, not a game controller's). */
    static boolean recordable(KeyEvent e) {
        int source = e.getSource();
        if ((source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) return false;
        if (needed(e.getKeyCode())) return false;
        return e.getKeyCode() != KeyEvent.KEYCODE_UNKNOWN || e.getScanCode() != 0;
    }

    /** The release of a button just added is eaten too (the press was). */
    private static int swallowUp = -1;

    private static boolean is(String maker) {
        return maker.equalsIgnoreCase(Build.MANUFACTURER) || maker.equalsIgnoreCase(Build.BRAND);
    }

    /** Opens the app for this key on its first press. True if the key is ours and in use (it is then consumed). */
    static boolean onKey(Context c, KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_UP && e.getKeyCode() == swallowUp) {
            swallowUp = -1;
            return true;
        }
        boolean learning = Prefs.learning();
        if (!supported() && !learning && !Prefs.hasLearned()) return false;
        // BUTTON_3 is also a game controller's button: keys from a gamepad or a joystick are left alone.
        int source = e.getSource();
        if ((source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) return false;
        // Waiting for a button to add (Settings > Remote Buttons > Added buttons): the first one that isn't needed to move around.
        if (learning && e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0 && recordable(e)) {
            Prefs.writeLearnResult(e.getKeyCode(), e.getScanCode());
            Prefs.stopLearning();
            swallowUp = e.getKeyCode();
            return true;
        }
        int added = Prefs.learnedId(e.getKeyCode(), e.getScanCode());
        if (added >= 0) return open(c, e, Prefs.keyApp(added));
        if (!supported()) return false;
        int keyCode = e.getKeyCode() == KEY_YOUTUBE_TCL ? Prefs.KEY_YOUTUBE : e.getKeyCode();
        // Sony sends BUTTON_9 for five app buttons (BUTTON_4 for Netflix, BUTTON_8 for Google Play): only the scan code
        // tells them apart.
        if (SONY && (keyCode == KeyEvent.KEYCODE_BUTTON_4 || keyCode == KeyEvent.KEYCODE_BUTTON_8
                || keyCode == KeyEvent.KEYCODE_BUTTON_9)) {
            keyCode = Prefs.SONY_KEY + e.getScanCode();
        }
        // Skyworth's Prime Video and Disney+ buttons are F8 and F6: a keyboard's F keys stay a keyboard's.
        if (SKYWORTH && (keyCode == Prefs.KEY_SKY_PRIME || keyCode == Prefs.KEY_SKY_DISNEY)) {
            InputDevice device = e.getDevice();
            if (device == null || device.getVendorId() != SKYWORTH_REMOTE) return false;
        }
        boolean ours = false;
        for (int key : Prefs.KEYS) ours |= key == keyCode;
        if (!ours) return false;
        return open(c, e, Prefs.keyApp(keyCode));
    }

    /** Opens what the button is set to, on its first press. False when it is set to nothing: the key goes on as if we weren't here. */
    private static boolean open(Context c, KeyEvent e, String pkg) {
        if (pkg.isEmpty()) return false;
        if (pkg.equals(Prefs.QUICK_SETTINGS)) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                AppLog.w("remote key " + e.getKeyCode() + " -> Quick Settings");
                QuickCard.toggle(c);
            }
            return true;
        }
        if (pkg.equals(Prefs.TV_SETTINGS)) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                AppLog.w("remote key " + e.getKeyCode() + " -> TV Settings");
                c.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            return true;
        }
        if (pkg.equals(Prefs.FMT_SETTINGS)) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                AppLog.w("remote key " + e.getKeyCode() + " -> Sky Home Launcher Settings");
                Intent i = new Intent();
                i.setClassName(c, "com.skyhome.launcher.SettingsActivity");
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    c.startActivity(i);
                } catch (RuntimeException ex) {
                    AppLog.w("remote key " + e.getKeyCode() + ": " + ex.getMessage());
                }
            }
            return true;
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            Intent intent = launchIntent(c.getPackageManager(), pkg);
            AppLog.w("remote key " + e.getKeyCode() + " -> " + pkg + (intent == null ? " (not installed)" : ""));
            if (intent != null) c.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        return true;
    }

    /** The app's TV launcher, or its plain one (kept here, not in HomeApps, so the key process stays small). */
    private static Intent launchIntent(PackageManager pm, String pkg) {
        Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
        return intent != null ? intent : pm.getLaunchIntentForPackage(pkg);
    }
}
