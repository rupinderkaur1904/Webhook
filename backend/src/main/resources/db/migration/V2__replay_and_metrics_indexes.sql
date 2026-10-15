-- Replay chain lookups (eligibility counts replays per chain; the detail view lists the chain).
CREATE INDEX deliveries_root_idx ON deliveries (root_delivery_id, created_at);
-- Overview metrics count terminal transitions inside a time window.
CREATE INDEX deliveries_account_delivered_idx ON deliveries (account_id, delivered_at) WHERE delivered_at IS NOT NULL;
CREATE INDEX deliveries_account_dead_lettered_idx ON deliveries (account_id, dead_lettered_at)
    WHERE dead_lettered_at IS NOT NULL;
