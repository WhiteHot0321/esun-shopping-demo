# Task 018 — Phase 3.1 B1 ECPay integration

- Mode: IMPLEMENT
- Baseline: `ec140f728fe6e7e5cf4e87a7e065a4ba4bd83287` plus the uncommitted Task 017 payment-status foundation.
- Worktree / branch: `C:\GitHub\esun-shopping-phase31` / `codex/phase31-pay-status`.
- Risk: critical payment, authorization, transaction and idempotency behavior.
- Implementer: Terra worker. Reviewer: Codex coordinator, followed by independent read-only Claude audit.
- Goal: implement a testable ECPay AioCheckOut payment-form and verified callback flow without committing credentials.
- Initial change limit: backend payment package/controller/DTO/config/repository/service plus focused tests and minimal schema migration; frontend integration is a later dependent step.
- Target verification: focused ECPay unit/controller/integration tests; broader backend suite only after focused evidence passes.
- Exclusions: product administration/restock, refunds, recurring payments, commit/push/merge, real sandbox credentials, unrelated cleanup.

## Acceptance

- Provider-neutral gateway seam with an ECPay implementation using documented CheckMacValue ordering/encoding/SHA-256 rules.
- Merchant ID, HashKey, HashIV, payment URL and callback/return URLs are configuration/environment values; secrets never enter Git or logs.
- Authenticated order owner can request a payment form only for a PENDING order; amount is loaded from the DB, never trusted from the client.
- Callback independently verifies CheckMacValue, merchant/order/amount and successful provider status before an atomic `PENDING -> PAID` update.
- Duplicate valid callbacks are idempotent; conflicting or invalid callbacks cannot update the order.
- Focused tests cover deterministic signing, wrong signature, wrong amount/order, unsuccessful payment, first success and duplicate success.

## Result

Backend B1 implementation is accepted for this milestone; frontend payment redirect/return UX and live ECPay sandbox acceptance remain separate required work before Phase 3.1 can close.

- Changed (uncommitted): `EcpayProperties`, provider-neutral `PaymentGateway`/`PaymentForm`/`VerifiedPaymentCallback`, `EcpayPaymentGateway`, `PaymentService`, `PaymentRepository`, `PaymentTransaction`, `PaymentController`, ECPay callback public-route exception, environment-backed `ecpay` configuration, `payment_transaction` base schema and additive migration, and focused gateway/service tests.
- Behavior implemented: the payment form reads the order amount from DB, requires JWT-subject ownership and `PENDING`; the callback verifies a SHA-256 CheckMacValue before checking merchant, trade number, amount and successful status. The repository locks the transaction plus order, records the provider transaction id, and conditionally transitions only `PENDING -> PAID`; matching duplicate callbacks acknowledge without another update, and conflicting replays fail.
- CheckMacValue test uses ECPay's published 2023 SHA-256 vector: expected `6C51C9E6888DE861FD62FB1DD17029FC742634498FD813DC43D4243B5685B840`.
- Verification attempt 1: `mvn '-Dtest=EcpayPaymentGatewayTest,PaymentServiceTest' test` failed at compilation: Java 17 does not provide `List.getFirst()`, and `URLEncoder.encode` requires `raw.toString()`. These were corrected in the single permitted repair round.
- Verification attempt 2 (same command): `EcpayPaymentGatewayTest` 2/2 passed; `PaymentServiceTest` executed 5 tests with 0 assertion failures and 1 Mockito `UnnecessaryStubbingException` error in `createPaymentForm_requiresOwnerAndLoadsAmountFromDatabase` (`paymentGateway.merchantId()` stubbing from shared setup is unused by that test). Maven exited 1, so focused verification is **not green**. No further repair was made because this task explicitly limits automatic repair to one round.
- `git diff --check` executed after the test; no whitespace errors (only Git LF/CRLF warnings).
- No real credentials, callback bodies, or secrets were logged or committed. No frontend or product-admin files were changed by this task. Existing Task 017 changes remain preserved.
- Repair rounds: 1. User interventions: 0. Elapsed time/token/cost evidence: unknown.

## Next bounded action

Codex correction round 2 localized the optional merchant-id mock with Mockito `lenient()` and reran the same command. All 7 focused tests passed (gateway 2/2, service 5/5, 0 failures/errors/skips); Maven still exited 1 only at the repository-wide JaCoCo gate because a focused invocation does not cover the unrelated gated Order/Product/Stock services. No further implementation repair is authorized before independent review.

Next: independent payment-flow review, then a full backend suite if the review passes.

## Acceptance closeout — 2026-09-17

- Independent read-only payment/security review: **PASS**, no blocker defects.
- Final backend verification: `mvn clean test` — **89/89 passed**, 0 failures/errors/skips, JaCoCo four-service gate passed, Maven exit 0.
- Review observations retained for final Phase 3.1 work: callback failures currently use the global JSON error response instead of ECPay's text protocol; existing order creation still accepts client-provided `memberId`, which weakens the ownership trust root until it is derived from JWT.
- B1 backend status: implemented, tested, independently reviewed; uncommitted and not pushed/merged.
