# Task 038 — Phase 3 k6 load / stress suite [Critical-adjacent]

- Baseline: `advanced-v2` @ `ccf62b9`, branch `feature/phase3-k6-suite` (isolated git worktree, because another session was editing the main checkout for #15). Implementer: Claude Code. Reviewer: independent fresh-context read-only agent (not Codex).
- Scope: k6 suite with the three standard workloads from `docs/project-state.md` Phase 3 item 3 (normal, deadlock stress, sold-out) plus a hot-coupon workload (follow-up of task 036), with database reconciliation after each run. Out of scope: Redis-enabled stock, soak/spike profiles, multi-instance, CI wiring, capacity claims.
- Risk: the harness is test tooling, but it produced one production change (error mapping on the order path), so that part was treated as Critical and independently reviewed.

## Requirements
1. Reproducible with one command; never touches the compose MySQL/Redis; cleans up its container.
2. Pass/fail decided by correctness invariants proven in the database (no oversell, exact quota, stock == initial - sold, orders committed == HTTP 200s), not by latency thresholds. Latency is recorded as a baseline.
3. Deadlock claims backed by InnoDB counters, not by log greps that a retry can hide.
4. Order latency excludes setup traffic.

## Outcome
- All four workloads pass; numbers and method in `bench/PHASE3-K6.md`, raw evidence in `bench/phase3/`.
- **Defect found by `soldout`:** 9/600 attempts answered HTTP 500 at the sell-out edge (data correct, contract wrong): the stored procedure's stock SIGNAL (SQLSTATE 45000 / 1644) hit the generic `DataAccessException` handler. Fixed in `GlobalExceptionHandler` (409 `商品庫存不足`), unit-tested (`GlobalExceptionHandlerTest`, 2 tests). Before the fix the same workload failed with 9 x 500; after it, 0 x 500 in 4 full runs and 2 repeats.
- Harness self-corrections found from the first results: the package step tripped the JaCoCo gate (`-Djacoco.skip=true`); setup registrations polluted the built-in latency metric (added `order_latency_ms`); a 100/s arrival rate sits at this machine's saturation knee (74.9-95.5 achieved, hundreds of dropped iterations), so `normal` defaults to 50/s.

## Verification
- `mvn clean test`: **227/227**, JaCoCo gate PASS (225 baseline + 2 new), run on the same code before it was moved into the isolated worktree; the worktree copy is re-verified by a targeted compile + `GlobalExceptionHandlerTest` run.
- `python bench/run-phase3-suite.py`: PASS x 4 full runs (final recorded in `bench/phase3/run-output.txt`) + 2 extra sold-out-only runs.

## Independent review (read-only, fresh context) — verdict PASS, no must-fix
Part A (the fix): mapping is correct and narrow (only `sp_decrease_stock` can SIGNAL); rollback, `@Retryable` and Redis compensation are unchanged because the mapping happens at the controller advice; the only caller of the procedure is `OrderTransactionService` (order and cart checkout are both correctly 409). Accepted residual: a future SIGNAL with the default errno would be misclassified; a soft-deleted/missing product now also answers "商品庫存不足" 409 (the procedure's message covers both).
Part B (the harness), should-fix items applied: (1) a sold-out run can pass without exercising the fix -> k6 counts the procedure-path 409 separately (9 in every sold-out run); (2) environment inherited from the caller's shell -> DB/Redis settings pinned; (3) log grep cannot prove zero deadlocks -> InnoDB `lock_deadlocks`/`lock_timeouts` deltas, asserted enabled (verified default-enabled on MySQL 8.0). Not applied (optional): per-member coupon limit stress, NULL discount guard (column is NOT NULL), readiness-wait race (covered by the retry loop).

## Known follow-ups
- Redis-enabled run (`STOCK_REDIS_ENABLED=true` with a disposable Redis), cart-checkout and cancel-under-load workloads, per-member coupon stress, multi-run statistics (median/range) for the latency table, CI wiring, Hikari pool-size experiment (the constant 9 race-path 409s suggests the pool bound; unproven).
- Not a Codex review.
