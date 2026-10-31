package com.skyhome.launcher;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.List;

/**
 * Page 3: Trakt's and the TV's rows (Continue Watching, Shows you watch, Watchlist), one under another, each a node of
 * the page's column. ← → along a row, ↑ ↓ between rows: the focused row rises with each press, but the last row's foot
 * never leaves the bottom of the screen. The page is as tall as its rows (one to three screens).
 */
final class HomeWatchingPage extends HomePage {
    /** One row's title to the next one's, and how far below its top a row's last caption ends. */
    static final float PITCH = 440, ROW_FOOT = 400;
    /** The first row's top on the page, the same as page 4's video window. */
    private static final float TOP = 60;
    /** Where the last row's foot may rise to: the screen's bottom, 40 short. */
    private static final float BOTTOM = 1040;

    private final Catalogs catalogs;
    private List<Catalogs.Row> source;
    private List<PosterRow> list = new ArrayList<>();
    /** How far the rows have scrolled, in rows, and the focus popping onto a poster. */
    private final Spring scroll = new Spring(0.42f, 0.92f, 0), focus = new Spring(0.3f, 0.82f, 0);
    private final List<Node> rowNodes = new ArrayList<>();
    private Paint rowTitle;
    private final RectF cell = new RectF();

    HomeWatchingPage(HomeView home, Catalogs catalogs) {
        super(home, "watching");
        this.catalogs = catalogs;
    }

    @Override boolean isEmpty() {
        return list.isEmpty();
    }

    @Override float height() {
        return Math.max(1, Math.min(3, (float) Math.ceil((TOP + list.size() * PITCH) / HomeGrid.H)));
    }

    // MARK: - Layout

    @Override protected void makePaints() {
        rowTitle = Ui.text(home.look.typeface(Ui.semibold), 32 * k, 0xFF1D1D1F);
    }

    /** A column of rows, PITCH apart, between the side margins and from TOP down. */
    @Override protected Node build() {
        rowNodes.clear();
        Node[] items = new Node[list.size() + 1];
        items[0] = Node.space(0, TOP * k);
        for (int i = 0; i < list.size(); i++) {
            final int r = i;
            items[i + 1] = Node.of(list.get(i).row.title, (c, f) -> drawRow(c, r, f)).size(Node.FILL, PITCH * k);
            rowNodes.add(items[i + 1]);
        }
        Node rows = Stack.column("rows", 0, items).flex();
        return Stack.row("watching", 0, Node.space(HomeGrid.MARGIN * k, 0), rows, Node.space(HomeGrid.MARGIN * k, 0));
    }

    // MARK: - Rows

    /** Takes the catalog's current rows (a new list after a fetch), keeping the focus on the same title when it can. */
    void sync() {
        List<Catalogs.Row> fresh = catalogs.rows();
        if (fresh == source) return;
        int focusedRow = PosterRow.sameRowOf(list, row, fresh);
        source = fresh;
        list = PosterRow.sync(list, fresh);
        rows.clear();
        rows.addAll(list);
        row = focusedRow;
        relayout();
        if (home.onWatchingPage()) {
            if (list.isEmpty()) home.pagesChanged();
            else scroll.target = scrollTarget();
        }
        invalidate();
    }

    /** Back to the first row, unscrolled (the page comes in, or goes). */
    void top(boolean snap) {
        row = 0;
        if (snap) scroll.snap(0);
        else scroll.target = 0;
    }

    /** From above: the first row, unscrolled. From below (Up from page 4): the last row. */
    @Override void enter(boolean fromAbove) {
        if (fromAbove) {
            top(false);
        } else {
            row = Math.max(0, list.size() - 1);
            scroll.target = scrollTarget();
        }
        popFocus();
    }

    @Override void leave() {
        scroll.target = 0;
        invalidate();
    }

    @Override protected void focusChanged(boolean rowChanged) {
        if (rowChanged) scroll.target = scrollTarget();
        popFocus();
    }

