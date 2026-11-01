package com.skyhome.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Page 1, the resting screen: the weather and the icon-only buttons at the top (Home Settings, Settings, Live TV, AV and
 * HDMI input; the focused one's name below it, the small crypto quotes at the row's right end), the picks in the
 * person's style (`PicksSection`) and the shelf (`HomeShelf`), laid out as one column of nodes. Three rows take the
 * focus, top to bottom: `TOP` (Cards' tabs, then the buttons), `PICKS` and `SHELF`; ↓ from the shelf leaves the page.
 * It draws in four layers of the home screen, each redrawn only when its part changed: the top (weather, tabs, picks,
 * quotes), the buttons, the shelf's glass and the shelf's tiles.
 */
final class HomeTopPage extends HomePage {
    /** The rows that take the focus (also TalkBack's groups in the home screen's ids). */
    static final int SHELF = 2, TOP = 4, PICKS = 5;
    /** Below the shelf's glass, to the screen's foot (1920-wide units). */
    private static final float SHELF_FOOT = 60, QUOTES_BASE = 694;
    private static final int INK = 0xFF1D1D1F, DIM = 0xFF6E6E73, FAINT = 0xFF8E8E93;

    final HomeData data;
    final HomeShelf shelf;
    final PicksSection picks;
    private Node shelfNode;
    private int zone = SHELF, topIndex;
    /** Cards' three tabs, then the round buttons at the top right (up to HomeApps.MAX_TOP). */
    final Spring[] topFocus = new Spring[3 + HomeApps.MAX_TOP];
    final RectF[] topRects = new RectF[3 + HomeApps.MAX_TOP];
    private final List<HomeApps.Item> buttons = new ArrayList<>();
    private boolean buttonsDirty = true, glassDirty = true, focusMoving;

    Paint lTitle, lTab, lTabOn, lCardTitle, lCardMeta, lQuote, lTip, lDate, lClock, lWeather, lClockQuote, staleQuote, lStatus;
    String clockText = "", dateText = "";
    private long clockMinute = -1;
    /** "Off in 42 min" next to the round buttons while the sleep timer runs (Settings > Sleep Timer); "" otherwise. */
    private String sleepText = "";
    private Quotes quotes;
    private long weatherAt = -1;
    private int weatherPrefs = -1;
    private String weatherLanguage = "", temp = "", condition = "", highLow = "";
    private final RectF cell = new RectF(), tmp = new RectF();

    HomeTopPage(HomeView home, HomeData data, Recs recs) {
        super(home, "top");
        this.data = data;
        shelf = new HomeShelf(this);
        picks = PicksSection.create(this, recs);
        for (int i = 0; i < topFocus.length; i++) {
            topFocus[i] = new Spring(0.26f, 0.85f, 0);
            topRects[i] = new RectF();
        }
    }

    /** The first page always shows. */
    @Override boolean isEmpty() {
        return false;
    }

    // MARK: - Layout: one column (the top bar, the picks' nodes, the shelf) between the margins

    @Override protected void makePaints() {
        Look look = home.look;
        lTitle = Ui.text(look.typeface(Ui.displaySemibold), 76 * k, INK);
        lTitle.setLetterSpacing(0.004f);
        lTab = Ui.text(look.typeface(Ui.medium), 30 * k, DIM);
        lTabOn = Ui.text(look.typeface(Ui.semibold), 30 * k, INK);
        lCardTitle = Ui.text(look.typeface(Ui.semibold), 31 * k, INK);
        lCardTitle.setLetterSpacing(0.012f);
        lCardMeta = Ui.text(look.typeface(Ui.medium), 25 * k, DIM);
        lQuote = Ui.numbers(Ui.regular, 21 * k, FAINT);
        lTip = Ui.text(look.typeface(Ui.semibold), 25 * k, INK);
        lTip.setTextAlign(Paint.Align.CENTER);
        lDate = Ui.text(look.typeface(Ui.semibold), 34 * k, DIM);
        lClock = Ui.numbers(Ui.light, 200 * k, INK);
        lClock.setLetterSpacing(-0.01f);
        lWeather = Ui.text(look.typeface(Ui.medium), 34 * k, DIM);
        lStatus = Ui.text(look.typeface(Ui.medium), 22 * k, DIM);
        lClockQuote = Ui.numbers(Ui.medium, 28 * k, DIM);
        staleQuote = Ui.text(Ui.medium, 21 * k, 0x99FFFFFF);
        staleQuote.setTextAlign(Paint.Align.RIGHT);
        picks.makePaints(k);
        clockMinute = -1;
        updateClock();
    }

    @Override protected Node build() {
        Look.Metrics m = home.look.m;
        shelf.size(k);
        shelfNode = new Node("shelf").size(Node.FILL, shelf.height()).when(!shelf.apps.isEmpty());
        List<Node> nodes = new ArrayList<>();
        nodes.add(Node.space(0, HomeGrid.SAFE_TOP * k));
        nodes.add(new Node("weather and buttons").size(Node.FILL, (m.tabTop - HomeGrid.SAFE_TOP) * k));
        Collections.addAll(nodes, picks.nodes(k));
        nodes.add(shelfNode);
        nodes.add(Node.space(0, SHELF_FOOT * k));
        Node column = Stack.column("page 1", 0, nodes.toArray(new Node[0])).flex();
        return Stack.row("top", 0, Node.space(HomeGrid.MARGIN * k, 0), column, Node.space((HomeGrid.W - HomeGrid.CONTENT_R) * k, 0));
    }

    @Override protected void placed() {
        picks.placed();
        shelf.place(shelfNode.frame, w);
        // The round buttons from the right margin, at the look's height.
        Look.Metrics m = home.look.m;
        float b = m.buttonSize * k, right = HomeGrid.CONTENT_R * k, cy = m.buttonCy * k;
        for (int i = buttons.size() - 1; i >= 0; i--) {
            topRects[3 + i].set(right - b, cy - b / 2, right, cy + b / 2);
            right -= b + m.buttonGap * k;
        }
        buttonsDirty = glassDirty = true;
    }

    /** The round buttons at the top right (Settings > Top Buttons), with their names in the current language. */
    void setButtons(List<HomeApps.Item> list) {
        buttons.clear();
        buttons.addAll(list);
        if (zone == TOP && topIndex >= 3 + buttons.size()) {
            topIndex = 2 + buttons.size();
            // No tab to land on (no picks, or Spotlight, which has none): the picks, else the shelf.
            if (topIndex < 3 && picks.tabs() == 0) zone = picks.isEmpty() ? SHELF : PICKS;
            else if (topIndex < 3) picks.selectTab(topIndex);
            retarget(true);
        }
        relayout();
    }

    /** The shelf's apps: when they aren't the same ones, the page is laid out again. */
    void setShelf(List<HomeApps.Item> list) {
        home.wake();
        if (shelf.set(list)) {
            retarget(true);
            relayout();
        }
        glassDirty = true;
    }

    /** This hour's picks (before the first layout there's nothing to draw them in). */
    void updatePicks() {
        if (w > 0) picks.update();
    }

    /** The picks went while they had the focus: it goes to the shelf. */
    void picksEmptied() {
        if (zone != PICKS) return;
        zone = SHELF;
        retarget(false);
    }

    // MARK: - Focus

    /** The focus is on the picks. */
    boolean onPicks() {
        return zone == PICKS;
    }

    /** Sets the springs' targets from the current focus (snap: no animation); only the layers it changes are redrawn. */
    void retarget(boolean snap) {
        boolean open = home.sheetOpen();
        shelf.aim(!open && zone == SHELF ? shelf.index : -1, snap);
        boolean topChanged = aim(topFocus, !open && zone == TOP ? topIndex : -1, snap);
        boolean picksChanged = picks.aim(!open && zone == PICKS, snap);
        if (snap || topChanged) buttonsDirty = true;
        if (snap || topChanged || picksChanged) invalidate();
        home.focusMoved();
    }

    /** Aims a group's springs (only `focused` at 1); returns true if any target changed. */
    static boolean aim(Spring[] springs, int focused, boolean snap) {
        boolean changed = false;
        for (int i = 0; i < springs.length; i++) {
            float target = i == focused ? 1 : 0;
            changed |= springs[i].target != target;
            springs[i].target = target;
            if (snap) springs[i].snap(target);
        }
        return changed;
    }

    /** Coming back from another page: the shelf has the focus. */
    @Override void enter(boolean fromAbove) {
        zone = SHELF;
    }

    /** Back: the focus goes to the shelf; false if it was there. */
    boolean toShelf() {
        if (zone == SHELF) return false;
        zone = SHELF;
        return true;
    }

    /** Home pressed on the home screen: the first app, Explore, nothing being moved. */
    void reset(boolean snap) {
        shelf.cancel(snap);
        zone = SHELF;
        shelf.index = 0;
        picks.selectTab(0);
    }

    private int firstTop() {
        return picks.tabs() > 0 ? 0 : 3;
    }

    /** The arrows; false when nothing moved (↓ from the shelf leaves the page: the home screen's call). */
    @Override boolean move(int dx, int dy) {
        switch (zone) {
            case SHELF:
                if (dx != 0) {
                    if (!shelf.move(dx)) return false;
                } else if (dy > 0) {
                    return false;
                } else if (!picks.isEmpty()) {
                    zone = PICKS;
                    picks.index = 0;
                } else if (picks.tabs() > 0 || !buttons.isEmpty()) {
                    zone = TOP;
                    topIndex = picks.tabs() > 0 ? picks.tab() : 3;
                } else {
                    return false;
                }
                break;
            case PICKS:
                if (dx != 0) {
                    if (!picks.move(dx)) return false;
                } else if (dy < 0) {
                    // As on Apple TV: up to the tabs focuses the selected tab; Spotlight has none, so the buttons.
                    if (picks.tabs() == 0 && buttons.isEmpty()) return false;
                    zone = TOP;
                    topIndex = picks.tabs() > 0 ? picks.tab() : 3;
                } else {
                    zone = SHELF;
                    if (!shelf.apps.isEmpty()) shelf.index = shelf.nearest(picks.focusedRect().centerX());
                }
                break;
            default:
                if (dx != 0) {
                    int next = Math.max(firstTop(), Math.min(2 + buttons.size(), topIndex + dx));
                    if (next == topIndex) return false;
                    topIndex = next;
                    if (topIndex < 3) picks.selectTab(topIndex);   // like Apple TV tabs: focusing a tab selects it
                } else if (dy > 0) {
                    zone = picks.isEmpty() ? SHELF : PICKS;
                    picks.index = 0;
                } else if (topIndex < 3 && !buttons.isEmpty()) {
                    topIndex = 3;   // up from the tabs reaches the corner buttons without walking the row
                } else {
                    return false;
                }
                break;
        }
        retarget(false);
        return true;
    }

    /** OK: a tab is selected; a button, a pick or an app opens. */
    @Override boolean activate() {
        if (zone == TOP) {
            if (topIndex < 3) picks.selectTab(topIndex);
            else home.openItem(buttons.get(topIndex - 3), topRects[topIndex]);
        } else if (zone == PICKS) {
            Recs.Pick p = picks.focusedPick();
            if (p == null || p.pkg == null) return false;
            home.click.open();
            home.openPick(p, picks.focusedRect());
        } else {
            HomeApps.Item it = shelf.focused();
            if (it == null) return false;
            home.openItem(it, shelf.rects[shelf.index]);
        }
        return true;
    }

    /** Holding OK can lift the focused app to move it. */
    boolean canHold() {
        return !home.sheetOpen() && zone == SHELF && shelf.canLift();
    }

    /** OK held: the focused app is lifted to be moved; false if nothing happened. */
    @Override boolean hold() {
        return canHold() && shelf.lift();
    }

    @Override void pause() {
        shelf.cancel(true);
    }

    // MARK: - Frames

    /** One frame of the shelf, the buttons and the picks; the layers that moved are marked to draw again. */
    @Override boolean tick(float dt) {
        long up = SystemClock.uptimeMillis();
        boolean tiles = shelf.tick(dt, up), top = false;
        for (Spring s : topFocus) top |= s.step(dt);
        boolean moving = picks.tick(dt, up);
        if (top) buttonsDirty = true;
        if (top | moving) invalidate();
        focusMoving = tiles | top | moving;
        return focusMoving;
    }

    /** Something focused moved in the last frame (the look's focus glow follows it). */
    boolean focusMoving() {
        return focusMoving;
    }

    /** Every layer of the page draws again (night, new data). */
    void invalidateAll() {
        buttonsDirty = glassDirty = true;
        shelf.invalidate();
        invalidate();
    }

    /** The buttons' layer draws again (a focused button's name hides while the popover is open). */
    void invalidateButtons() {
        buttonsDirty = true;
        home.wake();
    }

    boolean takeButtons() {
        boolean d = buttonsDirty;
        buttonsDirty = false;
        return d;
    }

    boolean takeGlass() {
        boolean d = glassDirty;
        glassDirty = false;
        return d;
    }

    /** A new minute: the clock (shown without picks) and the buttons' row draw again; true if it changed. */
    boolean updateClock() {
        long now = System.currentTimeMillis();
        long minute = now / 60_000;
        if (minute == clockMinute || lClock == null) return false;
        clockMinute = minute;
        clockText = Ui.clock(now);
        dateText = Ui.date(now);
        buttonsDirty = true;
        if (picks instanceof PicksOff) invalidate();
        return true;
    }

    void setSleep(String text) {
        if (text.equals(sleepText)) return;
        sleepText = text;
        buttonsDirty = true;
    }

    /** Adds the focused items' centres, weighted by their focus, to `acc` (weight, x, y): the look's focus glow. */
    void glow(float[] acc) {
        for (int i = 0; i < Math.min(shelf.focus.length, shelf.rects.length); i++) add(acc, shelf.focus[i].value, shelf.rects[i]);
        for (int i = 0; i < topFocus.length; i++) add(acc, topFocus[i].value, topRects[i]);
        picks.glow(acc);
    }

    private static void add(float[] acc, float value, RectF r) {
        float v = Ui.clamp(value);
        acc[0] += v;
        acc[1] += v * r.centerX();
        acc[2] += v * r.centerY();
    }

    // MARK: - Drawing

    /** Text colors for the current night value (dark by day, white at night). */
    private void applyTone() {
        Look look = home.look;
        float dark = home.dark();
        lTitle.setColor(look.color(Look.TITLE, dark));
        lTab.setColor(look.color(Look.TAB, dark));
        lTabOn.setColor(look.color(Look.TAB_ON, dark));
        lCardTitle.setColor(look.color(Look.TITLE, dark));
        lCardMeta.setColor(look.color(Look.META, dark));
        lQuote.setColor(look.color(Look.QUOTE, dark));
        lTip.setColor(look.color(Look.TIP, dark));
        lDate.setColor(look.color(Look.META, dark));
        lClock.setColor(look.color(Look.TITLE, dark));
        lWeather.setColor(look.color(Look.WEATHER, dark));
        lClockQuote.setColor(look.color(Look.META, dark));
        lStatus.setColor(look.color(Look.META, dark));
        staleQuote.setColor(look.color(Look.FAINT, dark));
    }

    /** The top layer: the picks (with the weather or the clock) and the quotes. */
    @Override void draw(Canvas c) {
        if (lTitle == null) return;
        applyTone();
        picks.draw(c);
        if (HomeData.coinCount() > 0) drawQuotes(c);
        LayoutCheck.drawBounds(c, root, k);
    }

    /** The weather where the title used to be: the symbol and the temperature large, the condition and today's high and low beside them. */
    void drawWeather(Canvas c) {
        HomeData.Weather wx = data.weather;
        float x = HomeGrid.MARGIN * k, base = home.look.m.titleBase * k;
        if (!Prefs.weather() || Double.isNaN(wx.temp)) {
            drawStatus(c, x, base);      // no weather: the connection line takes its place
            return;
        }
        if (data.weatherAt != weatherAt || Prefs.version != weatherPrefs || !Ui.language.equals(weatherLanguage)) {
            weatherAt = data.weatherAt;
            weatherPrefs = Prefs.version;
            weatherLanguage = Ui.language;
            temp = HomeData.degrees(wx.temp);
            condition = HomeData.condition(wx.code, wx.isDay);
            String city = Prefs.city();
            highLow = HomeData.highLow(wx, "  ") + (city.isEmpty() ? "" : "  ·  " + city);
        }
        Context ctx = home.getContext();
        String symbol = Symbols.weather(wx.code, wx.isDay);
        float size = 64 * k, sw = Symbols.width(ctx, symbol, size);
        Symbols.draw(ctx, c, symbol, x + sw / 2, base - Ui.halfCap(lTitle), size, home.look.symbolInk(symbol, lTitle.getColor()));
        float tx = x + sw + 20 * k;
        c.drawText(temp, tx - Ui.leftBearing(lTitle, temp), base, lTitle);
        float bx = tx + lTitle.measureText(temp) + 30 * k;
        // With the connection line the three lines stack beside the temperature, the last on its baseline: the tabs
        // start 28 units below it, so nothing else moves.
        boolean status = showStatus();
        c.drawText(condition, bx, base - (status ? 60 : 34) * k, lWeather);
        c.drawText(highLow, bx, base - (status ? 30 : 0) * k, lCardMeta);
        if (status) drawStatus(c, bx, base - 2 * k);
    }

    /** The connection line is on (Home Settings) and there is something to show. */
    boolean showStatus() {
        return Prefs.connection() && !ConnectionStatus.get(home.getContext()).state().isEmpty();
    }

    /** Wi-Fi and Bluetooth (`ConnectionStatus`) as one line from `x` on the baseline `base`; the picks styles place it under their weather. */
    void drawStatus(Canvas c, float x, float base) {
        if (!showStatus()) return;
        ConnectionStatus.draw(c, ConnectionStatus.get(home.getContext()).state(), x, base, lStatus);
    }

    /** The quotes row's text and sizes, for one set of coins, prices and text size. */
    private static final class Quotes {
        final int[] coins;
        final double[] prices;
        final float size, cap, halfCap;
        final String[] values;
        final float[] glyphs, widths;

        Quotes(Context context, int[] coins, double[] all, Paint text) {
            this.coins = coins;
            size = text.getTextSize();
            cap = Ui.cap(text);
            halfCap = Ui.halfCap(text);
            int n = coins.length;
            prices = new double[n];
            values = new String[n];
            glyphs = new float[n];
            widths = new float[n];
            for (int i = 0; i < n; i++) {
                prices[i] = all[coins[i]];
                values[i] = HomeData.compact(prices[i]);
                glyphs[i] = Glyphs.width(context, HomeData.COINS[coins[i]], cap);
                widths[i] = text.measureText(values[i]);
            }
        }

        boolean matches(int[] now, double[] all, float textSize) {
            if (now != coins || textSize != size) return false;
            for (int i = 0; i < coins.length; i++) if (Double.compare(all[coins[i]], prices[i]) != 0) return false;
            return true;
        }
    }

    /** Coin logo + price in the configured currency, in a row at the right end of the tab bar (under the clock without picks). */
    private void drawQuotes(Canvas c) {
        Context ctx = home.getContext();
        boolean clock = picks instanceof PicksOff;
        // At the right margin, under the round buttons: the shelf's glass is narrower and centred with few apps.
        float right = HomeGrid.CONTENT_R * k;
        Paint text = clock ? lClockQuote : lQuote;
        // The corner buttons' names appear in this row: the quotes fade out while one of them has focus.
        float fade = 1;
        for (int i = 3; i < 3 + buttons.size() && !clock; i++) fade = Math.min(fade, 1 - Ui.clamp(topFocus[i].value));
        if (fade <= 0.01f) return;
        int textColor = text.getColor();
        text.setColor(Ui.alpha(textColor, fade));
        // One read of the list, so n always matches the coins drawn below.
        int[] coins = HomeData.active();
        int n = coins.length;
        if (n == 0) {
            text.setColor(textColor);
            return;
        }
        // Measured once per set of prices: this row is redrawn on every frame of the cards' focus animation, and
        // measuring text there cost ~3 ms a frame on a Mi Box 3.
        Quotes q = quotes;
        if (q == null || !q.matches(coins, data.prices, text.getTextSize())) q = quotes = new Quotes(ctx, coins, data.prices, text);
        Look look = home.look;
        float base = clock ? QUOTES_BASE * k : (look.m.tabTop + look.m.tabH / 2) * k + q.halfCap;
        float cap = q.cap, gap = (clock ? 8 : 6) * k, spacing = (clock ? 32 : 24) * k;
        float total = spacing * (n - 1);
        for (int i = 0; i < n; i++) total += q.glyphs[i] + gap + q.widths[i];
        float x = clock ? HomeGrid.MARGIN * k : right - total;
        int coinColor = clock ? text.getColor() : Ui.alpha(look.color(Look.COIN_DIM, home.dark()), fade);
        for (int i = 0; i < n; i++) {
            Glyphs.coin(ctx, c, HomeData.COINS[coins[i]], x + q.glyphs[i] / 2, base - cap / 2, cap, coinColor);
            x += q.glyphs[i] + gap;
            c.drawText(q.values[i], x, base, text);
            x += q.widths[i] + spacing;
        }
        text.setColor(textColor);
        String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
        if (stale.isEmpty()) return;
        if (clock) c.drawText(stale, x - spacing + 24 * k + staleQuote.measureText(stale), base, staleQuote);
        else c.drawText(stale, right - total - 24 * k, base, staleQuote);
    }

    /**
     * The round buttons (Screensaver, Home Settings and Settings by default; also Live TV, AV input and HDMI input):
     * icon-only glass circles (light by day, smoked at night); the focused one turns white with a dark icon (the tvOS
     * focus) and its name appears below it. Drawn in their own layer.
     */
    void drawButtons(Canvas c) {
        if (lTitle == null) return;
        applyTone();
        Look look = home.look;
        float dark = home.dark();
        Context ctx = home.getContext();
        for (int i = 0; i < buttons.size(); i++) {
            HomeApps.Item it = buttons.get(i);
            RectF r = topRects[3 + i];
            float f = Ui.clamp(topFocus[3 + i].value), s = 1 + look.m.buttonGrow * f, rad = r.width() / 2 * s;
            cell.set(r.centerX() - rad, r.centerY() - rad, r.centerX() + rad, r.centerY() + rad);
            tmp.set(cell);
            tmp.offset(0, (6 + 8 * f) * k);
            home.painter.shadow(c, tmp, rad, home.tone(0.1f, 0.3f) + home.tone(0.14f, 0.2f) * f);
            look.roundButton(c, cell, rad, f, dark);
            Symbols.draw(ctx, c, it.symbol, cell.centerX(), cell.centerY(), 27 * k * s, look.roundButtonInk(f, dark));
            if (f > 0.01f && !home.popoverOpen()) {
                String label = it.label;
                float half = lTip.measureText(label) / 2;
                float x = Math.min(r.centerX(), HomeGrid.CONTENT_R * k - half);
                lTip.setAlpha(Math.round(255 * f));
                c.drawText(label, x, r.bottom + (40 + 4 * f) * k, lTip);
            }
        }
        if (sleepText.isEmpty()) return;
        float right = (buttons.isEmpty() ? HomeGrid.CONTENT_R * k : topRects[3].left) - 28 * k, cy = look.m.buttonCy * k;
        float tw = lQuote.measureText(sleepText), size = lQuote.getTextSize();
        c.drawText(sleepText, right - tw, cy + Ui.halfCap(lQuote), lQuote);
        Symbols.draw(ctx, c, "bedtime", right - tw - size * 0.9f, cy, size, lQuote.getColor());
    }

    // MARK: - TalkBack: the tabs and buttons, the picks and the apps (ids from the home screen: group * 1000 + index + 1)

    void a11yItems(List<Integer> out) {
        for (int i = firstTop(); i < 3 + buttons.size(); i++) out.add(HomeView.a11yId(TOP, i));
        for (int i = 0; i < picks.a11yCount(); i++) out.add(HomeView.a11yId(PICKS, i));
        for (int i = 0; i < shelf.apps.size(); i++) out.add(HomeView.a11yId(SHELF, i));
    }

    String a11yText(int group, int i) {
        switch (group) {
            case TOP:
                if (i >= 3) return HomeView.label(buttons, i - 3);
                return Ui.trf("{name}, tab {i} of {n}", "name", new String[] {Ui.tr("Explore"), Ui.tr("Movies"), Ui.tr("Shows")}[i], "i", i + 1, "n", 3);
            case PICKS: return picks.a11yText(i);
            case SHELF:
                if (shelf.reordering() && i == shelf.index) {
                    return Ui.trf("Moving {app}, position {n} of {total}",
                            "app", HomeView.label(shelf.apps, i), "n", i + 1, "total", shelf.apps.size());
                }
                return HomeView.label(shelf.apps, i);
            default: return "";
        }
    }

    /** Where item `i` of `group` is on the page (the caller adds where the page is); false if it isn't there. */
    boolean a11yRect(int group, int i, RectF out) {
        switch (group) {
            case TOP:
                if (i < firstTop() || i >= 3 + buttons.size()) return false;
                out.set(topRects[i]);
                return true;
            case PICKS: return picks.a11yRect(i, out);
            case SHELF:
                if (i >= shelf.rects.length) return false;
                out.set(shelf.rects[i]);
                return true;
            default: return false;
        }
    }

    /** TalkBack's id of the focused item; -1 if none. */
    int a11yFocused() {
        switch (zone) {
            case TOP: return HomeView.a11yId(TOP, topIndex);
            case PICKS: return picks.a11yCount() > 0 ? HomeView.a11yId(PICKS, picks.index) : -1;
            default: return shelf.apps.isEmpty() ? -1 : HomeView.a11yId(SHELF, shelf.index);
        }
    }

    /** Item `i` of `group` can take the focus. */
    boolean a11yHas(int group, int i) {
        switch (group) {
            case TOP: return i >= firstTop() && i < 3 + buttons.size();
            case PICKS: return i < picks.a11yCount();
            case SHELF: return i < shelf.apps.size();
            default: return false;
        }
    }

    /** TalkBack moved to item `i` of `group` (checked with `a11yHas`): the remote's focus goes there. */
    void a11yFocus(int group, int i) {
        if (group == TOP) {
            topIndex = i;
            if (i < 3) picks.selectTab(i);
        } else if (group == PICKS) {
            picks.index = i;
        } else {
            shelf.index = i;
        }
        zone = group;
        retarget(false);
    }
}
