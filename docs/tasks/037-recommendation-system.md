# Task 037 — Phase 3.2 #15 推薦系統 [Tier 3]

- Baseline: `advanced-v2` @ `ccf62b9`, branch `feature/phase32-15-recommendation`
- Risk: **Medium** — new read-only endpoints plus one index. No write path, no lock, nothing on the checkout/stock/payment path (019 §: recommendations stay Medium unless they touch order amounts).
- Implementer: Claude Code (Sonnet 5), self-review only (no independent audit; see "Not proven").
- Scope: item-based "customers who bought this also bought" (public) and a personalised "for you" list (JWT), both derived from order history. Out of scope: browse/click tracking, embedding or ML similarity, seller-facing analytics, email campaigns, caching, A/B ranking.

## Requirements

1. `GET /api/products/{id}/recommendations?limit=` — public, aggregate-only. Unknown or soft-deleted anchor → 404. `limit` 1–20 (default 6), otherwise 400.
2. `GET /api/recommendations?limit=` — requires a JWT (401 otherwise); the member is the verified token identity, no member id is accepted from the request.
3. Ranking is three tiers concatenated and de-duplicated, each item labelled with its strongest tier: `CO_PURCHASE` (bought in the same live order as the anchor / the member's purchased products), then `POPULAR` (most distinct live orders), then `NEW_ARRIVAL` (filler so a fresh shop still shows something). Order inside a tier is fully deterministic: score DESC, product id ASC.
4. Only live orders count (`order_status <> 'CANCELLED'`); candidates are sellable (not soft-deleted, `quantity > 0`). A product that sells out between the ranking read and the product read simply drops out.
5. Privacy: a signal needs at least `recommendation.min-support` (default 2) distinct orders, so a public list cannot reveal one buyer's basket.
6. Personalised list excludes everything the member has bought (live orders) and everything already in their cart.
7. Frontend: a "為你推薦" strip for signed-in members, and a "買過「X」的人也買了" strip under the review panel of the product being viewed; "加入購物車" reuses the cart quantity path (stock clamping, server cart sync). Strips reload after each catalogue reload (checkout, stock change); a stale slower response never overwrites a newer one; malformed payloads render nothing.
8. Migration `15_recommendation.sql` is repeatable and only adds `idx_order_detail_product_order (product_id, order_id)`; `01_schema.sql` creates it for fresh installs.

## Design decisions and trade-offs

- **SQL co-occurrence, not ML.** A self-join of `order_detail` on `order_id` counted with `COUNT(DISTINCT order_id)` is exact, explainable ("2 orders") and needs no model, training job or new store. The `(product_id, order_id)` index turns the first hop into an index-only lookup.
- **Distinct orders, not quantities.** One bulk buyer cannot dominate a pair's score.
- **Read-only + one read-only transaction.** The endpoints take no row locks, so they cannot deadlock with checkout or cancel; `@Transactional(readOnly = true)` gives the several queries one snapshot (REPEATABLE READ).
- **Tier fallback instead of an empty list.** Cold start is the normal state of a young shop; showing an honest `NEW_ARRIVAL`/`POPULAR` label is better than a blank panel, and the label tells the user which signal they are looking at.
- **`min-support` as privacy, not just quality.** With support 1 the public list would expose a single customer's basket. Trade-off: on sparse data the co-purchase tier is empty and tiers 2–3 carry the list.
- **Personal list learns from the member's purchases, not their views.** No tracking table means no new PII and no write on every page view; browsing signals are the natural next step.
- **No cache.** The queries are bounded by `limit` and indexed; a cache would need invalidation on every order/stock change. Revisit with a measured latency problem.

## Verification (2026-09-24)

See "Results" in the handoff for the final counts. Coverage highlights:

- `RecommendationIntegrationTest` (real MySQL via Testcontainers, 6 cases): live-only counting, ordering and tie-break, min-support, sold-out/soft-deleted exclusion, cancel and stock changes reflected, cold-start fallback, 404/400, 401, identity from the token only (a `memberId` parameter is ignored), cart/purchased exclusion, migration run twice.
- `RecommendationServiceTest` (8 cases): limit validation, 404, tier order/de-dup/labels, later tiers asked only for the remainder and never for taken ids, dropout between reads, member anchors/exclusions.
- Mutation check: replacing the cancelled-order filter with `1 = 1` made 3 of the 6 integration cases fail; restored.
- Frontend: `RecommendationStrip.spec.js` (7), `App.spec.js` (+2: personal strip + add-to-cart, guest has no personal request + per-product strip). Two existing assertions that counted *all* GETs now exclude `/recommendations`.

## Known follow-ups (not blocking)

- No rate limit on the public per-product endpoint (each call is an indexed self-join bounded by `limit`).
- The personal query passes the member's purchased ids as an `IN` list; unbounded only in theory (bounded by catalogue size).
- No browse/click signals, no seller-specific or category-aware ranking, no time decay (all-time counts).
- Not a Codex review and no independent audit.
