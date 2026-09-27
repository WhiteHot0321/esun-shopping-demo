# Task 034 — Phase 3.1 #12 操作稽核日誌 [Maintainer]

Date: 2026-09-24 · Agent: Claude Code · Branch: `feature/phase31-12-audit-log`
Source spec: `docs/tasks/018-maintainer-feature-list.md` §2.1 (P1, "建議在 RBAC 之後做，稽核才有角色可記").

## Scope
Answer "誰、什麼時候、以什麼角色、對哪筆資料、改了什麼" for privileged writes, and let a maintainer (ADMIN) query it.
Audited actions (`AuditAction`): `PRODUCT_CREATE`, `PRODUCT_UPDATE`, `PRODUCT_DELETE`, `PRODUCT_RESTOCK`,
`PRODUCT_IMAGE_UPLOAD`, `ORDER_STATUS_CHANGE`, `REVIEW_VISIBILITY_CHANGE`.
Not in scope: authentication events (login/register/password), buyer self-service content (own reviews, cart, address
book, profile), error tracking/alerting (§2.2), log export/retention, tamper-proofing beyond app-level append-only.

## Design
- Table `audit_log` (`actor`, `actor_role`, `action`, `target_type`, `target_id`, `before_state JSON`, `after_state JSON`,
  `created_at DATETIME(3)`), indexes on time / actor / target / action. `01_schema.sql` (fresh init, also drops the table)
  and repeatable `12_audit_log.sql` (`CREATE TABLE IF NOT EXISTS`). No FK on the target: it spans several tables and must
  stay traceable after the target is soft-deleted.
- **Explicit service-layer calls, not AOP.** The controller-level AOP option in the spec cannot see accurate
  before/after values or the transaction boundary; `AuditLogService.record(...)` is called inside the same transaction as
  the change, so the audit row commits or rolls back with it (proven by a real-MySQL test that makes the audit insert
  fail and shows the restock it describes did not survive).
- Actor identity comes from the verified JWT (email + role); role falls back to a member-table lookup only when the caller
  did not supply one (`UNKNOWN` if the member no longer exists). Snapshots are whitelisted business fields only —
  never password hashes or tokens.
- Accurate before/after under concurrency: single-product update/delete/restock take `SELECT … FOR UPDATE` before reading
  the "before" snapshot; bulk operations update first (which locks the rows), then read "after" and derive "before"
  (restock: quantity − amount; delete: not deleted). Bulk writes one row **per product** so history can be traced per target.
  Restock rows carry `restockAmount`.
- Order status changes are audited inside `OrderStatusService.applyTransition` (same transaction as the compare-and-set
  and `order_status_history`). Checkout (`OrderTransactionService`) is deliberately **not** audited: an order's creation
  is already recorded by its initial `order_status_history` row, and adding another write to the hot, deadlock-tuned
  checkout transaction was not worth the risk. Review moderation (hide/restore) is audited because it acts on someone
  else's content; buyers' own review edits are not.
- Read API: `GET /api/admin/audit-logs` (ADMIN only, else 403; no token 401) with filters `actor`, `action`, `targetType`,
  `targetId`, `from`, `to` (ISO local date-time) and paging (`size` ≤ 100), newest first. No write/update/delete endpoint
  exists and `AuditLogRepository` has no update/delete method.
- Frontend: `AuditLogPanel.vue` (ADMIN-only "稽核日誌" topbar button) with filters, paging, changed-fields-only diff, and a
  stale-response guard.

## Verification (final tree)
- Backend `mvn clean test`: **159/159**, 0 failures/errors/skipped, JaCoCo gate PASS (145 baseline + 14 new).
- New real-MySQL `AuditLogIntegrationTest` 9/9: migration run twice keeps rows; full product lifecycle
  (create/update/restock/image/delete) with exact before/after; bulk restock/delete one row per product and an all-or-nothing
  rejection writes nothing; rejected operations (foreign owner 403, bad amount 400, duplicate 409) leave no row; **audit
  insert failure rolls the business change back** (temporary CHECK constraint); order status changes (seller + buyer cancel,
  illegal transition not audited) and review hide/restore; ADMIN-only read (401/403/200), filters, paging, 400s, no mutation
  endpoints; 8 concurrent restocks keep a gapless before→after chain (0→5→…→40); timestamps are app-time.
  Plus `AuditLogServiceTest` 5/5 (role resolution, blank actor, validation, filter/offset pass-through).
- Frontend: checkout 3/3, Vitest **50/50** (6 new `AuditLogPanel` cases + 1 ADMIN-only entry-point case in `App.spec.js`),
  production build PASS.
