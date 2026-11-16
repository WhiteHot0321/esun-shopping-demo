# 083 — Phase 3.3 #21: Trivy exception renewal and local SEC-02/03 re-check

Date: 2026-11-16 (Asia/Taipei). Executor: Claude Code. Branch: `feature/phase33-21-supplychain` (from `advanced-v2` `cd04e78`).
Scope: the parts of #21 that need no VM and no off-host storage. Not pushed, not merged, `docs/project-state.md` untouched.

## Task

Eight `.trivyignore.yaml` exceptions expired on 2026-11-05 and two more expire on 2026-11-19. Re-decide each one: upgrade where a fix now
exists, re-except with reachability reasoning and a short expiry where it does not, and prove the gate with the Trivy version CI pins.

## Problem (state of `advanced-v2` on 2026-11-16, measured)

With `aquasec/trivy:0.74.0` (the version `ci.yml` pins), CI's exact flags, vulnerability DB downloaded 2026-11-16, images built with `--pull`:

| CI step | Result with the committed config | Cause |
|---|---|---|
| `supply-chain-gate` repo scan (`trivy fs --scanners vuln,secret ...`) | **fails**, exit 1 | `follow-redirects` 1.16.1 in `frontend/package-lock.json`, CVE-2026-116689 HIGH, fixed in 1.16.2 |
| `docker-build` frontend image scan | **fails**, exit 1 | CVE-2026-78661 HIGH in `usr/bin/caddy` (`golang.org/x/net` 0.60.0, fixed in 0.62.0) — a new finding, not one of the expired ones |
| `docker-build` backend image scan | passes until 2026-11-19, then fails | the two `spring-webmvc` CRITICAL exceptions expire |

The eight expired entries are not what breaks the gate: the findings they covered are gone (below). Two unrelated new findings break it.

## Root cause

- Base images are floating tags (`caddy:2-alpine`, `eclipse-temurin:17-jre`), so the image content changes without a commit; the dependency
  lockfile is fixed, so a new advisory against an old pinned version appears without a commit either. A time-boxed exception is the mechanism
  that forces this re-check; it worked as designed.
- No Spring Framework 6.2.x release fixes CVE-2026-47884 / CVE-2026-47890.

## Solution

### Resolved by upstream — entries removed, no exception

| Old entry | Evidence |
|---|---|
| `usr/bin/caddy`: CVE-2026-78669, -78667, -97031, -111943 | `caddy:2-alpine` now resolves to Caddy **v2.11.9** (Docker Hub, updated 2026-11-13; was v2.11.4). Scan with an **empty** ignore file no longer reports them. |
| `usr/bin/pebble`: the same four CVEs | `eclipse-temurin:17-jre` rebuilt 2026-11-12 (OpenJDK **17.0.21**, Ubuntu 26.04.1; `pebble` binary dated Oct 21). Scan with an empty ignore file no longer reports them. |

No Dockerfile change was needed: both use floating tags and CI builds on a fresh runner. Scan with an empty ignore file after the rebuild:
frontend 1 finding (CVE-2026-78661), backend 2 findings (the two Spring CVEs). Those three are the whole new exception list.

### Fixed by a dependency bump

- `follow-redirects` 1.16.1 → **1.16.3** (`npm update follow-redirects --package-lock-only`; three lines of `frontend/package-lock.json`, transitive
  through `axios@1.20.0`, `package.json` unchanged). Node-only code path; the browser bundle does not use it.

### Re-excepted (no fix available)

| CVE | Scope (unchanged or exact) | Why no upgrade | Reachability | New expiry |
|---|---|---|---|---|
| CVE-2026-47884 CRITICAL | `app/app.jar/BOOT-INF/lib/spring-webmvc-*.jar` | Maven Central 2026-11-16: newest 6.2.x is 6.2.19 and newest Boot 3.5.x is 3.5.16 — both already in use. Fix only in 7.0.9 (Boot 4 line). | Not reachable: needs `XsltView` and rendered views. Re-checked in code: 14 `@RestController`, no `@Controller`, `ViewResolver`, `ModelAndView`, template engine, templates directory or XSLT. | 2026-12-14 |
| CVE-2026-47890 CRITICAL | same path | same | Not reachable: needs SSE with view fragments. No `SseEmitter`, `ResponseBodyEmitter`, `text/event-stream`, webflux. | 2026-12-14 |
| CVE-2026-78661 HIGH (new) | `usr/bin/caddy` | v2.11.9 is the newest Caddy release and the newest image; it ships `x/net` 0.60.0. | Not reachable in this configuration, availability-only otherwise: quadratic work in the `x/net/html` parser on crafted HTML. `caddy adapt` of `frontend/Caddyfile` yields only `encode`, `headers`, `rewrite`, `vars`, `reverse_proxy`, `file_server`, `subroute`; none parses HTML (no `templates`, no `browse`). | 2026-11-30 |

