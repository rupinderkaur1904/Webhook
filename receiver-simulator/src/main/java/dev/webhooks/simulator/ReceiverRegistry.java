package dev.webhooks.simulator;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** In-memory named receivers, each with its own behaviour and request log. */
@Component
public class ReceiverRegistry {

    static final int MAX_LOGGED_REQUESTS = 500;

    private final Map<String, Receiver> receivers = new ConcurrentHashMap<>();

    public Receiver get(String name) {
        return receivers.computeIfAbsent(name, n -> new Receiver());
    }

    public void reset(String name) {
        receivers.remove(name);
    }

    /** How a receiver responds. {@code secret} enables signature verification. */
    public record Behaviour(int status, long delayMs, String secret) {
        static final Behaviour DEFAULT = new Behaviour(200, 0, null);
    }

    public record ReceivedRequest(
            int sequence,
            Instant receivedAt,
            String webhookId,
            String deliveryId,
            String attempt,
            String signatureResult,
            boolean duplicate,
            int respondedWith,
            Map<String, String> headers,
            String body) {}

    public static final class Receiver {

        private Behaviour behaviour = Behaviour.DEFAULT;
        private final Deque<ReceivedRequest> log = new ArrayDeque<>();
        private final Set<String> seenWebhookIds = new HashSet<>();
        private int sequence;

        public synchronized Behaviour behaviour() {
            return behaviour;
        }

        public synchronized void configure(Behaviour behaviour) {
            this.behaviour = behaviour;
        }

        /** Records a request and returns whether its webhook-id was seen before (receiver-side dedup). */
        public synchronized ReceivedRequest record(Instant at, String webhookId, String deliveryId, String attempt,
                String signatureResult, int respondedWith, Map<String, String> headers, String body) {
            boolean duplicate = webhookId != null && !seenWebhookIds.add(webhookId);
            ReceivedRequest request = new ReceivedRequest(++sequence, at, webhookId, deliveryId, attempt,
                    signatureResult, duplicate, respondedWith, headers, body);
            log.addLast(request);
            while (log.size() > MAX_LOGGED_REQUESTS) {
                log.removeFirst();
            }
            return request;
        }

        public synchronized List<ReceivedRequest> requests() {
            return new ArrayList<>(log);
        }
    }
}