- Regression found and fixed while integrating: my review-moderation test left `product_review` rows that broke the
  existing `DELETE FROM member` cleanup in `CartIntegrationTest` / `ShippingAddressIntegrationTest` → the test now cleans
  up after itself. Timezone finding: DB-default `CURRENT_TIMESTAMP` would read back skewed (JDBC `serverTimezone=Asia/Taipei`
  vs. a UTC MySQL server), so `audit_log.created_at` is written by the application.
- No independent read-only audit was run for this item (self-review only); `order_status_history` timestamps still use the
  DB default and share the timezone skew — a pre-existing issue outside this task.

## Known limitations / follow-ups (not blocking)
- Append-only is enforced at the application layer only; a DB user with UPDATE/DELETE on `audit_log` can still rewrite it.
  A stricter deployment would grant the app INSERT/SELECT only, or add triggers / ship logs to WORM storage.
- No retention/archival job; the table grows without bound.
- Not audited: login/password events, address/profile/cart edits, checkout creation (see Design), Redis compensation.
- HTTP 405 gap repaired on 2026-09-26 (Codex, MODE: IMPLEMENT, baseline advanced-v2 @ 176b190; uncommitted):
  dedicated `HttpRequestMethodNotSupportedException` handling returns 405 with Spring's Allow header and the API JSON envelope.
  Exact MockMvc regression and real audit endpoint integration verification both PASS; Docker blocker resolved (details below).
- Legacy `createProduct(request)` (no principal, creator `legacy`) records actor `legacy` with role `UNKNOWN`; it is not
  reachable from any mapped endpoint.
- No live browser E2E; the panel is covered by Vitest only.

## HTTP-METHOD-405 follow-up — 2026-09-26 19:56 Asia/Taipei

- Small direct fix, Codex implementation/self-check, no independent review required or claimed. Scope: GlobalExceptionHandler,
  GlobalExceptionHandlerTest, AuditLogIntegrationTest (3 code/test files) and existing progress records; no auth/CORS/DB changes.
- RED: `mvn '-Dtest=GlobalExceptionHandlerTest' '-Djacoco.skip=true' test`: 4 tests, 1 expected failure
  (expected 405, actual 500), exit 1, 6.727 s. Confirms the previous generic handler swallowed the method exception.
- After fix: `mvn '-Dtest=GlobalExceptionHandlerTest,AuditLogIntegrationTest,AdminProductControllerTest,OrderControllerTest,OrderControllerValidationTest' '-Djacoco.skip=true' test`:
  21 PASS (4 handler, 6 admin product, 6 order, 5 validation); 9 integration initialization errors, 0 assertion failures,
  exit 1, 11.543 s. New MockMvc cases verify 405/Allow GET/message, valid GET still 200, unexpected exception still 500 without internal details.
- Audit integration assertions require exact 405 + Allow GET for POST/DELETE on the existing collection and 404 on
  nonexistent item routes. Initial run could not execute them: Testcontainers reported no valid Docker environment;
  `docker info` confirmed missing `dockerDesktopLinuxEngine` pipe. This was not 9 proven business failures; the rerun below resolves it.
- `git diff --check` PASS. Full regression, coverage and frontend not rerun (bounded backend fix; narrow tests skip global JaCoCo).
  All pre-existing changes preserved; no commit/push. One implementation round, two test commands, no repair iterations.
- User-authorized integration rerun (2026-09-26, same advanced-v2 @ 176b190 working tree): Docker Engine 29.8.0 available.
  `mvn '-Dtest=AuditLogIntegrationTest' '-Djacoco.skip=true' test`: **9/9 PASS**, 0 failures/errors/skipped,
  exit 0 / BUILD SUCCESS, 37.911 s (test class 35.48 s). Exact 405/Allow and 404 assertions now executed and passed,
  alongside existing audit authorization, rollback, lifecycle and concurrent-restock cases. No source/test repairs in this rerun.
  Evidence: `backend/target/surefire-reports/com.esun.shop.integration.AuditLogIntegrationTest.txt`.
- HTTP-METHOD-405 bounded acceptance is complete: prior 21 non-container PASS + this 9-case integration PASS.
  These are separate executions, not a new full-suite run. No coverage, frontend or independent review claim; no commit/push.
  User interventions now include the explicit integration-rerun request. Rerun duration is recorded above; other unavailable metrics remain unknown.
- Engineering note: a known HTTP protocol exception should keep its status and headers, not be treated as an unexpected server error.
- User interventions: initial explicit implementation authorization, none mid-run. Total elapsed/context/token/cost/five-hour usage delta unknown.
