# MON-05 integration acceptance and correction

## Session gate

- Mode: IMPLEMENT, following the MON-05 REVIEW_ONLY failure.
- Class: critical test-infrastructure correction (real MySQL, concurrency, transaction rollback).
- Baseline: `advanced-v2` @ `69517fa`; all pre-existing MON-01–04 and user changes must be preserved.
- Outcome: remove integration-suite resource/fixture interference without changing production behavior, then repeat MON-05 acceptance.
- Allowed code: shared MySQL integration fixture and directly failing audit concurrency test.
- Change limit: two test files plus this evidence file.
- Verification: one focused Maven command, then one clean full backend suite after independent review.
- Exclusions: production/config semantics, order/payment/stock policy, monitoring behavior, frontend, real ECPay, deploy, commit, push, merge.
- Implementer: Codex. Reviewer: Claude Code read-only audit.
- Usage at correction start: five-hour 70% used; weekly 42% used. Model/cost allocation unavailable.

## Failed acceptance evidence

The first MON-05 `mvn clean test` exited 1 after 4:43: 295 tests, 2 failures, 9 errors, 0 skipped. The first failure was `RealDeadlockRetryIntegrationTest` timing out before an opponent obtained/locked its row. Later contexts reported MySQL `Too many connections`, cascading into recommendation and Redis integration failures. `ProductReviewAuditConcurrencyIntegrationTest` independently failed while adding a global check constraint because earlier valid `REVIEW_VISIBILITY_CHANGE` rows already existed in the shared database. JaCoCo did not complete because Surefire failed.

## Correction

- The shared Testcontainers MySQL now permits 500 connections. The suite deliberately caches multiple distinct Spring contexts/Hikari pools, including a 30-connection real-deadlock context; MySQL's default 151 connections was below the aggregate test topology.
- The audit fault-injection constraint now rejects only the current review target. It still forces the intended audit insert to fail, while allowing valid history from earlier tests in the shared database.
- Production source and transaction behavior are unchanged.

## Result

- Focused verification: `mvn '-Dtest=RealDeadlockRetryIntegrationTest,ProductReviewAuditConcurrencyIntegrationTest,RecommendationIntegrationTest,RedisOrderIntegrationTest,RedisUnavailableOrderIntegrationTest,ExternalDependenciesHealthIntegrationTest' '-Djacoco.skip=true' test` — exit 0, 18/18 passed, 0 failures/errors/skips, 1:38. JaCoCo was deliberately skipped for the focused run.
- Independent correction review: Claude Code read-only audit **PASS**, with no blockers or valid defects. It confirmed that the connection-ceiling change is test-only, the target-scoped check constraint still forces the intended audit failure, assertions were not weakened, and production behavior was not changed.
- Final acceptance: `mvn clean test` — exit 0, **295/295 passed**, 0 failures, 0 errors, 0 skipped, 3:47. JaCoCo analyzed 156 classes and reported **All coverage checks have been met**; Maven ended with `BUILD SUCCESS` at 2026-09-28 09:33:39 Asia/Taipei.
- Outcome: **PASS**. Failure-matrix evidence, targeted correction verification, independent review, full regression, and coverage gate are complete. No real ECPay inbound traffic or deployment was claimed.

## Session accounting

- Repair rounds: 1. User interventions: 1 (`MODE: IMPLEMENT`). Valid independent-review defects: 0.
- Task class: critical test-infrastructure correction. Model/reasoning and context-size metrics: unavailable. Approximate Maven test rounds: 2 in IMPLEMENT mode (focused + final full gate); the preceding REVIEW_ONLY acceptance run is recorded under failed evidence.
- Five-hour usage at correction start: 70% used; weekly: 42% used. End usage/cost evidence: unavailable (unknown, not zero).
- Repository remains uncommitted; no push, merge, or deploy was performed.

## Engineering concept note

A shared Testcontainers database can hit its server-wide connection ceiling even when no individual pool leaks: cached Spring contexts retain several legitimate Hikari pools whose aggregate maximum exceeds the database default. Likewise, fault-injection fixtures must target the current test row so their validity does not depend on historical rows left by earlier contexts.
