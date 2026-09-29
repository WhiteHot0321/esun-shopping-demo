# 060 — Phase 3.3 #18 B3: tunnel mode instead of a rented VM

Date: 2026-09-29 (Asia/Taipei). Executor: Claude Code. Branch: `advanced-v2`.

## Decision

The user decided not to rent a VM for now and to run the production Compose stack on their own
machine, exposed to the public internet through a tunnel (Cloudflare quick tunnel; Tailscale
Funnel is the stable-URL alternative). This supersedes the VM path of task 059 for the time being:
`scripts/vm-bootstrap.sh` and the deploy SSH key are kept but are not used by this path.

## What changed

- `docker-compose.tunnel.yml` (new): override that sets `DOMAIN=http://:80` so Caddy serves plain
  HTTP, and republishes Caddy only on `127.0.0.1:8088` (`ports: !override`, Compose >= 2.24). The
  tunnel terminates TLS. Nothing is published on 80/443.
- `docker-compose.prod.yml`: backend healthcheck now calls `/actuator/health/readiness` instead of
  the aggregate `/actuator/health`. The aggregate includes the optional `dependencies` group, so a
  stack with no Ollama models pulled was reported `unhealthy` although liveness and readiness were
  UP. This was a B2 defect, found here; MON-01/02 already define readiness as the DB-only gate.

## Verification (2026-09-29, real run, then torn down)

Stack started under project name `esun-tunnel` (separate volumes) with a throwaway, git-ignored
`.env.prod` and a `cloudflared` quick tunnel to `127.0.0.1:8088`.

- `docker compose ps`: mysql/redis/backend healthy; caddy the only published port, `127.0.0.1:8088`.
- Through the public `https://<random>.trycloudflare.com`: SPA `200 text/html`; `/api/products/available`
  `200`; register `200` and login `200` with `Origin` = the tunnel URL; login with a foreign `Origin`
  `403`; `/api/orders` without a token `401`.
- `/actuator/health` and `/v3/api-docs` on the public URL return the SPA fallback page (HTML), not the
  real endpoints. Management stays on the unpublished port 8081 and API docs are disabled.
- Built-in browser opened the public URL: SPA rendered, no console errors.
- Before the healthcheck fix the backend reported `unhealthy` (aggregate DOWN because
  `dependencies.ollama` had 2 missing models); after the fix it reported `healthy`.
- Torn down afterwards: tunnel process stopped, `docker compose down -v`, `.env.prod` deleted.

## Not covered

- B3 deploy pipeline (GitHub Actions deploy job, GitHub Environment approval, backup-before-deploy,
  health gate, rollback) — there is no remote host to deploy to in this mode.
- #21 host-level acceptance (firewall, SSH policy, off-host restore) — not applicable to a laptop.
- Real ECPay inbound callback — not attempted; needs ECPay credentials and callback URLs pointing at
  the tunnel origin.
- Tailscale Funnel — not tried (not installed). Quick-tunnel URLs change on every restart, so
  `CORS_ALLOWED_ORIGINS` (and any ECPay URLs) must be updated to the new origin each time.
- The tunnel only works while the machine is on and the tunnel process is running. QUIC was blocked on
  this network; `cloudflared --protocol http2` connected.
- DuckDNS domain unused in this mode.

## How to run

```bash
cloudflared tunnel --protocol http2 --url http://127.0.0.1:8088   # note the printed https origin
# copy .env.prod.example to .env.prod, fill it, set CORS_ALLOWED_ORIGINS=<that origin>, DOMAIN=http://:80
docker compose -p esun-tunnel -f docker-compose.prod.yml -f docker-compose.tunnel.yml --env-file .env.prod up -d --build
```
