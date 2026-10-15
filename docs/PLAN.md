# Webhook Delivery & Reliability Platform: Architecture and Implementation Plan

Status: **approved 2026-10-15**, with all recommendations in §16 accepted. Implementation follows the seven stages agreed in the project thread. See *Implementation notes* at the end for decisions taken while building.
Date: 2026-10-15

---

## 0. Repository inspection

- `rupinderkaur1904/Webhook` was attached and cloned. It is **empty**: no commits, no branches, no files.
- This is therefore a **greenfield** project. Nothing exists that needs to be preserved.
- Build environment checked in the working container: OpenJDK 21, Maven, Gradle, Node 22, PostgreSQL 16 client and server binaries. Docker CLI is present but **no Docker daemon is running here**, so Testcontainers cannot run in this container. Integration tests will use Testcontainers in CI (GitHub Actions has Docker) and fall back to a `TEST_DATABASE_URL` pointing at a local PostgreSQL 16 when Docker is unavailable. Both paths run against real PostgreSQL, never H2.

---

## 1. Understanding of the product

A developer-facing service that sits between an application that produces events and the HTTP endpoints that want to hear about them. Its value is not "sending an HTTP POST"; it is the guarantees around it:

1. **Durable acceptance.** Once the API says `202 Accepted`, the event and its deliveries are committed to PostgreSQL. A crash after that point loses nothing.
2. **Asynchronous, at-least-once delivery.** A background worker attempts each delivery until it succeeds, is permanently rejected, or exhausts its retry budget.
3. **Honest failure handling.** Transient vs permanent failures are classified, retries back off with jitter and honor `Retry-After`, sick endpoints are paused rather than hammered, and terminal failures are preserved in a dead-letter state with full history.
4. **Verifiability.** Every request is HMAC-SHA256 signed so receivers can authenticate it, and every attempt is recorded so developers can see exactly what happened.
5. **Operability.** A dashboard shows real, persisted state: endpoints, deliveries, attempts, dead letters, health, and a replay action.

Users:
- **Integrating developer (API client):** submits events with an API key.
- **Dashboard user:** registers endpoints, inspects deliveries, diagnoses failures, replays dead letters.
- **Receiver developer:** verifies signatures and deduplicates using documented headers.

The platform is a single-tenant-per-account system: each account owns its endpoints, events, deliveries, and API keys, and can see nothing else.

---

## 2. Ambiguous or conflicting requirements and how I propose to resolve them

| # | Issue | Proposed resolution |
|---|---|---|
| A1 | **"Unique event identifier"** and **"idempotency key"** are both listed. Who generates the ID? | The platform generates the event `id` (UUID). The client supplies the `Idempotency-Key`. The event `id` is what receivers deduplicate on (sent as `webhook-id`). |
| A2 | **Endpoint selection** is left open ("destinations, or a documented mechanism"). | Endpoints have an optional event-type filter. An event with no `endpointIds` fans out to every active endpoint whose filter matches. An event with explicit `endpointIds` goes only to those (they must be owned and active). |
| A3 | **"Failed" vs "dead-lettered"** appear as separate metrics, but only dead-letter is defined as a state. | One terminal failure state, `DEAD_LETTERED`, with a `deadLetterReason` (`RETRIES_EXHAUSTED`, `NON_RETRYABLE_RESPONSE`, `DESTINATION_BLOCKED`). "Failed attempts" is an attempt-level metric. This avoids two terminal states that mean almost the same thing. |
| A4 | **SSRF protection vs a local failure simulator.** Blocking loopback and private ranges also blocks a receiver on `localhost` or a Docker network. | The simulator is a separate app. SSRF protection stays on in every profile. Local and demo profiles add an **exact-hostname allowlist** (`simulator`, `localhost:9090`), never a blanket "allow private networks" switch. In a public deployment the simulator gets its own public URL and needs no allowlist. |
| A5 | **Exactly what is idempotent** on reuse of a key with the same payload: what does the client get back? | Same key + same request → `200 OK` with the original response body and header `Idempotent-Replayed: true`. Same key + different request → `409 Conflict`. |
| A6 | **Which 4xx are retryable.** The spec says "don't retry indiscriminately" but some 4xx are transient. | Retry 408, 425, 429, and 5xx (except 501, 505). Treat other 4xx (400, 401, 403, 404, 410, 413, 422, ...) as permanent. Needs your approval because 401/404 are sometimes transient during a receiver's deploy. |
| A7 | **Replay vs idempotency.** Does replay reuse the delivery or create a new one? | Replay creates a **new delivery row** linked to the dead-lettered one (`replay_of_delivery_id`). The original stays `DEAD_LETTERED` and untouched. The event `id` (and therefore `webhook-id`) is unchanged, so a receiver that already processed it can dedupe. |
| A8 | **Endpoint URL changed after a delivery failed.** Which URL does a retry or replay use? | The **current** endpoint URL and **current** signing secret, re-validated for SSRF at send time. Each attempt records the URL it actually hit. |
| A9 | **Public deployment + arbitrary URLs.** Open sign-up on a public host makes the platform a request-sending service for strangers. | Self-registration is **off by default**. A bootstrap user is created from environment variables. Registration can be turned on explicitly. |
| A10 | **"Timestamp-based replay protection"** is listed as a concern, but it is a receiver-side check. | The platform sends `webhook-timestamp` and signs it. Verification docs and a reference verifier enforce a 5-minute tolerance on the receiver side. |
| A11 | **Ordering.** Not mentioned. | No ordering guarantee between events, documented explicitly. Retries make ordering impossible to promise cheaply. |
| A12 | **Deployment target** is "a suitable hosting setup". | One Docker image (Spring Boot serving the built React app on the same origin) plus managed PostgreSQL. Concrete host chosen at the deployment milestone after checking current free-tier terms. |

---

## 3. Recommended architecture

### 3.1 Shape

A **modular monolith**: one Spring Boot application containing the API, the delivery worker, and the static dashboard; one PostgreSQL database that is both the system of record and the work queue. A **separate** simulator application acts as a controllable receiver. No message broker, no microservices.

```
                         ┌──────────────────────────────────────────────────────────┐
                         │                Spring Boot application                   │
                         │                                                          │
 Producer app ──API key──▶  REST API  ──▶ Ingestion service ──(1 tx)──┐             │
                         │  (/api/v1)     validate, idempotency,      │             │
 Browser ──session──────▶│                fan-out to endpoints        ▼             │
  (React dashboard,      │                                   ┌─────────────────┐    │
   served as static      │  Query services ◀────────────────▶│   PostgreSQL    │    │
   files, same origin)   │  (deliveries, metrics, health)    │  events         │    │
                         │                                   │  deliveries ◀── queue│
                         │  Delivery worker                  │  attempts       │    │
                         │   ├ Poller: claim batch           │  endpoints      │    │
                         │   │  (FOR UPDATE SKIP LOCKED,     │  users/api_keys │    │
                         │   │   lease token + expiry)       └─────────────────┘    │
                         │   ├ Dispatcher (virtual threads)          ▲              │
                         │   │  sign → SafeHttpClient → classify     │ result tx    │
                         │   │  → RetryPolicy → record result ───────┘              │
                         │   ├ Lease reaper (crash recovery)                        │
                         │   └ Circuit breaker (per endpoint, in DB)                │
                         └───────────────────────────┬──────────────────────────────┘
                                                     │ HTTPS POST, signed,
                                                     │ SSRF-checked DNS, no redirects
                                                     ▼
                                   Receiver endpoints  /  Failure simulator (separate app)
```

### 3.2 Component responsibilities

| Component | Responsibility |
|---|---|
| **REST controllers + DTOs** | HTTP contract only: parse, validate, map to services, map errors to RFC 9457 problem responses. |
| **Auth** | Dashboard login with server-side session (HttpOnly cookie, CSRF token). API-key authentication for ingestion. Resolves every request to an `accountId`. |
| **Endpoint service** | CRUD, URL validation (syntax + SSRF), secret generation, encryption and rotation, enable/disable, test events. |
| **Ingestion service** | Validates the event, enforces idempotency, builds the exact webhook body, selects endpoints, inserts the event and its deliveries in **one transaction**. |
| **Delivery worker** | Claims due deliveries, sends them, records attempts, applies the retry policy, dead-letters exhausted deliveries. |
| **Retry policy + classifier** | Pure functions: outcome → `SUCCESS / RETRY(at) / DEAD_LETTER(reason)`. No I/O, fully unit-tested. |
| **Signing** | Computes HMAC-SHA256 signatures over the exact bytes sent. |
| **SafeHttpClient** | Apache HttpClient 5 with a custom `DnsResolver` that rejects non-public addresses, redirects disabled, strict timeouts, response-body cap. |
| **Lease reaper** | Returns deliveries whose worker lease expired (crash, hang) to the retry path. |
| **Circuit breaker** | Per-endpoint state stored on the endpoint row; pauses claiming for open endpoints without consuming retry budget. |
| **Replay service** | Validates eligibility, creates a linked replay delivery. |
| **Query / metrics services** | Read-only, keyset-paginated queries and documented metric calculations for the dashboard. |
| **Retention job** | Deletes terminal history older than the retention window, in batches. |
| **Failure simulator** (separate app) | Scriptable receiver: status sequences, delays, timeouts, 429 + `Retry-After`, intermittent failures, signature verification, request log. |

