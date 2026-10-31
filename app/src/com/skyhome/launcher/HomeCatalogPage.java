package com.skyhome.launcher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Page 4: TMDB's catalogs. The focused title's backdrop in a video window (where its trailer plays) with its details
 * beside it, and one catalog row below; ↑ ↓ change the catalog (the rows slide), ← → the title. Up from the first row
 * leaves the page.
 */
final class HomeCatalogPage extends HomePage {
    /** How long the focus stays on a title before TMDB is asked about it. */
    static final long REST_MS = 150;
    private static final float PITCH = 460;     // one catalog row to the next, as they slide
    private static final long AUTO_TRAILER_MS = 3000;

    private final Catalogs catalogs;
    private final Details details;
    private List<Catalogs.Row> source;
    private List<PosterRow> list = new ArrayList<>();
    private final Spring pos = new Spring(0.42f, 0.92f, 0), focus = new Spring(0.3f, 0.82f, 0), swap = new Spring(0.5f, 0.95f, 1);

    private Node stage, text, service, posters;
    private TextPaint titlePaint, overviewPaint;
    private Paint factsPaint, rowTitle;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint spin = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF(), cell = new RectF(), pill = new RectF();
    private final Path playArrow = new Path();

    // The focused title: what is known about it, its backdrop (and the one it replaces, cross-fading), its text.
    private Catalogs.Item shown;
    private Details.Info info;
    private Catalogs.Item rested;       // the title the focus has rested on (its picture may be asked for)
    private Bitmap back, prev;
    private StaticLayout titleText, overviewText;
    private String facts = "";
    private int textGen = -1;
    private final Runnable askDetails = this::askDetails;

    // The trailer of the focused title: in the video window (YouTube's embedded player), or in the YouTube app.
    private TrailerPlayer trailer;
    private boolean trailerOn;
    /** The running trailer started by itself (after the wait on a title): if it can't play, nothing opens. */
    private boolean trailerAuto;
    /** This title's trailer has played or been stopped: no new automatic start until the focus moves to another title. */
    private boolean trailerSpent, autoPending;
    private String touched = "";       // the title focused last: kind + id
    private String[] trailerIds = new String[0];     // this title's candidates, best first, and which one plays
    private int trailerAt;
    /** Titles whose every trailer the embedded player refused (this run): no hint and no autoplay for them. */
    private final Set<String> trailerBlocked = new HashSet<>();
    private final Runnable autoTrailer = () -> {
        autoPending = false;
        if (home.onCatalogPage() && home.trailerMayStart() && !trailerOn && !trailerSpent && autoTrailerOk()) startTrailer(true);
    };
    private final TrailerPlayer.Listener trailerListener = new TrailerPlayer.Listener() {
        @Override public void onPlaying() {
            invalidate();
        }

        @Override public void onEnded() {
            stopTrailer();
        }

        @Override public void onFailed() {
            if (trailerOn && trailerAt + 1 < trailerIds.length) {      // blocked here or not embeddable: the next trailer of the title
                trailerAt++;
                playTrailerAt();
                return;
            }
            boolean auto = trailerAuto;
            if (shown != null) trailerBlocked.add(shown.kind + shown.id);
            stopTrailer();
            if (!auto) home.showToast(Ui.tr("This trailer isn't available in your region."));     // asked for with a long press: say why nothing plays
        }
    };

    HomeCatalogPage(HomeView home, Catalogs catalogs, Details details) {
        super(home, "catalogs");
        this.catalogs = catalogs;
        this.details = details;
        spin.setStyle(Paint.Style.STROKE);
        spin.setStrokeCap(Paint.Cap.ROUND);
        spin.setColor(0xFFFFFFFF);
    }

    @Override boolean isEmpty() {
        return list.isEmpty();
    }

    // MARK: - Layout

    @Override protected void makePaints() {
        Look look = home.look;
        titlePaint = new TextPaint(Ui.text(look.typeface(Ui.semibold), 60 * k, 0xFFFFFFFF));
        factsPaint = Ui.text(look.typeface(Ui.medium), 27 * k, 0xE6FFFFFF);
        overviewPaint = new TextPaint(Ui.text(look.typeface(Ui.medium), 26 * k, 0xD9FFFFFF));
        rowTitle = Ui.text(look.typeface(Ui.semibold), 32 * k, 0xFF1D1D1F);
        textGen = -1;
    }

