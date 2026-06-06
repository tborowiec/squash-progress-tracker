package org.borowiec.squashprogresstracker;

import java.util.Arrays;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Forwards the SPA's client-side routes to {@code index.html} so deep links / refreshes load the
 * React shell rather than 404ing. Routes come from {@link SpaRoutes#CLIENT_ROUTES} — the same list
 * {@code SecurityConfig} permits — so the forwarding and security rules cannot drift apart.
 * The {@code /api/**} and {@code /actuator/**} paths are never listed, so they are never swallowed.
 */
@Configuration
public class SpaForwardingConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        Arrays.stream(SpaRoutes.CLIENT_ROUTES)
                .forEach(route -> registry.addViewController(route).setViewName("forward:/index.html"));
    }
}
