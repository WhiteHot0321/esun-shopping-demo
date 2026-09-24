# Phase 3 k6 suite handoff

Goal: k6 load/stress suite (normal, deadlock, sold-out, hot-coupon) with DB reconciliation; correctness invariants decide pass/fail.

Changed: `bench/phase3-suite.js`, `bench/run-phase3-suite.py`, `bench/PHASE3-K6.md`, `bench/phase3/*` (k6 summaries/logs/run output), `backend/.../exception/GlobalExceptionHandler.java` (stock-procedure SIGNAL 45000/1644 -> 409 instead of 500), `GlobalExceptionHandlerTest`, `docs/tasks/038-k6-load-suite.md`.

Validated: `mvn clean test` 227/227 + JaCoCo PASS; suite PASS x 4 full runs + 2 sold-out repeats; the sold-out workload failed before the fix (9 x HTTP 500 out of 600, no oversell) and passes after; InnoDB deadlock/timeout counters 0 in every workload; independent read-only review PASS (should-fix items applied).

Not proven: Redis-enabled stock path, per-member coupon stress, cart-checkout/cancel under load, multi-run latency statistics, multi-instance/soak; the "9 race-path 409s = pool bound" explanation is an inference; not a Codex review.

Risks: latency numbers are single-run on one machine (k6 + backend + MySQL share it), saturation near 75-95 orders/s; a future SIGNAL with the default errno in the schema would be mapped to "out of stock".

Next: Redis Lua stock reservation + Redis-enabled k6 run, or GitHub Actions CI/CD (Phase 3 items 2 and 5 in `docs/project-state.md`); optionally a Hikari pool-size experiment to confirm the race-path bound.
