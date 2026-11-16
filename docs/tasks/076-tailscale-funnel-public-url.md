# 076 — Tailscale Funnel as the stable public URL for tunnel mode

Decision (2026-10-26, owner): use **Tailscale Funnel** (personal account) for the stable public hostname. A Cloudflare *named* tunnel was evaluated first and dropped: it needs a domain managed by Cloudflare DNS (no free one is reliable), and a free `*.duckdns.org` name cannot be delegated to Cloudflare. Quick tunnels (`cloudflared tunnel --url ...`) remain a no-account fallback but their URL changes on every restart.

No repository code is needed: `docker-compose.tunnel.yml` already serves plain HTTP from Caddy on `127.0.0.1:8088`, and Funnel terminates TLS. This task only records the runbook.

## Setup (owner machine, personal Tailscale account — the school tailnet blocks Funnel)
1. Install Tailscale and log in with the personal account; in the Tailscale admin console enable **HTTPS certificates** (DNS page) and allow Funnel for the device (ACL `nodeAttrs` with `funnel`, or the one-click prompt `tailscale funnel` prints).
2. Fill the git-ignored `.env.prod` (see `.env.prod.example`): `DOMAIN=http://:80`, and after step 3 set `CORS_ALLOWED_ORIGINS=https://<machine>.<tailnet>.ts.net`.
3. `tailscale funnel --bg 8088` — prints the public `https://…ts.net` origin; stop with `tailscale funnel --https=443 off` (or `tailscale funnel reset`).
4. `bash scripts/deploy-tunnel.sh deploy` (backup, health gate on `127.0.0.1:8088`, rollback on failure).
5. Check: `curl -I https://<origin>/` -> 200; register/login with that `Origin` -> 200; a foreign `Origin` -> 403; `https://<origin>/actuator/health` is not exposed.

## Notes
- The Funnel hostname is stable while the machine/tailnet name is unchanged, but it only works while this machine is on and Tailscale is running.
- The Windows `cloudflared` connector the owner started for the abandoned named tunnel (`esun-shop`) can be stopped, and the tunnel deleted in the Cloudflare dashboard.
- Previously verified through Funnel on 2026-09-29: `docs/tasks/067-phase33-22-closeout-and-status.md` § Public smoke. This task adds no new verification; it was written in a sandbox without Docker or Tailscale.
