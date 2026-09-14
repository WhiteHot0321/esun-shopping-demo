# Task 001 — Read-only Claude pilot verified

Date: 2026-09-14; baseline: advanced-v2 / 9cd487c.

## Initial delegation attempt (historical)
The wrapper launched the native Claude Code executable successfully, but the API returned: OAuth session expired and could not be refreshed. No Claude audit was produced. This report is Codex's independent review, not Claude output. Reauthenticate with claude auth login, then rerun the task through scripts/invoke-claude.ps1. Do not classify the end-to-end workflow as verified yet.

## Executed verification
From backend/: mvn test, using installed Java 21 and real MySQL 8.0 Testcontainers on Docker Desktop.
Result: BUILD SUCCESS; 24 tests, 0 failures, 0 errors, 0 skipped. Includes 11 integration tests and 13 unit tests. This validates the tested scenarios on this run, not every possible concurrency schedule or Java 17 runtime compatibility.

## Coverage and remaining gaps
- Shared inventory: HighConcurrencyOrderIntegrationTest.java:236 runs 16 competing orders on five shared products, checks successful/rejected counts, zero remaining inventory and persisted totals.
- Opposite basket order: OrderConcurrencyIntegrationTest.java:82 exercises two reversed baskets and verifies remaining quantities.
- Last-unit rollback: OrderConcurrencyIntegrationTest.java:133 checks one winner, one loser, restored ample stock and order/detail counts. However, any ExecutionException counts as a valid loser. A pre-check rejection is also allowed, so the test does not deterministically prove execution reached a partial write before rollback.
- OrderServiceIntegrationTest.java:93 names stored-procedure SIGNAL, but expects BusinessException from the earlier service stock check. This does not prove the SIGNAL rollback path.
- HighConcurrencyOrderIntegrationTest.java:92 accepts all DataAccessException types (deadlocks are separately excluded); unexpected database errors can still be classified as expected rejections.

These are test-evidence gaps, not demonstrated production defects.

## Proposed next bounded task
Strengthen rollback evidence without production changes: deterministically trigger insufficient stock after an earlier line has been deducted, assert the specific expected rejection, unchanged stock on all affected rows, and no persisted order/details for the failed request. Consider duplicate product lines whose individual quantities pass pre-check but combined quantity exceeds inventory, after checking the request/schema semantics. Preserve existing concurrency scenarios and require the full backend suite to pass.

## Files and scope
Added coordination instructions, shared state, task specification and a read-only Claude launcher. CLAUDE.md received a pointer to current state. No application source or existing tests were edited; no commit, push or merge. Existing untracked .claude/skills/ preserved.

## Successful Claude retry and Codex review
After user reauthentication, run c5cd2183-c41d-4977-8a6d-e20e7281e909 completed successfully (is_error=false, 21 turns, no permission denials). Raw report is preserved in 001-claude-audit.md. The read-only dispatch/return/review loop is operational. Implementation delegation and timeout handling have not been exercised successfully by this pilot.

Codex acceptance corrections:
- The coverage table overstates deterministic rollback coverage. OrderConcurrencyIntegrationTest permits a pre-check failure, so it cannot guarantee partial writes occurred before rejection. Unit mocks likewise cannot demonstrate database rollback.
- Some citations omit backend/ or the Java package path. Resolve them under backend/src/main/java/com/esun/shop/service, backend/src/test/java/com/esun/shop/service, and backend/src/test/java/com/esun/shop/integration. The source checks support the gaps; the raw citation formatting is imperfect.
- Existing real-DB duplicate-product query-count coverage exists in OrderServiceQueryCountIntegrationTest.java:150. What is missing is specifically duplicate lines whose combined quantity exceeds stock.
- T1 is accepted as the next proposed task. T2 must not force every race loser to reach SIGNAL: legitimate scheduling can trigger the service pre-check. Validate either expected rejection precisely, and prove the partial-write/SIGNAL route with a separate deterministic test.
- The k6 figures are historical claims from existing bench/RESULTS.md, not measurements rerun by Codex or Claude in this retry.

No application code changed in this retry; the prior 24-test execution remains the runtime evidence and was not rerun for report-only changes. Pre-existing bench/README.md and bench/RESULTS.md edits were preserved.
