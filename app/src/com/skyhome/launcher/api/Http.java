package com.skyhome.launcher.api;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The one place that opens a connection: timeouts, the User-Agent, headers, reading the answer and closing it.
 * Blocking, so background threads only. The API clients (`TmdbApi`, `TraktApi`, ...) build on it.
 * Never put a URL with a key in a message: `Failure` and the logs carry the label and the code only.
 */
public final class Http {
    static final String USER_AGENT = "SkyHomeLauncher";

    /** A status other than 200 (from `get` and `download`). */
    public static final class Failure extends IOException {
        public final int code;

        Failure(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    public static final class Reply {
        public int code;
        public String body = "";
    }

    public interface Progress {
        void on(long got, long total);
    }

    /** The status code of a `Failure`, else 0. */
    public static int code(IOException e) {
        return e instanceof Failure ? ((Failure) e).code : 0;
    }

    private final String url;
    private final List<String[]> headers = new ArrayList<>();
    private int connectMs = 8000, readMs = 10000;
    private String label = "";

    private Http(String url) {
        this.url = url;
    }

    public static Http to(String url) {
        return new Http(url);
    }

    public Http timeouts(int connectMs, int readMs) {
        this.connectMs = connectMs;
        this.readMs = readMs;
        return this;
    }

    public Http header(String name, String value) {
        headers.add(new String[] {name, value});
        return this;
    }

    /** Names the service in error messages: "TMDB: HTTP 500". */
    public Http label(String label) {
        this.label = label + ": ";
        return this;
    }

    /** The body of a 200 answer; any other status throws a `Failure`. */
    public String get() throws IOException {
        HttpURLConnection c = open("GET");
        try {
            check(c);
            try (InputStream in = c.getInputStream()) {
                return read(in);
            }
        } finally {
            c.disconnect();
        }
    }

    /** Any method, with an optional JSON body; the status is the caller's to judge (the body of a 4xx is kept too). */
    public Reply send(String method, String body) throws IOException {
        HttpURLConnection c = open(method);
        try {
            if (body != null) {
                c.setRequestProperty("Content-Type", "application/json");
                c.setDoOutput(true);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            Reply r = new Reply();
            r.code = c.getResponseCode();
            InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in != null) {
                try (InputStream stream = in) {
                    r.body = read(stream);
                }
            }
            return r;
        } finally {
            c.disconnect();
        }
    }

    /** Saves a 200 answer to `to` (a .part of the caller's); a failed download leaves no file. */
    public void download(File to) throws IOException {
        download(to, null);
    }

    public void download(File to, Progress progress) throws IOException {
        HttpURLConnection c = open("GET");
        try {
            check(c);
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(to)) {
                long total = c.getContentLengthLong(), got = 0;
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                    got += n;
                    if (progress != null) progress.on(got, total);
                }
            }
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            to.delete();
            throw e;
        } finally {
            c.disconnect();
        }
    }

    public static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }

    private HttpURLConnection open(String method) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(connectMs);
        c.setReadTimeout(readMs);
        c.setRequestProperty("User-Agent", USER_AGENT);
        for (String[] h : headers) c.setRequestProperty(h[0], h[1]);
        return c;
    }

    private void check(HttpURLConnection c) throws IOException {
        int code = c.getResponseCode();
        if (code != 200) throw new Failure(code, label + "HTTP " + code);
    }
}
