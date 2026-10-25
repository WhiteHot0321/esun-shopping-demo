# 076 — Cloudflare named tunnel (stable public URL) for tunnel mode

Decision (2026-10-25, user): use a **Cloudflare named tunnel** instead of Tailscale Funnel / quick tunnels, to get a stable hostname.

## What changed
- `docker-compose.cloudflared.yml` (new overlay): `cloudflared` service (token mode, `http2` by default), joins only the `edge` network, read-only, `cap_drop ALL`. Fails `config` if `CLOUDFLARE_TUNNEL_TOKEN` is unset.
- `.env.prod.example`: `CLOUDFLARE_TUNNEL_TOKEN` (secret) and optional `CLOUDFLARED_PROTOCOL`.
- `scripts/deploy-tunnel.sh`: `CLOUDFLARED=1` adds the overlay.
- CI `config-validate`: also validates prod + tunnel + cloudflared (dummy token).

## One-time setup (owner, Cloudflare dashboard — not automatable from the repo)
1. Cloudflare Zero Trust -> Networks -> Tunnels -> Create a tunnel (Cloudflared). Copy the token.
2. Public Hostname: your domain/subdomain (domain must be on Cloudflare DNS) -> service type HTTP, URL `caddy:80`.
3. In the git-ignored `.env.prod`: `CLOUDFLARE_TUNNEL_TOKEN=<token>`, `CORS_ALLOWED_ORIGINS=https://<hostname>`, `DOMAIN=http://:80`.
4. `CLOUDFLARED=1 bash scripts/deploy-tunnel.sh deploy`
   (health gate checks Caddy on `127.0.0.1:8088`; the tunnel itself is checked in step 5).
5. `curl -I https://<hostname>/` -> 200; `curl https://<hostname>/actuator/health` must not be exposed.

## Not verified
Written in a sandbox without Docker or `cloudflared`; the overlay was **not** run and `docker compose config` was only expected to pass via CI. Real acceptance (SPA 200, register/login with the tunnel `Origin`, foreign `Origin` 403) still needs the owner's token and hostname.
