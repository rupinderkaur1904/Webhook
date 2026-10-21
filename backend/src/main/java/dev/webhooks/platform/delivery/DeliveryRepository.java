package dev.webhooks.platform.delivery;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import dev.webhooks.platform.config.WebhookProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Write-side queue operations on deliveries and attempts. */
@Repository
public class DeliveryRepository {

    private final JdbcClient jdbc;

    private final boolean circuitEnabled;

    public DeliveryRepository(JdbcClient jdbc, WebhookProperties properties) {
        this.jdbc = jdbc;
        this.circuitEnabled = properties.circuit().enabled();
    }

    public void insertPending(UUID id, UUID accountId, UUID eventId, UUID endpointId, int maxAttempts) {
        jdbc.sql("""
                        INSERT INTO deliveries (id, account_id, event_id, endpoint_id, status, max_attempts,
                                                next_attempt_at, root_delivery_id)
                        VALUES (:id, :account, :event, :endpoint, 'PENDING', :maxAttempts, now(), :id)
                        """)
                .param("id", id)
                .param("account", accountId)
                .param("event", eventId)
                .param("endpoint", endpointId)
                .param("maxAttempts", maxAttempts)
                .update();
    }

    /**
     * Claims up to {@code limit} due deliveries for this worker.
     *
     * <p>{@code FOR UPDATE OF d SKIP LOCKED} row-locks the selected deliveries until this transaction
     * commits; concurrent claimers skip locked rows instead of waiting, and once committed the rows
     * are IN_FLIGHT and no longer match. Concurrent claimers therefore receive disjoint sets. Each
     * claim gets a fresh lease token and expiry, and a STARTED attempt row is written in the same
     * transaction so the attempt is visible even if the worker dies mid-request.
     */
    @Transactional
    public List<ClaimedDelivery> claimDue(int limit, String workerId, Duration leaseDuration) {
        record Claimed(UUID id, UUID accountId, UUID eventId, UUID endpointId, int attemptNumber, int maxAttempts,
                UUID leaseToken) {}

        List<Claimed> claimed = jdbc.sql("""
                        WITH due AS (
                            SELECT d.id
                            FROM deliveries d
                            JOIN endpoints e ON e.id = d.endpoint_id
                            WHERE d.status IN ('PENDING', 'RETRYING')
                              AND d.next_attempt_at <= now()
                              AND e.enabled AND e.deleted_at IS NULL
                              -- Circuit breaker: nothing is claimed for an OPEN endpoint, and only the
                              -- probe for a HALF_OPEN one. Skipped deliveries keep their status and budget.
                              AND (NOT :circuitEnabled OR e.circuit_state = 'CLOSED'
                                   OR (e.circuit_state = 'HALF_OPEN' AND e.half_open_probe_id = d.id))
                            ORDER BY d.next_attempt_at
                            LIMIT :limit
                            FOR UPDATE OF d SKIP LOCKED
                        )
                        UPDATE deliveries d
                        SET status = 'IN_FLIGHT',
                            attempt_count = d.attempt_count + 1,
                            lease_token = gen_random_uuid(),
                            lease_owner = :owner,
                            lease_expires_at = now() + make_interval(secs => :leaseSeconds),
                            last_attempt_at = now(),
                            next_attempt_at = NULL,
                            updated_at = now()
                        FROM due
                        WHERE d.id = due.id
                        RETURNING d.id, d.account_id, d.event_id, d.endpoint_id, d.attempt_count, d.max_attempts,
                                  d.lease_token
                        """)
                .param("limit", limit)
                .param("circuitEnabled", circuitEnabled)
                .param("owner", workerId)
                .param("leaseSeconds", (double) leaseDuration.toMillis() / 1000.0)
                .query((rs, n) -> new Claimed(
                        rs.getObject("id", UUID.class),
                        rs.getObject("account_id", UUID.class),
                        rs.getObject("event_id", UUID.class),
                        rs.getObject("endpoint_id", UUID.class),
                        rs.getInt("attempt_count"),
                        rs.getInt("max_attempts"),
                        rs.getObject("lease_token", UUID.class)))
                .list();

        List<ClaimedDelivery> result = new ArrayList<>(claimed.size());
        for (Claimed c : claimed) {
            record Started(UUID attemptId, String url) {}
            Started started = jdbc.sql("""
                            INSERT INTO delivery_attempts (delivery_id, account_id, endpoint_id, attempt_number, status,
                                                           request_url, started_at)
                            SELECT :delivery, :account, e.id, :number, 'STARTED', e.url, now()
                            FROM endpoints e WHERE e.id = :endpoint
                            RETURNING id, request_url
                            """)
                    .param("delivery", c.id())
                    .param("account", c.accountId())
                    .param("endpoint", c.endpointId())
                    .param("number", c.attemptNumber())
                    .query((rs, n) -> new Started(rs.getObject("id", UUID.class), rs.getString("request_url")))
                    .single();
            result.add(new ClaimedDelivery(c.id(), c.accountId(), c.eventId(), c.endpointId(), started.attemptId(),
                    c.attemptNumber(), c.maxAttempts(), c.leaseToken(), started.url()));
        }
        return result;
    }

