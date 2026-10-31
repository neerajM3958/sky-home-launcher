package com.skyhome.launcher.api;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

/** Where the TV's internet address is, from free services without a key. Blocking: background threads only. */
public final class GeoIpApi {
    private static final String[] SERVICES = {"https://ipwho.is/", "https://ipapi.co/json/"};

    private GeoIpApi() {}

    /** {city, latitude, longitude} from the first service that answers; throws the last failure if none does. */
    public static String[] lookup() throws IOException {
        IOException failed = new IOException("no service answered");
        for (String url : SERVICES) {
            try {
                JSONObject o = new JSONObject(Http.to(url).timeouts(8000, 10000).get());
                if (o.has("success") && !o.optBoolean("success")) continue;
                return new String[] {o.optString("city", ""), String.valueOf(o.getDouble("latitude")), String.valueOf(o.getDouble("longitude"))};
            } catch (IOException e) {
                failed = e;
            } catch (JSONException e) {
                failed = new IOException("unreadable answer from " + url);
            }
        }
        throw failed;
    }
}
