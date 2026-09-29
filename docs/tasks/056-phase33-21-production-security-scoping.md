# 056 — Phase 3.3 #21 production security hardening scoping

## Session gate

- Mode: analysis/scoping only; no production, workflow, image, host, network, secret, or backup-policy implementation in this session.
- Class: Critical/Heavy, split into separate A scoping, B implementation slices, and C independent acceptance sessions.
- Baseline: `advanced-v2` @ `429346d`; preserve all pre-existing uncommitted #18 B2/Flyway and user files.
- Outcome: freeze the threat surface, inventory reusable security controls and evidence gaps, and define the smallest executable #21 slices without claiming control over an unavailable production VM.
- Affected module: production edge/VM, GitHub Actions and GHCR supply chain, application/runtime secrets and privileges, and MySQL backup lifecycle.
- Core files read: `CLAUDE.md`, `docs/project-state.md`, `docs/tasks/055-phase33-20-sql-performance-scoping.md`, `.github/workflows/ci.yml`, and `docker-compose.yml`; targeted searches covered the existing backup drill and security evidence.
- Files changed: this task record and the concise state entry in `docs/project-state.md` only.
- Verification: documentation diff/status inspection; executable tests, scans, deployment, and restore drills are intentionally excluded from this scoping-only change.
- Exclusions: production VM/DNS/SSH access, firewall or cloud rules, TLS issuance, secret creation/rotation, CI/workflow edits, image scanning, SBOM generation, backup upload/deletion, commit, push, merge, and deploy.
- Usage at start: five-hour 8% used (92% remaining); weekly 80% used (20% remaining). Model/cost details unavailable.

## Threat boundary and protected assets

The fixed target remains one Ubuntu VM running a production Compose stack behind Caddy. Only HTTPS traffic on 80/443 is intended to be public; SSH is an administrative path, not an application endpoint. MySQL, Redis, Ollama, the backend application port, and the management port must stay on private container/loopback networks. GitHub Actions publishes commit-addressable GHCR images and is part of the deployment trust chain.

Protected assets are customer identity and address data, order/payment/audit records, JWT/payment/database/Redis credentials, deploy credentials, GHCR artifacts, backup contents, and the ability to alter production. Primary threats are public service exposure, password or token theft, weak SSH access, stale/over-privileged credentials, dependency or image compromise, mutable/unverified deployment artifacts, destructive or exfiltrated backups, and a restore procedure that has never crossed the off-host boundary.

Production credentials, VM addresses, SSH material, DNS control and real ECPay values must be supplied out of band. Evidence may name a secret or control but must never record its value.

## Verified reusable foundation

| Control area | Current evidence | #21 disposition |
|---|---|---|
| Application production guardrails | The merged #18 B1 validator rejects weak/default JWT and DB credentials, sandbox or incomplete ECPay configuration, unsafe CORS, and Redis-without-password; production management defaults to loopback | Reuse. C acceptance must still prove the deployed stack enables the prod profile and does not publish the management port |
| CI least privilege and immutable tag | CI defaults to `contents: read`; only the publish job receives `packages: write`; GHCR includes a full commit-SHA tag after test/build gates | Reuse, then add vulnerability, SBOM, and provenance/identity evidence before release eligibility |
| Dependency updates | Dependabot is configured for Maven, npm, Docker and Actions | PR creation is not a vulnerability gate; severity policy and auditable failure behavior remain open |
| Backup/restore mechanics | `scripts/mysql-backup-restore.ps1` has prior real scratch-database drill evidence with source/data hashes and safety guards | Reuse the data-correctness core. It does not prove encrypted off-host retention, least-privilege backup credentials, expiry, recovery from off-host storage, or production RPO/RTO |
| Network topology | The intended #18 topology says only 80/443 are public | Not implemented/proven. Current root `docker-compose.yml` is development-only and publishes MySQL, Redis and Ollama with development defaults; it must never be treated as a production manifest |
| TLS and response headers | Caddy HTTPS and production edge behavior are planned under #18 | No current certificate-renewal proof or explicit HSTS/CSP/frame/content-type/referrer policy acceptance evidence |

## Confirmed gaps and acceptance evidence

