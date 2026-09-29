# 065 — Phase 3.3 #21 SEC-03: secrets inventory, runtime least privilege, and a Redis stock-sync defect found on the way

Date: 2026-09-29 (Asia/Taipei). Executor: Claude Code. Branch: `advanced-v2`. Scope source: `056` (SEC-03, gap G4).

## What changed

- `scripts/mysql-init/10-app-user.sh` (new, idempotent): creates/re-syncs MySQL user `esun_app` with `SELECT, INSERT, UPDATE, DELETE, EXECUTE` on the application
  schema only. It is mounted into `/docker-entrypoint-initdb.d`, so it runs on first start of an empty data directory, and it doubles as the rotation/retrofit step.
- `docker-compose.prod.yml`: a one-shot `migrate` service (Flyway CLI 11.x, root, the same versioned SQL under `backend/src/main/resources/db/migration`) creates the
  schema; the backend depends on `service_completed_successfully`, runs as `esun_app`, and starts with `FLYWAY_ENABLED=false`, so it holds no DDL credential.
  `APP_DB_PASSWORD` is a new required variable (`.env.prod.example`).
- `docs/security/secrets-and-rotation.md`: inventory (name, consumer, storage, rotation trigger, effect), rotation and revocation procedures, residual risk.

## Verification (local run, throwaway values, 2026-09-29)

- Empty database: `migrate` exited 0 (Flyway 11.20.3, "1 - baseline schema"), the backend became healthy as `esun_app`.
- `SHOW GRANTS`: only `USAGE` on `*.*` and the five data privileges on the schema. `DROP`, `CREATE`, `ALTER`, `TRUNCATE` are refused for `esun_app`. The backend
  container's `DB_PASSWORD` differs from the root password and contains no Flyway credentials.
- Full flow through Caddy with that user: register, promote seller (root SQL, the only non-API step), product create, address, order (stock 10 → 8, price 398.00),
  idempotent replay returns the same order id, the seller order list (the deferred-join query from #20) shows the order, seller confirm, buyer cancel restores
  stock (→ 10), audit rows and status history written.
- Rotation drill (see `docs/security/secrets-and-rotation.md`): `APP_DB_PASSWORD` and `JWT_SECRET` rotated on a running stack; old DB password rejected, new accepted, backend
  healthy, root unaffected; a token issued before the JWT rotation → 401, fresh login → 200. A first attempt with a shell path-conversion mistake skipped the database step and
  reproduced the documented failure mode (backend HTTP 500 until the step was run), then recovered.

## Defect found and fixed: Redis stock counters were never synchronised after startup

Running the full flow exposed that, with `STOCK_REDIS_ENABLED=true` (the B2 production default), Redis counters were preloaded only at startup:

1. A product created afterwards had no counter; its first order latched the whole process to DB-only mode (`shop_stock_cache_degraded` = 1, so the #19
   `StockCacheDegraded` alert would fire on normal operation) and a later cancel logged an ERROR ("Reservation keys missing during compensation").
2. **Customer-visible:** sell a product out, restock it through the API, and the next order returned **409** because Redis still said 0 — reproduced live
   (DB 5, Redis 0) — until the next restart.

Fix: after the owning transaction commits, `ProductService` seeds a new product's counter (`StockCacheService.seed`, `SETNX`) and applies restocks
(`StockCacheService.increase`: `INCRBY`, or seed from the database when the counter is missing); single and bulk restock are covered; any Redis failure latches DB-only mode
like a failed reservation. New `RedisProductSyncIntegrationTest` (real MySQL + Redis, 4 cases): seeded product and first order without latching, restock after sell-out,
bulk restock, missing counter seeded from the database. A mutation check (removing the restock sync) fails the test (`expected "5" but was "0"`). Live re-run of the
reproduction after rebuilding the image: counter seeded (3), first order 200 with gauge 0, restock DB 5 / Redis 5, next order 200, no ERROR in the log.

## CI-only failure caused by the new test, and how it was found

The full suite passed locally (Windows/Java 21) and in a Linux/Java 17 container, but `backend-test` failed on GitHub for two commits. Run logs need a signed-in viewer, so a CI step now
publishes failing test classes as annotations (readable without signing in); the next run named the cause: the pre-existing `AuditLogIntegrationTest.auditRowAndBusinessChangeRollBackTogether`
adds `CHECK (action <> 'PRODUCT_RESTOCK')` to the **shared** `audit_log` table, which MySQL validates against existing rows, so it fails whenever another test class has already written a
restock audit entry. The new Redis sync tests restock, and the runner happened to execute them first. Reproduced locally with `-Dsurefire.runOrder=reversealphabetical`; fixed by scoping
the constraint to the product under test (`... OR target_id <> '<id>'`); both classes then pass in the adverse order. Lesson recorded: a test that passes only in one class order is a defect in the test.

## Observation for the owner (not changed)

A buyer whose only order was **cancelled** can still post a product review (201): the verified-purchase check counts orders regardless of status.

## Not covered

- Root credentials remain in `.env.prod` and in the MySQL and `migrate` containers. Retrofitting an existing MySQL volume needs the script run once by hand (documented).
- The migration path was verified from an empty schema only; adopting an existing legacy database still needs the explicit offline adoption procedure from task 053, which the
  one-shot CLI does not perform.
- Rotation of `REDIS_PASSWORD`, `PAYMENT_CALLBACK_SECRET` and ECPay values, and automatic expiry/alerting for secret age, were not exercised.
- The Flyway image is pinned to the `11-alpine` tag, not a digest.
- Redis sync covers create and restock; soft delete and price/name updates intentionally do not touch counters (the database rejects deleted products and a rejected reservation is compensated).
- Known residual races in the Redis sync (found by the independent review, not fixed): an order that arrives between a product's commit and its post-commit seed still sees a missing
  counter and latches DB-only mode (narrow window); two concurrent restocks of a product whose counter is *missing* can double-count one of them (the seed already includes the
  other); a transient Redis error while seeding or syncing latches DB-only mode until reconciliation, by design. The 60 s stock audit logs any drift that results.
- Independent review findings applied afterwards: the two init scripts are now git-executable (Linux sources non-executable init scripts, and `exit 0` in the optional backup-user script
  would have ended the MySQL entrypoint mid-initialisation), and grants escape the `_` wildcard in the schema name.
