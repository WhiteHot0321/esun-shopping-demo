# Incident runbook

Applies to the single-host production stack (`docker-compose.prod.yml` + `docker-compose.monitoring.yml`).
Every alert in `monitoring/prometheus/alerts.yml` links to a section here. Owner: the maintainer (single owner;
there is no on-call rotation). Compose shorthand used below:

```bash
C="docker compose -f docker-compose.prod.yml -f docker-compose.monitoring.yml --env-file .env.prod"
```

Open the UIs over an SSH port-forward or locally: Prometheus `127.0.0.1:9090`, Alertmanager `127.0.0.1:9093`,
Grafana `127.0.0.1:3000` (dashboard "ESUN Shop overview"). Follow one request through the logs with its
`X-Correlation-Id` response header: `$C logs backend | grep <id>` (logs are one JSON object per line).

## Retention and export policy

| Data | Where | Kept | Note |
|---|---|---|---|
| Metrics | Prometheus volume | 15 days or 2 GB, whichever is reached first | Older samples are deleted automatically. |
| Alert state | Alertmanager volume | Until silences/notifications expire | Not a history store. |
| Application logs | Docker json-file logs of the `backend` container | Until the container is recreated | No shipping/export is configured. Copy logs out before `down`/recreate if they are needed: `$C logs --no-color backend > backend-$(date +%F).log`. |
| Dashboards | Git (`monitoring/grafana/dashboards`) | Versioned | Read-only in Grafana; change them in Git. |

Not covered: off-host log/metric export and long-term retention. Add them only with a real destination.

## Triage first (2 minutes)

1. `$C ps` — which containers are unhealthy or restarting?
2. Grafana "ESUN Shop overview": backend up, 5xx ratio, p95, order failures, DB pool.
3. `$C logs --since 15m backend | grep -E '"level":"(WARN|ERROR)"'` — recent warnings/errors with correlation ids.
4. Decide: user-visible impact now (page-level) or a slow-burn warning.

## BackendDown

Meaning: Prometheus cannot scrape the backend management port (or has no target at all).
1. `$C ps backend` and `$C logs --tail 100 backend`.
2. Crash loop with a configuration message: fix `.env.prod` (a missing/weak secret is refused at startup by design).
3. Database unreachable: `$C ps mysql`; see the DB steps under DbPoolSaturated.
4. Otherwise restart: `$C restart backend`; confirm `$C ps` shows `healthy` (healthcheck = readiness).
Recovery check: alert clears within ~1 minute after the target is up again.

## HighHttp5xxRatio

Meaning: more than 5% of non-actuator requests return 5xx with real traffic.
1. Find the failing endpoint in Grafana (or query `sum by (uri) (rate(http_server_requests_seconds_count{status=~"5.."}[5m]))`).
2. Grep the backend logs for `ERROR` around the start time and follow one `correlationId`.
3. If it began with a deploy, roll back to the previous image tag (`BACKEND_IMAGE` in `.env.prod`) and `$C up -d backend`.
Recovery check: 5xx ratio below 1% for 10 minutes.

## HighP95Latency

Meaning: p95 above 1 s for 10 minutes.
1. Check DB pool panel (active near max, or pending > 0) and lock retries: contention usually shows there first.
2. Check JVM heap; if near max, see JvmHeapHigh.
3. Check host CPU/memory (`docker stats --no-stream`); Ollama inference shares the host with the app.
Recovery check: p95 below 1 s.

## OrderFailureRatioHigh

Meaning: more than 20% of order attempts fail in 10 minutes (at least 5 failures). Failures include business
rejections such as sold out, so first separate those from errors.
1. Grep logs for order failures and their causes: `$C logs --since 15m backend | grep -i order`.
2. Sold-out spikes are expected during a promotion; errors (5xx, lock exhaustion) are not — go to the matching section.
Recovery check: failure ratio back under 20%.

## PaymentFailuresElevated

Meaning: five or more provider-verified declines in 15 minutes.
1. Confirm whether the provider is degraded (provider status page / test a payment on the stage environment).
2. Verify callback signatures are still accepted: unverified callbacks are rejected before this counter, so a signature
   or secret mismatch shows up as 4xx on the callback URL, not here.
3. Orders with declined payments stay retryable by the buyer; no data repair is needed.

## OrderLockRetriesElevated

Meaning: more than 20 internal deadlock/lock-wait retries in 10 minutes. Retries absorb this today; it is an early
warning that hot products are contending.
1. Identify the hot product from order logs; consider scheduling a restock or limiting a promotion.
2. Watch OrderLockRetriesExhausted — if it starts, contention is beyond what retries absorb.

## OrderLockRetriesExhausted

Meaning: at least one order failed after three attempts and the buyer received a conflict answer.
1. Check the DB: `$C exec mysql sh -c 'MYSQL_PWD=$MYSQL_ROOT_PASSWORD mysql -uroot -e "SHOW ENGINE INNODB STATUS\G"' | sed -n '/LATEST DETECTED DEADLOCK/,/WE ROLL BACK/p'`.
2. Reduce concurrency on the affected product; buyers can safely retry (order submission is idempotent per request id).
3. If it persists, treat as HighHttp5xxRatio and consider rolling back the last release.

## StockCacheDegraded

Meaning: the Redis stock path latched to DB-only after a Redis failure. Orders still succeed against MySQL (correct,
slower); the latch never clears by itself.
1. Restore Redis: `$C ps redis`, `$C logs --tail 50 redis`, `$C restart redis` if needed.
2. Reconcile Redis stock with the database (maintenance), then restart the backend so it returns to the Redis path:
   `$C restart backend`.
Recovery check: `shop_stock_cache_degraded` is 0 after the restart and stays 0.

## JvmHeapHigh

Meaning: heap above 90% of max for 10 minutes.
1. Look at the heap trend; a saw-tooth that keeps its floor high suggests a leak, a flat high line suggests undersizing.
2. Restart as a stop-gap (`$C restart backend`); raise `JAVA_OPTS` (`-XX:MaxRAMPercentage`) only if the host has headroom.
3. Capture evidence before restarting if a leak is suspected (heap dump is not configured here).

## DbPoolSaturated

Meaning: connection pool nearly exhausted or requests are waiting for a connection.
1. `$C exec mysql sh -c 'MYSQL_PWD=$MYSQL_ROOT_PASSWORD mysql -uroot -e "SHOW FULL PROCESSLIST"'` — long-running or blocked statements?
2. Long lock waits usually accompany lock-retry metrics; see OrderLockRetriesElevated.
3. Restarting the backend releases the pool; fix the slow statement before raising the pool size.

## After any incident

Record what happened, the trigger, what fixed it and the follow-up in `docs/tasks/` (or the Notion Log). If an alert
was noisy or late, change its threshold in `monitoring/prometheus/alerts.yml`, extend `alerts.test.yml`, and re-run:

```bash
docker run --rm -v "$PWD/monitoring/prometheus:/w" -w /w --entrypoint promtool prom/prometheus:v2.55.1 test rules alerts.test.yml
```
