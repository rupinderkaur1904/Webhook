# Security

This page describes the controls in place and the trade-offs that remain. The threat model and design reasoning are in [PLAN.md](PLAN.md) §8.

**Accounts and sessions**
- Passwords are hashed with **Argon2id**. Hashes from before Stage 5 (bcrypt) still verify and are re-hashed with Argon2id at the next login.
- An unknown email is checked against a dummy hash, so the response time and the error (`INVALID_CREDENTIALS`) are the same as for a wrong password.
- Login creates a **new session id** (any earlier session on that browser is invalidated) and a new CSRF token.
- Sessions are stored in PostgreSQL (Spring Session JDBC): they survive restarts and work across instances, and logout deletes them. The cookie `WEBHOOKS_SESSION` is `HttpOnly`, `Secure` and `SameSite=Lax`; it times out after 8 hours idle (`SESSION_TIMEOUT`).
- Self-registration is off unless `REGISTRATION_ENABLED=true`. The first account comes from `BOOTSTRAP_EMAIL` / `BOOTSTRAP_PASSWORD`.

**API keys**
- 32 random bytes; only the SHA-256 hash is stored, and the key is shown once. Keys are created, listed and revoked from the dashboard (or `/api-keys` with a session). `last_used_at` is tracked.

**Signing secrets**
- Encrypted at rest with AES-256-GCM using `SECRET_ENCRYPTION_KEY`; the ciphertext is bound to its endpoint.
- Write-only: a secret appears in a response only when it is created or rotated. Responses show `secretPrefix` so secrets can be told apart.
- **Rotation** keeps the old secret signing alongside the new one for a grace period (0–72 hours), so receivers can switch without rejecting anything.

**SSRF**
- At registration: `https` only, no credentials or fragment, and every resolved address must be public. Loopback, private, link-local and cloud-metadata ranges, documentation ranges, numeric and hex IP forms (`2130706433`, `0x7f.1`) and IPv6 forms that embed them are all blocked.
- At send time the HTTP client's DNS resolver repeats the check and connects only to addresses that passed, closing the DNS-rebinding gap. Redirects are never followed.
- `SSRF_ALLOWED_HOSTS` allowlists exact `host[:port]` entries. It's used for the local simulator and must be empty in production.

**Request limits**
- API request bodies are capped at 256 KB (`413 PAYLOAD_TOO_LARGE`), checked from `Content-Length` and counted while streaming for chunked bodies. JSON nesting is capped at 64 levels.
- Rate limits (Bucket4j, in memory): event ingestion 50/s per credential with bursts of 100; login 5 a minute per client address and per email. Over the limit: `429 RATE_LIMITED` with `Retry-After`.
- At most 50 endpoints and 20 active API keys per account.

**Browser hardening and logging**
- Responses carry a strict `Content-Security-Policy` (`default-src 'self'`, no inline scripts, `frame-ancestors 'none'`), `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff` and `Referrer-Policy: strict-origin-when-cross-origin`. The dashboard's HTML and assets are served by Spring Boot with the same headers, so there is no second web server to configure.
- Log lines carry the request id. No code path logs a secret or key; as a backstop, anything shaped like `whsec_…` or `whk_…` is masked in log output, in both the plain-text and the JSON format.

**Operational endpoints**
- `/actuator/health` is public (liveness and readiness, no details).
- `/actuator/prometheus` needs `Authorization: Bearer $METRICS_TOKEN` (at least 24 characters, compared in constant time). Without `METRICS_TOKEN` it refuses every request. The token opens nothing else, and no other actuator endpoint is exposed.

**Outbound limits:** 5 s connect timeout, plus a hard 15 s deadline for the whole response. Only the first 2 KB of the response body is read and stored, after stripping control characters. Only the `content-type`, `content-length`, `retry-after` and `date` response headers are stored.

## Reverse proxies and client addresses

Behind a hosting proxy, the TCP peer of every request is the proxy, not the visitor. The app needs the real client address for the per-address login limit, and the original protocol to know a request arrived over HTTPS (HSTS is only sent on secure requests).

- **Forwarding headers are believed only from configured proxies.** `TRUSTED_PROXIES` is a comma-separated list of literal IP addresses or CIDR ranges (no host names, so no DNS lookups at startup). When it is set, Tomcat's `RemoteIpValve` reads `X-Forwarded-For` and `X-Forwarded-Proto`, but only on requests whose TCP peer is inside those ranges. Any other request keeps its peer address, and its forwarding headers are ignored.
- **The client is the rightmost untrusted address** in `X-Forwarded-For`. A client can put anything in the header it sends, but the proxy appends the address it actually saw, and the app walks the list from the right, skipping only trusted proxies. Values the client prepended are never reached.
- **`X-Forwarded-Host` and `X-Forwarded-Port` are never believed,** and Spring's own forwarded-header handling is off (`server.forward-headers-strategy: none`), so nothing else interprets those headers.
- **Unset means trust nobody.** With `TRUSTED_PROXIES` empty (the default), no valve is installed. An invalid entry stops the app at startup rather than silently trusting nothing or everything.
- **Finding the right range.** `PROXY_DIAGNOSTICS=true` logs the peer address and the forwarding headers of each request (sanitised, truncated) without trusting them, so the proxy's real source range can be observed before it is trusted. [DEPLOYMENT.md](DEPLOYMENT.md#trusted-proxies) gives the procedure for Fly.io.

Tests (`TrustedProxyTest`, `UntrustedProxyTest`) cover: a spoofed `X-Forwarded-For` from an untrusted peer cannot dodge the login limit; a spoofed `X-Forwarded-Proto: https` from an untrusted peer does not produce HSTS; from a trusted proxy, each forwarded client gets its own login allowance, values a client prepends are ignored, and forwarded HTTPS produces HSTS. Removing the valve makes these tests fail.

## The public receiver simulator

The simulator is a demo tool, and a public one would otherwise let anyone reconfigure receivers or read logged payloads. With `SIMULATOR_CONTROL_TOKEN` set, every route except `POST /receivers/{name}` and `GET /healthz` needs `Authorization: Bearer <token>`, compared in constant time. It also caps request bodies at 1 MB, receiver names at 64 safe characters, receivers at 50 and logged requests at 200 per receiver (bodies truncated to 8 KB), so it cannot be used to exhaust memory. Without a token the simulator **refuses to start**, unless `SIMULATOR_OPEN_CONTROL_API=true` explicitly opens the control API. Docker Compose sets that flag for local use; a deployment that forgets the token therefore fails at startup instead of exposing the control API. A POST to `/` or `/receivers` is also open: it only returns a 404 naming the right path (`/receivers/{name}`).

## Known trade-offs

- **Targeted login slowdown.** The per-email limit lets anyone slow one known account's logins to 5 a minute. It is a throttle, not a lockout, and it is the price of resisting password guessing spread over many addresses.
- **Rate limits are per instance.** With N instances a client can reach N times the configured rate. The demo runs one instance.
- **CSRF uses a double-submit cookie,** the standard pattern for a single-page app. It relies on the dashboard being the only site that can set cookies for its domain, so do not serve it next to untrusted subdomains.
- **The body-size filter wraps `getInputStream()` but not `getReader()`.** Nothing reads request bodies through the reader today (Jackson uses the stream), so this is not exploitable; it is noted for future code.
- **Local development values are public.** The `local` profile (used by Docker Compose) has a well-known encryption key, admin password, API key and metrics token, plain-HTTP cookies and an SSRF allowlist. It must never be exposed to a network you do not control.

## Reporting a vulnerability

Please report security problems privately to the repository owners rather than in a public issue.
