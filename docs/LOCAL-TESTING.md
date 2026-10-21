# Run and test the platform on your own computer

This guide runs the whole platform on one machine with Docker Compose: PostgreSQL, the Spring Boot platform (which also serves the React dashboard) and the receiver simulator. It needs no cloud account and no credentials. The commands are written for **Windows 11 with PowerShell**; macOS and Linux users can run the same `git` and `docker compose` commands in a terminal.

## 1. Install the prerequisites (once)

| What | Why | Where |
|---|---|---|
| **Docker Desktop** | Runs the database, the platform and the simulator. During setup keep **"Use WSL 2 instead of Hyper-V"** ticked. Start Docker Desktop once after installing and wait until it says *Engine running*. | https://www.docker.com/products/docker-desktop/ |
| **Git for Windows** | Downloads the code. The default installer options are fine. | https://git-scm.com/download/win |

Docker needs about **4 GB of free memory** and **5 GB of disk** for the images. You do **not** need Java, Node.js or PostgreSQL on your computer: everything is built inside Docker.

Check both are ready by opening **PowerShell** (Start menu, type *PowerShell*) and running:

```powershell
docker --version
docker compose version
git --version
```

Each command should print a version number. If `docker` says it cannot connect, start Docker Desktop and wait for it.

The platform uses three ports on your computer: **8080** (dashboard and API), **9090** (receiver simulator) and **5432** (database). To check that nothing else uses them:

```powershell
netstat -ano | findstr ":8080 :9090 :5432"
```

