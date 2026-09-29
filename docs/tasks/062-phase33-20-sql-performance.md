# 062 — Phase 3.3 #20 SQL performance and capacity baseline (SQLPERF-01 to 03)

Date: 2026-09-29 (Asia/Taipei). Executor: Claude Code. Branch: `advanced-v2`. Scope source: `055`.

## Method (repeatable)

`python bench/sqlperf/capture.py [--seller-variant current|deferred] [--mysqld-arg=...] --out <file>` starts a throwaway
`mysql:8.0` container (8.0.46, 16 logical CPUs shared with the host, InnoDB buffer pool 128 MB unless overridden, REPEATABLE-READ),
applies the Flyway baseline `V1__baseline_schema.sql` plus `bench/sqlperf/seed.sql`, then for **47 SQL shapes** taken from the
repositories records `EXPLAIN`, and for the 43 read shapes `EXPLAIN ANALYZE` (one discarded warm-up, then the median of 5 runs). The 4 mutating
shapes use plain `EXPLAIN` only. `bench/sqlperf/compare.py` compares captures and, with `--check`, fails a candidate only when it exceeds the baseline's
worst run by more than `baseline_max * 1.5 + 0.5 ms`. Nothing touches a real database.

Dataset (skewed: member `u1` and product `P00001` are hot): 5,000 members, 2,000 products, 100k orders, 249k order lines, 199k status
history rows, 90k payments, 20k reviews, 100k audit rows, 10k cart lines, 100k order requests. Combined data+index is about 250 MB.

Coverage: the SQL of product catalog/search, buyer and seller/admin order lists and detail hydration, checkout lookups and locks, payment
callback lookups, reviews, recommendations, cart, audit filters and coupon lookup. Excluded on purpose: authentication/non-SQL endpoints,
and single-row primary-key/unique lookups, which MySQL resolves before execution (reported as `const`, ~0 ms).

## SQLPERF-01 findings (baseline, 128 MB buffer pool)

| Shape | Median | Reading |
|---|---:|---|
| `reco.popular` (public) | ~500 ms | Aggregates about 200k order lines, one primary-key lookup on `shop_order` per line, then a temporary-table group. |
| `reco.co_purchase` (public) | 381-567 ms | Same cost class. **Plan instability:** the join order flips between captures (anchor line before order = ~385 ms, order before anchor = ~550 ms) because sampled index statistics differ; it can flip in production after a stats refresh. |
| `order.seller_page` | 41.6 ms | Semijoin is materialised (6.6k orders for a 40-product seller), every one is joined to its header columns and then sorted to take 20. |
| `order.seller_count` | 8.4 ms | Same materialisation, count only. |
| `reco.member_purchases` | ~15 ms | `DISTINCT` with a temporary table, scales with the buyer's history. |
| `product.rating_all` | ~10.7 ms | Aggregates all visible reviews; proportional to review count. |
| `review.admin_all` | 6.7 ms | Full scan + filesort (no `created_at` index). Admin-only. |
| `audit.action_window` / `audit.count_action_window` | 2.6 / 3.9 ms | Uses the action index, then filters by time. |
| All others (catalog, member order pages, lines/history hydration, payment, reviews, cart, audit by actor/target) | < 1.2 ms | Index-supported. |

The `Using filesort` flag on the member order pages belongs to the dependent `payment` sub-select (0.007 ms x 20 rows); the main query uses a
backward scan of `idx_shop_order_member`, so those pages are healthy.

## SQLPERF-02 decisions (each backed by an experiment in a disposable database)

