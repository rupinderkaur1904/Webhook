package dev.webhooks.platform.health;

/** Endpoint health (docs/PLAN.md §7.5), evaluated in this order; the first rule that matches wins. */
public enum HealthStatus {
    /** The endpoint is disabled. */
    DISABLED,
    /**
     * The circuit is open or half-open, or the circuit's consecutive-failure count has reached
     * {@code failing-after} (default 5). Like the circuit, that count ignores 429s, non-retryable
     * answers and blocked destinations.
     */
    FAILING,
    /** Attempt success rate under 90% with at least 10 attempts in the window, or any dead letter in it. */
    DEGRADED,
    /** At least one attempt in the window and none of the above. */
    HEALTHY,
    /** No attempts in the window. */
    NO_DATA;

    /** Sort order for "worst first" lists: failing, degraded, healthy, no data, then disabled. */
    public int severity() {
        return switch (this) {
            case FAILING -> 0;
            case DEGRADED -> 1;
            case HEALTHY -> 2;
            case NO_DATA -> 3;
            case DISABLED -> 4;
        };
    }
}
