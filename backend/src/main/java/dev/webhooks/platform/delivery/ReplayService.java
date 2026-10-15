package dev.webhooks.platform.delivery;

import dev.webhooks.platform.common.ApiException;
import dev.webhooks.platform.config.WebhookProperties;
import dev.webhooks.platform.delivery.DeliveryDtos.DeliverySummary;
import dev.webhooks.platform.delivery.DeliveryDtos.ReplayEligibility;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manual replay of dead-lettered deliveries (docs/PLAN.md §7.7).
 *
 * <p>A replay is a new {@code PENDING} delivery linked to the one it replays, with a fresh attempt
 * budget and the same event (so the same {@code webhook-id}). The dead-lettered row and its attempts
 * are never modified. Replays are only ever started by a person, so a delivery that keeps failing
 * cannot loop.
 */
@Service
public class ReplayService {

    private static final Logger log = LoggerFactory.getLogger(ReplayService.class);

    private final JdbcClient jdbc;
    private final DeliveryQueryRepository queries;
    private final WebhookProperties properties;

    public ReplayService(JdbcClient jdbc, DeliveryQueryRepository queries, WebhookProperties properties) {
        this.jdbc = jdbc;
        this.queries = queries;
        this.properties = properties;
    }

    /** Why a delivery cannot be replayed right now. */
    public enum IneligibleReason {
        NOT_DEAD_LETTERED,
        ENDPOINT_DELETED,
        ENDPOINT_DISABLED,
        REPLAY_IN_PROGRESS,
        REPLAY_LIMIT_REACHED
    }

    /** Eligibility as shown in the delivery detail; empty if the delivery does not exist for this account. */
    public Optional<ReplayEligibility> eligibility(UUID accountId, UUID deliveryId) {
        return state(accountId, deliveryId, false).map(this::evaluate);
    }

    /**
     * Creates the replay. Runs in one transaction that holds a share lock on the endpoint row, so a
     * concurrent endpoint deletion either happens first (and the replay is refused) or waits and then
     * cancels the new replay along with the endpoint's other queued deliveries. Two concurrent replays
     * of the same chain cannot both succeed: the {@code deliveries_active_chain_uq} index allows one
     * unfinished delivery per chain, and the loser is told a replay is already in progress.
     */
    @Transactional
    public DeliverySummary replay(UUID accountId, UUID deliveryId) {
        ChainState state = state(accountId, deliveryId, true).orElseThrow(() -> ApiException.notFound("Delivery"));
        ReplayEligibility eligibility = evaluate(state);
        if (!eligibility.eligible()) {
            throw notEligible(IneligibleReason.valueOf(eligibility.reason()));
        }
        UUID replayId = UUID.randomUUID();
        try {
            jdbc.sql("""
                            INSERT INTO deliveries (id, account_id, event_id, endpoint_id, status, max_attempts,
                                                    next_attempt_at, replay_of_delivery_id, root_delivery_id,
                                                    replayed_by_user_id)
                            SELECT :id, d.account_id, d.event_id, d.endpoint_id, 'PENDING', :maxAttempts, now(),
                                   d.id, d.root_delivery_id, :user
                            FROM deliveries d
                            WHERE d.id = :source AND d.account_id = :account
                            """)
                    .param("id", replayId)
                    .param("maxAttempts", properties.retry().maxAttempts())
                    .param("user", accountId)
                    .param("source", deliveryId)
                    .param("account", accountId)
                    .update();
        } catch (DuplicateKeyException e) {
            throw notEligible(IneligibleReason.REPLAY_IN_PROGRESS);
        }
        log.info("Replay {} created for dead-lettered delivery {} (chain {})", replayId, deliveryId, state.rootId());
        return queries.find(accountId, replayId).orElseThrow().summary();
    }

    private ReplayEligibility evaluate(ChainState s) {
        IneligibleReason reason = null;
        if (!s.status().equals(DeliveryStatus.DEAD_LETTERED.name())) {
            reason = IneligibleReason.NOT_DEAD_LETTERED;
        } else if (s.endpointDeleted()) {
            reason = IneligibleReason.ENDPOINT_DELETED;
        } else if (!s.endpointEnabled()) {
            reason = IneligibleReason.ENDPOINT_DISABLED;
        } else if (s.chainActive()) {
            reason = IneligibleReason.REPLAY_IN_PROGRESS;
        } else if (s.replayCount() >= properties.replay().maxPerDelivery()) {
            reason = IneligibleReason.REPLAY_LIMIT_REACHED;
        }
        return new ReplayEligibility(reason == null, reason == null ? null : reason.name(),
                s.replayCount(), properties.replay().maxPerDelivery());
    }

    private Optional<ChainState> state(UUID accountId, UUID deliveryId, boolean lockEndpoint) {
        if (lockEndpoint) {
            List<UUID> endpoint = jdbc.sql("""
                            SELECT e.id FROM endpoints e JOIN deliveries d ON d.endpoint_id = e.id
                            WHERE d.id = :id AND d.account_id = :account
                            FOR SHARE OF e
                            """)
                    .param("id", deliveryId)
                    .param("account", accountId)
                    .query(UUID.class)
                    .list();
            if (endpoint.isEmpty()) {
                return Optional.empty();
            }
        }
        return jdbc.sql("""
                        SELECT d.status, d.root_delivery_id, e.enabled, e.deleted_at IS NOT NULL AS endpoint_deleted,
                               EXISTS (SELECT 1 FROM deliveries a
                                       WHERE a.root_delivery_id = d.root_delivery_id
                                         AND a.status IN ('PENDING', 'RETRYING', 'IN_FLIGHT')) AS chain_active,
                               (SELECT count(*) FROM deliveries r
                                WHERE r.root_delivery_id = d.root_delivery_id
                                  AND r.replay_of_delivery_id IS NOT NULL) AS replay_count
                        FROM deliveries d
                        JOIN endpoints e ON e.id = d.endpoint_id
                        WHERE d.id = :id AND d.account_id = :account
                        """)
                .param("id", deliveryId)
                .param("account", accountId)
                .query((rs, n) -> new ChainState(rs.getString("status"), rs.getObject("root_delivery_id", UUID.class),
                        rs.getBoolean("enabled"), rs.getBoolean("endpoint_deleted"), rs.getBoolean("chain_active"),
                        rs.getInt("replay_count")))
                .optional();
    }

    private static ApiException notEligible(IneligibleReason reason) {
        String detail = switch (reason) {
            case NOT_DEAD_LETTERED -> "Only dead-lettered deliveries can be replayed";
            case ENDPOINT_DELETED -> "The endpoint has been deleted";
            case ENDPOINT_DISABLED -> "The endpoint is disabled; enable it before replaying";
            case REPLAY_IN_PROGRESS -> "A delivery for this event and endpoint is already in progress";
            case REPLAY_LIMIT_REACHED -> "This delivery has been replayed the maximum number of times";
        };
        return new ApiException(HttpStatus.CONFLICT, "REPLAY_NOT_ELIGIBLE", detail, List.of(),
                Map.of("reason", reason.name()));
    }

    private record ChainState(String status, UUID rootId, boolean endpointEnabled, boolean endpointDeleted,
            boolean chainActive, int replayCount) {}
}
