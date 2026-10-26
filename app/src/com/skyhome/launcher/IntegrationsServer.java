package com.skyhome.launcher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Settings > Integrations: a small HTTP server on the TV's own LAN address, up only while that screen is open, so the
 * TMDB key, Watchmode key and Trakt Client ID can be typed on a phone instead of with the remote. No library: raw
 * sockets, like Qr.java is a raw QR encoder. The address's random token is the only thing standing between a random
 * device on the network and the page; the real boundary is "LAN only, and only while the screen is open" (stop()
 * closes it). A field's real value is never sent back to the page, only whether one is already set (shown as a fixed
 * placeholder); never logged either.
 */
final class IntegrationsServer {
    private IntegrationsServer() {}

    private static final int MAX_HEADER = 8192, MAX_BODY = 8192, TIMEOUT_MS = 5000;
    // Eight bullets: never the real key, just a "something is set" placeholder the page shows and clears on focus.
    private static final String MASK = "••••••••";

    private static ServerSocket socket;

    static synchronized boolean running() {
        return socket != null && !socket.isClosed();
    }

    /** Starts the server (stopping any previous one first) and returns its address with the fresh token in it. */
    static synchronized String start() throws IOException {
        stop();
        String ip = localIp();
        if (ip == null) throw new IOException("no network address");
        ServerSocket s = new ServerSocket(0);
        socket = s;
        String token = randomToken();
        Thread t = new Thread(() -> acceptLoop(s, token), "integrations-http");
        t.setDaemon(true);
        t.start();
        return "http://" + ip + ":" + s.getLocalPort() + "/" + token;
    }

