package com.skyhome.launcher;

import com.skyhome.launcher.api.Http;
import com.skyhome.launcher.api.TmdbApi;
import com.skyhome.launcher.api.WatchmodeApi;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home screen picks ("This morning / afternoon / tonight"): what's trending this week plus recent releases, only from
 * the streaming apps installed on the TV (Netflix, Prime Video, Disney+, Max, Apple TV, Okko, Hulu, Paramount+, Peacock,
 * Globoplay...), via TMDB (an open movie database that knows what streams where in each country). "Watch" opens the
 * service's app.
 *
 * The list is fetched once a day. The next day's is prepared in the background (details and images on disk) and shown
 * only when the picks aren't in view ({@link #publish}): the titles that stay keep their places, and the new ones take
 * the places of those that left, with their images already there.
 *
 * Each person uses their own free TMDB key (config.properties "tmdb_key", built into the app). It never goes to the log.
 */
final class Recs {
    static final class Pick {
        int id;
        String kind;            // "movie" or "tv"
        String title, backdrop, poster, meta = "", full = "", app, pkg;      // meta: the short line (cards); full: year, length, rating, genres, service (Spotlight)
        boolean detailed;
        String servicePkg;      // routed picks: the app it opens in, for the card's icon (null not known yet, "" none; UI thread)
        boolean serviceAsked;
    }

    interface Listener { void onRecs(); }

    /** The backdrops: on a 720p UI, TMDB's 1280-wide copy shows the same and is lighter to download and decode. */
    private static final String IMAGES = "https://image.tmdb.org/t/p/" + (Backdrop.W <= 1280 ? "w1280" : "original");
    /** TMDB's Animation genre (movies and shows: cartoons and anime), left out with hide_animation=true. */
    private static final int ANIMATION = 16;
    private static final long LIST_EVERY = 24 * 3600_000L;
    /** Bump when what is saved for each title changes (the saved list is discarded and fetched again). */
    private static final int CACHE_VERSION = 16;
    /** Spotlight's poster row pool (HomeView.updateCards) — bigger than pick_count's default, so the poster cache below
     * holds the whole circular pool: with a smaller one, the row's own working set (every pick the circular scroll
     * revisits) would keep evicting itself, visible as the posters blinking. The full-screen photos are only kept for
     * the focus ±1 (they're read back from disk quickly). */
    static final int SPOTLIGHT_POOL = 20;

    private static int poolCap() {
        return Prefs.spotlightOn() ? SPOTLIGHT_POOL : Config.PICK_COUNT;
    }
    private static final int MANHA = 0, TARDE = 1, NOITE = 2;
    /** Service name on TMDB (part of the name, lowercase) → TV app (HomeApps key, package). */
    private static final String[][] SERVICES = {
        {"netflix", "netflix", "com.netflix.ninja"},
        {"prime video", "prime", "com.amazon.amazonvideo.livingroom"},
        {"disney", "disney", "com.disney.disneyplus"},
        {"hulu", "hulu", "com.hulu.livingroomplus"},
        {"paramount", "paramount", "com.cbs.ott"},
        {"paramount", "paramount", "com.cbs.ca"},      // the build on Sony TVs in the UK (and in Canada)
        {"peacock", "peacock", "com.peacocktv.peacockandroid"},
        {"globoplay", "globoplay", "com.globo.globotv"},
        {"max", "max", "com.wbd.stream"},
        {"max", "max", "com.hbo.hbonow"},
        {"apple tv", "appletv", "com.apple.atve.androidtv.appletv"},
        {"apple tv", "appletv", "com.apple.atve.sony.appletv"},   // Sony's own build
        {"hotstar", "hotstar", "in.startv.hotstar"},   // TMDB: "JioHotstar" (earlier "Disney Plus Hotstar")
        {"okko", "okko", "ru.more.play"},
        {"crunchyroll", "crunchyroll", "com.crunchyroll.crunchyroid"},
        {"oneplay", "oneplay", "net.cme.voyo.cz.tvapp"},    // Czechia: Voyo and O2 TV became Oneplay, same app
        {"now tv", "now", "com.bskyb.nowtv.beta"},     // UK: Sky's NOW (TMDB: "Now TV", "Now TV Cinema")
        // Russia (TMDB's watch providers for RU)
        {"kinopoisk", "kinopoisk", "ru.kinopoisk.tv"},
        {"premier", "premier", "gpm.tnt_premier"},
        {"amediateka", "amediateka", "ru.amediateka"},
        {"more tv", "wink", "ru.rt.video.app.tv"},   // TMDB: "More TV" (more.tv is part of Rostelecom's Wink now)
    };
    private static final Map<String, String> SERVICE_NAME = new HashMap<>();
    static {
        SERVICE_NAME.put("netflix", "Netflix");
        SERVICE_NAME.put("prime", "Prime Video");
        SERVICE_NAME.put("disney", "Disney+");
        SERVICE_NAME.put("hulu", "Hulu");
        SERVICE_NAME.put("paramount", "Paramount+");
        SERVICE_NAME.put("peacock", "Peacock");
        SERVICE_NAME.put("globoplay", "Globoplay");
        SERVICE_NAME.put("max", "Max");
        SERVICE_NAME.put("appletv", "Apple TV");
        SERVICE_NAME.put("hotstar", "JioHotstar");
        SERVICE_NAME.put("okko", "Okko");
        SERVICE_NAME.put("crunchyroll", "Crunchyroll");
        SERVICE_NAME.put("oneplay", "Oneplay");
        SERVICE_NAME.put("now", "NOW");
        SERVICE_NAME.put("kinopoisk", "Кинопоиск");
        SERVICE_NAME.put("premier", "Premier");
        SERVICE_NAME.put("amediateka", "Амедиатека");
        SERVICE_NAME.put("wink", "Wink");
    }
    /**
     * Country for "what streams where": the Region setting (Sky Home Launcher > TMDB), whose first value is config.properties'
     * tmdb_region (US, GB, BR...); "auto" is the TV's own country.
     */
    static String region() {
        String v = Prefs.region();
        if (!v.equals("auto")) return v;
        String c = Locale.getDefault().getCountry();
        return c.isEmpty() ? "US" : c.toUpperCase(Locale.ROOT);
    }
    /** The errors the settings say in words: TMDB lists none of the TV's apps in the region, or the TV has none we know. */
    static final String NO_APPS = "none of the TV's streaming services are on TMDB";
    static final String NO_KNOWN_APPS = "none of the TV's streaming apps can give picks";

    private static Recs instance;

