# API reference

All routes are under `/api/v1`. Errors are `application/problem+json` with a stable `code` and the `requestId` (also in the `X-Request-Id` response header). Resources owned by another account return `404`.

There are two ways to authenticate:
- **Dashboard session** (people): sign in with email and password. The session lives in PostgreSQL and the browser holds only an HttpOnly cookie. Every state-changing request needs the CSRF token: `GET /auth/csrf` sets the readable `XSRF-TOKEN` cookie, and requests echo it in the `X-XSRF-TOKEN` header.
- **API key** (producers): `Authorization: Bearer whk_…`. A key can submit events and read (`GET`). It cannot manage endpoints, replay, rotate secrets or manage keys; those return `403 SESSION_REQUIRED`. A leaked ingestion key therefore cannot redirect webhooks or mint more keys.

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `GET` | `/auth/csrf` | none | Issue the CSRF token. |
| `POST` | `/auth/login` | none + CSRF | `{email, password}`. Starts a session with a new session id. `401 INVALID_CREDENTIALS`, `429` after 5 attempts a minute per address or per email. |
| `POST` | `/auth/logout` | session | Ends the session server-side (`204`). |
| `GET` | `/auth/me` | session | The signed-in user. |
| `POST` | `/auth/register` | none + CSRF | Only when `REGISTRATION_ENABLED=true`, otherwise `404`. Password 12–128 characters. `409 EMAIL_TAKEN`. |
| `GET` / `POST` | `/api-keys` | session | List keys (prefix, created, last used, revoked), or create one: `{name}` → `201 {id, name, key, prefix}`, the key shown **once**. At most 20 active keys. |
| `DELETE` | `/api-keys/{id}` | session | Revoke (`204`); the key stops working immediately. |

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/endpoints` | Register an endpoint. Returns the signing secret **once** (`201`). At most 50 endpoints per account (`422 ENDPOINT_LIMIT_REACHED`). |
| `POST` | `/endpoints/{id}/rotate-secret` | `{gracePeriodHours: 0..72}` (default 24). Returns the new secret **once**, with `previousSecretExpiresAt`. Until then every delivery carries two signatures, new and old. |
| `GET` | `/endpoints` | List endpoints, each with its `circuit` and 24h `health`. |
| `GET` / `PATCH` / `DELETE` | `/endpoints/{id}` | Read, update (`name`, `description`, `url`, `eventTypes`, `enabled`), or soft-delete. Deleting cancels queued deliveries. |
| `POST` | `/endpoints/{id}/test` | Queue a real `webhook.test` event to this endpoint. |
| `GET` | `/endpoints/{id}/health?window=1h\|24h\|7d` | Endpoint health over the window (default 24h). See [Circuit breaker and endpoint health](ARCHITECTURE.md#circuit-breaker-and-endpoint-health). |
| `POST` | `/endpoints/{id}/circuit/reset` | Close the endpoint's circuit by hand; queued deliveries resume on the next poll. |
| `POST` | `/events` | Submit an event. `Idempotency-Key` header required. Body at most 256 KB (`413`). 50 per second per credential, bursts of 100 (`429` with `Retry-After`). |
| `GET` | `/events`, `/events/{id}` | List events, with per-status delivery counts. Filter by `eventType`, `from`, `to`; cursor-paginated. Or get one event with its deliveries. |
| `GET` | `/deliveries` | Filter by `status` (repeatable), `endpointId`, `eventId`, `eventType`, `deadLetterReason`, `from` (inclusive), `to` (exclusive). Cursor-paginated. The dead-letter view is `status=DEAD_LETTERED`. |
| `GET` | `/deliveries/{id}` | Delivery with its attempt history, the payload sent, `replayEligibility` and the replay `chain`. |
| `POST` | `/deliveries/{id}/replay` | Replay a dead letter (`202` with the new delivery). See [Dead letters and replay](ARCHITECTURE.md#dead-letters-and-replay). |
| `GET` | `/metrics/overview?window=1h\|24h\|7d` | Overview numbers for the dashboard (definitions below). |

Endpoint management, replay and rotation need a session; the `GET` routes and `POST /events` accept either.

**Overview metrics.** All of these are computed from the database, using the database clock.

| Field | Definition |
|---|---|
| `eventsSubmitted` | Events created in the window. |
| `deliveriesSucceeded` | Deliveries whose `delivered_at` falls in the window. |
| `deliveriesDeadLettered` | Deliveries whose `dead_lettered_at` falls in the window, as a total and per reason. |
| `deliverySuccessRate` | `succeeded / (succeeded + deadLettered)` over the window. It is `null` when both are 0. |
| `current` | Pending, retrying and in-flight counts right now. These are not windowed. |
| `attempts` | Attempts started in the window that have finished: the succeeded count, the failed count (abandoned attempts count as failed), the success rate, and p50/p95 latency. |
| `recentActivity` | The 20 most recent attempts, including ones still in flight. |
| `endpointHealth` | Every endpoint with its circuit and health over the same window, worst first (failing, degraded, healthy, no data, disabled). |

The OpenAPI 3.1 document is at `/v3/api-docs` and Swagger UI is at `/swagger-ui.html`. The same document is committed as `frontend/openapi.json`; the dashboard's TypeScript types (`frontend/src/api/schema.ts`) are generated from it with `npm run gen:api`. A backend test fails when the live document and the committed file differ, and CI fails when the generated types are out of date, so the API and the dashboard cannot drift apart. To update both after an API change:

```bash
./mvnw -pl backend test -Dtest=OpenApiContractTest -Dopenapi.update=true
cd frontend && npm run gen:api
```

**Event submission:**

```json
POST /api/v1/events
Idempotency-Key: order-123

{ "eventType": "order.created", "payload": { "orderId": "123" }, "endpointIds": ["…"], "occurredAt": "2026-10-15T04:00:00Z" }
```

- `endpointIds` and `occurredAt` are optional.
- Without `endpointIds`, the event fans out to every enabled endpoint whose `eventTypes` filter matches: exact (`order.created`), prefix (`order.*`), or empty (all events).
- Repeating the same request with the same key returns `200` with `Idempotent-Replayed: true` and the original response. JSON key order and whitespace don't matter.
- Reusing a key with a different request returns `409 IDEMPOTENCY_KEY_REUSED`.
- Keys are scoped per account.

## What a delivery looks like

```http
POST /webhooks HTTP/1.1
content-type: application/json
webhook-id: 5778c16b-b6d8-4199-9fcc-7a7859b9750c
webhook-timestamp: 1760520000
webhook-signature: v1,<base64 HMAC-SHA256 of "id.timestamp.body">

{"id":"5778c16b-…","type":"order.created","createdAt":"2026-10-15T09:23:53Z","data":{"orderId":"1001"}}
```

Verification is described in [ARCHITECTURE.md](ARCHITECTURE.md#what-receivers-get), with working receivers in Java, Node.js and Python under [`examples/receivers`](../examples/receivers).