### 3.3 Key flows

**Ingestion.** `POST /api/v1/events` → authenticate API key → validate → compute request hash → in one transaction: `INSERT event ... ON CONFLICT (account_id, idempotency_key) DO NOTHING`; if inserted, select matching endpoints and insert one `PENDING` delivery per endpoint with `next_attempt_at = now()`; commit → `202`. If the insert conflicted, load the existing event and compare hashes (`200` replay or `409`). The API never contacts a destination.

**Delivery.** Poller claims up to N due deliveries in one short transaction, assigning a lease → for each, a virtual thread loads the event body and endpoint, signs, sends, and classifies the outcome → a second short transaction writes the attempt row and the delivery's next state, **guarded by the lease token**. No database transaction is held open across the HTTP call.

**Retry.** Retryable outcome with attempts remaining → `RETRYING`, `next_attempt_at = now() + delay`. The schedule lives in the row, so restarts lose nothing.

**Dead-letter.** Non-retryable outcome, blocked destination, or attempts exhausted → `DEAD_LETTERED` with reason, timestamps, and all attempts intact.

**Recovery.** Lease reaper finds `IN_FLIGHT` rows with `lease_expires_at < now()`, marks the open attempt `ABANDONED`, and schedules a retry (or dead-letters if that was the last attempt). On startup nothing special is needed: due rows are simply claimable.

**Replay.** Dashboard → `POST /deliveries/{id}/replay` → eligibility checks → new `PENDING` delivery with `replay_of_delivery_id`, a fresh attempt budget, same event.

---

## 4. Database design

### 4.1 Entities and relationships

```
users 1──* api_keys
users 1──* endpoints 1──* deliveries *──1 events *──1 users
deliveries 1──* delivery_attempts
deliveries 0..1──* deliveries   (replay_of_delivery_id, root_delivery_id)
```

"Account" = user in the first version (one user owns their own data). Teams/organizations are postponed; the column is named `account_id` so a later `accounts` table can be introduced without renaming.

### 4.2 Schema (Flyway `V1__init.sql`, abridged but complete in intent)

```sql
CREATE TABLE users (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  email            citext NOT NULL UNIQUE,
  password_hash    text   NOT NULL,                 -- bcrypt/argon2
  created_at       timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE api_keys (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  account_id       uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name             text NOT NULL,
  key_prefix       text NOT NULL,                   -- first 8 chars, for display
  key_hash         bytea NOT NULL UNIQUE,           -- SHA-256 of a 32-byte random key
  last_used_at     timestamptz,
  revoked_at       timestamptz,
  created_at       timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE endpoints (
  id                     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  account_id             uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name                   text NOT NULL CHECK (length(name) BETWEEN 1 AND 100),
  description            text,
  url                    text NOT NULL CHECK (length(url) <= 2048),
  event_types            text[] NOT NULL DEFAULT '{}',   -- empty = all types
  enabled                boolean NOT NULL DEFAULT true,
  secret_ciphertext      bytea NOT NULL,                 -- AES-256-GCM, key from env
  previous_secret_ciphertext bytea,
  previous_secret_expires_at timestamptz,
  circuit_state          text NOT NULL DEFAULT 'CLOSED'
                         CHECK (circuit_state IN ('CLOSED','OPEN','HALF_OPEN')),
  consecutive_failures   int  NOT NULL DEFAULT 0,
  circuit_opened_at      timestamptz,
  circuit_retry_at       timestamptz,
  circuit_open_count     int  NOT NULL DEFAULT 0,        -- for growing cool-down
  half_open_probe_id     uuid,                           -- delivery acting as probe
  deleted_at             timestamptz,
  created_at             timestamptz NOT NULL DEFAULT now(),
  updated_at             timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX endpoints_account_name_uq ON endpoints(account_id, name) WHERE deleted_at IS NULL;

CREATE TABLE events (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  account_id       uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  event_type       text NOT NULL,
  idempotency_key  text NOT NULL CHECK (length(idempotency_key) BETWEEN 1 AND 255),
  request_hash     bytea NOT NULL,                  -- SHA-256 of canonical request
  body             text NOT NULL,                   -- exact JSON bytes sent to receivers
  occurred_at      timestamptz,                     -- optional, client-supplied
  created_at       timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT events_idempotency_uq UNIQUE (account_id, idempotency_key)
);

CREATE TABLE deliveries (
  id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  account_id            uuid NOT NULL,              -- denormalized for filtering
  event_id              uuid NOT NULL REFERENCES events(id) ON DELETE CASCADE,
  endpoint_id           uuid NOT NULL REFERENCES endpoints(id),
  status                text NOT NULL CHECK (status IN
                          ('PENDING','RETRYING','IN_FLIGHT','DELIVERED','DEAD_LETTERED','CANCELLED')),
  attempt_count         int  NOT NULL DEFAULT 0,
  max_attempts          int  NOT NULL,              -- snapshot of policy at creation
  next_attempt_at       timestamptz,                -- set when PENDING/RETRYING
  lease_token           uuid,                       -- set when IN_FLIGHT
  lease_owner           text,                       -- worker id, diagnostics only
  lease_expires_at      timestamptz,
  last_attempt_at       timestamptz,
  last_response_status  int,
  last_error            text,                       -- sanitized
  dead_letter_reason    text CHECK (dead_letter_reason IN
                          ('RETRIES_EXHAUSTED','NON_RETRYABLE_RESPONSE','DESTINATION_BLOCKED')),
  delivered_at          timestamptz,
  dead_lettered_at      timestamptz,
  replay_of_delivery_id uuid REFERENCES deliveries(id) ON DELETE CASCADE,
  root_delivery_id      uuid NOT NULL,              -- self for originals
  replayed_by_user_id   uuid REFERENCES users(id),
  created_at            timestamptz NOT NULL DEFAULT now(),
  updated_at            timestamptz NOT NULL DEFAULT now(),
  CHECK (status NOT IN ('PENDING','RETRYING') OR next_attempt_at IS NOT NULL),
  CHECK (status <> 'IN_FLIGHT' OR (lease_token IS NOT NULL AND lease_expires_at IS NOT NULL)),
  CHECK (status <> 'DEAD_LETTERED' OR dead_letter_reason IS NOT NULL)
);
-- one original delivery per (event, endpoint)
CREATE UNIQUE INDEX deliveries_original_uq ON deliveries(event_id, endpoint_id)
  WHERE replay_of_delivery_id IS NULL;
-- at most one non-terminal delivery per (event, endpoint) chain: blocks concurrent double replay
CREATE UNIQUE INDEX deliveries_active_chain_uq ON deliveries(root_delivery_id)
  WHERE status IN ('PENDING','RETRYING','IN_FLIGHT');

CREATE TABLE delivery_attempts (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  delivery_id        uuid NOT NULL REFERENCES deliveries(id) ON DELETE CASCADE,
  account_id         uuid NOT NULL,
  endpoint_id        uuid NOT NULL,
  attempt_number     int  NOT NULL,
  status             text NOT NULL CHECK (status IN ('STARTED','SUCCEEDED','FAILED','ABANDONED')),
  outcome            text,     -- SUCCESS, HTTP_4XX, HTTP_5XX, RATE_LIMITED, TIMEOUT,
                               -- CONNECTION_ERROR, DNS_ERROR, TLS_ERROR, REDIRECT,
                               -- DESTINATION_BLOCKED, LEASE_EXPIRED
  retryable          boolean,
  request_url        text NOT NULL,
  response_status    int,
  response_headers   jsonb,    -- allowlisted subset only
  response_body_excerpt text,  -- first 2 KB, sanitized
  error_message      text,     -- sanitized
  retry_after_seconds int,
  started_at         timestamptz NOT NULL,
  finished_at        timestamptz,
  duration_ms        int,
  UNIQUE (delivery_id, attempt_number)
);

-- Spring Session JDBC tables (SPRING_SESSION, SPRING_SESSION_ATTRIBUTES) in V2.
```

