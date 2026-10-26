package com.skyhome.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Where the TV is, for the weather and the sunset, when the person hasn't chosen a city: the place its internet address
 * belongs to (the city of its provider, which can be a nearby one), asked of a free service without a key once in 12
 * hours and kept. A TV has no GPS to ask. Sky Home Launcher > Home Settings > City chooses one by hand instead.
 */
final class Locator {
    private Locator() {}

    private static final long EVERY = 12 * 3600_000L, RETRY = 10 * 60_000L;
    private static final String[] SERVICES = {"https://ipwho.is/", "https://ipapi.co/json/"};
    private static volatile long failedAt;

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("location", Context.MODE_PRIVATE);
    }

    /** {city, latitude, longitude} as last found, or null before the first answer. */
    static String[] cached(Context c) {
        if (c == null) return null;
        SharedPreferences sp = sp(c);
        String lat = sp.getString("lat", ""), lon = sp.getString("lon", "");
        return lat.isEmpty() || lon.isEmpty() ? null : new String[] {sp.getString("city", ""), lat, lon};
    }

    /** The network is back: the next refresh() tries at once instead of waiting out the pause after a failure. */
    static void retryNow() {
        failedAt = 0;
    }

    /** Asks again when the last answer is over 12 hours old. Blocks on the network: call it from a background thread. */
    static void refresh(Context c) {
        SharedPreferences sp = sp(c);
        long now = System.currentTimeMillis(), at = sp.getLong("at", 0);
        if (cached(c) != null && now >= at && now - at < EVERY) return;
        if (now >= failedAt && now - failedAt < RETRY) return;
        for (String url : SERVICES) {
            try {
                JSONObject o = new JSONObject(get(url));
                if (o.has("success") && !o.optBoolean("success")) continue;
                double lat = o.getDouble("latitude"), lon = o.getDouble("longitude");
                sp.edit().putString("city", o.optString("city", "")).putString("lat", String.valueOf(lat))
                        .putString("lon", String.valueOf(lon)).putLong("at", now).apply();
                failedAt = 0;
                return;
            } catch (Exception e) {
                AppLog.w("location: " + e.getMessage());
            }
        }
        failedAt = now;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", "SkyHomeLauncher");
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        try {
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode() + " from " + url);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            c.disconnect();
        }
    }
}
