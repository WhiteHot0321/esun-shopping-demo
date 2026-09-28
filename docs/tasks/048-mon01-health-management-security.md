# Task 048 — MON-01 health probe separation and management security

## Session gate

- Mode: IMPLEMENT
- Class: Medium / security boundary (independent review required)
- Baseline: `advanced-v2` @ `69517faaa84ac70a914d08420e3d0a17969bd82a`
- Scope: `application.yml`, `application-prod.yml`, `ProductionProfileIntegrationTest.java`
- Excluded: MON-02 Redis/Ollama indicators, business APIs, order/payment semantics, deployment, dashboards, commit/push/merge
- Implementer: Codex
- Reviewer: Claude Code, read-only via `scripts/invoke-claude.ps1`
- User interventions: 1 (the request to start MON-01)

## Acceptance

- Liveness remains dependency-free; readiness contains `readinessState` and `db` only.
- A separate `dependencies` health group is reserved for Redis/Ollama without blocking startup before MON-02 supplies its indicators.
- Only `health` and `metrics` are exposed on the management interface; `env`, `configprops`, and `beans` are unavailable.
- Production management defaults to loopback. Cross-container access requires an explicit address override and an unpublished management port.
- Main business port does not serve health or metrics.

## Result

- Changes: implemented, target-tested, and independently reviewed PASS; not committed, pushed, merged, or deployed.
- Target test attempt 1: `mvn '-Dtest=ProductionProfileIntegrationTest' '-Djacoco.skip=true' test` — exit 1, 4 errors. Root cause: `validate-group-membership` was placed under the group instead of `management.endpoint.health`, so absent MON-02 contributors rejected startup.
- Repair round 1: moved `validate-group-membership: false` to the Spring Boot 3.3 property level.
- Target test attempt 2: same command — exit 0; 4/4 passed, 0 failures/errors/skips, 35.263 s; Testcontainers MySQL and actual `prod` profile with separate random main/management HTTP ports.
- Coverage: deliberately skipped for the narrow test. Full regression is reserved for MON-05.
- Environment observation: existing Ollama indexing emitted non-blocking missing-model warnings; application and tests still passed.
- Independent review: Claude Code read-only audit PASS; no blocker. It confirmed probe separation, allow-listed exposure, loopback default, main-port isolation, and valid Boot 3.3 configuration. Non-blocking: the membership-validation switch is global, and hidden health details mean DB group membership is established by config inspection plus real-DB startup rather than an HTTP component-name assertion.
- Repair rounds: 1. Valid review defects: 0. Elapsed time: approximately 10 minutes through review. Token/cost and five-hour usage change: unknown.

## Independent review contract

Derive expected behavior from the acceptance section and the three-file diff before relying on this result. Inspect only:

1. `backend/src/main/resources/application.yml`
2. `backend/src/main/resources/application-prod.yml`
3. `backend/src/test/java/com/esun/shop/integration/ProductionProfileIntegrationTest.java`

Check for security or correctness blockers: liveness/readiness contamination, unintended endpoint exposure, management binding assumptions, secret/detail leakage, invalid Spring Boot configuration, and hollow tests. Do not modify files or broaden into MON-02. Return exactly `PASS`, `FAIL`, `BLOCKED`, or `NEEDS_ARCH_DECISION`, followed by concise evidence and any defects.

## Engineering concept

A separate port is routing, not authorization. Binding the management listener to loopback by default and exposing only an allow-list reduces the reachable attack surface; named probe groups then keep restart/traffic decisions independent from optional dependency degradation.