No output means all three are free. If one is taken, see [Changing ports](#changing-ports-or-the-admin-account).

## 2. Download the code

Pick a folder for the project, for example your Documents folder, and clone the repository there:

```powershell
cd $HOME\Documents
git clone https://github.com/rupinderkaur1904/Webhook.git webhook-platform
cd webhook-platform
git checkout stage-7-release
```

`stage-7-release` is the branch with everything, including the dashboard served by Spring Boot, the Docker setup and this guide. GitHub may ask you to sign in if the repository is private; Git for Windows opens a browser window for that.

**Already have an older copy?** Clone a fresh copy into a new folder as above. An older checkout on Windows may have converted script line endings to the Windows style, which breaks the build inside Docker; the repository now prevents that, but only for files checked out after the change.

**Getting later updates** (from inside the project folder):

```powershell
git checkout stage-7-release
git pull
docker compose up -d --build
```

## 3. Start everything

From the project folder:

```powershell
docker compose up -d --build
```

- The **first run takes about 5 to 10 minutes**: Docker downloads the base images and builds the dashboard and both Java applications. Later starts take seconds.
- `-d` runs everything in the background, so you get your prompt back.

Check that the three services are up:

```powershell
docker compose ps
```

Wait until the `app` line says **(healthy)**; it takes about 20 to 40 seconds after the build finishes. Run the command again to refresh. When all three show `Up`, and `app` and `simulator` show `(healthy)`, the platform is ready.

To watch the platform's log while it starts (press `Ctrl+C` to stop watching; the app keeps running):

```powershell
docker compose logs -f app
```

On a fresh database you will see Flyway apply the 5 migrations (`Successfully applied 5 migrations`) and then `Bootstrap account ready`, which means the first dashboard account exists.

## 4. Open it

| What | URL |
|---|---|
| **Landing page** | http://localhost:8080 |
| **Dashboard** | http://localhost:8080/overview |
| API documentation (Swagger UI) | http://localhost:8080/swagger-ui.html |
| OpenAPI document | http://localhost:8080/v3/api-docs |
| Health check | http://localhost:8080/actuator/health |
| **Receiver simulator** (health) | http://localhost:9090/healthz |
| Simulator request log for a receiver named `orders` | http://localhost:9090/receivers/orders/requests |

**Sign in to the dashboard** with:

- Email: `dev@localhost`
- Password: `local-dev-password`

For calls from scripts, the local setup also creates the API key `whk_local_dev_key_0000000000000000000000`.

These are well-known development values for your own computer only. The deployed version uses its own secrets.

## 5. Manual test walkthrough

Follow these steps in order. Steps that change the simulator's behaviour use PowerShell's `Invoke-RestMethod`; copy each block into PowerShell as it is.

### 5.1 Sign in

1. Open http://localhost:8080. You see the Relay landing page. Click **Open dashboard**: you land on the sign-in page.
2. Try a wrong password first: you get an error and stay on the sign-in page.
3. Sign in with `dev@localhost` / `local-dev-password`. You land on **Overview**.

Logins are limited to 5 a minute per address and per email. If you see *Too many requests*, wait a minute.

### 5.2 Prepare a receiver in the simulator

The simulator plays the part of a customer's webhook receiver. Each receiver has a name; we use `orders`. Tell it to answer `200 OK`:

```powershell
$sim = "http://localhost:9090/receivers/orders"
Invoke-RestMethod -Method Put -Uri "$sim/config" -ContentType "application/json" -Body '{"status": 200}'
```

### 5.3 Create an endpoint

1. In the dashboard, open **Endpoints** and click **Register endpoint**.
2. Name: `Orders`. URL: **`http://simulator:9090/receivers/orders`**
   - The URL must end in `/receivers/<name>`. `<name>` can be anything (letters, digits, `-`, `_`) and the simulator creates that receiver on first use; the commands in this guide use `orders`. The bare address `http://simulator:9090` is the simulator itself, not a receiver: deliveries to it get `HTTP 404` and are dead-lettered at once, because a 4xx is a permanent failure.
   - Use `simulator`, not `localhost`: the platform runs inside Docker and reaches the simulator by its service name. For safety the platform refuses private and local addresses; the local setup allows only the simulator.
3. Click **Register**. The **signing secret** (`whsec_…`) is shown **once**. Click **Copy**, then **Done**.
4. Give the secret to the simulator so it can check signatures. Replace the value between the quotes with the secret you copied:

```powershell
$secret = "whsec_PASTE_YOUR_SECRET_HERE"
Invoke-RestMethod -Method Put -Uri "$sim/config" -ContentType "application/json" -Body (@{ status = 200; secret = $secret } | ConvertTo-Json)
```

### 5.4 Send an event and see it delivered

**From the dashboard:** open the `Orders` endpoint and click **Send test event**. Or open **Events**, click **Publish event**, enter an event type such as `order.created` and a JSON payload, and click **Publish event**. The dialog lists the deliveries it queued, one per matching endpoint, or says that no endpoint subscribes to that type. *Accepted* only means stored and queued; open a delivery to see whether it was delivered.

**Or as a producer would, through the API:**

```powershell
$api = "http://localhost:8080/api/v1"
$key = @{ Authorization = "Bearer whk_local_dev_key_0000000000000000000000"; "Idempotency-Key" = "order-1001" }
$body = @{ eventType = "order.created"; payload = @{ orderId = "1001"; amount = 4999 } } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "$api/events" -Headers $key -ContentType "application/json" -Body $body
```

Then:

1. Open **Deliveries**. The delivery shows **Delivered** with 1 attempt and HTTP 200.
2. Click the delivery id. The detail page shows the attempt history, the exact payload sent and the event id (`webhook-id`).
3. Open http://localhost:9090/receivers/orders/requests. The simulator logged the request with `"signatureResult": "VALID"`: it verified the signature with the secret independently.

**Idempotency:** run the same `Invoke-RestMethod ... "$api/events"` command again with the same `Idempotency-Key`. You get the same answer and **no** new delivery. Change the payload but keep the key, and the API refuses it with `409 Conflict`.

### 5.5 Retries: the receiver fails, then recovers

Script the simulator to fail twice with `500`, then succeed:

```powershell
Invoke-RestMethod -Method Put -Uri "$sim/config" -ContentType "application/json" -Body (@{ status = 200; secret = $secret; script = @(@{ status = 500 }, @{ status = 500 }) } | ConvertTo-Json -Depth 4)
```

Click **Send test event** again and open the new delivery from **Deliveries**. Within about 10 seconds it goes **Retrying**, then **Delivered** on **attempt 3**: two `HTTP 500` attempts marked *retryable*, then `HTTP 200`. The local setup uses short delays (2 s base, exponential with jitter) so you can watch it happen; production waits longer.

### 5.6 Dead letters: the receiver keeps failing

Make the receiver fail every time:

```powershell
Invoke-RestMethod -Method Put -Uri "$sim/config" -ContentType "application/json" -Body (@{ status = 503; secret = $secret } | ConvertTo-Json)
```

Send another test event. The local setup gives up after 4 attempts, which takes about 15 to 30 seconds. The delivery then shows **Dead letter** with the reason *Retries exhausted* and appears on the **Dead letters** page.

A `400` from the receiver is different: it is treated as permanent and dead-lettered after one attempt, with no retries. Try it with `status = 400` if you like.

### 5.7 Replay a dead letter

1. "Fix" the receiver:

   ```powershell
   Invoke-RestMethod -Method Put -Uri "$sim/config" -ContentType "application/json" -Body (@{ status = 200; secret = $secret } | ConvertTo-Json)
   ```

2. On **Dead letters**, click **Replay** on the delivery. A side panel shows what the replay sends (same `webhook-id`, the endpoint's current URL, a fresh retry budget). Click **Replay delivery**. You see *Replay queued as a new delivery*.
3. Open the original delivery: it stays **Dead letter** (history is never rewritten) and its **Replay chain** shows the new delivery, which becomes **Delivered**.
4. The simulator's request log shows the replay carries the **same `webhook-id`** as the original attempts, so a real receiver can recognise it as the same event.

### 5.8 Secret rotation

1. On the endpoint page, click **Rotate** next to *Signing secret*, keep a grace period of a few hours and click **Rotate secret**. Copy the new secret (shown once) and click **Done**.
2. Send a test event. The simulator still shows `VALID` with the **old** secret, because during the grace period every delivery carries two signatures.
3. Update the simulator to the new secret (as in step 5.3, with the new value) and send another test event. It is `VALID` again.

### 5.9 Edit an endpoint

On the endpoint page, click **Edit** to change its name, URL, description or event types. Deliveries that are queued or retrying go to the new URL; dead letters are not retried, so replay them after saving (step 5.7). This is how you fix an endpoint registered with the wrong URL.

To remove an endpoint, click **Delete** on its page and confirm. Its pending and retrying deliveries are cancelled; its history stays visible but can no longer be replayed. To pause deliveries instead, use the **Enabled** switch.

### 5.10 API keys

1. Open **API keys** and click **Create API key**. The full key is shown **once**.
2. Click **Revoke** on it and confirm. Requests with that key are refused immediately.

API keys can only submit events and read data. They cannot change endpoints, replay or manage keys; those need a dashboard sign-in.

### 5.11 Dashboard metrics and endpoint health

1. Open **Overview** and switch between **1h**, **24h** and **7d**. The tiles show events submitted, delivered and dead-lettered counts, the delivery and attempt success rates, attempt latency (p50/p95), and what is waiting to retry or in flight right now.
2. **Endpoint health** lists each endpoint, worst first. After the failures above, `Orders` shows as degraded.
3. On the endpoint page, the **Health** card shows the attempt success rate, latency, dead letters and the last failure. Below it, the circuit breaker line says whether deliveries are paused. *Circuit closed* means they are not paused; it does not mean there were no failures. The circuit only opens after 10 consecutive failures that suggest the receiver is down (5xx, timeouts, connection errors), not on 4xx responses.
4. **Recent attempts** lists the latest attempts across all endpoints.

**Circuit breaker (optional):** set the receiver to `status = 500` and send 10 or more test events. After 10 consecutive failures the endpoint's circuit **opens**: the endpoint page shows it, and queued deliveries wait without using up their retries. Set the receiver back to `200`; after a short cool-down (5 s locally) one probe delivery succeeds, the circuit closes and the backlog is delivered. **Close circuit now** on the endpoint page closes it by hand.

**Prometheus metrics** (for monitoring tools) need a token:

```powershell
Invoke-RestMethod -Uri "http://localhost:8080/actuator/prometheus" -Headers @{ Authorization = "Bearer local-dev-metrics-token-00000000" } | Select-String "webhooks_"
```

### 5.12 Sign out

Click **Sign out** in the header. You return to the sign-in page, and the old session no longer works.

## 6. Scripted demos (optional)

`scripts/demo.sh` runs every scenario automatically (success, retries, rate limiting, timeouts, dead letters, replay, idempotency, signatures, crash recovery, circuit breaker, rotation and security checks). It is a bash script that also needs `jq`, so on Windows run it from **WSL** (Ubuntu from the Microsoft Store), where `sudo apt install jq curl` installs what it needs:

```bash
SIMULATOR_URL_FROM_API=http://simulator:9090 scripts/demo.sh success
SIMULATOR_URL_FROM_API=http://simulator:9090 scripts/demo.sh replay
SIMULATOR_URL_FROM_API=http://simulator:9090 CRASH_CMD="docker compose kill app" START_CMD="docker compose start app" scripts/demo.sh crash
```

Run `scripts/demo.sh` with no argument for the full list (see [SETUP.md](SETUP.md#demo)).

## 7. Stop, restart and reset

| What you want | Command | Your data |
|---|---|---|
| Pause everything | `docker compose stop` | Kept |
| Start again after a pause or a reboot | `docker compose start` (or `docker compose up -d`) | Kept |
| Stop and remove the containers | `docker compose down` | **Kept**: the database lives in the `pgdata` volume |
| Rebuild after `git pull` | `docker compose up -d --build` | Kept |
| Rebuild one service | `docker compose up -d --build app` (or `simulator`) | Kept |
| **Delete everything and start from an empty database** | `docker compose down -v` | **Deleted**: endpoints, events, deliveries, API keys |

Endpoints, events, deliveries, attempts, API keys and sign-in sessions are stored in PostgreSQL, so they survive every command above except `down -v`. The **simulator's request log is kept in memory only** and starts empty after the simulator restarts; its receivers also need their config set again (step 5.2).

Stopping is graceful: the platform gets 30 seconds to finish deliveries that are in flight. Anything still unfinished is picked up again on the next start; nothing is lost.

## Changing ports or the admin account

Copy the example settings file and edit it in Notepad:

```powershell
Copy-Item .env.example .env
notepad .env
```

- `APP_PORT`, `SIMULATOR_PORT`, `DB_PORT`: the ports on your computer. If you change `APP_PORT` to `8081`, the dashboard is at http://localhost:8081. Inside Docker nothing changes, so the endpoint URL stays `http://simulator:9090/...`.
- `ADMIN_EMAIL`, `ADMIN_PASSWORD` (at least 12 characters): the first dashboard account. It is created on the first start of an **empty** database. Changing them later creates an additional account and does not change the password of an existing one; to start over, use `docker compose down -v`.

Then apply the change with `docker compose up -d`.

## Troubleshooting

| Symptom | Fix |
|---|---|
| `error during connect` / `Cannot connect to the Docker daemon` | Start Docker Desktop and wait for *Engine running*. |
| `port is already allocated` | Another program uses that port. Change it in `.env` (see above). |
| Build fails with `/bin/sh^M` or `mvnw: not found` | The checkout has Windows line endings. Clone a fresh copy (step 2). |
| `app` stays `(health: starting)` for several minutes | Run `docker compose logs app` and look for the first `ERROR`. |
| An endpoint with `http://localhost:9090/...` is refused | Use `http://simulator:9090/receivers/<name>`. |
| Delivery fails with `HTTP 404` and is dead-lettered after one attempt | The endpoint URL is missing `/receivers/<name>`; the attempt's response body says so. Click **Edit** on the endpoint, fix the URL, then **Replay** the dead letter. |
| Simulator log: *control API is open* | Expected locally: Compose sets `SIMULATOR_OPEN_CONTROL_API=true` so you can configure receivers without a token. A public simulator uses `SIMULATOR_CONTROL_TOKEN` instead, and without either the simulator refuses to start. |
| Simulator exits with *SIMULATOR_CONTROL_TOKEN is not set* | It was started outside Compose without a token. Set `SIMULATOR_CONTROL_TOKEN`, or `SIMULATOR_OPEN_CONTROL_API=true` on your own machine only. |
| Deliveries fail with `connection refused` | The simulator is not running: `docker compose ps`, then `docker compose start simulator`. |
| Signature shows `INVALID_SIGNATURE` in the simulator | The simulator has an old secret. Set the current one (step 5.3). |
| *Too many requests* on sign-in | Wait one minute. |