### 4.3 Delivery status machine

```
           create                    claim (lease)
  ──────▶ PENDING ───────────────────────────────▶ IN_FLIGHT ──2xx────────────▶ DELIVERED
                                                   │  │  │
           ┌───────────── retryable, attempts left ┘  │  └ non-retryable / blocked ─▶ DEAD_LETTERED
           ▼                                          │
        RETRYING ──── claim (lease) ─────────────────▶┘    retryable, no attempts left ─▶ DEAD_LETTERED
           ▲                                              lease expired (reaper) ─▶ RETRYING or DEAD_LETTERED
           └──────────────────────────────────────────────┘

  PENDING / RETRYING ── endpoint deleted ──▶ CANCELLED
  DEAD_LETTERED ── replay ──▶ (new delivery) PENDING        original row unchanged
```

Rules enforced in code **and** in SQL `WHERE` clauses (every transition is a conditional `UPDATE ... WHERE id = ? AND status = ? [AND lease_token = ?]`; zero rows updated means someone else won and the change is discarded):

| From | To | Trigger |
|---|---|---|
| PENDING, RETRYING | IN_FLIGHT | worker claim, `next_attempt_at <= now()` |
| IN_FLIGHT | DELIVERED | 2xx, lease token matches |
| IN_FLIGHT | RETRYING | retryable failure, attempts left, lease token matches |
| IN_FLIGHT | DEAD_LETTERED | permanent failure, blocked destination, or budget exhausted |
| IN_FLIGHT | RETRYING / DEAD_LETTERED | lease expired (reaper) |
| PENDING, RETRYING | CANCELLED | endpoint deleted |
| DELIVERED, DEAD_LETTERED, CANCELLED | — | terminal; never updated again |

Disabling an endpoint or an open circuit does **not** change delivery status. Deliveries stay `PENDING`/`RETRYING` and simply are not claimed. Nothing is discarded.

### 4.4 Indexes

| Index | Purpose |
|---|---|
| `deliveries (next_attempt_at) WHERE status IN ('PENDING','RETRYING')` | Worker claim query: small partial index of due work only. |
| `deliveries (lease_expires_at) WHERE status = 'IN_FLIGHT'` | Lease reaper. |
| `deliveries (account_id, created_at DESC, id DESC)` | Default delivery list, keyset pagination. |
| `deliveries (account_id, status, created_at DESC, id DESC)` | Status filter, dead-letter view. |
| `deliveries (endpoint_id, created_at DESC, id DESC)` | Endpoint delivery history. |
| `deliveries (event_id)` | Event detail. |
| `deliveries (replay_of_delivery_id)` | Replay chain lookups. |
| `events (account_id, created_at DESC, id DESC)` and `(account_id, event_type, created_at DESC)` | Event list and filter. |
| `delivery_attempts (endpoint_id, started_at DESC)` | Endpoint health and recent failures. |
| `delivery_attempts (account_id, started_at DESC)` | Recent activity feed. |
| `api_keys (key_hash)` (unique) | API-key lookup. |

### 4.5 Transaction boundaries

1. **Ingestion:** event insert + delivery inserts, one transaction. Commit before responding.
2. **Claim:** one statement (CTE select `FOR UPDATE OF d SKIP LOCKED` + `UPDATE ... RETURNING`), which also inserts `STARTED` attempt rows. Short, autocommit-sized.
3. **HTTP send:** no transaction, no connections held.
4. **Result:** update attempt row + conditional update delivery + endpoint failure counters / circuit state, one transaction.
5. **Reaper, replay, endpoint deletion, retention:** each its own short transaction; retention deletes in batches of e.g. 1,000.

All "is it due" comparisons use the database clock (`now()`), never JVM clocks, so multiple workers with skewed clocks agree.

### 4.6 Retention and deletion

- Terminal deliveries, their attempts, and events with no non-terminal deliveries are purged after `RETENTION_DAYS` (default 30) by a daily job. Non-terminal work is never purged.
- Idempotency keys live as long as their event. After an event is purged, its key can be reused; this is documented.
- Endpoints are soft-deleted (`deleted_at`) so historical deliveries still reference them. Deleting an endpoint cancels its `PENDING`/`RETRYING` deliveries; `IN_FLIGHT` ones finish their current attempt and are then cancelled instead of rescheduled.
- Account deletion cascades everything.

---

## 5. API contract

Base path `/api/v1`. JSON only. Errors use RFC 9457 `application/problem+json`:

```json
{ "type": "https://webhooks.example/errors/validation", "title": "Validation failed",
  "status": 422, "code": "VALIDATION_FAILED", "detail": "...",
  "errors": [ { "field": "eventType", "message": "must match ^[a-z0-9]+([._-][a-z0-9]+)*$" } ],
  "requestId": "..." }
```

Authentication:
- **Session** (dashboard): HttpOnly, Secure, SameSite=Lax cookie; CSRF token required on non-GET requests.
- **API key** (producers): `Authorization: Bearer whk_<random>`.
- Every resource lookup is scoped by the caller's `account_id`. A resource owned by someone else returns **404**, not 403, so IDs cannot be probed.

Lists use keyset pagination: `?limit=50&cursor=<opaque>` → `{ "items": [...], "nextCursor": "..." | null }`. `limit` max 200.

### 5.1 Auth and API keys

| Method & path | Auth | Purpose | Success | Errors |
|---|---|---|---|---|
| `POST /auth/login` `{email,password}` | none | Start session | 200 `{user}` | 401, 429 |
| `POST /auth/logout` | session | End session | 204 | — |
| `GET /auth/me` | session | Current user + CSRF bootstrap | 200 | 401 |
| `POST /auth/register` | none, only if enabled | Create user | 201 | 404 when disabled, 409, 422 |
| `GET /api-keys` | session | List keys (prefix, name, lastUsedAt) | 200 | 401 |
| `POST /api-keys` `{name}` | session | Create key; **plaintext returned once** | 201 `{id,name,key,prefix}` | 422 |
| `DELETE /api-keys/{id}` | session | Revoke | 204 | 404 |

### 5.2 Endpoints

| Method & path | Auth | Purpose | Success | Errors |
|---|---|---|---|---|
| `GET /endpoints?enabled=&cursor=&limit=` | session or key | List with health summary | 200 | — |
| `POST /endpoints` | session | Register | 201 `{endpoint, secret}` (secret shown once) | 422 (incl. `URL_NOT_ALLOWED`) |
| `GET /endpoints/{id}` | session or key | Detail + health | 200 | 404 |
| `PATCH /endpoints/{id}` `{name?,description?,url?,eventTypes?,enabled?}` | session | Update / enable / disable | 200 | 404, 422 |
| `DELETE /endpoints/{id}` | session | Soft delete, cancels pending deliveries | 204 | 404 |
| `POST /endpoints/{id}/rotate-secret` `{gracePeriodHours: 0..72, default 24}` | session | New secret; old one keeps signing alongside until expiry | 200 `{secret, previousSecretExpiresAt}` | 404 |
| `POST /endpoints/{id}/test` `{payload?}` | session | Creates a real `webhook.test` event targeted at this endpoint | 202 (same shape as event ingestion) | 404, 409 if disabled |
| `GET /endpoints/{id}/health?window=24h` | session or key | Health details | 200 | 404 |
| `POST /endpoints/{id}/circuit/reset` | session | Close the circuit manually | 200 | 404 |

Endpoint request validation: `url` absolute, `https` (or `http` only for allowlisted hosts), no userinfo, no fragment, ≤2048 chars, host resolves only to public addresses; `name` 1–100 chars, unique per account; `eventTypes` ≤50 entries, each matching the event-type pattern or ending in `.*` (prefix wildcard). Max 50 endpoints per account (configurable).

Endpoint response never includes the secret except on create and rotate. It includes `secretPrefix` (first 6 chars after `whsec_`) so users can tell secrets apart.

### 5.3 Events

`POST /events`. Auth: API key (or session + CSRF). Header `Idempotency-Key` **required**.

```json
{
  "eventType": "order.created",
  "payload": { "orderId": "ord_123", "amount": 4999 },
  "endpointIds": ["…"],          // optional; omit to fan out by subscription
  "occurredAt": "2026-10-15T03:59:57Z"   // optional
}
```

Validation: body ≤ 256 KB (413 otherwise); `eventType` 1–100 chars matching `^[a-z0-9]+([._-][a-z0-9]+)*$`; `payload` must be a JSON object; `endpointIds` ≤50, each owned, not deleted, and enabled (422 `ENDPOINT_NOT_FOUND` / `ENDPOINT_DISABLED`); `Idempotency-Key` 1–255 printable ASCII (400 if missing).

