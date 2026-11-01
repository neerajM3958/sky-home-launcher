package com.skyhome.launcher;

import android.graphics.Canvas;
import android.graphics.RectF;

/** Picks off: no tabs and no cards; a large clock, the date and the weather where they would be (1920-wide units). */
final class PicksOff extends PicksSection {
    private static final float DATE_BASE = 330, CLOCK_BASE = 540, WEATHER_BASE = 626, STATUS_BASE = 660;
    private final RectF none = new RectF();
    private long weatherAt = -1;
    private int weatherPrefs = -1;
    private String weatherLanguage = "", weatherLine = "";

    PicksOff(HomeTopPage page, Recs recs) {
        super(page, recs);
    }

    @Override Node[] nodes(float k) {
        return new Node[] {Node.space(0, 0).flex()};
    }

    @Override void draw(Canvas c) {
        HomeTopPage p = page;
        float x = HomeGrid.MARGIN * k;
        c.drawText(p.dateText, x - Ui.leftBearing(p.lDate, p.dateText), DATE_BASE * k, p.lDate);
        c.drawText(p.clockText, x - Ui.leftBearing(p.lClock, p.clockText), CLOCK_BASE * k, p.lClock);
        HomeData.Weather wx = p.data.weather;
        // The connection line sits under the weather (where the weather would be, without it), above the quotes.
        boolean weatherOn = Prefs.weather() && !Double.isNaN(wx.temp);
        p.drawStatus(c, x, (weatherOn ? STATUS_BASE : WEATHER_BASE) * k);
        if (!weatherOn) return;
        if (p.data.weatherAt != weatherAt || Prefs.version != weatherPrefs || !Ui.language.equals(weatherLanguage)) {
            weatherAt = p.data.weatherAt;
            weatherPrefs = Prefs.version;
            weatherLanguage = Ui.language;
            weatherLine = HomeData.degrees(wx.temp) + "  " + HomeData.condition(wx.code, wx.isDay) + "   "
                    + HomeData.highLow(wx, "  ");
        }
        float size = 40 * k, base = WEATHER_BASE * k;
        String symbol = Symbols.weather(wx.code, wx.isDay);
        float sw = Symbols.width(home.getContext(), symbol, size);
        Symbols.draw(home.getContext(), c, symbol, x + sw / 2, base - Ui.halfCap(p.lWeather), size, p.lWeather.getColor());
        c.drawText(weatherLine, x + sw + 16 * k, base, p.lWeather);
    }

    @Override boolean move(int dx) {
        return false;
    }

    @Override void autoRotate() {}

    @Override RectF focusedRect() {
        return none;
    }

    @Override Recs.Pick focusedPick() {
        return null;
    }
}
