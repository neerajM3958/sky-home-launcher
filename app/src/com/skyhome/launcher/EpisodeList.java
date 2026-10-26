package com.skyhome.launcher;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/**
 * The episodes of one show with the sorting and filters of the episodes screen. Nothing from Android in here, so it
 * can be tested on its own.
 */
final class EpisodeList {
    static final class Episode {
        int season, number;
        String title = "";
        long aired;             // UTC milliseconds; 0 when Trakt has no date
        boolean watched;
        boolean resume;
        boolean resumePinned;
    }

    static final int NUMBER_DESC = 0, NUMBER_ASC = 1, DATE_DESC = 2, DATE_ASC = 3;
    /** Date filter: 1000 and up is that year. */
    static final int DATE_ANY = 0, LAST_7 = 1, LAST_30 = 2, THIS_YEAR = 3, NOT_AIRED = 4;
    static final int SEASON_ALL = -1;
    static final int WATCHED_ALL = 0, UNWATCHED = 1, WATCHED = 2;

    private static final long DAY = 24 * 3600_000L;

    final List<Episode> all = new ArrayList<>();
    int sort = NUMBER_DESC, season = SEASON_ALL, date = DATE_ANY, watched = WATCHED_ALL;
    private final Calendar calendar = Calendar.getInstance();

    static int key(Episode e) { return e.season * 100_000 + e.number; }

    boolean filtered() {
        return season != SEASON_ALL || date != DATE_ANY || watched != WATCHED_ALL;
    }

    void clearFilters() {
        season = SEASON_ALL;
        date = DATE_ANY;
        watched = WATCHED_ALL;
    }

    private int year(long ms) {
        calendar.setTimeInMillis(ms);
        return calendar.get(Calendar.YEAR);
    }

    /** The episodes that pass the filters, in the chosen order. */
    List<Episode> view(long now) {
        List<Episode> out = new ArrayList<>();
        int thisYear = year(now);
        for (Episode e : all) {
            if (season != SEASON_ALL && e.season != season) continue;
            if (watched == UNWATCHED && e.watched) continue;
            if (watched == WATCHED && !e.watched) continue;
            if (!matchesDate(e, now, thisYear)) continue;
            out.add(e);
        }
        Collections.sort(out, comparator());
        return out;
    }

    private boolean matchesDate(Episode e, long now, int thisYear) {
        switch (date) {
            case DATE_ANY: return true;
            case LAST_7: return e.aired > 0 && e.aired <= now && e.aired >= now - 7 * DAY;
            case LAST_30: return e.aired > 0 && e.aired <= now && e.aired >= now - 30 * DAY;
            case THIS_YEAR: return e.aired > 0 && year(e.aired) == thisYear;
            case NOT_AIRED: return e.aired > now;
            default: return e.aired > 0 && year(e.aired) == date;
        }
    }

    private static final Comparator<Episode> BY_NUMBER = (a, b) ->
            a.season != b.season ? Integer.compare(a.season, b.season) : Integer.compare(a.number, b.number);

    private Comparator<Episode> comparator() {
        switch (sort) {
            case NUMBER_ASC: return BY_NUMBER;
            case DATE_ASC: return byDate(true);
            case DATE_DESC: return byDate(false);
            default: return Collections.reverseOrder(BY_NUMBER);
        }
    }

    /** By air date; the ones with no date go last either way, and a tie falls back to the number in the same direction. */
    private static Comparator<Episode> byDate(boolean ascending) {
        return (a, b) -> {
            if (a.aired == 0 || b.aired == 0) return a.aired == b.aired ? BY_NUMBER.compare(a, b) : a.aired == 0 ? 1 : -1;
            int c = Long.compare(a.aired, b.aired);
            if (c == 0) c = BY_NUMBER.compare(a, b);
            return ascending ? c : -c;
        };
    }

    /** The season numbers, 1 up, with the specials (season 0) last. */
    List<Integer> seasons() {
        TreeSet<Integer> set = new TreeSet<>();
        for (Episode e : all) set.add(e.season);
        List<Integer> out = new ArrayList<>(set);
        if (!out.isEmpty() && out.get(0) == 0) {
            out.remove(0);
            out.add(0);
        }
        return out;
    }

    /** The years episodes aired in, newest first. */
    List<Integer> years() {
        TreeSet<Integer> set = new TreeSet<>(Collections.reverseOrder());
        for (Episode e : all) if (e.aired > 0) set.add(year(e.aired));
        return new ArrayList<>(set);
    }

    boolean hasUpcoming(long now) {
        for (Episode e : all) if (e.aired > now) return true;
        return false;
    }

    /** Aired in the last week. */
    static boolean isNew(Episode e, long now) {
        return e.aired > 0 && e.aired <= now && e.aired >= now - 7 * DAY;
    }

    /**
     * The episode to watch next: the first aired one you haven't seen after the last one you saw (the first episode if
     * you saw none). When you are up to date, the latest aired one. Specials don't count. Null with nothing aired.
     */
    Episode nextToWatch(long now) {
        Episode last = null;
        for (Episode e : all) {
            if (e.season >= 1 && e.watched && (last == null || BY_NUMBER.compare(e, last) > 0)) last = e;
        }
        Episode next = null, latest = null;
        for (Episode e : all) {
            if (e.season < 1 || e.aired == 0 || e.aired > now) continue;
            if (latest == null || BY_NUMBER.compare(e, latest) > 0) latest = e;
            if (e.watched || (last != null && BY_NUMBER.compare(e, last) <= 0)) continue;
            if (next == null || BY_NUMBER.compare(e, next) < 0) next = e;
        }
        return next != null ? next : latest;
    }
}
