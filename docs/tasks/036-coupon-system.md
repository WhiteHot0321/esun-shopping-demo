# Task 036 — Phase 3.2 #14 優惠券系統 [Tier 3]

- Baseline: `advanced-v2` @ `06b7a44`, branch `feature/phase32-14-coupon`
- Risk: **Critical** — touches order amount calculation, payable price, a shared counter under concurrency and the cancel path (019 §: coupons that touch order totals are promoted to Critical).
- Implementer: Claude Code (Sonnet 5). Reviewer: independent fresh-context read-only agent (see "Review" below). Not a Codex review.
- Scope: platform-wide discount codes managed by ADMIN, applied by buyers at checkout. Out of scope: seller-owned coupons, stacking, automatic (code-less) promotions, coupon distribution/claiming, refund of a coupon on paid-order cancellation beyond releasing its slot, expiry background jobs.

## Requirements (source for the independent review)

1. ADMIN (only) can create a coupon, list coupons, and change only `active` / `expires_at` / `total_quota`. Discount rule fields (type, value, max discount, minimum order, per-member limit, start) are immutable once issued. No delete (orders reference coupons). Create/update write `audit_log` rows in the same transaction (`COUPON_CREATE`, `COUPON_UPDATE`, before read under the row lock).
2. Types: `PERCENT` (whole 1–99, optional `max_discount` cap) and `FIXED` (whole dollars). Discounts are whole dollars (ECPay accepts integer TWD only). Payable amount never drops below 1 (payment ledger requires amount > 0): discount is capped at `subtotal - 1`; a coupon that would give 0 is refused.
3. Eligibility (all evaluated server-side against the **locked** coupon row at checkout): active, `starts_at <= now < expires_at`, subtotal >= `min_order_amount`, `used_count < total_quota` (when set), per-member usage < `per_member_limit`. Code lookup is case-insensitive (stored upper-case); unknown/malformed/disabled all answer 404 without distinguishing.
4. Pricing authority: the client only names a code. `shop_order.price` stores the amount payable (payment already charges `price`); `discount_amount`, `coupon_id`, `coupon_code` snapshot what was applied; `price + discount_amount` = subtotal. Any client-supplied price/discount field is ignored.
5. Atomicity: redemption runs inside the order-creation transaction (`Propagation.MANDATORY`). Concurrent checkouts can never exceed the total quota or a member's limit. A rejected coupon rolls back the whole order (no order, no stock taken, counters untouched, Redis reservation compensated).
6. Cancellation (buyer or seller/admin) releases the coupon slot exactly once, in the same transaction, without deadlocking against concurrent checkout. Documented lock order on every path: order row → coupon row → coupon_member_usage row → product rows.
7. Idempotency: replaying a checkout with the same `requestId` returns the original order and consumes no second use.
8. Buyer API: `POST /api/cart/coupon-preview {code}` (advisory, priced from the caller's own server-side cart, reserves nothing); `couponCode` optional on `POST /api/cart/checkout` and `POST /api/orders`. Order views expose `couponCode` / `discountAmount` (hidden from a seller who sees only part of the order).
9. Frontend: cart coupon field with preview, stale-preview reset on cart change, code frozen while an unconfirmed order is pending; order card shows the discount; ADMIN coupon panel; audit panel labels.
10. Migration `14_coupon.sql` is repeatable and adds the new tables/columns without touching existing rows.

## Design decisions and trade-offs

- **Counters + locked row instead of counting orders.** The first idea (count non-cancelled orders per coupon) fails under InnoDB REPEATABLE READ: the plain `COUNT(*)` reads the transaction's snapshot (already established by earlier reads in the order transaction) and can miss a redemption that committed after it. Counting with `FOR UPDATE` would take gap locks over `shop_order`. Instead: `SELECT … FOR UPDATE` on the coupon row (a current read), validate on that row, then bump `used_count` and `coupon_member_usage`. Verified by mutation: removing the lock makes the quota test fail.
- Per-member slot is a conditional `UPDATE … WHERE used_count < limit` after `INSERT IGNORE` (no gap-lock-then-insert pattern, unambiguous row count).
- Lock by primary key (row known to exist), not by code, to avoid gap locks on the unique index for guessed codes.
- Trade-off: a hot coupon serialises its own redemptions until each order transaction commits (including its stock decrement). Acceptable for this scale; the alternative (Redis Lua token bucket) is the natural next step and is out of scope.
- One coupon per order; platform-wide (ADMIN-issued) because multi-seller discount allocation is a separate problem.
- `discount_amount` is a per-order snapshot; cancelling keeps the snapshot (history) but stops it counting.
- Time uses the JVM local clock consistently for both storage and comparison (same convention as order ids/token expiry), not `CURRENT_TIMESTAMP`.

## Verification (2026-09-24)

Final state, executed:

- Backend `mvn clean test`: **225/225**, 0 failures/errors/skipped, JaCoCo gate PASS (206 baseline + 19 new: `CouponServiceTest` 6, `CouponIntegrationTest` 13 on real MySQL via Testcontainers).
- Frontend `npm test`: checkout 3/3 + Vitest **71/71** (58 baseline + 13 new: `CartPanel.spec` 7, `CouponPanel.spec` 5, `OrdersPanel.spec` +1), `npm run build` PASS.
- Mutation checks (temporary source edits, restored): removing the coupon `FOR UPDATE` makes `concurrentCheckoutsCannotExceedTheTotalQuota` fail; reverting `FOR UPDATE OF o` makes the new same-buyer/same-address test fail in round 0 with an HTTP 500 (see review finding 1).
- Live end-to-end on a disposable MySQL 8 (fresh schema through the real `docker-entrypoint-initdb.d` path, `PAYMENT_PROVIDER=sandbox`) with the real backend and Vite UI: ADMIN created a coupon in the panel, disable/enable wrote `audit_log` rows (before/after `active`, expiry/quota untouched); buyer cart preview refused an unknown code, applied `save10e2e` (2 x 1,200 -> discount 240 -> payable 2,160), the discount reset itself when the cart changed, checkout stored `price 2160 / discount_amount 240 / coupon_code SAVE10E2E`, `used_count 1`, order card showed the discount, the sandbox payment ledger row was **2160.00** (charged the discounted amount), a second use by the same buyer was refused with 409 on both preview and checkout, and cancelling the *paid* order gave `REFUND_REQUIRED`, `used_count 0`, `coupon_member_usage 0`, stock restored to 50, and a second cancel 409 without a second release. Pre-existing seed orders read `discount_amount 0`. Disposable container, backend and preview server were removed afterwards.

## Review (independent, fresh-context, read-only — not Codex)

Verdict on the first pass: **FAIL (conditional)**. Findings and disposition:

1. **Must fix — lock-order cycle (real, reproduced).** `OrderRepository.lockHeader` used a bare `FOR UPDATE` on a join with `shipping_address`, so cancel X-locked the order *and* its address row, then waited for the coupon row; checkout held the coupon and needed the same address for its FK check. Fixed with `FOR UPDATE OF o`; regression test with one buyer/one address over 8 rounds; verified by mutation (500 in round 0 without the fix).
2. **Must fix — vacuous "release exactly once" test.** Replaced by `repeatedAndConcurrentCancelsReleaseASlotExactlyOnce` (two live redemptions, 5 concurrent cancels of one order, counters must be 1 not 0).
3. Should fix — seller partial-view hiding untested: added `sellersSeeNoCouponDetailsOnOrdersTheyOnlyPartlyOwn` (multi-seller order).
4. Should fix — deadlock retry exhaustion returns 409 and could masquerade as "quota spent": the concurrent tests now assert no `CONCURRENT_CONFLICT` bodies.
5. Should fix — time handling: coupon timestamps are read with `getObject(..., LocalDateTime.class)` and `created_at`/`updated_at` are app-written, so neither the JVM nor the DB session time zone can shift them. (Reviewer could not confirm the skew by reading; the change is defensive and matches the audit-log convention.)
6. Should fix — stale preview in flight: `CartPanel` ignores a preview that returns after the cart total changed (spec added).
7. Should fix — toggle could overwrite a concurrent edit: added `POST /api/admin/coupons/{id}/active` (touches only `active`); the panel uses it. `PUT /api/admin/coupons/{id}` stays as an API for full edits of expiry/quota.
- Optional, accepted: migration test now also proves legacy orders read `discount_amount 0` and closes its connection.

After the repairs only the narrow re-verification above was run (targeted mutation checks, then one final full suite); no second full review round.

## Known follow-ups (not blocking)

- No rate limit on `POST /api/cart/coupon-preview`; expired/exhausted/not-started answers confirm that a code exists (disabled/unknown/malformed are indistinguishable).
- Fractional payables are still possible for non-integer product prices (ECPay refuses them); pre-existing and unchanged (`subtotal - 1` cap keeps the coupon path integer whenever the subtotal is).
- One coupon per order, platform-wide (ADMIN); no seller-scoped coupons, stacking, first-purchase rules, code-less promotions or expiry job. Refund execution for cancelled *paid* coupon orders is still the existing `REFUND_REQUIRED` ledger state.
- A hot coupon serialises its redemptions on the coupon row until commit; a Redis Lua token bucket is the next step if that ever matters.
- Not a Codex review.
