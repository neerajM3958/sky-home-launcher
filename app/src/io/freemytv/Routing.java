package io.freemytv;

import java.util.List;

/**
 * Where a title opens, decided from where it streams. No Android in here, so it can be tested on its own;
 * Launcher gathers the facts and runs it.
 *
 * <pre>
 * 0. The lookup could not be done            -> the catch-all app, else "couldn't check"
 * 5. TMDB answered: on no service            -> the catch-all app, else "unavailable in your region"
 * 1. Pick one service among the ones we can open: ready ones first (its app is on the TV, or service_map sends it
 *    somewhere), then subscription before free before rent or buy, then TMDB's order.
 * 3. service_map sends it to an app              -> that app
 * 4. its app is on the TV                        -> that app
 *    missing: the catch-all app, else its Play Store page
 *    only services we can't open: the catch-all app, else "it streams on X, which Free My TV can't open"
 * </pre>
 */
final class Routing {
    private Routing() {}

    static final int APP = 0, STORE = 1, NONE = 2;
    /** Why nothing opens (kind NONE). */
    static final int UNAVAILABLE = 1, COULDNT_CHECK = 2, UNKNOWN_SERVICE = 3;

    static final class Target {
        final int kind, reason;
        final String pkg;       // APP, STORE
        final String uri;       // APP: the title's page in that app, or null for the app's home
        final String service;   // UNKNOWN_SERVICE: the service's name

        private Target(int kind, int reason, String pkg, String uri, String service) {
            this.kind = kind;
            this.reason = reason;
            this.pkg = pkg;
            this.uri = uri;
            this.service = service;
        }

        Target withUri(String address) {
            return new Target(kind, reason, pkg, address, service);
        }
    }

    /** One place a title streams. tier: 0 subscription, 1 free or with ads, 2 rent or buy. */
    static final class Offer {
        final String name;
        final int tier;

        Offer(String name, int tier) {
            this.name = name;
            this.tier = tier;
        }
    }

    interface Env {
        /** {name part, key, package} of the app we know for a service's name, or null. */
        String[] service(String providerName);
        boolean installed(String pkg);
        /** The app service_map sends this service to (when it is on the TV), or null. */
        String override(String providerName);
        /** The app for what can't be opened any other way (service_map's "*", when it is on the TV), or null. */
        String catchAll();
        /** In hide_services: never opens. */
        boolean hidden(String providerName);
    }

    /** `offers` is null when the lookup could not be done. */
    static Target decide(List<Offer> offers, Env env) {
        String catchAll = env.catchAll();
        if (offers == null) return catchAll != null ? app(catchAll) : none(COULDNT_CHECK, null);
        if (offers.isEmpty()) return catchAll != null ? app(catchAll) : none(UNAVAILABLE, null);
        int bestRank = Integer.MAX_VALUE;
        String bestApp = null, bestPkg = null;
        for (int i = 0; i < offers.size(); i++) {
            Offer o = offers.get(i);
            if (env.hidden(o.name)) continue;
            String over = env.override(o.name);
            String[] row = over == null ? env.service(o.name) : null;
            if (over == null && row == null) continue;      // a service we can't open
            boolean ready = over != null || env.installed(row[2]);
            int rank = (ready ? 0 : 1000) + o.tier * 100 + Math.min(i, 99);
            if (rank < bestRank) {
                bestRank = rank;
                bestApp = over;
                bestPkg = row == null ? null : row[2];
            }
        }
        if (bestRank == Integer.MAX_VALUE) return catchAll != null ? app(catchAll) : none(UNKNOWN_SERVICE, offers.get(0).name);
        if (bestApp != null) return app(bestApp);
        if (env.installed(bestPkg)) return app(bestPkg);
        return catchAll != null ? app(catchAll) : new Target(STORE, 0, bestPkg, null, null);
    }

    static Target app(String pkg) {
        return new Target(APP, 0, pkg, null, null);
    }

    static Target none(int reason, String service) {
        return new Target(NONE, reason, null, null, service);
    }
}
