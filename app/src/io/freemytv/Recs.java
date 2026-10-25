package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
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
 * Globoplay...), via TMDB (an open movie database that knows what streams where in each country). Three per hour.
 * "Watch" opens the service's app.
 *
 * Each person uses their own free TMDB key (config.properties "tmdb_key", built into the app). It never goes to the log.
 */
final class Recs {
    static final class Pick {
        int id;
        String kind;            // "movie" or "tv"
        String title, backdrop, meta = "", app, pkg;
        long shownAt;           // when its picture was first drawn (UI thread): the picture fades in from then
        String link;            // opens the title in its app (looked up with Watchmode), or null: the app's home
        boolean detailed;
        String servicePkg;      // routed picks: the app it opens in, for the card's icon (null not known yet, "" none; UI thread)
        boolean serviceAsked;
    }

    interface Listener { void onRecs(); }

    private static final String API = "https://api.themoviedb.org/3";
    /** The backdrops: on a 720p UI, TMDB's 1280-wide copy shows the same and is lighter to download and decode. */
    private static final String IMAGES = "https://image.tmdb.org/t/p/" + (Backdrop.W <= 1280 ? "w1280" : "original");
    /** TMDB's Animation genre (movies and shows: cartoons and anime), left out with hide_animation=true. */
    private static final int ANIMATION = 16;
    private static final long LIST_EVERY = 6 * 3600_000L;
    /** Bump when what is saved for each title changes (the saved list is discarded and fetched again). */
    private static final int CACHE_VERSION = 12;
    private static final int MANHA = 0, TARDE = 1, NOITE = 2;
    /** Service name on TMDB (part of the name, lowercase) → TV app (HomeApps key, package). */
    private static final String[][] SERVICES = {
        {"netflix", "netflix", "com.netflix.ninja"},
        {"prime video", "prime", "com.amazon.amazonvideo.livingroom"},
        {"disney", "disney", "com.disney.disneyplus"},
        {"hulu", "hulu", "com.hulu.livingroomplus"},
        {"paramount", "paramount", "com.cbs.ott"},
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
    }
    /**
     * Country for "what streams where": the Region setting (Free My TV > TMDB), whose first value is config.properties'
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
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final Map<String, Backdrop> images = new LinkedHashMap<>();   // this hour's, built and ready to draw
    private final java.util.Set<String> pending = new java.util.HashSet<>();
    private volatile List<Pick> picks = new ArrayList<>();
    private volatile int period = -1;
    private volatile long listAt;
    private volatile boolean fetching;
    private volatile String error = "";
    private volatile boolean light;     // light design: builds only the cards (all three tabs' cards fit in memory)
    private volatile long failedAt, keyStamp, linkRetryAt;
    private volatile String listLanguage = "";   // language used for the saved titles, genres and texts

    private Recs(Context c) {
        context = c;
        cache = c.getSharedPreferences("recs", Context.MODE_PRIVATE);
        if (cache.getInt("version", 0) != CACHE_VERSION) cache.edit().clear().putInt("version", CACHE_VERSION).apply();
        try {
            String saved = cache.getString("picks", null);
            if (saved != null) picks = parse(new JSONArray(saved));
            period = cache.getInt("period", -1);
            listAt = cache.getLong("listAt", 0);
            listLanguage = cache.getString("language", cache.getBoolean("english", false) ? "en" : "pt");
            keyStamp = cache.getLong("keyStamp", 0);
        } catch (Exception ignored) {
        }
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
            return new JSONObject(read(in));
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
        return !hasKey(context) || (picks.isEmpty() && !error.isEmpty());
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

    /** This hour's picks (the first is the featured one; the arrows switch to the others). */
    List<Pick> now() {
        return now(null);
    }

