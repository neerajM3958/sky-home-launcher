package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Settings changed on the TV itself (Free My TV > Settings). config.properties only gives the first
 * values; anything the user changes here wins. Read from the :home process (home screen, screensaver, settings).
 */
final class Prefs {
    private Prefs() {}

    /** Remote keys we handle, with the app each opens until the user picks another. Codes read from the TCL's log. */
    static final int KEY_NETFLIX = 4062, KEY_YOUTUBE = 190, KEY_PRIME = 4099, KEY_TCL_CHANNEL = 4020;
    /** The Netflix button on Vestel TVs and Formovie projectors: STEM_1, which the TV hands to its Netflix key receiver (dumpsys window policy). */
    static final int KEY_STEM_NETFLIX = KeyEvent.KEYCODE_STEM_1;
    /** Skyworth remotes (Vendor_0957_Product_000f.kl): Netflix is STEM_1 (above), YouTube STEM_2, Prime Video F8, Disney+ F6, and the gear button NOTIFICATION. */
    static final int KEY_SKY_YOUTUBE = KeyEvent.KEYCODE_STEM_2, KEY_SKY_PRIME = KeyEvent.KEYCODE_F8, KEY_SKY_DISNEY = KeyEvent.KEYCODE_F6,
            KEY_SKY_SETTINGS = KeyEvent.KEYCODE_NOTIFICATION;
    /** Not an app: a remote button set to this opens the TV's own Settings. */
    static final String TV_SETTINGS = "android:settings";
    /** KTC's (NextStar) Tabii button: BUTTON_8, which the TV hands to its hot-key receiver (its log: "keycode = 195"). */
    static final int KEY_KTC_TABII = KeyEvent.KEYCODE_BUTTON_8;
    /** Sony's app buttons, as SONY_KEY + scan code (read from a BRAVIA's log; the key codes are shared). */
    static final int SONY_KEY = 100_000;
    static final int KEY_SONY_CORE = SONY_KEY + 496, KEY_SONY_NETFLIX = SONY_KEY + 583, KEY_SONY_DISNEY = SONY_KEY + 491,
            KEY_SONY_PRIME = SONY_KEY + 490, KEY_SONY_YOUTUBE = SONY_KEY + 485, KEY_SONY_CRUNCHYROLL = SONY_KEY + 507,
            KEY_SONY_PLAY = SONY_KEY + 587;   // the Google Play button of older remotes: BUTTON_8 (BRAVIA 4K UR3, Android 10)
    static final int[] KEYS = RemoteKeys.SKYWORTH
            ? new int[] {KEY_STEM_NETFLIX, KEY_SKY_YOUTUBE, KEY_SKY_PRIME, KEY_SKY_DISNEY, KEY_SKY_SETTINGS}
            : RemoteKeys.SONY
            ? new int[] {KEY_SONY_CORE, KEY_SONY_NETFLIX, KEY_SONY_DISNEY, KEY_SONY_PRIME, KEY_SONY_YOUTUBE, KEY_SONY_CRUNCHYROLL,
                    KEY_SONY_PLAY}
            : RemoteKeys.STEM_NETFLIX ? new int[] {KEY_STEM_NETFLIX}
            : RemoteKeys.KTC ? new int[] {KEY_KTC_TABII}
            : new int[] {KEY_NETFLIX, KEY_YOUTUBE, KEY_PRIME, KEY_TCL_CHANNEL};

    /** Bumped by every change: screens compare it to know when to redraw. */
    static volatile int version;

    private static Context app;

    static void init(Context c) {
        app = c.getApplicationContext();
        migrateKeys();   // before anything reads the button choices
        Ui.applyLanguage();   // the language picked on the TV, now that it can be read
    }

    /** The app's context in this process (null before App.onCreate). */
    static Context context() {
        return app;
    }

