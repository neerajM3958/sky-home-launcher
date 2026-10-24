package io.freemytv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The countries to pick the region from (Free My TV > TMDB > Region). The codes are the ones TMDB has streaming data for
 * (fetched now and then, kept for 30 days), else Android's own list of countries; the names come from Android in the
 * TV's language, so no translations are needed.
 */
final class Regions {
    private Regions() {}

    static final class Entry {
        final String code, name;

        Entry(String code, String name) {
            this.code = code;
            this.name = name;
        }
    }

    private static final long EVERY = 30L * 24 * 3600_000L;
    private static final ExecutorService NET = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "regions");
        t.setDaemon(true);
        return t;
    });

    private static File file(Context c) {
        return new File(c.getFilesDir(), "regions.json");
    }

    /** A country's name in the TV's language; the code itself when Android doesn't know it. */
    static String name(String code) {
        String n = new Locale("", code).getDisplayCountry(Ui.locale());
        return n.isEmpty() ? code : n;
    }

    /** The countries, by name. Instant: from the file when there is one, else Android's list. */
    static List<Entry> list(Context c) {
        List<String> codes = saved(c);
        if (codes.isEmpty()) codes = new ArrayList<>(java.util.Arrays.asList(Locale.getISOCountries()));
        List<Entry> out = new ArrayList<>();
        for (String code : codes) out.add(new Entry(code, name(code)));
        final Collator collator = Collator.getInstance(Ui.locale());
        Collections.sort(out, (a, b) -> collator.compare(a.name, b.name));
        return out;
    }

    private static List<String> saved(Context c) {
        List<String> out = new ArrayList<>();
        File f = file(c);
        if (!f.exists()) return out;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] bytes = new byte[(int) f.length()];
            int at = 0, n;
            while (at < bytes.length && (n = in.read(bytes, at, bytes.length - at)) > 0) at += n;
            JSONArray a = new JSONObject(new String(bytes, StandardCharsets.UTF_8)).getJSONArray("codes");
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception e) {
            out.clear();
        }
        return out;
    }

    /** Asks TMDB for its list when the one kept is over 30 days old (in the background; the next look at the list uses it). */
    static void refreshIfStale(Context c) {
        final Context app = c.getApplicationContext();
        File f = file(app);
        long now = System.currentTimeMillis();
        if (f.exists() && now >= f.lastModified() && now - f.lastModified() < EVERY) return;
        if (!Recs.hasKey(app)) return;
        NET.execute(() -> {
            try {
                JSONArray results = Recs.get(app).get("/watch/providers/regions?language=en-US").getJSONArray("results");
                JSONArray codes = new JSONArray();
                for (int i = 0; i < results.length(); i++) {
                    String code = results.getJSONObject(i).optString("iso_3166_1");
                    if (code.length() == 2) codes.put(code);
                }
                if (codes.length() == 0) return;
                try (FileOutputStream out = new FileOutputStream(f)) {
                    out.write(new JSONObject().put("codes", codes).toString().getBytes(StandardCharsets.UTF_8));
                }
            } catch (Exception e) {
                AppLog.w("regions: " + e.getMessage());
            }
        });
    }
}