`202 Accepted`:
```json
{ "id": "…", "eventType": "order.created", "idempotencyKey": "…", "createdAt": "…",
  "deliveries": [ { "id": "…", "endpointId": "…", "status": "PENDING" } ] }
```
Zero matching endpoints is still `202` with `deliveries: []` (the event is recorded and visible).

Idempotency:
- Scope: `(account, Idempotency-Key)`, for as long as the event is retained.
- Request hash: SHA-256 over canonical JSON of `{eventType, payload, sorted endpointIds, occurredAt}` (keys sorted, no insignificant whitespace).
- Same key, same hash → `200 OK`, original body, `Idempotent-Replayed: true`. No new deliveries.
- Same key, different hash → `409 IDEMPOTENCY_KEY_REUSED`.
- Two concurrent identical requests: PostgreSQL's unique index makes the second insert wait for the first to commit, then `DO NOTHING`; the second caller gets the `200` replay. Proven by an integration test.

Other routes:

| Method & path | Purpose |
|---|---|
| `GET /events?eventType=&from=&to=&cursor=&limit=` | List events |
| `GET /events/{id}` | Event with payload and its deliveries |

### 5.4 Deliveries and dead letters

| Method & path | Purpose | Notes |
|---|---|---|
| `GET /deliveries?status=&endpointId=&eventType=&eventId=&from=&to=&cursor=&limit=` | Delivery table | `status` repeatable. Dead-letter view = `status=DEAD_LETTERED`. Rows: `id, eventId, endpointId, endpointName, eventType, status, attemptCount, maxAttempts, lastResponseStatus, lastAttemptAt, nextAttemptAt, deadLetterReason, replayOfDeliveryId, createdAt` |
| `GET /deliveries/{id}` | Detail | Adds attempts (all fields in `delivery_attempts`), request headers sent (signature redacted), event body, replay chain (`root`, `replays[]`), `replayEligibility {eligible, reason}` |
| `POST /deliveries/{id}/replay` | Manual replay | `202` with the new delivery. `409 REPLAY_NOT_ELIGIBLE` with reason: `NOT_DEAD_LETTERED`, `ENDPOINT_DELETED`, `ENDPOINT_DISABLED`, `REPLAY_IN_PROGRESS`, `REPLAY_LIMIT_REACHED`, `EVENT_PURGED`. |

### 5.5 Metrics

`GET /metrics/overview?window=1h|24h|7d` (default `24h`):

| Field | Definition |
|---|---|
| `eventsSubmitted` | Events with `created_at` in window. |
| `deliveriesSucceeded` | Deliveries with `delivered_at` in window. |
| `deliveriesDeadLettered` | Deliveries with `dead_lettered_at` in window, split by reason. |
| `deliveriesPending` / `deliveriesRetrying` / `deliveriesInFlight` | **Current** counts, not windowed. |
| `deliverySuccessRate` | `succeeded / (succeeded + deadLettered)` among deliveries that reached a terminal state in window; `null` when the denominator is 0. |
| `attemptSuccessRate` | Successful attempts / finished attempts in window. |
| `recentActivity` | Last 20 attempts (time, endpoint, event type, outcome, status code, latency). |
| `endpointHealth[]` | Per endpoint, see §7.5. |

`GET /actuator/health` is public and minimal. Everything else under `/actuator` is disabled or admin-only.

OpenAPI 3.1 is generated by springdoc and served at `/v3/api-docs` and `/swagger-ui`. The frontend's TypeScript types are generated from it (`openapi-typescript`), so a contract change that breaks the frontend fails the frontend build.

### 5.6 What a receiver gets

```
POST <endpoint url>
Content-Type: application/json
User-Agent: WebhookPlatform/1.0
webhook-id: <event id>                       (stable across retries and replays)
webhook-timestamp: <unix seconds of this attempt>
webhook-signature: v1,<base64 HMAC>[ v1,<base64 HMAC with previous secret>]
X-Webhook-Delivery-Id: <delivery id>
X-Webhook-Attempt: <n>

{"id":"…","type":"order.created","createdAt":"…","data":{…}}
```

The body is built once at ingestion and stored verbatim (`events.body`), so every attempt sends byte-identical content.

---

## 6. Event and delivery lifecycle (end to end)

1. Producer `POST /events` with `Idempotency-Key`.
2. Event + deliveries committed (`PENDING`, `next_attempt_at = now()`). `202`.
3. Poller (every 500 ms when idle, immediately again when the last batch was full) claims due deliveries for endpoints that are enabled, not deleted, and whose circuit allows traffic.
4. Claim sets `IN_FLIGHT`, `attempt_count += 1`, a fresh `lease_token`, `lease_expires_at = now() + 60s`, and inserts an attempt row with status `STARTED`.
5. Dispatcher signs and sends via `SafeHttpClient` (connect timeout 5 s, total response timeout 15 s, read at most 64 KB of the body, store 2 KB).
6. Classifier + retry policy decide the outcome; result transaction writes the attempt and the next delivery state, guarded by `lease_token`.
7. `DELIVERED`, or `RETRYING` with `next_attempt_at`, or `DEAD_LETTERED` with reason.
8. Endpoint counters and circuit state updated in the same transaction.
9. Dashboard shows the live state (polling every 5 s on active views).
10. A dead letter can be replayed: new delivery, same event, fresh budget; original untouched.

---

## 7. Worker, retry, idempotency and recovery strategy

### 7.1 Claiming work safely

```sql
WITH due AS (
  SELECT d.id
  FROM deliveries d
  JOIN endpoints e ON e.id = d.endpoint_id
  WHERE d.status IN ('PENDING','RETRYING')
    AND d.next_attempt_at <= now()
    AND e.enabled AND e.deleted_at IS NULL
    AND (e.circuit_state = 'CLOSED'
         OR (e.circuit_state = 'HALF_OPEN' AND e.half_open_probe_id = d.id))
  ORDER BY d.next_attempt_at
  LIMIT :batch
  FOR UPDATE OF d SKIP LOCKED
)
UPDATE deliveries d
SET status = 'IN_FLIGHT', attempt_count = d.attempt_count + 1,
    lease_token = gen_random_uuid(), lease_owner = :workerId,
    lease_expires_at = now() + :leaseDuration, last_attempt_at = now(), updated_at = now()
FROM due WHERE d.id = due.id
RETURNING d.*;
```

Why this prevents double claiming under normal operation: `FOR UPDATE` row-locks each selected delivery until the statement's transaction commits; a concurrent worker's `SKIP LOCKED` skips locked rows instead of waiting; once committed, the row is `IN_FLIGHT` and no longer matches the `WHERE`. Two workers therefore get disjoint batches. This holds across threads, across application instances, and across restarts, and is verified with a real-PostgreSQL concurrency test (§9).

Concurrency inside one instance: a semaphore caps in-flight HTTP calls (default 32); batch size = free permits. Each send runs on a Java 21 virtual thread.

### 7.2 Crash recovery: leases and fencing

- Every claim carries a `lease_token` and `lease_expires_at` (default 60 s, which must exceed the 15 s HTTP timeout by a wide margin).
- The **lease reaper** runs every 15 s: `IN_FLIGHT AND lease_expires_at < now()` → attempt marked `ABANDONED` (outcome `LEASE_EXPIRED`), delivery goes to `RETRYING` with backoff, or `DEAD_LETTERED` if that was the last attempt. Abandoned attempts **count** against the budget, so a payload that crashes the worker cannot loop forever.
- **Fencing:** a slow worker whose lease was already reaped will find `lease_token` no longer matches; its result update affects zero rows and is discarded (logged). Its attempt row is still updated with what it observed, so the history is honest.
- **Graceful shutdown:** stop claiming, wait up to 20 s for in-flight sends to finish and record; anything left is recovered by the reaper after restart.

Limitations (documented):
- If a worker sends a request, the receiver processes it, and the worker dies before recording the result, the delivery will be sent again after the lease expires. This is inherent to at-least-once delivery.
- A worker paused for longer than the lease (e.g. a long GC pause or a frozen VM) may send at the same time as the worker that re-claimed the delivery. Fencing keeps the database consistent but cannot prevent the duplicate HTTP request.
- Lease expiry adds up to 60 s + reaper interval of latency for crashed work.

### 7.3 Retry policy

Configuration (`application.yml`, overridable via env):

| Setting | Default | Demo profile |
|---|---|---|
| `max-attempts` | 8 | 4 |
| `base-delay` | 30 s | 2 s |
| `max-delay` | 1 h | 20 s |
| `max-retry-after` | 6 h | 60 s |

