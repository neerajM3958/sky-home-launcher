package com.skyhome.launcher;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The link that adds the Torrentio addon to Stremio, with the languages and the size limit the person chose (Settings >
 * Stremio > Torrentio addon). Torrentio is a third-party addon: Sky Home Launcher only hands Stremio its install link, and
 * Stremio asks the person to confirm. The options are Torrentio's own (its configure page, torrentio.strem.fun/configure):
 * `language=a,b` and `sizefilter=5GB`, joined with `|` (written %7C), then `/manifest.json`.
 */
final class Torrentio {
    private Torrentio() {}

    /** Torrentio's language names, each with the language it is (a tag, to write its name in the TV's language). English is Torrentio's default: not listed. */
    static final String[][] LANGUAGES = {
        {"japanese", "ja"}, {"russian", "ru"}, {"italian", "it"}, {"portuguese", "pt"}, {"spanish", "es"}, {"latino", "es-419"},
        {"korean", "ko"}, {"chinese", "zh"}, {"taiwanese", "zh-TW"}, {"french", "fr"}, {"german", "de"}, {"dutch", "nl"},
        {"hindi", "hi"}, {"telugu", "te"}, {"tamil", "ta"}, {"polish", "pl"}, {"lithuanian", "lt"}, {"latvian", "lv"},
        {"estonian", "et"}, {"czech", "cs"}, {"slovakian", "sk"}, {"slovenian", "sl"}, {"hungarian", "hu"}, {"romanian", "ro"},
        {"bulgarian", "bg"}, {"serbian", "sr"}, {"croatian", "hr"}, {"ukrainian", "uk"}, {"greek", "el"}, {"danish", "da"},
        {"finnish", "fi"}, {"swedish", "sv"}, {"norwegian", "nb"}, {"turkish", "tr"}, {"arabic", "ar"}, {"persian", "fa"},
        {"hebrew", "he"}, {"vietnamese", "vi"}, {"indonesian", "id"}, {"malay", "ms"}, {"thai", "th"},
    };

    /** The biggest size of a stream, in Torrentio's words ("" is no limit). */
    static final String[] SIZES = {"", "1GB", "2GB", "3GB", "5GB", "10GB", "15GB", "20GB", "30GB"};
    static final String DEFAULT_SIZE = "5GB";

    /** The language's name for the person (in the TV's language), like "Hindi". */
    static String label(String name) {
        for (String[] l : LANGUAGES) {
            if (l[0].equals(name)) {
                Locale locale = Locale.forLanguageTag(l[1]);
                String shown = locale.getDisplayName(Ui.locale());
                return shown.isEmpty() ? name : shown.substring(0, 1).toUpperCase(Ui.locale()) + shown.substring(1);
            }
        }
        return name;
    }

    /** What to start with: the languages of the TMDB section (two-letter codes, "hi", "pt-BR"...), in Torrentio's names. English has none. */
    static List<String> fromTmdb(List<String> codes) {
        List<String> out = new ArrayList<>();
        for (String code : codes) {
            String tag = code.replace('_', '-').toLowerCase(Locale.ROOT);
            String base = tag.contains("-") ? tag.substring(0, tag.indexOf('-')) : tag;
            String found = null;
            for (String[] l : LANGUAGES) {
                if (l[1].toLowerCase(Locale.ROOT).equals(tag)) found = l[0];                     // an exact one first ("zh-tw", "es-419")
            }
            for (String[] l : LANGUAGES) {
                if (found == null && l[1].toLowerCase(Locale.ROOT).equals(base)) found = l[0];
            }
            if (found != null && !out.contains(found)) out.add(found);
        }
        return out;
    }

    /** "5 GB", or "No limit". */
    static String sizeLabel(String size) {
        if (size.isEmpty()) return Ui.tr("No limit");
        return Ui.trf("{n} GB", "n", size.replace("GB", ""));
    }

    static String link(List<String> languages, String size) {
        List<String> options = new ArrayList<>();
        if (!languages.isEmpty()) options.add("language=" + String.join(",", languages));
        if (!size.isEmpty()) options.add("sizefilter=" + size);
        return "stremio://torrentio.strem.fun/" + (options.isEmpty() ? "" : String.join("%7C", options) + "/") + "manifest.json";
    }

    /** Opens the link in Stremio, which asks the person to add the addon. False when Stremio can't take it. */
    static boolean install(Context c, List<String> languages, String size) {
        try {
            c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link(languages, size))).setPackage(Routing.STREMIO)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (RuntimeException e) {
            AppLog.w("torrentio: " + e.getMessage());
            return false;
        }
    }
}
