# 080 — Phase 3.3 #19 observability closeout (no-external-resource part)

Date: 2026-11-16 (Asia/Taipei). Executor: Claude Code (implementation-engineer, delegated by the lead).
Branch: `feature/phase33-19-obs` (worktree `esun-p33-19-obs`), cut from `advanced-v2` at `cd04e78`.
Scope source: `061` "Not covered" and `067` row 19. Reviewer: not yet assigned (observability only; no order,
inventory, payment, auth or transaction code changed, so the critical-change dual review rule does not apply).

## What changed

1. **Ollama connectivity metric** — `shop_llm_ollama_up` (1/0).
   - `CachedHealthGauge` (new) turns the existing `ollamaHealthIndicator` into a gauge value: `UP` → 1, anything
     else (DOWN, UNKNOWN, exception) → 0. The probe result is cached (`management.metrics.ollama.probe-cache-seconds`,
     default 30 s) so a scrape every 15 s does not hit Ollama every time; the indicator's 500 ms timeout bounds a probe.
   - Registered in `TelemetryMetricsConfiguration` only when `llm.provider=ollama` (same default as the app). A
     Claude-backed deployment exports no series, so the alert cannot fire on an unused dependency.
   - Semantics are the health indicator's: reachable **and** both configured models present (`/api/tags`). It does
     not prove that a chat completion succeeds (same limit as the indicator, `TAG_INVENTORY_ONLY`).
2. **`OllamaUnavailable` alert** — `max(shop_llm_ollama_up) == 0` for 5m, `warning`, runbook section added.
   Warning, not critical: only the support assistant depends on Ollama; orders/payments/readiness do not.
3. **Configurable alert delivery** — default stays "no receiver".
   - `monitoring/alertmanager/alertmanager.webhook.yml` (new): same route/inhibit as the default, receiver
     `webhook_configs.url_file: /run/secrets/alert_webhook_url`, `send_resolved: true`, `max_alerts: 20`.
   - `docker-compose.alerting.yml` (new, opt-in overlay): switches Alertmanager to that config and mounts a Compose
     secret from `${ALERT_WEBHOOK_URL_FILE:?…}`. Without the variable the overlay refuses to render (fail closed).
     The URL never appears in Git, in `docker compose config` output or in the container environment.
   - `.env.prod.example`: commented `ALERT_WEBHOOK_URL_FILE=` with no value. No real URL is committed anywhere.
   - `scripts/test/monitoring-config.test.sh` (new): config-validation test, Docker only (13 checks, see below).
4. **Threshold review and one tuning change** — `HighP95Latency` now excludes `/api/support.*` (LLM answers take
   seconds with a 30 s timeout by design and would dominate p95 without being a regression). Other thresholds were
   reviewed and kept; reasoning below.
5. **Self-review of the existing 11 rules and their tests** — rule expressions unchanged except (4); tests tightened:
   - Firing assertions moved to the exact `for:` boundary with a "not yet" assertion one minute earlier for
     `HighHttp5xxRatio`, `OrderFailureRatioHigh`, `HighP95Latency`, `PaymentFailuresElevated`,
     `OrderLockRetriesElevated`, `JvmHeapHigh`, `DbPoolSaturated`, `BackendMetricsAbsent`. Before, most firing checks
     were far past the boundary, so lengthening a `for:` window would not have turned any test red.
   - New cases: `DbPoolSaturated` via the `pending > 0` branch alone (previously untested) plus a quiet half-used pool;
     slow `/api/support` traffic does not raise `HighP95Latency`.
   - Fixed a misleading comment in the 5xx case (it said a series "must not fire" inside a case that expects firing).
6. `docs/runbook-incident.md`: `OllamaUnavailable` section and an "Alert delivery (optional)" section (secret file
   placement and permissions, enable, prove once, rotate).

## Threshold review (reasoning)

| Rule | Threshold | Verdict |
|---|---|---|
| BackendDown | `up == 0` for 1m, critical | Keep. 4 failed scrapes; live fire/clear measured in 061 (~103 s / ~41 s). |
| BackendMetricsAbsent | `absent(up)` 5m, critical | Keep. Config breakage, not flapping; 5m avoids deploy-time noise. |
| HighHttp5xxRatio | >5% and >0.1 rps for 5m, critical | Keep. Traffic floor (6 req/min) prevents single-error pages on a low-traffic demo. Trade-off: Ollama outages surface as 503 on `/api/support/ask`; with support-heavy traffic this rule can fire alongside `OllamaUnavailable`. Not excluded, because excluding the path would also hide real support 500s; an inhibit would hide a concurrent checkout outage. |
| HighP95Latency | p95 > 1s for 10m, warning | **Changed**: exclude `/api/support.*`. 1s stays; Micrometer buckets near 1s (~0.89/1.07 s) make interpolation reasonable. |
| OrderFailureRatioHigh | >20% and ≥5 failures in 10m, for 5m | Keep. Includes business rejections (sold out), so a lower ratio would page on flash sales. |
| PaymentFailuresElevated | ≥5 verified declines / 15m, immediate | Keep. Verified declines only; card declines are normal at a low rate. |
| OrderLockRetriesElevated | >20 retries / 10m for 5m | Keep. Retries are absorbed internally; this is a trend warning. |
| OrderLockRetriesExhausted | any in 5m, critical, immediate | Keep. A customer received a conflict answer. |
| StockCacheDegraded | latch == 1 for 1m | Keep. The latch never self-clears; 1m only filters startup races. |
| JvmHeapHigh | >90% for 10m | Keep. `jvm_memory_max_bytes` is -1 for some G1 pools; the sum error is a few bytes, negligible. |
| DbPoolSaturated | active/max >90% or pending > 0 for 3m | Keep. `pending > 0` must hold at every evaluation for 3m, so brief waits do not fire. |
| OllamaUnavailable (new) | `== 0` for 5m, warning | 5m tolerates an Ollama restart/model load; the flapping test proves 4-minute dips stay quiet. |

