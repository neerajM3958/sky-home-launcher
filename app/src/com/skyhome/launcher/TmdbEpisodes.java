package com.skyhome.launcher;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** TMDB episode lists for Watch Next shows without a Trakt id. */
final class TmdbEpisodes {
    interface Listener {
        void onEpisodes(List<EpisodeList.Episode> episodes, boolean fresh);
    }

    private static final long FRESH = 4 * 3600_000L;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tmdb-episodes");
        t.setDaemon(true);
        return t;
    });

    TmdbEpisodes(Context c) {
        context = c.getApplicationContext();
    }

    void load(int id, Listener listener) {
        net.execute(() -> {
            File f = new File(new File(context.getCacheDir(), "tmdb-episodes"), id + ".json");
            List<EpisodeList.Episode> saved = read(f);
            if (saved != null) main.post(() -> listener.onEpisodes(saved, false));
            long now = System.currentTimeMillis();
            if (saved != null && now >= f.lastModified() && now - f.lastModified() < FRESH) return;
            try {
                List<EpisodeList.Episode> fresh = fetch(id);
                write(f, fresh);
                main.post(() -> listener.onEpisodes(fresh, true));
            } catch (Exception e) {
                AppLog.w("TMDB episodes: " + e.getMessage());
                if (saved == null) main.post(() -> listener.onEpisodes(null, true));
            }
        });
    }

    private List<EpisodeList.Episode> fetch(int id) throws Exception {
        Recs recs = Recs.get(context);
        JSONObject show = recs.get("/tv/" + id + "?language=" + Recs.lang());
        int seasons = show.optInt("number_of_seasons");
        int count = seasons + 1;        // Season 0 contains specials.
        List<EpisodeList.Episode> out = new ArrayList<>();
        SimpleDateFormat date = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        date.setTimeZone(TimeZone.getTimeZone("UTC"));
        for (int start = 0; start < count; start += 20) {
            int end = Math.min(count, start + 20);
            StringBuilder path = new StringBuilder("/tv/").append(id).append("?language=").append(Recs.lang())
                    .append("&append_to_response=");
            for (int season = start; season < end; season++) {
                if (season > start) path.append(',');
                path.append("season/").append(season);
            }
            JSONObject response = recs.get(path.toString());
            for (int seasonNumber = start; seasonNumber < end; seasonNumber++) {
                JSONObject season = response.optJSONObject("season/" + seasonNumber);
                JSONArray episodes = season == null ? null : season.optJSONArray("episodes");
                for (int i = 0; episodes != null && i < episodes.length(); i++) {
                    JSONObject object = episodes.optJSONObject(i);
                    if (object == null) continue;
                    EpisodeList.Episode episode = new EpisodeList.Episode();
                    episode.season = object.optInt("season_number", seasonNumber);
                    episode.number = object.optInt("episode_number");
                    episode.title = object.optString("name", "");
                    String airDate = object.optString("air_date", "");
                    try {
                        if (!airDate.isEmpty()) episode.aired = date.parse(airDate).getTime();
                    } catch (java.text.ParseException ignored) {
                    }
                    out.add(episode);
                }
            }
        }
        return out;
    }

    private static List<EpisodeList.Episode> read(File file) {
        try {
            if (!file.exists()) return null;
            byte[] bytes = new byte[(int) file.length()];
            try (FileInputStream in = new FileInputStream(file)) {
                int at = 0, count;
                while (at < bytes.length && (count = in.read(bytes, at, bytes.length - at)) > 0) at += count;
            }
            JSONArray array = new JSONArray(new String(bytes, StandardCharsets.UTF_8));
            List<EpisodeList.Episode> out = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONArray row = array.optJSONArray(i);
                if (row == null) continue;
                EpisodeList.Episode episode = new EpisodeList.Episode();
                episode.season = row.optInt(0);
                episode.number = row.optInt(1);
                episode.title = row.optString(2);
                episode.aired = row.optLong(3);
                out.add(episode);
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private static void write(File file, List<EpisodeList.Episode> episodes) {
        try {
            File dir = file.getParentFile();
            if (dir != null) dir.mkdirs();
            JSONArray array = new JSONArray();
            for (EpisodeList.Episode episode : episodes) {
                array.put(new JSONArray().put(episode.season).put(episode.number).put(episode.title).put(episode.aired));
            }
            File part = new File(file.getPath() + ".part");
            try (FileOutputStream out = new FileOutputStream(part)) {
                out.write(array.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!part.renameTo(file)) throw new java.io.IOException("couldn't save episodes");
            File[] files = dir == null ? null : dir.listFiles();
            if (files != null && files.length > 30) {
                Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
                for (int i = 0; i < files.length - 30; i++) {
                    //noinspection ResultOfMethodCallIgnored
                    files[i].delete();
                }
            }
        } catch (Exception e) {
            AppLog.w("save TMDB episodes: " + e.getMessage());
        }
    }
}
