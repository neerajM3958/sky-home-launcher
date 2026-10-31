package com.skyhome.launcher.api;

import java.io.IOException;
import java.net.URLEncoder;

/** Open-Meteo's forecast and city search (free, no key). Blocking: background threads only. */
public final class OpenMeteoApi {
    private static final String QUERY = "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min,sunrise,sunset"
            + "&hourly=temperature_2m,weather_code,is_day&timezone=auto&forecast_days=2";

    private OpenMeteoApi() {}

    /** The forecast's JSON text for a place. */
    public static String forecast(String latitude, String longitude) throws IOException {
        return get("https://api.open-meteo.com/v1/forecast?latitude=" + latitude + "&longitude=" + longitude + QUERY);
    }

    /** The JSON text of the places with this name; `language` has no country (Open-Meteo takes "pt", not "pt-BR"). */
    public static String search(String name, String language) throws IOException {
        return get("https://geocoding-api.open-meteo.com/v1/search?count=10&format=json&language=" + language
                + "&name=" + URLEncoder.encode(name.trim(), "UTF-8"));
    }

    private static String get(String url) throws IOException {
        return Http.to(url).timeouts(6000, 6000).get();
    }
}
