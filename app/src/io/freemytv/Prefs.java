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
    /** Not an app: a remote button set to this opens Free My TV's own Settings. */
    static final String FMT_SETTINGS = "io.freemytv:settings";
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
        String v = sp().getString("theme", Config.THEME);
        return v.equals("light") || v.equals("dark") ? v : "auto";
    }

    static void setTheme(String v) { put("theme", v); }

    /** The visual theme is independent of the light/dark color scheme. */
    static String look() {
        String v = sp().getString("look", Config.LOOK);
        return Themes.has(v) ? v : Themes.GLASS;
    }

    static void setLook(String v) { put("look", Themes.has(v) ? v : Themes.GLASS); }

    // MARK: - City

    /** The place is found from the TV's internet address (Locator) rather than chosen; the config's first value is "yes" when it names no place. */
    static boolean autoLocation() { return sp().getBoolean("autoLocation", Config.AUTO_LOCATION); }
    static void setAutoLocation(boolean on) { put("autoLocation", on); }

    /** False while the place is to be found from the TV's internet address and hasn't been yet: nothing is known about where the TV is. */
    static boolean locationKnown() {
        return !autoLocation() || Locator.cached(app) != null;
    }

    static String city() {
        String[] here = autoLocation() ? Locator.cached(app) : null;
        return here != null ? here[0] : autoLocation() ? "" : sp().getString("city", Config.CITY).trim();
    }

    static String latitude() {
        String[] here = autoLocation() ? Locator.cached(app) : null;
        return here != null ? here[1] : sp().getString("latitude", Config.LATITUDE);
    }

    static String longitude() {
        String[] here = autoLocation() ? Locator.cached(app) : null;
        return here != null ? here[2] : sp().getString("longitude", Config.LONGITUDE);
    }

    static void setCity(String name, String latitude, String longitude) {
        sp().edit().putString("city", name).putString("latitude", latitude).putString("longitude", longitude)
                .putBoolean("autoLocation", false).apply();
        version++;
    }

    // MARK: - Hidden services (Free My TV > TMDB)

    /** Streaming services (the keys of Recs.serviceNames: "netflix", "prime"...) never recommended or opened; the config's hide_services first. */
    static List<String> hiddenServices() {
        String v = app == null ? Config.HIDE_SERVICES : sp().getString("hiddenServices", Config.HIDE_SERVICES);
        List<String> out = new ArrayList<>();
        for (String p : v.split(",")) {
            p = p.trim().toLowerCase(Locale.ROOT);
            if (!p.isEmpty() && !out.contains(p)) out.add(p);
        }
        return out;
    }

    static void setHiddenServices(List<String> keys) { put("hiddenServices", String.join(",", keys)); }

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

    /**
     * The picks carousel (the cards under the tabs): on unless the TV is short of memory (shortOfMemory) and the person
     * hasn't chosen. Off, none are fetched or shown; the tabs go and the shelf and catalogs stay.
     */
    static boolean picks() {
        if (app == null) return false;
        SharedPreferences s = sp();
        return s.contains("picks") ? s.getBoolean("picks", true) : !shortOfMemory();
    }
    static void setPicks(boolean on) { put("picks", on); }

    /** The picks are off only because the TV is short of memory: the person hasn't chosen. */
    static boolean picksOffForMemory() { return app != null && !sp().contains("picks") && shortOfMemory(); }

    /** Leave cartoons and anime out of the picks and the poster rows. */
    static boolean hideAnimation() { return app == null ? Config.HIDE_ANIMATION : sp().getBoolean("hideAnimation", Config.HIDE_ANIMATION); }
    static void setHideAnimation(boolean on) { put("hideAnimation", on); }

    /**
     * The poster rows that are on, in order (Catalogs' keys); the config's catalogs= until the person edits them. A TV short
     * of memory starts with none when the config still has the rows it ships with (a list you wrote there is used as written).
     */
    static List<String> catalogRows() {
        String v = app == null ? null : sp().getString("catalogRows", null);
        if (v == null) v = app != null && !Config.CATALOGS_CUSTOM && shortOfMemory() ? "none" : Config.CATALOGS;
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

    // MARK: - Integrations (Free My TV > Integrations); config.properties gives the first values, never read back once set

    static String tmdbKey() { return (app == null ? Config.TMDB_KEY : sp().getString("tmdbKey", Config.TMDB_KEY)).trim(); }
    static void setTmdbKey(String v) { put("tmdbKey", v.trim()); }

    static String watchmodeKey() { return (app == null ? Config.WATCHMODE_KEY : sp().getString("watchmodeKey", Config.WATCHMODE_KEY)).trim(); }
    static void setWatchmodeKey(String v) { put("watchmodeKey", v.trim()); }

    static String traktClientId() { return (app == null ? Config.TRAKT_CLIENT_ID : sp().getString("traktClientId", Config.TRAKT_CLIENT_ID)).trim(); }
    static void setTraktClientId(String v) { put("traktClientId", v.trim()); }

    /** Original languages (two-letter codes) the picks and catalogs are limited to; empty: any. */
    static List<String> languages() {
        String v = app == null ? Config.TMDB_LANGUAGES : sp().getString("languages", Config.TMDB_LANGUAGES);
        List<String> out = new ArrayList<>();
        for (String p : v.split(",")) {
            p = p.trim().toLowerCase(Locale.ROOT);
            if (!p.isEmpty() && !out.contains(p)) out.add(p);
        }
        return out;
    }

    static void setLanguages(List<String> codes) { put("languages", String.join(",", codes)); }

    /**
     * The Torrentio addon for Stremio (Settings > Stremio): its language names and the biggest size. Until the person
     * chooses, the languages are the TMDB section's and the size is 5 GB; an empty list is "all languages".
     */
    static List<String> torrentioLanguages() {
        if (app == null || !sp().contains("torrentioLanguages")) return Torrentio.fromTmdb(languages());
        List<String> out = new ArrayList<>();
        for (String p : sp().getString("torrentioLanguages", "").split(",")) if (!p.isEmpty() && !out.contains(p)) out.add(p);
        return out;
    }

    static void setTorrentioLanguages(List<String> names) { put("torrentioLanguages", String.join(",", names)); }

    static String torrentioSize() { return app == null ? Torrentio.DEFAULT_SIZE : sp().getString("torrentioSize", Torrentio.DEFAULT_SIZE); }

    static void setTorrentioSize(String size) { put("torrentioSize", size); }

    /** Continue Watching also lists what the TV's other apps published (Android's Watch Next); the config gives the first value. */
    static boolean watchNext() { return app == null ? Config.WATCH_NEXT : sp().getBoolean("watchNext", Config.WATCH_NEXT); }
    static void setWatchNext(boolean on) { put("watchNext", on); }

    /**
     * Trailers play inside the home screen (an embedded YouTube player); off, the YouTube app opens them. On unless the TV is
     * short of memory (shortOfMemory) and the person hasn't chosen.
     */
    static boolean trailers() { return sp().contains("trailers") ? sp().getBoolean("trailers", true) : !shortOfMemory(); }
    static void setTrailers(boolean on) { put("trailers", on); }

    private static Boolean shortMemory;

    /**
     * A TV short of memory: a 720p UI, a low-RAM flag, or under 1.5 GB in all. The features that hold the most (the picks'
     * pictures, the trailer player, the poster rows) start off there, and the person can turn each on. Asked once.
     */
    static boolean shortOfMemory() {
        Boolean known = shortMemory;
        if (known == null) {
            boolean low = Backdrop.W <= 1280;
            android.app.ActivityManager am = (android.app.ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                android.app.ActivityManager.MemoryInfo info = new android.app.ActivityManager.MemoryInfo();
                am.getMemoryInfo(info);
                low |= am.isLowRamDevice() || info.totalMem < 1536L * 1024 * 1024;
            }
            shortMemory = known = low;
        }
        return known;
    }

    /** The TV has been asked once for the permission to read it (the home screen doesn't ask again). */
    static boolean watchNextAsked() { return sp().getBoolean("watchNextAsked", false); }
    static void setWatchNextAsked(boolean asked) { sp().edit().putBoolean("watchNextAsked", asked).apply(); }

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
    /**
     * Buttons the person added by pressing them (Settings > Remote Buttons > Added buttons): each {key code, scan code, name,
     * package}, saved as "learn=" lines in the same file. Their ids, where a button is an int, start at LEARNED.
     */
    static final int LEARNED = 200_000;
    private static final List<String[]> learned = new ArrayList<>();
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

    /** Reads the file again when it changed. Call with keyApps locked. */
    private static void loadKeys() {
        File f = keysFile();
        long stamp = f.lastModified() * 31 + f.length();   // 0 without the file: every key opens its default
        if (stamp == keysStamp) return;
        keysStamp = stamp;
        java.util.Arrays.fill(keyApps, null);
        learned.clear();
        if (stamp == 0) return;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("learn=")) {
                    String[] p = line.substring(6).split("\\|", 4);
                    if (p.length == 4) learned.add(p);
                    continue;
                }
                for (int i = 0; i < KEYS.length; i++) {
                    String prefix = KEYS[i] + "=";
                    if (line.startsWith(prefix)) keyApps[i] = line.substring(prefix.length());
                }
            }
        } catch (IOException ignored) {
        }
    }

    /** Package the key opens ("" = nothing). `keyCode` is a built-in key or the id of an added button. */
    static String keyApp(int keyCode) {
        synchronized (keyApps) {
            loadKeys();
            if (keyCode >= LEARNED) return keyCode - LEARNED < learned.size() ? learned.get(keyCode - LEARNED)[3] : "";
            for (int i = 0; i < KEYS.length; i++) if (KEYS[i] == keyCode && keyApps[i] != null) return keyApps[i];
            return defaultApp(keyCode);
        }
    }

    /** The id of the added button for this key and scan code, or -1. */
    static int learnedId(int keyCode, int scanCode) {
        synchronized (keyApps) {
            loadKeys();
            for (int i = 0; i < learned.size(); i++) {
                String[] l = learned.get(i);
                if (l[0].equals(String.valueOf(keyCode)) && l[1].equals(String.valueOf(scanCode))) return LEARNED + i;
            }
            return -1;
        }
    }

    static boolean hasLearned() {
        synchronized (keyApps) {
            loadKeys();
            return !learned.isEmpty();
        }
    }

    static String learnedName(int id) {
        synchronized (keyApps) {
            loadKeys();
            return id - LEARNED < learned.size() ? learned.get(id - LEARNED)[2] : "";
        }
    }

    /** The built-in keys, then the ids of the added buttons. */
    static int[] allKeys() {
        synchronized (keyApps) {
            loadKeys();
            int[] all = java.util.Arrays.copyOf(KEYS, KEYS.length + learned.size());
            for (int i = 0; i < learned.size(); i++) all[KEYS.length + i] = LEARNED + i;
            return all;
        }
    }

    /** Writes every choice and every added button (`id`'s app replaced by `pkg`). Call with keyApps locked. */
    private static void writeAll(int id, String pkg) {
        StringBuilder sb = new StringBuilder();
        for (int key : KEYS) sb.append(key).append('=').append(key == id ? pkg : keyApp(key)).append('\n');
        for (int i = 0; i < learned.size(); i++) {
            String[] l = learned.get(i);
            sb.append("learn=").append(l[0]).append('|').append(l[1]).append('|').append(l[2]).append('|')
                    .append(LEARNED + i == id ? pkg : l[3]).append('\n');
        }
        if (writeKeys(sb.toString())) version++;
    }

    static void setKeyApp(int keyCode, String pkg) {
        synchronized (keyApps) {
            loadKeys();
            writeAll(keyCode, pkg);
        }
    }

    /** Adds a button (a key and scan code the person pressed); returns its id, the existing one if it was added already. */
    static int addLearned(int keyCode, int scanCode, String name) {
        synchronized (keyApps) {
            int known = learnedId(keyCode, scanCode);
            if (known >= 0) return known;
            learned.add(new String[] {String.valueOf(keyCode), String.valueOf(scanCode), name.replace('|', ' ').replace('\n', ' '), ""});
            writeAll(-1, "");
            return LEARNED + learned.size() - 1;
        }
    }

    /** Gives a button the person picked a new name (the default is "Button {n}"). */
    static void renameLearned(int id, String name) {
        synchronized (keyApps) {
            loadKeys();
            if (id < LEARNED || id - LEARNED >= learned.size()) return;
            String[] l = learned.get(id - LEARNED);
            l[2] = name.replace('|', ' ').replace('\n', ' ');
            writeAll(-1, "");
        }
    }

    static void removeLearned(int id) {
        synchronized (keyApps) {
            loadKeys();
            if (id < LEARNED || id - LEARNED >= learned.size()) return;
            learned.remove(id - LEARNED);
            writeAll(-1, "");
        }
    }

    // Learning a button: the settings screen asks (a file), and the first key that arrives is written to another one, by the
    // key service (its own process: it sees keys no app gets to see) or by the settings screen itself.
    private static final long LEARN_MS = 30_000;

    private static File learnFile(String name) {
        return new File(app.getFilesDir(), name);
    }

    static void startLearning() {
        //noinspection ResultOfMethodCallIgnored
        learnFile("remote-learn.res").delete();
        try (FileOutputStream out = new FileOutputStream(learnFile("remote-learn.req"))) {
            out.write('1');
        } catch (IOException ignored) {
        }
    }

    /** True while a button is being waited for (a few seconds at most). Cheap: the service asks on every key. */
    static boolean learning() {
        File f = learnFile("remote-learn.req");
        if (!f.exists()) return false;
        if (System.currentTimeMillis() - f.lastModified() < LEARN_MS) return true;
        //noinspection ResultOfMethodCallIgnored
        f.delete();
        return false;
    }

    static void stopLearning() {
        //noinspection ResultOfMethodCallIgnored
        learnFile("remote-learn.req").delete();
    }

    static void writeLearnResult(int keyCode, int scanCode) {
        try (FileOutputStream out = new FileOutputStream(learnFile("remote-learn.res"))) {
            out.write((keyCode + "|" + scanCode).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }

    /** {key code, scan code} of the button that was pressed, once; null while none was. */
    static int[] takeLearnResult() {
        File f = learnFile("remote-learn.res");
        if (!f.exists()) return null;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String[] p = r.readLine().split("\\|");
            return new int[] {Integer.parseInt(p[0]), Integer.parseInt(p[1])};
        } catch (IOException | RuntimeException e) {
            return null;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

}
