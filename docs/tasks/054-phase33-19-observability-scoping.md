# 054 — Phase 3.3 #19 observability and production alerting scoping

## Session gate

- Mode: analysis/scoping only; no production or test implementation in this session.
- Class: Medium-Heavy, split into separate A scoping, B implementation, and C independent acceptance sessions.
- Baseline: `advanced-v2` @ `429346d` (`origin/advanced-v2`); preserve all pre-existing uncommitted #18 B2/Flyway and user files.
- Outcome: map the committed MON-01–05 capabilities to #19 and define the smallest implementation slices without duplicating monitoring work.
- Files read: `CLAUDE.md`, `docs/project-state.md`, `docs/tasks/052-mon05-integration-acceptance.md`, relevant `backend/pom.xml` and application configuration excerpts, and the corresponding Notion pages.
- Files changed: this task record and the concise state entry in `docs/project-state.md` only.
- Verification: documentation diff/status inspection; executable tests are intentionally not required for this scoping-only change.
- Exclusions: production/test/config implementation, deployment, external monitoring accounts, notification delivery, commit, push, and merge.
- Usage at start: five-hour 89% used (11% remaining); weekly 77% used (23% remaining). Model/cost details unavailable.

## Verified existing foundation

The monitoring foundation is committed in `ac7e768` with evidence committed in `429346d`:

| Capability | Current evidence | #19 disposition |
|---|---|---|
| Liveness/readiness/dependency health separation | MON-01/02; production management listener isolation; real MySQL/Redis/Ollama fault cases | Reuse; do not redesign |
| HTTP request latency | `http.server.requests` histogram plus p95/p99 configuration and endpoint assertion | Export to Prometheus and dashboard |
| Order outcomes | `shop.orders.success` and `shop.orders.failure`, with retry/replay semantics tested | Export and alert on rates/ratios |
| Payment failures | `shop.payments.failure`, after-commit and duplicate-safe | Export and alert on a bounded window |
| Full monitoring regression | MON-05: `mvn clean test`, 295/295, JaCoCo gates PASS, independent review PASS | Baseline evidence; not rerun for scoping |

## Confirmed gaps

1. `pom.xml` has Actuator but no Prometheus registry, and endpoint exposure is currently `health,metrics`; there is no `/actuator/prometheus` scrape surface.
2. No JSON logging encoder or HTTP correlation-ID filter/MDC contract was found. Sensitive-field redaction and propagation behavior therefore remain unproven.
3. Deadlock retry/exhaustion and Redis DB-only latch/degradation are observable in behavior/logs but are not dedicated low-cardinality metrics suitable for alert rules.
4. No Prometheus scrape configuration, alert rules, Grafana dashboard provisioning, notification route, or alert firing evidence exists.
5. No explicit application-log retention/export policy or incident runbook exists.
6. #18 is not fully complete: B2 Flyway work is uncommitted and the Compose/Caddy production topology is not yet accepted. #19 may prepare versioned monitoring artifacts, but real production notification and retention acceptance depends on that topology.

## Proposed bounded implementation sequence

### OBS-01 — application telemetry contract

- Add JSON structured logs with an inbound/generated correlation ID carried in response headers and MDC.
- Add Prometheus registry and expose `prometheus` only on the restricted management listener.
- Add low-cardinality counters/gauges for deadlock retry/exhaustion and Redis degradation/latch state; preserve existing order/payment semantics.
- Prove correlation propagation/redaction, scrape reachability/isolation, and metric outcome semantics with targeted tests.
- Risk: Medium. Suggested limit: 5 core files read, 3 production files plus focused tests/config changed, 2 targeted commands, 1 repair round.

### OBS-02 — versioned monitoring stack

- Add Prometheus scrape/rule configuration and a provisioned Grafana dashboard for QPS, p95/p99, HTTP errors, order/payment failures, deadlock retry/exhaustion, Redis degradation, JVM memory/CPU, DB pool saturation, and dependency health.
- Define alert windows, `for` durations, severity labels, and inhibition/noise rules. Keep receiver secrets and real destinations out of Git.
- Validate rule syntax and run controlled local alert transitions; notification delivery is proven only against a user-supplied safe receiver.

### OBS-03 — operations documentation and independent acceptance

- Add retention/export policy and an incident runbook with owner, triage queries, dependency-specific actions, rollback/escalation, and recovery verification.
- Independently verify metrics accuracy, management-port isolation, absence of sensitive data, dashboard queries, controlled alert latency, history/retention behavior, and documented recovery steps.
- Production claims remain blocked until #18 topology and a real monitoring destination exist.

## Acceptance boundary

This A-stage scoping is complete when the local and required Notion status locations record that #19 has started, MON-01–05 are reused, the gaps above remain open, and OBS-01 is the next single task. It does not mark any #19 implementation checkbox complete.

## Session accounting

- Elapsed time: approximately 15 minutes.
- User interventions: 1 (start Phase 3.3 #19).
- Repair/test rounds: 0; scope expansions: 0.
- Approximate tool calls: 10.
- Start/end context and model-specific cost: unavailable (unknown, not zero).

## Engineering concept note

Observability should be designed as a contract from application event to operational decision: emit a stable, low-cardinality signal, export it through a restricted path, aggregate it over a meaningful window, and attach an actionable runbook. A metric existing in-process is not yet an alerting system.
