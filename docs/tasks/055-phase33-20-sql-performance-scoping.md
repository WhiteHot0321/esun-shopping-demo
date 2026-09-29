# 055 — Phase 3.3 #20 SQL performance and capacity baseline scoping

## Session gate

- Mode: analysis/scoping only; no production, schema, test, benchmark, or database configuration implementation in this session.
- Class: Medium-Heavy, split into separate A scoping, B implementation, and C independent acceptance sessions.
- Baseline: `advanced-v2` @ `429346d`; preserve all pre-existing uncommitted #18 B2/Flyway and user files.
- Outcome: inventory the current SQL/index/load-test evidence, identify evidence gaps, and define the smallest executable slices for #20 without treating historical latency as a controlled pre/post baseline.
- Affected module: backend JDBC repositories, MySQL schema/indexes and runtime configuration, and the existing `bench/` k6 suite.
- Files read: `CLAUDE.md`, `docs/project-state.md`, `docs/tasks/054-phase33-19-observability-scoping.md`, targeted application/POM/schema excerpts, SQL call-site search results, benchmark inventory, and the corresponding Notion pages.
- Files changed: this task record and the concise state entry in `docs/project-state.md` only.
- Verification: documentation diff/status inspection; executable tests and benchmarks are intentionally excluded from this scoping-only change.
- Exclusions: `EXPLAIN ANALYZE` execution, index or query changes, slow-query configuration, Hikari tuning, k6 execution, production traffic/database access, commit, push, merge, and deploy.
- Usage at start: five-hour 97% used (3% remaining); weekly 78% used (22% remaining). Model/cost details unavailable.

## Verified existing foundation

| Capability | Current evidence | #20 disposition |
|---|---|---|
| Versioned MySQL schema and indexes | `V1__baseline_schema.sql` defines primary, unique, foreign-key, and workload-specific secondary indexes | Use as the candidate-index inventory; do not assume every index is effective or non-redundant |
| JDBC query surface | Repository search shows read/write/locking queries for products, orders, payments, carts, reviews, recommendations, coupons, audit logs, addresses, members, password resets, FAQ, and embeddings | Build a query-to-endpoint/workload matrix before running plans |
| Query-count regression protection | `OrderServiceQueryCountIntegrationTest` and test-scope `datasource-proxy` guard the fixed order-creation query count | Reuse for round-trip regressions; it does not prove optimizer-plan quality |
| Concurrency/load workloads | Existing k6 normal, deadlock, sold-out, and coupon workloads plus database correctness reconciliation | Reuse workload definitions and invariants; add controlled repeatability rather than inventing a second suite |
| Historical latency samples | `bench/phase3/*-summary.json`, older Phase 1.5 summaries, and `PHASE3-K6.md` record p95/p99 under specific local conditions | Context only; not a valid pre/post comparison until environment, dataset, warm-up, run count, and pool settings are fixed |

## Confirmed gaps and risks

1. No checked-in `EXPLAIN ANALYZE` corpus ties each important repository query to representative parameters, data cardinality, MySQL version, and the intended endpoint/workload.
2. Existing indexes have not been assessed against actual access paths for effectiveness, leftmost-prefix coverage, selectivity, sort avoidance, write amplification, duplication, or foreign-key support.
3. The current scope phrase “all API endpoints” is too broad and misleading: authentication and non-SQL endpoints do not need plans, while one endpoint may execute several queries. Acceptance should cover every distinct production SQL shape in the selected critical/query-heavy workloads, with exclusions explicitly recorded.
4. Historical k6 results were built primarily as correctness evidence. They do not establish statistically comparable pre/post performance because environment fingerprint, fixed seed/cardinality, warm-up, repeated-run aggregation, and resource/pool controls are incomplete or inconsistent.
5. Hikari uses defaults in the inspected application configuration; pool size, wait time, utilization, saturation, and transaction hold time have no measured baseline. A pool-size change must not be used as an optimization before demand and database capacity are measured.
6. No versioned slow-query capture contract was found. The Notion #20 page states that #19 provides a slow-query-log foundation, but current evidence shows #19 only completed A-stage scoping; its B/C work remains open. #20 must either define a disposable-MySQL capture method or explicitly depend on a future #19/#18 telemetry/topology slice.
7. No capacity model links measured throughput to database CPU, connections, lock waits/deadlocks, disk/data/index growth, retention, headroom, or a forecast horizon. QPS alone is insufficient.
8. `EXPLAIN ANALYZE` executes the statement. Mutating SQL and locking paths require rollback-safe fixtures or non-destructive alternatives; production execution is out of scope.

