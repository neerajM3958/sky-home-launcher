package com.skyhome.launcher;

import android.graphics.RectF;

/**
 * Page 2: the widgets, the cards that are only read (today the crypto quotes with their 24 h change). Hidden when none
 * is on. Nothing on it takes the remote's focus (TalkBack's can land on a card, to read it).
 */
final class HomeWidgetsPage extends HomePage {
    private static final float QUOTES_H = 300;
    private Node quotes;

    HomeWidgetsPage(HomeView home) {
        super(home, "widgets");
    }

    @Override boolean isEmpty() {
        return !home.quotesOn();
    }

    /** The cards, centred on the page between the side margins. */
    @Override protected Node build() {
        quotes = Node.of("quotes", (c, f) -> home.drawQuotes(c, f)).size(Node.FILL, QUOTES_H * k);
        Node column = Stack.column("cards", 0, Node.space(0, 0).flex(), quotes, Node.space(0, 0).flex()).flex();
        return Stack.row("widgets", 0, Node.space(HomeGrid.MARGIN * k, 0), column, Node.space(HomeGrid.MARGIN * k, 0));
    }

    /** Where the quotes card is (for TalkBack). */
    RectF quotesRect() {
        return quotes == null ? new RectF() : quotes.frame;
    }
}
