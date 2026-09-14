# Task 002: Deterministic partial-write rollback regression

Status: proposed; not dispatched or implemented.
Baseline for this policy update: advanced-v2 / ac9a19f (recheck before implementation).
Risk: critical transaction/rollback behavior; independent cross-agent review required.
Planned implementer: Claude Code. Planned reviewer: Codex; roles may be exchanged before dispatch with the reason recorded.
Review: derive expected rollback behavior and inspect code/tests before reading the implementer's explanation.
Result record: elapsed time, actual model/effort, test counts/skips, user interventions, repair rounds, valid review defects, available tokens/cost (unknown when unavailable), and why the test proves rollback.
This is the first planned full implementation trial, not a completed trial. Reassess routing after three completed comparable tasks.
Scope: backend/src/test/java/com/esun/shop/integration/OrderServiceIntegrationTest.java only; no production changes.

Problem: existing concurrent rollback coverage may reject before any write, and broad failure assertions can hide unrelated errors.

Add a real-MySQL integration test using duplicate product lines, each individually within initial stock but together exceeding it (for example stock 3, quantities 2 and 2). Existing DTO/schema and duplicate-line tests allow this request. The first line can deduct stock and persist a detail, while the second triggers the stored procedure's insufficient-stock SIGNAL.

Acceptance:
- Inspect the actual SQLException cause chain and assert the expected SQLSTATE 45000 and relevant insufficient-stock failure, rather than any DataAccessException.
- After failure, stock is restored to 3; no new order or details remain. Use a unique product/member and before/after assertions isolated from other tests.
- No test-level transaction may mask whether the service transaction rolls back.
- Run the relevant test and the full backend mvn test suite on real MySQL Testcontainers; report counts and skips.
- Preserve existing concurrency tests and production behavior; report unexpected defects for separate scope.
- Codex inspects diff and results before acceptance; no commit/push during pilot.