    /**
     * The video window (16:9) at the top left with the details beside it and the service's badge at the right; the
     * catalog row below, from where the rows slide (they may slide off screen, so that node bleeds).
     */
    @Override protected Node build() {
        float stageH = 440 * k, badge = Math.round(64 * k);
        stage = Node.of("video", (c, f) -> drawStage(c, f)).size(stageH * 16 / 9, stageH);
        text = Node.of("details", (c, f) -> drawText(c, f)).flex();
        service = Node.of("service", (c, f) -> drawService(c, f)).size(badge, badge);
        Node top = Stack.row("top", 0, stage, Node.space(56 * k, 0), text, Node.space(26 * k, 0), service).size(Node.FILL, stageH);
        posters = Node.of("rows", (c, f) -> drawRows(c, f)).flex().bleed();
        Node content = Stack.column("content", 0, Node.space(0, 90 * k), top, Node.space(0, 90 * k), posters).flex();
        return Stack.row("catalogs", 0, Node.space(HomeGrid.MARGIN * k, 0), content, Node.space(HomeGrid.MARGIN * k, 0));
    }

    // MARK: - Rows

    /** Takes TMDB's current rows (a new list after a fetch), keeping the focus where it was when it can. */
    void sync() {
        List<Catalogs.Row> fresh = catalogs.browseRows();
        if (fresh == source) return;
        int focusedRow = PosterRow.sameRowOf(list, row, fresh);
        source = fresh;
        list = PosterRow.sync(list, fresh);
        rows.clear();
        rows.addAll(list);
        row = focusedRow;
        pos.snap(row);
        if (home.onCatalogPage()) {
            if (list.isEmpty()) home.leaveBrowse();
            else touch();
        }
        invalidate();
    }

    @Override void enter() {
        pos.snap(row);
        focus.snap(0);
        focus.target = 1;
        touch();
    }

    @Override void leave() {
        touched = "";
        cancelAutoTrailer();
        stopTrailer();
        invalidate();
    }

    @Override protected void focusChanged(boolean rowChanged) {
        if (rowChanged) pos.target = row;
        focus.snap(0);
        focus.target = 1;
        touch();
    }

    @Override boolean tick(float dt) {
        boolean moving = super.tick(dt) | pos.step(dt) | focus.step(dt) | swap.step(dt);
        if (trailerOn && trailer.loading()) invalidate();      // its spinner turns
        return moving;
    }

    private Catalogs.Item item() {
        return list.isEmpty() ? null : list.get(row).focused();
    }

    /** The focused poster: the title it shows opens (even while its trailer plays). */
    @Override boolean activate() {
        Catalogs.Item it = item();
        if (it == null) return false;
        stopTrailer();
        home.click.open();
        posterRect(row, list.get(row).index, cell);
        home.openTitle(it, cell);
        return true;
    }

    /** Held OK: starts the trailer, or pauses and resumes the one playing. */
    @Override boolean hold() {
        trailerKey();
        return true;
    }

    @Override void pause() {
        cancelAutoTrailer();
        stopTrailer();
        if (trailer != null) {      // the WebView's memory goes back while the home screen is away
            trailer.release();
            trailer = null;
        }
    }

    /** On this page the details' answer brings where a title streams: asked only when it came from disk without it. */
    boolean waitsForDetails(Catalogs.Item it) {
        return it == shown && home.onCatalogPage() && info == null;
    }

    // MARK: - The focused title's details

    /**
     * The focused title changed: shows what is already known about it, and asks for its details (and its neighbours',
     * so the next press is quick) once the focus rests there (holding an arrow asks for nothing on the way).
     */
    private void touch() {
        Catalogs.Item it = item();
        shown = it;
        info = it == null ? null : details.known(it.kind, it.id);
        rested = null;
        home.handler.removeCallbacks(askDetails);
        if (it != null) home.handler.postDelayed(askDetails, REST_MS);
        dropBackdrop();
        textGen = -1;
        String key = it == null ? "" : it.kind + it.id;
        if (!key.equals(touched)) {      // another title (not just the lists made again): its trailer may start by itself
            touched = key;
            trailerSpent = false;
            cancelAutoTrailer();
        }
        scheduleAutoTrailer();
        invalidate();
    }

