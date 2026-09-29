# Verified project state

Updated: 2026-09-27 (2026-09-27 batch merged: PRs #7-#11; strategy: engineering depth)
Baseline: advanced-v2 @ 11c60b6 (merge of PR #11)

## Phase 3.3 #18 closeout sync — 2026-09-29 Asia/Taipei

This entry reconciles two already-PASS but previously unsynced #18 corrections against `docs/project-state.md`; both were re-verified in this session against the current working tree (still uncommitted on `advanced-v2` @ `429346d`), not just read from their task docs.

- **B1 044-C1 correction (CORS user-info bypass).** Static review of the current tree found `ProductionConfigValidator.isPlainOrigin` accepted origins carrying user-info, e.g. `https://user@shop.example.com`. Fixed by requiring `URI.getRawUserInfo() == null`; `ProductionConfigValidatorTest` gained four rejection cases (username, username/password, empty user-info, and a mixed valid/malformed multi-origin list). Independent Claude Code correction review: PASS, no edits/tests, report at `.git/codex-claude-runs/58eb324f-774a-494e-9e8d-60931e1e1e69/result.json`. Re-run in this session: `mvn '-Dtest=ProductionConfigValidatorTest' '-Djacoco.skip=true' test` — **19/19 PASS**, 0 failures/errors/skips, 0.379s. Evidence: `docs/tasks/044-b1-production-hardening-review.md`. This closes 044-C1 only, not the broader B2/B3/C acceptance already noted as open below.
- **B2 Flyway adoption spike (Task 053), bounded PASS.** Adds `flyway-mysql`, an opt-in `spring.flyway.enabled=${FLYWAY_ENABLED:false}` with `baseline-on-migrate=false`, a consolidated `V1__baseline_schema.sql`, and `FlywayAdoptionConfiguration` for one-time explicit adoption of the existing legacy schema (`shop.flyway.adopt-existing=true`) via a pinned information_schema structural fingerprint (tables/columns/indexes/constraints/engines/routines incl. signature+body+SQL mode/charset/triggers/events); mismatches or hidden routine metadata fail closed without touching business data. After an initial Terra attempt and one FAIL round (insufficient preflight, self-referential legacy fixture), the C1 correction rebuilt the fixture from the real legacy DB/ init chain and passed independent Claude Code review. Re-run in this session: `mvn '-Dtest=FlywayAdoptionIntegrationTest' '-Djacoco.skip=true' test` — **1/1 PASS**, 0 failures/errors/skips, 74.31s (fresh V1 install of 3 procedures, repeat-migration no-op, and supported-adoption product-data preservation all verified inside the one test). This is a one-time **offline** adoption path only — application writers/DDL must be stopped through `baseline()`/`migrate()` since the check and baseline are not atomic against concurrent DDL — and the pinned fingerprint fails closed on any different MySQL version/grants rather than auto-learning. Evidence: `docs/tasks/053-phase33-18-b2-flyway.md`.
- **Still open for #18**: B2 container topology (Compose/Caddy/frontend image/non-root container, local-stack acceptance), B3 (VM prerequisites, deploy/rollback pipeline), and C (integrated independent go-live acceptance) are all unstarted; AI-support provider for production remains a separate open user decision. Nothing in this entry was committed, pushed, merged, or deployed — one-writer-per-checkout pilot convention still applies, so committing this and the rest of the uncommitted tree needs a separate explicit go-ahead.

## Phase 3.3 #21 A-stage scoping started — 2026-09-29 Asia/Taipei

Phase 3.3 #21 has started as a bounded production-security scoping milestone on `advanced-v2` @ `429346d`; no workflow, image, host, network, secret, backup-policy, production or test implementation was performed. Reusable controls are the #18 B1 production validator/management isolation, least-privilege GHCR publish job with commit-SHA tags, Dependabot coverage, and the existing local backup/restore correctness drill. Confirmed gaps are deployed exposure/firewall/SSH proof, TLS/security-header acceptance, secret rotation and runtime/backup least privilege, digest-linked image scanning and SBOM, blocking dependency policy, encrypted off-host retention/expiry and a restore downloaded from off-host storage. The root Compose file is development-only because it publishes MySQL/Redis/Ollama with development defaults and is not production evidence. Proposed sequence: SEC-01 repository supply-chain gate → SEC-02 edge/VM/SSH → SEC-03 secrets/least privilege → SEC-04 off-host restore → SEC-05 independent acceptance. Evidence: `docs/tasks/056-phase33-21-production-security-scoping.md`. Next single task: SEC-01. #21 B/C remain unstarted and the default #18 → #19 → #20 → #21 dependency order is unchanged.

## Phase 3.3 #20 A-stage scoping started — 2026-09-29 Asia/Taipei

Phase 3.3 #20 has started as a bounded scoping milestone on `advanced-v2` @ `429346d`; no production, schema, test, benchmark, or database configuration implementation was performed. Existing reusable assets are the Flyway baseline index inventory, JDBC repository query surface, order query-count regression test, and k6 normal/deadlock/sold-out/coupon workloads with database correctness reconciliation. Confirmed gaps are a representative query-to-workload matrix, repeatable `EXPLAIN ANALYZE` evidence, index effectiveness/redundancy analysis, controlled multi-run pre/post benchmarking, Hikari/transaction/lock-duration measurements, a safe slow-query capture contract, and a resource/growth-based capacity model. Historical k6 latency is context rather than a comparable pre/post baseline. Notion's statement that #19 supplies slow-query monitoring is not yet proven because #19 remains at A-stage; #20 must preserve that dependency or use disposable-MySQL capture. Evidence and proposed SQLPERF-01 → SQLPERF-02 → SQLPERF-03 split: `docs/tasks/055-phase33-20-sql-performance-scoping.md`. Next single task: SQLPERF-01 reproducible query and plan baseline. No tests, commit, push, merge, deploy, or #20 completion is claimed.

## Phase 3.3 #19 A-stage scoping started — 2026-09-28 22:17 Asia/Taipei

Phase 3.3 #19 has started as a bounded scoping milestone on `advanced-v2` @ `429346d`; no application/config/test implementation was performed. The committed MON-01–05 foundation is reusable: health-group isolation, Redis/Ollama dependency states, HTTP p95/p99 configuration, duplicate-safe order/payment outcome metrics, independent review, and final backend regression 295/295 with JaCoCo PASS. Confirmed open gaps are Prometheus registry/scrape exposure, JSON logs and correlation IDs, deadlock-retry and Redis-degradation alertable metrics, Prometheus/Grafana provisioning, alert rules and controlled firing evidence, retention/export policy, and an incident runbook. #18 production topology remains an external dependency for real notification/retention acceptance. Evidence and proposed OBS-01 → OBS-02 → OBS-03 split: `docs/tasks/054-phase33-19-observability-scoping.md`. Next single task: OBS-01 application telemetry contract. No tests, commit, push, merge, deploy, or #19 completion is claimed.

## MON-05 integration acceptance — 2026-09-28 Asia/Taipei

MON-05 is complete with **PASS** on `advanced-v2` from working baseline `69517fa`, still uncommitted. The initial
`mvn clean test` exposed two test-infrastructure interactions: cached Spring contexts collectively exceeded the
shared MySQL container's default connection ceiling, and an audit fault-injection constraint incorrectly rejected
valid history from earlier tests. The bounded correction raises only the test-container connection ceiling and scopes
the constraint to the current review target; production behavior and assertions are unchanged.

Focused verification passed 18/18. Claude Code independent read-only correction review returned **PASS** with no
blockers or valid defects. Final `mvn clean test` passed **295/295**, 0 failures/errors/skips, exit 0 in 3:47; JaCoCo
analyzed 156 classes and all configured coverage checks were met. Evidence:
`docs/tasks/052-mon05-integration-acceptance.md`. No real ECPay inbound traffic, deploy, commit, push, or merge was
claimed or performed. The non-blocking throwing-`MeterRegistry` executable fault-injection idea remains optional.

## MON-04 payment failure metric and callback deduplication — 2026-09-28 Asia/Taipei

MON-04 is complete for its bounded scope on `advanced-v2` from baseline `69517fa`, still uncommitted. A verified
provider decline now publishes process-local `shop.payments.failure` only when the existing `INITIATED -> FAILED`
conditional update succeeds and its transaction commits. Sequential replay and eight-way concurrent duplicate
callbacks count once; rollback, late success and `REFUND_REQUIRED` do not add counts. The metric has no identifier
tags, and registry failure is isolated from the already-committed payment outcome. Payment repository SQL, order-to-
payment lock order, amount/signature/provider rules and refund semantics are unchanged.

Final targeted verification: `mvn '-Dtest=PaymentIntegrationTest' '-Djacoco.skip=true' test` — exit 0, 18/18,
0 failures/errors/skips after Docker Desktop was started; the first attempt exited 1 with 18 Testcontainers
initialization errors because Docker was not running, with 0 assertion failures. Claude Code independent read-only
review: **PASS**, 0 blockers. Its only non-blocking follow-up is executable fault injection for a throwing metric
registry; static inspection confirms the exception is contained, and this is deferred to MON-05 with full regression
and coverage. Evidence: `docs/tasks/051-mon04-payment-failure-metrics.md`. No commit, push, merge or deploy. Next:
MON-05.

## MON-03 order counters and HTTP latency — 2026-09-28 Asia/Taipei

MON-03 is complete for its bounded scope on `advanced-v2` from baseline `69517fa`, still uncommitted. `OrderService`
now publishes process-local `shop.orders.success` / `shop.orders.failure` counters at the final service outcome:
only `OrderCreationResult.newlyCreated=true` counts success, replay does not, successful internal retry does not count
failure, and non-retry failure or exhausted retry counts exactly once. Metric-registry failures are isolated from the
original order result. `http.server.requests` has histogram and p95/p99 configuration; the targeted test sends real
401 traffic and then uses an authenticated Actuator metrics request to verify the meter and low-cardinality tags.

Final targeted verification: `mvn '-Dtest=OrderRetryTest' '-Djacoco.skip=true' test` — exit 0, 6/6, 0 failures,
errors, or skips. Initial independent review found one valid defect (no Actuator endpoint query); the bounded
correction added that query and the same reviewer re-reviewed read-only: **PASS**. Earlier failed rounds and repair
history are preserved in `docs/tasks/050-mon03-order-http-metrics.md`. Full suite and JaCoCo remain MON-05; no commit,
push, merge, or deploy. Next: MON-04.

## MON-02 Redis degradation and Ollama dependency status — 2026-09-27 21:14 Asia/Taipei

Codex implemented MON-02 on `advanced-v2` from baseline `69517fa`, preserving the uncommitted MON-01 changes and
without commit/push/merge/deploy. `StockCacheService` now exposes an immutable, thread-safe dependency snapshot with
`DISABLED`, `ENABLED`, and `LATCHED_DB_ONLY`; the health path never clears the latch or writes stock. The custom Redis
indicator adds bounded PING connectivity and a distinct `CONNECTION_FAILED` observation. The Ollama indicator calls
only `/api/tags`, checks configured chat/embed model presence, labels the result as tag-inventory-only, exposes counts
rather than model names, and has a configurable 500 ms default timeout. These indicators belong only to the separate
`dependencies` group; readiness remains `readinessState,db` and liveness remains dependency-free.

Targeted real-dependency verification: initial MySQL+Redis+controlled-Ollama run passed 2/2, exit 0, 46.145 s; the
expanded three-test run exposed only an immediate post-MySQL-unpause connection-pool timing assertion. Repair round 1
changed recovery verification to bounded polling, then `DatabaseReadinessHealthIntegrationTest` passed 1/1, exit 0,
40.621 s. Proven behaviors include DB outage readiness 503/liveness 200, Redis/Ollama outage not affecting readiness,
Redis disabled/healthy/failed/latched modes, PING recovery retaining the latch, bounded Ollama timeout/missing-model
reporting, and a successful DB-only order during a real Redis pause. A first-round Claude Code independent read-only
review returned PASS but was superseded: a completion audit found the Ollama-refusal criterion (#6) was only proven
for missing-model/timeout, not an actual HTTP error response. Repair round 2 added a controlled Ollama HTTP 503 stub
case and one assertion; `ExternalDependenciesHealthIntegrationTest` re-ran 1/1, exit 0, 41.942 s. A fresh independent
read-only review against the updated code returned **PASS**, all seven acceptance criteria and the `show-details`
scoping re-confirmed with file:line evidence, 0 valid defects/blockers; `ProductionProfileIntegrationTest.java`'s
change was checked and is in-scope (prod management-port separation supporting MON-02's isolation requirement).
JaCoCo/full regression remain MON-05. Evidence: `docs/tasks/049-mon02-redis-ollama-dependencies.md`. Next: MON-03.
Usage/cost evidence unavailable (unknown).

## MON-01 health probe separation and management security — 2026-09-27 21:02 Asia/Taipei

Codex implemented MON-01 on `advanced-v2` from baseline `69517fa` without commit/push/merge/deploy. The Actuator allow-list is now `health,metrics`; liveness remains dependency-free, readiness remains `readinessState,db`, and a separate `dependencies` group reserves the Redis/Ollama observation boundary for MON-02. The production management listener defaults to `127.0.0.1`; cross-container monitoring must explicitly bind `0.0.0.0` while keeping the management port unpublished. `ProductionProfileIntegrationTest` proves real-prod-profile main/management port separation, liveness/readiness reachability, management-only metrics, and rejection of main-port Actuator plus `env`/`configprops`/`beans`.

Verification: `mvn '-Dtest=ProductionProfileIntegrationTest' '-Djacoco.skip=true' test` first failed at startup because the membership-validation property was nested at the wrong level; after repair round 1, the same command passed 4/4, 0 failures/errors/skips, exit 0, 35.263 s, with Testcontainers MySQL. Claude Code independent read-only review: PASS, no blocker or valid defect. Coverage was deliberately skipped; full regression remains MON-05. Existing Ollama missing-model warnings were non-blocking. Evidence: `docs/tasks/048-mon01-health-management-security.md`. Next: MON-02 Redis/Ollama dependency indicators and bounded-timeout behavior. Usage/cost evidence unavailable (unknown).

## User-directed audit closeout — 2026-09-27 19:19 Asia/Taipei

Current checked HEAD: `69517fa`. At the user's request, remaining monitoring work is handed off as five **pending** Notion prompts (MON-01–05), and this session is closed without further automatic implementation. This supersedes open-ended continuation notes, **not** feature acceptance: no claim that all code through #17 is complete/perfect. Hub: https://app.notion.com/p/3e8708da9f92810787c3e89341756fa2 ; local evidence and remaining #2/RBAC/final-regression limitations: `docs/handoff/through-phase32-closeout.md`. Current config already has Actuator/DB readiness, so historical wording claiming no management configuration is stale. Preserve that policy while adding separate optional-dependency observation. Documentation only this turn; no new tests, commit/push/merge/deploy.

## Merge status of the 2026-09-27 batch

All five code PRs were merged into `advanced-v2` with merge commits, in this order. CI below is the GitHub Actions run on the merge commit itself (`publish-image` ran because the push was to `advanced-v2`).

| PR | Merge commit | Content | CI on merge commit |
|---|---|---|---|
| [#7](https://github.com/WhiteHot0321/esun-shopping-demo/pull/7) | `cb1a601` | Testcontainers 1.21.4 pin (task 041) | all 4 jobs success |
| [#8](https://github.com/WhiteHot0321/esun-shopping-demo/pull/8) | `d7a858b` | HTTP 405 + Allow header | no run recorded on this commit; its content is contained in `3249f8f`, which is green |
| [#9](https://github.com/WhiteHot0321/esun-shopping-demo/pull/9) | `3249f8f` | image upload row lock (task 042) | all 4 jobs success |
| [#10](https://github.com/WhiteHot0321/esun-shopping-demo/pull/10) | `0baa879` | #15 snapshot, distinct-buyer privacy floor, card validation | all 4 jobs success |
| [#11](https://github.com/WhiteHot0321/esun-shopping-demo/pull/11) | `11c60b6` | #18 B1 production-profile hardening | all 4 jobs success |

The entries below were written while this work was uncommitted; where they say so, read them together with this table. Test evidence in those entries is unchanged (local targeted runs, author self-checks unless stated); the CI results above are the only new verification, and they cover the merged tree, not individual claims. Still not proven: real-stack health gate (#18 B2), enforcement that a deployment sets `SPRING_PROFILES_ACTIVE=prod` (#18 B2/B3), independent review of the buyer-count and card-validation parts of #15.

## Recommendation support threshold fix — 2026-09-27

Claude Code, MODE: IMPLEMENT (user-authorized after a full #15/#17 recheck), written on branch feature/phase33-18-b1-prod-hardening @ 4b1b30d; committed on its own branch and merged into advanced-v2 via #10 (`0baa879`). Privacy defect: `recommendation.min-support` counted distinct orders, so one buyer repeating the same basket in two live orders passed the floor and a public endpoint could reveal it. `RecommendationRepository.coPurchased`/`popular` now count `DISTINCT member_id` (score = number of buyers). New `RecommendationIntegrationTest` case reproduced RED, then GREEN: `mvn -Dtest=RecommendationIntegrationTest,RecommendationServiceTest -Djacoco.skip=true test` exit 0, **9/9 + 7/7**, 0 failures/errors/skips. Author self-check only; no full suite, frontend, browser or independent review. Same change corrects the baseline wording for the 2026-09-27 recommendation follow-ups (58d1b07 was a B1-branch commit and not on advanced-v2 when this was written; it reached advanced-v2 through the #11 merge). Evidence: `docs/tasks/037-recommendation-system.md` (RECOMMENDATION-SUPPORT-BUYERS). Also from the recheck: #17 API-docs verified again (OpenAPI tests 6/6, ProductionConfigValidatorTest 19/19); its prod `API_DOCS_ENABLED=false` gate was then only on the unmerged B1 branch and is now on advanced-v2 through #11 (`11c60b6`).

## Recommendation card validation follow-up — 2026-09-27

08:11 Asia/Taipei price-boundary correction: recommendation price now must be finite and >= 0.01, matching the backend minimum. Added 0.001/0.009 rejection and 0.01 add-event assertions. Targeted RecommendationStrip tests: RED 9 pass/1 fail, then GREEN **10/10 PASS**, exit 0 (723 ms); diff check PASS. Small direct self-check; no independent review or broader rerun; later committed and merged via #10. The earlier 40-test run below predates this boundary fix.

Codex, MODE: IMPLEMENT, branch feature/phase33-18-b1-prod-hardening @ 58d1b07 (corrected 2026-09-27: not on advanced-v2): malformed recommendation card fields now filtered before rendering; null response body renders nothing. Targeted Vitest **40/40 PASS** (RecommendationStrip 10, App 30), exit 0; initial regression tests reproduced two failures before the fix. Small direct implementation/self-check, not independently reviewed; no backend/full-suite/browser rerun (committed and merged later via #10). Scope and evidence: `docs/tasks/037-recommendation-system.md`. This does not close the whole-project audit.

**⚠️ STRATEGY CHANGE (2026-09-21)**: Phase 3 (final iteration) pivots away from buyer/seller/maintainer feature iteration toward **deep engineering foundation: Transaction/Deadlock/Idempotency/Redis Lua atomicity/Testcontainers/k6 load tests/CI/CD** as one coherent line of work. Interview value: "Can you handle 10-item concurrent purchase without overselling? How do you test it at load? How do you deploy it?" matters more than "Did you build 10 more CRUD endpoints?" After Phase 3 closes, secondary priorities: observability (logging/metrics/tracing), deployment (Docker/k8s on real platform), SQL performance/indexing, API documentation (Swagger/OpenAPI).

## Planned next sequence — Phase 3.3 #18–#22 (#18 B1 merged via #11; everything else not started)

Planned on 2026-09-25 by Codex; planning-only update on `advanced-v2` @ `3509923`. No implementation, test, deployment, commit, push or merge is claimed. Each item follows a separate **A read-only scoping → B implementation → C independent acceptance** flow; only one heavy phase should run at a time.

1. **#18 Engineering-depth integration and production deployment [Critical]** — Reconcile the existing transaction/deadlock, idempotency, Redis Lua/Testcontainers and k6 evidence without reimplementing completed work; then deliver a single-Ubuntu-VM topology with Docker Compose, Caddy HTTPS, frontend/backend/MySQL/Redis, GHCR commit-SHA images, GitHub Environment approval, backup-before-deploy, health gate and rollback. Only ports 80/443 may be public; production disables API docs and payment sandbox. Full k6 remains restricted to disposable environments; production receives non-destructive smoke tests only.
2. **#19 Observability and production alerting [Medium-Heavy]** — Structured logs and correlation IDs, Micrometer/Prometheus metrics, order/payment/deadlock-retry/Redis-degradation alerts, dashboards and an incident runbook. Build on the existing monitoring work rather than duplicating it.
3. **#20 SQL performance and capacity baseline [Medium-Heavy]** — Slow-query inventory, `EXPLAIN ANALYZE`, index effectiveness/duplication, connection-pool and transaction lock-duration review, followed by repeatable pre/post k6 comparison and regression thresholds.
4. **#21 Production security hardening [Critical]** — VM firewall and SSH policy, TLS/security headers, secrets rotation and least privilege, image scanning/SBOM, dependency gates, backup retention and an off-host restore drill.
5. **#22 Go-live acceptance and portfolio closeout [Medium]** — Public HTTPS smoke, real ECPay inbound callback proof, non-destructive production acceptance, architecture/operation diagrams, measured performance results, failure stories and interview-ready documentation.

Dependencies are linear by default: **#18 → #19 → #20 → #21 → #22**. #21 may be pulled forward only for a concrete deployment blocker. VM, DNS, SSH and production ECPay credentials must be supplied out of band and must never be recorded in the repository or Notion.

## Current acceptance status (supersedes historical entries below)

- **2026-09-26 Phase 3.3 #18 B1 — production-profile hardening, implemented and tested, author self-review only; merged into advanced-v2 via #11 (`11c60b6`, post-merge CI green) — Claude Code, branch `feature/phase33-18-b1-prod-hardening` (from advanced-v2 @ 176b190). An independent-review contract exists (`docs/tasks/044`) but no review result is recorded, so the Critical-risk independent review (C) is still open.** Plan and gap table: `docs/tasks/043-production-deployment-plan.md` (single Ubuntu VM + Compose + Caddy + GHCR; B1 → B2 → B3 → C). B1: `application-prod.yml` removes defaults for `JWT_SECRET`/DB/`REDIS_HOST`/`CORS_ALLOWED_ORIGINS`; `ProductionConfigValidator` (`@Profile("prod")`) refuses a weak/dev JWT secret, blank or known-default DB password, sandbox payments, incomplete ECPay, wildcard/malformed CORS origins, and Redis stock without a password (names settings, never values); CORS origins configurable via `cors.allowed-origins`; actuator health only (liveness; readiness = DB, Redis deliberately excluded because the stock cache degrades to the DB) on an unpublished management port in prod. Verification: 19 new tests (13 validator, 2 CORS, 4 real-MySQL prod-profile), a mutation check (removing the prod management port fails 2 tests), backend `mvn test` all green (278 by Surefire reports, 0 failures) + JaCoCo PASS, live prod-profile start against real MySQL rejected a missing `JWT_SECRET` and the dev DB password. Not proven: real-stack health gate (B2), enforcement that deployments set `SPRING_PROFILES_ACTIVE=prod` (B2/B3), independent review (C, Critical). B2 open decision: production AI-support provider (self-hosted Ollama vs Claude API vs off).

- **2026-09-26 #15 recommendation snapshot contract — implemented, tested and independently reviewed PASS; merged via #10 (`0baa879`).** User explicitly selected consistent snapshots, baseline advanced-v2 @ `176b190`. Both service entry points explicitly use read-only REPEATABLE_READ; ranking and payload use the first consistent-read snapshot, and concurrent sell-out/soft-delete commits appear on the next request. Replaced the contradictory immediate-dropout promise and renamed the mock-only missing-payload test. Real MySQL public/member interleaving cases verify distinct connections, commit before payload lookup, retained old payload in the current response and exclusion in the next. `mvn '-Dtest=RecommendationIntegrationTest,RecommendationServiceTest' '-Djacoco.skip=true' test`: **15/15 PASS** (8 integration + 7 service), zero failures/errors/skips, exit 0, 44.592 s. Claude independent bounded snapshot review PASS. No full-suite/coverage/frontend re-run or blanket full-feature audit claimed (uncommitted when written; merged later via #10). Evidence: `docs/tasks/037-recommendation-system.md`.

- **2026-09-26 HTTP-METHOD-405 — implemented and targeted acceptance PASS; merged via #8 (`d7a858b`).** Codex, MODE: IMPLEMENT, advanced-v2 @ `176b190`. Dedicated handler preserves 405 and Allow headers; unexpected exceptions remain 500. RED reproduced expected 405 vs actual 500; after repair 21 non-container tests PASS. User-authorized rerun after Docker 29.8.0 recovery: `mvn '-Dtest=AuditLogIntegrationTest' '-Djacoco.skip=true' test`, **9/9 PASS**, zero failures/errors/skips, BUILD SUCCESS, 37.911 s. Real-endpoint 405/Allow and 404 assertions executed; prior Docker initialization blocker resolved. No full suite/coverage/frontend rerun or independent review claimed. Evidence: `docs/tasks/034-audit-log.md` HTTP-METHOD-405 follow-up. Existing edits preserved (uncommitted when written; merged later via #8). This closes the bounded 405 repair, not the overall historical-code audit.

- **2026-09-26 image upload concurrency repair — implemented, tested and independently reviewed PASS; merged via #9 (`3249f8f`) (Task 042).** Codex, `MODE: IMPLEMENT`, baseline `advanced-v2` @ `3509923`. A real-MySQL controlled-contention regression reproduced two HTTP 200 responses when two uploads competed for the last image slot. Upload now obtains the product row lock before its first snapshot read, then checks active/owner state, image count and display order. Four new integration cases verify the 10-image cap, unique ordering, deletion/ownership changes while waiting, and metadata/file/audit counts. Targeted **37/37** PASS; Claude read-only independent review PASS (no blockers); final `mvn clean test` **250/250**, zero failures/errors/skips, JaCoCo gates PASS, 3:13 min. Frontend/browser E2E not rerun; same-product uploads hold the lock during file I/O. Uncommitted when written; merged later via #9; existing changes preserved. This is bounded image-upload acceptance, not a blanket independent audit of all prior code. Evidence: `docs/tasks/042-image-upload-concurrency.md`.

- **2026-09-25 Docker 29 test compatibility repair — implemented and tested; merged via #7 (`cb1a601`) (Codex, MODE: IMPLEMENT), baseline advanced-v2 @ 3509923.** `backend/pom.xml` overrides Boot's `testcontainers.version` to 1.21.4 (was inherited 1.19.8; docker-java now 3.4.2). Resolves named-pipe discovery HTTP 400 on Docker Engine 29.8.0 without machine configuration changes. Targeted real-MySQL OrderServiceIntegrationTest 4/4, exit 0; final `mvn clean test` **246/246**, zero failures/errors/skips, JaCoCo gates PASS, 3:15 min, including #17's 6 docs tests and prior MySQL/Redis regression cases. Existing source/tests and gates unchanged. Earlier 248/113-error reports were initialization failures, not 113 proven business defects. Live Ollama model missing/fallback warnings remain; frontend/browser E2E and live payment callbacks were not rerun. This closes the local test-infrastructure blocker, not a blanket whole-codebase independent audit. Task: `docs/tasks/041-testcontainers-docker29-compatibility.md`.

- **Phase 3.2 #17 — API 文件 [Small], complete, merged and independently reviewed PASS — 2026-09-25, Claude Code implementation; Codex review of merged `advanced-v2` @ `3509923`.** springdoc-openapi 2.6.0 generates the OpenAPI 3 spec (`/v3/api-docs`, `.yaml`) and Swagger UI (`/swagger-ui.html`) from the 12 controllers (`@Tag` per controller, `@Operation(summary)` on all 51 paths, roles named in the summary). `JwtAuthFilter.isPublicRoute` is now a public static used by both the filter and `OpenApiConfig`, so the spec's bearer-auth marks cannot drift from what the filter enforces (a test compares every operation); docs paths are GET-only public routes. `API_DOCS_ENABLED` (default true, **set false in production**) removes the endpoints (404, tested). Author verification: backend `mvn test` **246/246** (+6), JaCoCo PASS; live app: api-docs 200 (51 paths, 12 tags), UI 200, `/api/orders` still 401. Codex static review found no blocker; merged-tree GitHub Actions [CI #66](https://github.com/WhiteHot0321/esun-shopping-demo/actions/runs/36102783582) completed successfully (backend test + JaCoCo, frontend test/build, Docker build and image publish). A local focused command did not start Maven because PowerShell parsed the unquoted comma; per review-only single-command limit it was not rerun. Known follow-ups: field-level `@Schema`, error examples, deployment default of `API_DOCS_ENABLED=false`. Details: `docs/tasks/040-api-docs.md`, `docs/handoff/phase32-17-api-docs.md`.
- **Phase 3.2 #16 — CI/CD 自動化 [Maintainer / Small-Medium], complete, merged and independently reviewed PASS — 2026-09-25, Claude Code implementation; Codex review of merged `advanced-v2` @ `3509923`.** Rewrote `.github/workflows/ci.yml`: push only on main/advanced-v2/`v*` tags plus `pull_request` and `workflow_dispatch`; concurrency cancels superseded runs except on protected refs; global `contents: read`; surefire + JaCoCo reports uploaded with `if: always()`; buildx + GHA cache image build without push for PRs; `publish-image` pushes `ghcr.io/<repo>/backend` with the built-in `GITHUB_TOKEN` (only job with `packages: write`, only after backend-test + frontend-build + docker-build are green, never on PRs). Added `.github/dependabot.yml` (maven/npm/docker/actions), `backend/.dockerignore`, README CI badge. No application code touched. Verification: workflow YAML/job graph/permissions review and local backend image build PASS; merged-tree GitHub Actions [CI #66](https://github.com/WhiteHot0321/esun-shopping-demo/actions/runs/36102783582) completed successfully, including the first observed GHCR `publish-image` job. Not proven: branch protection/required checks, image scan/SBOM, package visibility/content inspection or deployment. Deliberately no deploy job (target undecided), frontend image not built (no Dockerfile), k6 suite not wired into CI. Details: `docs/tasks/039-cicd-automation.md`, `docs/handoff/phase32-16-cicd.md`.
- **Phase 3.2 #15 — 推薦系統 [Tier 3 / Medium], complete and verified (self-review only, no independent audit) — 2026-09-24, Claude Code, branch `feature/phase32-15-recommendation`.** Read-only recommendations derived from order history: public `GET /api/products/{id}/recommendations` ("customers who bought this also bought") and JWT-only `GET /api/recommendations` (personalised; identity comes from the token, never a parameter). Three deterministic tiers, each item labelled: `CO_PURCHASE` (distinct live orders sharing the anchor / the member's purchases), `POPULAR`, `NEW_ARRIVAL` filler for cold start; cancelled orders, sold-out and soft-deleted products never count or appear; a signal needs `recommendation.min-support` (default 2) distinct orders so a public list cannot reveal a single buyer's basket; the member list excludes purchased and carted products. No writes, no locks, nothing on the checkout/stock/payment path; one read-only transaction per request; new repeatable `15_recommendation.sql` adds `idx_order_detail_product_order (product_id, order_id)`. Frontend `RecommendationStrip` ("為你推薦" for members, "買過「X」的人也買了" under the review panel, add-to-cart through the existing quantity path, stale-response guard, malformed payloads render nothing). Verification: backend **240/240** (real-MySQL `RecommendationIntegrationTest` 6/6, `RecommendationServiceTest` 8/8), JaCoCo PASS; Vitest **80/80**, checkout 3/3, build PASS; mutation check (drop the cancelled-order filter) failed 3 of 6 integration cases. Known follow-ups: no browser E2E, no rate limit on the public endpoint, no browse/click signals, time decay or cache, not a Codex review. Details: `docs/tasks/037-recommendation-system.md`, `docs/handoff/phase32-15-recommendation.md`. Engineering note: a public aggregate needs a minimum-support threshold, or it leaks individual baskets; and a read-only feature can still hide correctness rules (cancelled orders, stock) that belong in the query, not the UI.

- **Phase 3 item 3 — k6 load/stress suite, complete, independently reviewed and verified — 2026-09-24, Claude Code, branch `feature/phase3-k6-suite`.** One k6 script with four workloads (normal 50 orders/s, opposite-order two-item deadlock stress with 40 VUs, sold-out 600 attempts vs 200 units, hot coupon 400 attempts vs quota 100) driven by `bench/run-phase3-suite.py`, which starts a disposable MySQL 8 + the real backend jar (Redis stock off, all DB/Redis settings pinned) and reconciles the database after every workload; pass/fail is correctness (no oversell, exact quota, stock == initial - sold, orders committed == HTTP 200s, InnoDB `lock_deadlocks`/`lock_timeouts` counter deltas 0), latency is recorded as a baseline (normal p50/p95/p99 21/352/513 ms; deadlock stress capped ~56 orders/s by row-lock serialization; this machine saturates near 75-95 orders/s). **Real defect found and fixed:** the sold-out workload answered 9/600 attempts with HTTP 500 at the sell-out edge (no oversell, wrong contract): `sp_decrease_stock`'s SIGNAL (SQLSTATE 45000/1644) hit the generic DataAccessException handler; `GlobalExceptionHandler` now maps it to 409 `商品庫存不足` (`GlobalExceptionHandlerTest`); 0 x 500 in 4 post-fix full runs + 2 sold-out repeats. Verification: backend `mvn clean test` **227/227** + JaCoCo PASS; independent fresh-context read-only review PASS (no must-fix; three should-fix items applied: race-path 409 counted separately, environment pinned, InnoDB counters instead of log grep). Not covered: Redis-enabled stock, per-member coupon stress, cart-checkout/cancel under load, multi-run statistics, multi-instance/soak; not a Codex review. Details: `bench/PHASE3-K6.md`, `docs/tasks/038-k6-load-suite.md`, `docs/handoff/phase3-k6-suite.md`. Engineering note: an "out of stock" decided inside the database after a snapshot pre-check must map to the same client answer as the pre-check, and a deadlock check must read the engine's counters because a retried deadlock never appears in the application log.

- **Phase 3.2 #14 — 優惠券系統 [Tier 3 / Critical], complete, independently reviewed (one repair round) and verified — 2026-09-24, Claude Code, branch `feature/phase32-14-coupon`.** ADMIN-issued, platform-wide discount codes (PERCENT 1-99 with optional cap, FIXED whole dollars; min order, total quota, per-member limit, validity window; rule fields immutable, only active/expiry/quota move, all changes audited in the same transaction). Buyers preview a code against their server-side cart (`POST /api/cart/coupon-preview`, advisory) and name it at checkout; the client never sends a price. Redemption runs inside the order transaction (`Propagation.MANDATORY`): lock the coupon row (`FOR UPDATE`, a current read), validate every rule on the locked row, bump `used_count` and `coupon_member_usage`; `shop_order.price` stays the payable amount so payment charges the discounted price, with `coupon_id/coupon_code/discount_amount` as snapshot; payable never drops below 1 and discounts are whole dollars (ECPay). Cancel releases the slot once in the same transaction. Documented lock order everywhere: order → coupon → member usage → product. New `14_coupon.sql` (repeatable, legacy orders read discount 0). Frontend: coupon field with preview and stale-preview reset in `CartPanel`, discount line in `OrdersPanel`, ADMIN `CouponPanel`, audit labels. Verification: backend `mvn clean test` **225/225**, JaCoCo PASS (real-MySQL `CouponIntegrationTest` 13/13 incl. 12-way quota race, per-member race, cancel-vs-redeem race, same-buyer cancel/checkout deadlock regression); Vitest **71/71**, checkout 3/3, build PASS; mutation checks (drop coupon lock; revert `FOR UPDATE OF o`) each fail the matching test; live E2E on a disposable MySQL + real backend + browser (create → apply → checkout → sandbox payment of 2160 → second use 409 → cancel paid order → REFUND_REQUIRED, counters back to 0, stock restored). Independent fresh-context read-only review FAILED first pass on a real cancel/checkout deadlock (bare `FOR UPDATE` on the order⨝shipping_address join also locked the address row; reproduced as HTTP 500, fixed with `FOR UPDATE OF o`) plus a vacuous release test and five should-fix items, all repaired and narrowly re-verified; not a Codex review. Known follow-ups: preview rate limit, seller-scoped coupons/stacking, refund execution, Redis Lua for a hot coupon. Details: `docs/tasks/036-coupon-system.md`, `docs/handoff/phase32-14-coupon.md`. Engineering note: a counter is only safe if it is read and written under a current-read row lock, and a bare `FOR UPDATE` on a join silently locks every joined table — name the table (`OF o`) or the documented lock order is not the real one.

- **Phase 3.2 #13 — 金流整合 [Critical], complete, independently reviewed and verified — 2026-09-24, Claude Code.** Closed the hole where `POST /api/orders` stored the client's `payStatus` (an order could be created PAID): orders are now always PENDING and only a verified payment-provider callback can mark one PAID. New `payment` ledger (`01_schema.sql`, repeatable `13_payment.sql`) with a DB-enforced single live attempt per order (generated `active_order_id` + UNIQUE); `POST /api/orders/{id}/payment` (owner-only, server-priced, idempotent), public `POST /api/payments/callback` authenticated by HMAC-SHA256 before any transaction, idempotent compare-and-set under a fixed order→payment lock order, REFUND_REQUIRED for money that cannot be applied (cancelled order, closed attempt, amount mismatch), cancel/callback race converges to one state. Sandbox (`PAYMENT_SANDBOX_ENABLED`, default **off**) lets a buyer simulate the provider through the same signed path; off means no payment starts and even a correctly signed callback is refused. `PaymentGateway` is the seam for a real provider. Frontend: payment badges + pay/retry flow in `OrdersPanel`, self-selected payStatus UI removed. Verification: backend `mvn clean test` **184/184**, JaCoCo PASS; real-MySQL `PaymentIntegrationTest` 16/16 (8-way concurrent duplicate callbacks, callback-vs-cancel race ×5, DB invariants, migration rerun), `PaymentDisabledIntegrationTest`, `HmacPaymentGatewayTest` 8/8; Vitest **56/56**, checkout 3/3, build PASS. Independent fresh-context read-only review PASS; three should-fix findings (transaction before signature check, unbounded untrusted BigDecimal, dropped mismatched-amount money) repaired and retested. **ECPay integration (follow-up task, same day):** the earlier real-ECPay work on branch `codex/phase31-pay-status` (clashing class names, table `payment_transaction`; **archived 2026-09-24 as tag `archive/codex-phase31-pay-status` @ `9b3c4e0`, branch and worktree removed**) was ported onto this state machine instead of merged: `PaymentGateway` is now selected by `payment.provider` (`none` default | `sandbox` | `ecpay`), `EcpayPaymentGateway` (SHA-256 CheckMacValue, credit card only, integer TWD, `1|OK` ack, form redirect in `OrdersPanel`) added; a second independent review FAILED on a real bug (CheckMacValue parameter sort must be case-insensitive — it also existed in the original branch) which was fixed, plus late-success-on-closed-attempt now applies to a still-payable order, ECPay re-pay opens a fresh trade number, and `SimulatePaid` is refused off stage. The old branch's payment code is superseded and must not be merged. Final: backend `mvn clean test` **206/206** + JaCoCo PASS, Vitest **58/58**, build PASS. **Live verification (2026-09-24):** real ECPay stage accepted the server-signed form (payment page shown, amount/trade no correct), rejected a re-posted `MerchantTradeNo` with `10300028` (confirming the fresh-trade-number-on-retry design) and accepted a new one; browser E2E on real backend+DB+UI covered fail → retry → paid → cancel-paid → REFUND_REQUIRED with stock restored. Known follow-ups: ECPay **inbound** callback from a real ECPay payment NOT yet received (needs a public HTTPS callback URL; manual steps in task 035), not a Codex review, no refund execution / expiry / payment audit / callback rate limit, sellers can still fulfil unpaid orders (business decision). Details: `docs/tasks/035-payment-integration.md`, `docs/handoff/phase32-13-payment.md`. Engineering note: payment truth is a verified callback plus compare-and-set on locked rows, never a client field; unappliable money must leave a ledger trail.

- **Phase 3.1 #12 — 操作稽核日誌 [Maintainer], complete and verified — 2026-09-24, Claude Code (self-review; no independent audit).** Append-only `audit_log` (actor, role, action, target, JSON before/after) via `01_schema.sql` and repeatable `12_audit_log.sql`. Written by explicit `AuditLogService.record` calls **inside the same transaction** as the change (product create/update/delete/restock/image upload, bulk ops one row per product, order status changes, review hide/restore); checkout creation is intentionally not audited (already in `order_status_history`; keeps the deadlock-tuned hot path untouched). Before snapshots use row locks so concurrent writers keep a gapless chain; `created_at` is app-written to avoid the UTC/Asia/Taipei skew. `GET /api/admin/audit-logs` is ADMIN-only with actor/action/target/time filters and paging; no mutation endpoints. Frontend `AuditLogPanel.vue` (ADMIN button). Verification: backend `mvn clean test` **159/159**, JaCoCo PASS; real-MySQL `AuditLogIntegrationTest` 9/9 (incl. audit failure rolling back the business change, 8-way concurrent restock chain); Vitest **50/50**, checkout 3/3, build PASS. Known follow-ups: append-only is app-level only (no DB privilege/trigger hardening), no retention job, auth/profile/cart events not audited, unmapped HTTP methods return 500 not 405 (pre-existing), no live browser E2E. Details: `docs/tasks/034-audit-log.md`, `docs/handoff/phase31-12-audit-log.md`. Engineering note: an audit row must share the transaction of the change it describes, and "before" must be read under the row lock — otherwise the trail can lie under concurrency.

- **Phase 3.1 #11 — 訂單狀態流程 [Seller + Buyer], complete, independently reviewed and fully verified — 2026-09-24, Claude Code.** Order lifecycle CREATED→CONFIRMED→SHIPPED→DELIVERED with CANCELLED allowed only before shipping (stock returned, Redis compensated after commit). New `shop_order.order_status` plus append-only `order_status_history` (timeline + audit) via `01_schema.sql` and repeatable, backfilling `11_order_status.sql`. Buyer: JWT-owned order list/detail/timeline and self-cancel (foreign orders 404). Seller/Admin: scoped list/detail and transitions; sellers see only their own lines and may transition only orders whose lines are all theirs, admin unrestricted; server-provided `allowedActions`. Transitions row-lock the order, re-validate on the locked row, compare-and-set and log in one transaction. Frontend `OrdersPanel.vue` (buyer/seller). Verification: backend `mvn clean test` **145/145**, JaCoCo PASS; real-MySQL `OrderStatusIntegrationTest` 6/6 (incl. 6-way concurrent cancel restoring stock once, migration run twice); Vitest **43/43**, checkout 3/3, build PASS. Independent read-only audit PASS; lock-order (SQL collation vs Java sort) and stale-response findings repaired. Known follow-ups: multi-seller orders need admin to advance; Redis compensation drift on DB-only checkouts; no notifications; no live browser E2E. Details: `docs/tasks/033-order-status-flow.md`, `docs/handoff/phase31-11-order-status-flow.md`. Engineering note: make the state machine's authority the locked database row (compare-and-set), never the client's view of the status.

- **Phase 3.1 #10 — 賣家商品管理後台 [Seller], complete, independently reviewed and fully verified — 2026-09-21, Codex (core CRUD/restock, 2026-09-20) + Claude Code (search/pagination, images, bulk, repairs).** SELLER/ADMIN-gated, JWT-owned product list/search (keyword, status, paging)/create/update/soft-delete/restock, single- and multi-image upload (server-generated UUID names, content-type + extension + magic-byte checks, traversal-safe root guard, cleanup on failure, `product_image` metadata, public serving limited to `/uploads/products/{uuid}.{jpg|png|webp}`), and all-or-nothing bulk delete/restock; atomic restock, `deleted_at` soft-delete filtering across catalog/order/stock/stored procedures/AI indexing; frontend management UI with search, paging, bulk selection and image upload. Verification: final backend `mvn clean test` **137/137**, 0 failures/errors/skips, JaCoCo PASS (ProductService 108/110 lines); real-MySQL `ProductManagementIntegrationTest` 3/3; frontend checkout 3/3, Vitest **35/35**, production build PASS; `git diff --check` PASS. Independent read-only audit: **PASS** on six security/ownership rules; its seven implementation findings (partial upload rollback, pre-commit index side effects, orphan file on failed write, static location slash, offset overflow/400s, 413 for oversized upload, restock cap) were repaired and retested. Follow-ups recorded, not blocking: image-delete endpoint / orphan sweeper, upload rate limit, `nosniff` header, live browser E2E. Details: `docs/tasks/031-seller-product-management.md`, `docs/handoff/phase31-10-seller-product-management.md`. Feature commit `13d13f3` (branch `feature/phase31-10-seller-product-management`); merge commit `7d6a887` into `advanced-v2`. Engineering note: repeat the ownership predicate inside the write itself, and make filesystem and database changes compensate each other.

- **Phase 3.1 #9 — 商品評論系統 [Buyer + Seller], implemented, fully verified, independently reviewed and merged — 2026-09-20, Codex.** Added public visible-review paging/stable sorting and visible-only average/count; verified-purchase buyer create, author-only update/delete, DB-backed one-review-per-member/product concurrency enforcement; and seller-owned/admin hide/restore moderation without buyer-content editing. Integrated the missing role-bearing JWT and product creator ownership prerequisites. Frontend exposes rating/count, buyer authoring, and seller/admin moderation. Verification: product-review real-MySQL integration 3/3, targeted RBAC compatibility PASS, final backend `mvn -q clean test` **104/104** with 0 failures/errors/skips and JaCoCo PASS; frontend checkout 3/3 and Vitest **26/26**, Vite production build PASS (102 modules), `git diff --check` PASS. Two separate Claude Code read-only audits (prerequisite authorization and product-review contract) both returned **PASS**. Feature commit `7c24669`; merge commit `8876fa4`; both pushed through `origin`, with the merge integrated into `advanced-v2`. Details: `docs/tasks/028-product-reviews-system.md`, audits 029/030. Engineering note: review authorization needs both server-derived identity and database invariants; UI role hiding is only presentation, while backend ownership checks remain authoritative.

- **Phase 3.1 #8 — 購物車持久化 [Buyer], implemented, independently reviewed, full
  regression passed and merged — 2026-09-20, Codex.** Added a MySQL-backed member cart with
  positive-quantity and member/product uniqueness constraints; JWT-owned list/add/update/delete/clear
  APIs; server-authoritative frontend restore and write synchronization; and transactional checkout
  that atomically consumes one member cart while preserving it on failure. Cross-member item access
  returns 404. Client ordering guards cover stale GET vs PUT, delayed add vs clear, clear vs checkout,
  and ambiguous retry vs clear; backend member locking prevents two distinct request IDs from consuming
  the same cart twice. Verification: real-MySQL `CartIntegrationTest` **4/4**, final backend
  `mvn clean test` **100/100**, 0 failures/errors/skipped with JaCoCo gate PASS; frontend checkout
  tests **3/3**, Vitest **24/24**, production build PASS (101 modules), and `git diff --check` PASS.
  Terra read-only independent review: **PASS** after six explicitly bounded repair rounds. Details:
  `docs/tasks/027-cart-persistence.md`. Feature commit `6c79815`; merge commit `10c642c` was pushed
  to `origin/advanced-v2`. Engineering note: persistence alone is
  insufficient for a cart—transactional consumption and client/server operation ordering are part of
  the data-consistency contract.
- **Phase 3.1 #7 — 收件地址簿 [Buyer], complete, merged and re-verified —
  2026-09-24, Codex, `advanced-v2` @ `8e6cd20`.** The implementation commit `6c79815`
  was merged by `10c642c`; the older `feature/frontend-ux-revamp` @ `ab1ff81`, not-committed
  status is superseded. Added member-owned multi-address CRUD, DB-enforced single
  default, order/address foreign-key persistence, referenced-address deletion protection, JWT
  ownership enforcement, default-address fallback, and checkout address selection/quick-add UI.
  Backend production and test sources compile. After Docker 28.4.0 became available, two bounded
  corrections fixed six stale `OrderServiceTest` Mockito signatures and the missing MockMvc web
  test environment. `ShippingAddressIntegrationTest` then executed both cases: **1 passed, 1 failed**
  because the backend emitted `isDefault` while its integration assertion and frontend consumed
  `default`; the real frontend would not have recognized the default address. The user explicitly
  authorized a third repair, the contract was unified on `isDefault`, and final targeted verification
  passed: real-MySQL `ShippingAddressIntegrationTest` **2/2**, 0 failures/errors/skipped with JaCoCo
  gate passing; frontend checkout tests 3/3, Vitest 19/19 and production build passed. One repair round fixed URL-specific
  GET mocking after the new address load exposed an ordering assumption in an existing test. Task
  details: `docs/tasks/026-shipping-address-book.md`. Independent authorization review completed;
  no commit/push/merge. User interventions: two scope-limit approvals; elapsed/token/cost/five-hour
  usage delta unavailable. Independent authorization review found one valid blocker: DTO validation
  still required caller `memberId` before the controller could replace it with JWT identity. The
  requirement was removed and the pending integration case now omits the body field (repair round
  2); the user-authorized JSON contract correction was repair round 3. Final closure found and fixed
  legacy order fixtures without addresses, nullable address-ID auto-unboxing, and an address SELECT
  establishing a repeatable-read snapshot before the idempotency claim. Final `mvn clean test`:
  **96/96**, 0 failures/errors/skipped, JaCoCo gate PASS; the final transaction-order and fixture
  changes passed an independent read-only review. Closure re-verification on the merged tree:
  real-MySQL `ShippingAddressIntegrationTest` **2/2 PASS** and frontend checkout **3/3** +
  Vitest **35/35 PASS**. This closure changed documentation only; production/test code was unchanged.
  Engineering note:
  ownership must come from the verified principal, and
  a unique database invariant must back application-level default-address switching.
- **Phase 3.1 #6 — 個人資料編輯 [Buyer], complete, merged and re-verified —
  2026-09-24, Codex, `advanced-v2` @ `8e6cd20`.**
  Added nullable `member.display_name` / `member.phone` columns to the fresh schema and an
  idempotent `06_member_profile.sql` migration. Authenticated buyers can read and replace only
  their own profile through `GET/PUT /api/member/profile`; the target identity comes exclusively
  from the verified JWT email, while email is read-only and request-body identity fields are
  ignored. Optional blank values normalize to SQL `NULL`; display name and phone length/format
  are validated. The new frontend profile panel loads existing values, prevents email editing,
  validates phone input and persists profile changes. Verification: real-MySQL
  `MemberProfileIntegrationTest` **2/2 PASS** with JaCoCo gate passing; frontend Vitest
  **18/18 PASS**; production build PASS; `git diff --check` PASS. The profile work was later
  included in feature commit `6c79815` and merge commit `10c642c` on `advanced-v2`. Closure
  verification on 2026-09-24 passed real-MySQL `MemberProfileIntegrationTest` **2/2** and
  frontend checkout **3/3** plus `App.spec.js` **26/26**. One repair round corrected an
  unsupported MySQL `ADD COLUMN IF NOT EXISTS` form to an `information_schema`-guarded migration.
  The focused suite proves fresh initialization and the already-current no-op migration path;
  a populated legacy-DB migration drill was completed on 2026-09-24 against a disposable MySQL 8
  database: two pre-existing member rows retained the same count and legacy-field MD5 before and
  after migration, both nullable profile columns were added, profile values remained intact after
  a second no-op execution, and the disposable container was removed. Subsequent merged-tree full
  regressions through Phase 3.1 #10 remain broader regression evidence. Closure details:
  `docs/tasks/032-phase31-profile-closure.md`.
  User interventions: one explicit scope expansion approval; elapsed/model cost/five-hour usage
  delta unavailable. Engineering note: derive record ownership from authenticated server context,
  never from an editable identifier in the request payload.
- **Phase 3.1 #5 — 忘記密碼 & 修改密碼 [Buyer], implemented, corrected and verified —
  2026-09-18–19, Claude Code + Codex, branch `feature/frontend-ux-revamp`; original implementation
  commit `03249a0`.** Per the P0
  scope in `docs/tasks/017-buyer-feature-list.md` §1.4: `POST /api/auth/forgot-password`
  (public), `POST /api/auth/reset-password` (public, one-time token) and
  `POST /api/auth/change-password` (JWT-protected) added to `AuthController`/`AuthService`.
  Since no SMTP/mail infrastructure exists yet, this is deliberately the task's own described
  "minimal token-only version": a 256-bit random token is generated, its SHA-256 hash (never the
  raw token) is stored in a new `password_reset_token` table
  (`backend/DB/05_password_reset_token.sql`, 30-minute expiry, one-time use enforced via
  `used_at`, superseding any earlier unused token for the same member), and the raw token is
  logged server-side instead of emailed — wiring a real mail sender is an explicit follow-up, not
  part of this pass. `forgot-password` always returns 200 regardless of whether the email is
  registered (same account-enumeration defense as `login`'s unified error message).
  **Bug found and fixed during manual browser verification**: `change-password` initially reused
  HTTP 401 for "wrong current password," but the frontend's global axios response interceptor
  (`frontend/src/api.js`) treats *any* 401 as session expiry and force-logs-out the caller — so a
  simple typo in the current-password field would silently end the user's session instead of
  showing a field error. Fixed by using 400 for that case (the JWT itself is still valid; only
  the submitted field is wrong), verified both via the corrected unit/integration tests and live
  in the browser (error toast shown, session preserved). Frontend: `AuthPanel.vue` gained a
  "忘記密碼？" link and a forgot-password mode; new `ChangePasswordPanel.vue` (toggled from the
  topbar "修改密碼" button) and `ResetPasswordView.vue` (new `/reset-password?token=...` route in
  `router.js`, which was already real infrastructure — not a placeholder as earlier docs assumed)
  handle the other two flows. Full backend `mvn clean test`: **89/89, 0 failures/errors/skipped**
  (81 baseline + 8 new), run against real MySQL via Testcontainers (the new DB file added to
  `AbstractMySqlIntegrationTest`'s init list). Frontend `npm test` (3+16/16) and `npm run build`
  both clean. Manual browser E2E against the user's live dev containers (`esun-mysql` on host
  port 3310; the new table was applied there by hand since the long-running container predates
  this migration file) with a fresh Spring Boot instance and the Vite dev server: register →
  change-password (wrong password rejected without logout, correct password accepted, re-login
  with the new password succeeds) → forgot-password (identical success message queried for both a
  registered and an unregistered email) → reset-password via the logged token (real page renders,
  password reset, login with the new password succeeds, and replaying the same token via curl is
  correctly rejected with 400 — one-time use confirmed). Also fixed an unrelated pre-existing bug
  found while starting the preview: `.claude/launch.json`'s `frontend-dev` entry was missing
  `cwd: "frontend"`, so `preview_start` tried to run `npm run dev` from the repo root and failed;
  added the missing field (`frontend-mock-ui`/`backend` already had it). Test account created
  during verification was deleted from the live `esun-mysql` container afterward. Not done: an
  actual email-sending integration (explicitly deferred per the task's own scope), rate-limiting
  repeated forgot-password requests, and an automated frontend component test for the three new
  Vue components (covered by manual E2E only, matching this branch's existing pattern for its
  other UI-only additions). Changes are implemented and verified but **not committed** — this
  branch already carries other uncommitted, unrelated work from before this session
  (`docs/analysis/`, `docs/tasks/016-020`, `package-lock.json`, `AGENTS.md`), so committing was
  left for an explicit user decision on scope rather than bundled automatically. **Independent
  Codex correction, 2026-09-19:** the original query → password update → `markUsed` sequence was
  not atomic under concurrent replay. Task 025 now locks the token row with `SELECT ... FOR UPDATE`,
  conditionally consumes it and updates the password in one `@Transactional` boundary. The first
  real-MySQL run also found and fixed an application/MySQL timezone mismatch by standardizing token
  expiry on UTC. Final targeted Testcontainers verification: `AuthIntegrationTest` 5/5 plus
  `AuthServiceTest` 12/12, **17/17 PASS**; independent Terra static security review PASS. Detailed
  evidence: `docs/tasks/025-phase31-password-reset-atomicity.md`; correction commit `50ce7f9`.
- **Product embedding re-index + member_id widening, committed — 2026-09-18, Claude Code
  (MODE: IMPLEMENT), commits `224c348` and `3f2ab48` on `advanced-v2` (rebased from
  `feature/frontend-ux-revamp` after PR #6 merged).** Two independent fixes picked up from
  pre-existing uncommitted work: (1) `EmbeddingIndexService` extracts the per-doc embed+upsert
  logic out of `EmbeddingIndexRunner` and adds `indexProduct(productId)`, which
  `ProductService.createProduct` now calls right after the write so a newly created product is
  immediately searchable by the AI customer service instead of only after the next app restart;
  indexed content now also includes price/quantity, not just the name. (2) `shop_order.member_id`
  and `order_request.member_id` widened from `VARCHAR(20)` to `VARCHAR(100)` in `01_schema.sql`/
  `04_add_order_request.sql`, plus a matching `@Size(max = 100)` on `CreateOrderRequest`, because
  member ids are email addresses (JWT auth uses email as the identifier) and were being silently
  truncated past 20 characters. Full backend `mvn clean test`: **81/81, 0 failures/errors/skipped**,
  JaCoCo gate passing, both changes present together. **Schema drift discovered while verifying
  whether the live `esun-mysql` container needed a matching `ALTER TABLE`**: it did not — both
  columns on the running container are already `VARCHAR(255)` (confirmed via `SHOW CREATE TABLE`),
  wider than both the old committed `VARCHAR(20)` and the new `VARCHAR(100)`. The tracked
  `01_schema.sql` has therefore not matched what the long-running dev container actually executes
  for some time; nothing was altered on the container since it already satisfies the new, stricter
  application-level `@Size(max = 100)` bound. `payStatus` analysis docs (`docs/analysis/`) and the
  Phase 3 RBAC/role-based planning docs (`docs/tasks/016`–`020`) from the same uncommitted batch are
  deliberately left uncommitted for a later session, per the user's stated priority order.
- **Phase 3.0 #4 — 備份與恢復演練 (Medium), executed and closed — 2026-09-18, Claude Code
  (MODE: IMPLEMENT), branch `feature/frontend-ux-revamp`, merged into `advanced-v2` via PR #6
  (commit `8a97dfd`).** `scripts/mysql-backup-restore.ps1` (authored by Codex) had never actually been run
  end-to-end before this session — its own result doc's acceptance checkboxes were pre-checked from
  static review only. Running it for real against the live `esun-mysql` container surfaced and fixed
  four real defects: (1) MySQL SQL-identifier backticks misapplied to `mysqldump`/`mysql` shell
  command-line database-name arguments triggered POSIX `sh` command substitution
  (`esun_shop: command not found`); (2) `-p"$MYSQL_ROOT_PASSWORD"`'s stderr password warning was
  misread as a terminating error under Windows PowerShell 5.1's `2>&1` + `$ErrorActionPreference=
  'Stop'` combination — switched to the `MYSQL_PWD` env var; (3) `Invoke-MySql`'s inline
  `-e "<SQL>"` argument got corrupted by .NET's native-process argument re-quoting on Windows —
  switched to writing SQL to a temp file and `docker cp`-ing it in, matching the existing
  backup/restore file-transfer pattern; (4) the Drill's original full-dump SHA-256 comparison
  produced a false failure on a byte-correct restore, because MySQL's dictionary records whether a
  column's collation was "explicitly" resolved at `CREATE TABLE` time, and that bookkeeping differs
  between the original schema-init path and a restore-from-dump path even when the declared
  collation is identical — confirmed via line diff that all 42 differing lines were only this
  cosmetic annotation and all 8/8 `INSERT` statements were byte-identical. Redesigned the Drill's
  pass/fail check to a data-only dump hash (`--no-create-info`) plus a sorted table-name-list
  comparison; full structural dumps are still saved as evidence. After the fixes: `Backup` against
  real `esun_shop` succeeded (sha256 `41A1DDE0...35004`); `Restore`'s two safety guards (refuse
  without `-Force`; refuse overwriting the source without `-AllowSourceOverwrite`) both correctly
  rejected; a real restore into a scratch database (`esun_backup_test`) produced all 8 expected
  tables and was manually dropped; the full `Drill` action (backup → restore into temp DB → simulate
  corruption → restore again → verify content+table-list → cleanup) returned `DRILL_OK`, and
  post-drill checks confirmed the temporary database was gone, no leftover container temp files, and
  the source `shop_order` row count (12) was unchanged throughout. `backups/` (contains real
  member/order data) was added to `.gitignore` — it was previously untracked and not ignored.
  `docs/MANUAL_TESTING_GUIDE.md` gained a new "第九步：備份與恢復演練" section (the guide's intro
  already listed this as test method 4 but had no matching section). Full evidence, commands and
  exact hashes: `docs/tasks/024-phase30-backup-restore-result.md`. Scheduling/off-site retention
  remains explicitly out of scope for this round per the task's own acceptance checklist. This task's
  own exclusions say no commit/push/merge; changes are complete but left uncommitted pending the
  user's decision.
- **`feature/frontend-ux-revamp` regression + manual acceptance — 2026-09-17 22:28 Asia/Taipei,
  Claude Code (MODE: IMPLEMENT), commit 6ae0641, branched off the Phase 2.1 closure above.** This
  branch's four prior commits (795d864 sticky message banner, ec140f7 real field validation
  messages, e846a9a shopping UX overhaul — catalog cards/cart sidebar/toasts/chat widget, d09417d
  Ollama port-collision fix for a native Windows Ollama install) had no test evidence recorded
  before this entry. Full backend `mvn clean test`: **81/81, 0 failures/errors/skipped**, JaCoCo
  gate passing — matches the Phase 2.1 baseline, no regression. Frontend `npm test`:
  checkout.test.js 3/3 + `App.spec.js` **16/16** (up from the Phase 2 baseline's 9/9 — new cases
  added on this branch) + `npm run build` clean. Manual browser E2E against the user's own running
  dev servers (backend :8080, frontend :5173, confirmed to be this app via page title and a real
  `/api/products/available` response before reusing them, not a fresh instance) with mysql/redis/
  ollama containers already up: (1) cart sidebar — added 1x osii 舒壓按摩椅 + 2x 起司蛋糕, sidebar
  correctly totaled NT$100,400; (2) checkout — `POST /api/orders` returned 200, stock decremented
  exactly (P001 4→3, P002 44→42), cart cleared, and the success banner (order id
  Ms20260917222523056LJGDIH) stayed sticky at the top of the page; (3) field validation — submitting
  the empty "上架新商品" form showed four distinct per-field messages (請輸入商品編號/商品名稱/
  價格/庫存數量), not a generic error; (4) AI 客服 chat widget — asking "有哪些付款方式？" hit
  `POST /api/support/ask` (200) and returned a correctly grounded answer with FAQ/product sources,
  confirming the Ollama port fix works against this machine's native-Windows-Ollama-plus-Docker
  setup. No console errors, no failed network requests observed during the session. Not covered by
  this pass: automated component tests for the new cart-sidebar/toast/chat-widget UI itself (still
  relies on manual verification), mobile-width layout check, and re-running the Phase 1.5/2.5
  load/fault suites (out of scope for a UI-only branch).
- **Phase 2.1 closed — 2026-09-16 17:12 Asia/Taipei, Claude Code (MODE: IMPLEMENT).** Finished the
  already-in-progress merge of the LLM product/FAQ support chat (origin/claude/phase-2-1-ilfbgw,
  PR #3) in worktree `codex/phase21-acceptance`, fixed a double-HTML-escaping bug in
  `SupportService` (Vue's `{{ }}` already escapes, so the backend escaping double-encoded `&`/`"`
  in answers), added `SupportServiceTest`, and merged into `advanced-v2` (6bd4c13). Also fixed a
  project-wide MySQL charset bug found while acceptance-testing: `character_set_client` defaults
  to latin1 in the `mysql:8.0` image, double-encoding every Chinese seed string in
  `02_data.sql`/`04_faq.sql` on init (confirmed on both a fresh container and the long-running
  `esun-mysql` container that's been used since Phase 1.5 — no prior test ever caught it, since
  none assert Chinese string content). Fixed via
  `--character-set-client-handshake=FALSE` on `docker-compose.yml`'s mysql service and the
  Testcontainers fixture. Full backend (`mvn clean test`, merged tree): **81/81, 0
  failures/errors/skipped**; frontend `npm test`/`npm run build` clean. Real-Ollama acceptance
  (not just static review) against the actual `esun-ollama` Docker container with
  `llama3.1`+`nomic-embed-text` pulled: startup embedding-indexed 13/13 docs, `POST
  /api/support/ask` returned correctly-grounded answers with accurate sources, 400/503 validated
  (blank/over-length question, Ollama stopped mid-session), and — importantly — a question with
  no matching product/FAQ correctly returned "不知道" instead of a hallucinated answer, verified
  both via curl and in the browser UI against the Vite dev server. Existing
  register/login/products endpoints smoke-tested on the same instance, unaffected. Full
  detail/evidence: `docs/tasks/014-phase21-acceptance.md`. Not done in this session: recreating
  the shared `esun-mysql` container's existing (already-corrupted) data — the charset fix only
  takes effect on the next fresh container init.
- **Phase 2 documentation closure — 2026-09-16 14:42 Asia/Taipei, Codex (MODE: IMPLEMENT, documentation only).** Verified 87014ae and merge/fixup 26d9e2f/4eac5e8 are included in remote advanced-v2 at ae7363f. Merged-tree evidence remains 74/74 backend, 12/12 frontend and production build passing; this documentation round did not rerun tests. Remote [CI 35050689173](https://github.com/WhiteHot0321/esun-shopping-demo/actions/runs/35050689173) confirms backend tests, frontend tests/build and Docker build all succeeded at ae7363f; workflow makes Docker depend on both preceding jobs. Phase 2 is implemented, independently reviewed, verified, committed, merged and pushed. This synchronization entry is included in the Phase 2 documentation commit; the verified commit/push reference is recorded on the linked Notion pages.
- Phase 2 Notion status/acceptance cells and checklists were directly updated in [progress](https://app.notion.com/p/3c4708da9f9280d89606c93c3a6d3e53), [stage table/prompts](https://app.notion.com/p/3c2708da9f9280b083d3f000ff381579), [Phase 2](https://app.notion.com/p/3d7708da9f9281579bd9eadda365b4b9), [execution strategy](https://app.notion.com/p/3d7708da9f928173be92dfc94182db9f) and [execution plan](https://app.notion.com/p/3d4708da9f9281dc8b3ddb0423cbc0ad). Original requirements retained, including the documented user substitution of AuthPanel + ShopWorkspace for four components; historical evidence remains identified as historical. All five pages read back; a stale historical pending paragraph was corrected during that check and read back again. Phase 2.1/2.5/3 are outside this update.
- Documentation-session metrics: small task; one existing local Markdown changed, five related Notion pages updated; application/test changes 0; executable tests 0; application repair rounds 0; one documentation consistency correction pass; explicit user mode authorization 1 after review-only gate. Elapsed time/context/model cost and five-hour usage delta unknown. Engineering note: a clean branch build does not prove a merge is safe; use the merged commit's CI and keep the status ledger tied to that evidence.

- **Phase 2.5 final acceptance complete — Task 013, 2026-09-16 13:44**. Validation ran sequentially in an isolated detached ae7363f worktree; the new test and 79-entry source manifest match the main checkout. Final `mvn clean test`: **76/76, 0 failures/errors/skipped, exit 0**. Fresh JaCoCo line gates: OrderService 34/36 (94.44%), OrderTransactionService 52/52 (100%), StockCacheService 60/63 (95.24%), ProductService 18/18 (100%). No gate relaxation or production-code changes.
- **Current retained load/fault evidence**: ordinary B0/C3/R3 each three rounds, all nine 100% successful (7,511/7,511), exact DB order/stock deltas; R3 audits empty. Controlled HTTP attempts=1: 842/852, 10 true MySQL 1213 / 10 CONCURRENT_CONFLICT / 0 retries; attempts=3: 837/837, 10 true 1213 / 10 retries / 0 conflicts. Separate 20-caller real-deadlock negative control: 0/20 successful with 0 retries; full-suite attempts=3: 20/20 with exactly 20 retries and exact inventory.
- **Live outage closes the earlier drill's evidence limitation**: new RedisLiveOutageIntegrationTest pauses its own disposable Redis while 20 authenticated concurrent HTTP orders all succeed against real MySQL; checks exact orders/details/stock, replay, persistent DB-only latch after unpause and reconciliation, all-product recovery including zeros, and empty final audit. A fresh service instance models latch reset; literal JVM restart and multi-instance reconciliation are not claimed. Independent narrow Claude review PASS; original production repair review remains PASS.
- Task 013 supersedes older Phase 2.5 "HTTP pairing missing" / "Docker blocked" / "full coverage pending" statements below. Historical ordinary ~50% improvement was not reproduced; no Redis throughput improvement is claimed. Sustained sold-out performance studies and Phase 1.5's separate historical workload remain optional/outside this Phase 2.5 closure. Raw current JSON/XML/logs/manifests: `.git/phase25-final-ae7363f/`; detailed ledger: `docs/tasks/013-phase25-final-acceptance.md`.

- Phase 1: 11 historical fixes complete.
- **Phase 1.5: executed and sealed for this round on 2026-09-16** — see docs/tasks/008-phase15-result.md and bench/RESULTS.md "Current-version B0 baseline". Docker recovered (was blocked since Task 006). `mvn clean test` on the latest working tree: 72/72 passing, 0 failures/errors/skipped, four-service JaCoCo gate passing (OrderService/OrderTransactionService/ProductService 100%, StockCacheService 83.3%, all >=80%) — satisfies P15-3. P15-1 (B0, 40 VUs/45s, disposable DB, historical seeds) ran one round each for 2-item and 3-item workloads: zero MySQL 1213/1205 in either, exact stock/order/detail reconciliation, nine HTTP 500s each traced to the known sp_decrease_stock SQLSTATE 45000/1644 stock-race SIGNAL (not a deadlock; a pre-existing HTTP semantics gap, unchanged). P15-2 (B0, 100,000 stock/product, 40 VUs/45s, one round, no isolated warm-up) reached 1,985/1,985 new orders (100%), 43.2 orders/s, p95/p99 992ms/1.07s, zero deadlocks, no stockout. This is one round each, not the spec's three; repeating to three rounds and adding P15-2 warm-up isolation are documented, optional follow-ups, not required to consider this round's Phase 1.5 execution complete.
- Historical 9cd487c reruns already recorded zero observed deadlocks/timeouts in both 2/3-item workloads, with nine stock-race HTTP 500s each (bench/RESULTS.md); the 2026-09-16 current-version run above independently reproduces the same zero-deadlock, nine-500 pattern on the latest code (JWT + idempotent requestId + DB-conditional stock decrement included).
- 2026-09-16 baseline documentation: docs/tasks/007-phase15-baseline-acceptance.md separates B0 (Redis off, attempts=1), C3 (Redis off, attempts=3) and R3 (Redis on, attempts=3).
- **Phase 2.5: executed for this round on 2026-09-16 09:14-09:22 (concurrent Claude Code session, commit 6b1e690, on top of the Phase 1.5/login-fix commits above)**. Docker recovery confirmed independently in that session too. `RealDeadlockRetryIntegrationTest` attempts=1: 20/20 real InnoDB deadlocks correctly rejected with MySQL 1213, 0 retries, 0 successes, no orphaned `order_request` rows; attempts=3: 20/20 succeeded after exactly one retry each, post-run stock exactly as expected. `Phase25K6Acceptance` ordinary load, one round each on the current working tree: B0 (Redis off, attempts=1) 910/910 success; C3 (Redis off, attempts=3) 895/895 success, 0 retries; R3 (Redis on, attempts=3) 871/871 success, 0 retries, `cache.audit()` empty (no Redis/DB drift) — all well above the >=95% gate, though load never depletes the 10,000-unit stock, so this is not a sold-out/409 stress scenario. The four-class Redis fault suite (`OrderRetryTest`, `StockCacheServiceIntegrationTest`, `RedisOrderIntegrationTest`, `RedisUnavailableOrderIntegrationTest`) all passed now that Docker is available, resolving the 13 Docker-initialization errors recorded in Task 006. That session's own final `mvn clean test`: 72/72 passed, 0 failures/errors/skipped, JaCoCo gate: OrderService 94.4% (34/36), OrderTransactionService 100% (52/52), StockCacheService 95.2% (60/63), ProductService 100% (18/18) — all clear >=80%. This differs slightly from the Phase 1.5 session's own clean-suite run in the same window (100%/100%/100%/83.3%); both are legitimate single passing runs of the same gate, and the per-class percentage drift is consistent with ordinary run-to-run coverage variance on lightly-exercised branches, not a regression — the gate itself (>=80% on all four classes) passed both times.
- Not covered by this Phase 2.5 round: three-round-per-configuration repetition (**now closed, see next bullet**), explicit 1213/1205 bucketed counts under ordinary load, a stock-depleting stress/409 scenario, `Phase25K6DeadlockAcceptance` paired HTTP controlled-deadlock attempts=1/3, and the Redis outage/recovery/reconciliation procedure. Full detail in `bench/PHASE25.md`'s "Current-version run" section. This is a passing functional/regression run of every explicit runner command in `bench/PHASE25.md`, sufficient to close out Phase 2.5's fault/load/coverage acceptance for this round, not the exhaustive multi-round statistical ledger Task 007 describes.
- **Follow-up scoped 2026-09-16**: docs/tasks/010-phase25-followup-3round-outage.md defines two independent bounded sessions — (A) repeat the ordinary k6 B0/C3/R3 runner 3 times each with median/range reporting, and (B) a Redis outage/recovery drill against disposable Testcontainers only (never the user's compose Redis/MySQL), following the recovery procedure in `bench/PHASE25.md`. Per AGENTS.md's heavy-task split, these are separate sessions.
- **Task 010 sub-task A executed 2026-09-16 10:31-10:40, in two independent concurrent batches**: two Claude Code sessions ran the same 9-round (B0/C3/R3 x3) acceptance on the same checkout at the same time without coordinating — a real instance of AGENTS.md's "one writer per checkout" being violated; both batches are genuine and neither overwrites the other's evidence (docs/tasks/011-phase25-3round-result.md records both, reconciled). Across all 18 rounds: Maven exit 0 every round, success rate 100%/100%/100% (median, all three configs, both batches; range 100%-100% — no round differed) against the required >=95% gate for C3/R3, zero retries in every C3/R3 round, ending stock reconciled exactly to `10000-success` every round, R3's `cache.audit()` empty in every R3 round. Total request counts varied only by normal k6 timing jitter (678-874 across both batches combined). Full per-round tables in docs/tasks/011-phase25-3round-result.md; summary also in bench/PHASE25.md.
- **Task 010 sub-task B executed 2026-09-16, also a concurrent-session collision**: a second Claude Code session independently wrote `RedisOutageRecoveryDrillTest.java` on this same checkout at the same time as this session; two successive versions were live-edited and torn down during the overlap. The first (real Testcontainers `docker stop`/`start` on the Redis container) failed because this Windows/Docker Desktop host reassigns a new random host port on container restart — verified independently with a throwaway `docker run`/`stop`/`start`/`docker port` round trip — a host quirk, not an app bug. The second (a synthetic broken-Lettuce-client approach) initially asserted the first outage call would take >=400ms, but a locally *refused* port fails in ~35ms, not a timeout; this was corrected (by the other session, mid-diagnosis by this one) to prove the same claim by destroying the connection factory outright instead of timing it. Final result: `mvn -Dtest=RedisOutageRecoveryDrillTest test` 1/1 passed; full `mvn clean test` 75 tests (74 prior + this one), 0 failures/errors/skipped, coverage gate passed. Confirms: outage latches `degraded=true` (BYPASSED, no throw/hang), the latch survives both reconnection and a manual key-fix, the documented DB-snapshot recovery converges `cache.audit()` to empty, and only an actual process restart resumes real Redis reservations. Full detail and the collision writeup: docs/tasks/012-phase25-redis-outage-result.md. **Task 010 (both sub-tasks) is now closed.** Two independent-session collisions in this one follow-up task (the Task 010A result-doc clobber and this test file's live rewrite) suggest "one writer per checkout" needs an actual enforcement mechanism, not just after-the-fact reconciliation — flagged for the user, not resolved here.
- **2026-09-16 login/connectivity fix (commit c9601bf)**: Phase 2 login was failing end-to-end. Root cause: `backend/src/main/resources/application.yml`'s JDBC URL was missing `allowPublicKeyRetrieval=true`; under MySQL 8's default `caching_sha2_password` plugin with `useSSL=false`, Connector/J refused the very first HikariCP connection with `Public Key Retrieval is not allowed`, which failed every JdbcTemplate-backed endpoint (reproduced on `/api/products/available` too, not just `/api/auth/*`) — not a bug in `AuthService`/`AuthController` login logic itself. Fixed by adding `allowPublicKeyRetrieval=true` to the datasource URL. Verified end-to-end: register/login/wrong-password all return correct status codes and a valid JWT; `mvn clean test` (full suite, not narrow) still exits 0, matching the 72/72 + four-service coverage-gate baseline. See docs/tasks/009-login-db-connection-fix.md.
- Phase 2 test/CI foundations merged via PR #2 (3b2fb14); JWT foundation merged via PR #4 (020cd6f).
- **Phase 2 closed out 2026-09-16 (merge commit 26d9e2f + fixup 4eac5e8, both on advanced-v2 and pushed to origin)**: the codex/phase2-acceptance branch (a804d42, f64ffbd, plus implementer commit 87014ae) was merged into advanced-v2. The three items left open after a804d42 are done — (1) the static `App.vue` `submitAuth` defect (catch called an undefined `errorMessage` helper removed during the AuthPanel/ShopWorkspace extraction) is fixed, reading `error.response?.data?.message` inline, with double-submit guards added on the auth form; (2) component/UI regression coverage added via `App.spec.js` (Vitest + @vue/test-utils, 9/9 passing: login failure/retry, network-error fallback, empty-field validation, register+logout, busy-state during a pending login, auth-expired handling, and the pre-existing checkout retry/idempotency/409 paths re-verified with the auth layer in front of them), wired into `npm test` and CI; (3) independent JWT review completed and documented in `docs/tasks/007-phase2-review-result.md` (no blockers — JWT boundary, blank-subject rejection, BCrypt hashing, and email-enumeration resistance all verified against real code and executed tests, not just read). New `AuthIntegrationTest` (real MySQL) and a `JwtServiceTest` blank-subject case were added; `OrderRollbackIntegrationTest` was hardened to assert the specific SQLSTATE 45000/1644 signal and zero orphaned `order_request` rows.
  The merge itself (auto-resolved by git as non-conflicting) silently duplicated a `requestId` Map entry in `JwtAuthFilterTest` and a `JwtService` import in `OrderControllerTest`, because both branches had independently touched the same spot; this is exactly why "no conflict markers" was not treated as "safe to skip re-running the suite" — the merged-tree `mvn clean test` run caught it (3 errors), and it was fixed in 4eac5e8. Full regression on the final merged tree: backend `mvn clean test` 74 tests, 0 failures/errors/skipped; frontend `npm test` (checkout.test.js 3/3 + App.spec.js 9/9) and `npm run build` both clean.
  Phase 2.1/2.5/3 remain separate and are not implied complete by this closure.
- Current POM gates OrderService, OrderTransactionService, StockCacheService and ProductService at >=80% lines. **Resolved 2026-09-16**: Docker recovered; two independent full `mvn clean test` runs this session (Phase 1.5 session and Phase 2.5 session) both passed clean, 72/72, 0 failures/errors/skipped, with the coverage gate passing both times (see the two bullets above for the per-class numbers). This supersedes the 07:16 focused-invocation entry below (2 passed, 13 Docker initialization errors) and the "Docker Desktop failed starting its dockerInference socket" blocker, which no longer applies as of this session.
- Phase 2.1: **closed 2026-09-16, see the "Current acceptance status" entry above and
  docs/tasks/014-phase21-acceptance.md** — merged into advanced-v2 at 6bd4c13 with full Docker
  suite (81/81) and real Ollama acceptance both done.
- Phase 3.1/3.2: partial shared foundations (member table, checkout lifecycle, Dockerfile) exist; remaining feature acceptance is not complete.
- Notion: Phase 1.5 page, 進度追蹤 page and the 9/13 schedule page were synced for the Phase 1.5 results (commit 8bb95be) as of 09:20. **Resolved 09:35**: the Phase 2.5 acceptance run (6b1e690) and the login/JDBC fix (c9601bf) are now synced too — 進度追蹤 (new dated section) and the dedicated Phase 2.5 page (checklist items updated, gaps annotated: 3-round repetition, `Phase25K6DeadlockAcceptance` HTTP pairing, and the Redis outage/recovery drill remain unchecked and are recorded as optional follow-ups, not silently dropped). The 9/13 schedule page already carried a one-line summary of both from an earlier sync. All writes were read back and confirmed.
- Metrics: elapsed/token/cost unknown; one user request to reconcile the two concurrent sessions' statuses into one canonical block (this update); application repair rounds 0 this round (the JDBC fix was a genuine bug fix, tracked separately in docs/tasks/009); one static App.vue defect found (not an independent review).

## Phase 3 (final, engineering depth focus)

**Goal**: Close after establishing the engineering foundation, not by adding 10 more CRUD features. Priorities:

1. **Transaction/Deadlock/Idempotency deep-dive** — Verify concurrent inventory deduction is deadlock-free (fixed lock order by `productId`). Idempotency pattern for order retry (idempotency key in `order_request`). Root-cause any MySQL 1213 vs. application 409 boundary.
2. **Redis Lua script + Testcontainers** — Implement atomic stock reservation in Redis (Lua script: single-roundtrip `INCR` + comparison, not multi-step). Testcontainers for both MySQL and Redis in integration tests. Verify cache/DB reconciliation.
3. **k6 load tests** — Quantify performance under load (p95/p99 latency, throughput, error rate). Three standard workloads: normal purchase, deadlock stress, sold-out scenario. Baseline and regression tracking.
4. **CI/CD pipeline** — GitHub Actions: test suite → Docker image → registry. Status-badge-driven.

**Not Phase 3**: no new buyer/seller/maintainer CRUD features. The Phase 3.1 feature items below (order history, product review, cart persistence, address book, profile, password reset, seller product management) were exploratory work to establish buyer/seller foundations; they are **not the focus** going forward. Archive their branches, don't expand them.

## Historical Phase 3.1 feature work (archived, not active)

- **Order history view** — `docs/tasks/015-order-history-candidate.md`. Deferred in favor of Phase 3 engineering depth.
- **Role-based feature lists** — `docs/tasks/016–020` (seller/buyer/maintainer/RBAC/audit). Analyzed but not scheduled ahead of engineering work.

## Phase 3.0 #1–4 evidence reconciliation — 2026-09-27, Claude Code, MODE: REVIEW_ONLY

Documentation/evidence check only (per Task 046's "next bounded action" in the isolated `codex/review-audit-concurrency` worktree); no production/test code read or changed beyond `grep`/`git log` inspection of already-committed history. Baseline: this checkout's `advanced-v2` @ `32afd3d`.

- **#1 RBAC — capability is real and merged, but its cited acceptance evidence is orphaned.** `Member.Role`, the JWT `role` claim (`JwtService.java`), and controller-level role checks (`ProductController`, `OrderController`, `AuditLogController`, `CouponController`) all exist in the current tree. However, `git log` traces that code to commit `7c24669` (Phase 3.1 #9 product-review prerequisites), not to the `codex/phase30-rbac` branch that Notion's "Task 020" entry describes (86/86 tests, independent review PASS) — that branch no longer exists in `git branch -a` and is not an ancestor of `advanced-v2`. The only Task-020-named file in this working tree, `docs/tasks/020-rbac-endpoint-audit.md` (still untracked), is the earlier read-only gap inventory (0 role-gated endpoints), not that implementation's own record. **Do not keep citing the old branch's 86/86 run as evidence for the RBAC code actually running today** — if dedicated RBAC acceptance is wanted, it needs a fresh, narrowly-scoped verification against the current controllers.
- **#2 Order history — still an unreconciled, uncommitted worktree, not "done pending merge."** Re-inspected the `phase31-closure` worktree (baseline `3cfd934`): it is unchanged since 2026-09-24 (`OrderHistoryMigrationTest`, `OrderQueryServiceTest`, `OrderHistory.vue` etc. all still untracked). The overlap this project-state.md already flagged against the merged Phase 3.1 #11 order-status-flow feature has never actually been checked by anyone — nobody has determined how much of #2's scope #11 already covers. Status is genuinely open/undecided, not a completed feature waiting on a mechanical merge.
- **#3 Monitoring — matches its documentation; still not integrated.** Same worktree's `backend/src/main/java/com/esun/shop/monitoring/` and `ManagementEndpointsIntegrationTest` remain untracked there; `advanced-v2`'s `application.yml` has no actuator/management-port configuration. Existing "implemented and tested in an isolated worktree, not committed/merged" wording is accurate as-is — no correction needed here.
- **#4 Backup/restore — confirmed complete, no gap found.** Task 024's drill is merged (PR #6, `8a97dfd`) and was a real, executed drill (backup/restore/corrupt/restore cycle with SHA-256 data-hash comparison and both safety guards exercised), not a static review. Stands as PASS.

Next: user/Codex decision needed on whether to rebase the `phase31-closure` worktree's order-history delta onto `advanced-v2` @ `e289141`-or-later and de-duplicate against #11, or archive it as superseded; RBAC's evidence citation should be corrected wherever it's repeated (Notion 進度追蹤's "Phase 3.0 RBAC Task 020" entry) rather than treated as current proof.

## Historical records below

- Spring Boot 3.3.5 / Java target 17; Vue 3 / Vite.
- Recent commits include Axios consolidation, stored procedure wiring, Testcontainers tests and the FK shared-lock-upgrade deadlock fix.
- .claude/agents/test-engineer.md exists. Older CLAUDE.md phase and agent descriptions are historical and need reconciliation against code before scheduling work.
- Existing untracked .claude/skills/ belongs to the user and is preserved.
- Claude Code native executable is available at ~/.local/bin/claude.exe and authentication was confirmed. Do not record account identifiers or credentials.
- Pilot: docs/tasks/001-order-concurrency-audit.md. Scope is a static coverage audit; runtime test status must be recorded separately.

## Next

Phase 3 (engineering depth) execution order:
1. **Deadlock analysis & fix validation** — verify fixed lock order (productId) prevents 1213, quantify latency cost.
2. **Idempotency pattern integration** — order retry with idempotency key, test replay scenario.
3. **Redis Lua + Testcontainers** — atomic stock reserve in Lua, real-MySQL + real-Redis integration tests.
4. **k6 load/stress suite** — three workloads (normal, deadlock stress, sold-out), baseline metrics.
5. **GitHub Actions CI/CD** — test → Docker build → registry, branch protection, status badges.
6. **Observability** (post-Phase-3) — SLF4J structure + Prometheus/Micrometer, optional Jaeger tracing.
7. **Deployment** (post-Phase-3) — k8s manifest or cloud platform (GCP Cloud Run, AWS Lambda), health checks, graceful shutdown.
8. **SQL performance** (post-Phase-3) — EXPLAIN analysis, indexing strategy for hot queries, metrics.
9. **API documentation** (post-Phase-3) — Swagger/OpenAPI, auto-generated from Spring annotations.

## Pilot verification result
- Codex ran mvn test on 2026-09-14: 24 passed, 0 failed/errors/skipped, real MySQL Testcontainers, Java 21 host runtime.
- Initial Claude invocation failed due to expired OAuth. After reauthentication, retry c5cd2183-c41d-4977-8a6d-e20e7281e909 succeeded. Codex reviewed the report and recorded corrections. Read-only delegation is verified; implementation delegation remains untested.
- See docs/tasks/001-order-concurrency-result.md for Codex's coverage review and proposed rollback-test strengthening task.

## Collaboration documentation
- Notion: [AI 開發協作｜Codex × Claude](https://app.notion.com/p/3db708da9f92819dbd00e7dfee4f5ab6). Contains the proposed model/effort/mode policy; implementation dispatch remains pending.

## Collaboration policy update — 2026-09-14 20:51 Asia/Taipei
- Executor: Codex. Policy/docs updated on advanced-v2 at HEAD ac9a19f; this update is uncommitted.
- Small reversible changes: one agent with proportional checks. Critical changes: independent cross-agent review. Codex and Claude may exchange implementation/review roles.
- AGENTS.md defines routing; CLAUDE.md points to it. Task 002 now records risk, roles, independent review and trial metrics.
- Documentation-only update; application tests not run. No new implementation trial completed; the read-only launcher remains unchanged.
- Next: execute Task 002 through a scoped implementation workflow, then compare reliability, user interventions, repair rounds and available cost across three completed comparable trials.
