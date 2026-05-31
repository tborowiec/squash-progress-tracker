package org.borowiec.squashprogresstracker.security;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Resolves the authenticated user's id from the SecurityContext.
 * Every owned-data query MUST filter through currentUserId() — this is the
 * ownership-boundary primitive that prevents cross-player data leaks.
 */
@Component
public class CurrentUser {

    public Long currentUserId() {
        return principal().getId();
    }

    public AppUserDetails principal() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AppUserDetails details)) {
            throw new IllegalStateException("No authenticated user in SecurityContext");
        }
        return details;
    }
}
