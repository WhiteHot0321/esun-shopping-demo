# MON-04 — payment failure metrics and callback deduplication

## Session gate

- Task class: Critical (payment transaction and callback concurrency).
- Baseline: `advanced-v2` @ `69517fa`; MON-01–03 and unrelated user changes were already present and must be preserved.
- Outcome: publish `shop.payments.failure` only when a verified provider callback first commits one attempt as `FAILED`.
- Allowed production scope: `PaymentCallbackService`; `PaymentRepository` may be read but its state transitions and lock order are unchanged.
- Allowed test scope: direct payment integration tests only.
- Change limit: three files. Target verification: `PaymentIntegrationTest` with JaCoCo skipped; full regression and coverage are reserved for MON-05.
- Exclusions: amount rules, signatures, provider protocol, order→payment lock order, refund semantics, distributed exactly-once machinery, Prometheus/Grafana, commit, push, merge and deploy.

## Acceptance

- A newly committed, verified provider decline increments `shop.payments.failure` once.
- Replay and genuinely concurrent duplicate callbacks do not increment it again.
- A rolled-back transition does not increment it.
- Late success and `REFUND_REQUIRED` transitions do not add failure counts.
- Metrics contain no payment, provider, member or token identifiers and registry failure cannot alter the payment result.
- Existing payment/cancellation race coverage remains green.

## Roles and risk

- Implementer: Codex.
- Independent reviewer: Claude Code, read-only, after targeted verification.
- Main risk: incrementing before commit would publish a failure that does not exist in the payment ledger; therefore observation is registered on transaction `afterCommit` after the existing CAS succeeds.

## Result

Completed 2026-09-28 Asia/Taipei on `advanced-v2` from baseline `69517fa`; uncommitted, with all pre-existing MON-01–03 and user changes preserved.

- `PaymentCallbackService` registers `shop.payments.failure` only after the existing `INITIATED -> FAILED` CAS succeeds and increments it from transaction `afterCommit`. Replays, concurrent duplicates, rollback, late success and refund-required paths do not register another count. The metric has no tags and registry failures are logged without changing payment outcomes.
- `PaymentIntegrationTest` adds explicit counter-delta assertions for one new decline, replay, eight concurrent duplicates, an outer transaction rollback, late success and `REFUND_REQUIRED`. The existing payment/cancellation race cases ran in the same class.
- First command: `mvn '-Dtest=PaymentIntegrationTest' '-Djacoco.skip=true' test` — exit 1 before Spring context, 18 errors because Docker Desktop was not running; 0 assertion failures. Docker Desktop was started and engine 29.8.0 became available. This was an environment recovery, not an application repair.
- Final targeted verification: same command — exit 0, 18/18 passed, 0 failures/errors/skips, Maven 50.586 s. JaCoCo was intentionally skipped; full suite and coverage remain MON-05.
- Independent Claude Code read-only review: **PASS**, 0 blockers. One non-blocking follow-up: a registry that actively throws is covered by static inspection rather than an executable fault-injection test; defer to MON-05.
- Repair rounds: 0. User interventions: 0. Valid review defects: 0. Scope expansions: mandatory progress synchronization only. Elapsed time: approximately 10 minutes. Token/cost and five-hour usage change: unknown.

Engineering concept: a process-local counter is an observation of committed ledger state, not the ledger itself. Registering the increment on `afterCommit` prevents telemetry from claiming a decline that the database rolled back, while the existing row lock and conditional update provide callback deduplication without a new distributed exactly-once system.