    /** This hour's picks (pick_count, 3 by default), movies only ("movie") or shows only ("tv"); null = both (the home screen tabs). */
    List<Pick> now(String kind) {
        List<Pick> list = new ArrayList<>();
        if (!hasKey(context)) return list;    // no TMDB key: no recommendations
        Map<String, Boolean> have = new HashMap<>();   // one look per app, not per title (this runs every 15 s)
        for (Pick p : picks) {
            if ((kind == null || kind.equals(p.kind))
                    && (!p.detailed || (p.pkg != null && have.computeIfAbsent(p.pkg, this::installed)))) list.add(p);   // app missing: skip
        }
        if (list.isEmpty()) return list;
        long hour = System.currentTimeMillis() / 3600_000L;
        // Three per hour (pick_count: more, that the home screen slides through), never two from the same franchise
        // ("Law & Order" and "Law & Order: Special…").
        List<Pick> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < list.size() && out.size() < Config.PICK_COUNT; i++) {
            Pick p = list.get((int) ((hour * 3 + i) % list.size()));
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

    /** The pick's ready backdrop (or null: builds it in the background and notifies when it's ready). */
    Backdrop backdrop(Pick p) {
        if (p == null || p.backdrop == null || !p.detailed) return null;   // the details choose the image: wait for them
        final String key = p.backdrop;    // the details may swap the image midway
        synchronized (images) {
            Backdrop b = images.get(key);
            if (b != null) return b;
            if (!pending.add(key)) return null;
        }
        io.execute(() -> {
            try {
                loadImage(p);
            } finally {
                synchronized (images) { pending.remove(key); }
            }
        });
        return null;
    }

    private boolean hasImage(Pick p) {
        return p.backdrop != null && imageFile(p).exists();
    }

    private File imageFile(Pick p) {
        return new File(new File(context.getCacheDir(), "recs"), p.backdrop.replace("/", ""));
    }

    /** With a key: fetches the list if stale or the time of day (morning/afternoon/night) changed; then the details. */
    void refreshIfStale() {
        if (!hasKey(context) || fetching) return;
        long now = System.currentTimeMillis();
        long stamp = sourceStamp();   // a new key, country, animation choice or installed streaming-app set refetches
        boolean newKey = stamp != keyStamp;
        long sinceFail = now - failedAt;    // negative = the clock went back: don't wait
        if (!newKey && failedAt != 0 && sinceFail >= 0 && sinceFail < 10 * 60_000L) return;
        int p = period(now);
        boolean stale = newKey || p != period || now - listAt > LIST_EVERY || now < listAt || picks.isEmpty()
                || !listLanguage.equals(Ui.language);
        boolean missing = false;
        for (Pick pick : wanted()) missing |= !pick.detailed || !hasImage(pick) || needsLink(pick);
        if (!stale && !missing) return;
        keyStamp = stamp;
        fetching = true;
        io.execute(() -> {
            try {
                if (stale) fetchList(p);
                // One failing title doesn't stop the others; whatever has no app on the TV is not downloaded.
                int failures = 0, tried = 0;
                String last = "";
                for (Pick pick : wanted()) {
                    tried++;
                    try {
                        if (!pick.detailed) fetchDetails(pick);
                        if (pick.pkg != null) download(pick);
                    } catch (Exception e) {
                        failures++;
                        last = e.getMessage() == null ? e.toString() : e.getMessage();
                    }
                    findLink(pick);
                    if (ROUTED.equals(pick.pkg)) Launcher.get(context).prefetch(pick.kind, pick.id);   // so OK opens it at once
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

    /** Home screen design: the ready images are rebuilt for it (the light design uses cards, not the whole photo). */
    void setLight(boolean value) {
        if (value == light) return;
        light = value;
        synchronized (images) {
            images.clear();
        }
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

    /** The pick's original image if it's already on disk (the screensaver loads it in its own way), or null. */
    File readyImage(Pick p) {
        return hasImage(p) ? imageFile(p) : null;
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
                        if (p.pkg != null) download(p);
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
        if (Config.PICK_COUNT > PAGE) {
            List<Pick> list = now(viewKind);
            for (int i = 0; i < Math.min(2 * PAGE, list.size()); i++) out.add(list.get((viewStart + i) % list.size()));
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

    private void fetchList(int p) throws Exception {
        if (listOnly()) {
            fetchListOnly(p);
            return;
        }
        String ids = installedProviderIds();
        String common = "&language=" + lang() + "&watch_region=" + region() + monetization() + languageFilter() + "&include_adult=false"
                + "&sort_by=popularity.desc" + "&with_watch_providers=" + URLEncoder.encode(ids.toString(), "UTF-8")
                + (Prefs.hideAnimation() ? "&without_genres=" + ANIMATION : "");
        // 1. The most popular right now (TMDB's popularity follows what people are watching and searching for), already
        //    filtered to the TV's services: one request each. The details (which service, runtime) come later, only for
        //    the titles that are shown.
        List<Pick> hotMovies = new ArrayList<>(), hotShows = new ArrayList<>();
        JSONArray hotM = get("/discover/movie?vote_count.gte=100" + common).getJSONArray("results");
        JSONArray hotS = get("/discover/tv?vote_count.gte=50" + common).getJSONArray("results");
        for (int i = 0; i < hotM.length(); i++) add(hotMovies, hotM.getJSONObject(i), "movie");
        for (int i = 0; i < hotS.length(); i++) add(hotShows, hotS.getJSONObject(i), "tv");
        // 2. Recent releases on the TV's services: movies from the past year and shows with a new episode in the
        //    past 3 months.
        JSONArray movies = get("/discover/movie?vote_count.gte=30&primary_release_date.gte=" + day(-365)
                + "&primary_release_date.lte=" + day(0) + common).getJSONArray("results");
        JSONArray shows = get("/discover/tv?vote_count.gte=20&air_date.gte=" + day(-90) + "&air_date.lte=" + day(0)
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
            Map<String, Pick> old = new HashMap<>();
            for (Pick o : picks) if (o.detailed) old.put(key(o), o);
            for (Pick q : list) {
                Pick o = old.get(key(q));
                if (o == null) continue;
                q.title = o.title;
                q.backdrop = o.backdrop;
                q.meta = o.meta;
                q.app = o.app;
                q.pkg = o.pkg;
                q.link = o.link;
                q.detailed = true;
            }
        }
        picks = list;
        period = p;
        listAt = System.currentTimeMillis();
        listLanguage = Ui.language;
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
        JSONArray providers = get("/watch/providers/movie?language=" + lang() + "&watch_region=" + region()).getJSONArray("results");
        for (int i = 0; i < providers.length(); i++) {
            JSONObject o = providers.getJSONObject(i);
            String[] s = service(o.optString("provider_name"));
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
        for (String path : paths) pending.add(net.submit(() -> get(path)));
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

    private void fetchListOnly(int p) throws Exception {
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
        Map<String, Pick> old = new HashMap<>();
        for (Pick o : picks) old.put(key(o), o);
        for (Pick q : list) {       // a title still in the list keeps the IMDb id it already found
            Pick o = old.get(key(q));
            if (o != null) {
                q.link = o.link;
                if (o.detailed) {       // its best backdrop was already chosen
                    q.backdrop = o.backdrop;
                    q.detailed = true;
                }
            }
        }
        picks = list;
        period = p;
        listAt = System.currentTimeMillis();
        listLanguage = Ui.language;
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
        }
        return out;
    }

    // MARK: - What Launcher needs (which app a service opens; the rest of this class stays about the picks)

    /** The SERVICES row (name part, key, package) for a provider's name on TMDB, or null when the TV has no app we know for it. */
    String[] serviceRow(String providerName) {
        return service(providerName);
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

    /** The service is one the person hides (Free My TV > TMDB > Hidden services): it never opens, even when its app is on the TV. */
    boolean hiddenService(String providerName) {
        String[] row = service(providerName);
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

    /** Picks from anything popular: the title's best backdrop (no text over it, closest to 1920 wide), one small request; TMDB's default stays if it has none. */
    private void fetchBackdrop(Pick p) throws Exception {
        String best = bestBackdrop(get("/" + p.kind + "/" + p.id + "/images?include_image_language=null"));
        if (best != null) p.backdrop = best;
        p.detailed = true;
    }

    /** Genre, runtime (or seasons) and the service it's on, among the ones the TV has. */
    private void fetchDetails(Pick p) throws Exception {
        if (listOnly()) {
            fetchBackdrop(p);
            return;
        }
        JSONObject o = get("/" + p.kind + "/" + p.id
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
            String[] s = service(flat.getJSONObject(i).optString("provider_name"));
            if (s != null && installed(s[2])) {
                p.app = s[1];
                p.pkg = s[2];
            }
        }
        if (p.app != null) parts.add(serviceName(p));
        p.meta = String.join(" · ", parts);
        p.detailed = true;
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
            String english = get("/" + p.kind + "/" + p.id + "?language=en-US").optString(field);
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
    private String[] service(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        String[] first = null;
        for (String[] s : SERVICES) {
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

    private static String linkKey(Pick p) {
        return "wm:" + p.kind + p.id + p.app;
    }

    private boolean needsLink(Pick p) {
        return linksOn() && p.pkg != null && p.link == null
                && cache.getString(linkKey(p), null) == null && System.currentTimeMillis() >= linkRetryAt;
    }

    /** A key is set and Watchmode hasn't refused it (a refused key isn't tried again until it changes). */
    private boolean linksOn() {
        String key = Prefs.watchmodeKey();
        return !listOnly() && !key.isEmpty() && cache.getInt("wm:refused", 0) != key.hashCode();
    }

    /** Sets p.link from what was looked up before, or looks it up (2 requests of the free plan's 2,500 a month). */
    private void findLink(Pick p) {
        if (!linksOn() || p.pkg == null || p.link != null) return;
        String saved = cache.getString(linkKey(p), null);
        if (saved != null) {
            p.link = saved.isEmpty() ? null : saved;
            return;
        }
        if (System.currentTimeMillis() < linkRetryAt) return;
        try {
            String link = "";
            JSONArray found = new JSONObject(watchmode("/search/?search_field=tmdb_" + p.kind + "_id&search_value=" + p.id))
                    .optJSONArray("title_results");
            if (found != null && found.length() > 0) {
                JSONArray sources = new JSONArray(watchmode("/title/" + found.getJSONObject(0).getInt("id") + "/sources/?regions=" + region()));
                String[] service = null;
                for (String[] s : SERVICES) if (s[1].equals(p.app)) service = s;
                for (int i = 0; service != null && i < sources.length() && link.isEmpty(); i++) {
                    JSONObject s = sources.getJSONObject(i);
                    String name = s.optString("name").toLowerCase(Locale.ROOT).replace(" ", "");
                    String want = service[0].replace(" ", "");
                    boolean same = want.equals("max") ? name.equals("max") : name.contains(want);
                    if (same && s.optString("type").equals("sub") && s.optString("web_url").startsWith("https://")) link = s.optString("web_url");
                }
            }
            cache.edit().putString(linkKey(p), link).apply();
            p.link = link.isEmpty() ? null : link;
        } catch (Exception e) {
            if ("Watchmode returned HTTP 401".equals(e.getMessage())) {
                cache.edit().putInt("wm:refused", Prefs.watchmodeKey().hashCode()).apply();
            }
            linkRetryAt = System.currentTimeMillis() + 10 * 60_000L;   // quota or network: not again right away
            AppLog.w("watchmode: " + e.getMessage());
        }
    }

    private static String watchmode(String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.watchmode.com/v1" + path + (path.contains("?") ? "&" : "?")
                + "apiKey=" + URLEncoder.encode(Prefs.watchmodeKey(), "UTF-8")).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("Watchmode returned HTTP " + code);
            return read(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    /** A TMDB request (the key is added); Launcher asks for a title's streaming services with it. */
    JSONObject get(String path) throws IOException, org.json.JSONException {
        String key = readKey();
        if (key == null) throw new IOException("no TMDB key");
        boolean bearer = key.startsWith("eyJ");   // read access token (v4) or API key (v3)
        String url = API + path + (bearer ? "" : (path.contains("?") ? "&" : "?") + "api_key=" + URLEncoder.encode(key, "UTF-8"));
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Accept", "application/json");
        if (bearer) c.setRequestProperty("Authorization", "Bearer " + key);
        try {
            int code = c.getResponseCode();
            if (code == 401) throw new IOException("TMDB rejected the key");
            if (code != 200) throw new IOException("TMDB returned HTTP " + code);
            return new JSONObject(read(c.getInputStream()));
        } finally {
            c.disconnect();
        }
    }

    private String readKey() {
        String key = Prefs.tmdbKey();
        return key.isEmpty() ? null : key;
    }

    // MARK: - Images (TMDB originals on disk, up to 24 so the whole list fits; this hour's kept ready in memory)

    private void download(Pick p) throws IOException {
        File f = imageFile(p);
        if (f.exists()) return;
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        File part = new File(f.getPath() + ".part");
        HttpURLConnection c = (HttpURLConnection) new URL(IMAGES + p.backdrop).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        } finally {
            c.disconnect();
        }
        if (!part.renameTo(f)) throw new IOException("couldn't save the image");
        prune(f.getParentFile());
    }

    /** Reads the photo (≥ the UI's width) and builds the backdrop (Backdrop: cards plus frosted and glass versions). */
    private void loadImage(Pick p) {
        synchronized (images) {
            if (images.containsKey(p.backdrop)) return;
        }
        File f = imageFile(p);
        try {
            download(p);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (bounds.outWidth / (opts.inSampleSize * 2) >= Backdrop.W) opts.inSampleSize *= 2;
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), opts);
            if (b == null) throw new IOException("unreadable image");
            AppLog.w("backdrop \u201c" + p.title + "\u201d: " + bounds.outWidth + "×" + bounds.outHeight + " → " + Backdrop.W + "×" + Backdrop.H);
            boolean forLight = light;
            Backdrop backdrop = Backdrop.make(b, forLight);
            if (forLight != light) return;   // the design changed midway: this one is stale
            synchronized (images) {
                images.put(p.backdrop, backdrop);
                while (images.size() > (forLight ? Math.max(10, Config.PICK_COUNT + 3) : 4)) images.remove(images.keySet().iterator().next());
            }
            notifyListeners();
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            AppLog.w("recommendation image: " + e.getMessage());
        }
    }

    void trim() {
        // Explore's picks stay: the home screen opens on that tab, so its cards are never gray when you come back.
        java.util.Set<String> keep = new java.util.HashSet<>();
        for (Pick p : now(null)) if (p.backdrop != null) keep.add(p.backdrop);
        synchronized (images) {
            images.keySet().retainAll(keep);
        }
    }

    private static void prune(File dir) {
        // A download cut off when the system stopped the app leaves its .part behind.
        long stale = System.currentTimeMillis() - 10 * 60_000L;
        File[] parts = dir.listFiles((d, name) -> name.endsWith(".part"));
        for (int i = 0; parts != null && i < parts.length; i++) {
            //noinspection ResultOfMethodCallIgnored
            if (parts[i].lastModified() < stale) parts[i].delete();
        }
        File[] files = dir.listFiles((d, name) -> !name.endsWith(".part"));
        if (files == null || files.length <= 24) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - 24; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }

    // MARK: - List cache

    private void save() {
        try {
            JSONArray a = new JSONArray();
            for (Pick p : picks) {
                a.put(new JSONObject().put("id", p.id).put("kind", p.kind).put("title", p.title).put("backdrop", p.backdrop).put("link", p.link == null ? "" : p.link)
                        .put("meta", p.meta).put("app", p.app == null ? "" : p.app).put("pkg", p.pkg == null ? "" : p.pkg)
                        .put("detailed", p.detailed));
            }
            SharedPreferences.Editor e = cache.edit().putString("picks", a.toString()).putInt("period", period).putLong("listAt", listAt)
                    .putString("language", listLanguage)
                    .putLong("keyStamp", keyStamp).putInt("version", CACHE_VERSION);
            // Watchmode links of titles no longer in the list go (the refused-key mark stays).
            Set<String> keep = new HashSet<>();
            for (Pick p : picks) keep.add(linkKey(p));
            for (String k : cache.getAll().keySet()) if (k.startsWith("wm:") && !k.equals("wm:refused") && !keep.contains(k)) e.remove(k);
            e.apply();
        } catch (Exception ignored) {
        }
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
            p.link = o.optString("link").isEmpty() ? null : o.optString("link");
            p.meta = o.optString("meta");
            p.app = o.optString("app").isEmpty() ? null : o.optString("app");
            p.pkg = o.optString("pkg").isEmpty() ? null : o.optString("pkg");
            p.detailed = o.optBoolean("detailed");
            out.add(p);
        }
        return out;
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
