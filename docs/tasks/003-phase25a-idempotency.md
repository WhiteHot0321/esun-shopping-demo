# Task 003 — Phase 2.5 A: order idempotency

Date: 2026-09-14 Asia/Taipei. Baseline: advanced-v2 ac9a19fd16b36e558a9646c948ff8497bfc3f2de.
Risk: critical order/transaction/concurrency. Implementer: Claude Code Sonnet, high effort, acceptEdits with scoped tools. Independent reviewer: Codex, inspect requirements/code/tests before author report.
Source: https://app.notion.com/p/3d8708da9f9281bda389d0599e1499e7

Implement ONLY Prompt A this session. No Redis, retry, commit/push/merge. One writer: Claude during implementation; Codex reviews afterwards. Existing dirty docs, bench README/RESULTS and untracked skills/results must be preserved. Do not modify AGENTS.md, CLAUDE.md, existing docs, scripts, or unrelated product functionality. Codex handles Notion/status.

Requirements:
- POST /api/orders accepts required UUID requestId (@NotBlank plus canonical UUID validation); same requestId returns original orderId with HTTP 200 and no repeated inventory deduction.
- Add order_request (request_id VARCHAR(64) PK, order_id VARCHAR(32), member_id VARCHAR(20), created_at DATETIME) to backend/DB/01_schema.sql and provide additive standalone migration for existing databases (never run destructive init against existing data).
- First database write in createOrder is request claim insert, same service transaction as order, details, stock. Catch DuplicateKeyException only around this insert; read committed original order ID safely with MySQL REPEATABLE READ; do not expose another member's order for reused key (reject member mismatch 409). No process-local lock/cache. Failed transaction removes claim, allowing same key to retry.
- GlobalExceptionHandler explicitly handles non-idempotency DuplicateKeyException with 409, not generic DB 500. Preserve other status behavior.
- Frontend generates key once for checkout, keeps key and exact payload on network/HTTP failure retries. Prevent in-flight double submit. Clear/rotate after confirmed success/new checkout; do not silently reuse key for edited payload. Handle ambiguous timeout safely without letting changed cart bypass unresolved checkout. Keep minimal UX. Extract small testable checkout helper if helpful.
- Update existing tests/request fixtures and bench JS request generation (only JS, not existing benchmark results). README API example and migration instructions may be updated.
- Real MySQL integration: >=20 simultaneous callers same key => one order/claim/detail set, once-only stock, all return same ID. >=20 distinct keys => all orders with adequate stock. Bounded waits/future timeouts and executor cleanup.
- Deterministic post-write rollback: stock 3, duplicate product lines buy 2 each; second stored-procedure decrement fails SQLSTATE 45000. Verify stock restored, no order/detail/claim; same request key can subsequently succeed after valid retry. Test must not have test-managed transaction that masks service rollback.
- Add HTTP tests missing/blank/malformed UUID =>400, duplicate replay=>200 original ID, other unique collision=>409; test member mismatch does not disclose existing order. Unit tests as relevant. Avoid broad refactor.

Allowed edit scope: backend/DB (schema plus additive migration), backend/src/main/java/com/esun/shop (order DTO/service/repository/exception), backend/src/test, frontend/src, optional frontend/package.json for focused tests, bench/*.js, README.md. Do not run shell or other agents; use Read/Glob/Grep/Edit/Write only. Codex runs Maven/frontend checks after review. Return concise report of files, decisions, unexecuted tests and risks; do not claim tests ran.

Acceptance: independent diff review; full mvn test with real Docker MySQL (no skipped); frontend build and focused checkout lifecycle verification; git diff --check. Maximum two repair rounds. Record elapsed time, user interventions, valid defects and token/cost metadata in task result; unknown if unavailable.

## Resume and routing — 2026-09-15 06:32 Asia/Taipei
- Previous Claude launch ended with no live process, no saved report and no implementation diff; outcome unknown, no claimed implementation.
- User supplied replacement routing policy: plan/decompose/route; Luna for bounded routine verification, Terra for service/transaction implementation; Claude independent review retained from explicit collaboration request.
- Current task graph: A implementation (Terra) -> independent Claude review + Luna verification -> repair if needed -> final evidence/Notion sync. C retry depends on accepted A; B Redis depends on accepted A and C. This task is the first bounded Phase 2.5 deliverable.
- Actual working repository: C:/GitHub/esun-shopping. Existing uncommitted files preserved. No automatic commit/push/merge.
- Docker initially unavailable; coordinator started Docker Desktop for real MySQL tests.

## Environment recovery — 2026-09-15 06:34 Asia/Taipei
Docker 28.4.0 responds after preserving its stale runtime socket directory as C:/Users/User/AppData/Local/Docker/run.stale-20260915-0634 and restarting Docker Desktop. No database volume reset. Startup log identified inaccessible dockerInference socket. Real MySQL Testcontainers can now be used.
