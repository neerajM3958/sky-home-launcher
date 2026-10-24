package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.database.ContentObserver;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The poster rows under the drawer's weather and quotes cards (config.properties "catalogs"): one TMDB list request per
 * row, saved for a few hours, with posters loaded only when a row is near the screen. A title opens through Launcher
 * (its own app, its Play Store page, or Stremio), like every other card.
 */
final class Catalogs {
    static final class Item {
        int id;
        String kind;        // "movie" or "tv"
        String title, poster;
        double rating;
        String link;        // the title's page in its app, once found
        String imdb;        // Trakt's rows know it already: Stremio's page needs no TMDB request
        int traktId;        // Trakt's own id, for its episode list
        boolean episodes;   // OK opens the show's episodes (the "Shows you watch" row) instead of the title's page
        String stars;       // "★ 7.8", or null
        String posterUrl;   // Trakt's own poster (a whole address); null: `poster` is a TMDB path
        String sub;         // under the title instead of the stars ("S2 · E5"), or null
        float progress;     // 0 to 1: a bar on the poster, when it is part-way through
        long at;            // when it was last watched (Continue Watching's order), 0 when unknown
        android.content.Intent launch;      // Watch Next's: OK reopens that app where it was left (not saved)
        String appPkg;      // ... and the app that published it
        String servicePkg;  // the app it opens in, for the card's icon: null not known yet, "" none (UI thread)
        int serviceAsked;   // 1: looked in what is kept, 2: asked TMDB too (UI thread)
    }

    static final class Row {
        String key, title;
        final List<Item> items = new ArrayList<>();
    }

    interface Listener { void onCatalogs(); }

    private static final long EVERY = 6 * 3600_000L;
    private static final int VERSION = 1, MAX_POSTERS = 40;
    private static final String IMAGES = "https://image.tmdb.org/t/p/w342";
    private static final int ANIMATION = 16;

    private static Catalogs instance;

