# Task 041 — Docker 29 Testcontainers compatibility

- Date/executor: 2026-09-25 Asia/Taipei, Codex; MODE: IMPLEMENT explicitly authorized.
- Baseline: advanced-v2 @ 3509923; existing project-state edits and untracked files preserved.
- Scope: Small, test infrastructure only. Allowed change: backend/pom.xml and result documentation. No production/test-source changes, no commit/push.
- Acceptance: real MySQL targeted test succeeds, then one clean full backend suite with unchanged JaCoCo gates.

## Cause and change

Boot 3.3.5 inherited Testcontainers 1.19.8 / docker-java 3.3.6. On this Docker Desktop 4.92.0 / Engine 29.8.0 host, the named-pipe provider received HTTP 400 during discovery. Docker CLI availability did not establish Java-client compatibility; the earlier claim that Docker was simply stopped was unsupported. Setting DOCKER_API_VERSION=1.40 did not resolve it.

Override Boot's testcontainers.version property to 1.21.4. All Testcontainers modules now resolve to 1.21.4 and docker-java to 3.4.2 (confirmed from actual Surefire classpath). The official 1.21.4 release explicitly addresses recent Docker Engine changes: https://github.com/testcontainers/testcontainers-java/releases/tag/1.21.4 . Same host and named pipe now initialize successfully without API-version overrides or persistent machine configuration changes.

## Verification

- Before: repeated full runs reported 248 tests, 113 initialization errors, no assertion failures. This was not evidence of 113 separate business defects.
- Targeted: `mvn '-Dtest=OrderServiceIntegrationTest' '-Djacoco.skip=true' test`: 4/4, no failures/errors/skips, BUILD SUCCESS, 50.334 s. Coverage skipped only for this narrow smoke test.
- Full: `mvn clean test`: **246/246**, 0 failures/errors/skips, BUILD SUCCESS, all JaCoCo checks met; 3 min 15 s. Includes OpenApiDocsTest 5/5 and OpenApiDocsDisabledTest 1/1, real MySQL/Redis, deadlock retry, payment/cancellation, coupon and outage tests. No API-version override. Earlier 248-count failed reports included initialization-error entries; test sources were not removed or edited.
- `git diff --check`: PASS after POM edit.
- Observed limitation: local Ollama lacks nomic-embed-text; fallback warnings occurred. Live RAG quality is not verified by these backend tests.

## Boundaries and measurement

No new business behavior or assertions; no independent reviewer required for this small dependency-only fix. Existing tests exercise actual MySQL and Redis. Full-suite success does not constitute a whole-codebase static audit, browser E2E or live payment-provider acceptance.

One dependency-change attempt; two executed verification commands. User supplied explicit implementation authorization once after the review-only gate. Model: GPT-6; exact reasoning/context/token/cost and five-hour usage delta unknown. Implementation and test phase approximately 6 minutes (19:50–19:56 Asia/Taipei), documentation synchronization additional. No independent review defects reported.

Fresh JaCoCo line evidence: ProductService 152/154, OrderService 34/36, OrderTransactionService 72/72, StockCacheService 60/63; all configured gates pass.

Notion synchronized and read back: progress tracking, historical prompt phase-work table (acceptance and status cells), execution strategy, Phase 3 task index #17 row, #17 API docs page, and Phase 3.2 page. One stale-table update was rejected; re-fetched current cells and preserved concurrent wording before the successful targeted update. Full Phase completion is not claimed. Local application change remains uncommitted.

Engineering note: a Docker CLI connection and a Java Docker-client connection can behave differently; align test dependencies through one BOM property and validate the real container lifecycle.
