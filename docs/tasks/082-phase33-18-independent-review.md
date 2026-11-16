# 082 — Phase 3.3 #18 B3 deploy pipeline: independent review (C stage)

Date: 2026-11-16 (Asia/Taipei). Reviewer: Claude Code (fresh context, did not write the pipeline). Branch: `feature/phase33-18-review`,
baseline `cd04e78` (`advanced-v2`). Risk: Critical (deploy, secrets, rollback). Outcome: **PASS WITH FIXES** for the repository side;
B' (a real VM run) is still required before the pipeline can be called verified.

Scope read: `AGENTS.md`, `docs/tasks/075`, `070`, `067`, the `deploy-script-test` / `publish-image` / `deploy` jobs in
`.github/workflows/ci.yml`, `scripts/deploy-vm.sh`, `scripts/esun-deploy-entry`, `scripts/vm-bootstrap.sh`,
`scripts/test/deploy-vm.test.sh`, `docker-compose.prod.yml`, `frontend/Caddyfile`, and the output lines of `scripts/mysql-backup.sh`.
No VM exists: nothing here ran against a real host, sshd, GHCR pull or ACME. No network deploy was attempted.

## Findings by severity

| # | Severity | Finding | Status |
|---|---|---|---|
| H1 | High | A dropped SSH session kills the deploy with SIGPIPE, possibly after `up` and before the rollback | **Fixed**, tests added |
| H2 | High | An unparsable `.env.prod` makes Compose quote the offending value into the public Actions log | **Fixed**, tests added |
| M1 | Medium | After one failed migration every later approved deploy is an outage plus rollback; nothing in the output says `repair` | Open (design decision) |
| M2 | Medium | `vm-bootstrap.sh` SSH hardening is ineffective when a `sshd_config.d` drop-in sets the same option | Open (needs a host to test) |
| M3 | Medium | Rollback restores only `backend` and `caddy`; everything else the failed `up` changed stays changed | Open (document or extend) |
| M4 | Medium | A deploy waiting for approval holds the workflow concurrency group, so later CI runs on `advanced-v2` queue behind it | Open (not confirmed on GitHub) |
| M5 | Medium | Digest pinning covers 2 of 6 images; `ollama/ollama:latest`, `flyway`, `mysql`, `redis` float and are not scanned | Open |
| L1 | Low | The revision label is a consistency check, not authenticity; the VM cannot tell a scanned digest from an unscanned one | Open (accepted risk candidate) |
| L2 | Low | The health gate is local only: verified certificate, but no proof of public reachability, and it passes on the first 200 | Open |
| L3 | Low | The forced-command regex runs in the client-chosen locale (`AcceptEnv LANG LC_*`) | Open (one-line hardening) |
| L4 | Low | The downtime statement is honest but under-quantified | Open (doc) |
| L5 | Low | A failed `write_state` after a passing gate exits 1, which the workflow reports as "nothing was started" | Open |
| L6 | Low | Third-party actions in the `packages: write` job are pinned by tag, not by commit | Open (belongs to #21) |

### H1 — a dropped SSH session kills the deploy mid-flight (fixed)

`deploy-vm.sh` wrote straight to the SSH channel. Without a pty, sshd does not signal the remote command when the client goes away; the
command dies on its **next write** (SIGPIPE). The health gate is silent while it polls, so the next write after `up` is the gate's verdict.

Failure scenario: the owner approves a deploy of a broken build. `up -d` replaces the backend. The Actions job is cancelled, hits
`timeout-minutes: 25`, or the runner loses the connection. The gate times out, tries to print `health gate failed`, and bash is killed
before `redeploy` runs. The broken version stays live, the state file still names the old version, and nobody is told. The mirror case on
a good deploy: the version is live but never recorded, so the next automatic rollback goes one version too far back.

Evidence (before the fix, new case 11): `bad deploy without a reader: exit 10 (expected '10', got '141')`, no rollback `up` in the stub log.

Fix: `detach_output` in `setup()` routes all output of the script and its children through `tee -p -a $STATE_DIR/deploy.log`. `tee -p`
keeps running when its reader is gone, so the pipe the script writes to never breaks; `trap '' HUP` covers an operator session with a pty.
It is called before the lock fd is opened so `tee` does not hold the lock. Side effects: stderr is merged into stdout (the Actions log
already interleaves them), and the host now keeps a copy of every run's output (mode 600, in the 700 state directory, append-only, a few
lines per run, same content as the public log). A cancelled Actions run now **keeps deploying on the VM**; the lock makes the next run
exit 1 with "already running" until it finishes.

### H2 — Compose quotes `.env.prod` into the public log (fixed)

The header claimed `compose config -q` is safe output. It is not when the env file does not parse. Measured with Docker Compose v5.5.1
and fake values:

```
line 5: unterminated quoted value "FAKE-jwt-111
line 2: unexpected character "!" in variable name "FAKE-pasted-secret-000!"
Invalid template: "FAKE-jwt-111 ${unclosed"
```

Every subcommand prints this (`ps`, `pull`, `up` too), not only `config`.

