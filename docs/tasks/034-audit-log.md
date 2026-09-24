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
- Unmapped HTTP methods (e.g. `DELETE /api/admin/audit-logs`) return 500 rather than 405 because
  `GlobalExceptionHandler.handleOther` swallows `HttpRequestMethodNotSupportedException` — a pre-existing gap, only
  asserted here as "not 2xx".
- Legacy `createProduct(request)` (no principal, creator `legacy`) records actor `legacy` with role `UNKNOWN`; it is not
  reachable from any mapped endpoint.
- No live browser E2E; the panel is covered by Vitest only.
