-- Initial schema for the webhook delivery platform.
-- The deliveries table is both the system of record and the work queue; see docs/PLAN.md §4.

CREATE EXTENSION IF NOT EXISTS citext;

CREATE TABLE users (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email         citext      NOT NULL UNIQUE,
    password_hash text        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE api_keys (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id   uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name         text        NOT NULL CHECK (length(name) BETWEEN 1 AND 100),
    key_prefix   text        NOT NULL,
    key_hash     bytea       NOT NULL UNIQUE,
    last_used_at timestamptz,
    revoked_at   timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX api_keys_account_idx ON api_keys (account_id, created_at DESC);

CREATE TABLE endpoints (
    id                         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id                 uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name                       text        NOT NULL CHECK (length(name) BETWEEN 1 AND 100),
    description                text CHECK (length(description) <= 500),
    url                        text        NOT NULL CHECK (length(url) <= 2048),
    event_types                text[]      NOT NULL DEFAULT '{}',
    enabled                    boolean     NOT NULL DEFAULT true,
    secret_ciphertext          bytea       NOT NULL,
    secret_prefix              text        NOT NULL,
    previous_secret_ciphertext bytea,
    previous_secret_expires_at timestamptz,
    circuit_state              text        NOT NULL DEFAULT 'CLOSED'
        CHECK (circuit_state IN ('CLOSED', 'OPEN', 'HALF_OPEN')),
    consecutive_failures       int         NOT NULL DEFAULT 0,
    circuit_opened_at          timestamptz,
    circuit_retry_at           timestamptz,
    circuit_open_count         int         NOT NULL DEFAULT 0,
    half_open_probe_id         uuid,
    deleted_at                 timestamptz,
    created_at                 timestamptz NOT NULL DEFAULT now(),
    updated_at                 timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX endpoints_account_name_uq ON endpoints (account_id, name) WHERE deleted_at IS NULL;
CREATE INDEX endpoints_account_idx ON endpoints (account_id, created_at DESC, id DESC);

CREATE TABLE events (
    id              uuid PRIMARY KEY,
    account_id      uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    event_type      text        NOT NULL CHECK (length(event_type) BETWEEN 1 AND 100),
    idempotency_key text        NOT NULL CHECK (length(idempotency_key) BETWEEN 1 AND 255),
    request_hash    bytea       NOT NULL,
    body            text        NOT NULL,
    occurred_at     timestamptz,
    created_at      timestamptz NOT NULL,
    CONSTRAINT events_idempotency_uq UNIQUE (account_id, idempotency_key)
);
CREATE INDEX events_account_created_idx ON events (account_id, created_at DESC, id DESC);
CREATE INDEX events_account_type_created_idx ON events (account_id, event_type, created_at DESC, id DESC);

CREATE TABLE deliveries (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id            uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    event_id              uuid        NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    endpoint_id           uuid        NOT NULL REFERENCES endpoints (id),
    status                text        NOT NULL CHECK (status IN
        ('PENDING', 'RETRYING', 'IN_FLIGHT', 'DELIVERED', 'DEAD_LETTERED', 'CANCELLED')),
    attempt_count         int         NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    max_attempts          int         NOT NULL CHECK (max_attempts >= 1),
    next_attempt_at       timestamptz,
    lease_token           uuid,
    lease_owner           text,
    lease_expires_at      timestamptz,
    last_attempt_at       timestamptz,
    last_response_status  int,
    last_error            text,
    dead_letter_reason    text CHECK (dead_letter_reason IN
        ('RETRIES_EXHAUSTED', 'NON_RETRYABLE_RESPONSE', 'DESTINATION_BLOCKED')),
    delivered_at          timestamptz,
    dead_lettered_at      timestamptz,
    replay_of_delivery_id uuid REFERENCES deliveries (id) ON DELETE CASCADE,
    root_delivery_id      uuid        NOT NULL,
    replayed_by_user_id   uuid REFERENCES users (id),
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT deliveries_schedule_ck CHECK (status NOT IN ('PENDING', 'RETRYING') OR next_attempt_at IS NOT NULL),
    CONSTRAINT deliveries_lease_ck CHECK (status <> 'IN_FLIGHT' OR (lease_token IS NOT NULL AND lease_expires_at IS NOT NULL)),
    CONSTRAINT deliveries_dead_letter_ck CHECK (status <> 'DEAD_LETTERED' OR dead_letter_reason IS NOT NULL)
);
-- One original delivery per (event, endpoint); replays are linked rows.
CREATE UNIQUE INDEX deliveries_original_uq ON deliveries (event_id, endpoint_id) WHERE replay_of_delivery_id IS NULL;
-- At most one unfinished delivery per replay chain: prevents concurrent double replays.
CREATE UNIQUE INDEX deliveries_active_chain_uq ON deliveries (root_delivery_id)
    WHERE status IN ('PENDING', 'RETRYING', 'IN_FLIGHT');
-- Worker claim query: only due work is indexed.
CREATE INDEX deliveries_due_idx ON deliveries (next_attempt_at) WHERE status IN ('PENDING', 'RETRYING');
-- Lease reaper.
CREATE INDEX deliveries_lease_idx ON deliveries (lease_expires_at) WHERE status = 'IN_FLIGHT';
-- Dashboard queries (keyset pagination on created_at, id).
CREATE INDEX deliveries_account_created_idx ON deliveries (account_id, created_at DESC, id DESC);
CREATE INDEX deliveries_account_status_created_idx ON deliveries (account_id, status, created_at DESC, id DESC);
CREATE INDEX deliveries_endpoint_created_idx ON deliveries (endpoint_id, created_at DESC, id DESC);
CREATE INDEX deliveries_event_idx ON deliveries (event_id);
CREATE INDEX deliveries_replay_of_idx ON deliveries (replay_of_delivery_id);

CREATE TABLE delivery_attempts (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    delivery_id           uuid        NOT NULL REFERENCES deliveries (id) ON DELETE CASCADE,
    account_id            uuid        NOT NULL,
    endpoint_id           uuid        NOT NULL,
    attempt_number        int         NOT NULL CHECK (attempt_number >= 1),
    status                text        NOT NULL CHECK (status IN ('STARTED', 'SUCCEEDED', 'FAILED', 'ABANDONED')),
    outcome               text,
    retryable             boolean,
    request_url           text        NOT NULL,
    response_status       int,
    response_headers      jsonb,
    response_body_excerpt text,
    error_message         text,
    retry_after_seconds   int,
    started_at            timestamptz NOT NULL,
    finished_at           timestamptz,
    duration_ms           int,
    CONSTRAINT delivery_attempts_number_uq UNIQUE (delivery_id, attempt_number)
);
CREATE INDEX delivery_attempts_endpoint_idx ON delivery_attempts (endpoint_id, started_at DESC);
CREATE INDEX delivery_attempts_account_idx ON delivery_attempts (account_id, started_at DESC);
