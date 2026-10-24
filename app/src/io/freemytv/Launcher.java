package io.freemytv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * What happens when OK is pressed on a title: its own streaming app, that app's Play Store page when it isn't on the
 * TV, the catch-all app (service_map's "*", Stremio in practice) or a message. Where a title streams comes from TMDB
 * (one request, kept for a day); the decision itself is Routing's. The answer comes back on the main thread, at once
 * if it was kept and otherwise after the request, or after 3 seconds as "couldn't check".
 */
final class Launcher {
    interface Done { void onTarget(Routing.Target target); }

    /** An open that is waiting for its answer: cancel() makes it never call back. */
    static final class Handle {
        private final AtomicBoolean finished = new AtomicBoolean();
        private Runnable giveUp;
        private Handler main;

        private boolean finish() {
            if (!finished.compareAndSet(false, true)) return false;
            if (giveUp != null) main.removeCallbacks(giveUp);
            return true;
        }

        void cancel() { finish(); }
    }

    private static final long GIVE_UP_MS = 3000, FRESH_MS = 24 * 3600_000L;
    private static final int KEEP = 400;
    /** Providers TMDB lists for renting or buying that the picks never use (so Recs.SERVICES doesn't have them). */
    private static final String[][] EXTRA = {
        {"google play", "gplay", "com.google.android.videos"},
        {"youtube", "youtube", "com.google.android.youtube.tv"},
        {"amazon video", "prime", "com.amazon.amazonvideo.livingroom"},
    };

    private static Launcher instance;

    static synchronized Launcher get(Context c) {
        if (instance == null) instance = new Launcher(c.getApplicationContext());
        return instance;
    }

    private static final class Info {
        long at;
        String imdb = "";
        final List<Routing.Offer> offers = new ArrayList<>();
    }

    private final Context context;
    private final Recs recs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "launcher");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Info> memory = new LinkedHashMap<String, Info>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Info> eldest) {
            return size() > 120;
        }
    };

    private final Routing.Env env = new Routing.Env() {
        @Override public String[] service(String name) {
            String[] row = recs.serviceRow(name);
            if (row != null) return row;
            String n = name.toLowerCase(Locale.ROOT);
            for (String[] extra : EXTRA) if (n.contains(extra[0])) return extra;
            return null;
        }
        @Override public boolean installed(String pkg) { return recs.appInstalled(pkg); }
        @Override public String override(String name) { return recs.serviceOverride(name); }
        @Override public String catchAll() { return recs.catchAllApp(); }
        @Override public boolean hidden(String name) { return recs.hiddenService(name); }
    };

    private Launcher(Context c) {
        context = c;
        recs = Recs.get(c);
    }

    /** The app's name, for messages. */
    String appName(String pkg) {
        return recs.appName(pkg);
    }

    private static String describe(Routing.Target t) {
        switch (t.kind) {
            case Routing.APP: return t.pkg + (t.uri == null ? "" : " on the title's page");
            case Routing.STORE: return "the Play Store page of " + t.pkg;
            default: return "nothing (" + (t.reason == Routing.UNAVAILABLE ? "unavailable in the region"
                    : t.reason == Routing.UNKNOWN_SERVICE ? "it streams on " + t.service + ", which we can't open" : "couldn't check") + ")";
        }
    }

    /** Words for a target that opens nothing. */
    static String message(Routing.Target t) {
        switch (t.reason) {
            case Routing.UNAVAILABLE: return Ui.tr("This title is unavailable in your region.");
            case Routing.UNKNOWN_SERVICE: return Ui.trf("It streams on {service}, which Free My TV can't open.", "service", t.service);
            default: return Ui.tr("Couldn't check where this streams.");
        }
    }

    /**
     * Decides where the title opens. `kind` is TMDB's "movie" or "tv", `imdb` the IMDb id when the caller has it
     * (Stremio opens the title's page with it). `done` is called on the main thread, once, unless the handle is cancelled.
     */
    Handle open(String kind, int tmdbId, String imdb, Done answer) {
        Done done = t -> {
            AppLog.w("opens " + kind + " " + tmdbId + ": " + describe(t));
            answer.onTarget(t);
        };
        Handle h = new Handle();
        h.main = main;
        h.giveUp = () -> {
            if (h.finish()) done.onTarget(target(null, kind, imdb));
        };
        main.postDelayed(h.giveUp, GIVE_UP_MS);
        // Every service goes to the "*" app (service_map with nothing but "*"): nothing to look up, only Stremio's page
        // needs the IMDb id.
        String star = recs.catchAllApp();
        boolean everythingThere = star != null && !recs.hasNamedRules();
        boolean needsLookup = !everythingThere || (Recs.STREMIO.equals(star) && (imdb == null || imdb.isEmpty()));
        net.execute(() -> {
            Info info = null;
            if (needsLookup && tmdbId > 0) {
                try {
                    info = info(kind, tmdbId);
                } catch (Exception e) {
                    AppLog.w("where it streams: " + e.getMessage());
                }
            }
            final Info found = info;
            main.post(() -> {
                if (!h.finish()) return;
                done.onTarget(everythingThere ? withPage(Routing.app(star), kind, imdb, found) : target(found, kind, imdb));
            });
        });
        return h;
    }

    /** Looks it up ahead of time (the episodes screen does, for its show), so the press is instant. */
    void prefetch(String kind, int tmdbId) {
        if (tmdbId <= 0 || !Recs.hasKey(context)) return;
        net.execute(() -> {
            try {
                info(kind, tmdbId);
            } catch (Exception e) {
                AppLog.w("where it streams: " + e.getMessage());
            }
        });
    }

    private Routing.Target target(Info info, String kind, String imdb) {
        return withPage(Routing.decide(info == null ? null : info.offers, env), kind, imdb, info);
    }

    /** Stremio opens the title's own page from its IMDb id. */
    private static Routing.Target withPage(Routing.Target t, String kind, String imdb, Info info) {
        if (t.kind != Routing.APP || !Recs.STREMIO.equals(t.pkg)) return t;
        String id = imdb != null && !imdb.isEmpty() ? imdb : info != null && !info.imdb.isEmpty() ? info.imdb : null;
        return id == null ? t : t.withUri(Recs.stremioLink(kind, id));
    }

    // MARK: - Where a title streams (TMDB, kept for a day)

    private Info info(String kind, int id) throws Exception {
        String key = kind + id + "-" + Recs.REGION;
        long now = System.currentTimeMillis();
        Info saved;
        synchronized (memory) {
            saved = memory.get(key);
        }
        File f = new File(new File(context.getCacheDir(), "launcher"), key + ".json");
        if (saved == null) {
            saved = read(f);
            if (saved != null) synchronized (memory) { memory.put(key, saved); }
        }
        if (saved != null && now >= saved.at && now - saved.at < FRESH_MS) return saved;
        try {
            Info fresh = fetch(kind, id);
            write(f, fresh);
            synchronized (memory) { memory.put(key, fresh); }
            return fresh;
        } catch (Exception e) {
            if (saved != null) return saved;        // a day-old answer beats none
            throw e;
        }
    }

    private Info fetch(String kind, int id) throws Exception {
        JSONObject o = recs.get("/" + kind + "/" + id + "?append_to_response=watch/providers,external_ids");
        Info info = new Info();
        info.at = System.currentTimeMillis();
        String imdb = o.optString("imdb_id");
        JSONObject ids = o.optJSONObject("external_ids");
        if (!imdb.startsWith("tt") && ids != null) imdb = ids.optString("imdb_id");
        info.imdb = imdb.startsWith("tt") ? imdb : "";
        JSONObject wp = o.optJSONObject("watch/providers");
        JSONObject results = wp == null ? null : wp.optJSONObject("results");
        JSONObject region = results == null ? null : results.optJSONObject(Recs.REGION);
        String[] types = {"flatrate", "free", "ads", "rent", "buy"};
        int[] tiers = {0, 1, 1, 2, 2};
        for (int t = 0; region != null && t < types.length; t++) {
            JSONArray list = region.optJSONArray(types[t]);
            for (int i = 0; list != null && i < list.length(); i++) {
                String name = list.getJSONObject(i).optString("provider_name");
                boolean seen = false;
                for (Routing.Offer other : info.offers) seen |= other.name.equals(name);
                if (!name.isEmpty() && !seen) info.offers.add(new Routing.Offer(name, tiers[t]));
            }
        }
        return info;
    }

    private static Info read(File f) {
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] bytes = new byte[(int) f.length()];
            int at = 0, n;
            while (at < bytes.length && (n = in.read(bytes, at, bytes.length - at)) > 0) at += n;
            JSONObject o = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            Info info = new Info();
            info.at = o.optLong("at");
            info.imdb = o.optString("imdb");
            JSONArray list = o.optJSONArray("o");
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONArray p = list.optJSONArray(i);
                if (p != null) info.offers.add(new Routing.Offer(p.optString(0), p.optInt(1)));
            }
            return info;
        } catch (Exception e) {
            return null;
        }
    }

    private static void write(File f, Info info) {
        try {
            JSONArray list = new JSONArray();
            for (Routing.Offer o : info.offers) list.put(new JSONArray().put(o.name).put(o.tier));
            String text = new JSONObject().put("at", info.at).put("imdb", info.imdb).put("o", list).toString();
            //noinspection ResultOfMethodCallIgnored
            f.getParentFile().mkdirs();
            File part = new File(f.getPath() + ".part");
            try (FileOutputStream out = new FileOutputStream(part)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
            if (!part.renameTo(f)) throw new IOException("couldn't save");
            File[] all = f.getParentFile().listFiles();         // the newest KEEP stay
            if (all != null && all.length > KEEP) {
                java.util.Arrays.sort(all, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
                for (int i = 0; i < all.length - KEEP; i++) {
                    //noinspection ResultOfMethodCallIgnored
                    all[i].delete();
                }
            }
        } catch (Exception e) {
            AppLog.w("couldn't keep where a title streams: " + e.getMessage());
        }
    }
}
