# Phase 3 k6 load / stress suite

`bench/phase3-suite.js` (k6 script, four workloads) driven by `bench/run-phase3-suite.py` (infrastructure + database
reconciliation). Raw evidence of the recorded run: `bench/phase3/` (k6 summaries/logs and `run-output.txt`).

```bash
python bench/run-phase3-suite.py [--skip-build] [normal deadlock soldout coupon]
```

The runner starts a disposable `mysql:8.0` container (port 3317, schema from `backend/DB/*.sql`), builds and starts the
backend jar on port 8081 with Redis stock reservation off, runs each workload, and after each one reconciles the
database with SQL. It pins every DB/Redis setting, so it cannot touch the compose MySQL (3306) or a real Redis, and it
removes the container when done. Requires Docker, Java 21, Maven and k6 on PATH. Exit code 0 only if every check passes.

## Workloads and pass criteria

| Workload | Load | What must hold |
|---|---|---|
| `normal` | 50 orders/s constant arrival rate, 30 s, single item over two unlimited products | all 200, 0 5xx, orders committed == 200 answers, stock == initial - sold |
| `deadlock` | 40 VUs, 30 s, two-item orders in **opposite item order** on the same two products | same, plus InnoDB `lock_deadlocks` / `lock_timeouts` counter delta == 0 |
| `soldout` | 600 attempts by 60 VUs against a product with **200** units | exactly 200 successes, every other attempt 409, 0 5xx, ending stock 0 (no oversell, no undersell) |
| `coupon` | 400 attempts by 60 VUs naming one hot coupon with quota **100** | exactly 100 redemptions, `used_count` == orders carrying the coupon == `sum(coupon_member_usage)`, price 900 / discount > 0 on each, others 409 |

Deadlocks are proven with InnoDB's own counters (`information_schema.INNODB_METRICS`, asserted enabled). Grepping the
application log is not enough: a deadlock that `@Retryable` retried and hid is never logged as "Deadlock found".

## Recorded run (2026-09-24, one machine, single run per workload)

Windows 11 host running k6, the Spring Boot jar (Java 21, Hikari default pool of 10) and a MySQL 8 container in Docker
Desktop, all on the same machine. These are baseline numbers for regression comparison on this machine, not capacity
claims for any other environment. Latency is `POST /api/orders` only (setup registrations are excluded).

| Workload | Orders/s | 200 / 409 / 5xx | avg | p50 | p95 | p99 | max | InnoDB deadlocks / timeouts |
|---|---|---|---|---|---|---|---|---|
| normal (50/s target) | 46.8 | 1501 / 0 / 0 | 62 ms | 21 ms | 352 ms | 513 ms | 797 ms | 0 / 0 |
| deadlock (40 VUs) | 55.9 | 1822 / 0 / 0 | 665 ms | 642 ms | 894 ms | 939 ms | 995 ms | 0 / 0 |
| soldout (200 units) | 115.9 | 200 / 400 / 0 | 350 ms | 85 ms | 1019 ms | 1055 ms | 1066 ms | 0 / 0 |
| coupon (quota 100) | 84.3 | 100 / 300 / 0 | 410 ms | 131 ms | 1379 ms | 1401 ms | 1423 ms | 0 / 0 |

Every reconciliation check passed (see `bench/phase3/run-output.txt`). Latency in `deadlock` is high by construction:
40 closed-loop VUs all need the same two product rows, so throughput is capped by row-lock serialization (~56 orders/s
here) rather than by deadlocks. Repeat runs vary; earlier full runs of the same code gave 74.9-95.5 orders/s at a 100/s
target, i.e. this machine saturates near 75-95 orders/s and queueing (multi-second latency, hundreds of dropped
iterations) appears above that, which is why `normal` defaults to 50/s for a stable baseline.

## Defect found and fixed by the `soldout` workload

First run, before the fix: 200 sold, no oversell, stock reconciled - but **9 of 600 attempts returned HTTP 500**. The
up-front stock check reads a snapshot; a buyer racing the sell-out passes it, then `sp_decrease_stock` finds too little
stock and SIGNALs SQLSTATE 45000 / error 1644, which the generic `DataAccessException` handler turned into
`500 DB_ERROR`. Data was correct, the client contract was wrong (a plain "sold out" answer became a server error).
`GlobalExceptionHandler` now maps that exact signal (the only SIGNAL in the schema) to `409 商品庫存不足`, covered by
`GlobalExceptionHandlerTest`. After the fix: 0 5xx in all four post-fix full runs and in 2 further repeated sold-out runs. The run counts
these procedure-path 409s separately (`orders_conflict_409_stock_race`); it was 9 in every sold-out run, consistent with
the last in-flight transactions bounded by the connection pool (10) all passing the check before the final unit sold -
an inference from the number, not something separately proven.

## Not covered

Redis-enabled stock reservation (`STOCK_REDIS_ENABLED=true`) is not exercised; a per-member coupon limit is not stressed
(only the global quota is); cart-checkout and cancel-under-load are not driven; no multi-instance backend; no soak or
spike profile; single run per workload for the latency table; not a Codex review.
