package com.skyhome.launcher.api;

import java.io.IOException;
import java.net.URLEncoder;

/** Watchmode's API: the address of a title's own page in a streaming app. Blocking: background threads only. */
public final class WatchmodeApi {
    private static final String API = "https://api.watchmode.com/v1";

    private WatchmodeApi() {}

    /** The answer's JSON text; a refused key is a `Http.Failure` with code 401. */
    public static String get(String key, String path) throws IOException {
        String url = API + path + (path.contains("?") ? "&" : "?") + "apiKey=" + URLEncoder.encode(key, "UTF-8");
        return Http.to(url).timeouts(8000, 10000).label("Watchmode").get();
    }
}
