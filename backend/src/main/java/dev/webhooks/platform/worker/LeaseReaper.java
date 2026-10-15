package dev.webhooks.platform.worker;

import dev.webhooks.platform.circuit.CircuitService;
import dev.webhooks.platform.config.WebhookProperties;
import io.micrometer.core.instrument.MeterRegistry;
import dev.webhooks.platform.delivery.DeliveryRepository;
import dev.webhooks.platform.delivery.DeliveryRepository.ExpiredLease;
import dev.webhooks.platform.retry.RetryPolicy;
import dev.webhooks.platform.retry.RetryPolicy.Decision;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Recovers deliveries whose worker stopped reporting (docs/PLAN.md §7.2). A delivery stays
 * {@code IN_FLIGHT} with a lease while a worker sends it; if the worker crashes, hangs, or is shut
 * down before recording a result, the lease expires and this reaper:
 * <ol>
 *   <li>marks the open attempt {@code ABANDONED} with outcome {@code LEASE_EXPIRED};</li>
 *   <li>moves the delivery to {@code RETRYING} with the normal backoff, or {@code DEAD_LETTERED}
 *       ({@code RETRIES_EXHAUSTED}) if that was its last attempt, or {@code CANCELLED} if its
 *       endpoint was deleted meanwhile.</li>
 * </ol>
 * Abandoned attempts count against the budget, so a payload that crashes workers cannot loop
 * forever. Every instance runs a reaper; {@code SKIP LOCKED} keeps them from processing the same row,
 * and the lease-token guard keeps a reaped delivery safe from a worker that reports late.
 *
 * <p>The same loop moves circuit breakers whose cool-down has passed to HALF_OPEN and picks their
 * probe delivery ({@link CircuitService#startProbes}).
 */
@Component
public class LeaseReaper implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(LeaseReaper.class);

    private final WebhookProperties.Worker config;
    private final DeliveryRepository deliveries;
    private final RetryPolicy retryPolicy;
    private final TransactionTemplate tx;
    private final CircuitService circuits;
    private final MeterRegistry meters;

    private volatile boolean running;
    private Thread thread;

    public LeaseReaper(WebhookProperties properties, DeliveryRepository deliveries, RetryPolicy retryPolicy,
            TransactionTemplate tx, CircuitService circuits, MeterRegistry meters) {
        this.circuits = circuits;
        this.meters = meters;
        this.config = properties.worker();
        this.deliveries = deliveries;
        this.retryPolicy = retryPolicy;
        this.tx = tx;
    }

    /** Recovers every currently expired lease, in batches. Returns how many deliveries were recovered. */
    public int reapExpired() {
        int total = 0;
        while (true) {
            Integer recovered = tx.execute(status -> reapBatch());
            total += recovered == null ? 0 : recovered;
            if (recovered == null || recovered < config.reaperBatchSize()) {
                return total;
            }
        }
    }

    private int reapBatch() {
        List<ExpiredLease> expired = deliveries.lockExpiredLeases(config.reaperBatchSize());
        int recovered = 0;
        for (ExpiredLease lease : expired) {
            String message = "Worker " + lease.leaseOwner() + " did not report a result before its lease expired";
            deliveries.abandonAttempt(lease, message);
            Decision decision = retryPolicy.decide(AttemptOutcome.LEASE_EXPIRED, null, null, lease.attemptCount(),
                    lease.maxAttempts());
            Duration delay = decision instanceof Decision.Retry retry ? retry.delay() : null;
            if (deliveries.releaseExpiredLease(lease, delay, "Lease expired: no result was recorded for attempt "
                    + lease.attemptCount())) {
                recovered++;
                meters.counter("webhooks.leases.expired").increment();
                log.warn("Recovered delivery {} from expired lease of {} (attempt {}/{}): {}", lease.deliveryId(),
                        lease.leaseOwner(), lease.attemptCount(), lease.maxAttempts(),
                        lease.endpointDeleted() ? "cancelled, endpoint deleted"
                                : delay == null ? "dead-lettered, retries exhausted" : "retry in " + delay.toMillis() + " ms");
            }
        }
        return recovered;
    }

    @Override
    public void start() {
        if (!config.enabled()) {
            return;
        }
        running = true;
        thread = Thread.ofVirtual().name("lease-reaper").start(this::loop);
        log.info("Lease reaper started (every {}, batch {})", config.reaperInterval(), config.reaperBatchSize());
    }

    private void loop() {
        while (running) {
            try {
                reapExpired();
            } catch (RuntimeException e) {
                log.error("Lease reaper run failed; retrying after the reaper interval", e);
            }
            try {
                circuits.startProbes(config.reaperBatchSize());
            } catch (RuntimeException e) {
                log.error("Circuit probe selection failed; retrying after the reaper interval", e);
            }
            try {
                Thread.sleep(config.reaperInterval());
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    @Override
    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