Delay for retry after attempt *n*:
`raw = min(maxDelay, baseDelay × 2^(n−1))`, then **equal jitter**: `delay = raw/2 + random(0, raw/2)`. Equal jitter keeps the spread that prevents synchronized retries while guaranteeing at least half the intended back-off. Default schedule (before jitter): 30 s, 1 m, 2 m, 4 m, 8 m, 16 m, 32 m → final attempt ≈ 1 h after the first.

`Retry-After` (on 429 and 503): parsed as delta-seconds or HTTP-date; `delay = max(backoffDelay, retryAfter)`, capped at `max-retry-after`. Invalid values are ignored.

`next_attempt_at = now() + delay` is written in the result transaction. No in-memory timers exist anywhere; a restarted worker simply finds the row when it becomes due.

When no attempts remain after a retryable failure: `DEAD_LETTERED` / `RETRIES_EXHAUSTED`.

### 7.4 Outcome classification

| Outcome | Retry? | Notes |
|---|---|---|
| 2xx | — success | Body not inspected. |
| 3xx | No → `NON_RETRYABLE_RESPONSE` | Redirects are never followed (SSRF). Error tells the user to register the final URL. |
| 408, 425 | Yes | Request timeout / too early. |
| 429 | Yes, honor `Retry-After` | Does **not** count toward the circuit breaker; it is backpressure, not breakage. |
| Other 4xx | No → `NON_RETRYABLE_RESPONSE` | **Needs approval** (A6). |
| 500, 502, 503, 504, other 5xx | Yes | 503 honors `Retry-After`. |
| 501, 505 | No | Will not change with time. |
| Connect timeout, read timeout | Yes | |
| Connection refused / reset, DNS failure (NXDOMAIN, SERVFAIL) | Yes | |
| TLS handshake / certificate error | Yes | Usually configuration, but fixable by the receiver within the retry window. |
| Destination resolves to a blocked address | No → `DESTINATION_BLOCKED` | No request is sent. |
| Status outside 100–599, malformed response | No | |

### 7.5 Circuit breaker and endpoint health

**Recommendation: implement it, but after the core flow is solid (milestone 5).** Without it, a dead endpoint with thousands of queued deliveries consumes worker capacity and every delivery burns its retry budget against a host that is known to be down.

- **Counts as failure:** retryable failures except 429. Non-retryable 4xx do not trip it (that is a payload problem, not endpoint health). Any 2xx resets `consecutive_failures` to 0.
- **CLOSED → OPEN:** `consecutive_failures ≥ 10` (configurable). Sets `circuit_retry_at = now() + cooldown`, cooldown = 30 s × 2^(open_count), capped at 30 min.
- **OPEN:** claim query skips the endpoint's deliveries. They stay `PENDING`/`RETRYING`; **no attempt is recorded and no budget is consumed**. Their `next_attempt_at` may pass; they are simply picked up later.
- **OPEN → HALF_OPEN:** the reaper job, when `circuit_retry_at <= now()`, atomically picks the endpoint's earliest due delivery as `half_open_probe_id` (single conditional update on the endpoint row, so exactly one probe).
- **HALF_OPEN → CLOSED:** probe gets 2xx (or a non-retryable 4xx, which proves the endpoint is reachable). Counters reset.
- **HALF_OPEN → OPEN:** probe fails retryably; cool-down grows.
- **Manual reset** from the dashboard closes the circuit.
- **Never permanent:** there is no state from which recovery requires manual action; the cool-down is capped.
- Limitation: while open, the claim query still scans past the endpoint's due rows. Acceptable at this project's scale; noted as a scaling risk.

**Endpoint health** (shown on endpoint list and detail, computed from `delivery_attempts` and endpoint state):

| Health | Rule (evaluated in order) |
|---|---|
| `DISABLED` | `enabled = false` |
| `FAILING` | circuit `OPEN`/`HALF_OPEN`, or `consecutive_failures ≥ 5` |
| `DEGRADED` | attempt success rate over last 24 h < 90 % with ≥ 10 attempts, or any dead letter in last 24 h |
| `HEALTHY` | ≥ 1 attempt in last 24 h and none of the above |
| `NO_DATA` | no attempts in last 24 h |

Also returned: attempts and success rate (24 h), p50/p95 latency (24 h), last success time, last failure time and reason, dead letters (24 h).

### 7.6 Idempotency and delivery semantics

| Situation | What the platform does |
|---|---|
| Duplicate event submission (same key, same body) | Unique constraint; returns original response, no new deliveries. |
| Same key, different body | `409`, nothing written. |
| Duplicate delivery attempts | Each attempt is a distinct, numbered row. Claim locking + leases prevent concurrent attempts under normal operation. |
| Destination processes the same event twice | Possible (lost response, timeout after processing, lease expiry, replay). The platform **does not** prevent it. Receivers must dedupe on `webhook-id`. |
| Receiver succeeded but response lost (e.g. timeout) | Recorded as a timeout, retried, receiver sees the same `webhook-id` again. |
| Manual replay | New delivery, same event and `webhook-id`, new `X-Webhook-Delivery-Id`, new timestamp/signature. The idempotency key is unaffected (it governs ingestion only). |

The platform guarantees **at-least-once delivery attempts** for accepted events while the endpoint exists and retries remain. It does **not** claim exactly-once processing, which no sender can guarantee over HTTP without receiver cooperation. The README will include receiver-side dedup guidance (store processed `webhook-id`s with a unique constraint).

### 7.7 Dead letter and replay

Preserved on dead-letter: the event body, the endpoint, `dead_letter_reason`, `last_error`, `last_response_status`, all attempt rows, and all timestamps.

Replay eligibility:
1. Delivery is `DEAD_LETTERED`.
2. Endpoint is not deleted and is enabled.
3. No non-terminal delivery exists in the same chain (enforced by the partial unique index, so two simultaneous clicks cannot create two replays).
4. Chain has fewer than `max-replays-per-delivery` replays (default 10).
5. Caller owns the delivery.

Replays are **only** manual. A replayed delivery that dead-letters again stays dead-lettered until someone replays it again. There is no automatic re-replay, so loops are impossible. The URL is re-validated for SSRF at send time like any other attempt. Bulk replay is postponed.

---

## 8. Security

