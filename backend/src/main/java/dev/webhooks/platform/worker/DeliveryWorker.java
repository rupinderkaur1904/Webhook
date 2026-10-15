package dev.webhooks.platform.worker;

import dev.webhooks.platform.config.WebhookProperties;
import dev.webhooks.platform.delivery.ClaimedDelivery;
import dev.webhooks.platform.delivery.DeliveryRepository;
import java.net.InetAddress;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Background delivery worker. A single poller thread claims due deliveries in batches and hands each
 * one to a virtual thread; a semaphore caps concurrent HTTP requests. When a batch comes back full
 * the poller immediately polls again, otherwise it sleeps for {@code poll-interval}.
 *
 * <p>All scheduling state lives in PostgreSQL, so a restart needs no special recovery for PENDING or
 * RETRYING work: due rows are simply claimable again.
 */
@Component
public class DeliveryWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);

    private final WebhookProperties.Worker config;
    private final DeliveryRepository deliveries;
    private final DeliveryProcessor processor;
    private final String workerId;
    private final Semaphore permits;

    private volatile boolean running;
    private Thread poller;
    private ExecutorService executor;

    public DeliveryWorker(WebhookProperties properties, DeliveryRepository deliveries, DeliveryProcessor processor) {
        this.config = properties.worker();
        this.deliveries = deliveries;
        this.processor = processor;
        this.workerId = hostName() + "/" + UUID.randomUUID().toString().substring(0, 8);
        this.permits = new Semaphore(config.maxConcurrency());
    }

    @Override
    public void start() {
        if (!config.enabled()) {
            log.info("Delivery worker disabled (webhooks.worker.enabled=false)");
            return;
        }
        executor = Executors.newVirtualThreadPerTaskExecutor();
        running = true;
        poller = Thread.ofPlatform().name("delivery-poller").start(this::pollLoop);
        log.info("Delivery worker {} started (concurrency={}, lease={})", workerId, config.maxConcurrency(),
                config.leaseDuration());
    }

    private void pollLoop() {
        while (running) {
            try {
                int claimed = pollOnce();
                if (claimed == 0 || permits.availablePermits() == 0) {
                    Thread.sleep(config.pollInterval());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                log.error("Delivery poll failed; retrying after poll interval", e);
                try {
                    Thread.sleep(config.pollInterval());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** Claims up to the free concurrency and dispatches the claimed deliveries. Returns how many. */
    int pollOnce() throws InterruptedException {
        int capacity = Math.min(config.batchSize(), permits.availablePermits());
        if (capacity == 0) {
            return 0;
        }
        List<ClaimedDelivery> claimed = deliveries.claimDue(capacity, workerId, config.leaseDuration());
        for (ClaimedDelivery claim : claimed) {
            permits.acquire();
            executor.submit(() -> {
                try {
                    processor.process(claim);
                } catch (RuntimeException e) {
                    // The lease expires and the delivery is recovered; never lose the error silently.
                    log.error("Delivery {} attempt {} failed unexpectedly", claim.deliveryId(), claim.attemptNumber(), e);
                } finally {
                    permits.release();
                }
            });
        }
        return claimed.size();
    }

    @Override
    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        poller.interrupt();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(config.shutdownTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("In-flight deliveries did not finish within {}; their leases will expire and they will be retried",
                        config.shutdownTimeout());
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("Delivery worker {} stopped", workerId);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    public String workerId() {
        return workerId;
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "worker";
        }
    }
}
