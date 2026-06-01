package org.borowiec.squashprogresstracker;

/**
 * Single source of truth for the SPA's client-side routes — paths that React Router owns and that
 * must be both (a) permitted anonymously in {@code SecurityConfig} and (b) forwarded to
 * {@code index.html} so a deep-link refresh loads the SPA shell instead of 404ing.
 *
 * <p>Add a new client route here only. Both the security matcher
 * ({@code SecurityConfig.filterChain}) and the forwarding view controllers
 * ({@code SpaForwardingConfig}) consume this array, so they can never drift apart.
 * Deliberately does NOT include static assets (/, /index.html, /assets/**) — those are not
 * forwarded to the shell.
 */
public final class SpaRoutes {

    private SpaRoutes() {
    }

    public static final String[] CLIENT_ROUTES = {
        "/login", "/register", "/history", "/matches/**"
    };
}