| Concern | Design |
|---|---|
| Authentication | Dashboard: email + password (Argon2id via Spring Security), server-side session in PostgreSQL (Spring Session JDBC, survives restarts), HttpOnly + Secure + SameSite=Lax cookie, CSRF token. Producers: API keys, 32 random bytes, stored as SHA-256 hash (high-entropy keys do not need a slow hash), shown once, revocable, `last_used_at` tracked. |
| Authorization | Every query filters by `account_id` from the authenticated principal. Integration tests check that user B gets 404 on each of user A's resources and cannot replay, rotate, or target them. |
| Registration | Off by default; bootstrap user from env (`BOOTSTRAP_EMAIL`, `BOOTSTRAP_PASSWORD`). |
| Signing secrets | `whsec_` + base64(32 random bytes). Encrypted at rest with AES-256-GCM using `SECRET_ENCRYPTION_KEY` from the environment. Returned only on create and rotate. Never logged (a Logback masking converter plus a test asserting secrets never appear in captured logs). |
| Signing | `HMAC-SHA256(key = base64-decoded secret, message = "{webhook-id}.{webhook-timestamp}.{body}")`, header `webhook-signature: v1,<base64>`. This is the [Standard Webhooks](https://www.standardwebhooks.com/) scheme, so receivers can use existing verifier libraries. During rotation both signatures are sent, space-separated. Verification docs require constant-time comparison (`MessageDigest.isEqual`, `hmac.compare_digest`, `crypto.timingSafeEqual`) and a 5-minute timestamp tolerance. |
| SSRF | See below. |
| Input limits | Request body 256 KB (enforced before JSON parsing), JSON depth/size limits in Jackson, strict validation on all DTOs. |
| Outbound limits | Connect 5 s, response 15 s, response body read capped at 64 KB, max 32 concurrent sends per instance, no redirects, no cookies, no proxy env inheritance for delivery traffic. |
| Rate limiting | Per API key on ingestion (default 50 req/s, burst 100) and per IP on login (5/min), using Bucket4j **in memory**. Limitation: per instance, not cluster-wide. |
| Errors | RFC 9457 bodies, no stack traces, a `requestId` for correlation. |
| Redaction | Stored and displayed: response status, allowlisted response headers (`content-type`, `retry-after`, `content-length`, `date`), first 2 KB of body with control characters stripped. Signatures are redacted from displayed request headers. Exception messages are mapped to fixed, categorized messages (e.g. "Connection refused") rather than raw exception text. |
| Config | All secrets via environment variables; nothing secret committed; startup fails if `SECRET_ENCRYPTION_KEY` is missing outside the `local` profile. |
| Frontend | Strict CSP, React's escaping for all rendered response excerpts (never `dangerouslySetInnerHTML`). |

### 8.1 SSRF protection in detail

URL string checks alone are bypassable (decimal IPs, DNS that resolves to `127.0.0.1`, DNS rebinding, redirects), so protection happens at three points:

1. **At registration / update:** parse with `java.net.URI`; require `https` (or `http` for allowlisted hosts in non-prod profiles); reject userinfo, fragments, empty host; resolve the host and reject if **any** resolved address is blocked. This gives immediate feedback but is not relied on.
2. **At every send:** a custom Apache HttpClient 5 `DnsResolver` resolves the host and throws if any address is blocked. The client then connects **only to the addresses that resolver returned**, so there is no gap between "check" and "connect" for DNS rebinding to exploit. TLS SNI and certificate checks still use the hostname.
3. **No redirects:** a 3xx is recorded, never followed.

Blocked ranges (IPv4): `0.0.0.0/8, 10.0.0.0/8, 100.64.0.0/10, 127.0.0.0/8, 169.254.0.0/16` (incl. `169.254.169.254` metadata), `172.16.0.0/12, 192.0.0.0/24, 192.0.2.0/24, 192.88.99.0/24, 192.168.0.0/16, 198.18.0.0/15, 198.51.100.0/24, 203.0.113.0/24, 224.0.0.0/4, 240.0.0.0/4, 255.255.255.255/32`.
Blocked ranges (IPv6): `::/128, ::1/128, fc00::/7` (incl. `fd00:ec2::254`), `fe80::/10, ff00::/8, 2001:db8::/32, 100::/64`; IPv4-mapped (`::ffff:0:0/96`), IPv4-compatible, NAT64 (`64:ff9b::/96`) and 6to4 (`2002::/16`) addresses are unwrapped and the embedded IPv4 is checked.

Allowlist: exact `host[:port]` entries via `SSRF_ALLOWED_HOSTS`, used for the simulator and tests. Empty in production.

Limitations to document:
- If the deployment network has internal services on **public** IP addresses, they are reachable. An egress firewall at the host level is the real backstop and is recommended for any public deployment.
- The platform relies on the system resolver; it does not defend against a compromised resolver.
- Ports are not restricted (any port on a public address is allowed).

---

## 9. Frontend structure

React + TypeScript + Vite. React Router for pages. TanStack Query for fetching, caching, and polling. Mantine as the component library (tables, forms, notifications, modals) to avoid hand-building UI primitives. API types generated from OpenAPI.

```
frontend/src/
  api/          client.ts (fetch + CSRF + problem+json parsing), generated types, query hooks
  auth/         LoginPage, RequireAuth
  layout/       AppShell, nav
  pages/
    overview/       OverviewPage: metric tiles with window selector, success rate,
                    recent activity, endpoint health list
    endpoints/      EndpointsPage, EndpointCreateModal, EndpointDetailPage
                    (config, enable/disable, rotate secret, send test, health, deliveries tab)
    deliveries/     DeliveriesPage (filters: status, endpoint, event type, event/delivery id,
                    date range; cursor pagination), DeliveryDetailPage (attempt timeline,
                    status codes, latency, sanitized errors, payload, replay chain, replay button)
    dead-letters/   DeadLettersPage (dead-letter filter + reason + replay eligibility)
    events/         EventsPage, EventDetailPage
    api-keys/       ApiKeysPage (create shows key once, revoke)
  components/   StatusBadge, HealthBadge, DataTable, EmptyState, ErrorState,
                CopyField, SecretRevealOnce, ConfirmDialog, RelativeTime
```

Every data view handles four states explicitly: loading (skeleton), empty (with a next action, e.g. "Register an endpoint"), error (problem detail message + retry), and success (toast for mutations). Filters live in the URL query string so views are shareable and survive reload. All numbers come from the API; there is no mock data in the production bundle (MSW is used only in tests).

---

## 10. Testing strategy

| Layer | Tooling | What it covers |
|---|---|---|
| Unit | JUnit 5, Mockito, AssertJ | Retry delay math (bounds, cap, jitter range, with a seeded `RandomGenerator`), status classification table, `Retry-After` parsing, signature generation against Standard Webhooks published test vectors, constant-time verifier, SSRF address classifier (every range incl. mapped/NAT64 forms), state-transition guards, canonical request hashing, circuit-breaker transitions. |
| Repository / SQL integration | Spring Boot Test + Testcontainers PostgreSQL 16 (or `TEST_DATABASE_URL`) | Flyway from an empty database, constraints (duplicate key, double replay index, check constraints), claim query, reaper query, retention job. |
| Concurrency | Real PostgreSQL, multiple threads and multiple `DeliveryWorker` instances | 2,000 due deliveries, 8 workers racing: every delivery claimed exactly once per lease; no lost work. Concurrent identical ingestion requests: exactly one event. Concurrent replay clicks: exactly one replay. |
| Delivery integration | WireMock as receiver (allowlisted) | Success, 500 → retry → 200, 429 + `Retry-After`, timeout, connection refused, 3xx not followed, retry exhaustion → dead letter, signature headers verified by an independent verifier in the test. |
| Recovery | Real PostgreSQL | Claim then "crash" (stop worker without recording) → reaper returns it and it is delivered; stale worker's late result is fenced out; retry schedule survives application context restart. |
| Security | Spring Boot Test | Unauthenticated → 401; cross-account access → 404 on every route; CSRF required; secrets never in responses or logs; SSRF rejection of `localhost`, `127.0.0.1`, `[::1]`, `169.254.169.254`, `0x7f.1`, a hostname resolving to a private IP (via a test DNS resolver), redirects to internal addresses. |
| API contract | MockMvc | Validation errors, 413, 409 idempotency conflict, pagination, problem+json shape; OpenAPI document generation does not fail. |
| Frontend | Vitest + React Testing Library + MSW | Each page's loading / empty / error / success states; replay and rotate flows; filter → URL sync. |
| End to end | Playwright (Chromium) against docker-compose stack (app + PostgreSQL + simulator) | The demo scenarios in §13, driven through the real UI and API. |

CI (GitHub Actions): backend unit + integration (Testcontainers), frontend lint + typecheck + tests, Docker image build. E2E runs on main and on demand.

---

## 11. Principal risks

Ranked by impact × likelihood.

| # | Risk | Impact | Likelihood | Mitigation |
|---|---|---|---|---|
| R1 | SSRF bypass (DNS rebinding, IPv6 forms, redirects) on a public deployment | High | Medium | Resolver-level check with connect-to-validated-address, unwrap embedded IPv4, no redirects, closed registration, egress firewall recommendation, dedicated tests. |
| R2 | Lost or stuck deliveries after crashes (rows left `IN_FLIGHT`) | High | Medium | Leases + reaper + fencing tokens; recovery integration tests. |
| R3 | Double claiming / race conditions in state transitions | High | Low–Med | `SKIP LOCKED`, conditional updates with status + token guards, partial unique indexes, real-DB concurrency tests. |
| R4 | Long transaction held during HTTP call exhausting the connection pool | High | Medium if done naively | Two short transactions per attempt, never one spanning the HTTP call; Hikari pool sized above worker concurrency; test asserts no connection held during send. |
| R5 | Secret leakage via logs, API responses, or error messages | High | Medium | Encryption at rest, write-only API fields, log masking, tests scanning captured logs. |
| R6 | Retry storms against a down endpoint; worker starvation by one bad endpoint | Medium | High | Exponential backoff with jitter, circuit breaker, per-instance concurrency cap. Per-endpoint concurrency limit is a known gap (postponed). |
| R7 | Claim query degrading with many rows for open-circuit/disabled endpoints | Medium | Low at this scale | Partial index; documented; future fix is to push `next_attempt_at` for paused endpoints. |
| R8 | Clock skew between workers | Medium | Low | All scheduling comparisons use DB `now()`. |
| R9 | Dashboard metrics slow on large tables | Medium | Medium | Indexed windowed queries, keyset pagination, retention job. |
| R10 | Frontend and backend contract drift | Medium | Medium | Types generated from OpenAPI; build breaks on mismatch. |
| R11 | Testcontainers unavailable in some environments (including this agent container) | Low | High here | `TEST_DATABASE_URL` fallback to a real local PostgreSQL; CI uses Testcontainers. |
| R12 | Free-tier hosting limits (DB expiry, sleeping instances stopping the worker) | Medium | Medium | Choose host at deploy milestone; document that a sleeping instance pauses delivery but loses nothing. |

---

## 12. Implementation milestones

Each milestone ends with: tests run and results reported, docs updated, and a demonstration you can run. Each lands as its own PR.

### M0. Project skeleton and CI
- **Objective:** buildable repo with database migrations running from scratch.
- **Tasks:** Maven multi-module (`backend`, `receiver-simulator`); Spring Boot (latest stable, verified at the time), Flyway, springdoc; `frontend/` Vite + React + TS scaffold; `docker-compose.yml` (PostgreSQL 16); GitHub Actions; README skeleton.
- **Tests:** context loads; Flyway migrates an empty database.
- **Acceptance:** `./mvnw verify` and `npm test` green locally and in CI; `docker compose up db` + app starts and `/actuator/health` is UP.
- **Demo:** start the stack, open Swagger UI.

### M1. Walking skeleton: ingest → deliver → record
- **Objective:** one event delivered end to end, signed, with the attempt recorded.
- **Tasks:** full V1 schema; endpoint create/list/get (with a bootstrap API key, real auth comes in M4); ingestion with idempotency; worker claim + dispatch + result (single attempt, no retries yet); Standard Webhooks signing; simulator app with a plain "always 200" mode that verifies signatures; delivery list/detail API.
- **Tests:** ingestion validation; idempotent replay and 409 conflict; concurrent duplicate submissions; signing vectors; claim query correctness; delivery happy path with WireMock.
- **Acceptance:** `POST /events` returns 202 before the receiver is contacted; the simulator logs a request with a valid signature; `GET /deliveries/{id}` shows `DELIVERED` with one attempt (status, latency, timestamps).
- **Demo:** `scripts/demo.sh happy-path`.

### M2. Reliability: retries, dead-letter, leases, recovery
- **Objective:** correct behaviour under failure and crashes.
- **Tasks:** classifier; retry policy with equal jitter and `Retry-After`; `RETRYING` scheduling; dead-letter transitions; lease + reaper + fencing; graceful shutdown; worker concurrency semaphore on virtual threads.
- **Tests:** classification table; delay bounds; `Retry-After` parsing; 500→500→200 flow; 429 honoring; timeout; exhaustion → `DEAD_LETTERED`; multi-worker concurrency test; crash-recovery test; restart-with-scheduled-retry test; fencing test.
- **Acceptance:** all above pass against real PostgreSQL; killing the app mid-retry and restarting completes the delivery with full history.
- **Demo:** `scripts/demo.sh recover`, `scripts/demo.sh exhaust`, `scripts/demo.sh crash` (kills and restarts the backend).

### M3. Replay and the failure simulator
- **Objective:** dead letters can be inspected and replayed; all failure modes are scriptable.
- **Tasks:** replay service + eligibility + chain API; simulator scripting (status sequences, delays, timeouts, 429 with `Retry-After`, seeded intermittent failure, recover-after-N), request log with signature validity and duplicate `webhook-id` detection; demo script covering every scenario.
- **Tests:** eligibility rules; concurrent replay; replay keeps original attempts; simulator unit tests.
- **Acceptance:** every scenario in §13 runs from the script with no database edits.

### M4. Security: auth, ownership, SSRF, secrets, limits
- **Objective:** safe to expose on the internet with documented limits.
- **Tasks:** users + login sessions + CSRF; API key management; account scoping on every query; SSRF resolver + registration validation + allowlist; secret encryption, rotation with dual signatures, write-only exposure; body-size limits; Bucket4j rate limits; redaction; problem+json everywhere.
- **Tests:** §10 security row in full.
- **Acceptance:** cross-account access impossible across all routes; SSRF test matrix passes; no secret appears in any response or log in the test suite.

### M5. Circuit breaker, endpoint health, metrics API
- **Objective:** sick endpoints are paused safely, and health/metrics come from real data.
- **Tasks:** breaker state machine in the result transaction and reaper; half-open single probe; manual reset; health and metrics queries with documented definitions.
- **Tests:** breaker transitions; no budget consumed while open; recovery after cooldown; metric calculations against seeded data.
- **Acceptance:** a receiver that fails 10 times opens the circuit; queued deliveries remain; when the receiver recovers, the probe closes the circuit and the backlog drains.

### M6. Dashboard
- **Objective:** every backend capability is usable from the UI.
- **Tasks:** pages in §9, generated API types, polling, all four UI states.
- **Tests:** Vitest/RTL per page; Playwright smoke.
- **Acceptance:** register endpoint, send test event, watch retry → success, inspect attempts, replay a dead letter, rotate secret, all through the UI.

### M7. End-to-end, deployment, documentation
- **Objective:** a reproducible, deployed, documented platform.
- **Tasks:** single Docker image (Spring Boot serves the React build); Playwright E2E of all §13 scenarios; deployment to the chosen host with managed PostgreSQL; simulator deployed separately; README (architecture, setup, API, semantics, security, limitations, testing); receiver verification examples (Java, Node, Python).
- **Acceptance:** §12-H criteria below.

### Final acceptance criteria (H)

The platform is complete when all of these are demonstrated with evidence (test output or a recorded run):

1. Clean database → `docker compose up` → app migrates and starts.
2. Events submitted via documented API return `202` and are persisted with their deliveries.
3. Worker delivers, recording status, latency, and errors for every attempt.
4. 5xx/timeout/429 retry with backoff and `Retry-After`; non-retryable 4xx dead-letters immediately.
5. Scheduled retries survive a backend restart.
6. Duplicate submissions follow the idempotency contract (200 replay / 409 conflict), including under concurrency.
7. Exhausted deliveries are `DEAD_LETTERED` with full history; replay creates a linked delivery and preserves the original.
8. Concurrency test (multi-worker) and crash-recovery test pass on real PostgreSQL.
9. Signatures verify with an independent Standard Webhooks verifier; rotation sends both signatures.
10. Auth, ownership, SSRF, and secret-handling tests pass.
11. Dashboard displays only persisted data, with loading/empty/error/success states covered by tests.
12. All §13 scenarios run repeatably via script and via Playwright, with no manual database edits.
13. README covers architecture, setup, API, delivery semantics, security, limitations, and testing.

---

## 13. Demonstration plan (I)

All scenarios run via `scripts/demo.sh <scenario>` against `docker compose up` (app + PostgreSQL + simulator), and are mirrored by Playwright tests. The demo profile uses short delays (2 s base, 4 attempts).

| Scenario | Steps | Expected visible result |
|---|---|---|
| **Success** | Simulator receiver `ok` returns 200. Submit `order.created`. | Delivery `DELIVERED`, 1 attempt, 200, latency; simulator log shows signature valid. |
| **Transient failure → recovery** | Receiver script `[500, 500, 200]`. Submit. | Attempts 1–2 fail (500) with growing delays shown as `nextAttemptAt`; attempt 3 succeeds; full timeline in delivery detail. |
| **Rate limited** | Receiver `[429 Retry-After: 5, 200]`. | Attempt 2 occurs ≥ 5 s after attempt 1; outcome `RATE_LIMITED` then success. |
| **Timeout** | Receiver delay 20 s (> 15 s timeout), then 200. | Attempt 1 `TIMEOUT`; retry succeeds. Simulator shows the same `webhook-id` twice (demonstrates why receivers dedupe). |
| **Retry exhaustion** | Receiver always 500. | 4 attempts, then `DEAD_LETTERED / RETRIES_EXHAUSTED`; appears in Dead letters view. |
| **Permanent failure** | Receiver 400. | Single attempt, `DEAD_LETTERED / NON_RETRYABLE_RESPONSE`. |
| **Dead-letter inspection + replay** | Switch receiver to 200; click Replay. | New delivery linked to the original; original keeps its 4 failed attempts; new one `DELIVERED`. Second replay click while in progress is rejected. |
| **Duplicate submission** | Submit twice with the same `Idempotency-Key`; then same key with a different payload. | First `202`, second `200` + `Idempotent-Replayed: true`, one event; third `409`. |
| **Signature verification** | Simulator verifies each request; also a tampered-body request sent to the simulator's verify endpoint. | Valid requests show "signature valid"; tampered one rejected. After secret rotation, both signatures present and verifiable during grace period. |
| **Crash recovery** | Receiver delay 10 s; submit; `docker compose kill app` mid-request; start app. | After lease expiry the delivery is retried and delivered; attempt history shows the `ABANDONED` attempt. |
| **Circuit breaker** | Receiver always 500, submit 20 events; then switch receiver to 200. | Circuit opens after 10 failures; remaining deliveries stay queued with no extra attempts; after cool-down the probe succeeds, circuit closes, backlog drains. |
| **SSRF** | Register `http://169.254.169.254/` and a hostname resolving to `127.0.0.1`. | Both rejected with `URL_NOT_ALLOWED`. |

---

## 14. Technical decision records (E)

**TDR-1 PostgreSQL as the queue (not Kafka/RabbitMQ/Redis).**
Chosen because the delivery record must be durable and queryable anyway; a separate broker means two sources of truth and an outbox to keep them consistent. Alternatives: RabbitMQ/SQS (better throughput, native delayed delivery), Kafka (ordering, replay). Trade-off: polling latency (≤ 0.5 s) and throughput limits (thousands per second is fine; much more is not). Limitation: one database is both queue and store, so load affects both. Upgrade path: `LISTEN/NOTIFY` for latency, partitioning, then a broker if needed.

**TDR-2 `FOR UPDATE SKIP LOCKED` + leases (not advisory locks or a status flag alone).**
Row locks give disjoint batches without coordination; leases give crash recovery. Alternative: `pg_advisory_lock` per delivery (harder to reason about across pooled connections); a single leader worker (simpler, but no horizontal scaling and a single point of stall). Limitation: duplicate sends after lease expiry (§7.2).

**TDR-3 Two short transactions per attempt.**
Holding a transaction across an HTTP call ties a pool connection to a remote server's latency and blocks other work. Cost: needs the `STARTED` attempt row and fencing token to stay consistent, which the design provides.

**TDR-4 Standard Webhooks signature format.**
Chosen over a custom header so receivers can use existing libraries and a published spec. Alternative: Stripe-style `t=…,v1=…`. Trade-off: slightly less familiar header names; gains interoperability and test vectors.

**TDR-5 Encrypt signing secrets (not hash).**
The platform must compute HMACs, so it needs the plaintext; hashing is impossible. AES-GCM with an env-supplied key keeps database dumps from leaking secrets. Limitation: the key and database together still expose secrets; key rotation for the master key is a documented manual procedure.

**TDR-6 Apache HttpClient 5 (not JDK `HttpClient` or `RestClient` defaults).**
Its `DnsResolver` hook lets the SSRF check govern the exact addresses connected to, closing the DNS-rebinding gap; the JDK client has no equivalent hook. Cost: one extra dependency.

**TDR-7 Replay as a new linked delivery.**
Keeps the dead-lettered record immutable, makes the audit trail obvious, and lets a DB index prevent duplicate concurrent replays. Alternative: reset the same row (simpler queries, but mutates history and complicates attempt numbering).

**TDR-8 Server-side sessions for the dashboard (not JWT in localStorage).**
HttpOnly cookies are not readable by injected scripts, logout is real revocation, and same-origin serving removes CORS. Cost: CSRF protection required (Spring Security provides it).

**TDR-9 Modular monolith with the worker in-process.**
One deployable is easier to run, test, and demo. The worker can be disabled per instance (`WORKER_ENABLED=false`) if the API and worker ever need to scale separately.

**TDR-10 Separate simulator application.**
Keeps test-only behaviour (scripted failures) out of the production artifact entirely, and exercises the real network path including SSRF allowlisting.

**TDR-11 Maven multi-module, Java 21.**
Java 21 for virtual threads (cheap per-delivery threads blocked on I/O). Maven over Gradle for predictable, conventional builds; no strong reason either way.

---

## 15. Critical review of the requirements (10)

**Keep as specified:** persistence-before-ack, `SKIP LOCKED` worker, leases, exponential backoff with jitter, `Retry-After`, HMAC signing, SSRF, dead-letter with history, replay, real-data dashboard, real-PostgreSQL tests.

**Simplify:**
- One terminal failure state (`DEAD_LETTERED` + reason) instead of separate `FAILED` and `DEAD_LETTERED`.
- Accounts = users for now; no teams, roles, or invitations.
- Endpoint selection via a simple event-type filter (exact or `prefix.*`), not a rules engine.
- Retry policy configured globally, not per endpoint.
- Rate limiting in memory, per instance.

**Postpone (documented as future work):**
- Per-endpoint concurrency limits and fair scheduling across endpoints.
- `LISTEN/NOTIFY` for lower latency.
- Bulk replay and replay-with-edited-payload.
- Ordering guarantees.
- Event payload search (search is by IDs, type, status, endpoint, and time).
- Outbound static IPs, mTLS to receivers, custom headers per endpoint.
- Prometheus/Grafana dashboards beyond Actuator basics.
- Master encryption key rotation automation.

**Change:**
- Make `Idempotency-Key` **required**. Optional keys make the duplicate-submission contract weaker and harder to explain; a required key is one header for clients.
- Treat the circuit breaker as an approved-but-later milestone (M5), not part of the first working flow.
- Close self-registration on public deployments (A9); the SSRF surface of an open registration is the main security risk of this project.

---

## 16. Decisions needing your approval

| # | Decision | Recommendation |
|---|---|---|
| D1 | Overall architecture: Spring Boot modular monolith + PostgreSQL queue + separate simulator, no broker | Yes |
| D2 | `Idempotency-Key` required on every event submission | Yes |
| D3 | Fan-out by endpoint event-type filter, with optional explicit `endpointIds` | Yes |
| D4 | Non-retryable 4xx (except 408, 425, 429) dead-letter immediately | Yes |
| D5 | Replay creates a new linked delivery; original stays untouched | Yes |
| D6 | Standard Webhooks signing format | Yes |
| D7 | Signing secrets encrypted at rest and shown only at create/rotate | Yes |
| D8 | Dashboard: session cookie login; producers: API keys; sign-up off by default | Yes |
| D9 | Circuit breaker included, as milestone 5 | Yes |
| D10 | Dashboard UI library: Mantine | Yes |
| D11 | Hosting chosen at M7 (single Docker image + managed PostgreSQL) | Yes |
| D12 | Work lands as one PR per milestone against `main` | Yes |


---

## Implementation notes

Decisions made during implementation. Each one either refines the plan or departs from it, and the reason is given.

**Stage 1**
- **Stack versions:** Spring Boot 4.1.1 (Jackson 3, Spring Security 7) and Java 21 on the backend. React 19, Vite 7, Mantine 8, TanStack Query 5 and Vitest 3 on the frontend. These are the latest releases within major lines that have been stable for a while.
- **Persistence uses Spring `JdbcClient` with explicit SQL rather than JPA.** The queue depends on precise SQL (`FOR UPDATE SKIP LOCKED`, conditional updates guarded by status and lease token, `ON CONFLICT DO NOTHING`), and ORM-generated SQL would hide it.
- **Leases and `SKIP LOCKED` claiming are in Stage 1, not Stage 4.** The worker is built on them from the start. Stage 4 adds the lease reaper, crash-recovery tests and stale-worker scenarios.
- **The send-time SSRF resolver and secret encryption are in Stage 1.** They are part of the HTTP client and the endpoint model, so adding them later would mean rewriting both.
- **Attempt `outcome` values** are `SUCCESS, HTTP_REDIRECT, HTTP_CLIENT_ERROR, RATE_LIMITED, HTTP_SERVER_ERROR, TIMEOUT, CONNECTION_ERROR, DNS_ERROR, TLS_ERROR, DESTINATION_BLOCKED, INVALID_RESPONSE`, plus `LEASE_EXPIRED` from Stage 4. Whether an outcome is retryable is decided by the Stage 2 classifier, not baked into the outcome.
- **The endpoint list is not paginated.** It returns all endpoints, because accounts are capped at 50 endpoints (the cap is enforced in Stage 5).
- **Delivery tests use an in-process JDK `HttpServer` instead of WireMock.** This avoids a Jetty/Jackson 2 dependency tree for something about 100 lines can do.
- **Passwords are hashed with bcrypt for now,** via Spring Security's delegating encoder. Argon2id needs BouncyCastle; that choice is made in Stage 5, when login is built.
- **Response bodies:** only the first 2 KB is read from the socket. The rest is discarded by closing the connection.

**Stage 2**
- **Retry policy settings:** `webhooks.retry.{max-attempts, base-delay, max-delay, max-retry-after}` replaces the Stage 1 `webhooks.delivery.max-attempts`. Defaults are as in §7.3. The `local` profile uses 4 attempts and a 2s base delay so demos run in seconds.
- **`Retry-After` is honoured only on 429 and 503.** On other statuses it is ignored and not stored on the attempt, because it carries no scheduling meaning there. It can only lengthen a delay, never shorten one, and never adds attempts.
- **Endpoint deleted mid-attempt:** if a retryable failure comes back after its endpoint was deleted, the delivery is marked `CANCELLED` instead of `RETRYING`, in the same conditional update.
- **The simulator accepts configurable response headers** (e.g. `Retry-After`). Scripted response sequences and intermittent failures are still planned for Stage 3. In the meantime, `scripts/demo.sh` reconfigures the receiver while a delivery is in progress.
