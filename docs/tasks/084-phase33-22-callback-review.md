# 084 — #22: adversarial review of the inbound ECPay callback

Date: 2026-11-16 (Asia/Taipei). Executor: Claude Code (implementer and reviewer of this slice; no independent review yet). Branch `feature/phase33-22-ecpay`, baseline `advanced-v2 @ cd04e78`. Risk: critical (payment state). Scope: everything about the inbound callback that needs neither real ECPay credentials nor a human payment. Tests use only ECPay's public stage test merchant.

Files read: `EcpayPaymentGateway`, `PaymentController`, `PaymentCallbackService`, `PaymentService`, `PaymentRepository` (locking and transitions), `EcpayProperties`, `GlobalExceptionHandler`, `JwtAuthFilter` (public routes), `13_payment.sql`, the two ECPay test classes and the concurrency tests in `PaymentIntegrationTest`.

## Findings

| # | Severity | Finding | Outcome |
|---|---|---|---|
| 1 | **High** | A signed field could be shadowed by a case-variant of its name | **Fixed**, 3 tests failed before the fix |
| 2 | Low | An authentic callback with a wrong amount on an already settled attempt is answered `0|ERROR`, so ECPay retries it | Recorded, not changed |
| 3 | Low | `SimulatePaid=1` is accepted whenever the payment URL contains `payment-stage.` (substring test on owner-controlled config) | Recorded, not changed |
| 4 | Info | CheckMacValue is case-blind and has no field delimiter protection (ECPay's scheme, not ours) | No exploit found, see below |
| 5 | Info | No rate limit on the public callback route | Recorded; unauthenticated requests cost one SHA-256 and no DB access |
| 6 | Info | Refund-required payments are only a log line and a row; nothing alerts on them | Recorded (already an open item in 035) |

### 1. Case-variant field names (High, fixed)

`calculateCheckMacValue` sorts names case-insensitively, as ECPay requires, by putting them in a case-insensitive `TreeMap`. When two names differ only in case the map keeps one entry whose value is the **last** one seen. `verifyCallback` then reads the business values by **exact** name. The controller's duplicate check only caught exact-name duplicates. So the MAC could cover one value while the shop acted on another, without knowing HashKey/HashIV.

Failure scenarios, both reproduced against real MySQL before the fix (`200 1|OK`, order marked paid):

- Retargeting: someone holding one genuine paid callback for a 100-TWD order (their own purchase, seen in any proxy or log on the callback path) sends `MerchantTradeNo=<another 100-TWD attempt>` plus the genuine fields and `merchanttradeno=<the paid one>`. Every further order of the same amount is marked PAID for free.
- Decline to success: a genuine decline callback (`RtnCode=10100058`) replayed as `RtnCode=1` plus `RTNCODE=10100058` marks the order PAID with no money moved.

The precondition is possession of one genuine signed callback, which is why this is High and not Critical: the callback is server to server and the buyer's browser never sees it.

Fix (`EcpayPaymentGateway.verifyCallback`): a callback whose names are not unique ignoring case is never authentic. It is placed in the gateway, not the controller, because the gateway is the trust boundary every caller goes through.

### 2-6. Not changed

- 2: `PaymentCallbackService.apply` throws 400 for an amount mismatch unless the attempt is INITIATED/FAILED with a success result (those are parked as `REFUND_REQUIRED/AMOUNT_MISMATCH`). ECPay stops retrying after its own limit; the mismatch is logged at ERROR. Changing the acknowledgement is a behaviour decision for the lead.
- 3: a production URL cannot contain that substring unless the owner configures one; `ProductionConfigValidator` is the better place for a strict host check if wanted.
- 4: the whole encoded string is lowercased before hashing, so values differing only in case share a MAC; the consumed fields are digits or looked up case-insensitively, so this reaches the same row. Values are concatenated unescaped, so `a=1&b=2` and `a=1%26b%3D2` share a MAC; with unique names enforced I found no way to change `MerchantID`, `MerchantTradeNo`, `TradeAmt`, `RtnCode` or `TradeNo` this way, because each needs the literal `&Name=value&` sequence that the genuine string does not contain.

## Checked and found correct

| Area | Result | Evidence |
|---|---|---|
| Algorithm | SHA-256 over `HashKey=..&sorted params..&HashIV=..`, URL-encoded, lowercased, .NET character table (`-_.!*()` unescaped, space as `+`), upper-hex | ECPay's published vector (existing test) and a new full credit-card field set with empty fields, spaces, slashes and Chinese `RtnMsg` whose expected MAC was computed by a separate Python implementation |
| Compare | `MessageDigest.isEqual` (constant time); supplied MAC trimmed and upper-cased, so its case is irrelevant | unit test passes a lowercase MAC |
| Missing / duplicate params | no MAC, missing `RtnCode`/`TradeNo`/`MerchantTradeNo`, non-integer or oversized `TradeAmt`, foreign `MerchantID`, exact duplicates (controller) and case-variant duplicates (finding 1) all rejected with `400 0|ERROR`, no state change | existing and new tests |
| Amount / trade number | amount compared with the server-side attempt amount under lock; unknown trade number is 404 after authentication only | existing tests |
| Replay | same callback twice: second is `DUPLICATE`, `1|OK`, no change | existing test |
| Late success after cancellation | attempt was closed `FAILED/ORDER_CANCELLED`; the success is acknowledged `1|OK`, parked `REFUND_REQUIRED/LATE_SUCCESS_ON_CLOSED_ATTEMPT`, order stays CANCELLED and unpaid; a replay changes nothing | new ECPay-path test |
| Expiry | there is no order or payment expiry in the codebase, so no late-after-expiry path exists (open item in 035) | code search |
| Concurrent callbacks | order row locked first, payment second, compare-and-set transitions; 8 simultaneous identical ECPay callbacks all get `1|OK`, exactly one `SUCCEEDED` row, order paid once | new ECPay-path test |
| Response body | `1|OK` (text/plain, 200) for applied, duplicate, declined and refund-parked results; `0|ERROR` 400 for unauthentic or unusable ones; an unexpected server error returns 500, which ECPay retries | existing and new tests |
| Logging | the reject line carries no parameters; HashKey, HashIV and the supplied MAC do not appear in captured output for an accepted and a rejected callback | new test with `OutputCaptureExtension` |

## Tests

Added: `EcpayPaymentGatewayTest` +2 (11 -> 13), `EcpayPaymentIntegrationTest` +5 (10 -> 15, real MySQL via Testcontainers).

- Before the fix: 28 run, **3 failed** (`aCapturedGenuineCallbackCannotBeRetargetedAtAnotherOrderWithACaseVariantFieldName`, `aGenuineDeclineCannotBeTurnedIntoASuccessWithACaseVariantRtnCode`, `aSignedFieldCannotBeShadowedByACaseVariantOfItsName`); the integration ones with `Status expected:<400> but was:<200>`.
- After the fix: `EcpayPaymentIntegrationTest` 15/15, `EcpayPaymentGatewayTest` 13/13.
- Full backend `mvn clean test`: **329 run, 0 failures, 0 errors, 0 skipped**, JaCoCo coverage checks met, BUILD SUCCESS (Windows host, Testcontainers MySQL/Redis).

The other four new tests passed before the fix; they pin behaviour that was correct but untested on the ECPay path.

## Not proven

- **No real ECPay callback has been received.** The field set, the lowercase-field ordering and the Chinese `RtnMsg` handling are verified against ECPay's documentation and an independent implementation, not against a captured callback. The raw-body test runs through MockMvc's form parsing, not Tomcat's.
- No independent review of this change (required by AGENTS.md for payment changes).
- Frontend untouched and not re-tested.

## Owner step

One stage payment in the owner's own Chrome, following the refreshed `docs/tasks/072-phase33-22-ecpay-stage-runbook.md` (six steps, public stage test merchant, callback through Tailscale Funnel).

## Engineering concept

Canonicalisation mismatch: the verifier and the consumer must read the same bytes. Here the MAC was computed over a case-folded view of the parameters while the business logic read the raw view, so one message had two meanings. The general rule is to verify, then use only what was verified, and to reject any input that is ambiguous under the verifier's own normalisation.

## Documentation block

- **Task**: #22 inbound ECPay callback adversarial review.
- **Problem**: a genuine signed callback could be re-used to pay another order or to turn a decline into a success.
- **Root Cause**: case-insensitive name handling in the MAC, exact-name reads afterwards.
- **Solution**: reject callbacks whose parameter names collide ignoring case.
- **Engineering Concept**: canonicalisation mismatch between signature verification and consumption.
- **Test Result**: see Tests.
- **Trade-offs**: a callback that legitimately carried two names differing only in case would be rejected; ECPay's documented fields contain none.
- **Next Step**: independent review, then the owner's stage payment (072).