    private void askDetails() {
        Catalogs.Item it = shown;
        if (it == null || !home.onCatalogPage()) return;
        rested = it;
        info = details.info(it.kind, it.id);
        List<Catalogs.Item> items = list.isEmpty() ? null : list.get(row).row.items;
        int c = list.isEmpty() ? -1 : list.get(row).index;
        for (int d = -1; items != null && d <= 1; d += 2) {
            if (c + d >= 0 && c + d < items.size()) details.info(items.get(c + d).kind, items.get(c + d).id);
        }
        dropBackdrop();
        textGen = -1;
        invalidate();
    }

    /** Details arrived (Details.Listener, through the home screen). */
    void onDetails() {
        if (shown == null) return;
        info = details.known(shown.kind, shown.id);
        if (info == null) return;
        // The details' answer also told Launcher where it streams: the badge looks again in what is kept.
        if (shown.servicePkg == null) shown.serviceAsked = 0;
        dropBackdrop();
        textGen = -1;
        scheduleAutoTrailer();
        invalidate();
    }

    /** No picture for the focused title: the old one goes. */
    private void dropBackdrop() {
        if (info != null && info.backdrop.isEmpty() && back != null) {
            prev = back;
            back = null;
            swap.snap(0);
            swap.target = 1;
        }
    }

    private void buildText() {
        textGen = Ui.generation;
        titleText = null;
        overviewText = null;
        facts = "";
        Catalogs.Item it = shown;
        if (it == null) return;
        int width = Math.max(1, Math.round(text.frame.width()));
        titleText = StaticLayout.Builder.obtain(it.title, 0, it.title.length(), titlePaint, width)
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setLineSpacing(0, 0.94f)
                .setIncludePad(false)
                .build();
        List<String> parts = new ArrayList<>();
        String overview = "";
        if (info != null) {
            if (!info.facts.isEmpty()) parts.add(info.facts);
            if (!info.genres.isEmpty()) parts.add(info.genres);
            overview = !info.overview.isEmpty() ? info.overview : info.tagline;
        } else if (it.stars != null) {
            parts.add(it.stars);
        }
        facts = String.join("  ·  ", parts);
        if (!overview.isEmpty()) {
            overviewText = StaticLayout.Builder.obtain(overview, 0, overview.length(), overviewPaint, width)
                    .setMaxLines(6)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .setLineSpacing(0, 1.05f)
                    .setIncludePad(false)
                    .build();
        }
    }

    // MARK: - Drawing

    @Override void draw(Canvas c) {
        if (list.isEmpty() || titlePaint == null) return;
        super.draw(c);
    }

    /** The video window: the backdrop (cross-fading from the last one) until the trailer plays over it. */
    private void drawStage(Canvas c, RectF stage) {
        float r = 12 * k;
        float dark = home.dark();
        // The picture only once the focus rests (askDetails): a quick scroll asks for none of the titles it passes.
        if (info != null && rested == shown) {
            Bitmap want = details.backdrop(info);
            if (want != null && want != back) {
                prev = back;
                back = want;
                swap.snap(0);
                swap.target = 1;
                home.wake();
            }
        }
        float s = Ui.clamp(swap.value);
        if (s > 0.999f) prev = null;
        tmp.set(stage);
        tmp.offset(0, 10 * k);
        home.dropShadow(c, tmp, r, home.tone(0.2f, 0.4f));
        if (back == null && prev == null) {
            fill.setShader(null);
            fill.setColor(home.look.color(Look.PLACEHOLDER, dark));
            c.drawRoundRect(stage, r, r, fill);
        } else {
            if (prev != null) home.roundImageAlpha(c, prev, stage, r, 255);
            if (back != null) home.roundImageAlpha(c, back, stage, r, prev == null ? 255 : Math.round(255 * s));
        }
        if (trailerOn && trailer.loading()) {      // asked for, not showing yet: the picture dims and a spinner turns on it
            fill.setShader(null);
            fill.setColor(0x73000000);
            c.drawRoundRect(stage, r, r, fill);
            spin.setStrokeWidth(6 * k);
            Ui.spinner(c, stage.centerX(), stage.centerY(), 34 * k, SystemClock.uptimeMillis() / 1000f, spin);
        }
        drawTrailerHint(c, stage);
    }

