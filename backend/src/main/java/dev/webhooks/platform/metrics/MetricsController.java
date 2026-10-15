package dev.webhooks.platform.metrics;

import dev.webhooks.platform.auth.AccountPrincipal;
import dev.webhooks.platform.common.TimeWindow;
import dev.webhooks.platform.endpoint.EndpointService;
import dev.webhooks.platform.metrics.MetricsDtos.Overview;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/metrics")
public class MetricsController {

    private final MetricsRepository metrics;
    private final EndpointService endpoints;

    public MetricsController(MetricsRepository metrics, EndpointService endpoints) {
        this.metrics = metrics;
        this.endpoints = endpoints;
    }

    /**
     * Overview numbers for one of the windows {@code 1h}, {@code 24h} (default) or {@code 7d}.
     * Definitions are in docs/PLAN.md §5.5. {@code endpointHealth} uses the same window.
     */
    @GetMapping("/overview")
    Overview overview(@AuthenticationPrincipal AccountPrincipal principal,
            @RequestParam(defaultValue = "24h") String window) {
        TimeWindow parsed = TimeWindow.parse(window);
        Overview overview = metrics.overview(principal.accountId(), parsed.name(), parsed.duration());
        return overview.withEndpointHealth(endpoints.healthList(principal.accountId(), parsed));
    }
}
