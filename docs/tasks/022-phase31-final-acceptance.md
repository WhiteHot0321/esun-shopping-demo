# Task 022 — Phase 3.1 最終驗收

## Session gate

- Baseline commit: `ec140f728fe6e7e5cf4e87a7e065a4ba4bd83287`（含 Tasks 017–021 未提交變更）
- Task class: heavy / independent final validation
- Goal: 逐項證明 Phase 3.1-B1、B2 與 C 驗收，辨識任何阻斷 Phase 關閉的缺口。
- Affected scope: Phase 3.1 payment、product ownership/admin、frontend integration、DB migrations，以及規格明列的 Phase 3.0 order-query regression prerequisite。
- Review limit: 先讀本文件、Tasks 018–021 結果與必要的 endpoint/migration inventory；0 production/test changes；一個完整 backend command、一個完整 frontend command（test + build 視為同一 frontend validation group）。
- Explicit exclusions: 自動修復、commit/push/merge、真實扣款、使用或記錄未經提供的密鑰、無關 Phase 3.2 功能。

## Requirement-to-evidence matrix

| Requirement | Required evidence | Status |
|---|---|---|
| B1 server-owned PENDING and JWT order identity | code + backend tests | proven by Tasks 017–019 and current payment/order tests |
| ECPay payment form uses DB order amount/owner | code + tests | proven by Task 018 review and current tests |
| Authenticated callback signature and idempotent atomic PAID transition | code + tests | proven by Task 018 review and current tests |
| Frontend payment POST redirect and non-authoritative return UI | frontend tests/build | PASS in current full frontend run |
| B2 owner-only create/update/delete/restock | code + backend tests | proven by Tasks 020–021 and current tests |
| Soft delete retains DB row and filters public/order paths | migration + integration/runtime evidence | code/review proven; populated-DB runtime evidence missing |
| ProductManagement validation and CRUD/restock UX | frontend tests/build | PASS in current full frontend run |
| Existing populated DB migration succeeds | migration execution against populated MySQL | missing |
| Phase 3.0 order query still works | endpoint inventory + regression tests | FAIL: GET order-query API/tests absent |
| ECPay sandbox end-to-end payment | test credentials, reachable callback, DB verification | BLOCKED: no `ECPAY_*` environment configuration |
| Full regressions | `mvn clean test`; `npm test`; `npm run build` | backend FAIL 95/96; frontend PASS 17/17 + build |

## Exit rules

- PASS only if every required row is proven.
- Missing prerequisite, failed test, or absent external acceptance evidence is FAIL/BLOCKED and must produce one bounded correction or external-action task; no repair occurs in this review session.

## Result (first pass, 2026-09-17)

- Status: **FAIL**
- Reviewer-created application changes: 0
- Backend command: `mvn clean test` — exit 1; 96 tests, 1 failure, 0 errors/skips. `RedisLiveOutageIntegrationTest.twentyHttpOrdersSurviveLivePauseAndRecoverFromDatabaseSnapshot` expected 200 but one request returned 500 `DB_ERROR` at `place:141`. Review rules prohibit rerun/repair in this session.
- Frontend validation group: `npm test -- --run` then `npm run build` — checkout 3/3, Vitest 14/14, build exit 0.
- Inventory evidence: no GET `/api/orders` list/detail mappings or repository query methods were found; only create and payment-form routes exist. Docker MySQL/Redis were running. No configured `ECPAY_*` environment variables were present.
- Outcome: Phase 3.1 cannot close until the order-query prerequisite is implemented/verified, populated migration is executed, backend full regression is green, and live sandbox evidence is obtained or the product owner explicitly changes that acceptance requirement.
- Elapsed time / usage / cost: `unknown`.

## Result (final rerun, 2026-09-18, Claude Code)

