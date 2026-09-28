# MON-02 — Redis degradation and Ollama dependency status

## Session gate

- Mode: IMPLEMENT
- Baseline: `advanced-v2` @ `69517faaa84ac70a914d08420e3d0a17969bd82a`
- Risk: medium; Redis degradation state requires independent read-only review
- Implementer: Codex
- Reviewer: Claude Code through `scripts/invoke-claude.ps1`
- Allowed production/config scope: `StockCacheService`, the dependency health configuration, and `application.yml`
- Direct test scope: `ExternalDependenciesHealthIntegrationTest.java`
- Exclusions: no core order policy change, no latch reset, no inventory writes from probes, no model installation or
  generation probe, no Prometheus/Grafana, no commit/push/merge/deploy, no full suite (reserved for MON-05)

## Acceptance criteria for independent review

Derive the expected behavior from this section and inspect the code/tests before relying on the implementation notes.

1. MySQL outage makes readiness return 503 while liveness remains 200.
2. Redis/Ollama failures do not affect readiness; they are visible through the separate dependencies group.
3. Redis reports disabled, connection-failed, enabled, and latched DB-only states distinctly.
4. A successful Redis PING after an outage does not clear or conceal the DB-only latch.
5. Health reads a thread-safe snapshot and never clears the latch or writes inventory.
6. Ollama `/api/tags` means connectivity/model inventory only. Required models are checked without installing a model
   or making an expensive generation call; timeout/error details are bounded and do not expose configured model names.
7. Tests use disposable real MySQL/Redis and a controlled Ollama HTTP stub, including a successful DB-only order.

## Implementation notes and evidence

- `StockCacheService.DependencySnapshot` reads the existing `AtomicBoolean` latch and exposes only an immutable mode.
- `ExternalDependenciesHealthConfiguration` contributes `redis` and `ollama` indicators. Redis PING is observational;
  Ollama calls only `/api/tags`, with a configurable 500 ms default timeout.
- Only the `dependencies` group uses `show-details: always`; global, readiness and liveness details stay hidden.
- First targeted run:
  `mvn '-Dtest=ExternalDependenciesHealthIntegrationTest,RedisDisabledDependenciesHealthIntegrationTest' '-Djacoco.skip=true' test`
  — 2/2 passed, 0 failures/errors/skips, exit 0, 46.145 s.
- Expanded DB-outage run initially had 2 passes and 1 failure because the first post-unpause readiness check observed a
  closed pooled connection. Repair round 1 changed only recovery verification to bounded polling (15 × 200 ms).
- Narrow repair verification:
  `mvn '-Dtest=DatabaseReadinessHealthIntegrationTest' '-Djacoco.skip=true' test`
  — 1/1 passed, 0 failures/errors/skips, exit 0, 40.621 s.
- Completion-audit verification after adding an explicit controlled Ollama HTTP 503 response:
  `mvn '-Dtest=ExternalDependenciesHealthIntegrationTest' '-Djacoco.skip=true' test`
  — 1/1 passed, 0 failures/errors/skips, exit 0, 41.942 s.
- JaCoCo and the full backend suite were intentionally skipped; MON-05 owns final full regression.

## Review request

Perform a read-only review. Inspect the requirements above, then the exact allowed files and their diff. Check state
semantics, thread safety, probe side effects, bounded failure behavior, information exposure, and whether tests prove
the acceptance criteria. Do not modify files or run tests. Return exactly one result: PASS, FAIL, BLOCKED, or
NEEDS_ARCH_DECISION, followed by concise evidence and any reproducible blocker.

## Independent review result

- First-round Claude Code read-only review returned **PASS**, but was marked superseded because the test file changed
  afterward (repair round 2 added an explicit Ollama HTTP 503 stub case). A fresh read-only review was required before
  closeout.
- Fresh read-only review (2026-09-27, post-repair-round-2) re-derived expected behavior from the seven acceptance
  criteria and re-inspected the current production/config/test files independently — **PASS**.
  1. MySQL outage → readiness 503 / liveness 200: met (`DatabaseReadinessHealthIntegrationTest.java:238-246`).
  2. Redis/Ollama failures do not affect readiness: met (`application.yml:54-59`; test lines 81-138).
  3. Redis reports 4 distinct states: met (`ExternalDependenciesHealthConfiguration.java:26-55`; test lines 86, 118,
     136, 218).
  4. Successful Redis PING does not clear the latch: met (test lines 131-137 — after recovery, still
     `LATCHED_DB_ONLY`).
  5. Health read is a thread-safe, non-mutating snapshot: met (`StockCacheService.java:62-67` reads only
     `AtomicBoolean.get()`; indicator never writes).
  6. Ollama checks only `/api/tags`, bounded errors, no configured model names leaked: met
     (`ExternalDependenciesHealthConfiguration.java:77`, 84-108; test line 106 asserts absence of model names).
  7. Tests use real MySQL/Redis plus a controlled Ollama HTTP stub, covering all four Redis modes, a DB-only order,
     DB-readiness failure, and the added explicit Ollama HTTP 503 case: met (test lines 53-64, 82-137, 103-106,
     163-169; `DatabaseReadinessHealthIntegrationTest.java:238-251`).
  - `show-details` scoping (only `dependencies` group uses `always`; global/readiness/liveness stay hidden): met
    (`application.yml:52,59`).
  - `ProductionProfileIntegrationTest.java`'s change (lines 59-81, prod management-port separation/health-probe
    availability) was flagged and assessed as in-scope — it supports MON-02's management-port isolation requirement in
    `application-prod.yml:32-38`, not an out-of-scope modification.
- Valid defects: 0. Reproducible blockers: 0. Reviewer made no repository changes (Read/Glob/Grep only).

## Measurement

- Start/end context: unavailable (unknown)
- Elapsed time: approximately 8 minutes through independent review
- Approximate tool calls: 25 (exact count unavailable)
- User interventions: 0 after task start
- Repair rounds: 2 (recovery polling; completion-audit addition of explicit Ollama HTTP 503 evidence)
- Valid review defects: 0
- Token/cost and five-hour usage change: unavailable (unknown)