    static synchronized Recs get(Context c) {
        if (instance == null) instance = new Recs(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final SharedPreferences cache;
    private final ExecutorService io = Executors.newSingleThreadExecutor();     // the list and the details, one at a time
    /** Images missing from disk: downloaded and built, two at a time (never in the way of a read from disk). */
    private final ExecutorService fetch = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "picks-fetch");
        t.setDaemon(true);
        return t;
    });
    /** Images read from disk, the last asked first (the focus moved: what it is on now comes before what it passed). */
    private final ExecutorService decode = new java.util.concurrent.ThreadPoolExecutor(1, 1, 0, java.util.concurrent.TimeUnit.MILLISECONDS,
            new java.util.concurrent.LinkedBlockingDeque<Runnable>() {
                @Override public boolean offer(Runnable r) { return offerFirst(r); }
            }, r -> {
                Thread t = new Thread(r, "picks-decode");
                t.setDaemon(true);
                return t;
            });
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    /** Built and ready to draw, the least recently drawn first; limited by bytes ({@link #budget}). */
    private final Map<String, Backdrop> images = new LinkedHashMap<>(16, 0.75f, true);
    private final java.util.Set<String> pending = new java.util.HashSet<>();
    /** The style the images in memory were built for (Spotlight has the full photo, Cards the cards). */
    private volatile boolean spotlight = Prefs.spotlightOn();
    // Spotlight's poster row: TMDB's poster art (the title baked in), not the textless backdrop — a small, separate
    // cache (w342, like Catalogs' poster rows) since it's a different image from the same title's backdrop.
    private static final String POSTER_IMAGES = "https://image.tmdb.org/t/p/w342";
    private final Map<String, Bitmap> posters = new LinkedHashMap<>(16, 0.75f, true);
    private final java.util.Set<String> posterPending = new java.util.HashSet<>();
    /** One build of an image at a time (the refresh and the screen may both want it). */
    private final Map<String, Object> building = new java.util.concurrent.ConcurrentHashMap<>();
    /** The images on disk (`cacheDir/picks`): about 600 KB a title; past this the least recently used go. */
    private static final long DISK_BUDGET = 40L << 20;

    private volatile List<Pick> picks = new ArrayList<>();
    private volatile List<Pick> next;   // the next list, ready (details and images on disk) and waiting for publish()
    private volatile long listAt;
    private volatile boolean fetching;
    private volatile String error = "";
    private volatile long failedAt, keyStamp, linkRetryAt;
    private volatile String listLanguage = "";   // language used for the saved titles, genres and texts

    private Recs(Context c) {
        context = c;
        cache = c.getSharedPreferences("recs", Context.MODE_PRIVATE);
        if (cache.getInt("version", 0) != CACHE_VERSION) cache.edit().clear().putInt("version", CACHE_VERSION).apply();
        try {
            String saved = cache.getString("picks", null);
            if (saved != null) picks = parse(new JSONArray(saved));
            String waiting = cache.getString("next", null);
            if (waiting != null) next = parse(new JSONArray(waiting));
            listAt = cache.getLong("listAt", 0);
            listLanguage = cache.getString("language", cache.getBoolean("english", false) ? "en" : "pt");
            keyStamp = cache.getLong("keyStamp", 0);
        } catch (Exception ignored) {
        }
        io.execute(this::removeOldCache);
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    static boolean hasKey(Context c) {
        return !Prefs.tmdbKey().isEmpty();
    }

    /** Last fetch, for the settings screen: {count, error, at}. Written by the home screen (another process). */
    static JSONObject status(Context c) {
        File f = new File(c.getFilesDir(), "recs-status.json");
        if (!f.exists() || !hasKey(c)) return new JSONObject();
        try (FileInputStream in = new FileInputStream(f)) {
            return new JSONObject(Http.read(in));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void writeStatus() {
        try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), "recs-status.json"))) {
            out.write(new JSONObject().put("count", picks.size()).put("error", error).put("at", System.currentTimeMillis())
                    .toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    String error() { return error; }

    /** There is nothing to show and nothing coming: no TMDB key, or the last fetch failed or found no titles. */
    boolean noPicks() {
        return !hasKey(context) || !Prefs.picksOn() || (picks.isEmpty() && !error.isEmpty());
    }

    static String periodLabel() {
        int p = period(System.currentTimeMillis());
        return p == MANHA ? Ui.tr("THIS MORNING") : p == TARDE ? Ui.tr("THIS AFTERNOON") : Ui.tr("TONIGHT");
    }

    private static int period(long now) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        return hour >= 5 && hour < 12 ? MANHA : hour >= 12 && hour < 18 ? TARDE : NOITE;
    }

    /** Today's picks (the first is the featured one; the arrows switch to the others). */
    List<Pick> now() {
        return now(null);
    }

    /** Today's picks (pick_count, 3 by default), movies only ("movie") or shows only ("tv"); null = both (the home screen tabs). */
    List<Pick> now(String kind) {
        return now(kind, Config.PICK_COUNT);
    }

    /** Like {@link #now(String)}, but with its own count instead of `pick_count` — Spotlight's poster row wants more
     * titles on screen than Cards' 3-card rotation, without changing Cards' own pace or `pick_count` (and its memory
     * budgets, which key off it: the poster cache's size). Pulled from the same already-fetched
     * `picks` pool TMDB's list call already returned (no extra request), just capped differently. */
    List<Pick> now(String kind, int count) {
        List<Pick> list = new ArrayList<>();
        if (!hasKey(context) || !Prefs.picksOn()) return list;    // no TMDB key, or the picks are turned off: no recommendations
        Map<String, Boolean> have = new HashMap<>();   // one look per app, not per title (this runs every 15 s)
        for (Pick p : picks) {
            if ((kind == null || kind.equals(p.kind))
                    && (!p.detailed || (p.pkg != null && have.computeIfAbsent(p.pkg, this::installed)))) list.add(p);   // app missing: skip
        }
        if (list.isEmpty()) return list;
        // The same all day, in the list's order (it changes with the daily list), never two from the same franchise
        // ("Law & Order" and "Law & Order: Special…").
        List<Pick> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < list.size() && out.size() < count; i++) {
            Pick p = list.get(i);
            if (seen.add(franchise(p.title))) out.add(p);
        }
        return out;
    }

    /** "Law & Order: Special Victims Unit" → "law & order" (what comes before ":" or " - "). */
    private static String franchise(String title) {
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT);
        int cut = t.indexOf(':');
        if (cut < 0) cut = t.indexOf(" - ");
        return (cut > 0 ? t.substring(0, cut) : t).trim();
    }

    /** Name of the service the pick is on ("Netflix"), or empty. */
    static String serviceName(Pick p) {
        if (p == null || p.app == null) return "";
        String name = SERVICE_NAME.get(p.app);
        return name != null ? name : p.app;
    }

    /**
     * The pick's ready backdrop, or null: reads it from disk (or downloads and builds it) in the background and notifies
     * when it's ready. A read is skipped when the pick is no longer near the focus by the time its turn comes.
     */
    Backdrop backdrop(Pick p) {
        if (p == null || p.backdrop == null || !p.detailed) return null;   // the details choose the image: wait for them
        boolean spot = Prefs.spotlightOn();
        if (spot != spotlight) {        // the style changed: the images in memory have the other style's parts
            spotlight = spot;
            synchronized (images) { images.clear(); }
            if (!spot) synchronized (posters) { posters.clear(); }    // only Spotlight's row draws them
        }
        final String key = p.backdrop;    // the details may swap the image midway
        synchronized (images) {
            Backdrop b = images.get(key);
            if (b != null) return b;
            if (!pending.add(key)) return null;
        }
        decode.execute(() -> {
            boolean handed = false;
            try {
                if (!near(key) || spot != spotlight) return;
                Backdrop b = Backdrop.load(base(key), spot);
                if (b != null) {
                    touch(key);
                    keep(key, b, spot);
                    return;
                }
                handed = true;
                fetch.execute(() -> {
                    try {
                        keep(key, build(key, spot, true), spot);
                    } catch (IOException | RuntimeException | OutOfMemoryError e) {
                        AppLog.w("recommendation image: " + e.getMessage());
                    } finally {
                        synchronized (images) { pending.remove(key); }
                    }
                });
            } finally {
                if (!handed) synchronized (images) { pending.remove(key); }
            }
        });
        return null;
    }

    /** Puts a ready image in memory (dropping the least recently drawn past the budget) and tells the screen. */
    private void keep(String key, Backdrop b, boolean spot) {
        if (b == null || spot != spotlight) return;
        synchronized (images) {
            images.put(key, b);
            // Not recycled when dropped: HomeView's bgFrom/bgTo can hold any of these for as long as that pick stays
            // the background (plans/plan-memory-management.md, item 5: recycling here crashed the renderer).
            long total = 0;
            for (Backdrop o : images.values()) total += o.bytes();
            java.util.Iterator<Backdrop> it = images.values().iterator();
            while (total > budget(spot) && images.size() > 1 && it.hasNext()) {
                Backdrop o = it.next();
                if (o == b) continue;
                total -= o.bytes();
                it.remove();
            }
        }
        notifyListeners();
    }

    /** Memory for ready images: Spotlight 3 (the focus ±1, ~5.5 MB each at 1080p with the 1280×720 photo), Cards the three tabs' 9 cards (~4.7 MB each). */
    private static long budget(boolean spot) {
        return (spot ? 18L : 48L) << 20;
    }

    /** The picks whose image is worth having in memory now: the focus ±1 in Spotlight, otherwise the cards that may show next. */
    private List<Pick> nearby() {
        if (!Prefs.spotlightOn()) return wanted();
        List<Pick> out = new ArrayList<>();
        List<Pick> list = now(viewKind, poolCap());
        for (int i = -1; i <= 1 && !list.isEmpty(); i++) out.add(list.get(Math.floorMod(viewStart + i, list.size())));
        return out;
    }

    private boolean near(String backdrop) {
        for (Pick p : nearby()) if (backdrop.equals(p.backdrop)) return true;
        return false;
    }

    /** The pick's poster (TMDB's poster art, the title baked in), decoded at about `width` px — or null: loads in the
     * background and notifies listeners when ready. Unlike {@link #backdrop}, needs no details fetch: `poster_path`
     * comes straight off the list response. */
    Bitmap poster(Pick p, int width) {
        if (p == null || p.poster == null) return null;
        final String key = p.poster;
        synchronized (posters) {
            Bitmap b = posters.get(key);
            if (b != null) return b;
            if (!posterPending.add(key)) return null;
        }
        decode.execute(() -> {
            boolean handed = false;
            try {
                if (posterFile(key).exists()) {
                    loadPoster(key, width);
                    return;
                }
                handed = true;
                fetch.execute(() -> {
                    try {
                        downloadPoster(key);
                        loadPoster(key, width);
                    } catch (IOException e) {
                        AppLog.w("poster: " + e.getMessage());
                    } finally {
                        synchronized (posters) { posterPending.remove(key); }
                    }
                });
            } finally {
                if (!handed) synchronized (posters) { posterPending.remove(key); }
            }
        });
        return null;
    }

    private boolean hasImage(Pick p) {
        return p.backdrop != null && Backdrop.photoFile(base(p.backdrop)).exists();
    }

    /** The picks' images on disk: Android may empty it (low storage), and then they're fetched again. */
    private File dir() {
        return new File(context.getCacheDir(), "picks");
    }

    /** "/abc.jpg" → "abc": what the files of a TMDB image start with (Backdrop adds ".jpg", ".frost.png"…). */
    private static String stem(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.indexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private File base(String path) {
        return new File(dir(), stem(path));
    }

    /**
     * With a key: fetches the list once a day (or when a setting it depends on changed); then the details and images of
     * what's on screen. A daily list is prepared whole and waits for {@link #publish}; a first list, or one for changed
     * settings, shows at once.
     */
    void refreshIfStale() {
        if (!hasKey(context) || !Prefs.picksOn() || fetching) return;
        long now = System.currentTimeMillis();
        long stamp = sourceStamp();   // a new key, country, animation choice or installed streaming-app set refetches
        boolean newKey = stamp != keyStamp;
        long sinceFail = now - failedAt;    // negative = the clock went back: don't wait
        if (!newKey && failedAt != 0 && sinceFail >= 0 && sinceFail < 10 * 60_000L) return;
        boolean changed = newKey || picks.isEmpty() || !listLanguage.equals(Ui.language);
        boolean stale = changed || now - listAt > LIST_EVERY || now < listAt;
        boolean missing = false;
        for (Pick pick : wanted()) missing |= !pick.detailed || !hasImage(pick);
        if (!stale && !missing) return;
        keyStamp = stamp;
        fetching = true;
        io.execute(() -> {
            try {
                if (stale) {
                    AppLog.w("picks: fetching the list (" + (newKey ? "settings changed" : picks.isEmpty() ? "none yet"
                            : changed ? "language changed" : "daily") + ")");
                    List<Pick> list = fetchList();
                    if (changed) {
                        picks = list;
                        next = null;
                    } else {
                        next = prepare(list);
                    }
                    save();
                }
                // One failing title doesn't stop the others; whatever has no app on the TV is not downloaded.
                int failures = 0, tried = 0;
                String last = "";
                for (Pick pick : wanted()) {
                    tried++;
                    try {
                        if (!pick.detailed) fetchDetails(pick);
                        if (pick.pkg != null && pick.backdrop != null) build(pick.backdrop, Prefs.spotlightOn(), false);
                    } catch (Exception e) {
                        failures++;
                        last = e.getMessage() == null ? e.toString() : e.getMessage();
                        AppLog.w("details “" + pick.title + "”: " + last);
                    }
                    Launcher.get(context).prefetch(pick.kind, pick.id);   // so OK opens it at once
                }
                save();
                if (tried > 0 && failures == tried) throw new IOException(last);
                error = "";
                failedAt = 0;
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
                failedAt = System.currentTimeMillis();
                AppLog.w("recommendations: " + error);
                if (newKey) {       // the settings changed and nothing fits them: the old titles don't either
                    picks = new ArrayList<>();
                    save();
                }
            } finally {
                fetching = false;
                writeStatus();
                notifyListeners();
            }
        });
    }

    /**
     * Screensaver: the picks with an app on the TV and an image already on disk, starting with this hour's (the
     * featured one first), never two from the same franchise.
     */
    List<Pick> showcase() {
        List<Pick> out = new ArrayList<>();
        if (!hasKey(context)) return out;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Pick p : wanted()) if (hasImage(p) && seen.add(franchise(p.title))) out.add(p);
        Map<String, Boolean> have = new HashMap<>();
        for (Pick p : picks) {
            if (p.detailed && p.pkg != null && have.computeIfAbsent(p.pkg, this::installed) && hasImage(p)
                    && seen.add(franchise(p.title))) out.add(p);
        }
        return out;
    }

    /** The pick's photo if it's already on disk (the screensaver loads it in its own way), or null. */
    File readyImage(Pick p) {
        return hasImage(p) ? Backdrop.photoFile(base(p.backdrop)) : null;
    }

    /**
     * While the screensaver is showing: details and image for every pick in the list (not just this hour's), one at a
     * time in the background, so it has more photos. Not used otherwise.
     */
    void prefetchAll() {
        if (!hasKey(context) || fetching) return;
        long sinceFail = System.currentTimeMillis() - failedAt;
        if (failedAt != 0 && sinceFail >= 0 && sinceFail < 10 * 60_000L) return;
        List<Pick> todo = new ArrayList<>();
        for (Pick p : picks) if (!p.detailed || (p.pkg != null && !hasImage(p))) todo.add(p);
        if (todo.isEmpty()) return;
        fetching = true;
        io.execute(() -> {
            int failures = 0;
            String last = "";
            try {
                // One failing title (gone from TMDB, an image that won't download) doesn't stop the others.
                for (Pick p : todo) {
                    try {
                        if (!p.detailed) fetchDetails(p);
                        if (p.pkg != null && p.backdrop != null) build(p.backdrop, Prefs.spotlightOn(), false);
                    } catch (Exception e) {
                        failures++;
                        last = e.getMessage() == null ? e.toString() : e.getMessage();
                    }
                }
                save();
                if (failures == todo.size()) {
                    error = last;
                    failedAt = System.currentTimeMillis();
                    AppLog.w("recommendations (screensaver): " + last);
                }
            } finally {
                fetching = false;
                notifyListeners();
            }
        });
    }

    /**
     * The next list made ready to show (io thread): details for the new titles, every image on disk. Titles whose
     * details or image failed, or (from the TV's apps) with no app on the TV, are left out, so nothing in it shows
     * without its picture or disappears later. Throws when nothing is left.
     */
    private List<Pick> prepare(List<Pick> list) throws IOException {
        boolean spot = Prefs.spotlightOn();
        List<Pick> out = new ArrayList<>();
        int fresh = 0;
        String last = "";
        for (Pick p : list) {
            try {
                if (!p.detailed) {
                    fetchDetails(p);
                    fresh++;
                }
                if (p.pkg == null || p.backdrop == null) continue;
                build(p.backdrop, spot, false);
                if (spot && p.poster != null) downloadPoster(p.poster);
                Launcher.get(context).prefetch(p.kind, p.id);   // a title that stays: where it streams, a day later
                out.add(p);
            } catch (Exception e) {
                last = e.getMessage() == null ? e.toString() : e.getMessage();
                AppLog.w("next picks: “" + p.title + "”: " + last);
            }
        }
        if (out.isEmpty()) throw new IOException(last.isEmpty() ? "TMDB returned no recommendations" : last);
        AppLog.w("picks: next list ready, " + out.size() + " titles, " + fresh + " new");
        return out;
    }

    /**
     * Shows the list prepared by the daily refresh, if one is waiting. HomeView calls it only while the picks aren't in
     * view (the home screen is away, or the focus is elsewhere), so no title changes under the focus. The titles that
     * stay keep their places (and their objects); the new ones take the places of those that left, in the new list's
     * order, and any more go at the end.
     */
    void publish() {
        List<Pick> fresh = next;
        if (fresh == null) return;
        next = null;
        Map<String, Pick> incoming = new LinkedHashMap<>();
        for (Pick p : fresh) incoming.put(key(p), p);
        java.util.Set<String> staying = new java.util.HashSet<>();
        for (Pick o : picks) if (incoming.containsKey(key(o))) staying.add(key(o));
        List<Pick> added = new ArrayList<>();
        for (Pick p : fresh) if (!staying.contains(key(p))) added.add(p);
        java.util.Iterator<Pick> in = added.iterator();
        List<Pick> out = new ArrayList<>();
        for (Pick o : picks) {
            if (staying.contains(key(o))) out.add(o);
            else if (in.hasNext()) out.add(in.next());
        }
        while (in.hasNext()) out.add(in.next());
        picks = out;
        AppLog.w("picks: new list shown, " + added.size() + " new titles");
        io.execute(this::save);
        notifyListeners();
    }

    /** Cards on screen at once: the window that gets details is this page and the next one. */
    private static final int PAGE = 3;
    private volatile String viewKind;   // the tab on screen (null = Explore)
    private volatile int viewStart;     // its first card

    /** The home screen says which tab and card it is on; with pick_count > 3 only this page and the next get details. */
    void setView(String kind, int start) {
        viewKind = kind;
        viewStart = start;
    }

    /**
     * What needs details and images: with pick_count > 3, the cards on screen and the next page of the current tab (the
     * cards slide one by one, so the next page is ready before it shows); otherwise this hour's picks for the three
     * tabs (Explore, Movies, Shows), without repeats.
     */
    List<Pick> wanted() {
        List<Pick> out = new ArrayList<>();
        if (Config.PICK_COUNT > PAGE || Prefs.spotlightOn()) {
            // Spotlight's row is its own, bigger list (SPOTLIGHT_POOL): the window must come from that same list, or
            // the picks past pick_count never get details or a photo.
            List<Pick> list = now(viewKind, poolCap());
            if (list.isEmpty()) return out;     // no TMDB key, or the picks are turned off
            // Spotlight's row is circular and shows a big photo per focus: two behind the focus load too, so Left is ready.
            int back = Prefs.spotlightOn() ? 2 : 0;
            for (int i = -back; i < Math.min(2 * PAGE, list.size()); i++)
                out.add(list.get(Math.floorMod(viewStart + i, list.size())));
            return out;
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String kind : new String[] {null, "movie", "tv"}) {
            for (Pick p : now(kind)) if (seen.add(key(p))) out.add(p);
        }
        return out;
    }

    /** Movies and shows are numbered separately on TMDB: a pick is identified by type + number. */
    static String key(Pick p) {
        return p.kind + ":" + p.id;
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onRecs(); });
    }

    // MARK: - TMDB

    /** The new list (not shown yet), with the details already known for the titles in it. */
    private List<Pick> fetchList() throws Exception {
        if (listOnly()) return fetchListOnly();
        String ids = installedProviderIds();
        String common = "&language=" + lang() + "&watch_region=" + region() + monetization() + languageFilter() + "&include_adult=false"
                + "&sort_by=popularity.desc" + "&with_watch_providers=" + URLEncoder.encode(ids.toString(), "UTF-8")
                + (Prefs.hideAnimation() ? "&without_genres=" + ANIMATION : "");
        // 1. The most popular right now (TMDB's popularity follows what people are watching and searching for), already
        //    filtered to the TV's services: one request each. The details (which service, runtime) come later, only for
        //    the titles that are shown.
        List<Pick> hotMovies = new ArrayList<>(), hotShows = new ArrayList<>();
        JSONArray hotM = TmdbApi.get(Prefs.tmdbKey(), "/discover/movie?vote_count.gte=100" + common).getJSONArray("results");
        JSONArray hotS = TmdbApi.get(Prefs.tmdbKey(), "/discover/tv?vote_count.gte=50" + common).getJSONArray("results");
        for (int i = 0; i < hotM.length(); i++) add(hotMovies, hotM.getJSONObject(i), "movie");
        for (int i = 0; i < hotS.length(); i++) add(hotShows, hotS.getJSONObject(i), "tv");
        // 2. Recent releases on the TV's services: movies from the past year and shows with a new episode in the
        //    past 3 months.
        JSONArray movies = TmdbApi.get(Prefs.tmdbKey(), "/discover/movie?vote_count.gte=30&primary_release_date.gte=" + day(-365)
                + "&primary_release_date.lte=" + day(0) + common).getJSONArray("results");
        JSONArray shows = TmdbApi.get(Prefs.tmdbKey(), "/discover/tv?vote_count.gte=20&air_date.gte=" + day(-90) + "&air_date.lte=" + day(0)
                + common).getJSONArray("results");
        List<Pick> newMovies = new ArrayList<>(), newShows = new ArrayList<>();
        for (int i = 0; i < movies.length(); i++) add(newMovies, movies.getJSONObject(i), "movie");
        for (int i = 0; i < shows.length(); i++) add(newShows, shows.getJSONObject(i), "tv");
        // Popular first (12), then the new releases; movies and shows alternating, without repeats, up to 24.
        List<Pick> list = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        alternate(list, seen, hotMovies, hotShows, 12);
        alternate(list, seen, newMovies, newShows, 24);
        if (list.isEmpty()) throw new IOException("TMDB returned no recommendations");
        // Titles still in the list keep their details (same language and same apps), so a refresh asks for new ones only.
        if (listLanguage.equals(Ui.language) && cache.getLong("keyStamp", 0) == keyStamp) {
            Map<String, Pick> old = known();
            for (Pick q : list) {
                Pick o = old.get(key(q));
                if (o == null) continue;
                q.title = o.title;
                q.backdrop = o.backdrop;
                q.meta = o.meta;
                q.full = o.full;
                q.app = o.app;
                q.pkg = o.pkg;
                q.detailed = true;
            }
        }
        listAt = System.currentTimeMillis();
        listLanguage = Ui.language;
        return list;
    }

    /** The titles with details, from the list on screen and the one waiting, by {@link #key}. */
    private Map<String, Pick> known() {
        Map<String, Pick> out = new HashMap<>();
        List<Pick> waiting = next;
        if (waiting != null) for (Pick o : waiting) if (o.detailed) out.put(key(o), o);
        for (Pick o : picks) if (o.detailed) out.put(key(o), o);
        return out;
    }

    /** The discover filter on the titles' original language: "&with_original_language=en%7Chi" (either), or nothing. */
    static String languageFilter() {
        List<String> codes = Prefs.languages();
        return codes.isEmpty() ? "" : "&with_original_language=" + String.join("%7C", codes);
    }

    /** The discover filter on how a title streams: subscription (and free), plus rent or buy when asked, or no filter. */
    static String monetization() {
        if (!Prefs.flatrateOnly()) return "";
        return "&with_watch_monetization_types=" + (Prefs.includeRentBuy() ? "flatrate%7Crent%7Cbuy" : "flatrate");
    }

    /**
     * The services the TV has, by their TMDB ids in the region ("8|119"), for a discover query; throws NO_KNOWN_APPS
     * when none of the services we know is on the TV (or hide_services hides them all), NO_APPS when TMDB lists none of
     * them in the region.
     */
    String installedProviderIds() throws Exception {
        boolean any = false;
        for (String[] s : SERVICES) any |= installed(s[2]);
        if (!any) throw new IOException(NO_KNOWN_APPS);
        Map<Integer, String[]> services = new HashMap<>();
        JSONArray providers = TmdbApi.get(Prefs.tmdbKey(), "/watch/providers/movie?language=" + lang() + "&watch_region=" + region()).getJSONArray("results");
        for (int i = 0; i < providers.length(); i++) {
            JSONObject o = providers.getJSONObject(i);
            String[] s = service(o.optString("provider_name"), o.optInt("provider_id"));
            if (s != null && installed(s[2])) services.put(o.getInt("provider_id"), s);
        }
        if (services.isEmpty()) throw new IOException(NO_APPS);
        StringBuilder ids = new StringBuilder();
        for (int id : services.keySet()) ids.append(ids.length() == 0 ? "" : "|").append(id);
        return ids.toString();
    }

    /** Language of titles and genres (TMDB's "fr-FR", "zh-CN"…); the services come from the region. */
    static String lang() {
        String lang = Ui.language;
        if (lang.equals("en")) return "en-US";
        if (lang.equals("pt")) return "pt-BR";
        if (lang.equals("es")) return "ES".equals(java.util.Locale.getDefault().getCountry()) ? "es-ES" : "es-MX";
        if (lang.equals("de")) return "de-DE";   // TMDB's de-AT and de-CH have few translations of their own
        if (lang.indexOf('-') > 0) return lang;
        return lang + "-" + Ui.locale().getCountry();
    }

    private static void alternate(List<Pick> out, java.util.Set<String> seen, List<Pick> a, List<Pick> b, int max) {
        for (int i = 0; i < Math.max(a.size(), b.size()) && out.size() < max; i++) {
            if (i < a.size() && seen.add(key(a.get(i)))) out.add(a.get(i));
            if (i < b.size() && out.size() < max && seen.add(key(b.get(i)))) out.add(b.get(i));
        }
    }

    /** The date `offset` days from today, as "2026-10-19" (negative = in the past). */
    static String day(int offset) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, offset);
        return String.format(Locale.US, "%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
    }

    private static Pick add(List<Pick> list, JSONObject o, String kind) {
        String backdrop = o.optString("backdrop_path", "");
        if (backdrop.isEmpty() || "null".equals(backdrop)) return null;
        if (Prefs.hideAnimation() && animated(o)) return null;
        Pick p = new Pick();
        p.id = o.optInt("id");
        p.kind = kind;
        p.title = o.optString(kind.equals("movie") ? "title" : "name");
        p.backdrop = backdrop;
        String poster = o.optString("poster_path", "");
        p.poster = poster.isEmpty() || "null".equals(poster) ? null : poster;
        list.add(p);
        return p;
    }

    // MARK: - Picks from anything popular (no filter on the apps; a press asks Launcher where the title opens)

    /**
     * A pick that opens through Launcher rather than through an app of its own, so Pick.pkg holds this instead of a
     * package. Nothing about the service is needed to build one: it comes from the list answer alone (title, backdrop,
     * genre, rating).
     */
    static final String ROUTED = "routed";

    /** Picks come from the apps on the TV (the older way) instead of from anything popular. */
    private static boolean listOnly() {
        return !Prefs.recommendFromApps();
    }

    static final String STREMIO = Routing.STREMIO;

    /** Stremio's page for a title, from its IMDb id; `kind` is TMDB's "movie" or "tv". */
    static String stremioLink(String kind, String imdb) {
        return "stremio:///detail/" + (kind.equals("movie") ? "movie" : "series") + "/" + imdb;
    }

    /** Stremio's page for one episode of a show (checked on a TV: it opens the episode's streams). */
    static String stremioEpisode(String imdb, int season, int number) {
        return "stremio:///detail/series/" + imdb + "/" + imdb + ":" + season + ":" + number;
    }

    private static final long GENRES_EVERY = 7 * 24 * 3600_000L;
    private final Map<String, String> genreNames = new HashMap<>();   // "movie28" → "Action"
    private final ExecutorService net = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "tmdb");
        t.setDaemon(true);
        return t;
    });

    /** Several requests at once (four at a time); the answers come back in the order of the paths. */
    JSONObject[] getAll(String... paths) throws Exception {
        List<java.util.concurrent.Future<JSONObject>> pending = new ArrayList<>();
        for (String path : paths) pending.add(net.submit(() -> TmdbApi.get(Prefs.tmdbKey(), path)));
        JSONObject[] out = new JSONObject[paths.length];
        for (int i = 0; i < out.length; i++) {
            try {
                out[i] = pending.get(i).get();
            } catch (java.util.concurrent.ExecutionException e) {
                throw e.getCause() instanceof Exception ? (Exception) e.getCause() : e;
            }
        }
        return out;
    }

    /** The genre names from the last week's answer (same language), or false: they need fetching. */
    private boolean loadSavedGenres() {
        long at = cache.getLong("genresAt", 0), now = System.currentTimeMillis();
        String movie = cache.getString("genres:movie", null), tv = cache.getString("genres:tv", null);
        if (movie == null || tv == null || !lang().equals(cache.getString("genresLang", "")) || now < at || now - at > GENRES_EVERY) return false;
        try {
            readGenres("movie", new JSONObject(movie));
            readGenres("tv", new JSONObject(tv));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void readGenres(String kind, JSONObject answer) {
        JSONArray list = answer.optJSONArray("genres");
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject g = list.optJSONObject(i);
            if (g != null) genreNames.put(kind + g.optInt("id"), g.optString("name"));
        }
    }

    private List<Pick> fetchListOnly() throws Exception {
        String common = "&language=" + lang() + "&watch_region=" + region() + monetization() + languageFilter()
                + "&include_adult=false&sort_by=popularity.desc" + (Prefs.hideAnimation() ? "&without_genres=" + ANIMATION : "");
        boolean genres = !loadSavedGenres();
        List<String> paths = new ArrayList<>();
        paths.add("/discover/movie?vote_count.gte=100" + common);
        paths.add("/discover/tv?vote_count.gte=50" + common);
        paths.add("/discover/movie?vote_count.gte=30&primary_release_date.gte=" + day(-365) + "&primary_release_date.lte=" + day(0) + common);
        paths.add("/discover/tv?vote_count.gte=20&air_date.gte=" + day(-90) + "&air_date.lte=" + day(0) + common);
        if (genres) {
            paths.add("/genre/movie/list?language=" + lang());
            paths.add("/genre/tv/list?language=" + lang());
        }
        JSONObject[] r = getAll(paths.toArray(new String[0]));
        if (genres) {
            readGenres("movie", r[4]);
            readGenres("tv", r[5]);
            cache.edit().putString("genres:movie", r[4].toString()).putString("genres:tv", r[5].toString())
                    .putString("genresLang", lang()).putLong("genresAt", System.currentTimeMillis()).apply();
        }
        List<Pick> hotMovies = listed(r[0], "movie"), hotShows = listed(r[1], "tv");
        List<Pick> newMovies = listed(r[2], "movie"), newShows = listed(r[3], "tv");
        List<Pick> list = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        alternate(list, seen, hotMovies, hotShows, 12);
        alternate(list, seen, newMovies, newShows, 24);
        if (list.isEmpty()) throw new IOException("TMDB returned no recommendations");
        Map<String, Pick> old = known();
        for (Pick q : list) {       // a title still in the list keeps the backdrop it already chose
            Pick o = old.get(key(q));
            if (o != null) {
                q.backdrop = o.backdrop;
                q.detailed = true;
            }
        }
        listAt = System.currentTimeMillis();
        listLanguage = Ui.language;
        return list;
    }

    private List<Pick> listed(JSONObject answer, String kind) {
        List<Pick> out = new ArrayList<>();
        JSONArray results = answer.optJSONArray("results");
        for (int i = 0; results != null && i < results.length(); i++) {
            JSONObject o = results.optJSONObject(i);
            Pick p = o == null ? null : add(out, o, kind);
            if (p == null) continue;
            p.pkg = ROUTED;
            // not detailed yet: the best backdrop is chosen when the card is about to show (fetchBackdrop)
            List<String> parts = new ArrayList<>();
            if (kind.equals("tv")) parts.add(Ui.tr("Series"));
            JSONArray ids = o.optJSONArray("genre_ids");
            String genre = ids != null && ids.length() > 0 ? genreNames.get(kind + ids.optInt(0)) : null;
            if (genre != null) parts.add(genre(genre));
            double rating = o.optDouble("vote_average", 0);
            if (rating > 0 && o.optInt("vote_count") >= 10) parts.add(String.format(Ui.locale(), "★ %.1f", rating));
            p.meta = String.join(" · ", parts);
            // The same answer carries the year and more genres: Spotlight has room for them.
            List<String> full = new ArrayList<>();
            String date = o.optString(kind.equals("movie") ? "release_date" : "first_air_date");
            if (date.length() >= 4) full.add(date.substring(0, 4));
            if (rating > 0 && o.optInt("vote_count") >= 10) full.add(String.format(Ui.locale(), "★ %.1f", rating));
            for (int g = 0; ids != null && g < ids.length() && g < 3; g++) {
                String name = genreNames.get(kind + ids.optInt(g));
                if (name != null) full.add(genre(name));
            }
            p.full = String.join(" · ", full);
        }
        return out;
    }

    // MARK: - What Launcher needs (which app a service opens; the rest of this class stays about the picks)

    /** The SERVICES row (name part, key, package) for a provider's name on TMDB, or null when the TV has no app we know for it. */
    String[] serviceRow(String providerName, int providerId) {
        return service(providerName, providerId);
    }

    boolean appInstalled(String pkg) {
        return installed(pkg);
    }

    /** {key, name} of each streaming service we know, in the order the Stremio screen lists them. */
    static List<String[]> serviceNames() {
        List<String[]> out = new ArrayList<>();
        for (String[] s : SERVICES) {
            boolean seen = false;
            for (String[] o : out) seen |= o[0].equals(s[1]);
            if (!seen) out.add(new String[] {s[1], SERVICE_NAME.get(s[1])});
        }
        return out;
    }

    /** The app of this service (a Routing service key) is on the TV, whether or not the service is hidden. */
    boolean serviceAppInstalled(String key) {
        for (String[] s : SERVICES) {
            if (s[1].equals(key) && HomeApps.launchIntent(context.getPackageManager(), s[2]) != null) return true;
        }
        return false;
    }

    /** Stremio is on the TV (without it every "Stremio" choice is off). */
    boolean stremioInstalled() {
        return installed(STREMIO);
    }

    /** The service is one the person hides (Sky Home Launcher > TMDB > Hidden services): it never opens, even when its app is on the TV. */
    boolean hiddenService(String providerName, int providerId) {
        String[] row = service(providerName, providerId);
        return row != null && Prefs.hiddenServices().contains(row[1]);
    }

    String appName(String pkg) {
        return appLabel(pkg);
    }

    private static boolean animated(JSONObject o) {
        JSONArray genres = o.optJSONArray("genre_ids");
        for (int i = 0; genres != null && i < genres.length(); i++) if (genres.optInt(i) == ANIMATION) return true;
        return false;
    }

    /**
     * Picks from anything popular: the title's best backdrop (no text over it, closest to 1920 wide; TMDB's default
     * stays if it has none), and in the same answer where it streams and its IMDb id, which Launcher keeps (so OK opens
     * it at once, with no request of its own).
     */
    private void fetchBackdrop(Pick p) throws Exception {
        JSONObject o = TmdbApi.get(Prefs.tmdbKey(), "/" + p.kind + "/" + p.id + "?append_to_response=images,watch/providers,external_ids&include_image_language=null");
        String best = bestBackdrop(o.optJSONObject("images"));
        if (best != null) p.backdrop = best;
        p.detailed = true;
        Launcher.get(context).remember(p.kind, p.id, o);
        completeFull(p, o);
    }

    /**
     * Spotlight's line from the list (year, rating, genres) gets what only the details have, from this same answer: the
     * length (or seasons) after the year and, at the end, the service among the apps the TV has. Genres stay the list's
     * (this answer has no language).
     */
    private void completeFull(Pick p, JSONObject o) {
        List<String> parts = new ArrayList<>(Arrays.asList(p.full.isEmpty() ? new String[0] : p.full.split(" · ")));
        int at = !parts.isEmpty() && parts.get(0).matches("\\d{4}") ? 1 : 0;
        String length = null;
        if (p.kind.equals("movie")) {
            int minutes = o.optInt("runtime");
            if (minutes > 0) length = minutes >= 60 ? Ui.trf("{h}h {m}m", "h", minutes / 60, "m", minutes % 60) : Ui.trf("{m}m", "m", minutes);
        } else {
            int seasons = o.optInt("number_of_seasons");
            if (seasons > 0) length = Ui.plural("plural|one:{n} season|other:{n} seasons", seasons);
        }
        if (length != null && !parts.contains(length)) parts.add(at, length);
        JSONObject wp = o.optJSONObject("watch/providers");
        JSONObject br = wp == null || wp.optJSONObject("results") == null ? null : wp.optJSONObject("results").optJSONObject(region());
        JSONArray flat = br == null ? null : br.optJSONArray("flatrate");
        for (int i = 0; flat != null && i < flat.length(); i++) {
            JSONObject f = flat.optJSONObject(i);
            String[] row = f == null ? null : service(f.optString("provider_name"), f.optInt("provider_id"));
            if (row != null && installed(row[2])) {
                String name = SERVICE_NAME.get(row[1]);
                String shown = name != null ? name : row[1];
                if (!parts.contains(shown)) parts.add(shown);
                break;
            }
        }
        p.full = String.join(" · ", parts);
    }

    /** Genre, runtime (or seasons) and the service it's on, among the ones the TV has. */
    private void fetchDetails(Pick p) throws Exception {
        if (listOnly()) {
            fetchBackdrop(p);
            return;
        }
        JSONObject o = TmdbApi.get(Prefs.tmdbKey(), "/" + p.kind + "/" + p.id
                + "?language=" + lang() + "&append_to_response=watch/providers,images,external_ids&include_image_language=null," + Ui.language.replaceAll("-.*", "") + ",en");
        p.title = title(p, o);
        String best = bestBackdrop(o.optJSONObject("images"));
        if (best != null) p.backdrop = best;
        List<String> parts = new ArrayList<>();
        JSONArray genres = o.optJSONArray("genres");
        if (p.kind.equals("tv")) parts.add(Ui.tr("Series"));
        if (genres != null && genres.length() > 0) {
            String name = genres.getJSONObject(0).optString("name");
            parts.add(genre(name));
        }
        if (p.kind.equals("movie")) {
            int minutes = o.optInt("runtime");
            if (minutes > 0) parts.add(minutes >= 60 ? Ui.trf("{h}h {m}m", "h", minutes / 60, "m", minutes % 60) : Ui.trf("{m}m", "m", minutes));
        } else {
            int seasons = o.optInt("number_of_seasons");
            if (seasons > 0) parts.add(Ui.plural("plural|one:{n} season|other:{n} seasons", seasons));
        }
        // TMDB's own rating, from the same answer (a title with too few votes has none worth showing).
        double rating = o.optDouble("vote_average", 0);
        if (rating > 0 && o.optInt("vote_count") >= 10) parts.add(String.format(Ui.locale(), "★ %.1f", rating));
        JSONObject wp = o.optJSONObject("watch/providers");
        JSONObject br = wp == null ? null : wp.optJSONObject("results") == null ? null : wp.optJSONObject("results").optJSONObject(region());
        JSONArray flat = br == null ? null : br.optJSONArray("flatrate");
        for (int i = 0; flat != null && i < flat.length() && p.app == null; i++) {
            String[] s = service(flat.getJSONObject(i).optString("provider_name"), flat.getJSONObject(i).optInt("provider_id"));
            if (s != null && installed(s[2])) {
                p.app = s[1];
                p.pkg = s[2];
            }
        }
        if (p.app != null) parts.add(serviceName(p));
        p.meta = String.join(" · ", parts);
        // Spotlight's line from the same answer: year, length or seasons, rating, up to three genres, the service.
        List<String> full = new ArrayList<>();
        String date = o.optString(p.kind.equals("movie") ? "release_date" : "first_air_date");
        if (date.length() >= 4) full.add(date.substring(0, 4));
        if (p.kind.equals("movie")) {
            int minutes = o.optInt("runtime");
            if (minutes > 0) full.add(minutes >= 60 ? Ui.trf("{h}h {m}m", "h", minutes / 60, "m", minutes % 60) : Ui.trf("{m}m", "m", minutes));
        } else {
            int seasons = o.optInt("number_of_seasons");
            if (seasons > 0) full.add(Ui.plural("plural|one:{n} season|other:{n} seasons", seasons));
        }
        if (rating > 0 && o.optInt("vote_count") >= 10) full.add(String.format(Ui.locale(), "★ %.1f", rating));
        for (int g = 0; genres != null && g < genres.length() && g < 3; g++) full.add(genre(genres.getJSONObject(g).optString("name")));
        if (p.app != null) full.add(serviceName(p));
        p.full = String.join(" · ", full);
        p.detailed = true;
        Launcher.get(context).remember(p.kind, p.id, o);
    }

    /**
     * Without a title in the TV's language TMDB answers with the original one, so a Korean series on a German TV reads
     * "닥터X: 하얀 마피아의 시대". Then the English title, when TMDB has one (one more request, only for those).
     */
    private String title(Pick p, JSONObject o) {
        String field = p.kind.equals("movie") ? "title" : "name";
        String own = o.optString(field, p.title);
        String original = o.optString("original_language");
        String lang = Ui.language.replaceAll("-.*", "");   // TMDB's original_language has no country: "zh", not "zh-CN"
        if (lang.equals("en") || own.isEmpty() || !own.equals(o.optString("original_" + field))
                || lang.equals(original) || "en".equals(original)) return own;
        try {
            String english = TmdbApi.get(Prefs.tmdbKey(), "/" + p.kind + "/" + p.id + "?language=en-US").optString(field);
            return english.isEmpty() ? own : english;
        } catch (Exception e) {
            return own;
        }
    }

    /** TV genres that TMDB returns in English even in other languages (pt-BR, es, de…). */
    private static String genre(String name) {
        switch (name) {
            case "Action & Adventure": return Ui.tr("genre|Action & Adventure");
            case "Sci-Fi & Fantasy": return Ui.tr("genre|Sci-Fi & Fantasy");
            case "War & Politics": return Ui.tr("genre|War & Politics");
            case "Kids": return Ui.tr("genre|Kids");
            case "News": return Ui.tr("genre|News");
            case "Soap": return Ui.tr("genre|Soap");
            case "Talk": return Ui.tr("genre|Talk");
            default: return name;
        }
    }

    /**
     * The title's best backdrop: no text written over it (our own text goes on top of it), the closest to 1920 wide
     * (larger only costs download, disk and decoding) and, on a tie, the highest rated.
     */
    private static String bestBackdrop(JSONObject images) {
        JSONArray list = images == null ? null : images.optJSONArray("backdrops");
        String best = null;
        double bestScore = -1;
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject b = list.optJSONObject(i);
            if (b == null || b.optString("file_path", "").isEmpty()) continue;
            boolean textless = b.isNull("iso_639_1");
            int width = b.optInt("width");
            double score = (textless ? 1e7 : 0) + Math.min(width, 1920) * 100 - Math.max(0, width - 1920) + b.optDouble("vote_average", 0);
            if (score > bestScore) {
                bestScore = score;
                best = b.optString("file_path");
            }
        }
        return best;
    }

    /** The SERVICES row for a TMDB name: the installed one first, when a service has more than one package. */
    private String[] service(String name, int providerId) {
        String n = name.toLowerCase(Locale.ROOT);
        String[] first = null;
        // "HBO Max Amazon Channel", "Apple TV Amazon Channel"...: a subscription sold inside another app, not this one.
        if (n.contains(" channel")) return null;
        for (String[] s : SERVICES) {
            // Apple TV: only the streaming service (TMDB provider 350); provider 2 is the Apple TV Store (rent or buy).
            if (s[1].equals("appletv") && providerId != 350) continue;
            if (!(s[0].equals("max") ? n.matches(".*\\bmax\\b.*") : n.contains(s[0]))) continue;
            if (installed(s[2])) return s;
            if (first == null) first = s;
        }
        return first;
    }

    /** The app's own name on the TV ("Stremio"), or its package. */
    private String appLabel(String pkg) {
        try {
            return String.valueOf(context.getPackageManager().getApplicationLabel(context.getPackageManager().getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            return pkg;
        }
    }

    /** The service's app is on the TV, and the service isn't hidden (an app the TV ships without a subscription, say). */
    private boolean installed(String pkg) {
        if (ROUTED.equals(pkg)) return true;
        List<String> hidden = Prefs.hiddenServices();
        if (!hidden.isEmpty()) for (String[] s : SERVICES) if (s[2].equals(pkg) && hidden.contains(s[1])) return false;
        return HomeApps.launchIntent(context.getPackageManager(), pkg) != null;
    }

    private long sourceStamp() {
        long stamp = (Prefs.tmdbKey().hashCode() * 31L + region().hashCode()) * 31L + (Prefs.hideAnimation() ? 1 : 0);
        stamp = stamp * 31L + (listOnly() ? 1 : 0) + (Prefs.flatrateOnly() ? 2 : 0) + (Prefs.includeRentBuy() ? 4 : 0) + Prefs.languages().hashCode() * 31L;
        for (String[] service : SERVICES) stamp = stamp * 31L + (installed(service[2]) ? service[2].hashCode() : 0);
        return stamp;
    }

    // MARK: - Watchmode (the title's own page in its app; the free plan's web addresses are what the apps open)

    /** A key is set and Watchmode hasn't refused it (a refused key isn't tried again until it changes). */
    private boolean linksOn() {
        String key = Prefs.watchmodeKey();
        return !key.isEmpty() && cache.getInt("wm:refused", 0) != key.hashCode();
    }

    /** The service's own kind of offer, best first: subscription, free, rent, buy (Watchmode's "type"). */
    private static int sourceRank(String type) {
        switch (type) {
            case "sub": return 0;
            case "free": return 1;
            case "rent": return 2;
            case "buy": return 3;
            default: return -1;
        }
    }

    /**
     * The title's own page in a service's app (Watchmode's web address for it; `serviceKey` is a SERVICES key), kept after the
     * first look (2 requests of the free plan's 2,500 a month, made when OK is pressed). Null with no key, when Watchmode doesn't
     * know the title there, or when it can't be reached now (not again for 10 minutes). Waits on the network: not on the main thread.
     */
    String watchLink(String kind, int id, String serviceKey) {
        if (!linksOn() || serviceKey == null) return null;
        String saved = cache.getString("wm2:" + kind + id + serviceKey, null);
        if (saved != null) return saved.isEmpty() ? null : saved;
        if (System.currentTimeMillis() < linkRetryAt) return null;
        try {
            String link = "";
            JSONArray found = new JSONObject(WatchmodeApi.get(Prefs.watchmodeKey(), "/search/?search_field=tmdb_" + kind + "_id&search_value=" + id))
                    .optJSONArray("title_results");
            if (found != null && found.length() > 0) {
                JSONArray sources = new JSONArray(WatchmodeApi.get(Prefs.watchmodeKey(), "/title/" + found.getJSONObject(0).getInt("id") + "/sources/?regions=" + region()));
                String[] service = null;
                for (String[] s : SERVICES) if (s[1].equals(serviceKey)) service = s;
                int best = Integer.MAX_VALUE;
                for (int i = 0; service != null && i < sources.length(); i++) {
                    JSONObject s = sources.getJSONObject(i);
                    String name = s.optString("name").toLowerCase(Locale.ROOT).replace(" ", "");
                    String want = service[0].replace(" ", "");
                    // Not a channel sold inside another app ("AppleTV+ Amazon Channel", "Paramount+ (Via Amazon Prime)"):
                    // that link opens Amazon or Apple's app, not the one the pick is for.
                    boolean resold = name.contains("channel") || name.contains("(via");
                    boolean same = !resold && (want.equals("max") ? name.equals("max") : name.contains(want));
                    int rank = sourceRank(s.optString("type"));
                    if (same && rank >= 0 && rank < best && s.optString("web_url").startsWith("https://")) {
                        best = rank;
                        link = s.optString("web_url");
                    }
                }
            }
            cache.edit().putString("wm2:" + kind + id + serviceKey, link).apply();
            return link.isEmpty() ? null : link;
        } catch (Exception e) {
            if (e instanceof IOException && Http.code((IOException) e) == 401) {
                cache.edit().putInt("wm:refused", Prefs.watchmodeKey().hashCode()).apply();
            }
            linkRetryAt = System.currentTimeMillis() + 10 * 60_000L;   // quota or network: not again right away
            AppLog.w("watchmode: " + e.getMessage());
            return null;
        }
    }

    /** The SERVICES key of the app with this package, or null. */
    static String serviceKeyOf(String pkg) {
        for (String[] s : SERVICES) if (s[2].equals(pkg)) return s[1];
        return null;
    }

    // MARK: - Images (built once, kept on disk at the size drawn; the ones near the focus kept ready in memory)

    /**
     * Makes sure the image's files for this style are on disk (TMDB's photo is downloaded only when even the saved 16:9
     * copy is missing, and isn't kept) and returns it ready to draw, or (`keep` false) null. Background threads only.
     */
    private Backdrop build(String path, boolean spot, boolean keep) throws IOException {
        Object lock = building.computeIfAbsent(stem(path), k -> new Object());
        synchronized (lock) {
            File base = base(path);
            if (Backdrop.ready(base, spot)) {
                if (!keep) return null;
                Backdrop b = Backdrop.load(base, spot);
                if (b != null) return b;
                Backdrop.delete(base);      // unreadable, or made for another UI size: made again
            }
            //noinspection ResultOfMethodCallIgnored
            dir().mkdirs();
            long started = android.os.SystemClock.uptimeMillis();
            File photo = Backdrop.photoFile(base);
            boolean saved = photo.exists();
            File src = saved ? photo : download(IMAGES + path, new File(base.getPath() + ".src.part"));
            try {
                Backdrop b = Backdrop.make(decode(src, Backdrop.W), spot, base, keep);
                AppLog.w("picks image " + stem(path) + ": " + (saved ? "rebuilt" : "downloaded and built") + " in "
                        + (android.os.SystemClock.uptimeMillis() - started) + " ms");
                return b;
            } finally {
                //noinspection ResultOfMethodCallIgnored
                if (!saved) src.delete();
                if (!saved) prune();
            }
        }
    }

    /** Downloads to `part` (a file of its own), or throws. */
    private static File download(String url, File part) throws IOException {
        Http.to(url).timeouts(8000, 20000).label("image").download(part);
        return part;
    }

    /** Decodes at the smallest power-of-two reduction still at least `width` wide. */
    private static Bitmap decode(File f, int width) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), bounds);
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        while (bounds.outWidth / (opts.inSampleSize * 2) >= width) opts.inSampleSize *= 2;
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), opts);
        if (b == null) throw new IOException("unreadable image");
        return b;
    }

    /** The image was just used: the disk cache drops the least recently used first. */
    private static void touch(File f) {
        long now = System.currentTimeMillis();
        //noinspection ResultOfMethodCallIgnored
        if (now - f.lastModified() > 3600_000L) f.setLastModified(now);
    }

    private void touch(String backdrop) {
        touch(Backdrop.photoFile(base(backdrop)));
    }

    private File posterFile(String path) {
        return new File(dir(), stem(path) + ".poster.jpg");
    }

    private void downloadPoster(String path) throws IOException {
        File f = posterFile(path);
        if (f.exists()) return;
        //noinspection ResultOfMethodCallIgnored
        dir().mkdirs();
        File part = download(POSTER_IMAGES + path, new File(f.getPath() + "." + Thread.currentThread().getId() + ".part"));
        if (!part.renameTo(f)) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw new IOException("couldn't save the poster");
        }
        prune();
    }

    /** Decodes the saved poster at about `width` px, straight into GPU memory. */
    private void loadPoster(String path, int width) {
        synchronized (posters) {
            if (posters.containsKey(path)) return;
        }
        File f = posterFile(path);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), bounds);
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.HARDWARE;
        opts.inSampleSize = 1;
        while (bounds.outWidth / (opts.inSampleSize * 2) >= width) opts.inSampleSize *= 2;
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), opts);
        if (b == null) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            AppLog.w("poster: unreadable image");
            return;
        }
        touch(f);
        synchronized (posters) {
            posters.put(path, b);
            // Not recycled when dropped: the row may be drawing it this very frame (the UI thread holds no lock).
            java.util.Iterator<String> it = posters.keySet().iterator();
            while (posters.size() > Math.max(10, poolCap() + 3) && it.hasNext()) {
                it.next();
                it.remove();
            }
        }
        notifyListeners();
    }

    /** The home screen is away or memory is short: only the image on screen stays in memory (the rest is read from disk again). */
    void trim(boolean posters) {
        java.util.Set<String> keep = new java.util.HashSet<>();
        boolean spot = Prefs.spotlightOn();
        List<Pick> list = spot ? now(viewKind, poolCap()) : now(viewKind);
        // On screen: Spotlight's focused photo, or the three cards (the first is also the background).
        for (int i = 0; i < (spot ? 1 : 3) && !list.isEmpty(); i++) {
            String b = list.get(Math.floorMod(viewStart + i, list.size())).backdrop;
            if (b != null) keep.add(b);
        }
        synchronized (images) {
            images.keySet().retainAll(keep);
        }
        // Spotlight's posters are on screen too: they go only when memory is short (read back from disk).
        if (posters) synchronized (this.posters) { this.posters.clear(); }
    }

    /**
     * Keeps the images on disk under DISK_BUDGET: the list's own titles stay, the others go least recently used first.
     * A download cut off when the system stopped the app leaves a .part behind: gone after 10 minutes.
     */
    private synchronized void prune() {
        File[] files = dir().listFiles();
        if (files == null) return;
        long stale = System.currentTimeMillis() - 10 * 60_000L, total = 0;
        Map<String, List<File>> groups = new HashMap<>();
        Map<String, Long> used = new HashMap<>();
        for (File f : files) {
            String name = f.getName();
            if (name.endsWith(".part")) {
                //noinspection ResultOfMethodCallIgnored
                if (f.lastModified() < stale) f.delete();
                continue;
            }
            int dot = name.indexOf('.');
            String stem = dot > 0 ? name.substring(0, dot) : name;
            groups.computeIfAbsent(stem, k -> new ArrayList<>()).add(f);
            used.merge(stem, f.lastModified(), Math::max);
            total += f.length();
        }
        if (total <= DISK_BUDGET) return;
        java.util.Set<String> keep = new java.util.HashSet<>();
        List<Pick> mine = new ArrayList<>(picks);
        List<Pick> waiting = next;
        if (waiting != null) mine.addAll(waiting);
        for (Pick p : mine) {
            if (p.backdrop != null) keep.add(stem(p.backdrop));
            if (p.poster != null) keep.add(stem(p.poster));
        }
        List<String> old = new ArrayList<>(groups.keySet());
        old.removeAll(keep);
        old.sort((a, b) -> Long.compare(used.get(a), used.get(b)));
        for (int i = 0; i < old.size() && total > DISK_BUDGET; i++) {
            for (File f : groups.get(old.get(i))) {
                total -= f.length();
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    /** The folders of the earlier cache (TMDB's originals as downloaded): replaced by `picks`. */
    private void removeOldCache() {
        for (String name : new String[] {"recs", "recs-posters"}) {
            File[] files = new File(context.getCacheDir(), name).listFiles();
            if (files == null) continue;
            //noinspection ResultOfMethodCallIgnored
            for (File f : files) f.delete();
            //noinspection ResultOfMethodCallIgnored
            new File(context.getCacheDir(), name).delete();
        }
    }


    // MARK: - List cache

    private void save() {
        try {
            JSONArray a = toJson(picks);
            SharedPreferences.Editor e = cache.edit().putString("picks", a.toString()).putLong("listAt", listAt)
                    .putString("language", listLanguage)
                    .putLong("keyStamp", keyStamp).putInt("version", CACHE_VERSION);
            List<Pick> waiting = next;
            if (waiting != null) e.putString("next", toJson(waiting).toString());
            else e.remove("next");
            // Watchmode links of every title opened are kept; past 600 they start over (the refused-key mark stays).
            List<String> links = new ArrayList<>();
            for (String k : cache.getAll().keySet()) if ((k.startsWith("wm:") || k.startsWith("wm2:")) && !k.equals("wm:refused")) links.add(k);
            if (links.size() > 600) for (String k : links) e.remove(k);
            e.apply();
        } catch (Exception ignored) {
        }
    }

    private static JSONArray toJson(List<Pick> list) throws org.json.JSONException {
        JSONArray a = new JSONArray();
        for (Pick p : list) {
            a.put(new JSONObject().put("id", p.id).put("kind", p.kind).put("title", p.title).put("backdrop", p.backdrop)
                    .put("poster", p.poster == null ? "" : p.poster)
                    .put("meta", p.meta).put("full", p.full).put("app", p.app == null ? "" : p.app).put("pkg", p.pkg == null ? "" : p.pkg)
                    .put("detailed", p.detailed));
        }
        return a;
    }

    private static List<Pick> parse(JSONArray a) {
        List<Pick> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Pick p = new Pick();
            p.id = o.optInt("id");
            p.kind = o.optString("kind");
            p.title = o.optString("title");
            p.backdrop = o.optString("backdrop");
            p.poster = o.optString("poster").isEmpty() ? null : o.optString("poster");
            p.meta = o.optString("meta");
            p.full = o.optString("full");
            p.app = o.optString("app").isEmpty() ? null : o.optString("app");
            p.pkg = o.optString("pkg").isEmpty() ? null : o.optString("pkg");
            p.detailed = o.optBoolean("detailed");
            out.add(p);
        }
        return out;
    }
}
