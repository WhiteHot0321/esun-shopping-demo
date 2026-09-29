# Secrets inventory, ownership and rotation

Names and procedures only: **no value belongs in Git, CI logs, artifacts, task records, chat or Notion.** Values live in the git-ignored `.env.prod`
on the machine that runs the stack (and, for CI, in GitHub secrets/environment secrets if a deploy job is ever added). Owner for every secret
below is the repository maintainer (single owner; there is no team).

| Secret (env var) | Used by | Stored | Rotate when | Effect of rotating |
|---|---|---|---|---|
| `DB_PASSWORD` | MySQL root, the one-shot `migrate` service | `.env.prod` | suspected leak; maintainer change | Needs `ALTER USER root` in MySQL and a stack restart; **not** given to the backend |
| `APP_DB_PASSWORD` | MySQL user `esun_app` (data-only), backend runtime | `.env.prod` | suspected leak; every 90 days | Backend reconnects after a restart; see procedure |
| `REDIS_PASSWORD` | Redis, backend | `.env.prod` | suspected leak | Redis and backend restart together; the counters persist in the Redis volume and any missing counter is re-seeded from the database at startup |
| `JWT_SECRET` | backend token signing | `.env.prod` | suspected leak; every 180 days | Every issued token becomes invalid; all users must sign in again |
| `PAYMENT_CALLBACK_SECRET` | backend, HMAC of payment callbacks | `.env.prod` | suspected leak | In-flight callbacks signed with the old value are refused |
| `ECPAY_HASH_KEY`, `ECPAY_HASH_IV`, `ECPAY_MERCHANT_ID` | ECPay gateway (only when `PAYMENT_PROVIDER=ecpay`) | `.env.prod` | provider-driven; suspected leak | Rotate at ECPay first, then here, in one maintenance window |
| `GRAFANA_ADMIN_PASSWORD` | Grafana admin (monitoring overlay, loopback only) | `.env.prod` | suspected leak | Change in Grafana as well; the env var only seeds a fresh volume |
| Deploy SSH private key | operator access to a future VM | `~/.ssh` of the operator, never in the repo | operator change; suspected leak | Replace the public key in `authorized_keys`; only the public key is in `scripts/vm-bootstrap.sh` |
| Alert receiver URL/token | Alertmanager (not configured) | a host file referenced by `url_file`, never in Git | when set up | n/a yet |

## Runtime least privilege

- The backend connects as `esun_app`, granted only `SELECT, INSERT, UPDATE, DELETE, EXECUTE` on the application schema (`scripts/mysql-init/10-app-user.sh`).
  DDL is performed by the `migrate` service as root; the backend container has no root/DDL credential in its environment. Verified: `DROP`, `CREATE`,
  `ALTER`, `TRUNCATE` are refused for `esun_app`, and the full purchase flow works with that user.
- Containers run with `cap_drop: ALL` (plus the few capabilities MySQL/Redis/Caddy need), `no-new-privileges`, and the backend and Caddy have a read-only root filesystem.
- Residual risk: `DB_PASSWORD` (root) is still present in `.env.prod` and in the MySQL and `migrate` containers; anyone with the host can read it.

## Rotation procedures (Compose; run from the repository root)

```bash
C="docker compose -f docker-compose.prod.yml [-f docker-compose.tunnel.yml] --env-file .env.prod"
```

**`APP_DB_PASSWORD`** (order matters — the database must change before the backend restarts):
1. Generate a new value (hex, no quotes/backslashes) and put it in `.env.prod`.
2. `$C up -d --no-deps mysql` (recreates the container with the new env; the data volume is kept).
3. `docker exec <mysql-container> sh /docker-entrypoint-initdb.d/10-app-user.sh` (idempotent `ALTER USER`). On Git Bash set `MSYS_NO_PATHCONV=1`.
4. `$C up -d --no-deps backend`, wait for `healthy`.
Checks: the old password is rejected by MySQL, the new one is accepted, `GET /api/products/available` is 200. Doing step 4 before step 3 leaves the backend unable to connect (HTTP 500) until step 3 is done.

**`JWT_SECRET`**: put the new value in `.env.prod`, `$C up -d --no-deps backend`; tokens issued before the restart are refused (401), a fresh login works.

**`REDIS_PASSWORD`, `PAYMENT_CALLBACK_SECRET`, ECPay values**: change `.env.prod`, then recreate the affected services; for ECPay values change them at the provider first.

**Revocation of a leaked secret**: rotate it immediately using the row above; for the deploy key also remove its line from `~/.ssh/authorized_keys` on the host.

## Drill evidence (2026-09-29, local stack, throwaway values)

`APP_DB_PASSWORD` and `JWT_SECRET` were rotated on a running stack using the procedures above (values never printed): old DB password rejected, new accepted,
backend healthy and serving, root credential unaffected; a token issued before the JWT rotation returned 401 afterwards and a fresh login returned 200.
The first attempt ran step 4 before step 3 by mistake (a shell path-conversion error skipped the script) and produced exactly the failure described above,
which was then recovered by running step 3. Task record: `docs/tasks/065-phase33-21-sec03-secrets-and-least-privilege.md`.
