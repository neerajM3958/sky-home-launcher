package com.skyhome.launcher;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * What the Quick Settings card (in the :keys process) needs to look like the home screen: the look, the light/dark
 * setting, the language and today's sunrise and sunset. SharedPreferences aren't shared between processes, so the
 * settings write them here and the card reads them each time it opens (the same way `Prefs.keyApp` reaches :keys).
 */
final class QuickLook {
    private QuickLook() {}

    /**
     * The card's tiles, in order: "settings", "wifi", "bluetooth", "inputs" (one tile per TV input), "home", "apps",
     * "notifications" (the section under the tiles) and "app:<package>" (an app the person added).
     */
    static final String DEFAULT_TILES = "settings,wifi,bluetooth,inputs,home,apps,notifications";

    String look = Config.LOOK, theme = Config.THEME, language = Config.LANGUAGE, tiles = DEFAULT_TILES;
    long sunrise, sunset;

    private static File file(Context c) {
        return new File(c.getFilesDir(), "quick-look.txt");
    }

    static QuickLook load(Context c) {
        QuickLook q = new QuickLook();
        try (BufferedReader r = new BufferedReader(new FileReader(file(c)))) {
            String line;
            while ((line = r.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String v = line.substring(eq + 1);
                switch (line.substring(0, eq)) {
                    case "look": q.look = v; break;
                    case "theme": q.theme = v; break;
                    case "language": q.language = v; break;
                    case "tiles": q.tiles = v; break;
                    case "sunrise": q.sunrise = parse(v); break;
                    case "sunset": q.sunset = parse(v); break;
                    default: break;
                }
            }
        } catch (IOException ignored) {
        }
        return q;
    }

    private static long parse(String v) {
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The settings changed (look, theme, language): written with the sunrise and sunset already in the file. */
    static void save(Context c) {
        QuickLook old = load(c);
        write(c, old.sunrise, old.sunset);
    }

    /** A new forecast. */
    static void save(Context c, long sunrise, long sunset) {
        write(c, sunrise, sunset);
    }

    private static void write(Context c, long sunrise, long sunset) {
        String text = "look=" + Prefs.look() + "\ntheme=" + Prefs.theme() + "\nlanguage=" + Prefs.language() + "\ntiles=" + Prefs.quickTiles()
                + "\nsunrise=" + sunrise + "\nsunset=" + sunset + "\n";
        File tmp = new File(c.getFilesDir(), "quick-look." + android.os.Process.myPid() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(file(c));
    }
}
