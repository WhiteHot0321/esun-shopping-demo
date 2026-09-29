# 061 — Phase 3.3 #19 observability implementation (OBS-01 to OBS-03)

Date: 2026-09-29 (Asia/Taipei). Executor: Claude Code. Branch: `advanced-v2`. Scope source: `054`.

## What was built

- **OBS-01 telemetry contract (backend):**
  - `micrometer-registry-prometheus`; `prometheus` added to the exposed actuator endpoints. In prod it is served only on the
    management listener (main port answers 404 even with an ADMIN token).
  - `CorrelationIdFilter` (highest precedence): honours a safe `X-Correlation-Id` (`[A-Za-z0-9._-]{1,64}`), otherwise generates a
    UUID; puts it in the MDC and the response header; always clears the MDC.
  - `logback-spring.xml`: prod logs are JSON (logstash encoder, `correlationId` from the MDC); other profiles keep a readable line
    that shows the correlation id.
  - `TelemetryMetricsConfiguration`: order success/failure and payment failure counters registered at startup (export 0 before the
    first event, so `rate()`/`increase()` alerts work), plus `shop.orders.lock.retry`, `shop.orders.lock.exhausted` and the
    `shop.stock.cache.degraded` gauge. `OrderService` increments the two lock counters.
- **OBS-02 versioned monitoring stack:** `docker-compose.monitoring.yml` (Prometheus 15 d / 2 GB retention, Alertmanager, Grafana,
  UIs bound to 127.0.0.1) and `monitoring/` (scrape config, 11 alert rules with `for` windows and severities, rule unit tests,
  provisioned datasource and a 10-panel dashboard). The backend management port is bound to `0.0.0.0` inside the container so
  Prometheus can reach it; it stays unpublished.
- **OBS-03 operations documentation:** `docs/runbook-incident.md` (retention/export policy, triage, one section per alert).

## Verification (real runs, 2026-09-29)

- `mvn -Dtest=ProductionProfileIntegrationTest -Djacoco.skip=true test`: 6/6 PASS (2 new: Prometheus scrape only on the management
  port with all business metrics present and no secrets; correlation id echoed/generated). `CorrelationIdFilterTest` and
  `OrderRetryTest` (extended with lock retry/exhausted assertions) ran green in the same invocation as the first attempt.
  One test-setup fix was needed: `@SpringBootTest` disables metrics export by default, so the class uses `@AutoConfigureObservability`.
- `promtool check rules`: 11 rules OK. `promtool test rules alerts.test.yml`: all cases PASS (after correcting two of my own
  expectations that had the `for: 1m` boundary one minute late). `amtool check-config`: OK.
- Live stack (prod + tunnel + monitoring overlays, project `esun-obs`, throwaway secrets): Prometheus target `esun-backend` up; 11 rules
  loaded and healthy; `shop_orders_lock_retry_total`, `shop_stock_cache_degraded`, `hikaricp_connections_max` present; the management
  port was unreachable from the host; a request with `X-Correlation-Id: obs-live-check-1` and a bad token produced a JSON log line
  carrying `"correlationId":"obs-live-check-1"` and no token content.
- Controlled alert transition: stopping the backend moved `BackendDown` to pending at ~40 s and firing at ~103 s, Alertmanager listed it
  active with its runbook annotation; restarting the backend cleared it in Prometheus and Alertmanager (0 active) within ~41 s.
- Grafana 11.3.0: datasource and the dashboard were provisioned; all 16 panel queries returned `success` against Prometheus.
- Everything was torn down afterwards (`down -v`, `.env.prod` deleted).

## Not covered

- Notification delivery: no destination is configured on purpose (secret); alerts are proven only up to Alertmanager.
- The full backend regression (`mvn clean test`) was not run for this change, and there was no independent review.
- Alerts for dependency health (Ollama/Redis connectivity) rely on the Redis degradation gauge only; there is no metric for
  Ollama availability. Thresholds are untuned starting points; the HTTP panels were empty because no traffic ran.
- A real Redis-outage or deadlock-driven firing of `StockCacheDegraded`/`OrderLockRetries*` was not run live; those rules are covered by
  rule unit tests, and the counters by `OrderRetryTest`. (An independent review found only 4 of the 11 rules had unit tests at first; all 11 now do, with negative cases for the ratio and latency rules.)
- No log/metric export or long-term retention; single-host only.
