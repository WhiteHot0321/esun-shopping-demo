# Task 033 — Phase 3.1 #11 訂單狀態流程 [Seller + Buyer]

Date: 2026-09-24 · Agent: Claude Code · Branch: `feature/phase31-11-order-status-flow`

## Scope
Order lifecycle `CREATED → CONFIRMED → SHIPPED → DELIVERED`, with `CANCELLED` allowed only before shipping
(stock returned). Buyer: own order history, tracking timeline, self-cancel. Seller/Admin: scoped order list and
fulfilment transitions. Not in scope: notifications, refunds/returns, payment coupling (`pay_status` untouched).

## Design
- `shop_order.order_status VARCHAR(20)` (by name, unlike ordinal-persisted `PayStatus`) + append-only
  `order_status_history` (from/to, actor, actor_role, ms timestamp) that doubles as the buyer timeline and audit trail.
  `01_schema.sql` (fresh init) and repeatable `11_order_status.sql` (column/index guards via `information_schema`,
  backfills one `CREATED` history row per pre-existing order).
- Endpoints: buyer `GET /api/orders`, `GET /api/orders/{id}`, `POST /api/orders/{id}/cancel`;
  seller/admin `GET /api/seller/orders[/{id}]` (alias `/api/admin/orders`), `POST .../{id}/status {status}`.
- Authorization comes only from the verified JWT (email + role). Foreign/invisible orders are 404. A seller sees only
  orders containing their products and only their own lines/subtotal (buyer email hidden); because status is per order,
  a seller may transition only when **every** line is theirs (else 403); ADMIN is unrestricted.
  The server returns `allowedActions`; the UI never derives permissions.
- Concurrency: one transaction = `SELECT … FOR UPDATE` on the order → re-validate state machine on the locked row →
  compare-and-set `UPDATE … WHERE order_status = ?` → history row → (cancel) restore MySQL stock, products sorted in Java
  exactly like checkout (case-sensitive) → Redis `compensate` registered `afterCommit`.
- Frontend: `OrdersPanel.vue` (buyer/seller modes), topbar "我的訂單" / "訂單管理"; stale-response guard and page step-back.

## Verification (final tree)
- Backend `mvn clean test`: **145/145**, 0 failures/errors/skipped, JaCoCo gate PASS.
- New real-MySQL `OrderStatusIntegrationTest` 6/6: buyer isolation & filters/paging, full seller happy path with illegal
  transitions (409), cancel restores stock exactly once, 6-way concurrent cancel (exactly one 200, five 409, stock
  restored once), mixed-seller scoping + admin-only transition/cancel, migration repeatability + backfill (run twice).
  Plus `OrderStatusTest` (state machine).
- Frontend: checkout 3/3, Vitest **43/43** (8 new `OrdersPanel` cases), production build PASS (106 modules).
- Independent read-only audit: rules 1, 2, 3, 5, 6, 7 PASS; findings repaired: (a) lock-order mismatch between SQL
  collation ORDER BY and checkout's Java sort → sort in Java; (b) stale `load()` responses / empty page after cancel.
- Regressions found and fixed while integrating: existing tests that `DELETE FROM shop_order` needed
  `order_status_history` cleared first; `JwtAuthFilterTest` slice needed an `OrderStatusService` mock.

## Known limitations / follow-ups (not blocking)
- Multi-seller orders can only be advanced by ADMIN (status is per order, not per seller line item). Per-seller
  fulfilment would need a status on `order_detail`.
- Redis compensation on cancel does not know whether the original checkout went through Redis or the DB-only path;
  drift is reported by the existing `StockCacheService.audit()` but not auto-corrected.
- `LEFT JOIN shipping_address … FOR UPDATE` also locks the address row briefly.
- No notifications; buyer "confirm receipt" not implemented; live browser E2E not run.
