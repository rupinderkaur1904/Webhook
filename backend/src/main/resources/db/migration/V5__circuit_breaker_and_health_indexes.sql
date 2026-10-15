-- Circuit breaker probe selection: an endpoint's earliest due delivery.
CREATE INDEX deliveries_endpoint_due_idx ON deliveries (endpoint_id, next_attempt_at)
    WHERE status IN ('PENDING', 'RETRYING');
-- Endpoint health: dead letters per endpoint in a window.
CREATE INDEX deliveries_endpoint_dead_lettered_idx ON deliveries (endpoint_id, dead_lettered_at)
    WHERE dead_lettered_at IS NOT NULL;
-- Maintenance loop: circuits waiting for their cool-down or for a probe.
CREATE INDEX endpoints_circuit_idx ON endpoints (circuit_retry_at)
    WHERE circuit_state <> 'CLOSED' AND deleted_at IS NULL;
