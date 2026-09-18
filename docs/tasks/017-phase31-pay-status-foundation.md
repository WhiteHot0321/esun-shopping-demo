# Task 017 — Phase 3.1 payment-status foundation

- Mode: IMPLEMENT
- Baseline: `ec140f728fe6e7e5cf4e87a7e065a4ba4bd83287` (`advanced-v2`)
- Branch/worktree: `codex/phase31-pay-status` / `C:\GitHub\esun-shopping-phase31`
- Task class: medium, payment/security-sensitive
- Goal: make order creation server-authoritative for `payStatus` and remove the buyer-controlled payment-status UI/payload.
- Allowed production scope: `CreateOrderRequest`, `OrderTransactionService`, `ShopWorkspace.vue`.
- Verification scope: focused backend order tests, frontend tests/build, independent read-only review.
- Exclusions: real ECPay/NewebPay credentials and callbacks, product administration, cancellation/restock, commit/push/merge.

## Acceptance

- A client-supplied `payStatus` cannot create an order as `PAID` or `SHIPPED`.
- Every new order is persisted as `PayStatus.PENDING` (`0`).
- Checkout no longer presents or sends a payment-status selector.
- Existing order idempotency and checkout behavior remain covered by focused tests.

## Result

Status: implemented and independently reviewed; targeted verification is partial, so Phase 3.1 is not complete.

- Production changes: removed client-owned payment status from the JSON DTO and checkout form/payload; order persistence now always uses `PayStatus.PENDING.ordinal()`.
- Focused backend command: `mvn '-Dtest=OrderServiceTest,OrderControllerTest,OrderControllerValidationTest' test` executed 23 tests with 0 failures/errors/skips. Maven then exited 1 only at the global JaCoCo gate because this narrow run did not cover unrelated gated services.
- Frontend: checkout lifecycle 3/3 passed. Vitest passed 8/9; the unchanged registration test still searches for the obsolete exact placeholder `密碼` after the UI changes it to `密碼（至少 8 碼）`. This pre-existing selector mismatch prevented the build chained after `npm test` from running.
- Independent review: Claude read-only audit PASS; no blocker defect against the four acceptance requirements.
- Repair rounds: 1. The initial Java test compilation exposed 13 fixtures calling the removed setter; resolved with a deprecated, `@JsonIgnore` no-op compatibility setter that cannot influence persistence.
- User interventions: 0 after the initial request.
- Elapsed time / token / cost evidence: unknown.
- Branch remains uncommitted; no push or merge performed.

## Remaining

- Fix or separately accept the unrelated `App.spec.js` registration selector, then rerun frontend tests/build.
- Run the full backend suite if this foundation is promoted for merge.
- Provider decision (2026-09-17): use ECPay AioCheckOut. Official documentation exposes a clear test endpoint and CheckMacValue flow, which is a smaller first integration surface than NewebPay's AES256 plus SHA256/CheckCode flow. Credentials must come from environment variables and never be committed.
- Implement the ECPay payment-form/callback boundary in a fresh bounded session; do not expose a buyer-callable endpoint that can self-mark an order paid.

## ECPay design boundary for the next session

- Add a provider-neutral `PaymentGateway` interface and ECPay implementation.
- `POST /api/orders/{orderId}/payment-form` may create a signed redirect form only for the authenticated order owner and a `PENDING` order.
- `POST /api/payments/ecpay/callback` is the only first-version path that can transition `PENDING -> PAID`; it must verify CheckMacValue, merchant/order/amount, and be idempotent.
- Store the provider transaction identifier and reject conflicting replays. Do not log HashKey, HashIV, full callback bodies, or card/payment secrets.
- Tests use a mock gateway and deterministic signing vectors; live sandbox credentials remain an explicit external acceptance step.
