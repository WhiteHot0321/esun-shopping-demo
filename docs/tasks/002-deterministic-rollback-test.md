# Task 002: Deterministic partial-write rollback regression

Status: proposed; not dispatched or implemented.
Baseline: advanced-v2 / 9cd487c (recheck before implementation).
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