| Candidate | Result | Decision |
|---|---|---|
| Deferred header join for the seller/admin order page (page the `shop_order` rows first, join header columns afterwards) | 41.6 → 15.4 ms (ranges 41.0-42.7 vs 15.1-15.6 ms); small seller 28 → 10 ms; seller with no orders unchanged; identical rows and order (checksum equal) | **Accepted** — `OrderRepository.findHeadersForSeller`; new integration test `sellerOrderListPagesInDatabaseOrderAndKeepsScopeAndStatusFilter`. |
| Index `shop_order(created_at, order_id)` plus `NO_SEMIJOIN` (early-exit scan) | Big seller 13 → 3.6 ms, but a seller with no matching orders 0.04 → **117 ms** (scans all 100k orders) | **Rejected** — a 3x typical gain is not worth a ~3000x worst case. |
| Covering index `shop_order(order_id, order_status, member_id)` for `reco.popular` | 500-630 ms, no gain | **Rejected**. |
| `JOIN_ORDER` hint for `reco.popular` | ~500 → ~380 ms (-25%) | **Rejected** — brittle and still 380 ms; it does not remove the O(lines) work. |
| Pre-deduplicated (product, member) pairs for `reco.popular` | ~1300 ms (2.5x slower), same result | **Rejected**. |
| `product_review(created_at, id)` index for `review.admin_all` | Not built | **Deferred** — admin-only, 6.7 ms at 20k reviews; revisit when reviews reach a few hundred thousand. |
| Hikari pool size / MySQL buffer pool | 1 GB buffer pool vs 128 MB: every query within noise (`deferred-bp1g.json`) | **No change** — the workload is CPU-bound and cached at this size; no demand-side pool measurement exists. |

**Open, owner decision:** the two public recommendation endpoints cost ~0.4-0.55 s of database CPU per call and scale linearly with order
lines. No semantics-preserving query change fixes this. Real mitigations (a short-TTL cache or a periodic roll-up table, a time window on
the aggregate, or a rate limit on the public endpoints) each change the documented consistent-snapshot contract of #15 or add a moving
part, so none was applied. Until one is chosen, treat these two endpoints as the capacity and abuse hot spot.

## SQLPERF-03 controlled comparison, thresholds and capacity model

Comparison: 3 runs of the previous SQL (`baseline.json`, `current-run1/2.json`) against 2 runs of the new SQL (`deferred-run1/2.json`), each
run a fresh container and reseeded data. Only `order.seller_page` changed beyond noise (-63%); the other 42 shapes moved within their
observed bands (sub-millisecond shapes ±15%, `reco.co_purchase` within its known two-mode range). `compare.py --check` passes.

Regression threshold: `baseline_max * 1.5 + 0.5 ms` per shape (see Method). This is derived from the measured noise, not an SLO; the
`100 ms` slow-query capture value in Notion remains an un-validated capture threshold.

Capacity model (assumptions: current schema; 1 order ≈ 2.5 lines, 2 history rows, 0.9 payments, 1 request row):

| Item | Measured / derived |
|---|---|
| Storage per 100k orders (data + index, five order tables) | ≈ 116 MB (~1.16 KB/order); audit log ≈ 27.6 MB per 100k rows |
| At 1,000 orders/day | ≈ 423 MB/year of order data; disk is not the constraint |
| Working set for order-history hot tables at 100k orders | ≈ 116 MB, within a 128 MB buffer pool but nearly all of it; size the pool to ≥ 512 MB when order rows pass ~300k (untested beyond the seeded size) |
| Recommendation endpoints | ~0.5 s per call today; ≈ 1 s per call at 500k lines, ≈ 2.5 s at 1.25M lines (linear); one database core sustains roughly 2 calls/s at today's size |
| Connection pool | Hikari default 10 vs MySQL `max_connections` 151: not the bottleneck; long queries (recommendations, seller list) hold a connection for their whole duration |
| Recalibration | Re-run `capture.py` monthly or after schema/query changes and compare with `compare.py --check`; watch the `DbPoolSaturated` alert from #19 |

## Not covered

- No application-level load test was run (k6 workloads were not re-run; historical numbers are context only). Lock-duration and Hikari
  wait were not measured under concurrency.
- The dataset is synthetic; real skew, `member`/`product` growth and MySQL statistics on a production host may differ. Numbers come from
  one machine (Docker Desktop, shared CPU).
- `EXPLAIN ANALYZE` was not run for mutating statements (plain `EXPLAIN` only) or for the embedding/FAQ tables.
- Full backend regression was not run for this change; targeted: `OrderStatusIntegrationTest` 7/7 and `OrderServiceQueryCountIntegrationTest` 3/3.
- No independent review.