    private void popFocus() {
        focus.snap(0);
        focus.target = 1;
        invalidate();
    }

    /**
     * How far the rows scroll (in rows) with the focus on row `row`: the focused row rises with each press, but never
     * past the point where the last row's foot reaches the bottom of the screen, so a few rows don't leave it empty.
     */
    private float scrollTarget() {
        float firstTop = TOP;
        float lastFoot = firstTop + (list.size() - 1) * PITCH + ROW_FOOT;
        float most = Math.max(0, lastFoot - BOTTOM) / PITCH;
        return Math.min(row, most);
    }

    @Override boolean tick(float dt) {
        return super.tick(dt) | scroll.step(dt) | focus.step(dt);
    }

    // MARK: - The focused poster

    Catalogs.Row focusedRow() {
        return list.isEmpty() ? null : list.get(row).row;
    }

    Catalogs.Item focusedItem() {
        return list.isEmpty() ? null : list.get(row).focused();
    }

    /** Where the focused poster is on screen now; false without one. */
    boolean focusedRect(RectF out) {
        if (focusedItem() == null) return false;
        posterRect(row, list.get(row).index, out);
        return true;
    }

    /** How far the focus has popped onto the focused poster (0 to 1). */
    float focusAmount() {
        return Ui.clamp(focus.value);
    }

    /** A show you watch opens its episodes; what an app says you were watching goes back into that app; else the title. */
    @Override boolean activate() {
        Catalogs.Item it = focusedItem();
        if (it == null) return false;
        home.click.open();
        if (it.episodes) {
            home.activity.startActivity(EpisodesActivity.intent(home.activity, it));
        } else if (it.launch != null) {
            home.openWatchNext(it);
        } else {
            focusedRect(cell);
            home.openTitle(it, cell);
        }
        return true;
    }

    // MARK: - Drawing

    /** How far the rows have scrolled up the page's layer now (the pager moves the layer itself). */
    private float origin() {
        return -scroll.value * PITCH * k;
    }

    @Override void draw(Canvas c) {
        if (list.isEmpty() || rowTitle == null) return;
        rowTitle.setColor(home.look.color(Look.TITLE, home.dark()));
        home.posterColors();
        c.save();
        c.translate(0, origin());
        super.draw(c);
        c.restore();
    }

    private void drawRow(Canvas c, int r, RectF frame) {
        if (r >= list.size()) return;
        float o = origin();
        if (frame.top + o > h || frame.bottom + o < 0) return;
        PosterRow pr = list.get(r);
        c.drawText(pr.row.title, frame.left + 4 * k, frame.top + 30 * k, rowTitle);
        int first = Math.max(0, (int) Math.floor(pr.shift.value)), last = Math.min(pr.count() - 1, first + PosterRow.VISIBLE + 1);
        for (int i = first; i <= last; i++) {
            place(frame, pr, i, cell);
            if (cell.right < 0 || cell.left > w) continue;
            float f = home.onWatchingPage() && r == row && i == pr.index ? Ui.clamp(focus.value) : 0;
            home.drawPoster(c, cell, pr.row.items.get(i), f, frame.top, true);
        }
    }

    /** Poster `i` of a row whose node is `frame`, in the page's own space. */
    private void place(RectF frame, PosterRow pr, int i, RectF out) {
        float x = frame.left + (i - pr.shift.value) * (PosterRow.POSTER_W + PosterRow.GAP) * k;
        out.set(x, frame.top + 50 * k, x + PosterRow.POSTER_W * k, frame.top + (50 + PosterRow.POSTER_H) * k);
    }

    /** Where poster `i` of row `r` is on screen now. */
    private void posterRect(int r, int i, RectF out) {
        if (r >= rowNodes.size()) {     // not laid out yet
            out.setEmpty();
            return;
        }
        place(rowNodes.get(r).frame, list.get(r), i, out);
        out.offset(0, origin());
    }
}