Limits of that reasoning, stated rather than hidden:

- For CVE-2026-78661 the parser **is** linked into the binary (`html.Parse`, `html.NewTokenizer` symbols present) and the Caddy module that
  calls it was **not identified**. The conclusion rests on the configured handler list, not on a call graph. Two weeks only for that reason.
- The Spring entries are the second renewal of the same decision. Four weeks, because nothing short of the Boot 4 / Framework 7 migration
  closes them; that migration is not started and needs an owner decision (see Next step).

## Verification

Pinned Trivy 0.74.0 through `aquasec/trivy:0.74.0`, flags copied from `ci.yml`. Images: backend and frontend built from this branch with `--pull`.

| Check | Exit | Findings |
|---|---|---|
| Self-test fixture (`.github/security-fixtures/vulnerable-npm`) | 1 (expected) | 4 HIGH, CVE ids present |
| Repo scan, new config, after the lockfile bump | 0 | none; secret scan clean |
| Repo scan before the bump | 1 | CVE-2026-116689 |
| Backend image, new config | 0 | — |
| Frontend image, new config | 0 | — |
| Frontend image, committed (old) config | 1 | CVE-2026-78661 |
| Frontend image, new config **minus** the Caddy entry | 1 | CVE-2026-78661 — the entry is what passes it, nothing wider |
| Backend image, new config with the Spring entries' date set to 2026-11-15 | 1 | CVE-2026-47884, CVE-2026-47890 — expiry still bites |

Tests after the bump:

- Frontend: `node --test` 3/3 pass; Vitest **8 files, 91 tests, 91 pass**; `vite build` succeeded. `npm audit --omit=dev`: 0 vulnerabilities.
- Backend (`mvn -B clean test`, no backend change on this branch): see "Backend suite" at the end of this file.

Not verified: no GitHub Actions run (branch not pushed). The local images are not the digests CI will publish.

## SEC-02 / SEC-03 re-check on the rebuilt images (local, throwaway values, stack removed afterwards)

`docker-compose.prod.yml` under a separate project name, Caddy on `127.0.0.1:18088`, random credentials generated into a scratch file and deleted.

Confirmed:

- All services up; `migrate` exited 0; backend healthy on OpenJDK 17.0.21; Caddy v2.11.9 serves the SPA and proxies `/api/products/available` (200).
- Backend: `ReadonlyRootfs=true`, `CapDrop=[ALL]`, no added capabilities, `CapEff/CapPrm/CapBnd` all zero in `/proc/1/status`, `NoNewPrivs: 1`,
  uid 999 `spring`, 1 GiB, `pids_limit` 512, write to `/app` fails with "Read-only file system", no Flyway credential in its environment.
- Caddy: read-only root, `CapDrop=[ALL]` + `NET_BIND_SERVICE` only (`CapEff 0x400`), `NoNewPrivs: 1`, 256 MiB; `mysql` and `redis` do not resolve
  from it (edge network only), `backend:8080` does.
- MySQL `CapDrop=[ALL]` + five capabilities, Redis `CapDrop=[ALL]` + three, uid 999, 256 MiB; only Caddy publishes a port.
- `esun_app`: `USAGE` on `*.*` plus `SELECT, INSERT, UPDATE, DELETE, EXECUTE` on the application schema only. `CREATE TABLE`, `DROP TABLE` and
  reading `mysql.user` are refused (ERROR 1142).