    /**
     * Finishes the attempt row with what the worker observed. Always applied, even for a stale lease:
     * if the reaper already marked the attempt {@code ABANDONED}, the observation is still stored but
     * the row stays abandoned and is flagged {@code reported_after_lease_expiry}, because the delivery
     * has moved on and this result was not applied to it.
     */
    public void finishAttempt(UUID attemptId, String status, AttemptRecord record) {
        jdbc.sql("""
                        UPDATE delivery_attempts
                        SET status = CASE WHEN status = 'ABANDONED' THEN 'ABANDONED' ELSE :status END,
                            reported_after_lease_expiry = (status = 'ABANDONED'),
                            outcome = :outcome, retryable = :retryable,
                            response_status = :responseStatus, response_headers = CAST(:headers AS jsonb),
                            response_body_excerpt = :body, error_message = :error,
                            retry_after_seconds = :retryAfter, finished_at = now(), duration_ms = :duration
                        WHERE id = :id AND (status = 'STARTED' OR (status = 'ABANDONED' AND NOT reported_after_lease_expiry))
                        """)
                .param("id", attemptId)
                .param("status", status)
                .param("outcome", record.outcome())
                .param("retryable", record.retryable())
                .param("responseStatus", record.responseStatus())
                .param("headers", record.responseHeadersJson())
                .param("body", record.responseBodyExcerpt())
                .param("error", record.errorMessage())
                .param("retryAfter", record.retryAfterSeconds())
                .param("duration", record.durationMs())
                .update();
    }

    /** An in-flight delivery whose lease has expired, locked for recovery. */
    public record ExpiredLease(UUID deliveryId, UUID leaseToken, String leaseOwner, int attemptCount, int maxAttempts,
            boolean endpointDeleted) {}

    /**
     * Locks up to {@code limit} in-flight deliveries whose lease has expired. Must run inside a
     * transaction; {@code SKIP LOCKED} lets several reapers (one per instance) share the work, and
     * rows a worker is finishing right now are skipped until its result transaction commits.
     */
    public List<ExpiredLease> lockExpiredLeases(int limit) {
        return jdbc.sql("""
                        SELECT d.id, d.lease_token, d.lease_owner, d.attempt_count, d.max_attempts,
                               e.deleted_at IS NOT NULL AS endpoint_deleted
                        FROM deliveries d
                        JOIN endpoints e ON e.id = d.endpoint_id
                        WHERE d.status = 'IN_FLIGHT' AND d.lease_expires_at < now()
                        ORDER BY d.lease_expires_at
                        LIMIT :limit
                        FOR UPDATE OF d SKIP LOCKED
                        """)
                .param("limit", limit)
                .query((rs, n) -> new ExpiredLease(
                        rs.getObject("id", UUID.class),
                        rs.getObject("lease_token", UUID.class),
                        rs.getString("lease_owner"),
                        rs.getInt("attempt_count"),
                        rs.getInt("max_attempts"),
                        rs.getBoolean("endpoint_deleted")))
                .list();
    }

    /** Marks the open attempt of an expired lease as abandoned. */
    public void abandonAttempt(ExpiredLease lease, String message) {
        jdbc.sql("""
                        UPDATE delivery_attempts
                        SET status = 'ABANDONED', outcome = 'LEASE_EXPIRED', retryable = true, error_message = :message,
                            finished_at = now(),
                            duration_ms = LEAST(extract(epoch FROM now() - started_at) * 1000, 2147483647)::int
                        WHERE delivery_id = :id AND attempt_number = :number AND status = 'STARTED'
                        """)
                .param("id", lease.deliveryId())
                .param("number", lease.attemptCount())
                .param("message", message)
                .update();
    }

