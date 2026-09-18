# Task 019 — Phase 3.1 B1 frontend and identity closeout

- Mode: IMPLEMENT
- Baseline: Task 017–018 uncommitted work on `codex/phase31-pay-status`.
- Worktree: `C:\GitHub\esun-shopping-phase31`.
- Goal: close B1's missing frontend redirect/return UX and make JWT subject authoritative for order ownership.
- Risk: payment authorization and browser navigation.
- Implementer: Terra worker; reviewer: Codex plus independent read-only audit if security behavior changes materially.
- Allowed scope: order controller/DTO/service tests, payment callback response handling/tests, ShopWorkspace/App routing state and focused frontend tests.
- Exclusions: B2 product admin, real ECPay credentials/live payment, commit/push/merge, unrelated UI redesign.

## Acceptance

- `POST /api/orders` overwrites/derives member identity from `authenticatedEmail`; a crafted body cannot create an order for another member.
- Successful checkout keeps the order id and exposes a pay action; requesting the payment form sends no amount and uses authenticated ownership.
- Browser submits an ephemeral POST form containing only server-returned ECPay fields to the server-returned action URL; no secret is exposed.
- The return URL can show a bounded payment-result state without trusting it to update DB state.
- Callback success and rejection use ECPay-compatible plain-text response semantics.
- Focused backend and frontend tests prove ownership, payment form request, generated form submission and existing checkout retry behavior.

## Result

Implementation is present, but this task is not ready for independent review because the focused suites are not fully green.

- Changed (uncommitted): `CreateOrderRequest`, `OrderController`, `PaymentController`, focused order/JWT/payment controller tests, `ShopWorkspace.vue`, `App.vue`, `App.spec.js`, and `checkout.test.js`. Task 017–018 files and B2 product-admin scope were not changed by this task.
- Ownership: `POST /api/orders` now requires `authenticatedEmail` and overwrites the legacy body `memberId` before `OrderService` sees it. The DTO no longer requires a client `memberId`; a controller/JWT test captures and proves the JWT subject wins over a crafted body value.
- Payment UX: after a successful or retried checkout, the UI retains the order id and exposes `前往付款`. It POSTs only to `/orders/{orderId}/payment-form`, then creates and submits a transient browser form from the server-returned action URL and fields. Checkout lifecycle retry still retains the original payload/request id. `App.vue` only presents a fixed return notice for bounded ECPay/query markers; it never trusts those markers to update local or server payment status.
- Callback protocol: both valid acknowledgement and `BusinessException`/malformed callback rejection return `text/plain` (`1|OK` or `0|ERROR`), so no payment callback path falls through to the global JSON handler.
- Verification attempt 1: `mvn '-Dtest=OrderControllerTest,OrderControllerValidationTest,JwtAuthFilterTest,PaymentControllerTest' test` compiled and ran 21 tests; 20 passed and one new ownership test failed because its fixture omitted `requestId`. Repair round 1 added the required UUID.
- Verification attempt 2: same backend command ran 21 tests; 20 passed and `createOrder_bodyMemberIdIsIgnoredAndJwtIdentityIsUsed` failed with HTTP 500 because the newly-valid request reaches a Mockito `OrderService` with no configured return order id, not because the JWT overwrite assertion failed. Maven exit 1. No further repair was made (maximum one automatic repair round).
- Frontend command: `npm test`. `checkout.test.js` 3/3 passed, including the member-free original-payload retry. `App.spec.js` 10/11 passed: the new payment form POST/fields test and bounded return notice test passed. The sole failure is the known pre-existing registration test that still queries exact `input[placeholder="密碼"]` while the existing registration UI uses `密碼（至少 8 碼）`; it is unrelated to this task. Exit 1.
- `git diff --check` passed (only repository LF/CRLF warnings).
- Repair rounds: 1. User interventions: 0. Elapsed time/token/cost evidence: unknown.

## Next bounded action

In a fresh repair task, stub the successful `OrderService.createOrder` return in the ownership controller test and rerun the same focused backend command. Separately either update or explicitly accept the pre-existing registration selector test before a frontend suite can be green. Then obtain the required independent security/payment review.

## Correction and verification — 2026-09-17

- Codex correction round 2 added the missing successful service stub and changed the brittle password placeholder selector to `input[type="password"]`.
- Backend focused command with JaCoCo intentionally skipped for the narrow run: 21/21 passed, Maven exit 0.
- Frontend: checkout 3/3, App 11/11, and `npm run build` all passed.
- Next: independent review of JWT ownership, callback response semantics and generated payment form behavior.

## Acceptance closeout

- Independent read-only review: **PASS**, no blocker defects.
- Verified JWT ownership overwrite, member-free retry payload, server-owned payment amount, DOM-safe ephemeral form construction, non-authoritative return notice, and ECPay `text/plain` callback protocol.
- B1 application status: backend and frontend implementation accepted; live ECPay sandbox payment remains an external Phase 3.1-C acceptance item.
- Uncommitted; no push or merge performed.
