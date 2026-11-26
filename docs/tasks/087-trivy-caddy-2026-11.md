# 087 — Trivy exception CVE-2026-78661 (Caddy), due 2026-11-30

Date: 2026-11-26 (Asia/Taipei). Executor: Claude Code, single agent, risk Small. Branch: `chore/trivy-caddy-2026-11` (from `origin/advanced-v2` `c32a123`).
Follows tasks 083 (the exception was added) and 086 (the expiry warning that surfaced this date). Merge is the owner's.

## Task

Decide what to do with the `CVE-2026-78661` exception on `usr/bin/caddy` before it lapses on 2026-11-30: delete it if a fresh image no longer
reports the CVE, otherwise renew it with the same reasoning. The two Spring `spring-webmvc` entries are out of scope (Boot 4 migration).

## Problem

The frontend image gate (`docker-build`, `supply-chain-gate`) fails on any fixable HIGH/CRITICAL. After 2026-11-30 Trivy stops applying the
entry, so both jobs would go red on whatever push comes next.

## Root cause

The frontend image is `caddy:2-alpine`. Its Caddy binary is statically linked Go and carries `golang.org/x/net`, whose `html` parser has a
quadratic-cost DoS (CVE-2026-78661, HIGH). The fixed x/net is 0.62.0.

## Solution

Rebuilt the frontend image with `docker build --pull` and looked at what is actually inside it.

| Item | Value (2026-11-26) |
|---|---|
| Base `caddy:2-alpine` digest | `sha256:6aeddd44c3078b0f9a35206472a11420648a79c184603ef95957d0a20044cb2b`, created 2026-09-17 |
| Caddy | **v2.11.9**, built with go1.27.3 |
| `golang.org/x/net` (`caddy build-info`) | **v0.60.0** (fixed in 0.62.0) |
| Newest Caddy release | v2.11.9, published 2026-11-05 — there is no newer one |
| Caddy `master` go.mod | already `golang.org/x/net v0.62.0`, but no release or image carries it yet |
| Trivy DB | freshly downloaded during the first scan (cache was from 2026-09-29) |

The CVE is **still reported**, so the exception is **renewed, not deleted**: same scope (`usr/bin/caddy`), same not-reachable reasoning,
`expired_at` 2026-11-30 -> **2026-12-14**, tracking pointer -> this file, and the header comment records the 2026-11-26 re-check and the
master-branch fact. 2026-12-14 matches the two Spring entries, so one date now covers every exception. Nothing else changed:
no Dockerfile, Caddyfile, pom.xml, ci.yml or Spring entry was touched.

## Engineering concept

An exception is a decision with a shelf life. Renewing is only honest if the check is repeated, not assumed: the rebuild, the version read
from the binary itself (`caddy build-info`, not the image tag) and the upstream check are what turn "extend by two weeks" into "still the
newest release, fix not shipped". The negative test matters as much as the positive one: it shows the green result comes from this one
entry and not from a scan that quietly stopped finding things.

## Test result

Local, Trivy `aquasec/trivy:0.74.0` (the version pinned in CI) with the flags of the CI image scans
(`--scanners vuln --severity HIGH,CRITICAL --ignore-unfixed --exit-code 1`), frontend image built from this branch with `--pull`.

| Check | Exit | Result |
|---|---|---|
| (0) Frontend image, **no** ignore file | 1 | `usr/bin/caddy`: 1 HIGH, `golang.org/x/net` v0.60.0, CVE-2026-78661, fixed in 0.62.0; Alpine layer 0 findings |
| (a) Frontend image, new `.trivyignore.yaml` | **0** | `alpine` 0, `usr/bin/caddy` 0 |
| (b) Frontend image, new file **minus** the CVE-2026-78661 entry (two Spring entries kept) | **1** | `Total: 1 (HIGH: 1)`, CVE-2026-78661 — the entry is what passes it, nothing wider |
| (c) `bash scripts/check-trivy-expiry.sh .trivyignore.yaml 14` | **0** | 3 entries `ok`, all expire 2026-12-14 (18 days), 0 need attention |
| `bash scripts/test/trivy-expiry.test.sh` | 0 | 29 passed, 0 failed |
| `.trivyignore.yaml` parsed as YAML | — | 3 entries, ids and dates as above |

Not verified: GitHub Actions (see the PR for the CI result). The backend image was not scanned: no backend change and the Spring entries are untouched.
The local image is not the digest CI publishes.

## Trade-offs

- Renewing keeps a HIGH finding accepted for 18 more days. The risk is availability only, in a handler set (`encode`, `headers`, `rewrite`,
  `vars`, `reverse_proxy`, `file_server`) that does not parse HTML; the caller of the parser inside Caddy is still unidentified, which is
  why the statement keeps its "re-assess if any other handler is added" clause.
- Alternative not taken: patching x/net inside the image (building Caddy with `xcaddy`) would remove the exception but adds a build stage and
  a self-maintained binary; out of scope, and the upstream fix is one release away.

## Next step

- By **2026-12-14** (the `check-trivy-expiry.sh` warning starts 2026-11-30): rebuild with `--pull`. If a Caddy release built with x/net >= 0.62.0
  has landed (watch `caddyserver/caddy` releases), delete this entry; otherwise re-decide.
- The two Spring entries expire the same day; they are closed only by the Spring Boot 4 migration (owner decision).
