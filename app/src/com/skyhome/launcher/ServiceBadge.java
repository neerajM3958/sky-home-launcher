package com.skyhome.launcher;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Drawable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The small round icon on a card's corner: the app the title opens in. Built once per app and size on a background
 * thread (loading an app icon is slow), then drawn as a plain bitmap.
 */
final class ServiceBadge {
    private ServiceBadge() {}

    private static final Map<String, Bitmap> BADGES = new HashMap<>();      // package + size → ready badge
    private static final Set<String> BUILDING = new HashSet<>();
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "badges");
        t.setDaemon(true);
        return t;
    });

    /** The badge of `pkg` at `px` pixels, or null while it is being built (`ready` runs on the pool's thread when it is). */
    static Bitmap get(Context c, String pkg, int px, Runnable ready) {
        if (pkg == null || pkg.isEmpty()) return null;
        String key = pkg + "@" + px;
        synchronized (BADGES) {
            Bitmap b = BADGES.get(key);
            if (b != null || !BUILDING.add(key)) return b;
        }
        Context app = c.getApplicationContext();
        POOL.execute(() -> {
            Bitmap made = build(app, pkg, px);
            synchronized (BADGES) {
                BUILDING.remove(key);
                if (made != null) BADGES.put(key, made);
            }
            if (made != null) ready.run();
        });
        return null;
    }

    /** TMDB's logos are square pictures; `pkg` starting with "/" is one of their paths, anything else an app's package. */
    private static Bitmap build(Context c, String pkg, int px) {
        try {
            Drawable icon = pkg.startsWith("/") ? logo(c, pkg) : c.getPackageManager().getApplicationIcon(pkg);
            if (icon == null) return null;
            Bitmap out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(0xB3000000);
            canvas.drawCircle(px / 2f, px / 2f, px / 2f, paint);
            int inset = Math.max(1, Math.round(px * 0.08f));
            Path round = new Path();
            round.addCircle(px / 2f, px / 2f, px / 2f - inset, Path.Direction.CW);
            canvas.clipPath(round);
            icon.setBounds(inset, inset, px - inset, px - inset);
            icon.draw(canvas);
            return out;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return null;
        }
    }

    /** A service's logo from TMDB, kept in the cache folder. */
    private static Drawable logo(Context c, String path) {
        try {
            File f = new File(new File(c.getCacheDir(), "badges"), path.replace("/", "_"));
            if (!f.exists()) {
                //noinspection ResultOfMethodCallIgnored
                f.getParentFile().mkdirs();
                HttpURLConnection h = (HttpURLConnection) new URL("https://image.tmdb.org/t/p/w92" + path).openConnection();
                h.setConnectTimeout(8000);
                h.setReadTimeout(8000);
                File part = new File(f.getPath() + ".part");
                try (InputStream in = h.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                } finally {
                    h.disconnect();
                }
                if (!part.renameTo(f)) return null;
            }
            Bitmap b = BitmapFactory.decodeFile(f.getPath());
            return b == null ? null : new android.graphics.drawable.BitmapDrawable(c.getResources(), b);
        } catch (Exception e) {
            return null;
        }
    }
}
