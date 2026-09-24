# Phase 3.1 #11 handoff

Goal: Order status flow — CREATED→CONFIRMED→SHIPPED→DELIVERED, CANCELLED before shipping (stock returned); buyer history/timeline/cancel and seller/admin fulfilment, JWT-scoped and race-safe.

Changed: `01_schema.sql`, `11_order_status.sql`; `OrderStatus`, `OrderStatusService`, `OrderRepository`, `OrderController`, `OrderTransactionService` (initial history row); DTOs `OrderView`/`OrderPageResponse`/`UpdateOrderStatusRequest`; frontend `OrdersPanel.vue` + `App.vue`; tests (integration, unit, Vitest) and cleanup fixes in three existing integration tests.

Validated: Backend 145/145 + JaCoCo PASS; real-MySQL `OrderStatusIntegrationTest` 6/6; Vitest 43/43, checkout 3/3, build PASS; independent read-only audit PASS after two repairs.

Not proven: live browser E2E; notifications; per-seller fulfilment of multi-seller orders (admin only).

Next: Phase 3.1 remaining items per docs/tasks/019 (audit log, API docs, etc.) or Phase 3 engineering-depth line.
