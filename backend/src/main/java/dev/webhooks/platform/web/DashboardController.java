package dev.webhooks.platform.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the React dashboard from the same origin as the API. The built app ({@code frontend/dist})
 * is packaged into the image under {@code classpath:/static}; its client-side routes are listed here
 * explicitly and all return {@code index.html}, so a reload or a shared link to
 * {@code /deliveries/<id>} works. Any other unknown path stays a 404.
 */
@Controller
public class DashboardController {

    /** Client-side routes of the dashboard (frontend/src/App.tsx). */
    public static final String[] ROUTES = {
        "/", "/overview", "/endpoints", "/endpoints/{id}", "/events", "/events/{id}",
        "/deliveries", "/deliveries/{id}", "/dead-letters", "/api-keys"
    };

    @GetMapping({"/", "/overview", "/endpoints", "/endpoints/{id}", "/events", "/events/{id}",
            "/deliveries", "/deliveries/{id}", "/dead-letters", "/api-keys"})
    String dashboard() {
        return "forward:/index.html";
    }
}
