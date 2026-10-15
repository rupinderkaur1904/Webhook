package dev.webhooks.platform.metrics;

import dev.webhooks.platform.auth.AccountPrincipal;
import dev.webhooks.platform.common.ApiException;
import dev.webhooks.platform.metrics.MetricsDtos.Overview;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/metrics")
public class MetricsController {

    static final Map<String, Duration> WINDOWS =
            Map.of("1h", Duration.ofHours(1), "24h", Duration.ofHours(24), "7d", Duration.ofDays(7));

    private final MetricsRepository metrics;

    public MetricsController(MetricsRepository metrics) {
        this.metrics = metrics;
    }

    /**
     * Overview numbers for one of the windows {@code 1h}, {@code 24h} (default) or {@code 7d}.
     * Definitions are in docs/PLAN.md §5.5.
     */
    @GetMapping("/overview")
    Overview overview(@AuthenticationPrincipal AccountPrincipal principal,
            @RequestParam(defaultValue = "24h") String window) {
        Duration duration = WINDOWS.get(window);
        if (duration == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "window must be one of 1h, 24h, 7d");
        }
        return metrics.overview(principal.accountId(), window, duration);
    }
}