## Proposed bounded implementation sequence

### SQLPERF-01 — reproducible query and plan baseline

- Define the critical/query-heavy workload matrix, initially: product catalog/search, buyer/seller order lists and detail hydration, checkout/idempotency, payment callback lookup, reviews, recommendations, coupon redemption, cart, and audit-log filtering.
- For each distinct SQL shape, record source method, representative bind values, fixture cardinality/selectivity, current indexes, `EXPLAIN ANALYZE` plan, actual rows/loops/time, and rows examined versus returned. Record explicit exclusions for trivial primary-key lookups or non-SQL endpoints.
- Run only against disposable MySQL fixtures. For mutating/locking statements, use rollback-safe fixtures or `EXPLAIN` without execution where `EXPLAIN ANALYZE` would change state or distort locking semantics.
- Produce a checked-in, repeatable capture command/script and machine-readable/raw evidence plus a concise interpretation table.
- Risk: Medium. No index or query changes in this slice.

### SQLPERF-02 — index/query and connection/transaction tuning

- Turn SQLPERF-01 findings into a ranked change list; require evidence for every added, removed, or reordered index and for every query rewrite.
- Check composite-index leftmost prefixes, covering opportunities, redundant indexes, selectivity, filesort/temp-table behavior, write overhead, and lock footprint.
- Measure Hikari acquisition wait/active/max behavior and transaction/lock duration before considering pool changes. Keep database connection capacity and application concurrency aligned.
- Apply the smallest Flyway migration/query/config changes, with regression tests for semantics and concurrency invariants. Preserve order/payment/coupon locking and idempotency contracts.
- Compare plans before/after and reject changes that merely shift cost to writes or broaden lock scope without an explicit trade-off.

### SQLPERF-03 — controlled k6 comparison, capacity model, and independent acceptance

- Pin environment fingerprint, code revision, MySQL settings/version, Hikari settings, dataset seed/cardinality, warm-up, VUs/duration, and run order. Use multiple runs and report median plus range/dispersion, not a single best run.
- Reuse the existing k6 correctness reconciliation; performance thresholds must never replace no-oversell, exact-quota, idempotency, and database reconciliation gates.
- Capture endpoint throughput/latency/error mix together with DB connections, CPU, lock waits/deadlocks, rows examined/sent, buffer-pool behavior where available, and data/index size.
- Establish regression thresholds from the measured baseline and noise band, not from an arbitrary universal number. The existing Notion `100ms` slow-query value begins as a capture threshold to validate, not an achieved endpoint SLO.
- Produce a capacity model with explicit assumptions, bottleneck/headroom, disk/data/index growth, retention horizon, and a monthly monitoring/recalibration plan.
- Independent acceptance reviews the selected query-shape coverage, plan interpretations, index redundancy, controlled pre/post evidence, correctness invariants, and capacity arithmetic.

## Acceptance boundary

This A-stage is complete when local and required Notion locations record that #20 has started, identify the existing schema/query/k6 assets and evidence gaps, preserve #19/#18 dependency uncertainty, and name SQLPERF-01 as the next single task. It does not complete any #20 implementation or acceptance checkbox and does not claim that any current index, query, latency, pool size, or capacity is acceptable.

## Session accounting

- Elapsed time: approximately 20 minutes across the initial and continued goal turns.
- User interventions: 1 (start Phase 3.3 #20).
- Repair/test rounds: 0; scope expansions: 0.
- Approximate tool calls: 9.
- Usage at end: five-hour window reset during the session and is now 6% used; weekly 80% used.
- Start/end context and model-specific cost: unavailable (unknown, not zero).

## Engineering concept note

A performance baseline is a controlled experiment, not a collection of fast-looking numbers. Query plans explain where database work occurs; repeated load tests show how that work behaves under concurrency; capacity planning connects both to finite resources and growth. Keeping those layers linked prevents an index that improves one sample query from being mistaken for a system-wide capacity improvement.