    static synchronized void stop() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
        socket = null;
    }

    private static void acceptLoop(ServerSocket s, String token) {
        while (!s.isClosed()) {
            try {
                Socket client = s.accept();
                Thread t = new Thread(() -> handle(client, token), "integrations-http-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                return;      // closed: stop quietly
            }
        }
    }

    // MARK: - One connection

    private static void handle(Socket client, String token) {
        try (Socket c = client) {
            c.setSoTimeout(TIMEOUT_MS);
            InputStream in = c.getInputStream();
            byte[] head = readUntilBlankLine(in);
            if (head == null) return;
            String[] lines = new String(head, StandardCharsets.ISO_8859_1).split("\r\n");
            String[] requestLine = lines.length > 0 ? lines[0].split(" ") : new String[0];
            if (requestLine.length < 2) return;
            String method = requestLine[0];
            String path = requestLine[1];
            int contentLength = 0;
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon > 0 && lines[i].substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                    try {
                        contentLength = Integer.parseInt(lines[i].substring(colon + 1).trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            String query = "";
            int q = path.indexOf('?');
            if (q >= 0) {
                query = path.substring(q + 1);
                path = path.substring(0, q);
            }
            OutputStream out = c.getOutputStream();
            if (!path.equals("/" + token)) {
                respond(out, 404, "Not found", "text/plain", "Not found".getBytes(StandardCharsets.UTF_8), null);
                return;
            }
            if ("POST".equalsIgnoreCase(method)) {
                byte[] body = readExactly(in, Math.min(contentLength, MAX_BODY));
                save(parseForm(new String(body, StandardCharsets.UTF_8)));
                respond(out, 302, "Found", "text/plain", new byte[0], "/" + token + "?saved=1");
            } else {
                byte[] page = page(query.contains("saved=1")).getBytes(StandardCharsets.UTF_8);
                respond(out, 200, "OK", "text/html; charset=utf-8", page, null);
            }
        } catch (IOException ignored) {
        }
    }

    private static void save(Map<String, String> form) {
        String tmdb = form.getOrDefault("tmdb_key", "").trim();
        String watchmode = form.getOrDefault("watchmode_key", "").trim();
        String trakt = form.getOrDefault("trakt_client_id", "").trim();
        if (!tmdb.isEmpty() && !tmdb.equals(MASK)) Prefs.setTmdbKey(tmdb);
        if (!watchmode.isEmpty() && !watchmode.equals(MASK)) Prefs.setWatchmodeKey(watchmode);
        if (!trakt.isEmpty() && !trakt.equals(MASK)) Prefs.setTraktClientId(trakt);
    }

    private static void respond(OutputStream out, int code, String reason, String contentType, byte[] body, String location) throws IOException {
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n");
        head.append("Content-Type: ").append(contentType).append("\r\n");
        head.append("Content-Length: ").append(body.length).append("\r\n");
        head.append("Connection: close\r\n");
        if (location != null) head.append("Location: ").append(location).append("\r\n");
        head.append("\r\n");
        out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    // MARK: - Tiny HTTP parsing (no library)

    /** Everything up to and including the blank line that ends the headers, or null if the connection closed first. */
    private static byte[] readUntilBlankLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int state = 0, b;      // counts progress through \r\n\r\n
        while (buf.size() < MAX_HEADER && (b = in.read()) != -1) {
            buf.write(b);
            switch (state) {
                case 0: state = b == '\r' ? 1 : 0; break;
                case 1: state = b == '\n' ? 2 : (b == '\r' ? 1 : 0); break;
                case 2: state = b == '\r' ? 3 : 0; break;
                default:
                    if (b == '\n') return buf.toByteArray();
                    state = b == '\r' ? 1 : 0;
            }
        }
        return buf.size() > 0 ? buf.toByteArray() : null;
    }

    private static byte[] readExactly(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        int off = 0, r;
        while (off < n && (r = in.read(b, off, n - off)) >= 0) off += r;
        return off == n ? b : Arrays.copyOf(b, off);
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> out = new HashMap<>();
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            try {
                out.put(URLDecoder.decode(k, "UTF-8"), URLDecoder.decode(v, "UTF-8"));
            } catch (UnsupportedEncodingException ignored) {
            }
        }
        return out;
    }

    // MARK: - The TV's LAN address

    private static String localIp() {
        try {
            List<InetAddress> candidates = new ArrayList<>();
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                if (!iface.isUp() || iface.isLoopback()) continue;
                Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) candidates.add(a);
                }
            }
            for (InetAddress a : candidates) if (!a.isLinkLocalAddress()) return a.getHostAddress();
            return candidates.isEmpty() ? null : candidates.get(0).getHostAddress();
        } catch (Exception e) {
            return null;
        }
    }

    private static String randomToken() {
        byte[] b = new byte[3];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xFF));
        return sb.toString();
    }

    // MARK: - The page

    private static String page(boolean saved) {
        StringBuilder h = new StringBuilder();
        h.append("<!doctype html><html><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append("<title>Sky Home Launcher</title><style>")
                .append("body{font-family:-apple-system,Roboto,Arial,sans-serif;background:#111;color:#eee;margin:0;padding:28px 20px 60px}")
                .append("h1{font-size:20px;margin:0 0 4px}")
                .append("p.lead{color:#aaa;margin:0 0 24px;font-size:14px}")
                .append("label{display:block;font-weight:600;margin-top:22px}")
                .append("p.caption{color:#999;font-size:13px;margin:4px 0}")
                .append("a.get{color:#8ab4f8;font-size:13px;text-decoration:none}")
                .append("input{width:100%;box-sizing:border-box;padding:10px;margin-top:8px;border-radius:8px;border:1px solid #444;background:#1c1c1e;color:#fff;font-size:16px}")
                .append("button{margin-top:30px;width:100%;padding:14px;border-radius:8px;border:none;background:#0a84ff;color:#fff;font-size:16px;font-weight:600}")
                .append(".ok{background:#1d3b1d;color:#8fe38f;padding:10px 14px;border-radius:8px;margin-bottom:16px;font-size:14px}")
                .append("</style></head><body>");
        if (saved) h.append("<p class=\"ok\">Saved.</p>");
        h.append("<h1>Sky Home Launcher</h1>")
                .append("<p class=\"lead\">Set your TMDB key, Watchmode key or Trakt Client ID. Leave a field as it is to keep what's already set.</p>")
                .append("<form method=\"post\">");
        field(h, "tmdb_key", "TMDB key",
                "For the trending picks on the home screen. Without it, the home screen still works, just without them.",
                "https://www.themoviedb.org/settings/api", !Prefs.tmdbKey().isEmpty());
        field(h, "watchmode_key", "Watchmode key",
                "Optional — makes a pick open the title's own page in its app instead of just the app.",
                "https://api.watchmode.com/", !Prefs.watchmodeKey().isEmpty());
        field(h, "trakt_client_id", "Trakt Client ID",
                "Optional — adds Continue Watching and Watchlist rows under the home screen's cards, from your Trakt account.",
                "https://app.trakt.tv/settings/apps/api/new", !Prefs.traktClientId().isEmpty());
        h.append("<button type=\"submit\">Save</button></form></body></html>");
        return h.toString();
    }

    private static void field(StringBuilder h, String name, String label, String caption, String link, boolean set) {
        h.append("<label for=\"").append(name).append("\">").append(label).append("</label>")
                .append("<p class=\"caption\">").append(caption).append(" <a class=\"get\" href=\"").append(link)
                .append("\" target=\"_blank\" rel=\"noopener\">Get one →</a></p>")
                .append("<input id=\"").append(name).append("\" name=\"").append(name).append("\" type=\"text\" autocomplete=\"off\" ")
                .append("value=\"").append(set ? MASK : "").append("\" onfocus=\"if(this.value=='").append(MASK).append("')this.value=''\">");
    }
}
