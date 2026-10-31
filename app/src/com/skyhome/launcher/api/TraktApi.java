package com.skyhome.launcher.api;

import org.json.JSONObject;

import java.io.IOException;

/** Trakt's HTTP side: the address, the headers, the token. What to do with the answer (renewing the token...) is `Trakt`'s. */
public final class TraktApi {
    private static final String API = "https://api.trakt.tv";

    private TraktApi() {}

    /** One request; `token` may be null (login calls). The status is the caller's to judge. */
    public static Http.Reply call(String clientId, String method, String path, JSONObject body, String token) throws IOException {
        Http http = Http.to(API + path).timeouts(8000, 20000)
                .header("trakt-api-version", "2")
                .header("trakt-api-key", clientId);
        if (token != null) http.header("Authorization", "Bearer " + token);
        return http.send(method, body == null ? null : body.toString());
    }
}