| ID | Gap | Minimum acceptable evidence |
|---|---|---|
| G1 | No deployed exposure inventory or deny-by-default VM/cloud firewall policy | On the target host, a recorded ruleset and external scan prove only 80/443 plus the explicitly restricted SSH path are reachable; DB/Redis/Ollama/backend/management ports are not public |
| G2 | SSH baseline is unspecified | Key-only login, root/password login disabled, a named non-root deploy operator with narrowly scoped sudo, rate limiting/fail2ban or provider equivalent, and tested recovery access |
| G3 | TLS and security headers lack an executable contract | Valid public chain, automated renewal evidence, modern TLS policy, HTTP-to-HTTPS redirect, HSTS after domain readiness, CSP compatible with the frontend/ECPay flow, frame denial, nosniff and referrer policy verified from the public edge |
| G4 | Secret ownership, rotation and revocation are not defined | Inventory of secret names/owners/storage/consumers/rotation trigger, dual-value or maintenance-window procedure, least-privilege DB/runtime identities, redacted rotation drill, and revocation proof; no value in Git, logs, artifacts or Notion |
| G5 | GHCR images have no blocking vulnerability scan or SBOM | Pinned scanner/action versions, scan of the exact digest selected for deployment, documented severity/exception policy, failing controlled fixture or equivalent gate proof, CycloneDX/SPDX SBOM retained with the image/run, and digest/SBOM linkage |
| G6 | Dependency automation does not block known-risk releases | Maven/npm/container/Actions checks have an explicit severity policy, deterministic lockfile/input behavior, bounded exceptions with owner/expiry, and a controlled failure demonstration |
| G7 | Backup retention and off-host recovery are absent | Encrypted backup leaves the VM under a least-privilege identity, retention/expiry is enforced, restore downloads from the off-host copy into a disposable database, hashes/schema/business counts are verified, cleanup is proven, and measured RPO/RTO are recorded |
| G8 | No independent Critical-risk acceptance | A fresh REVIEW_ONLY session derives expected behavior from this contract, inspects the exact diff/config and one controlled acceptance command; any unmitigated high-risk gap yields FAIL/BLOCKED |

## Executable slices

### SEC-01 — Repository supply-chain gate

Add digest-addressable image scanning, SBOM generation/retention, and dependency severity gates to CI with pinned tool/action identities and a documented exception format. The gate must scan the same image digest that can be promoted, not rebuild an unlinked image. Verify normal success plus one controlled known-vulnerable failure without weakening the existing backend/frontend/image gates.

This is the next single task because it is repository-local and does not depend on the unfinished #18 production topology or unavailable VM credentials.

### SEC-02 — Edge, VM and SSH hardening

After #18 produces the actual Compose/Caddy/deploy layout, add the deny-by-default firewall, restricted SSH policy, non-root operator, private container networks, read-only/capability/resource hardening where compatible, TLS policy and response headers. Verification must include an external port scan and public header/TLS evidence; a local configuration review alone is insufficient.

### SEC-03 — Secrets and runtime least privilege

Freeze the secret inventory and owners, split runtime/deploy/backup/database privileges, remove long-lived credentials where platform identity can replace them, document rotation/revocation, and execute a redacted rotation drill. This slice cannot store production values in the repository, CI output, task records or Notion.

### SEC-04 — Off-host backup lifecycle and restore

Extend the existing correctness drill with encryption, off-host storage, retention/expiry and least-privilege access. Acceptance restores a downloaded off-host artifact into a disposable database and records integrity checks plus measured RPO/RTO. A same-host dump or upload-only check does not pass.

### SEC-05 — Independent production-security acceptance

The other agent performs the Critical-risk REVIEW_ONLY gate after SEC-01–04 and #18 topology are available. Review the exact controls and run one pre-agreed controlled acceptance entrypoint that aggregates non-destructive checks. VM/provider limitations must be reported as BLOCKED rather than inferred as secure.

## Dependency and stop conditions

- #21 A-stage is complete, but B/C are not started. This does not override the default #18 → #19 → #20 → #21 sequence.
- SEC-01 may proceed independently because it changes only the repository supply chain. SEC-02–04 require the concrete #18 deployment topology and, for live proof, user-supplied VM/DNS/storage access.
- Do not expose credentials to unblock automation, scan production with destructive tools, change firewall/SSH before a tested recovery path exists, enable HSTS before the final domain/HTTPS path is stable, or delete backup generations during a first retention test.
- Any need to change application authorization/payment behavior, adopt a new cloud platform, or redesign the deployment topology is a new acceptance objective and requires a new bounded session.

## Result

- Status: Phase 3.3 #21 A-stage scoping complete; no security control implementation or live production acceptance claimed.
- Branch/commit: `advanced-v2` @ `429346d`; changes remain uncommitted.
- Tests/scans/drills: not executed by design; documentation-only scoping.
- Repair rounds: 0.
- User interventions: 1 (start Phase 3.3 #21).
- Valid review defects: not applicable; no independent review occurred.
- Approximate tool calls: 10; scope expansions: 0.
- Elapsed time: about 3 minutes 32 seconds. Goal token usage: 108,675. Five-hour usage changed 8% → 17%; weekly usage changed 80% → 81%. Model/cost details and start/end context size are unavailable.
- Next single task: SEC-01 repository supply-chain gate.

## Engineering concept note

Production security is evidence about boundaries, identities and artifact lineage, not a checklist of installed tools. A scan only protects a release when it is blocking, policy-driven, and tied to the exact digest being deployed; a backup only protects recovery when an off-host copy can be decrypted and restored under controlled conditions.
