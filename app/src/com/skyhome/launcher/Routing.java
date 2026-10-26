package com.skyhome.launcher;

import java.util.List;

/**
 * Where a title opens, decided from where it streams. No Android in here, so it can be tested on its own;
 * Launcher gathers the facts and runs it. Stremio only counts when it is on the TV.
 *
 * <pre>
 * 0. The lookup could not be done            -> Stremio if it takes what is missing, else "couldn't check"
 * 5. TMDB answered: on no service            -> Stremio if it takes what is missing, else "unavailable in your region"
 * 1. Pick one service among the ones we can open: subscription before free before rent or buy; among the same kind,
 *    ready ones first (its app is on the TV, or it goes to Stremio), then TMDB's order. So a missing Netflix is not
 *    skipped for a rented copy on an app we have: it goes to Stremio or the Play Store, as the card says.
 * 3. the service goes to Stremio             -> Stremio
 * 4. its app is on the TV                    -> that app
 *    missing: Stremio if it takes what is missing, else its Play Store page
 *    only services we can't open: Stremio if it takes them or what is missing, else "it streams on X, which Sky Home Launcher can't open"
 * </pre>
 */
final class Routing {
    private Routing() {}

    static final String STREMIO = "com.stremio.one";
    /** The Stremio screen's name for every service we have no app for. */
    static final String OTHER = "other";

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
        final String logo;      // TMDB's logo path ("/abc.jpg") for the card's icon, or ""
        final int providerId;   // TMDB's provider id: tells apart services sharing a name (Apple TV the service vs. the Store)

        Offer(String name, int tier) {
            this(name, tier, "", 0);
        }

        Offer(String name, int tier, String logo) {
            this(name, tier, logo, 0);
        }

        Offer(String name, int tier, String logo, int providerId) {
            this.name = name;
            this.tier = tier;
            this.logo = logo;
            this.providerId = providerId;
        }
    }

    interface Env {
        /** {name part, key, package} of the app we know for a service's name, or null. */
        String[] service(String providerName, int providerId);
        boolean installed(String pkg);
        /** Stremio is on the TV: without it every "Stremio" choice is off. */
        boolean stremio();
        /** The person sends this service (its key, or OTHER) to Stremio. */
        boolean toStremio(String key);
        /** "Use Stremio for what is missing" is on. */
        boolean stremioForMissing();
        /** In hide_services: never opens. */
        boolean hidden(String providerName, int providerId);
    }

    /** `offers` is null when the lookup could not be done. */
    static Target decide(List<Offer> offers, Env env) {
        boolean stremio = env.stremio();
        boolean missing = stremio && env.stremioForMissing();
        if (offers == null) return missing ? app(STREMIO) : none(COULDNT_CHECK, null);
        if (offers.isEmpty()) return missing ? app(STREMIO) : none(UNAVAILABLE, null);
        int bestRank = Integer.MAX_VALUE;
        String bestPkg = null;      // null with a best rank: Stremio
        for (int i = 0; i < offers.size(); i++) {
            Offer o = offers.get(i);
            if (env.hidden(o.name, o.providerId)) continue;
            String[] row = env.service(o.name, o.providerId);
            boolean sent = stremio && env.toStremio(row == null ? OTHER : row[1]);
            if (row == null && !sent) continue;      // a service we can't open
            int rank = rank(o, i, sent || env.installed(row[2]));
            if (rank < bestRank) {
                bestRank = rank;
                bestPkg = sent ? null : row[2];
            }
        }
        if (bestRank == Integer.MAX_VALUE) return missing ? app(STREMIO) : none(UNKNOWN_SERVICE, offers.get(0).name);
        if (bestPkg == null) return app(STREMIO);
        if (env.installed(bestPkg)) return app(bestPkg);
        return missing ? app(STREMIO) : new Target(STORE, 0, bestPkg, null, null);
    }

    /**
     * Lower wins: subscription before free before rent or buy, so the best service is the one the card shows even when its
     * app is missing (that goes to Stremio or the Play Store); among the same kind, a service we can open now first, then
     * TMDB's order.
     */
    static int rank(Offer o, int index, boolean ready) {
        return o.tier * 1000 + (ready ? 0 : 100) + Math.min(index, 99);
    }

    /** Its app is on the TV, or the person sends it to Stremio (which is on the TV). */
    static boolean ready(Offer o, Env env) {
        String[] row = env.service(o.name, o.providerId);
        return (env.stremio() && env.toStremio(row == null ? OTHER : row[1])) || (row != null && env.installed(row[2]));
    }

    static Target app(String pkg) {
        return new Target(APP, 0, pkg, null, null);
    }

    static Target none(int reason, String service) {
        return new Target(NONE, reason, null, null, service);
    }
}
