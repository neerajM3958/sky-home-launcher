package com.skyhome.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.skyhome.launcher.Catalogs.Row;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The person's own Trakt lists (Sky Home Launcher > Trakt): "Continue Watching" and "Watchlist" rows for the home screen.
 * They sign in once with Trakt's device code (the TV shows a code, they enter it at trakt.tv/activate on a phone), so
 * no password is typed on the TV and, as Trakt issues new apps no secret, only config.properties' "trakt_client_id"
 * is needed. Only reads. The tokens stay in this app's private preferences and never go to the log.
 */
final class Trakt {
    /** The sign-in: nothing going on, asking for a code, or waiting for the person to enter it. */
    static final int IDLE = 0, STARTING = 1, WAITING = 2;
    static final int ERR_NONE = 0, ERR_NETWORK = 1, ERR_DENIED = 2, ERR_EXPIRED = 3, ERR_REFUSED = 4;

    interface Listener { void onTrakt(); }

    static final String CONTINUE = "trakt_continue", SHOWS = "trakt_shows", WATCHLIST = "trakt_watchlist";

    private static final String API = "https://api.trakt.tv";
    private static final long CHECK_EVERY = 30 * 60_000L;      // one small request tells whether anything changed
    private static final long RETRY_AFTER_FAILURE = 10 * 60_000L;
    // VERSION clears everything, the sign-in too; ROWS_VERSION only the saved rows (when a row is added or changes shape).
    private static final int VERSION = 1, ROWS_VERSION = 6, CONTINUE_MAX = 12, SHOWS_MAX = 20, WATCHLIST_MAX = 20, FETCH = 20;
    private static final long YEAR = 365L * 24 * 3600_000L;

    private static Trakt instance;

    static synchronized Trakt get(Context c) {
        if (instance == null) instance = new Trakt(c.getApplicationContext());
        return instance;
    }

    /** True with a Client ID in config.properties. */
    static boolean configured() {
        return !Prefs.traktClientId().isEmpty();
    }