- Headers on `/`: CSP, COOP, Permissions-Policy, Referrer-Policy, `X-Frame-Options: DENY`, `nosniff`, `Strict-Transport-Security: max-age=0`, no `Server`.
  `/actuator/health` through the edge returns the SPA's `index.html` (text/html), not the actuator; `/api/v3/api-docs` returns 401.

Gaps observed (recorded, **not changed** here — each is a behaviour change outside this task):

1. The gate scans only the two images we build. `mysql:8.0`, `redis:7`, `flyway/flyway:11-alpine`, `ollama/ollama:latest` and the three
   monitoring images run in production unscanned and unpinned by digest; `ollama` is on `latest`.
2. `ollama`: no `cap_drop`, runs as root, no memory limit. MySQL: no memory limit. Neither MySQL nor Redis has a read-only root or `pids_limit`.
3. `docker-compose.monitoring.yml`: Prometheus, Alertmanager and Grafana have no `cap_drop`, `no-new-privileges` or read-only root
   (their ports are bound to `127.0.0.1`).
4. MySQL has `root@'%'`: root can log in from any container on the `data` network (the one-shot `migrate` needs this). The backend sits on that
   network but does not hold the root password.
5. `REDIS_PASSWORD` is passed on the `redis-server` command line, so it is visible in `docker inspect` and the process list on the host.
6. A build without `--pull` reuses a stale local base: during this task a plain `docker build` produced a Caddy v2.11.4 image again from the
   8-week-old cached `caddy:2-alpine`. CI is unaffected (fresh runner), and deploys use CI's digests, but a local or on-VM `docker compose build`
   would silently ship the old base.
7. `npm audit` (dev dependencies included) reports moderate advisories in `@vitest/mocker`, `glob`, `minimatch`; below the gate's threshold,
   build/test tooling only.

## Still open — host level, nothing done, nothing claimed

No VM and no off-host destination exist, so these remain exactly as tasks 064 and 066 left them:

- Host firewall ruleset and an external port scan (G1). `scripts/vm-bootstrap.sh` has never run on a host.
- SSH baseline: key-only, no root login, restricted deploy key (G2).
- Public TLS chain, renewal evidence, HTTP→HTTPS redirect, live HSTS (`HSTS_MAX_AGE` is still 0 by default) (G3).
- Off-host backup: destination, upload under a separate identity, restore from a downloaded copy, retention on the remote, scheduling,
  passphrase stored off the backup host, measured RPO/RTO (G7).
- Rotation drills for `REDIS_PASSWORD`, `PAYMENT_CALLBACK_SECRET` and ECPay values; secret-age alerting (065).

## Engineering concept

A vulnerability exception is a decision with a shelf life, not a suppression. Scoping each entry to one CVE on one path and giving it an expiry
means the gate re-opens the question on a date; when it did, eight of ten entries turned out to be obsolete and could be deleted rather than
extended, and the real failures were two findings nobody had excepted. The negative tests matter as much as the green run: a gate is only
evidence if removing or expiring an entry turns it red.

## Trade-offs

- Floating base tags gave the fixes for free, but make builds non-reproducible and let a cached base go stale (gap 6). Digest pinning plus an
  automated bump would be reproducible at the cost of maintenance; not decided here.
- Re-excepting two CRITICAL Spring CVEs a second time is acceptable only while the "no view layer, no SSE" facts hold; nothing enforces them
  automatically. A small architecture test asserting them would turn the statement into a check (optional follow-up, not done).

## Next step

1. Lead: push / open the PR so CI confirms the gate on real runners; `advanced-v2` is currently red on the repo scan and the frontend image scan.
2. By **2026-11-30**: rebuild and re-scan; drop the Caddy entry if a newer image carries `x/net` ≥ 0.62.0, otherwise re-decide.
3. By **2026-12-14**: owner decision on the Spring Boot 4 / Framework 7 migration, the only way to close the two Spring entries.
4. Owner decision on gaps 1–6 above (each a separate, small change).

## Backend suite

`mvn -B clean test` on this branch (local JDK 21, Testcontainers on Docker 29.8.0): **Tests run: 322, Failures: 0, Errors: 0, Skipped: 0**,
BUILD SUCCESS, 6 min 29 s. The backend sources and `pom.xml` are unchanged on this branch, so this is a baseline, not a regression test of a change.
