package dev.webhooks.simulator;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** In-memory named receivers, each with its own behaviour and request log. */
@Component
public class ReceiverRegistry {

    static final int MAX_LOGGED_REQUESTS = 200;
    /** Logged bodies are cut to this many characters (signatures are checked on the full body first). */
    static final int MAX_LOGGED_BODY_CHARS = 8 * 1024;
    /** Remembered webhook-ids per receiver, for duplicate detection. */
    static final int MAX_REMEMBERED_IDS = 10_000;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    static final int MAX_SCRIPT_STEPS = 100;
    static final long MAX_DELAY_MS = 120_000;

    private final Map<String, Receiver> receivers = new ConcurrentHashMap<>();
    private final int maxReceivers;

    /** {@code simulator.max-receivers} bounds memory on a publicly reachable simulator. */
    public ReceiverRegistry(@Value("${simulator.max-receivers:50}") int maxReceivers) {
        this.maxReceivers = maxReceivers;
    }

    /** The named receiver, created on first use. Names are 1-64 letters, digits, '-' or '_'. */
    public Receiver get(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "receiver names are 1-64 letters, digits, '-' or '_'");
        }
        Receiver existing = receivers.get(name);
        if (existing != null) {
            return existing;
        }
        synchronized (receivers) {
            if (!receivers.containsKey(name) && receivers.size() >= maxReceivers) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "the simulator holds at most " + maxReceivers + " receivers; delete one first");
            }
            return receivers.computeIfAbsent(name, n -> new Receiver());
        }
    }

    public void reset(String name) {
        receivers.remove(name);
    }

    /** One scripted response. */
    public record Step(int status, Long delayMs, Map<String, String> headers) {
        public Step {
            delayMs = delayMs == null ? 0L : delayMs;
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }
    }

    /**
     * How a receiver responds. Each request is answered by the first rule that applies:
     * <ol>
     *   <li>the next unused step of {@code script}, in order (e.g. {@code [500, 500, 200]}, or a
     *       {@code 429} with {@code Retry-After} followed by {@code 200});</li>
     *   <li>with probability {@code failureRate}, {@code failureStatus} (default 500), drawn from a
     *       random generator seeded with {@code seed} so a run can be repeated exactly;</li>
     *   <li>otherwise {@code status}, {@code delayMs} and {@code headers}.</li>
     * </ol>
     * {@code secret} enables signature verification. A delay longer than the platform's response
     * timeout simulates a receiver timeout.
     */
    public record Behaviour(Integer status, Long delayMs, String secret, Map<String, String> headers, List<Step> script,
            Double failureRate, Integer failureStatus, Long seed) {

        static final Behaviour DEFAULT = new Behaviour(200, 0L, null, Map.of(), List.of(), null, null, null);

        /** {@code status} defaults to 200 and {@code delayMs} to 0 when omitted. */
        public Behaviour {
            status = status == null ? 200 : status;
            delayMs = delayMs == null ? 0L : delayMs;
            headers = headers == null ? Map.of() : Map.copyOf(headers);
            script = script == null ? List.of() : List.copyOf(script);
        }

        /** Returns a description of what is wrong, or null if the behaviour is valid. */
        String validate() {
            if (!validStatus(status) || !validDelay(delayMs)) {
                return "status must be 100-599 and delayMs 0-" + MAX_DELAY_MS;
            }
            if (script.size() > MAX_SCRIPT_STEPS) {
                return "script may have at most " + MAX_SCRIPT_STEPS + " steps";
            }
            for (Step step : script) {
                if (!validStatus(step.status()) || !validDelay(step.delayMs())) {
                    return "every script step needs status 100-599 and delayMs 0-" + MAX_DELAY_MS;
                }
            }
            if (failureRate != null && (failureRate < 0 || failureRate > 1)) {
                return "failureRate must be between 0 and 1";
            }
            if (failureStatus != null && !validStatus(failureStatus)) {
                return "failureStatus must be 100-599";
            }
            if (secret != null && !SignatureVerifier.isValidSecret(secret)) {
                return "secret must be whsec_ followed by base64";
            }
            return null;
        }

        private static boolean validStatus(int status) {
            return status >= 100 && status <= 599;
        }

        private static boolean validDelay(long delayMs) {
            return delayMs >= 0 && delayMs <= MAX_DELAY_MS;
        }
    }

    /** The response chosen for one request, and which rule chose it. */
    public record Response(int status, long delayMs, Map<String, String> headers, String rule) {}

    public record ReceivedRequest(
            int sequence,
            Instant receivedAt,
            String webhookId,
            String deliveryId,
            String attempt,
            String signatureResult,
            boolean duplicate,
            int respondedWith,
            String rule,
            Map<String, String> headers,
            String body) {}

    public static final class Receiver {

        private Behaviour behaviour = Behaviour.DEFAULT;
        private int scriptPosition;
        private Random random = new Random();
        private final Deque<ReceivedRequest> log = new ArrayDeque<>();
        private final Set<String> seenWebhookIds = new LinkedHashSet<>();
        private int sequence;

        public synchronized Behaviour behaviour() {
            return behaviour;
        }

        /** Replaces the behaviour and restarts its script and random sequence. The request log is kept. */
        public synchronized void configure(Behaviour behaviour) {
            this.behaviour = behaviour;
            this.scriptPosition = 0;
            this.random = behaviour.seed() == null ? new Random() : new Random(behaviour.seed());
        }

        /** Chooses the response for the next request; advances the script. */
        public synchronized Response nextResponse() {
            List<Step> script = behaviour.script();
            if (scriptPosition < script.size()) {
                Step step = script.get(scriptPosition++);
                return new Response(step.status(), step.delayMs(), step.headers(),
                        "script " + scriptPosition + "/" + script.size());
            }
            Double rate = behaviour.failureRate();
            if (rate != null && rate > 0 && random.nextDouble() < rate) {
                int status = behaviour.failureStatus() == null ? 500 : behaviour.failureStatus();
                return new Response(status, behaviour.delayMs(), Map.of(), "intermittent failure");
            }
            return new Response(behaviour.status(), behaviour.delayMs(), behaviour.headers(), "default");
        }

        /** Records a request, noting whether its webhook-id was seen before (receiver-side dedup). */
        public synchronized ReceivedRequest record(Instant at, String webhookId, String deliveryId, String attempt,
                String signatureResult, Response response, Map<String, String> headers, String body) {
            boolean duplicate = webhookId != null && !seenWebhookIds.add(webhookId);
            if (seenWebhookIds.size() > MAX_REMEMBERED_IDS) {
                seenWebhookIds.remove(seenWebhookIds.iterator().next());
            }
            String logged = body != null && body.length() > MAX_LOGGED_BODY_CHARS
                    ? body.substring(0, MAX_LOGGED_BODY_CHARS) + "…[truncated]" : body;
            ReceivedRequest request = new ReceivedRequest(++sequence, at, webhookId, deliveryId, attempt,
                    signatureResult, duplicate, response.status(), response.rule(), headers, logged);
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