    static synchronized Catalogs get(Context c) {
        if (instance == null) instance = new Catalogs(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final SharedPreferences cache;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService lists = Executors.newSingleThreadExecutor(r -> daemon(r, "catalogs"));
    private final ExecutorService images = Executors.newFixedThreadPool(2, r -> daemon(r, "posters"));
    private final ExecutorService watching = Executors.newSingleThreadExecutor(r -> daemon(r, "watch-next"));
    private volatile List<Item> watchNext = new ArrayList<>();    // what the TV's apps say you were watching, newest first
    private volatile boolean watchNextBusy, watchNextAgain;
    private volatile long watchNextAt;
    private final List<Listener> listeners = new ArrayList<>();
    private final Map<String, Bitmap> posters = new LinkedHashMap<String, Bitmap>(32, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
            return size() > MAX_POSTERS;
        }
    };
    private final Set<String> pending = new HashSet<>();
    private volatile List<Row> rows = new ArrayList<>();      // TMDB's
    private volatile List<Row> shown = new ArrayList<>();     // Trakt's rows (when connected), then TMDB's
    private volatile String shownFor = "";                   // what wanted() was when `shown` was built
    private volatile boolean fetching;
    private volatile long at, failedAt;
    private volatile String language = "";

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    private Catalogs(Context c) {
        context = c;
        cache = c.getSharedPreferences("catalogs", Context.MODE_PRIVATE);
        if (cache.getInt("version", 0) != VERSION) cache.edit().clear().putInt("version", VERSION).apply();
        try {
            String saved = cache.getString("rows", null);
            if (saved != null && wanted().equals(cache.getString("wanted", ""))) rows = parse(new JSONArray(saved));
            at = cache.getLong("at", 0);
            language = cache.getString("language", "");
        } catch (Exception ignored) {
        }
        Trakt.get(c).setListener(() -> { rebuild(); notifyListeners(); });
        rebuild();
        try {       // an app changes its entries: read them again
            c.getContentResolver().registerContentObserver(TvContract.WatchNextPrograms.CONTENT_URI, true, new ContentObserver(main) {
                @Override public void onChange(boolean selfChange) {
                    refreshWatchNext(true);
                }
            });
        } catch (RuntimeException e) {
            AppLog.w("watch next: " + e.getMessage());
        }
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    /** The rows to show: Trakt's when connected, then TMDB's (they need its key and "catalogs=" not empty). A card opens through Launcher. */
    List<Row> rows() {
        return shown;
    }

    /** A new list each time the rows change: the home screen compares it by reference. TMDB's follow the order the person set, and a row turned off goes at once. */
    private void rebuild() {
        List<Row> all = withWatchNext(new ArrayList<>(Trakt.get(context).rows()));
        if (Recs.hasKey(context)) {
            for (String k : keys()) for (Row r : rows) if (r.key.equals(k)) all.add(r);
        }
        shown = all;
        shownFor = wanted();
    }

    // MARK: - Continue Watching: Trakt's, and what the TV's apps say (Watch Next)

    /**
     * Trakt's Continue Watching with the Watch Next entries Trakt doesn't list, by most recent first; with no Trakt
     * list, the entries alone. `trakt` is a copy the rows of which are not touched.
     */
    private List<Row> withWatchNext(List<Row> trakt) {
        List<Item> mine = watchNext;
        if (mine.isEmpty() || !Prefs.watchNext()) return trakt;
        int at = -1;
        for (int i = 0; i < trakt.size() && at < 0; i++) if (Trakt.CONTINUE.equals(trakt.get(i).key)) at = i;
        List<Item> theirs = at < 0 ? new ArrayList<>() : trakt.get(at).items;
        Row row = new Row();
        row.key = Trakt.CONTINUE;
        row.title = Ui.tr("Continue Watching");
        row.items.addAll(theirs);
        int added = 0;
        for (Item it : mine) {
            if (added >= WatchNext.MAX) break;
            if (!listed(it, theirs)) {
                row.items.add(it);
                added++;
            }
        }
        if (added == 0) return trakt;
        java.util.Collections.sort(row.items, (a, b) -> Long.compare(b.at, a.at));     // stable: equal times keep Trakt's order
        if (at < 0) trakt.add(0, row); else trakt.set(at, row);
        return trakt;
    }

    /** Trakt's list already has the title: by TMDB id when both know it, else by name. */
    private static boolean listed(Item it, List<Item> trakt) {
        String name = WatchNext.normalize(it.title);
        for (Item t : trakt) {
            if (it.id > 0 && t.id == it.id && it.kind.equals(t.kind)) return true;
            if (name.equals(WatchNext.normalize(t.title))) return true;
        }
        return false;
    }

    /** Reads Watch Next again (a local query, TMDB lookups kept): when the drawer opens, and when an app changes its entries. */
    void refreshWatchNext(boolean changed) {
        if (!Prefs.watchNext() || !WatchNext.permitted(context)) {
            if (!watchNext.isEmpty()) {
                watchNext = new ArrayList<>();
                rebuild();
                notifyListeners();
            }
            return;
        }
        if (watchNextBusy) {
            watchNextAgain |= changed;
            return;
        }
        if (SystemClock.elapsedRealtime() - watchNextAt < (changed ? 3_000L : 20_000L)) return;
        watchNextBusy = true;
        watching.execute(() -> {
            try {
                do {
                    watchNextAgain = false;
                    List<Item> next = WatchNext.items(context);
                    if (!same(next, watchNext)) {
                        AppLog.w("watch next: " + next.size() + " from the TV's apps");
                        watchNext = next;
                        rebuild();
                        notifyListeners();
                    }
                } while (watchNextAgain);
            } catch (RuntimeException e) {
                AppLog.w("watch next: " + e.getMessage());
            } finally {
                watchNextAt = SystemClock.elapsedRealtime();
                watchNextBusy = false;
            }
        });
    }

    private static boolean same(List<Item> a, List<Item> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            Item x = a.get(i), y = b.get(i);
            if (!x.title.equals(y.title) || x.at != y.at || x.progress != y.progress || !java.util.Objects.equals(x.sub, y.sub)
                    || !java.util.Objects.equals(x.poster, y.poster)) return false;
        }
        return true;
    }

    /** Every row TMDB can fill, in the order they are offered (Free My TV > TMDB > Poster rows). */
    static final String[] ALL = {"trending", "popular_movies", "popular_shows", "hindi", "top_rated", "now_playing", "upcoming"};

    /** The rows that are on (Free My TV > TMDB > Poster rows; catalogs= in config.properties gives the first ones), in order. */
    private static List<String> keys() {
        List<String> out = new ArrayList<>();
        for (String k : Prefs.catalogRows()) if (title(k) != null && !out.contains(k)) out.add(k);
        return out;
    }

    private static String wanted() {
        return String.join(",", keys()) + (Prefs.hideAnimation() ? "|noanimation" : "")
                + "|" + (Prefs.recommendFromApps() ? "apps" : "any") + (Prefs.flatrateOnly() ? "S" : "") + (Prefs.flatrateOnly() && Prefs.includeRentBuy() ? "R" : "")
                + Recs.region() + Prefs.languages() + Prefs.hiddenServices();
    }

    static String title(String key) {
        switch (key) {
            case "trending": return Ui.tr("Trending today");
            case "popular_movies": return Ui.tr("Popular movies");
            case "popular_shows": return Ui.tr("Popular shows");
            case "top_rated": return Ui.tr("Top rated");
            case "now_playing": return Ui.tr("Now playing");
            case "upcoming": return Ui.tr("Upcoming");
            case "hindi": return Ui.tr("Hindi picks");
            default: return null;
        }
    }

    /**
     * The discover filter of the TMDB section's recommendation settings (what streams in the region, on the TV's apps),
     * or null when neither is on: then the rows are TMDB's plain lists.
     */
    private static String filter(Recs recs) throws Exception {
        boolean apps = Prefs.recommendFromApps();
        if (!apps && !Prefs.flatrateOnly() && Prefs.languages().isEmpty()) return null;
        return "&watch_region=" + Recs.region() + Recs.monetization()
                + (apps ? "&with_watch_providers=" + java.net.URLEncoder.encode(recs.installedProviderIds(), "UTF-8") : "");
    }

    /**
     * The TMDB requests of a row, each {kind of its titles ("movie", "tv", or "" when the answer says), path}. Popularity
     * is worldwide; titles come in the TV's language. With a filter every row is a discover query, since TMDB's own
     * trending, top rated, now playing and upcoming lists take none: trending is the popular movies and shows
     * together, top rated the best rated ones, now playing the releases of the past six weeks, upcoming the next six
     * months (which mostly stream nowhere yet, so with the filter on that row usually has nothing and goes).
     */
    private static List<String[]> requests(String key, String lang, String filter) {
        String q = "?language=" + lang + "&include_adult=false" + (filter == null ? "" : filter);
        // The Hindi row is Hindi whatever the languages say.
        String languages = filter == null || key.equals("hindi") ? "" : Recs.languageFilter();
        q += languages;
        String popular = "&sort_by=popularity.desc";
        List<String[]> out = new ArrayList<>();
        if (filter == null) {
            switch (key) {
                case "trending": out.add(new String[] {"", "/trending/all/day" + q}); break;
                case "popular_movies": out.add(new String[] {"movie", "/discover/movie" + q + popular + "&vote_count.gte=100"}); break;
                case "popular_shows": out.add(new String[] {"tv", "/discover/tv" + q + popular + "&vote_count.gte=50"}); break;
                case "top_rated": out.add(new String[] {"movie", "/movie/top_rated" + q}); break;
                case "now_playing": out.add(new String[] {"movie", "/movie/now_playing" + q}); break;
                case "upcoming": out.add(new String[] {"movie", "/movie/upcoming" + q}); break;
                default: out.add(new String[] {"movie", "/discover/movie" + q + popular + "&vote_count.gte=20&with_original_language=hi"});
            }
            return out;
        }
        switch (key) {
            case "trending":
                out.add(new String[] {"movie", "/discover/movie" + q + popular + "&vote_count.gte=100"});
                out.add(new String[] {"tv", "/discover/tv" + q + popular + "&vote_count.gte=50"});
                break;
            case "popular_movies": out.add(new String[] {"movie", "/discover/movie" + q + popular + "&vote_count.gte=100"}); break;
            case "popular_shows": out.add(new String[] {"tv", "/discover/tv" + q + popular + "&vote_count.gte=50"}); break;
            case "top_rated": out.add(new String[] {"movie", "/discover/movie" + q + "&sort_by=vote_average.desc&vote_count.gte=500"}); break;
            case "now_playing":
                out.add(new String[] {"movie", "/discover/movie" + q + popular + "&primary_release_date.gte=" + Recs.day(-45) + "&primary_release_date.lte=" + Recs.day(0)});
                break;
            case "upcoming":
                out.add(new String[] {"movie", "/discover/movie" + q + popular + "&primary_release_date.gte=" + Recs.day(1) + "&primary_release_date.lte=" + Recs.day(180)});
                break;
            default: out.add(new String[] {"movie", "/discover/movie" + q + popular + "&vote_count.gte=20&with_original_language=hi"});
        }
        return out;
    }

    /** Fetches the rows when they are older than a few hours, in another language or not the ones asked for (the home screen calls this when the drawer opens). */
    void refreshIfStale() {
        refreshWatchNext(false);
        if (!wanted().equals(shownFor)) {      // the rows or the animation choice changed in the settings
            failedAt = 0;       // a new try, even if the last one failed a minute ago
            rebuild();
            notifyListeners();
        }
        Trakt.get(context).refreshIfStale();
        if (fetching || !Recs.hasKey(context)) return;
        List<String> keys = keys();
        if (keys.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (failedAt != 0 && now - failedAt >= 0 && now - failedAt < 10 * 60_000L) return;
        boolean fresh = now >= at && now - at < EVERY && language.equals(Ui.language) && wanted().equals(cache.getString("wanted", ""))
                && !rows.isEmpty();
        if (fresh) return;
        fetching = true;
        lists.execute(() -> {
            try {
                String lang = Recs.lang();
                String filter = filter(Recs.get(context));
                List<String> paths = new ArrayList<>();
                List<List<String[]>> asked = new ArrayList<>();
                for (String key : keys) {
                    List<String[]> r = requests(key, lang, filter);
                    asked.add(r);
                    for (String[] one : r) paths.add(one[1]);
                }
                org.json.JSONObject[] answers = Recs.get(context).getAll(paths.toArray(new String[0]));
                List<Row> next = new ArrayList<>();
                int n = 0;
                for (int i = 0; i < keys.size(); i++) {
                    Row row = new Row();
                    row.key = keys.get(i);
                    row.title = title(row.key);
                    List<String[]> r = asked.get(i);
                    // A row with two requests (trending, with a filter) takes their titles in turn.
                    for (int j = 0; j < ROW_SIZE; j++) {
                        for (int a = 0; a < r.size() && row.items.size() < ROW_SIZE; a++) {
                            JSONArray results = answers[n + a].optJSONArray("results");
                            if (results != null && j < results.length()) add(row, results.optJSONObject(j), r.get(a)[0]);
                        }
                    }
                    n += r.size();
                    if (!row.items.isEmpty()) next.add(row);
                }
                if (next.isEmpty()) throw new IOException("TMDB returned no titles");
                // A title found before keeps its link.
                for (Row row : next) for (Item it : row.items) {
                    for (Row old : rows) for (Item o : old.items) if (o.link != null && o.id == it.id && o.kind.equals(it.kind)) it.link = o.link;
                }
                rows = next;
                rebuild();
                at = System.currentTimeMillis();
                language = Ui.language;
                failedAt = 0;
                save(keys);
            } catch (Exception e) {
                failedAt = System.currentTimeMillis();
                AppLog.w("catalogs: " + e.getMessage());
                if (!wanted().equals(cache.getString("wanted", ""))) {      // the settings changed and nothing fits them
                    rows = new ArrayList<>();
                    rebuild();
                }
            } finally {
                fetching = false;
                notifyListeners();
            }
        });
    }

    private static final int ROW_SIZE = 20;

    /** `kind`: "movie" or "tv" when the request was for one, "" when each title says (media_type). */
    private static void add(Row row, JSONObject o, String kind) {
        if (o == null) return;
        String poster = o.optString("poster_path", "");
        if (poster.isEmpty() || "null".equals(poster)) return;
        String type = o.optString("media_type", "");
        String own = kind.equals("tv") || type.equals("tv") ? "tv" : type.equals("person") ? null : "movie";
        if (own == null) return;
        kind = own;
        if (Prefs.hideAnimation()) {
            JSONArray genres = o.optJSONArray("genre_ids");
            for (int i = 0; genres != null && i < genres.length(); i++) if (genres.optInt(i) == ANIMATION) return;
        }
        Item it = new Item();
        it.id = o.optInt("id");
        it.kind = kind;
        it.title = o.optString(kind.equals("movie") ? "title" : "name");
        it.poster = poster;
        it.rating = o.optInt("vote_count") >= 10 ? o.optDouble("vote_average", 0) : 0;
        it.stars = it.rating > 0 ? String.format(Ui.locale(), "★ %.1f", it.rating) : null;
        row.items.add(it);
    }

    private void save(List<String> keys) {
        try {
            JSONArray a = new JSONArray();
            for (Row row : rows) {
                JSONArray items = new JSONArray();
                for (Item it : row.items) {
                    items.put(new JSONObject().put("id", it.id).put("kind", it.kind).put("title", it.title).put("poster", it.poster)
                            .put("rating", it.rating).put("link", it.link == null ? "" : it.link));
                }
                a.put(new JSONObject().put("key", row.key).put("items", items));
            }
            cache.edit().putString("rows", a.toString()).putString("wanted", wanted()).putLong("at", at)
                    .putString("language", language).apply();
        } catch (Exception ignored) {
        }
    }

    private static List<Row> parse(JSONArray a) {
        List<Row> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Row row = new Row();
            row.key = o.optString("key");
            row.title = title(row.key);
            JSONArray items = o.optJSONArray("items");
            for (int j = 0; row.title != null && items != null && j < items.length(); j++) {
                JSONObject p = items.optJSONObject(j);
                if (p == null) continue;
                Item it = new Item();
                it.id = p.optInt("id");
                it.kind = p.optString("kind");
                it.title = p.optString("title");
                it.poster = p.optString("poster");
                it.rating = p.optDouble("rating", 0);
                it.link = p.optString("link").isEmpty() ? null : p.optString("link");
                it.stars = it.rating > 0 ? String.format(Ui.locale(), "★ %.1f", it.rating) : null;
                row.items.add(it);
            }
            if (!row.items.isEmpty()) out.add(row);
        }
        return out;
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onCatalogs(); });
    }

    // MARK: - Posters (w342 on disk; the ones near the screen stay decoded in memory)

    /** The decoded poster at about `width` pixels, or null: it is loaded in the background and the listeners are told when it is ready. */
    Bitmap poster(Item it, int width) {
        synchronized (posters) {
            Bitmap b = posters.get(it.poster);
            if (b != null) return b;
            if (!pending.add(it.poster)) return null;
        }
        images.execute(() -> {
            try {
                File f = new File(new File(context.getCacheDir(), "catalogs"), it.poster.replace("/", ""));
                boolean appArt = it.poster.startsWith("wn-");      // an app's own picture, often wide
                if (!f.exists()) {
                    if (it.posterUrl != null && !it.posterUrl.startsWith("http")) copy(it.posterUrl, f);      // content:, android.resource: (an app's own picture)
                    else download(it.posterUrl != null ? it.posterUrl : IMAGES + it.poster, f);
                }
                // Trakt's posters are larger than TMDB's w342: decoded at half or a quarter of their size when that is still enough.
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(f.getPath(), bounds);
                BitmapFactory.Options decode = new BitmapFactory.Options();
                int sample = 1;
                // A wide picture is cut to the portrait card, so what counts is its height.
                while (sample < 8 && (appArt ? bounds.outHeight / (sample * 2) >= width * 15 / 8 : bounds.outWidth / (sample * 2) >= width * 5 / 4)) sample *= 2;
                decode.inSampleSize = sample;
                Bitmap b = BitmapFactory.decodeFile(f.getPath(), decode);
                if (appArt && b != null && b.getWidth() * 3 > b.getHeight() * 2 + 2) {
                    int cut = b.getHeight() * 2 / 3;
                    Bitmap tall = Bitmap.createBitmap(b, (b.getWidth() - cut) / 2, 0, cut, b.getHeight());
                    if (tall != b) b.recycle();
                    b = tall;
                }
                if (b != null && b.getWidth() > width * 5 / 4) {     // kept at the size it is drawn at: a quarter of the memory
                    Bitmap small = Bitmap.createScaledBitmap(b, width, Math.round(b.getHeight() * (float) width / b.getWidth()), true);
                    if (small != b) b.recycle();
                    b = small;
                }
                if (b == null) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                    throw new IOException("unreadable poster");
                }
                synchronized (posters) { posters.put(it.poster, b); }
                notifyListeners();
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                AppLog.w("poster: " + e.getMessage());
            } finally {
                synchronized (posters) { pending.remove(it.poster); }
            }
        });
        return null;
    }

    /** An app's picture from its own address (Watch Next art: content: or android.resource:), saved like a download. */
    private void copy(String uri, File f) throws IOException {
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        File part = new File(f.getPath() + ".part");
        try (InputStream in = context.getContentResolver().openInputStream(Uri.parse(uri));
             FileOutputStream out = new FileOutputStream(part)) {
            if (in == null) throw new IOException("no picture at " + uri);
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        } catch (IOException | RuntimeException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e instanceof IOException ? (IOException) e : new IOException(e.getMessage());
        }
        if (!part.renameTo(f)) throw new IOException("couldn't save the picture");
    }

    private static void download(String url, File f) throws IOException {
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        File part = new File(f.getPath() + ".part");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", "FreeMyTV");
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
        if (!part.renameTo(f)) throw new IOException("couldn't save the poster");
        File[] all = f.getParentFile().listFiles();     // the newest 300 stay
        if (all != null && all.length > 300) {
            Arrays.sort(all, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (int i = 0; i < all.length - 300; i++) {
                //noinspection ResultOfMethodCallIgnored
                all[i].delete();
            }
        }
    }
}
