#!/usr/bin/env python3
"""Local load benchmark for the webhook platform (standard library only).

Runs against a local stack (docker compose up) and the receiver simulator. It creates its own
endpoints, simulator receivers and API keys, removes them afterwards, and never touches other data.

    python3 scripts/bench.py --events 500 --endpoints 5 --concurrency 16
    python3 scripts/bench.py --events 2000 --endpoints 10 --concurrency 32 --keys 10 \
        --receiver-delay-ms 50 --failure-rate 0.2

Ingestion is rate limited per credential (50/s, burst 100 by default), so the script spreads
requests over --keys API keys. Results are local measurements on one machine: they describe this
environment, not production capacity.
"""
import argparse
import http.cookiejar
import json
import statistics
import threading
import time
import urllib.error
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime


def pct(values, p):
    if not values:
        return None
    ordered = sorted(values)
    k = max(0, min(len(ordered) - 1, round(p / 100 * (len(ordered) - 1))))
    return ordered[k]


def iso(ts):
    return datetime.fromisoformat(ts.replace("Z", "+00:00")).timestamp()


class Client:
    def __init__(self, api, sim, sim_token):
        self.api, self.sim = api.rstrip("/"), sim.rstrip("/")
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))
        self.sim_headers = {"Authorization": f"Bearer {sim_token}"} if sim_token else {}

    def _xsrf(self):
        return next((c.value for c in self.jar if c.name == "XSRF-TOKEN"), "")

    def call(self, method, path, body=None, headers=None, session=True, base=None):
        data = json.dumps(body).encode() if body is not None else None
        h = {"Content-Type": "application/json", **(headers or {})}
        if session and method not in ("GET", "HEAD"):
            h["X-XSRF-TOKEN"] = self._xsrf()
        req = urllib.request.Request((base or self.api) + path, data=data, method=method, headers=h)
        opener = self.opener if session else urllib.request.build_opener()
        try:
            with opener.open(req, timeout=30) as r:
                raw = r.read()
                return r.status, (json.loads(raw) if raw and r.headers.get_content_type().endswith("json") else raw)
        except urllib.error.HTTPError as e:
            return e.code, e.read()

    def login(self, email, password):
        self.call("GET", "/api/v1/auth/csrf")
        status, _ = self.call("POST", "/api/v1/auth/login", {"email": email, "password": password})
        if status != 200:
            raise SystemExit(f"login failed: HTTP {status} (5 logins a minute per address and email)")

    def sim_call(self, method, path, body=None):
        return self.call(method, path, body, headers=self.sim_headers, session=False, base=self.sim)

    def metrics(self, token):
        status, raw = self.call("GET", "/actuator/prometheus", headers={"Authorization": f"Bearer {token}"}, session=False)
        values = {}
        if status == 200:
            for line in raw.decode().splitlines():
                if line.startswith(("webhooks_queue_due ", "webhooks_queue_oldest_due_seconds", "hikaricp_connections_active",
                                    "hikaricp_connections_pending", "hikaricp_connections_max")):
                    name, _, value = line.rpartition(" ")
                    values[name.split("{")[0]] = float(value)
        return values


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--api", default="http://localhost:8080")
    ap.add_argument("--sim", default="http://localhost:9090", help="simulator as seen from this machine")
    ap.add_argument("--sim-from-app", default="http://simulator:9090", help="simulator as seen from the platform")
    ap.add_argument("--sim-token", default="", help="SIMULATOR_CONTROL_TOKEN, if the simulator has one")
    ap.add_argument("--email", default="dev@localhost")
    ap.add_argument("--password", default="local-dev-password")
    ap.add_argument("--metrics-token", default="local-dev-metrics-token-00000000")
    ap.add_argument("--events", type=int, default=200)
    ap.add_argument("--endpoints", type=int, default=4)
    ap.add_argument("--concurrency", type=int, default=8, help="parallel producer threads")
    ap.add_argument("--keys", type=int, default=4, help="API keys to spread the per-key rate limit over (max 20)")
    ap.add_argument("--receiver-delay-ms", type=int, default=0)
    ap.add_argument("--failure-rate", type=float, default=0.0, help="share of receiver responses that are 503")
    ap.add_argument("--payload-bytes", type=int, default=512)
    ap.add_argument("--timeout", type=int, default=600, help="seconds to wait for deliveries to finish")
    ap.add_argument("--out", help="write the JSON summary here")
    args = ap.parse_args()

    run = uuid.uuid4().hex[:6]
    c = Client(args.api, args.sim, args.sim_token)
    c.login(args.email, args.password)
    endpoints, keys = [], []
    try:
        for i in range(args.endpoints):
            name = f"bench-{run}-{i}"
            status, body = c.call("POST", "/api/v1/endpoints", {"name": name, "url": f"{args.sim_from_app}/receivers/{name}"})
            if status != 201:
                raise SystemExit(f"endpoint create failed: HTTP {status} {body!r}")
            behaviour = {"status": 200, "delayMs": args.receiver_delay_ms, "secret": body["secret"]}
            if args.failure_rate:
                behaviour.update(failureRate=args.failure_rate, failureStatus=503, seed=i)
            if c.sim_call("PUT", f"/receivers/{name}/config", behaviour)[0] != 200:
                raise SystemExit("simulator config failed (is SIMULATOR_CONTROL_TOKEN needed?)")
            endpoints.append((body["endpoint"]["id"], name))
        for i in range(args.keys):
            status, body = c.call("POST", "/api/v1/api-keys", {"name": f"bench-{run}-{i}"})
            if status != 201:
                raise SystemExit(f"api key create failed: HTTP {status}")
            keys.append((body["id"], body["key"]))

        payload = {"orderId": "x", "pad": "p" * max(0, args.payload_bytes - 32)}
        latencies, statuses, deliveries = [], {}, []
        lock = threading.Lock()

        def submit(n):
            key = keys[n % len(keys)][1]
            endpoint_id = endpoints[n % len(endpoints)][0]
            body = json.dumps({"eventType": "bench.event", "payload": {**payload, "n": n}, "endpointIds": [endpoint_id]}).encode()
            req = urllib.request.Request(args.api + "/api/v1/events", data=body, method="POST", headers={
                "Content-Type": "application/json", "Authorization": f"Bearer {key}", "Idempotency-Key": f"bench-{run}-{n}"})
            for _ in range(20):
                t0 = time.perf_counter()
                try:
                    with urllib.request.urlopen(req, timeout=30) as r:
                        result = json.loads(r.read())
                        code = r.status
                except urllib.error.HTTPError as e:
                    code, result = e.code, None
                    retry_after = float(e.headers.get("Retry-After") or 1)
                elapsed = (time.perf_counter() - t0) * 1000
                with lock:
                    statuses[code] = statuses.get(code, 0) + 1
                    if code == 202:
                        latencies.append(elapsed)
                        deliveries.extend(d["id"] for d in result["deliveries"])
                if code != 429:
                    return
                time.sleep(retry_after)

        metrics_samples = []
        start = time.perf_counter()
        with ThreadPoolExecutor(args.concurrency) as pool:
            list(pool.map(submit, range(args.events)))
        ingest_seconds = time.perf_counter() - start

        deadline = time.time() + args.timeout
        finished = {}
        while time.time() < deadline:
            m = c.metrics(args.metrics_token)
            if m:
                metrics_samples.append(m)
            finished = {}
            for endpoint_id, _ in endpoints:
                cursor = None
                while True:
                    q = f"/api/v1/deliveries?endpointId={endpoint_id}&limit=200" + (f"&cursor={cursor}" if cursor else "")
                    status, page = c.call("GET", q)
                    for d in page["items"]:
                        finished[d["id"]] = d
                    cursor = page.get("nextCursor")
                    if not cursor:
                        break
            open_count = sum(1 for d in finished.values() if d["status"] in ("PENDING", "RETRYING", "IN_FLIGHT"))
            if open_count == 0 and len(finished) >= len(deliveries):
                break
            time.sleep(1)
        total_seconds = time.perf_counter() - start

        done = [d for d in finished.values() if d["status"] == "DELIVERED"]
        completion = [(iso(d["deliveredAt"]) - iso(d["createdAt"])) * 1000 for d in done]
        by_status = {}
        for d in finished.values():
            by_status[d["status"]] = by_status.get(d["status"], 0) + 1
        attempts = [d["attemptCount"] for d in finished.values()]
        summary = {
            "config": {k: v for k, v in vars(args).items() if k not in ("password", "metrics_token", "sim_token")},
            "ingestion": {
                "accepted": len(latencies), "http_statuses": statuses, "seconds": round(ingest_seconds, 2),
                "events_per_second": round(len(latencies) / ingest_seconds, 1) if ingest_seconds else None,
                "latency_ms": {p: round(pct(latencies, q), 1) for p, q in (("p50", 50), ("p95", 95), ("p99", 99))} if latencies else None,
            },
            "delivery": {
                "deliveries": len(deliveries), "by_status": by_status,
                "all_finished": all(d["status"] not in ("PENDING", "RETRYING", "IN_FLIGHT") for d in finished.values()),
                "wall_seconds_from_first_submit": round(total_seconds, 2),
                "deliveries_per_second": round(len(done) / total_seconds, 1) if total_seconds else None,
                "completion_ms": {p: round(pct(completion, q)) for p, q in (("p50", 50), ("p95", 95), ("p99", 99))} if completion else None,
                "attempts_per_delivery_mean": round(statistics.mean(attempts), 2) if attempts else None,
            },
            "queue_samples": {
                "max_due": max((m.get("webhooks_queue_due", 0) for m in metrics_samples), default=None),
                "max_oldest_due_seconds": max((m.get("webhooks_queue_oldest_due_seconds", 0) for m in metrics_samples), default=None),
                "max_hikari_active": max((m.get("hikaricp_connections_active", 0) for m in metrics_samples), default=None),
                "max_hikari_pending": max((m.get("hikaricp_connections_pending", 0) for m in metrics_samples), default=None),
                "hikari_max": max((m.get("hikaricp_connections_max", 0) for m in metrics_samples), default=None),
            },
        }
        print(json.dumps(summary, indent=2))
        if args.out:
            with open(args.out, "w") as f:
                json.dump(summary, f, indent=2)
    finally:
        for endpoint_id, name in endpoints:
            c.call("DELETE", f"/api/v1/endpoints/{endpoint_id}")
            c.sim_call("DELETE", f"/receivers/{name}")
        for key_id, _ in keys:
            c.call("DELETE", f"/api/v1/api-keys/{key_id}")


if __name__ == "__main__":
    main()