    /** Title, facts and overview, top to bottom. */
    private void drawText(Canvas c, RectF frame) {
        if (textGen != Ui.generation) buildText();
        float dark = home.dark();
        titlePaint.setColor(home.look.color(Look.TITLE, dark));
        factsPaint.setColor(home.look.color(Look.META, dark));
        overviewPaint.setColor(home.look.color(Look.META, dark));
        float x = frame.left, y = frame.top;
        if (titleText != null) {
            c.save();
            c.translate(x, y);
            titleText.draw(c);
            c.restore();
            y += titleText.getHeight();
        }
        if (!facts.isEmpty()) {
            float base = y + 18 * k - factsPaint.ascent();
            c.drawText(facts, x, base, factsPaint);
            y = base + factsPaint.descent();
        }
        if (overviewText != null) {
            c.save();
            c.translate(x, y + 24 * k);
            overviewText.draw(c);
            c.restore();
        }
    }

    /** The badge of the service OK opens the title in. */
    private void drawService(Canvas c, RectF frame) {
        String pkg = shown == null ? null : home.catalogService(shown, true);
        if (pkg == null || pkg.isEmpty()) return;
        Bitmap badge = ServiceBadge.get(home.getContext(), pkg, Math.round(frame.width()), home.badgesReady);
        if (badge != null) c.drawBitmap(badge, frame.left, frame.top, null);
    }

    /** The catalog rows: the focused one, and the one sliding out while ↑ ↓ change it (clipped under the details). */
    private void drawRows(Canvas c, RectF frame) {
        float dark = home.dark();
        rowTitle.setColor(home.look.color(Look.TITLE, dark));
        home.posterColors();
        c.save();
        c.clipRect(0, frame.top, w, h);
        for (int r = 0; r < list.size(); r++) {
            float d = r - pos.value;
            if (Math.abs(d) >= 1) continue;
            float y0 = rowTop(r);
            PosterRow pr = list.get(r);
            c.drawText(pr.row.title, frame.left + 4 * k, y0 + 30 * k, rowTitle);
            int first = Math.max(0, (int) Math.floor(pr.shift.value)), last = Math.min(pr.count() - 1, first + PosterRow.VISIBLE + 1);
            for (int i = first; i <= last; i++) {
                posterRect(r, i, cell);
                if (cell.right < 0 || cell.left > w) continue;
                float f = home.onCatalogPage() && r == row && i == pr.index ? Ui.clamp(focus.value) : 0;
                home.drawPoster(c, cell, pr.row.items.get(i), f, y0, false);      // no badge: the details above show the service
            }
        }
        c.restore();
    }

    /** Row `r`'s top now (its title, then its posters 50 below). */
    private float rowTop(int r) {
        return posters.frame.top + 20 * k + (r - pos.value) * PITCH * k;
    }

    /** Where poster `i` of row `r` is now. */
    private void posterRect(int r, int i, RectF out) {
        float y0 = rowTop(r);
        float x = posters.frame.left + (i - list.get(r).shift.value) * (PosterRow.POSTER_W + PosterRow.GAP) * k;
        out.set(x, y0 + 50 * k, x + PosterRow.POSTER_W * k, y0 + (50 + PosterRow.POSTER_H) * k);
    }

    // MARK: - Trailer

    boolean trailerOn() {
        return trailerOn;
    }

    /** Resting on a title starts its trailer after a few seconds, when trailers are on the home screen and the player can run. */
    private boolean autoTrailerOk() {
        return Prefs.trailers() && TrailerPlayer.available() && playableTrailer();
    }

    /** The focused title has a trailer and the embedded player hasn't refused them all. */
    private boolean playableTrailer() {
        return info != null && !info.trailer.isEmpty() && shown != null && !trailerBlocked.contains(shown.kind + shown.id);
    }

    private void scheduleAutoTrailer() {
        if (autoPending || trailerSpent || trailerOn || !home.onCatalogPage() || !autoTrailerOk()) return;
        autoPending = true;
        home.handler.postDelayed(autoTrailer, AUTO_TRAILER_MS);
    }

    private void cancelAutoTrailer() {
        autoPending = false;
        home.handler.removeCallbacks(autoTrailer);
    }

    /** Long press on OK or the remote's Play/Pause: starts the trailer, or pauses or resumes the one playing. */
    void trailerKey() {
        if (trailerOn) trailer.togglePause();
        else startTrailer(false);
    }

