package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.tv.TvContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the TV's other apps say you were watching: Android TV's shared Watch Next catalogue (a local database that apps
 * such as Netflix and YouTube write to), for the Continue Watching row. Only "continue" and "next" entries, the latest
 * of each show, from apps that are still on the TV and open. Each title is looked up on TMDB once (its id, to tell
 * whether Trakt already lists it, and its portrait poster); without a TMDB key an entry keeps the app's own data.
 */
final class WatchNext {
    static final String PERMISSION = "android.permission.READ_TV_LISTINGS";
    static final int ASK = 7;                   // requestPermissions' code
    static final int MAX = 12;
    private static final int CONTINUE = 0, NEXT = 1, WATCHLIST = 2, TYPE_MOVIE = 0;
    private static final long MAX_AGE = 60L * 24 * 3600_000L, NO_MATCH_FOR = 24 * 3600_000L;
    private static final String[] COLUMNS = {"title", "package_name", "intent_uri", "poster_art_uri", "thumbnail_uri",
            "last_playback_position_millis", "duration_millis", "watch_next_type", "last_engagement_time_utc_millis",
            "season_display_number", "episode_display_number", "episode_title", "type"};

    private WatchNext() {}

    static boolean permitted(Context c) {
        return c.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    /** A title as comparable text: no accents, case, punctuation or "(2019)". */
    static String normalize(String s) {
        String t = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return t.toLowerCase(Locale.ROOT).replaceAll("\\(\\d{4}\\)", " ").replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    private static final class Entry {
        String title, episodeTitle, pkg, art;
        Intent launch;
        long position, duration, at;
        int season, episode, kind, type;
        String year = "";
        boolean movie;
    }

    /** The cards for Continue Watching, newest first; empty when the permission is missing or nothing qualifies. */
    static List<Catalogs.Item> items(Context c) {
        List<Catalogs.Item> out = new ArrayList<>();
        for (Entry e : read(c, false)) {
            if (out.size() >= 2 * MAX) break;        // a few spare: some are Trakt's already
            Catalogs.Item it = new Catalogs.Item();
            it.kind = e.movie ? "movie" : "tv";
            it.title = e.title;
            it.at = e.at;
            it.launch = e.launch;
            it.appPkg = e.pkg;
            it.season = e.season; it.episode = e.episode; it.episodeTitle = e.episodeTitle; it.wnKind = e.kind;
            if (!e.movie && e.season > 0 && e.episode > 0) it.sub = String.format(Ui.locale(), "S%d · E%d", e.season, e.episode);
            else if (!e.movie && !e.episodeTitle.isEmpty()) it.sub = e.episodeTitle;
            if (e.duration > 0 && e.position > 0) it.progress = Math.min(1f, (float) e.position / e.duration);
            Match m = Recs.hasKey(c) ? lookup(c, it.kind, e.title, e.year) : null;
            if (m != null) {
                it.id = m.id;
                it.poster = m.poster;       // may be null: the app's own art then
            }
            if (it.poster == null && e.art != null) {
                it.poster = "wn-" + Integer.toHexString(e.art.hashCode());
                it.posterUrl = e.art;
            }
            if (it.poster != null) out.add(it);     // no picture at all: no card
        }
        return out;
    }

    /** TV shows for the Shows you watch row, including app watchlists. */
    static List<Catalogs.Item> showItems(Context c) {
        List<Catalogs.Item> out = new ArrayList<>();
        Map<String, Entry> latest = new HashMap<>();
        for (Entry e : read(c, true)) {
            if (e.movie || (e.type != 1 && e.type != 2 && e.type != 3)) continue;
            if (e.type == 3 && e.season <= 0 && e.episode <= 0 && e.episodeTitle.isEmpty()) continue;
            String key = normalize(e.title) + "|" + e.year;
            Entry old = latest.get(key);
            if (old == null || e.kind < old.kind || (e.kind == old.kind && e.at > old.at)) latest.put(key, e);
        }
        List<Entry> entries = new ArrayList<>(latest.values());
        Collections.sort(entries, (a,b) -> Long.compare(b.at,a.at));
        for (Entry e : entries) {
            if (out.size() >= MAX) break;
            Match m = Recs.hasKey(c) ? lookup(c, "tv", e.title, e.year) : null;
            if (m == null || m.id <= 0 || m.poster == null) continue;
            Catalogs.Item it = new Catalogs.Item(); it.kind="tv"; it.title=e.title; it.id=m.id; it.poster=m.poster;
            it.at=e.at; it.launch=e.launch; it.appPkg=e.pkg; it.episodes=true; it.season=e.season; it.episode=e.episode;
            it.episodeTitle=e.episodeTitle; it.wnKind=e.kind; out.add(it);
        }
        return out;
    }

    private static List<Entry> read(Context c, boolean includeWatchlist) {
        List<Entry> found = new ArrayList<>();
        Cursor cursor;
        try {
            cursor = c.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI, COLUMNS, null, null, null);
        } catch (RuntimeException e) {      // denied after all, or a TV without the provider
            AppLog.w("watch next: " + e.getMessage());
            return found;
        }
        if (cursor == null) return found;
        PackageManager pm = c.getPackageManager();
        long now = System.currentTimeMillis();
        Map<String, Entry> latest = new HashMap<>();
        try (Cursor q = cursor) {
            while (q.moveToNext()) {
                int kind = (int) number(q, "watch_next_type");
                if (kind != CONTINUE && kind != NEXT && !(includeWatchlist && kind == WATCHLIST)) continue;
                Entry e = new Entry();
                e.title = text(q, "title");
                java.util.regex.Matcher yearMatch=java.util.regex.Pattern.compile("\\((\\d{4})\\)\\s*$").matcher(e.title);
                e.year=yearMatch.find()?yearMatch.group(1):"";
                e.pkg = text(q, "package_name");
                if (e.title.isEmpty() || e.pkg.equals(c.getPackageName())) continue;
                e.at = number(q, "last_engagement_time_utc_millis");
                if ((includeWatchlist && e.at <= 0) || (e.at > 0 && now - e.at > MAX_AGE)) continue;
                e.kind = kind;
                e.launch = launch(pm, text(q, "intent_uri"));
                if (e.launch == null) continue;      // the app is gone, turned off, or the address means nothing
                e.art = text(q, "poster_art_uri");
                if (e.art.isEmpty()) e.art = text(q, "thumbnail_uri");
                if (e.art.isEmpty()) e.art = null;
                e.position = number(q, "last_playback_position_millis");
                e.duration = number(q, "duration_millis");
                e.season = (int) number(q, "season_display_number");
                e.episode = (int) number(q, "episode_display_number");
                e.episodeTitle = text(q, "episode_title");
                e.type = (int) number(q, "type");
                e.movie = e.type == TYPE_MOVIE;
                String key = normalize(e.title) + (e.movie ? "|m" : "|t") + (includeWatchlist ? "|" + kind : "");
                Entry old = latest.get(key);
                if (old == null || e.at > old.at) latest.put(key, e);       // one per show: the latest
            }
        } catch (RuntimeException e) {
            AppLog.w("watch next: " + e.getMessage());
        }
        found.addAll(latest.values());
        Collections.sort(found, (a, b) -> Long.compare(b.at, a.at));
        return found;
    }

    /** The intent an entry opens, or null when nothing on the TV answers it (its app was uninstalled or turned off). */
    private static Intent launch(PackageManager pm, String uri) {
        if (uri.isEmpty()) return null;
        try {
            Intent i = Intent.parseUri(uri, 0);
            i.setFlags((i.getFlags() & ~(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)) | Intent.FLAG_ACTIVITY_NEW_TASK);
            return pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY) == null ? null : i;     // as startActivity would
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }

    private static String text(Cursor q, String column) {
        int i = q.getColumnIndex(column);
        String s = i < 0 || q.isNull(i) ? null : q.getString(i);
        return s == null ? "" : s.trim();
    }

    private static long number(Cursor q, String column) {
        String s = text(q, column);
        try {
            return s.isEmpty() ? 0 : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // MARK: - TMDB (one search per title, kept)

    private static final class Match {
        final int id;
        final String poster;

        Match(int id, String poster) {
            this.id = id;
            this.poster = poster;
        }
    }

    private static Match lookup(Context c, String kind, String title, String year) {
        String key = kind + "|" + normalize(title) + "|" + year;
        SharedPreferences sp = c.getSharedPreferences("watchnext", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        String saved = sp.getString(key, null);
        if (saved != null) {
            String[] p = saved.split("\\|", 3);
            long at = p.length > 2 ? parse(p[2]) : 0;
            int id = (int) parse(p[0]);
            if (id > 0) return new Match(id, p[1].isEmpty() ? null : p[1]);
            if (now >= at && now - at < NO_MATCH_FOR) return null;       // nothing last time: not again today
        }
        try {
            JSONObject r = Recs.get(c).get("/search/" + kind + "?query=" + URLEncoder.encode(title, "UTF-8")
                    + "&language=" + Recs.lang() + "&include_adult=false");
            JSONArray list = r.optJSONArray("results");
            String want = normalize(title);
            Match found = null;
            List<JSONObject> exact = new ArrayList<>();
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject o = list.optJSONObject(i);
                if (o == null) continue;
                String field = kind.equals("movie") ? "title" : "name";
                if (want.equals(normalize(o.optString(field))) || want.equals(normalize(o.optString("original_" + field)))) exact.add(o);
            }
            if (exact.size() == 1) found = match(exact.get(0));
            else if (!exact.isEmpty() && !year.isEmpty()) {
                List<JSONObject> sameYear = new ArrayList<>();
                for (JSONObject o : exact) if (year.equals(o.optString("first_air_date", "").substring(0, Math.min(4, o.optString("first_air_date", "").length())))) sameYear.add(o);
                if (sameYear.size() == 1) found = match(sameYear.get(0));
            }
            if (sp.getAll().size() > 200) sp.edit().clear().apply();
            sp.edit().putString(key, found == null ? "0||" + now : found.id + "|" + (found.poster == null ? "" : found.poster) + "|" + now).apply();
            return found;
        } catch (Exception e) {
            AppLog.w("watch next lookup: " + e.getMessage());     // not kept: tried again next time
            return null;
        }
    }

    private static Match match(JSONObject o) {
        String poster=o.optString("poster_path", "");
        return new Match(o.optInt("id"), poster.isEmpty() || "null".equals(poster) ? null : poster);
    }

    private static long parse(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
