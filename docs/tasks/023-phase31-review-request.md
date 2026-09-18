# CLAUDE INDEPENDENT REVIEW REQUEST

MODE: REVIEW_ONLY

TASK_ID: 023-phase31-order-query-correction
BASELINE: Tasks 017-022 plus this task's changes, current uncommitted state
WORKTREE: C:\GitHub\esun-shopping-phase31
ALLOWED_FILES: OrderController.java, OrderService.java, OrderRepository.java, OrderDetailResponse.java,
OrderPageResponse.java, OrderSummaryResponse.java, OrderQueryControllerTest.java, OrderQueryServiceTest.java,
OrderHistory.vue, OrderHistory.spec.js, DB/01_schema.sql, DB/07_order_query_index.sql,
JwtAuthFilter.java (comment only), RedisLiveOutageIntegrationTest.java (constructor call-site fix only)
CHANGE_LIMIT: 0
TEST_COMMAND: none; inspect supplied evidence
EXCLUSIONS: no edits, no B1/B2 payment/product-admin re-review, no live sandbox call, no populated-DB migration
execution, no commit/push/merge

## What this session did

Codex had already written the owner-only order list/detail feature (controller, service, repository,
DTOs, frontend `OrderHistory.vue`, and ownership unit/controller tests) but left three things
unfinished:

1. **Compile break**: `OrderService`'s constructor gained an `OrderRepository` parameter, but
   `RedisLiveOutageIntegrationTest.java:130` still called the old 2-arg
   `(OrderTransactionService, StockCacheService)` overload, which no longer exists as a 2-arg
   signature matching those types — this broke `mvn test` for the entire backend module (nothing
   could run, not just this one test). Fixed by autowiring `OrderRepository orders` in that test
   class and passing it through.
2. **Missing index**: `shop_order` had no index supporting
   `WHERE member_id = ? ORDER BY created_at DESC` (the exact query `OrderRepository.findByMemberId`
   issues), so `GET /api/orders` would degrade to a full table scan as data grows. Added
   `idx_shop_order_member_created (member_id, created_at)` to `DB/01_schema.sql` (fresh installs)
   and `DB/07_order_query_index.sql` (idempotent `ADD INDEX IF NOT EXISTS` for the already-running
   populated DB, following the existing `06_product_ownership.sql` pattern; not executed against
   the live container — that remains Task 022's separate "populated-DB migration" item).
3. **Missing required test**: Task 023's REQUIRED_TEST calls for "one focused frontend Vitest
   command covering list/detail/error behavior" for the order-history component, but no such file
   existed (`OrderHistory.vue` was wired into `App.vue` untested). Added
   `frontend/src/components/OrderHistory.spec.js` (6 tests: unauthenticated no-op, list+detail
   happy path with the exact `/orders?page=...` querystring asserted, empty state, list-load
   error message, detail-load 403 error message, payStatus filter resets to page 0 and requeries).

Also corrected a stale JavaDoc comment on `JwtAuthFilter` that only listed the POST endpoints as
protected and didn't mention the new GET order endpoints (the actual `isPublic()` allowlist logic
was already correct — GET `/api/orders` and `/api/orders/{id}` were never reachable without a
valid JWT; this was a documentation-only fix, not a behavior change).

No other files were touched. Task 023's own OUT_OF_SCOPE (ECPay, product management, Redis outage
repair, populated-migration execution, Notion, commit/push/merge) was respected.

## Evidence

- Focused backend (`OrderQueryServiceTest`, `OrderQueryControllerTest`, `OrderServiceTest`,
  `OrderControllerTest`, `OrderControllerValidationTest`): **29/29, 0 failures/errors/skipped.**
- Full backend `mvn test` (jacoco skipped, all 102 tests including integration):
  **101/102, 1 failure** — `RedisLiveOutageIntegrationTest.twentyHttpOrdersSurviveLivePauseAndRecoverFromDatabaseSnapshot`
  failed one in-flight HTTP order with `DB_ERROR`/500 during the live Redis pause window. This is
  the same pre-existing flake Task 022's final acceptance already recorded (1 failure out of 96 at
  that point); "Redis outage repair" is explicitly out of Task 023's scope, so it was left
  untouched rather than papered over.
- Frontend focused (`OrderHistory.spec.js`): **6/6.**
- Frontend full `vitest run`: **20/20** (11 `App.spec.js` + 3 `ProductManagement.spec.js` + 6
  `OrderHistory.spec.js`), consistent with the 17/17 Vitest count Task 022 recorded before this
  file existed (14 + the 3-test `checkout.test.js` run separately via `node --test`).

## Review focus

Please verify, from the code itself (not just this summary):

1. `OrderService.getOrders`/`getOrderDetail` derive the owner exclusively from the JWT-authenticated
   `memberId` parameter passed in from `OrderController`'s `authenticatedEmail` request attribute —
   never from a client-suppliable field — and that another member's order really produces 403 while
   a missing order produces 404 (see `OrderQueryServiceTest.detailRejectsAnotherBuyerAndDistinguishesMissingOrder`).
2. `OrderRepository`'s new SQL (`findByMemberId`, `countByMemberId`, `findOrderById`,
   `findDetailsByOrderId`) is parameterized (no string-concatenated user input) and that the
   optional `payStatus` branch doesn't change parameter order between the count and select queries.
3. The `RedisLiveOutageIntegrationTest.java` constructor fix is a pure call-site correction (same
   real `OrderRepository` bean now passed where a `null` gap would otherwise exist) and doesn't
   weaken what that test asserts.
4. `OrderHistory.vue`/`OrderHistory.spec.js` genuinely exercise loading/empty/error states rather
   than only the happy path, and that pagination/filter requests never send a member identifier
   the browser could tamper with.

End with exactly PASS, FAIL, BLOCKED, or NEEDS_ARCH_DECISION and report only concrete blocker
defects with file/line and trigger.