    /**
     * Moves a delivery out of an expired lease: {@code RETRYING} after {@code delay},
     * {@code DEAD_LETTERED} when {@code delay} is null, or {@code CANCELLED} if its endpoint was deleted.
     * Guarded by the lease token like every other transition.
     */
    public boolean releaseExpiredLease(ExpiredLease lease, Duration delay, String error) {
        String status = lease.endpointDeleted() ? "CANCELLED" : delay == null ? "DEAD_LETTERED" : "RETRYING";
        return jdbc.sql("""
                        UPDATE deliveries
                        SET status = :status,
                            next_attempt_at = CASE WHEN :status = 'RETRYING' THEN now() + make_interval(secs => :delaySeconds) END,
                            dead_letter_reason = CASE WHEN :status = 'DEAD_LETTERED' THEN 'RETRIES_EXHAUSTED' END,
                            dead_lettered_at = CASE WHEN :status = 'DEAD_LETTERED' THEN now() END,
                            last_response_status = NULL, last_error = :error,
                            lease_token = NULL, lease_owner = NULL, lease_expires_at = NULL, updated_at = now()
                        WHERE id = :id AND status = 'IN_FLIGHT' AND lease_token = :token
                        """)
                .param("status", status)
                .param("delaySeconds", delay == null ? 0.0 : delay.toMillis() / 1000.0)
                .param("error", error)
                .param("id", lease.deliveryId())
                .param("token", lease.leaseToken())
                .update() == 1;
    }

    /**
     * Cancels queued (PENDING / RETRYING) deliveries whose endpoint has been deleted. Endpoint
     * deletion cancels the queue in its own transaction, but a delivery can re-enter the queue
     * concurrently without seeing the deletion: a retry recorded, a lease reaped, or an event ingested
     * while the deletion commits. Such a delivery is never claimed (deleted endpoints are excluded),
     * so without this sweep it would stay queued forever. Returns how many were cancelled.
     */
    public int cancelQueuedForDeletedEndpoints() {
        return jdbc.sql("""
                        UPDATE deliveries d
                        SET status = 'CANCELLED', next_attempt_at = NULL, updated_at = now()
                        FROM endpoints e
                        WHERE e.id = d.endpoint_id AND e.deleted_at IS NOT NULL
                          AND d.status IN ('PENDING', 'RETRYING')
                        """)
                .update();
    }

    public boolean markDelivered(ClaimedDelivery claim, Integer responseStatus) {
        return jdbc.sql("""
                        UPDATE deliveries
                        SET status = 'DELIVERED', delivered_at = now(), last_response_status = :responseStatus,
                            last_error = NULL, lease_token = NULL, lease_owner = NULL, lease_expires_at = NULL,
                            updated_at = now()
                        WHERE id = :id AND status = 'IN_FLIGHT' AND lease_token = :token
                        """)
                .param("id", claim.deliveryId())
                .param("token", claim.leaseToken())
                .param("responseStatus", responseStatus)
                .update() == 1;
    }

    public boolean markDeadLettered(ClaimedDelivery claim, DeadLetterReason reason, Integer responseStatus,
            String error) {
        return jdbc.sql("""
                        UPDATE deliveries
                        SET status = 'DEAD_LETTERED', dead_lettered_at = now(), dead_letter_reason = :reason,
                            last_response_status = :responseStatus, last_error = :error,
                            lease_token = NULL, lease_owner = NULL, lease_expires_at = NULL, updated_at = now()
                        WHERE id = :id AND status = 'IN_FLIGHT' AND lease_token = :token
                        """)
                .param("id", claim.deliveryId())
                .param("token", claim.leaseToken())
                .param("reason", reason.name())
                .param("responseStatus", responseStatus)
                .param("error", error)
                .update() == 1;
    }

    /**
     * Schedules the next attempt. If the endpoint was deleted while the attempt was in flight the
     * delivery is cancelled instead, so no further attempts are made to a removed endpoint.
     */
    public boolean markRetrying(ClaimedDelivery claim, Duration delay, Integer responseStatus, String error) {
        return jdbc.sql("""
                        UPDATE deliveries d
                        SET status = CASE WHEN e.deleted_at IS NULL THEN 'RETRYING' ELSE 'CANCELLED' END,
                            next_attempt_at = CASE WHEN e.deleted_at IS NULL
                                                   THEN now() + make_interval(secs => :delaySeconds) END,
                            last_response_status = :responseStatus, last_error = :error,
                            lease_token = NULL, lease_owner = NULL, lease_expires_at = NULL, updated_at = now()
                        FROM endpoints e
                        WHERE d.id = :id AND d.status = 'IN_FLIGHT' AND d.lease_token = :token AND e.id = d.endpoint_id
                        """)
                .param("id", claim.deliveryId())
                .param("token", claim.leaseToken())
                .param("delaySeconds", delay.toMillis() / 1000.0)
                .param("responseStatus", responseStatus)
                .param("error", error)
                .update() == 1;
    }

    /** What a finished attempt observed, in storable form. */
    public record AttemptRecord(
            String outcome,
            Boolean retryable,
            Integer responseStatus,
            String responseHeadersJson,
            String responseBodyExcerpt,
            String errorMessage,
            Integer retryAfterSeconds,
            Integer durationMs) {}
}
