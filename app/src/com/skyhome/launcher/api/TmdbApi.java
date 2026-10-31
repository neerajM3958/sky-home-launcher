package com.skyhome.launcher.api;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;

/** The Movie Database's API (v3). Blocking: background threads only. */
public final class TmdbApi {
    private static final String API = "https://api.themoviedb.org/3";

    private TmdbApi() {}

    /** A request with the person's key (empty: none); `path` starts with "/" and may carry a query. */
    public static JSONObject get(String key, String path) throws IOException, JSONException {
        if (key.isEmpty()) throw new IOException("no TMDB key");
        boolean bearer = key.startsWith("eyJ");   // read access token (v4) or API key (v3)
        String url = API + path + (bearer ? "" : (path.contains("?") ? "&" : "?") + "api_key=" + URLEncoder.encode(key, "UTF-8"));
        Http http = Http.to(url).timeouts(8000, 8000).label("TMDB").header("Accept", "application/json");
        if (bearer) http.header("Authorization", "Bearer " + key);
        try {
            return new JSONObject(http.get());
        } catch (Http.Failure e) {
            if (e.code == 401) throw new IOException("TMDB rejected the key");
            throw e;
        }
    }
}
