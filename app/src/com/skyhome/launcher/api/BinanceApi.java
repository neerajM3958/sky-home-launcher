package com.skyhome.launcher.api;

import java.io.IOException;

/** Binance's public 24-hour prices (no key). Blocking: background threads only. */
public final class BinanceApi {
    /** Binance.com refuses the US (HTTP 451); Binance.US answers the same call in dollars. */
    private static final String[] HOSTS = {"https://api.binance.com", "https://api.binance.us"};

    private BinanceApi() {}

    /**
     * The JSON text of the 24-hour change of these pairs ("BTCUSDT"...). A pair Binance doesn't list throws a
     * `Http.Failure` with code 400, which the caller answers with another currency.
     */
    public static String ticker(String[] pairs) throws IOException {
        StringBuilder symbols = new StringBuilder();
        for (int i = 0; i < pairs.length; i++) symbols.append(i == 0 ? "" : ",").append("%22").append(pairs[i]).append("%22");
        String path = "/api/v3/ticker/24hr?type=MINI&symbols=%5B" + symbols + "%5D";
        IOException failed = null;
        for (String host : HOSTS) {
            try {
                return Http.to(host + path).timeouts(6000, 6000).get();
            } catch (IOException e) {
                // Binance.US has only dollar pairs: its error counts only where Binance.com refuses the US (HTTP 451).
                // After a timeout or a 5xx there, its HTTP 400 would put the prices in dollars for good.
                if (failed == null || Http.code(failed) == 451) failed = e;
                // A pair Binance doesn't list (HTTP 400) isn't a refusal: the caller falls back to dollars instead.
                if (Http.code(e) == 400) break;
            }
        }
        throw failed;
    }
}
