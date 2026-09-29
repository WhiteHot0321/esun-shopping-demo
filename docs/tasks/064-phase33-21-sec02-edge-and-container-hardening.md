# 064 — Phase 3.3 #21 SEC-02: edge headers, container hardening and network segmentation

Date: 2026-09-29 (Asia/Taipei). Executor: Claude Code. Branch: `advanced-v2`. Scope source: `056` (SEC-02, gaps G1-G3).

No VM exists (task 060), so the host-level half of SEC-02 (firewall, SSH policy, TLS chain, external scan) cannot be done or claimed. This task
covers what is enforceable in the Compose/Caddy configuration and provable on a local run.

## What changed

- `frontend/Caddyfile`: adds `Content-Security-Policy` (`default-src 'self'`, no inline script/style, `object-src 'none'`, `frame-ancestors 'none'`,
  `form-action` limited to self and the ECPay cashier hosts because checkout submits a form there), `Permissions-Policy`,
  `Cross-Origin-Opener-Policy`, removes the `Server` header, and emits `Strict-Transport-Security` with `max-age={$HSTS_MAX_AGE:0}` — i.e. **off by
  default**; set `HSTS_MAX_AGE` only when the final HTTPS domain is stable, since browsers cache it.
- `docker-compose.prod.yml`: two networks (`edge`: Caddy↔backend; `data`: backend↔MySQL/Redis/Ollama) so Caddy cannot reach the data services;
  `cap_drop: ALL` everywhere it works (Caddy keeps `NET_BIND_SERVICE`; MySQL keeps `CHOWN DAC_OVERRIDE FOWNER SETUID SETGID`; Redis keeps
  `CHOWN SETUID SETGID`; Ollama only `no-new-privileges`), `no-new-privileges` on all services, read-only root filesystem with a `/tmp` tmpfs on the
  backend and Caddy, memory limits (backend 1 GB, Caddy and Redis 256 MB) and `pids_limit` on the backend.
- `docker-compose.monitoring.yml`: Prometheus, Alertmanager and Grafana join `edge`.

## Verification (local run of the hardened stack: prod + tunnel + monitoring overlays)

- All eight containers started and were healthy/up, including MySQL with the reduced capability set and the read-only backend.
- Response headers on the SPA and on an API response: CSP, COOP, Permissions-Policy, Referrer-Policy, `X-Frame-Options: DENY`,
  `X-Content-Type-Options: nosniff`, `Strict-Transport-Security: max-age=0`, no `Server` header; the uploaded image is served with `image/png` + `nosniff`.
- Browser (built-in): the page loaded under the CSP with the stylesheet applied, no console messages, no `securitypolicyviolation` events; registering through the UI
  succeeded and logged in with no violations.
- Segmentation: from the Caddy container, `mysql:3306` and `redis:6379` were unreachable while `backend:8080` was reachable; the backend stayed `healthy`
  (its healthcheck is readiness, which includes the database on the `data` network). Redis reachability from the backend was not separately probed.
- Hardening as applied (`docker inspect`): backend `ReadonlyRootfs=true`, `CapDrop=[ALL]`, `no-new-privileges`, 1 GiB; running as uid 999 `spring`; writing to
  the root filesystem fails with "Read-only file system"; Caddy `CapAdd=[NET_BIND_SERVICE]`.
- Under the read-only backend: seller product creation and a PNG image upload succeeded (200), the file landed in the uploads volume and was served through Caddy.
- The stack, volumes and the throwaway `.env.prod` were removed afterwards.

## Not covered (BLOCKED without a VM or a public domain)

- G1 external exposure: no host firewall ruleset and no external port scan. G2: no SSH baseline. G3: no public certificate chain, no renewal evidence,
  no HTTP→HTTPS redirect check on a real domain, no live HSTS. `scripts/vm-bootstrap.sh` (ufw 22/80/443, key-only SSH) exists but has never been run on a host.
- The CSP was exercised on registration/login and the catalogue only; the ECPay form submission and image-heavy pages were not exercised under it, and
  `style-src 'self'` may need adjusting if a component later sets inline style attributes in markup.
- Memory limits are starting values, not tuned; the backend's 1 GB limit was not load-tested. Ollama and MySQL have no memory limit.
- No independent review.
