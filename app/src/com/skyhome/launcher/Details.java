package com.skyhome.launcher;

import android.content.Context;
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
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * What the browse screen shows about the focused title: description, genres, runtime, rating, its backdrop and its
 * trailers. One TMDB request per title (kept for a week), one backdrop picture; both load in the
 * background and the listeners are told on the main thread.
 */
final class Details {
    static final class Info {
        String tagline = "", overview = "", genres = "", facts = "", backdrop = "", trailer = "", trailers = "";      // trailers: the YouTube ids worth trying, best first, comma apart; trailer: the first
        long at;
    }

    interface Listener { void onDetails(); }

    private static final long FRESH_MS = 7 * 24 * 3600_000L;
    private static final int VERSION = 3, KEEP = 200, TRAILERS = 4;
    private static final String IMAGES = "https://image.tmdb.org/t/p/" + (Backdrop.W <= 1280 ? "w780" : "w1280");

    private static Details instance;

    static synchronized Details get(Context c) {
        if (instance == null) instance = new Details(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "details");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService images = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "backdrops");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Info> infos = new LinkedHashMap<String, Info>(32, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Info> eldest) {
            return size() > 40;
        }
    };
    private final Map<String, Bitmap> backdrops = new LinkedHashMap<String, Bitmap>(4, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
            return size() > 3;
        }
    };
    private final Set<String> asking = new HashSet<>(), loading = new HashSet<>();
    private final List<Listener> listeners = new ArrayList<>();

    private Details(Context c) {
        context = c;
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    private static String key(String kind, int id) {
        return kind + id + "-" + Recs.lang() + "-" + Recs.region();
    }

    /** What is known about the title now, or null; it is fetched in the background (and the listeners told) when it is not. */
    Info info(String kind, int id) {
        if (id <= 0 || !Recs.hasKey(context)) return null;
        String key = key(kind, id);
        Info known;
        synchronized (infos) {
            known = infos.get(key);
            if (known != null && System.currentTimeMillis() - known.at < FRESH_MS) return known;
            if (!asking.add(key)) return known;
        }
        net.execute(() -> {
            try {
                File f = new File(new File(context.getCacheDir(), "details"), key + ".json");
                Info got = read(f);
                if (got == null || System.currentTimeMillis() - got.at >= FRESH_MS) {
                    try {
                        got = fetch(kind, id);
                        write(f, got);
                    } catch (Exception e) {
                        if (got == null) throw e;       // a week-old answer beats none
                    }
                }
                synchronized (infos) { infos.put(key, got); }
                notifyListeners();
            } catch (Exception e) {
                AppLog.w("details: " + e.getMessage());
            } finally {
                synchronized (infos) { asking.remove(key); }
            }
        });
        return known;
    }

    private Info fetch(String kind, int id) throws Exception {
        JSONObject o = Recs.get(context).get("/" + kind + "/" + id + "?language=" + Recs.lang() + "&append_to_response=videos&include_video_language="
                + Ui.language.replaceAll("-.*", "") + ",en,null");
        Info info = new Info();
        info.at = System.currentTimeMillis();
        info.tagline = o.optString("tagline");
        info.overview = o.optString("overview");
        info.backdrop = o.optString("backdrop_path");
        List<String> genres = new ArrayList<>();
        JSONArray list = o.optJSONArray("genres");
        for (int i = 0; list != null && i < list.length() && i < 3; i++) genres.add(list.getJSONObject(i).optString("name"));
        info.genres = String.join(" · ", genres);
        List<String> facts = new ArrayList<>();
        String date = o.optString(kind.equals("movie") ? "release_date" : "first_air_date");
        if (date.length() >= 4) facts.add(date.substring(0, 4));
        if (kind.equals("movie")) {
            int minutes = o.optInt("runtime");
            if (minutes > 0) facts.add(minutes >= 60 ? Ui.trf("{h}h {m}m", "h", minutes / 60, "m", minutes % 60) : Ui.trf("{m}m", "m", minutes));
        } else {
            int seasons = o.optInt("number_of_seasons");
            if (seasons > 0) facts.add(Ui.plural("plural|one:{n} season|other:{n} seasons", seasons));
        }
        double rating = o.optDouble("vote_average", 0);
        if (rating > 0 && o.optInt("vote_count") >= 10) facts.add(String.format(Ui.locale(), "★ %.1f", rating));
        info.facts = String.join(" · ", facts);
        info.trailers = trailers(o.optJSONObject("videos"));
        info.trailer = info.trailers.split(",")[0];
        return info;
    }

    /**
     * The YouTube ids of the trailers worth trying, best first, up to TRAILERS, comma apart ("" when there is none): in the TV's
     * language first, official first, trailers before teasers. A trailer can be blocked in the region or for embedding, so the
     * player tries the next one before it gives up.
     */
    private static String trailers(JSONObject videos) {
        JSONArray list = videos == null ? null : videos.optJSONArray("results");
        String language = Ui.language.replaceAll("-.*", "");
        List<String[]> found = new ArrayList<>();       // {score, key}
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject v = list.optJSONObject(i);
            if (v == null || !"YouTube".equals(v.optString("site")) || v.optString("key").isEmpty()) continue;
            int score = "Trailer".equals(v.optString("type")) ? 10 : "Teaser".equals(v.optString("type")) ? 1 : 0;
            if (score == 0) continue;
            if (language.equals(v.optString("iso_639_1"))) score += 4;
            if (v.optBoolean("official")) score += 2;
            boolean seen = false;
            for (String[] f : found) seen |= f[1].equals(v.optString("key"));
            if (!seen) found.add(new String[] {String.valueOf(100 - score), v.optString("key")});      // 100 - score: sorts best first as text of the same length
        }
        java.util.Collections.sort(found, (x, y) -> Integer.compare(Integer.parseInt(x[0]), Integer.parseInt(y[0])));
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < found.size() && i < TRAILERS; i++) keys.add(found.get(i)[1]);
        return String.join(",", keys);
    }

    /** The backdrop picture of the title (decoded, kept for the last few), or null while it loads (the listeners are told). */
    Bitmap backdrop(Info info) {
        if (info == null || info.backdrop.isEmpty()) return null;
        String path = info.backdrop;
        synchronized (backdrops) {
            Bitmap b = backdrops.get(path);
            if (b != null) return b;
            if (!loading.add(path)) return null;
        }
        images.execute(() -> {
            try {
                File f = new File(new File(context.getCacheDir(), "details"), "b" + path.replace("/", "_"));
                if (!f.exists()) download(IMAGES + path, f);
                Bitmap b = BitmapFactory.decodeFile(f.getPath());
                if (b == null) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                    throw new IOException("unreadable backdrop");
                }
                synchronized (backdrops) { backdrops.put(path, b); }
                notifyListeners();
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                AppLog.w("backdrop: " + e.getMessage());
            } finally {
                synchronized (backdrops) { loading.remove(path); }
            }
        });
        return null;
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onDetails(); });
    }

    private static void download(String url, File f) throws IOException {
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        File part = new File(f.getPath() + ".part");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", "SkyHomeLauncher");
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
        if (!part.renameTo(f)) throw new IOException("couldn't save the backdrop");
        File[] all = f.getParentFile().listFiles();         // the newest KEEP stay
        if (all != null && all.length > KEEP) {
            java.util.Arrays.sort(all, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (int i = 0; i < all.length - KEEP; i++) {
                //noinspection ResultOfMethodCallIgnored
                all[i].delete();
            }
        }
    }

    private static Info read(File f) {
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] bytes = new byte[(int) f.length()];
            int at = 0, n;
            while (at < bytes.length && (n = in.read(bytes, at, bytes.length - at)) > 0) at += n;
            JSONObject o = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (o.optInt("v") != VERSION) return null;
            Info info = new Info();
            info.at = o.optLong("at");
            info.tagline = o.optString("tagline");
            info.overview = o.optString("overview");
            info.genres = o.optString("genres");
            info.facts = o.optString("facts");
            info.backdrop = o.optString("backdrop");
            info.trailer = o.optString("trailer");
            info.trailers = o.optString("trailers");
            return info;
        } catch (Exception e) {
            return null;
        }
    }

    private static void write(File f, Info info) {
        try {
            String text = new JSONObject().put("v", VERSION).put("at", info.at).put("tagline", info.tagline)
                    .put("overview", info.overview).put("genres", info.genres).put("facts", info.facts)
                    .put("backdrop", info.backdrop).put("trailer", info.trailer).put("trailers", info.trailers).toString();
            //noinspection ResultOfMethodCallIgnored
            f.getParentFile().mkdirs();
            File part = new File(f.getPath() + ".part");
            try (FileOutputStream out = new FileOutputStream(part)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
            if (!part.renameTo(f)) throw new IOException("couldn't save");
        } catch (Exception e) {
            AppLog.w("couldn't keep the details: " + e.getMessage());
        }
    }
}