Failure scenario: the operator edits `.env.prod` on the VM and leaves a quote open, pastes a secret on its own line, or generates a
password containing `${`. The next approved deploy prints that value into the Actions log of a public repository. The manual `rollback`
path had no `config` step at all: it printed the same message from `pull`/`up`, moved the checkout twice and exited 11.

Fix: `config_ok` runs `config -q` with stdout and stderr discarded; the deploy path uses it where the check already was, and `rollback`
runs it before touching anything (exit 1, checkout and state untouched). The error names the command to run on the host instead.
Residual: `.env.prod` changing between the check and a later compose call in the same run is not covered.

### M1 — a failed migration turns every later deploy into an outage

`075` measured that a failed Flyway migration blocks all later `migrate` runs until a manual `repair`, and calls the resulting
"fail and roll back on every deploy" the safe direction. It also measured that Compose stops the old backend **before** `migrate` runs.
Together: until someone repairs, each approved deploy takes the site down, fails, and rolls back. The script exits 10 with "rolled back
... and it is healthy" and the workflow says "the VM rolled back to the previous version and is healthy"; neither mentions the schema or
`repair`, and exit 10 does not distinguish "migrate failed" from "gate failed". MySQL DDL is not transactional, so the old backend may also
be running against half-applied DDL while everything reports healthy.

Recommendation (not implemented, it changes the deploy flow and needs a real Compose run): run the migration as its own step before
`up` (`compose run --rm migrate`) while the old backend is still serving, so a failing migration costs no downtime; give it its own exit
code and message that points at the `repair` procedure in `075`.

### M2 — bootstrap SSH hardening can be a no-op

`vm-bootstrap.sh` edits `/etc/ssh/sshd_config` with `sed`. On Ubuntu 22.04/24.04 that file starts with
`Include /etc/ssh/sshd_config.d/*.conf`, and sshd keeps the **first** value it reads. Cloud images commonly ship
`50-cloud-init.conf` with `PasswordAuthentication yes` when the provider sets a password. Scenario: bootstrap prints "Hardening SSH
(key-only, no root login)", password login stays enabled. The `sed` also does nothing when the option line is absent, and there is no
`sshd -t` before the reload.

Not fixed here: it cannot be tested without a host and a wrong sshd change locks the operator out. Suggested shape for B': write
`/etc/ssh/sshd_config.d/00-esun-hardening.conf`, run `sshd -t`, reload, then assert with
`sshd -T | grep -E '^(passwordauthentication|permitrootlogin) no$'` and fail the bootstrap if either is missing.

### M3 — rollback scope is narrower than the failed change

`redeploy` runs `up -d --no-deps backend caddy` from the old checkout. If the failed commit also changed `mysql`/`redis`/`ollama`
service definitions, added a service, or changed a mount, the failed `up` already applied that and the rollback leaves it: old backend,
new neighbours, new schema. Forward compatibility of the schema is a convention, not something the pipeline checks. This is a reasonable
trade (re-running `up` for everything would re-run `migrate`), but "rolled back" overstates it. Either state it in the exit-10 message
or treat commits that touch non-application services as manual deploys.

### M4 — approval wait and CI concurrency (reasoned, not confirmed on GitHub)

`ci.yml` has a workflow-level `concurrency` group per ref with `cancel-in-progress: false` on `advanced-v2`. A run whose `deploy` job is
waiting for review has not finished, so it should keep holding that group: the next push's entire CI run stays pending behind it, and a
third push cancels the pending second. `075` says an unreviewed deploy may be left to expire after 30 days; if this reading is right, CI
feedback on `advanced-v2` stalls for that long. Check the Actions history of the pushes after `110afbc`. If confirmed: always reject at
once, or move `deploy` to its own workflow so it does not share the CI group.

### M5 — only two images are pinned

`BACKEND_IMAGE`/`FRONTEND_IMAGE` are digests. `mysql:8.0`, `redis:7`, `flyway/flyway:11-alpine` and `ollama/ollama:latest` are tags:
whatever the VM pulled first stays, a rebuilt VM gets something else, and none of them passes the Trivy gate. The Flyway container
receives the MySQL root password; Ollama runs without `cap_drop`. Pin them by digest in `docker-compose.prod.yml` (out of scope for a
script review: B was told not to touch the data services).

### L1 — what the label and the entry point do and do not prove

Checked and sound: the entry regex is anchored, rejects prefixes, suffixes, uppercase and newlines, passes arguments as an array through
`env -i`, and `deploy-vm.sh` validates them again; the sha must be on the freshly fetched `advanced-v2` and descend from the deployed
sha (a force-push or a missing object fails closed); `IMAGE_BASE` is fixed, so a digest can only come from this repository's packages.

Not proven: the revision label is written by whoever builds the image, and the VM verifies no signature or provenance. `publish-image`
pushes tags before its digest scan and before the Environment approval. So a holder of the Actions key can deploy a published digest
whose scan failed, or a commit nobody approved. "Only scanned digests" is enforced by `needs:` in the workflow, not on the VM. The key
is only released to an approved job, so this needs a prior compromise; record it as accepted or add `cosign verify-attestation` later.
Deleting the state file also resets the no-downgrade check (needs host access).