    private static SharedPreferences sp() {
        return app.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    private static void put(String key, String value) {
        sp().edit().putString(key, value).apply();
        version++;
    }

    private static void put(String key, boolean value) {
        sp().edit().putBoolean(key, value).apply();
        version++;
    }

    // MARK: - Language and units

    /** "auto" or a language code as picked ("pt", "fr", "zh-CN"…); Ui.applyLanguage finds its catalogue. */
    static String language() {
        String v = app == null ? Config.LANGUAGE : sp().getString("language", Config.LANGUAGE);
        v = v.trim();
        return v.isEmpty() || v.equalsIgnoreCase("auto") ? "auto" : v;
    }

    static void setLanguage(String v) { put("language", v); Ui.applyLanguage(); }

    /** "auto", "C" or "F". */
    static String temperature() {
        String v = sp().getString("temperature", Config.TEMPERATURE).trim().toUpperCase(Locale.ROOT);
        return v.startsWith("F") ? "F" : v.startsWith("C") ? "C" : "auto";
    }

    static void setTemperature(String v) { put("temperature", v); }

    /** "auto" (dark from sunset to sunrise), "light" or "dark". */
    static String theme() {
        String v = sp().getString("theme", "auto");
        return v.equals("light") || v.equals("dark") ? v : "auto";
    }

    static void setTheme(String v) { put("theme", v); }

    // MARK: - City

    static String city() { return sp().getString("city", Config.CITY).trim(); }
    static String latitude() { return sp().getString("latitude", Config.LATITUDE); }
    static String longitude() { return sp().getString("longitude", Config.LONGITUDE); }

    static void setCity(String name, String latitude, String longitude) {
        sp().edit().putString("city", name).putString("latitude", latitude).putString("longitude", longitude).apply();
        version++;
    }

    /** The home screen's soft click when moving and opening. */
    static boolean sounds() { return sp().getBoolean("sounds", true); }
    static void setSounds(boolean on) { put("sounds", on); }

    // MARK: - Widgets and coins

    static boolean weather() { return sp().getBoolean("weather", true); }
    static void setWeather(boolean on) { put("weather", on); }

    static boolean quotes() { return sp().getBoolean("quotes", Config.SHOW_CRYPTO); }
    static void setQuotes(boolean on) { put("quotes", on); }

    static boolean coin(int i) { return sp().getBoolean("coin" + i, true); }
    static void setCoin(int i, boolean on) { put("coin" + i, on); }

    // MARK: - TMDB (Free My TV > TMDB); config.properties gives the first values

    /** "auto" (the TV's own country) or a country code ("IN", "BR"...). */
    static String region() {
        String v = (app == null ? Config.TMDB_REGION : sp().getString("region", Config.TMDB_REGION)).trim();
        return v.isEmpty() || v.equalsIgnoreCase("auto") ? "auto" : v.toUpperCase(Locale.ROOT);
    }

    static void setRegion(String v) { put("region", v); }

    /** Leave cartoons and anime out of the picks and the poster rows. */
    static boolean hideAnimation() { return app == null ? Config.HIDE_ANIMATION : sp().getBoolean("hideAnimation", Config.HIDE_ANIMATION); }
    static void setHideAnimation(boolean on) { put("hideAnimation", on); }

    /** The poster rows that are on, in order (Catalogs' keys); the config's catalogs= until the person edits them. */
    static List<String> catalogRows() {
        String v = app == null ? null : sp().getString("catalogRows", null);
        if (v == null) v = Config.CATALOGS;
        List<String> out = new ArrayList<>();
        for (String p : v.split(",")) {
            p = p.trim().toLowerCase(Locale.ROOT);
            if (!p.isEmpty() && !p.equals("none") && !out.contains(p)) out.add(p);
        }
        return out;
    }

    static void setCatalogRows(List<String> keys) {
        StringBuilder sb = new StringBuilder();
        for (String p : keys) sb.append(sb.length() == 0 ? "" : ",").append(p);
        put("catalogRows", sb.length() == 0 ? "none" : sb.toString());
    }

    /** Picks only from titles on the apps the TV has, rather than anything popular. */
    static boolean recommendFromApps() { return app != null && sp().getBoolean("recommendApps", false); }
    static void setRecommendFromApps(boolean on) { put("recommendApps", on); }

    /** Only titles on a streaming service in the region (subscription, free or with ads); the config's flatrate_only first. */
    static boolean flatrateOnly() { return app == null ? Config.FLATRATE_ONLY : sp().getBoolean("flatrateOnly", Config.FLATRATE_ONLY); }
    static void setFlatrateOnly(boolean on) { put("flatrateOnly", on); }

    /** With flatrateOnly: also the titles that can only be rented or bought. */
    static boolean includeRentBuy() { return app == null ? Config.INCLUDE_RENT_BUY : sp().getBoolean("includeRentBuy", Config.INCLUDE_RENT_BUY); }
    static void setIncludeRentBuy(boolean on) { put("includeRentBuy", on); }

    // MARK: - Stremio (Free My TV > Stremio)

    /** Missing apps and titles on no service we know open in Stremio, when it is installed. */
    static boolean stremioForMissing() { return sp().getBoolean("stremioMissing", false); }
    static void setStremioForMissing(boolean on) { put("stremioMissing", on); }

    /** This service (a Routing service key, or Routing.OTHER) opens in Stremio rather than in its own app. */
    static boolean toStremio(String key) {
        return ("," + sp().getString("stremioServices", "") + ",").contains("," + key + ",");
    }

    static void setToStremio(String key, boolean on) {
        List<String> keys = new ArrayList<>();
        for (String k : sp().getString("stremioServices", "").split(",")) if (!k.isEmpty() && !k.equals(key)) keys.add(k);
        if (on) keys.add(key);
        put("stremioServices", String.join(",", keys));
    }

    // MARK: - Episodes screen

    /** How the episodes screen sorts (an EpisodeList sort), the same for every show. */
    static int episodeSort() {
        int v = sp().getInt("episodeSort", EpisodeList.NUMBER_DESC);
        return v >= EpisodeList.NUMBER_DESC && v <= EpisodeList.DATE_ASC ? v : EpisodeList.NUMBER_DESC;
    }

    static void setEpisodeSort(int v) { sp().edit().putInt("episodeSort", v).apply(); }

    // MARK: - Shelf

    /** Packages on the home shelf, in order; null until the user edits it (then the built-in list applies). */
    static List<String> shelf() {
        String v = sp().getString("shelf", null);
        if (v == null) return null;
        List<String> out = new ArrayList<>();
        for (String p : v.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    static void setShelf(List<String> packages) {
        StringBuilder sb = new StringBuilder();
        for (String p : packages) sb.append(sb.length() == 0 ? "" : ",").append(p);
        put("shelf", sb.toString());
    }

    // MARK: - Top buttons

    /**
     * The round buttons at the top right, in order: "live", "inputs" and/or "settings". null until the user edits
     * them (then HomeApps.TOP_DEFAULT applies); empty when they took all three off.
     */
    static List<String> topButtons() {
        String v = sp().getString("topButtons", null);
        if (v == null) return null;
        List<String> out = new ArrayList<>();
        for (String p : v.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    static void setTopButtons(List<String> keys) {
        StringBuilder sb = new StringBuilder();
        for (String p : keys) sb.append(sb.length() == 0 ? "" : ",").append(p);
        put("topButtons", sb.toString());
    }

    // MARK: - Remote buttons

    private static String defaultApp(int keyCode) {
        switch (keyCode) {
            case KEY_NETFLIX: case KEY_STEM_NETFLIX: return "com.netflix.ninja";
            case KEY_YOUTUBE: case KEY_SKY_YOUTUBE: return "com.google.android.youtube.tv";
            case KEY_PRIME: case KEY_SKY_PRIME: return "com.amazon.amazonvideo.livingroom";
            case KEY_SKY_DISNEY: return "com.disney.disneyplus";
            case KEY_SKY_SETTINGS: return TV_SETTINGS;
            case KEY_TCL_CHANNEL: return "";
            default: return "";
        }
    }

    // The key service runs in its own small process (:keys), so that a busy home screen can never delay the TV's keys.
    // SharedPreferences are not shared between processes, so these few values live in a tiny file both processes read.
    // The service asks on every key: the values stay in memory and the file is read again only when it changes.
    private static File keysFile() {
        return new File(app.getFilesDir(), "remote-keys.txt");
    }

    private static final String[] keyApps = new String[KEYS.length];   // null = the default
    private static long keysStamp = Long.MIN_VALUE;

    /** A temporary file per process (two can start together after an update), synced before it replaces the old one. */
    private static boolean writeKeys(String text) {
        File tmp = new File(app.getFilesDir(), "remote-keys." + android.os.Process.myPid() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }
        return tmp.renameTo(keysFile());
    }

    /** Choices saved before they moved to the file (in SharedPreferences) are copied there once. */
    private static void migrateKeys() {
        if (keysFile().exists()) return;
        SharedPreferences old = sp();
        boolean any = false;
        for (int key : KEYS) any |= old.contains("key" + key);
        if (!any) return;
        StringBuilder sb = new StringBuilder();
        for (int key : KEYS) sb.append(key).append('=').append(old.getString("key" + key, defaultApp(key))).append('\n');
        writeKeys(sb.toString());
    }

    /** Package the key opens ("" = nothing). */
    static String keyApp(int keyCode) {
        synchronized (keyApps) {
            File f = keysFile();
            long stamp = f.lastModified() * 31 + f.length();   // 0 without the file: every key opens its default
            if (stamp != keysStamp) {
                keysStamp = stamp;
                java.util.Arrays.fill(keyApps, null);
                if (stamp != 0) {
                    try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            for (int i = 0; i < KEYS.length; i++) {
                                String prefix = KEYS[i] + "=";
                                if (line.startsWith(prefix)) keyApps[i] = line.substring(prefix.length());
                            }
                        }
                    } catch (IOException ignored) {
                    }
                }
            }
            for (int i = 0; i < KEYS.length; i++) if (KEYS[i] == keyCode && keyApps[i] != null) return keyApps[i];
            return defaultApp(keyCode);
        }
    }

    static void setKeyApp(int keyCode, String pkg) {
        StringBuilder sb = new StringBuilder();
        for (int key : KEYS) sb.append(key).append('=').append(key == keyCode ? pkg : keyApp(key)).append('\n');
        if (writeKeys(sb.toString())) version++;
    }
}
