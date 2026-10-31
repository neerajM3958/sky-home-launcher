package com.skyhome.launcher;

import com.skyhome.launcher.api.GeoIpApi;
import android.content.Context;
import android.content.SharedPreferences;

import java.io.IOException;


/**
 * Where the TV is, for the weather and the sunset, when the person hasn't chosen a city: the place its internet address
 * belongs to (the city of its provider, which can be a nearby one), asked of a free service without a key once in 12
 * hours and kept. A TV has no GPS to ask. Sky Home Launcher > Home Settings > City chooses one by hand instead.
 */
final class Locator {
    private Locator() {}

    private static final long EVERY = 12 * 3600_000L, RETRY = 10 * 60_000L;
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
        try {
            String[] place = GeoIpApi.lookup();
            sp.edit().putString("city", place[0]).putString("lat", place[1]).putString("lon", place[2]).putLong("at", now).apply();
            failedAt = 0;
            return;
        } catch (IOException e) {
            AppLog.w("location: " + e.getMessage());
        }
        failedAt = now;
    }
}