    /** `auto`: started by the wait on a title, so it only ever plays in the window (YouTube opens only on a long press). */
    private void startTrailer(boolean auto) {
        if (!home.onCatalogPage()) return;
        cancelAutoTrailer();
        trailerSpent = true;
        trailerAuto = auto;
        if (!auto && shown != null && trailerBlocked.contains(shown.kind + shown.id)) {      // refused before: don't try again
            home.showToast(Ui.tr("This trailer isn't available in your region."));
            return;
        }
        trailerIds = info == null || info.trailers.isEmpty() ? new String[0] : info.trailers.split(",");
        trailerAt = 0;
        String id = trailerIds.length > 0 ? trailerIds[0] : "";
        if (id.isEmpty()) {
            if (!auto) home.showToast(Ui.tr("This title has no trailer."));
            return;
        }
        if (!Prefs.trailers() || !TrailerPlayer.available()) {
            if (!auto) openYoutube(id);
            return;
        }
        if (trailer == null) trailer = new TrailerPlayer(home.getContext(), trailerListener);
        trailerOn = true;
        playTrailerAt();
    }

    /** Plays the candidate `trailerAt` in the video window. */
    private void playTrailerAt() {
        RectF s = stage.frame;
        trailer.play(trailerIds[trailerAt], Math.round(s.left), Math.round(s.top), Math.round(s.width()), Math.round(s.height()));
        invalidate();
    }

    void stopTrailer() {
        if (!trailerOn) return;
        trailerSpent = true;
        trailerOn = false;
        trailer.stop();
        invalidate();
    }

    /**
     * Opens the trailer where the person prefers: the official YouTube TV app, else another YouTube client (SmartTube and
     * the like: the apps that answer a "vnd.youtube:" link, which only YouTube clients do), else a browser (the first app
     * that opens the link, not Android's placeholder). Always with a plain watch link (some clients don't read the
     * "vnd.youtube:" form), and always for one chosen app, so no chooser comes up.
     */
    private void openYoutube(String id) {
        android.content.pm.PackageManager pm = home.getContext().getPackageManager();
        android.content.Intent watch = new android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("https://www.youtube.com/watch?v=" + id));
        String official = null, client = null, browser = null;
        try {
            android.content.Intent clients = new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("vnd.youtube:" + id));
            for (android.content.pm.ResolveInfo r : pm.queryIntentActivities(clients, 0)) {
                if (client == null) client = r.activityInfo.packageName;
            }
            for (android.content.pm.ResolveInfo r : pm.queryIntentActivities(watch, 0)) {
                String pkg = r.activityInfo.packageName;
                if (pkg.equals("com.google.android.youtube.tv")) official = pkg;
                else if (browser == null && !pkg.equals(client) && !pkg.equals("android") && !pkg.endsWith("frameworkpackagestubs")) browser = pkg;
            }
        } catch (RuntimeException e) {
            AppLog.w("trailer apps: " + e.getMessage());
        }
        String pick = official != null ? official : client != null ? client : browser;
        if (pick != null) watch.setPackage(pick);
        try {
            home.activity.startActivity(watch);
        } catch (RuntimeException e) {
            AppLog.w("trailer " + pick + ": " + e.getMessage());
            home.showToast(Ui.tr("Couldn't open YouTube."));
        }
    }

    /** The small "Hold OK for the trailer" pill at the video window's foot, while there is a trailer and it isn't playing. */
    private void drawTrailerHint(Canvas c, RectF stage) {
        if (!home.onCatalogPage() || trailerOn || !playableTrailer()) return;
        String hint = Ui.tr("Hold OK for the trailer");
        float pad = 24 * k, arrow = 18 * k, gap = 14 * k, hh = 54 * k;
        float x = stage.left + 24 * k, y = stage.bottom - 24 * k - hh;
        pill.set(x, y, x + pad + arrow + gap + factsPaint.measureText(hint) + pad, y + hh);
        fill.setShader(null);
        fill.setColor(0x99000000);
        c.drawRoundRect(pill, hh / 2, hh / 2, fill);
        playArrow.reset();
        playArrow.moveTo(pill.left + pad, y + hh / 2 - arrow / 2);
        playArrow.lineTo(pill.left + pad + arrow, y + hh / 2);
        playArrow.lineTo(pill.left + pad, y + hh / 2 + arrow / 2);
        playArrow.close();
        fill.setColor(0xFFFFFFFF);
        c.drawPath(playArrow, fill);
        factsPaint.setColor(0xFFFFFFFF);
        c.drawText(hint, pill.left + pad + arrow + gap, y + hh / 2 + Ui.halfCap(factsPaint), factsPaint);
    }
}