### L2 — health gate trust

Good: `curl` verifies the certificate against the system store for the real hostname (`--resolve`), and a test rejects `-k`. Limits:
it targets 127.0.0.1, so a stale DNS record or a closed provider firewall still yields "deployed, health gate passed"; one 200 passes
(no stability window); the SPA is not fetched; `2>/dev/null` makes a certificate failure and a dead listener both read `000`.
A second, external probe from the runner would close the first gap but needs the hostname as an Environment variable.

### L3 — locale of the forced command

Ubuntu's sshd accepts `LANG`/`LC_*` from the client, and `restrict` does not stop that. The entry point matches `[0-9a-f]` in that
locale; in some locales ranges are not ASCII ranges. Today this is harmless because `deploy-vm.sh` re-validates under `env -i`. Add
`export LC_ALL=C` at the top of `esun-deploy-entry`. Not changed here: only `C`/`C.utf8` exist in the test environment, so no test
could fail before the change.

### L4 — downtime

`075` already states there is no zero-downtime on one VM. Missing numbers: every commit produces a new frontend digest, so Caddy (the
TLS listener) is recreated on every deploy, not only the backend; a successful deploy is down for stop + migrate + JVM start; a gate
failure is down or degraded for the whole `HEALTH_TIMEOUT` (180 s) plus the rollback's restart, and up to another 180 s if the rollback
also fails. The "about 8 seconds" figure is the fast-failing migrate case on a laptop.

### L5, L6

L5: if `write_state` fails after a passing gate (disk full), `set -e` exits 1; the workflow prints "refused ... nothing was started"
while the new version is live and unrecorded. L6: `actions/checkout@v4`, `docker/*@v3..v6` run with `packages: write` and are pinned
by tag; only Trivy is pinned by commit.

### Checked, no finding

State file fields are validated before use; state is written only after a passing gate; the script survives its own file being swapped
by `git checkout`; `known_hosts` is strict with no TOFU; the deploy job checks nothing out and holds no application secret;
`mysql-backup.sh` prints only a path and a size; two concurrent runs are mutually exclusive (now tested with the real `flock`).

## What changed in this branch

- `scripts/deploy-vm.sh`: `detach_output` (H1), `config_ok` plus its use in deploy and rollback (H2), header comment.
- `scripts/test/deploy-vm.test.sh`: case 10 (H2, deploy and rollback), case 11 (H1, failing and passing deploy with no reader),
  case 12 (real `flock`: second deploy and a rollback are refused while the first run holds the lock, lock is released afterwards).
  The docker stub can now imitate Compose's env-file error and hold `up` open.

## Verification

| Check | Result |
|---|---|
| Stub tests, baseline `cd04e78`, WSL Ubuntu (bash 5.1.16) | 83 passed, 0 failed |
| Final tests against the **unfixed** `deploy-vm.sh` | 100 passed, **10 failed** (3 in case 10, 7 in case 11) |
| Final tests against the fixed script, three consecutive runs | 110 passed, 0 failed, each run |
| `bash -n` on the three scripts and the test | all pass |
| `shellcheck -x` 0.11.0 on the same four files | 0 findings, before and after |
| `actionlint` on `.github/workflows/ci.yml` | 0 findings (workflow not modified) |
| Compose env-file error text | reproduced with Docker Compose v5.5.1, fake values only |

`shellcheck` and `actionlint` are not installed in WSL or Git Bash; both were run from the `rhysd/actionlint` image already on this
machine, offline (`--pull=never --network none`). Case 12 passed before the fixes too: it is evidence for the lock, not a regression test.
Backend and frontend test suites were not run (no application code touched).

## Still unverified because no VM exists

- Real sshd: `restrict,command=`, behaviour of the remote command on disconnect (H1's fix is proven only against a closed pipe),
  `AcceptEnv` (L3), the whole of `vm-bootstrap.sh` including M2.
- `tee -p` under the VM's coreutils (8.32 here; Ubuntu 22.04/24.04 ship 8.32/9.4).
- Real Docker: `compose pull` by index digest with attestations, `docker image inspect` of the label on that reference, `compose ps -q`,
  the env-file message of the Compose version apt installs on the VM.
- Caddy/ACME timing against `FIRST_DEPLOY_HEALTH_TIMEOUT`, certificate verification against a real chain.
- The `deploy` job itself, Environment approval with secrets present, and M4.
- M1's proposed pre-step and every Flyway statement here rest on `075`'s local measurements, not re-run in this review.

## Engineering concept

A deploy script is a small state machine whose transitions must not depend on its observer: output is a side channel, so a broken pipe
must never decide whether the rollback runs (H1). And "we print nothing secret" has to hold for the tools the script calls, on their
error paths too, not only for its own `echo`s (H2).

## Next step

Lead: decide M1 (migrate as a pre-step) and M4, then run B' on a real VM with the list above as the checklist.
