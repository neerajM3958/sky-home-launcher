package com.skyhome.launcher;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** One catalog row of posters (Trakt's, Watch Next's or TMDB's) as a `FocusRow`, and the poster measurements they share. */
final class PosterRow extends FocusRow {
    /** Posters (1920-wide units): 2:3, `GAP` apart, `VISIBLE` of them on screen at once. */
    static final float POSTER_W = 180, POSTER_H = 270, GAP = 36;
    static final int VISIBLE = 8;

    final Catalogs.Row row;

    PosterRow(Catalogs.Row row) {
        super(VISIBLE);
        this.row = row;
    }

    @Override int count() {
        return row.items.size();
    }

    Catalogs.Item focused() {
        return row.items.isEmpty() ? null : row.items.get(Math.min(index, row.items.size() - 1));
    }

    /** New rows for `rows` (a new list after a fetch), each focused on the same title as before when it is still there. */
    static List<PosterRow> sync(List<PosterRow> old, List<Catalogs.Row> rows) {
        List<Catalogs.Row> oldRows = new ArrayList<>(old.size());
        int[] oldCols = new int[old.size()];
        for (int i = 0; i < old.size(); i++) {
            oldRows.add(old.get(i).row);
            oldCols[i] = old.get(i).index;
        }
        int[] cols = sameTitles(oldRows, oldCols, rows);
        List<PosterRow> out = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            PosterRow r = new PosterRow(rows.get(i));
            r.focus(cols[i]);
            out.add(r);
        }
        return out;
    }

    /** The focused row in the new rows: the one with the same key, else the same place (within the new rows). */
    static int sameRow(List<Catalogs.Row> old, int index, List<Catalogs.Row> rows) {
        if (index < old.size()) {
            for (int i = 0; i < rows.size(); i++) if (rows.get(i).key.equals(old.get(index).key)) return i;
        }
        return Math.min(index, Math.max(0, rows.size() - 1));
    }

    /** As `sameRow`, for rows already made. */
    static int sameRowOf(List<PosterRow> old, int index, List<Catalogs.Row> rows) {
        List<Catalogs.Row> oldRows = new ArrayList<>(old.size());
        for (PosterRow r : old) oldRows.add(r.row);
        return sameRow(oldRows, index, rows);
    }

    /**
     * Each new row's focused column: on the title that had the focus in the row with the same key, when it is still
     * there; else the same column (within the new row). A row that changed never moves the focus to another title.
     */
    static int[] sameTitles(List<Catalogs.Row> old, int[] oldCols, List<Catalogs.Row> rows) {
        Map<String, String> focused = new HashMap<>();
        Map<String, Integer> column = new HashMap<>();
        for (int i = 0; i < old.size() && i < oldCols.length; i++) {
            Catalogs.Row r = old.get(i);
            if (r.items.isEmpty()) continue;
            int c = Math.min(oldCols[i], r.items.size() - 1);
            focused.put(r.key, Catalogs.hideKey(r.key, r.items.get(c)));
            column.put(r.key, c);
        }
        int[] cols = new int[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            Catalogs.Row r = rows.get(i);
            String who = focused.get(r.key);
            int c = -1;
            for (int j = 0; who != null && j < r.items.size() && c < 0; j++) if (who.equals(Catalogs.hideKey(r.key, r.items.get(j)))) c = j;
            if (c < 0) {
                Integer was = column.get(r.key);
                c = Math.min(was == null ? (i < oldCols.length ? oldCols[i] : 0) : was, Math.max(0, r.items.size() - 1));
            }
            cols[i] = c;
        }
        return cols;
    }
}
