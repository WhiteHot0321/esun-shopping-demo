# Results: concurrent order-creation load test (Phase 1 item 3.3)

Both runs used the same k6 script (`bench/order-load-test.js`, VUS=40,
DURATION=45s) against a freshly-reset database (`docker compose down -v &&
up -d`, so `P001`=5, `P002`=50, `P003`=20 every time), one backend process
at a time, same machine, same JVM (Java 21 / Spring Boot 3.3.5). Raw k6
output: `bench/k6-prefix.txt` / `bench/k6-postfix.txt`. Raw backend stdout
(used for the deadlock/lock-timeout grep below): `bench/backend-prefix.log`
/ `bench/backend-postfix.log`.

- **Pre-fix** = commit `36185d9` (one commit before the 3.3 fix; items
  processed in raw request order, 2n SELECT per item).
- **Post-fix** = `advanced-v2` HEAD (`11521c8`, includes `a197192`: items
  fetched once into a Map and locked/decremented in `productId` order).

## Headline numbers

| Metric | Pre-fix (`36185d9`) | Post-fix (`advanced-v2` HEAD) |
|---|---:|---:|
| Total requests | 16,800 | 16,789 |
| Throughput | 372.57 req/s | 372.27 req/s |
| HTTP 200 (order created) | 20 | 20 |
| HTTP 409 (business "insufficient stock") | 16,634 | 16,620 |
| HTTP 500 (DB-layer error, ambiguous bucket) | 146 | 149 |
| `http_req_duration` p95 (all requests) | 5.67ms | 5.3ms |
| `http_req_duration` p99 (all requests) | 13.59ms | 23.74ms |
| `http_req_duration` p95 (2xx only) | 1.14s | 1.12s |
| `http_req_duration` p99 (2xx only) | 1.16s | 1.14s |

The p95/p99 "all requests" numbers are dominated by the ~99% of requests
that get an instant 409 (stock already gone) or 500; the "2xx only" row is
the handful of requests that actually made it through the full
insert-detail + decrement-stock path while under lock contention, which is
why those are ~1.1s instead of milliseconds.

## Disambiguated 500s (the real comparison)

As documented in `bench/README.md`, `GlobalExceptionHandler.handleDb()`
returns the same HTTP 500 + message for every `DataAccessException`, so the
k6-visible "500" bucket above is not itself the deadlock count. Grepping
each run's captured backend log for the actual exception text gives:

| Cause (from backend log) | Pre-fix count | Post-fix count |
|---|---:|---:|
| InnoDB deadlock ("Deadlock found when trying to get lock") | **144** | **148** |
| Lock wait timeout ("Lock wait timeout exceeded") | 0 | 0 |
| Stock-race SIGNAL from `sp_decrease_stock` ("庫存不足或商品不存在", TOCTOU) | 2 | 1 |
| **Total (matches k6's 500 count)** | **146** | **149** |

Commands used:
```powershell
Select-String -Path bench\backend-prefix.log  -Pattern "Deadlock found" | Measure-Object | % Count   # 288 (2 lines/instance) -> 144 instances
Select-String -Path bench\backend-postfix.log -Pattern "Deadlock found" | Measure-Object | % Count   # 296 (2 lines/instance) -> 148 instances
Select-String -Path bench\backend-prefix.log  -Pattern "UncategorizedSQLException" | Measure-Object | % Count  # 2
Select-String -Path bench\backend-postfix.log -Pattern "UncategorizedSQLException" | Measure-Object | % Count  # 1
```

## Finding: the fix did NOT reduce total deadlock count in this workload — and why that's expected, not a bug in the fix

144 deadlocks pre-fix vs. 148 post-fix is not a meaningful difference (well
within run-to-run noise for this workload). This looks like a null result
at first glance, but it is explained by which deadlock *mechanism*
dominates in this schema, and it doesn't contradict the fix being correct:

- The `productId`-sort fix removes exactly one deadlock class: two
  concurrent orders that both touch the **same two rows in opposite order**
  (txn A locks P002 then P003; txn B locks P003 then P002 -> classic
  lock-order-inversion deadlock). That is exactly the scenario this k6
  script is built to trigger (half the VUs use `[P002, P003]`, half use
  `[P003, P002]`), and post-fix, both groups now lock in the same
  `productId` order — so this specific class should be gone after the fix.
- But `order_detail.product_id` has `fk_order_detail_product REFERENCES
  product(product_id)`. InnoDB takes an implicit **shared lock** on the
  parent (`product`) row when inserting the referencing child
  (`order_detail`) row, before `sp_decrease_stock`'s `UPDATE` later
  requests an **exclusive** lock on that same row. When many concurrent
  transactions all touch the *same single product row* this way (S-lock via
  FK insert, then want to upgrade to X-lock via the UPDATE), you get a
  classic shared-lock-upgrade deadlock — on ONE row, with no dependency on
  which order multiple rows are processed in. This mechanism is exactly the
  "Known residual limitation" already called out in `bench/README.md`
  before these numbers were collected.
- With only two products in the basket and both present in every request
  (just reordered), essentially every deadlock observed in both runs traces
  back to this single-row FK/UPDATE lock-upgrade conflict rather than to
  the two-row crossed-lock-order pattern the fix targets. That conflict is
  present identically before and after the fix, which is why the totals
  land in the same range (144 vs 148).

**This does not mean commit `a197192` accomplished nothing.** It removed a
real, distinct deadlock cause (cross-order multi-row locking) that would
dominate in baskets with 3+ overlapping products ordered differently across
concurrent requests — a 2-item, always-the-same-two-products workload like
this one just isn't the scenario where that particular class shows up as
the majority of the total. It happens to be the dominant deadlock mechanism
*in this specific 2-product benchmark*, not evidence the productId-sort fix
is ineffective in general.

## What would show the fix's targeted effect more clearly (not done here, out of scope)

A follow-up benchmark with baskets of 3+ distinct overlapping products,
where cross-order permutations across concurrent transactions are more
numerous relative to the fixed single-row FK/UPDATE contention, would
better isolate the crossed-multi-row-lock class this fix targets. Doing
that here would mean modifying seed data / stock levels beyond what this
task's `bench/`-only scope allows, so it's left as a follow-up idea rather
than attempted.

## Residual deadlocks after the fix — expected, not a regression

Per the task's known-limitation note: sorting by `productId` cannot remove
the FK-driven shared-lock vs. exclusive-lock conflict described above. The
~148 post-fix deadlocks are consistent with that residual layer, not with
the fix failing. Resolving it would need either avoiding the implicit
FK shared lock (e.g., locking rows with `SELECT ... FOR UPDATE` up front
instead of relying on insert-order FK checks) or an application-level retry
on `CannotAcquireLockException` — both out of scope for this benchmarking
task and, per the task brief, expected to land in a separate, not-yet
scheduled Phase 2.5 retry-mechanism task.

## Environment notes / caveats affecting these numbers

- Docker Desktop on this machine failed to start at all until the "Docker
  AI" / Model Runner feature was disabled (see main report) — unrelated to
  the app, but worth knowing if these numbers are ever reproduced on a
  different machine where that isn't an issue.
- MySQL was published on host port **3308**, not the default 3306, because
  a native `mysqld.exe` was already bound to 3306 on this machine (and 3307
  was also occupied by something not identifiable via `Get-NetTCPConnection`
  — plausibly a Windows-reserved dynamic port). `bench/README.md` step 2
  documents `DB_PORT=3308`; adjust if free ports differ elsewhere.
- Both backend processes were started with `SPRING_DATASOURCE_URL`
  overriding the query string to add `allowPublicKeyRetrieval=true` — MySQL
  8's default `caching_sha2_password` auth plugin fails with "Public Key
  Retrieval is not allowed" under `useSSL=false` without it. This is a
  pre-existing gap in `application.yml` unrelated to item 3.3; it was
  worked around via an env var rather than edited, per this task's
  restriction to `bench/`-only changes.
- Only `P002`/`P003` stock (50 + 20 = 70 units) is available per DB reset,
  so successful 200s cap out at ~20 orders per run (each order buys 1 of
  each => bounded by the smaller stock, `P003`=20) before the 409 bucket
  dominates for the rest of the 45s window — this is expected and by
  design (see script comments).

## Follow-up: 3-item overlapping basket (multi-row crossed-lock isolation)

The section above ended with a proposed follow-up (see "What would show the
fix's targeted effect more clearly"): the original 2-product benchmark's
deadlocks were almost entirely the single-row FK-insert-vs-UPDATE
lock-upgrade class, which the `productId`-sort fix does *not* touch, so the
144-vs-148 result could not say anything about the multi-row crossed-lock
class the fix *does* target. This section runs that follow-up.

### Methodology differences from the original run

- New script: `bench/order-load-test-multiitem.js` (does not replace or
  modify `order-load-test.js`). Every request's basket now contains **all
  three** seeded products (`P001`, `P002`, `P003`), quantity 1 each, instead
  of 2 products. VUs are split into 6 groups by `__VU % 6`, one group per
  permutation of `[P001, P002, P003]` (3! = 6 orderings), each VU sticking to
  its assigned order for the whole run — the same idea as the original
  script's even/odd `[P002,P003]`/`[P003,P002]` split, just with 6 buckets
  instead of 2, and with 3 rows always in play instead of 2. This makes any
  two concurrent transactions far more likely to be touching all 3 rows in a
  *different* relative order pre-fix (and the *same* sorted order post-fix),
  which is exactly the scenario the productId-sort fix targets.
- Everything else — same `docker compose down -v && up -d` reset, same
  worktree-based pre/post-fix comparison, same VUS=40/DURATION=45s, same
  deadlock/lock-timeout log-grep methodology (HTTP 500 is still an ambiguous
  bucket per the "Limitation" section above) — is unchanged from the
  original run.

### Runtime-only stock adjustment (not committed to seed data)

`backend/DB/02_data.sql` seeds `P001` at only 5 units. With 3 fully-
overlapping products and 40 concurrent VUs, `P001` would sell out within
single-digit milliseconds of the run starting, collapsing the rest of the
45s window into fast 409s and defeating the purpose of a concurrency
benchmark (this is exactly why the original script deliberately avoided
`P001` and used only `P002`/`P003` — see that script's header comment). This
follow-up needs all three products to have a comparable, generous stock
level so the concurrent-overlap window lasts long enough to matter.

Per the task's constraint of staying `bench/`-only, `backend/DB/02_data.sql`
was **not** modified. Instead, after each `docker compose down -v && up -d`
reset (and after confirming MySQL finished running its init scripts), a
one-off runtime SQL statement was run directly against the freshly-seeded
database, via the native `mysql` client:

```powershell
& "C:\AppServ\MySQL\bin\mysql.exe" -h127.0.0.1 -P3308 -uroot -p123456 esun_shop -e "UPDATE product SET quantity=200 WHERE product_id IN ('P001','P002','P003');"
```

This is a plain `UPDATE` against already-seeded rows — it does not touch
schema, stored procedures, or the seed script itself, and is lost the next
time the volume is wiped (`docker compose down -v`), same as any other
in-memory/runtime state for this benchmark. It was re-run identically before
both the pre-fix and post-fix k6 runs so both start from the same stock
level (200/200/200). Verified with `SELECT product_id, quantity FROM
product;` after each `UPDATE` before starting the corresponding backend.

### Headline numbers

| Metric | Pre-fix (`36185d9`) | Post-fix (`advanced-v2` HEAD) |
|---|---:|---:|
| Total requests | 16,466 | 16,537 |
| Throughput | 365.79 req/s | 366.93 req/s |
| HTTP 200 (order created) | 200 | 200 |
| HTTP 409 (business "insufficient stock") | 14,918 | 15,124 |
| HTTP 500 (DB-layer error, ambiguous bucket) | 1,348 | 1,213 |
| `http_req_duration` p95 (all requests) | 29.14ms | 27.38ms |
| `http_req_duration` p99 (all requests) | 55.28ms | 47.83ms |
| `http_req_duration` p95 (2xx only) | 137.06ms | 122.24ms |
| `http_req_duration` p99 (2xx only) | 963.76ms | 953.86ms |

Raw k6 output: `bench/k6-prefix-multiitem.txt` / `bench/k6-postfix-multiitem.txt`.
Raw backend stdout (used for the deadlock/lock-timeout grep below):
`bench/backend-prefix-multiitem.log` / `bench/backend-postfix-multiitem.log`.

200 successful orders per run this time (vs ~20 in the original 2-product
run) because the runtime stock bump (200 units/product) gives a much wider
concurrency window before any single product sells out — consistent with
"more overlapping in-flight transactions" being the whole point of this
follow-up.

### Disambiguated 500s

Same grep methodology as the original run:

```powershell
Select-String -Path bench\backend-prefix-multiitem.log  -Pattern "Deadlock found" | Measure-Object | % Count   # 2690 (2 lines/instance) -> 1345 instances
Select-String -Path bench\backend-postfix-multiitem.log -Pattern "Deadlock found" | Measure-Object | % Count   # 2424 (2 lines/instance) -> 1212 instances
Select-String -Path bench\backend-prefix-multiitem.log  -Pattern "Lock wait timeout exceeded" | Measure-Object | % Count   # 0
Select-String -Path bench\backend-postfix-multiitem.log -Pattern "Lock wait timeout exceeded" | Measure-Object | % Count   # 0
Select-String -Path bench\backend-prefix-multiitem.log  -Pattern "UncategorizedSQLException" | Measure-Object | % Count   # 3
Select-String -Path bench\backend-postfix-multiitem.log -Pattern "UncategorizedSQLException" | Measure-Object | % Count   # 1
```

| Cause (from backend log) | Pre-fix count | Post-fix count |
|---|---:|---:|
| InnoDB deadlock ("Deadlock found when trying to get lock") | **1,345** | **1,212** |
| Lock wait timeout ("Lock wait timeout exceeded") | 0 | 0 |
| Stock-race SIGNAL from `sp_decrease_stock` ("庫存不足或商品不存在", TOCTOU) | 3 | 1 |
| **Total (matches k6's 500 count)** | **1,348** | **1,213** |
| Deadlock rate (deadlocks / total requests) | 8.17% | 7.33% |

### Finding: a real, but modest, reduction this time — not a clean confirmation either

Deadlocks dropped from 1,345 to 1,212 — a **9.89% relative reduction**
(0.84 percentage points, 8.17% -> 7.33% of all requests). This is a
different result from the original 2-product run (144 -> 148, effectively
flat/noise), and it moves in the direction the fix's mechanism predicts. But
it should be read honestly, not as a clean confirmation:

- **What changed as predicted:** with 6 different submission orders across
  3 fully-overlapping rows in flight simultaneously pre-fix, and all 6
  converging to one sorted order post-fix, the two-/three-row
  crossed-lock-order deadlock class that `a197192` targets should shrink.
  The ~10% drop is consistent with that class actually shrinking here, in a
  workload built specifically to give it more opportunity to dominate than
  the original 2-product test did.
- **What did not change as much as the "clean isolation" framing hoped:**
  the reduction is real but far from eliminating the deadlock problem —
  7.33% of all requests still deadlock post-fix, barely better than 8.17%
  pre-fix. Two things plausibly explain why the multi-row crossed-lock class
  did not shrink to near-zero the way it arguably should have if it were the
  *only* mechanism at play:
  - The **single-row FK-insert-vs-UPDATE lock-upgrade** deadlock (documented
    in `bench/README.md`'s "Known residual limitation" section) is present
    on *every* row now, not just one pair — with 3 overlapping products
    instead of 2, there are 3 opportunities per transaction for that
    same-row S-lock-then-X-lock-upgrade conflict instead of 2, so this
    unaffected mechanism's absolute contribution likely grew roughly in
    proportion, diluting the visible effect of the crossed-lock fix in the
    aggregate count.
  - Both runs still show a very high absolute deadlock rate (>7% of all
    requests, versus <1% in the original 2-product test), which itself
    signals that raising the item count from 2 to 3 increased *total* lock
    contention (of all kinds) faster than the productId-sort fix could
    offset for the specific class it targets — i.e., this workload is
    considerably more lock-contentious overall, not just differently
    shaped.
  - It's also possible the 6-way permutation split, by design, still leaves
    each *specific* pair of orderings only sharing VUs 1/6 of the time,
    so the crossed-order overlap window per pair is smaller than the
    original test's 50/50 split gave for its one pair — a design trade-off
    of covering more permutations vs. concentrating VUs on head-to-head
    opposite orderings. A version with only 2 groups using fully-reversed
    3-item orders (e.g. `[P001,P002,P003]` vs `[P003,P002,P001]`) might show
    a larger effect and would be a reasonable next iteration.

**Bottom line, stated plainly and without over-claiming:** this follow-up
shows the fix has a real, measurable, correctly-directed effect (~10%
relative deadlock reduction) that the original 2-product benchmark could not
detect at all — so the original "null result" was indeed a benchmark
coverage gap, not evidence the fix does nothing, as hypothesized. But the
effect size is modest, not dramatic, because the FK-driven single-row
lock-upgrade deadlock (which the fix does not and was never intended to
address) still dominates the total count even more here than before, simply
because it now has 3 rows per transaction to trigger on instead of 1-2. A
full fix for the deadlock rate as a whole would additionally need to address
that separate, already-documented residual limitation (e.g., explicit
`SELECT ... FOR UPDATE` locking up front, or application-level retry on
`CannotAcquireLockException` — both out of scope here, same as in the
original run).

### Environment notes for this follow-up

- Same machine/toolchain as the original run (Windows 11, Docker Desktop,
  MySQL 8.0 on host port 3308, k6 native binary, Java 21 / Spring Boot
  3.3.5). Docker Desktop started normally this time (no repeat of the
  "Docker AI" stale-socket issue recorded in the original run).
- A stray `java -jar target\shopping-backend-1.0.0.jar` process from an
  earlier, unrelated session was found already bound to port 8080 before the
  pre-fix backend could start (`Web server failed to start. Port 8080 was
  already in use.`). It was confirmed via `Get-CimInstance Win32_Process`
  to be exactly that leftover jar (not an unrelated service) and stopped
  with `Stop-Process -Force` before proceeding. Worth knowing if these
  numbers are ever reproduced and the backend unexpectedly fails to bind.
- `git worktree add ../esun-prefix-multiitem 36185d9` was used for the
  pre-fix checkout (a different directory name from the original run's
  `../esun-prefix`, per the task's instruction, to avoid clashing with any
  leftover state — none was found; `../esun-prefix` was not present).