- Status: **PASS** — every row in the requirement-to-evidence matrix above is now proven.
- Corrections made since the first pass (Task 023 plus follow-on fixes, all independently reviewed PASS; see `docs/project-state.md` for full detail):
  - Task 023: implemented owner-only `GET /api/orders` / `GET /api/orders/{id}` (controller, service, repository, DTOs) and `OrderHistory.vue` + its Vitest coverage.
  - Root-caused and fixed the `RedisLiveOutageIntegrationTest` `DB_ERROR` flake: `shop_order`/`order_request.member_id` was `VARCHAR(20)`, too narrow for the JWT-email identity now stored there — a real production defect, not a test artifact. Widened to `VARCHAR(255)`.
  - Executed the populated-DB migration (`05_payment_transaction.sql`, `06_product_ownership.sql`, `07_order_query_index.sql`, `08_widen_member_id.sql`) against the live `esun-mysql` container; discovered and fixed a latent bug in `06`/`07` (MariaDB-only `ADD COLUMN/INDEX IF NOT EXISTS` syntax that real MySQL 8.0.46 rejects) in the process.
  - Obtained ECPay's officially-published Stage/sandbox test credentials for the correct product (全方位金流/AioCheckOut, MerchantID 3002607 — not the ECTicket product's different test ID initially found by mistake), with explicit user approval before use. Verified the checkout leg live against ECPay's real Stage server (accepted our CheckMacValue, rendered the real payment page) and the callback leg via a directly-computed, correctly-signed request to `/api/payments/ecpay/callback` (`1|OK`, PENDING→PAID, idempotent replay, forged-signature rejection all confirmed).
  - This rerun's own finding: the first `mvn clean test` attempt had all 102 tests pass, but the JaCoCo gate failed on `ProductService` (70% line coverage, below the 80% floor), a real regression in test coverage from Tasks 020-021's ownership/admin work landing without proportional tests. Added 7 tests covering `getOwnedProducts`, `getOwnedProduct`, `requireOwnedActive`'s NOT_FOUND branches (missing and soft-deleted), the success and 409-conflict branches of `updateOwnedProduct`/`deleteOwnedProduct`, and the deprecated legacy `createProduct(request)` overload.
- Backend command: `mvn clean test` — **exit 0; 108 tests, 0 failures/errors/skipped; JaCoCo coverage gate PASS on all four gated classes** (`OrderService`, `OrderTransactionService`, `StockCacheService`, `ProductService`). Run against the live `esun-mysql`/Redis Testcontainers infrastructure after a mid-session Docker Desktop crash-and-recover (environment hiccup, not a code issue — resolved by restarting Docker Desktop and the `esun-mysql` container, then rerunning clean).
- Frontend validation group: `npm test -- --run` then `npm run build` — checkout 3/3, Vitest 20/20 (11 `App.spec.js` + 3 `ProductManagement.spec.js` + 6 `OrderHistory.spec.js`), build exit 0.
- Inventory evidence: `GET /api/orders` and `GET /api/orders/{orderId}` now exist (`OrderController.java`), backed by `OrderRepository.findByMemberId`/`countByMemberId`/`findOrderById`/`findDetailsByOrderId`, all tested (`OrderQueryServiceTest`, `OrderQueryControllerTest`). Live `esun-mysql` schema verified to match `01_schema.sql` (payment_transaction table, product.creator_id/deleted_at, shop_order's query index, widened member_id all present). `.env` configured with ECPay Stage credentials (gitignored, never committed).
- Outcome: **Phase 3.1 acceptance requirements are met.** Remaining non-blocking notes for a future session: a single continuous ECPay round trip (checkout redirect through to ECPay's real server actually calling our callback) was not observed in one run — the two halves were verified independently, which the independent reviewer assessed as adequate evidence of correctness but not a full live round trip; and this rerun's own new coverage tests, while independently reasoned through here, were not dispatched to a separate review agent (routine, low-risk test-only additions, unlike this session's other fixes).
- Elapsed time / usage / cost: `unknown`.