All values remain starting points: no production traffic exists to tune against (HTTP panels were empty in 061).

## Verification (real runs, 2026-11-16, Windows 11 + Docker 29.8.0)

- **Backend targeted** (`mvn -Djacoco.skip=true -Dtest=CachedHealthGaugeTest,ExternalDependenciesHealthIntegrationTest,
  RedisDisabledDependenciesHealthIntegrationTest,DatabaseReadinessHealthIntegrationTest,ProductionProfileIntegrationTest,
  CorrelationIdFilterTest,OrderRetryTest test`): **24/24 PASS** (CachedHealthGauge 3, ExternalDependencies 2,
  RedisDisabled 1, DatabaseReadiness 2, ProductionProfile 6, CorrelationIdFilter 4, OrderRetry 6). New: gauge follows
  PRESENT → MISSING → REFUSED → PRESENT against the fake Ollama with no restart; gauge absent with `llm.provider=claude`;
  `shop_llm_ollama_up` present in the prod-profile management scrape.
- **Backend full suite** (`mvn -Djacoco.skip=true test`): **327 run, 0 failures, 0 errors, 0 skipped — BUILD SUCCESS** (run after the metric mutation was reverted).
- **promtool** (`prom/prometheus:v2.55.1`): `check rules` 12 rules SUCCESS; `test rules` 23 cases SUCCESS;
  `check config` SUCCESS.
- **amtool** (`prom/alertmanager:v0.27.0`): `check-config` SUCCESS for both configs.
- **`bash scripts/test/monitoring-config.test.sh`: 13 passed, 0 failed.**
- **Local delivery proof (throw-away, torn down):** private Docker network, receiver = `busybox:1.36` `nc -l`,
  Alertmanager started with `alertmanager.webhook.yml` and the URL only in a scratch file mounted at
  `/run/secrets/alert_webhook_url`. `amtool alert add TestDelivery` → the receiver got
  `POST /hook` with `User-Agent: Alertmanager/0.27.0` and the JSON payload (`"receiver":"default","status":"firing"`,
  alert labels/annotations) about 30 s later (group_wait). Alertmanager logged the URL as `<redacted>`. Containers,
  network and scratch file were removed.

### Mutation evidence (each made the suite red, then reverted)

| Mutation | Caught by | Result |
|---|---|---|
| `OllamaUnavailable` `for: 5m` → `1m` | promtool, "not before" at 6m | FAIL (got firing) |
| Rule metric renamed `shop_llm_ollama_available` | promtool, firing at 7m | FAIL (got none) |
| Rule inverted `== 1` | promtool, firing + quiet cases | FAIL |
| Backend metric renamed `shop.llm.ollama.available` | `ProductionProfileIntegrationTest` scrape contract | 1 of 6 FAIL |
| `HighHttp5xxRatio` `for: 5m` → `10m` (existing rule) | new boundary at 6m | FAIL |
| `DbPoolSaturated` pending branch removed (existing rule) | new pending-only case | FAIL |
| `HighP95Latency` support exclusion removed | new support-traffic case | FAIL |
| Webhook config: literal `url:` instead of `url_file`, `group_wait` drift; overlay `:?` → `:-/dev/null` | `monitoring-config.test.sh` | 4 FAIL (literal URL, route drift, missing url_file, fail-open overlay) |

## Not covered

- **Real notification delivery**: no destination exists. Proven only against a local throw-away receiver. A chat
  service that needs its own payload format (Slack/Discord/Telegram) needs an adapter or a native Alertmanager
  integration with its own `*_file` secret; not built.
- On a Linux host the secret file must be readable by the container user `nobody` (runbook gives `0444` file inside a
  `0700` directory). Docker Desktop on Windows bind mounts are permissive, so this permission path was not exercised.
- `OllamaUnavailable` was not fired on a live stack (Prometheus + real Ollama); covered by rule unit tests and the
  gauge integration test against a fake Ollama.
- Grafana dashboard: no Ollama panel added, and its p95 panel still includes `/api/support` (dashboard differs from
  the alert now). Follow-up, low risk.
- `monitoring-config.test.sh` is not wired into CI (`.github/workflows/ci.yml` `config-validate` only renders Compose).
  Follow-up: add `bash scripts/test/monitoring-config.test.sh` to that job.
- Log/metric export and long-term retention: unchanged (needs an external destination).
- No independent review yet.

## Engineering concept

An alert is only as good as the test that can fail it: asserting "fires at 12m" for a 5m window cannot detect a
window change, so the boundary ("not at 5m, yes at 6m") is what pins the rule. Secrets in alerting follow the same
indirection as the app's other secrets: configuration names a file (`url_file`), the deploy supplies the file, and
the overlay fails closed when the file is not provided. Probing a dependency from a metric needs a cache and a timeout,
otherwise the scrape interval silently becomes the dependency's load and the scrape's latency.

## Next step

1. Lead: independent review of this branch; decide whether to wire `monitoring-config.test.sh` into CI.
2. When a destination exists: create the secret file on the VM, enable the overlay, run the runbook's one-time
   delivery check, and record it.
3. Optional: Grafana Ollama panel and aligning the dashboard p95 query with the alert.
