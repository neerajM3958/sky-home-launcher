package com.skyhome.launcher;

/**
 * The home screen's shared measurements, in its 1920×1080 design space (multiplied by k = width / 1920 when a page lays
 * out). Pages take these from here instead of repeating them, so one change moves every page the same way.
 */
final class HomeGrid {
    static final float W = 1920, H = 1080;
    /** tvOS safe area: content 80 from the sides. */
    static final float MARGIN = 80, CONTENT_R = 1840;
    /** What a page's content (anything not marked bleed) stays inside, top to bottom. */
    static final float SAFE_TOP = 30, SAFE_BOTTOM = 1050;

    private HomeGrid() {}
}
