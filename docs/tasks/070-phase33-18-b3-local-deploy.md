# 070 — Phase 3.3 #18 B3: local deploy script for tunnel mode

Date: 2026-10-17 (Asia/Taipei). Executor: Claude Code. Branch: `advanced-v2`.

## What changed

`scripts/deploy-tunnel.sh` (new) implements the repository-side B3 flow for the no-VM tunnel stack (task 060):

1. Validate the Compose config.
2. Encrypted pre-deploy backup via `scripts/mysql-backup.sh` (abort on failure; `BACKUP_SKIP=1` is explicit opt-out).
3. Tag the currently running backend/frontend images as `:previous`.
4. `up -d --build`.
5. Health gate: backend container `healthy` and `HEALTH_URL` (default Caddy `/`) returns 200 within `HEALTH_TIMEOUT`.
6. On gate failure, redeploy the `:previous` images (`--no-build`) and exit non-zero. `rollback` runs step 6 on demand.

Also in `1315e7e`: tunnel Redis restart loop fixed (`user: "999:999"`).

## Verification (real runs, 2026-10-17)

- Dry run prints the expected command sequence.
- Real `deploy`: encrypted backup written, images rebuilt, backend/caddy recreated, gate passed.
- Failure path with an unreachable `HEALTH_URL`: gate failed, `:previous` images redeployed, script exited 1, stack stayed healthy (SPA 200 on 127.0.0.1:8088). The rollback's own gate used the same unreachable URL, so it also reported failure; with a real URL it would pass.
- Note: the SPA fallback returns 200 for any path, so a bogus path is not a valid failure probe.

## Not covered (B3 stays partial)

- No GitHub Actions deploy job or Environment approval: there is no remote host (a laptop would need a self-hosted runner).
- Not tested against a real regression (a build that is unhealthy), only an unreachable probe.
- `:previous` is overwritten on every deploy, so only one generation back is available.
