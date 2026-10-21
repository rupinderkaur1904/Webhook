# Webhook Delivery & Reliability Platform

A developer-facing platform that durably accepts events and delivers them to registered HTTP endpoints. It signs every request, retries failures with backoff, keeps undeliverable events as replayable dead letters, survives crashes mid-delivery, and makes every attempt visible in a dashboard.

**Stack:** Spring Boot 4 (Java 21), PostgreSQL 16, React 19 with Mantine, one Docker image. A separate receiver simulator plays the customer's side.

## Try it in 5 minutes

With Docker Desktop (Windows, macOS) or Docker Engine (Linux):

```bash
git clone https://github.com/rupinderkaur1904/Webhook.git webhook-platform
cd webhook-platform
git checkout stage-7-release
docker compose up -d --build
```

Open http://localhost:8080 for the landing page, or http://localhost:8080/overview to sign in to the dashboard with `dev@localhost` / `local-dev-password`. The receiver simulator is at http://localhost:9090.

**[docs/LOCAL-TESTING.md](docs/LOCAL-TESTING.md)** is the step-by-step guide (written for Windows 11): prerequisites, URLs, a manual walkthrough of every feature, and how to stop and restart without losing data.

## Features

- **Durable ingestion:** `POST /api/v1/events` with an `Idempotency-Key`; the event and its deliveries are committed in one transaction before `202` is returned. Same key and body: the original answer; same key, different body: `409`.
- **Signed deliveries** (Standard Webhooks: `webhook-id`, `webhook-timestamp`, `webhook-signature`), with secret rotation and a grace period where both secrets sign.
- **Retries:** classification of every outcome, exponential backoff with jitter, `Retry-After`, a per-delivery attempt budget.
- **Dead letters and replay:** exhausted or permanently failed deliveries are kept with their full history and can be replayed as linked new deliveries.
- **Crash recovery:** a PostgreSQL queue with leases and fencing tokens; a reaper recovers work from crashed workers, and late results cannot overwrite newer state.
- **Circuit breaker and endpoint health:** a failing endpoint is paused without burning its backlog's retries; one probe decides when it recovers.
- **Security:** dashboard sessions with CSRF protection, Argon2id, API keys limited to ingestion and reads, SSRF protection at registration and at send time, rate and size limits, trusted-proxy handling, secret-safe logs.
- **Observability:** an overview dashboard, Prometheus metrics, structured JSON logs with delivery context.

## Documentation

| Document | Contents |
|---|---|
| [LOCAL-TESTING.md](docs/LOCAL-TESTING.md) | Run everything locally with Docker Compose and try each feature. |
| [SETUP.md](docs/SETUP.md) | Developer setup from source, configuration, the demo script and the simulator API. |
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | Components, delivery flow, retries, recovery, replay, circuit breaker, packaging, observability. |
| [API.md](docs/API.md) | REST API reference, authentication, OpenAPI and generated types. |
| [SECURITY.md](docs/SECURITY.md) | Security controls, trusted proxies, the public simulator, known trade-offs. |
| [TESTING.md](docs/TESTING.md) | Every test suite, what it covers and how to run it. |
| [DEPLOYMENT.md](docs/DEPLOYMENT.md) | Fly.io + Neon deployment, trusted-proxy setup, configuration and operations. |
| [AUDIT-REPORT.md](docs/AUDIT-REPORT.md) | Engineering audit of 2026-10-16: what was verified, defects fixed, remaining risks. |
| [examples/receivers](examples/receivers) | Signature-verifying receivers in Java, Node.js and Python. |
| [PLAN.md](docs/PLAN.md) | The original design and its decisions. |

## Repository layout

- `backend/`: the Spring Boot platform (API, delivery worker, persistence, Flyway migrations). In the Docker image it also serves the dashboard.
- `frontend/`: the React + TypeScript dashboard (Vite, Mantine, TanStack Query), with types generated from the OpenAPI document, Vitest component tests and Playwright end-to-end tests.
- `receiver-simulator/`: a controllable receiver that verifies signatures independently and logs every request.
- `examples/receivers/`: receiver examples with shared test vectors.
- `scripts/`: `demo.sh` (every scenario, scripted) and `deploy-fly.sh`.
- `deploy/fly/`: Fly.io configuration.

## Status

| Stage | Scope | State |
|---|---|---|
| 1. End-to-end foundation | Schema, endpoints, idempotent ingestion, PostgreSQL queue + worker, signing, simulator, minimal dashboard | Done |
| 2. Delivery reliability | Retry classification, backoff with jitter, `Retry-After`, exhaustion | Done |
| 3. Developer experience | Full dashboard, dead-letter view, replay, scripted failure simulator | Done |
| 4. Recovery and concurrency | Lease reaper, crash recovery, stale-worker and duplicate scenarios | Done |
| 5. Security hardening | Sessions, API keys, SSRF matrix, rate and request limits | Done |
| 6. Reliability and observability | Circuit breaker, endpoint health, metrics, structured logging | Done |
| 7. Release | Single Docker image, Compose, trusted proxies, OpenAPI types, Playwright, receiver examples, docs | Done; public deployment pending |

## Known limitations

- **Crash recovery adds latency** of up to `lease-duration + reaper-interval` (75s with the defaults) for work that was in flight when a process died.
- **A paused worker can duplicate a send.** If a worker pauses for longer than its lease (a long GC pause or a frozen VM), it can send at the same moment as the worker that reclaimed the delivery. Fencing keeps the database correct, but the receiver gets the request twice. This is inherent to at-least-once delivery, and why receivers deduplicate on `webhook-id`.
- **Rate limits are per instance.** With N instances a client can reach N times the configured rate.
- **No ordering guarantee** between events.
- **A dead letter stays replayable after a later replay of it succeeded.** Replaying it again sends the event once more. The delivery detail shows the whole chain, so the earlier success is visible before you click.
- **An open circuit's queued rows are still scanned.** The claim query skips them but PostgreSQL reads past them; fine at this scale, a scaling risk for very large backlogs.
- **The circuit opens on a run of consecutive failures,** not on a failure rate. An endpoint failing every other request (like the `intermittent` demo) stays closed and shows as `DEGRADED`.
- **Changing an endpoint's URL keeps its circuit state.** A circuit opened by the old URL stays open until its probe succeeds against the new one, or until **Close circuit now**.
- **The claim query slows with very large parked backlogs** (about 0.3 s per claim with 200,000 deliveries parked behind open circuits, measured in the Stage 6 review). Fine at demo scale.