    private final Context context;
    private final SharedPreferences sp;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService episodesNet = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "trakt-episodes");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService net = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "trakt");
        t.setDaemon(true);
        return t;
    });
    private volatile Listener listener;
    private volatile List<Catalogs.Row> rows = new ArrayList<>();
    private volatile boolean fetching;
    private volatile long checked, failedAt;

    // The sign-in, read by the settings screen on every frame.
    private volatile int loginState = IDLE, loginError = ERR_NONE;
    private volatile String loginCode = "", loginUrl = "";
    private volatile long loginEndsAt;
    private volatile int loginTicket;
    private Thread loginThread;

    private Trakt(Context c) {
        context = c;
        sp = c.getSharedPreferences("trakt", Context.MODE_PRIVATE);
        if (sp.getInt("version", 0) != VERSION) sp.edit().clear().putInt("version", VERSION).apply();
        if (sp.getInt("rowsVersion", 0) != ROWS_VERSION) {
            sp.edit().remove("rows").remove("stamp").putInt("rowsVersion", ROWS_VERSION).apply();
        }
        // checked stays 0: the first look after each start is one request, and refetches only if something changed.
        try {
            String saved = sp.getString("rows", null);
            if (saved != null) rows = parse(new JSONArray(saved));
        } catch (Exception ignored) {
        }
    }

    void setListener(Listener l) { listener = l; }

    private void changed() {
        main.post(() -> {
            Listener l = listener;
            if (l != null) l.onTrakt();
        });
    }

    boolean connected() { return !sp.getString("refresh", "").isEmpty(); }

    /** Resolves a TMDB show id to Trakt for watched marks. */
    void resolveTmdb(int tmdbId, java.util.function.Consumer<Integer> done) {
        if (!connected() || tmdbId <= 0) { main.post(() -> done.accept(0)); return; }
        episodesNet.execute(() -> {
            int id = 0;
            try {
                JSONArray results = new JSONArray(get("/search/tmdb/" + tmdbId + "?type=show"));
                if (results.length() > 0) {
                    JSONObject show = results.getJSONObject(0).optJSONObject("show");
                    JSONObject ids = show == null ? null : show.optJSONObject("ids");
                    if (ids != null) id = ids.optInt("trakt");
                }
            } catch (Exception e) {
                AppLog.w("Trakt TMDB lookup: " + e.getMessage());
            }
            int answer = id;
            main.post(() -> done.accept(answer));
        });
    }

    /** The sign-in ran out (Trakt refused to renew it): the person has to connect again. */
    boolean needsReconnect() { return sp.getBoolean("reconnect", false) && !connected(); }

    /** The rows for the home screen: none until connected. */
    List<Catalogs.Row> rows() { return connected() ? rows : new ArrayList<Catalogs.Row>(); }

    // MARK: - Signing in

    int loginState() { return loginState; }
    int loginError() { return loginError; }
    String loginCode() { return loginCode; }
    String loginUrl() { return loginUrl; }

    long loginSecondsLeft() {
        return Math.max(0, (loginEndsAt - SystemClock.elapsedRealtime() + 999) / 1000);
    }

    /** Asks Trakt for a code and waits for the person to enter it (the state says how far it is). */
    synchronized void startLogin() {
        if (!configured() || loginState != IDLE) return;
        loginState = STARTING;
        loginError = ERR_NONE;
        final int ticket = ++loginTicket;
        loginThread = new Thread(() -> login(ticket), "trakt-login");
        loginThread.setDaemon(true);
        loginThread.start();
    }

    synchronized void cancelLogin() {
        loginTicket++;
        loginState = IDLE;
        if (loginThread != null) loginThread.interrupt();
    }

    private void login(int ticket) {
        try {
            Reply r = call("POST", "/oauth/device/code", obj("client_id", Prefs.traktClientId()), null);
            if (r.code != 200) {
                endLogin(ticket, r.code == 401 || r.code == 403 || r.code == 404 ? ERR_REFUSED : ERR_NETWORK);
                return;
            }
            JSONObject o = new JSONObject(r.body);
            String device = o.getString("device_code");
            int interval = Math.max(5, o.optInt("interval", 5));
            if (ticket != loginTicket) return;
            loginCode = o.getString("user_code");
            loginUrl = o.optString("verification_url", "trakt.tv/activate").replaceFirst("^https?://", "").replaceFirst("/$", "");
            loginEndsAt = SystemClock.elapsedRealtime() + o.optLong("expires_in", 600) * 1000;
            loginState = WAITING;
            int failures = 0;
            while (ticket == loginTicket && SystemClock.elapsedRealtime() < loginEndsAt) {
                Thread.sleep(interval * 1000L);
                if (ticket != loginTicket) return;
                try {
                    Reply t = call("POST", "/oauth/device/token",
                            obj("code", device, "client_id", Prefs.traktClientId()), null);
                    failures = 0;
                    if (t.code == 200) {
                        saveTokens(new JSONObject(t.body));
                        checked = 0;
                        endLogin(ticket, ERR_NONE);
                        refreshIfStale();
                        changed();
                        return;
                    }
                    if (t.code == 400) continue;                        // not entered yet
                    if (t.code == 429) { interval += 2; continue; }     // too fast
                    endLogin(ticket, t.code == 418 ? ERR_DENIED : t.code == 410 || t.code == 404 || t.code == 409 ? ERR_EXPIRED : ERR_REFUSED);
                    return;
                } catch (IOException e) {
                    if (++failures >= 5) {
                        endLogin(ticket, ERR_NETWORK);
                        return;
                    }
                }
            }
            endLogin(ticket, ERR_EXPIRED);
        } catch (InterruptedException e) {
            // cancelled
        } catch (Exception e) {
            AppLog.w("trakt login: " + e.getMessage());
            endLogin(ticket, ERR_NETWORK);
        }
    }

    private synchronized void endLogin(int ticket, int error) {
        if (ticket != loginTicket) return;
        loginState = IDLE;
        loginError = error;
        loginCode = "";
    }

    /** Forgets the sign-in and the lists (nothing changes on the Trakt account). */
    void disconnect() {
        cancelLogin();
        sp.edit().clear().putInt("version", VERSION).putInt("rowsVersion", ROWS_VERSION).apply();
        File[] saved = new File(context.getCacheDir(), "trakt-episodes").listFiles();     // watched marks are theirs: gone too
        if (saved != null) for (File f : saved) f.delete();
        rows = new ArrayList<>();
        checked = 0;
        failedAt = 0;
        changed();
    }

    private void saveTokens(JSONObject o) {
        long now = System.currentTimeMillis() / 1000;
        sp.edit().putString("access", o.optString("access_token")).putString("refresh", o.optString("refresh_token"))
                .putLong("expires", now + o.optLong("expires_in", 7 * 24 * 3600L)).putBoolean("reconnect", false).apply();
    }

    /** A valid access token, renewed first when it has under an hour left (a refresh token works once, so the new pair is saved at once). */
    private synchronized String token() throws IOException {
        if (!connected()) throw new IOException("not connected");
        long now = System.currentTimeMillis() / 1000;
        if (now < sp.getLong("expires", 0) - 3600) return sp.getString("access", "");
        Reply r = call("POST", "/oauth/token", obj("refresh_token", sp.getString("refresh", ""),
                "client_id", Prefs.traktClientId(), "grant_type", "refresh_token"), null);
        if (r.code == 200) {
            try {
                saveTokens(new JSONObject(r.body));
            } catch (Exception e) {
                throw new IOException("unreadable sign-in");
            }
            return sp.getString("access", "");
        }
        if (r.code == 400 || r.code == 401 || r.code == 403) {
            sp.edit().remove("access").remove("refresh").putBoolean("reconnect", true).apply();
            rows = new ArrayList<>();
            changed();
            throw new IOException("Trakt ended the sign-in");
        }
        throw new IOException("Trakt " + r.code);
    }

    // MARK: - The lists

    /** Looks for changes when the last look is over half an hour old (one request); fetches the rows only if something changed. */
    void refreshIfStale() {
        if (!configured() || !connected() || fetching) return;
        long now = System.currentTimeMillis();
        if (failedAt != 0 && now >= failedAt && now - failedAt < RETRY_AFTER_FAILURE) return;
        if (now >= checked && now - checked < CHECK_EVERY) return;
        fetching = true;
        net.execute(() -> {
            boolean changed = false;
            try {
                JSONObject activity = new JSONObject(get("/sync/last_activities"));
                String stamp = stamp(activity);
                if (!stamp.equals(sp.getString("stamp", "")) || sp.getString("rows", null) == null) {
                    List<Catalogs.Row> next = new ArrayList<>();
                    Row continuing = continueWatching();
                    if (!continuing.items.isEmpty()) next.add(continuing);
                    Row shows = showsRow();
                    if (!shows.items.isEmpty()) next.add(shows);
                    Row watchlist = watchlist();
                    if (!watchlist.items.isEmpty()) next.add(watchlist);
                    rows = next;
                    sp.edit().putString("rows", encode(next)).putString("stamp", stamp).apply();
                    changed = true;
                }
                checked = System.currentTimeMillis();
                failedAt = 0;
            } catch (Exception e) {
                failedAt = System.currentTimeMillis();
                AppLog.w("trakt: " + e.getMessage());
            } finally {
                fetching = false;
                if (changed) changed();
            }
        });
    }

    /** What changes when the lists do: the times of the last play, pause and watchlist change. */
    private static String stamp(JSONObject a) {
        JSONObject movies = a.optJSONObject("movies"), episodes = a.optJSONObject("episodes"), watchlist = a.optJSONObject("watchlist");
        return (movies == null ? "" : movies.optString("watched_at") + movies.optString("paused_at"))
                + "|" + (episodes == null ? "" : episodes.optString("watched_at") + episodes.optString("paused_at"))
                + "|" + (watchlist == null ? "" : watchlist.optString("updated_at"));
    }

    private static final class Candidate {
        String time = "";
        String key;
        Catalogs.Item item;
    }

    /** Stopped part-way (most recent first), then the next episode of each show being followed, newest activity first. */
    private Row continueWatching() throws Exception {
        List<Candidate> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        JSONArray playback = new JSONArray(get("/sync/playback?extended=full&limit=" + FETCH));
        for (int i = 0; i < playback.length(); i++) {
            JSONObject o = playback.optJSONObject(i);
            if (o == null) continue;
            boolean episode = "episode".equals(o.optString("type"));
            JSONObject media = o.optJSONObject(episode ? "show" : "movie");
            Catalogs.Item it = item(media, episode ? "tv" : "movie");
            if (it == null) continue;
            JSONObject ep = o.optJSONObject("episode");
            if (episode && ep != null) it.sub = label(ep);
            it.progress = (float) (o.optDouble("progress", 0) / 100);
            Candidate c = new Candidate();
            c.time = text(o, "paused_at");
            c.key = (episode ? "s" : "m") + it.imdb + it.id;
            c.item = it;
            if (seen.add(c.key)) all.add(c);
        }
        JSONArray upNext = new JSONArray(get("/sync/progress/up_next?extended=full&limit=" + FETCH));
        for (int i = 0; i < upNext.length(); i++) {
            JSONObject o = upNext.optJSONObject(i);
            if (o == null) continue;
            JSONObject progress = o.optJSONObject("progress");
            JSONObject next = progress == null ? null : progress.optJSONObject("next_episode");
            Catalogs.Item it = item(o.optJSONObject("show"), "tv");
            // Trakt also lists watchlisted shows that were never started (no last_watched_at): those belong to the Watchlist row.
            if (it == null || next == null || text(progress, "last_watched_at").isEmpty()) continue;
            it.sub = label(next);
            Candidate c = new Candidate();
            c.time = text(progress, "last_watched_at");
            c.key = "s" + it.imdb + it.id;
            c.item = it;
            if (seen.add(c.key)) all.add(c);
        }
        Collections.sort(all, (a, b) -> b.time.compareTo(a.time));       // ISO times in one format: text order is time order
        Row row = new Row();
        row.key = CONTINUE;
        row.title = Ui.tr("Continue Watching");
        for (int i = 0; i < all.size() && row.items.size() < CONTINUE_MAX; i++) {
            all.get(i).item.at = time(all.get(i).time);
            row.items.add(all.get(i).item);
        }
        return row;
    }

    /**
     * The shows you watch, the one watched most recently first. Trakt answers with every show ever watched; a show
     * seen once, more than a year ago, was a try and is left out. OK on one opens its episodes.
     */
    private Row showsRow() throws Exception {
        // "noseasons" leaves out every episode played; this list never has posters (its shows are saved without them).
        JSONArray list = new JSONArray(get("/sync/watched/shows?extended=noseasons"));
        List<Candidate> all = new ArrayList<>();
        String cutoff = iso(System.currentTimeMillis() - YEAR);
        List<JSONObject> shows = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            JSONObject show = o == null ? null : o.optJSONObject("show");
            String last = o == null ? "" : text(o, "last_watched_at");
            if (show == null || last.isEmpty() || (o.optInt("plays") <= 1 && last.compareTo(cutoff) < 0)) continue;
            Candidate c = new Candidate();
            c.time = last;
            c.key = String.valueOf(shows.size());
            all.add(c);
            shows.add(show);
        }
        Collections.sort(all, (a, b) -> b.time.compareTo(a.time));
        Row row = new Row();
        row.key = SHOWS;
        row.title = Ui.tr("Shows you watch");
        for (int i = 0; i < all.size() && row.items.size() < SHOWS_MAX; i++) {
            JSONObject show = shows.get(Integer.parseInt(all.get(i).key));
            JSONObject ids = show.optJSONObject("ids");
            int id = ids == null ? 0 : ids.optInt("trakt");
            if (id == 0) continue;
            Catalogs.Item it = item(show, "tv", showPoster(id));
            if (it == null) continue;
            it.episodes = true;
            try { it.at = java.time.Instant.parse(all.get(i).time).toEpochMilli(); } catch (Exception ignored) {}
            row.items.add(it);
        }
        return row;
    }

    /** A show's poster, from what was saved or one request (a show's poster hardly ever changes, so it is kept); null if it has none or Trakt didn't answer. */
    private String showPoster(int id) {
        String saved = sp.getString("poster" + id, null);
        if (saved != null) return saved.isEmpty() ? null : saved;
        try {
            String poster = poster(new JSONObject(get("/shows/" + id + "?extended=full")).optJSONObject("images"));
            sp.edit().putString("poster" + id, poster == null ? "" : poster).apply();
            return poster;
        } catch (Exception e) {
            AppLog.w("show poster: " + e.getMessage());
            return null;
        }
    }

    private static String iso(long ms) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return f.format(new java.util.Date(ms));
    }

    private Row watchlist() throws Exception {
        JSONArray list = new JSONArray(get("/sync/watchlist/movies,shows/added/desc?extended=full&limit=" + WATCHLIST_MAX));
        Row row = new Row();
        row.key = WATCHLIST;
        row.title = Ui.tr("Watchlist");
        for (int i = 0; i < list.length() && row.items.size() < WATCHLIST_MAX; i++) {
            JSONObject o = list.optJSONObject(i);
            if (o == null) continue;
            boolean show = "show".equals(o.optString("type"));
            Catalogs.Item it = item(o.optJSONObject(show ? "show" : "movie"), show ? "tv" : "movie");
            if (it != null) {
                if (show) it.episodes = true;      // a show on the watchlist: its episodes, not "where does this stream"
                row.items.add(it);
            }
        }
        return row;
    }

    /** A string field, "" when it is missing or null (org.json would read a null as the text "null"). */
    private static String text(JSONObject o, String key) {
        return o.isNull(key) ? "" : o.optString(key);
    }

    /** An ISO time from Trakt as milliseconds, 0 when it can't be read. */
    private static long time(String iso) {
        try {
            return java.time.Instant.parse(iso).toEpochMilli();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static String label(JSONObject episode) {
        return String.format(Ui.locale(), "S%d · E%d", episode.optInt("season"), episode.optInt("number"));
    }

    /** A card from a Trakt movie or show; null when it has no poster or no id to open it by. */
    private static Catalogs.Item item(JSONObject media, String kind) {
        return media == null ? null : item(media, kind, poster(media.optJSONObject("images")));
    }

    private static Catalogs.Item item(JSONObject media, String kind, String poster) {
        if (media == null) return null;
        JSONObject ids = media.optJSONObject("ids");
        if (ids == null || poster == null) return null;
        Catalogs.Item it = new Catalogs.Item();
        it.id = ids.optInt("tmdb");
        it.traktId = ids.optInt("trakt");
        it.imdb = ids.optString("imdb");
        if (!it.imdb.startsWith("tt")) it.imdb = null;
        if (it.id == 0 && it.imdb == null) return null;
        it.kind = kind;
        it.title = media.optString("title");
        it.poster = "trakt-" + poster;
        it.posterUrl = poster.startsWith("http") ? poster : "https://" + poster;
        return it.title.isEmpty() ? null : it;
    }

    /** Trakt's poster address (a list, or a single one; no "https://" in front), or null. */
    private static String poster(JSONObject images) {
        if (images == null) return null;
        Object p = images.opt("poster");
        if (p instanceof JSONArray) p = ((JSONArray) p).opt(0);
        return p instanceof String && !((String) p).isEmpty() ? (String) p : null;
    }

    // MARK: - The episodes of one show

    interface EpisodesListener {
        /**
         * On the main thread, up to twice: the saved copy (`fresh` false), then Trakt's (`fresh` true). `episodes` is
         * null when there is nothing to show and Trakt couldn't be reached.
         */
        void onEpisodes(List<EpisodeList.Episode> episodes, boolean fresh);
    }

    /** Every episode of the show with your watched marks. Each look at a show asks Trakt again; the saved copy only shows first. */
    void episodes(int traktId, EpisodesListener l) {
        episodesNet.execute(() -> {
            File dir = new File(context.getCacheDir(), "trakt-episodes");
            File f = new File(dir, traktId + ".json");
            List<EpisodeList.Episode> saved = null;
            try {
                if (f.exists()) saved = readEpisodes(f);
            } catch (Exception e) {
                AppLog.w("saved episodes: " + e.getMessage());
            }
            final List<EpisodeList.Episode> first = saved;
            if (first != null) main.post(() -> l.onEpisodes(first, false));
            try {
                List<EpisodeList.Episode> fresh = fetchEpisodes(traktId);
                writeEpisodes(dir, f, fresh);
                main.post(() -> l.onEpisodes(fresh, true));
            } catch (Exception e) {
                AppLog.w("episodes: " + e.getMessage());
                if (first == null) main.post(() -> l.onEpisodes(null, true));
            }
        });
    }

    private List<EpisodeList.Episode> fetchEpisodes(int id) throws Exception {
        JSONArray seasons = new JSONArray(get("/shows/" + id + "/seasons?extended=episodes,full"));
        Set<Integer> seen = new HashSet<>();
        JSONObject progress = new JSONObject(get("/shows/" + id + "/progress/watched?specials=true"));
        JSONArray done = progress.optJSONArray("seasons");
        for (int i = 0; done != null && i < done.length(); i++) {
            JSONObject s = done.optJSONObject(i);
            JSONArray es = s == null ? null : s.optJSONArray("episodes");
            for (int j = 0; es != null && j < es.length(); j++) {
                JSONObject e = es.optJSONObject(j);
                if (e != null && e.optBoolean("completed")) seen.add(s.optInt("number") * 100_000 + e.optInt("number"));
            }
        }
        java.text.SimpleDateFormat date = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        date.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        List<EpisodeList.Episode> out = new ArrayList<>();
        for (int i = 0; i < seasons.length(); i++) {
            JSONObject s = seasons.optJSONObject(i);
            JSONArray es = s == null ? null : s.optJSONArray("episodes");
            for (int j = 0; es != null && j < es.length(); j++) {
                JSONObject e = es.optJSONObject(j);
                if (e == null) continue;
                EpisodeList.Episode ep = new EpisodeList.Episode();
                ep.season = e.optInt("season", s.optInt("number"));
                ep.number = e.optInt("number");
                ep.title = text(e, "title");
                String aired = text(e, "first_aired");
                try {
                    ep.aired = aired.isEmpty() ? 0 : date.parse(aired).getTime();
                } catch (java.text.ParseException ignored) {
                }
                ep.watched = seen.contains(EpisodeList.key(ep));
                out.add(ep);
            }
        }
        return out;
    }

    private static void writeEpisodes(File dir, File f, List<EpisodeList.Episode> list) throws Exception {
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        JSONArray a = new JSONArray();
        for (EpisodeList.Episode e : list) {
            a.put(new JSONArray().put(e.season).put(e.number).put(e.title).put(e.aired).put(e.watched ? 1 : 0));
        }
        File part = new File(dir, f.getName() + ".part");
        try (FileOutputStream out = new FileOutputStream(part)) {
            out.write(a.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!part.renameTo(f)) throw new IOException("couldn't save the episodes");
        File[] all = dir.listFiles();       // the newest 30 shows stay
        if (all != null && all.length > 30) {
            java.util.Arrays.sort(all, (x, y) -> Long.compare(x.lastModified(), y.lastModified()));
            for (int i = 0; i < all.length - 30; i++) {
                //noinspection ResultOfMethodCallIgnored
                all[i].delete();
            }
        }
    }

    private static List<EpisodeList.Episode> readEpisodes(File f) throws Exception {
        byte[] bytes = new byte[(int) f.length()];
        try (FileInputStream in = new FileInputStream(f)) {
            int at = 0, n;
            while (at < bytes.length && (n = in.read(bytes, at, bytes.length - at)) > 0) at += n;
        }
        JSONArray a = new JSONArray(new String(bytes, StandardCharsets.UTF_8));
        List<EpisodeList.Episode> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONArray r = a.optJSONArray(i);
            if (r == null) continue;
            EpisodeList.Episode e = new EpisodeList.Episode();
            e.season = r.optInt(0);
            e.number = r.optInt(1);
            e.title = r.optString(2);
            e.aired = r.optLong(3);
            e.watched = r.optInt(4) == 1;
            out.add(e);
        }
        return out;
    }

    // MARK: - Saved rows

    private static String encode(List<Catalogs.Row> list) throws Exception {
        JSONArray a = new JSONArray();
        for (Catalogs.Row row : list) {
            JSONArray items = new JSONArray();
            for (Catalogs.Item it : row.items) {
                items.put(obj("id", it.id, "kind", it.kind, "title", it.title, "poster", it.poster, "url", it.posterUrl,
                        "imdb", it.imdb == null ? "" : it.imdb, "sub", it.sub == null ? "" : it.sub, "progress", it.progress,
                        "tid", it.traktId, "ep", it.episodes, "at", it.at));
            }
            a.put(obj("key", row.key, "items", items));
        }
        return a.toString();
    }

    private static List<Catalogs.Row> parse(JSONArray a) {
        List<Catalogs.Row> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Row row = new Row();
            row.key = o.optString("key");
            row.title = row.key.equals(CONTINUE) ? Ui.tr("Continue Watching") : row.key.equals(SHOWS) ? Ui.tr("Shows you watch")
                    : row.key.equals(WATCHLIST) ? Ui.tr("Watchlist") : null;
            JSONArray items = o.optJSONArray("items");
            for (int j = 0; row.title != null && items != null && j < items.length(); j++) {
                JSONObject p = items.optJSONObject(j);
                if (p == null) continue;
                Catalogs.Item it = new Catalogs.Item();
                it.id = p.optInt("id");
                it.kind = p.optString("kind");
                it.title = p.optString("title");
                it.poster = p.optString("poster");
                it.posterUrl = p.optString("url");
                it.imdb = p.optString("imdb").isEmpty() ? null : p.optString("imdb");
                it.sub = p.optString("sub").isEmpty() ? null : p.optString("sub");
                it.progress = (float) p.optDouble("progress", 0);
                it.traktId = p.optInt("tid");
                it.episodes = p.optBoolean("ep");
                it.at = p.optLong("at");
                row.items.add(it);
            }
            if (!row.items.isEmpty()) out.add(row);
        }
        return out;
    }

    // MARK: - HTTP

    private static final class Reply {
        int code;
        String body = "";
    }

    private static JSONObject obj(Object... pairs) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i < pairs.length; i += 2) o.put((String) pairs[i], pairs[i + 1]);
        } catch (org.json.JSONException ignored) {
        }
        return o;
    }

    /** An authorised GET; a 401 renews the access token once and tries again. */
    private String get(String path) throws IOException {
        for (int attempt = 0; ; attempt++) {
            Reply r = call("GET", path, null, token());
            if (r.code == 200) return r.body;
            if (r.code == 401 && attempt == 0) {
                sp.edit().putLong("expires", 0).apply();     // the next token() renews it
                continue;
            }
            throw new IOException("Trakt " + r.code + " for " + path.replaceAll("\\?.*", ""));
        }
    }

    private static Reply call(String method, String path, JSONObject body, String token) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(API + path).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(8000);
            c.setReadTimeout(20000);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("trakt-api-version", "2");
            c.setRequestProperty("trakt-api-key", Prefs.traktClientId());
            c.setRequestProperty("User-Agent", "SkyHomeLauncher");
            if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
            if (body != null) {
                c.setDoOutput(true);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            Reply r = new Reply();
            r.code = c.getResponseCode();
            InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in != null) {
                try (InputStream stream = in) {
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    byte[] chunk = new byte[8192];
                    int n;
                    while ((n = stream.read(chunk)) > 0) buffer.write(chunk, 0, n);
                    r.body = buffer.toString("UTF-8");
                }
            }
            return r;
        } finally {
            c.disconnect();
        }
    }
}
