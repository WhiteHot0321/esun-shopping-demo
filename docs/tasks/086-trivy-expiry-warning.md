# 086 — Warn in CI before a Trivy exception expires

Date: 2026-11-16 (Asia/Taipei). Executor: Claude Code. Branch: `chore/trivy-expiry-warning` (from `advanced-v2` `c90de1b`).
Follows task 083 ("Next step": a check that warns before the next expiry). Not merged; `docs/project-state.md` untouched.

## Task

Make CI say so 14 days before an exception in `.trivyignore.yaml` expires, instead of the expiry being found as a red gate.

## Problem

Trivy stops applying an entry the moment `expired_at` passes. Nothing announces that: the first symptom is `supply-chain-gate` or
`docker-build` going red on a push that has nothing to do with it. On 2026-11-05 eight entries lapsed this way and it was noticed
by hand on 2026-11-16 (task 083). Two more dates are ahead: CVE-2026-78661 on **2026-11-30**, the two `spring-webmvc` entries on
**2026-12-14**.

## Root cause

An exception is a decision with a shelf life, but the only thing enforcing the shelf life was the scanner's own failure, which arrives
late and without saying which entry caused it.

## Solution

- `scripts/check-trivy-expiry.sh [file] [warn-days]` (defaults `.trivyignore.yaml`, 14). Per entry it prints a GitHub Actions
  annotation: `::warning` when the entry expires within the window, has already expired, or has no `expired_at`; a plain `ok:` line
  otherwise. Annotations are readable without signing in, unlike run logs.
- It **never fails on a date**. It exits 1 only for input it cannot trust: missing file, an `expired_at` that is not a bare real
  `YYYY-MM-DD` date, or an entry count that disagrees with a second, independent count of `- id:` lines.
- CI: two steps at the top of the `supply-chain-gate` job, before the scan so the warning survives a red scan: the checker, then its
  test. Both scripts were added to the existing `bash -n` / `shellcheck` list in `deploy-script-test`.
- `scripts/test/trivy-expiry.test.sh`: 29 assertions with the clock pinned through `TODAY`, so they do not depend on the day they run.
- `.trivyignore.yaml` header documents the date format and the check.

With today's real file the checker reports the two Spring entries as `ok` and warns on CVE-2026-78661 (14 days out on 2026-11-16, so
the first CI run of this branch already shows the warning).

## Engineering concept

A check that fails quietly is worse than no check. While writing this the first version used `exp` as an awk variable name; `exp` is a
built-in function, awk exited with a syntax error, and the script reported "no exceptions" and exit 0 — the exact silent pass it is
meant to prevent. The fix was not only the rename: the parser's result is now cross-checked against an independent `grep` count of
entries and the awk exit status is checked, so a parser that drops or loses entries fails the job. The mutation runs below show the
tests would have caught the date logic being wrong too.

## Test result

Local, not on GitHub Actions yet:

| Check | Result |
|---|---|
| `bash scripts/test/trivy-expiry.test.sh`, Git Bash on Windows (GNU awk 5.0) | **29 passed, 0 failed** |
| same, in `ubuntu:24.04` (mawk 1.3.4, GNU date 9.4 — the runner's defaults) | **29 passed, 0 failed**; real file with `TODAY=2026-11-20` gives the same output |
| `shellcheck -x` on both scripts (`koalaman/shellcheck:stable` in Docker) | exit 0, no findings |
| Mutation: `-le` → `-lt` in the window test | 1 failure (the 14-day boundary) |
| Mutation: invalid dates no longer exit 1 | 3 failures (wrong format, impossible date, quoted date) |
| Mutation reverted | 29 passed |
| `.github/workflows/ci.yml` and `.trivyignore.yaml` | parse as YAML |
| Real file, `TODAY=2026-11-20` | warns on CVE-2026-78661 (10 days); Spring entries `ok` (24 days); exit 0 |

Not verified: a GitHub Actions run of the new steps (see Next step). The container run is not the runner image itself.

## Trade-offs

- It warns, it does not fail. A hard fail 14 days early would block unrelated work for two weeks; a warning that nobody reads would
  not have helped either, which is why the annotation shows on the run summary and why the window is 14 days, not 3.
- It only sees dates. An entry whose finding has already disappeared (task 083's eight entries) is not detected before its expiry;
  that still needs a scan with an empty ignore file.
- It runs only when CI runs. A `schedule:` trigger was not added: scheduled workflows run from the default branch (`main`), not from
  `advanced-v2`, so it would not exercise this workflow file. Push frequency on `advanced-v2` is the substitute.

## Next step

1. CI confirms the two new steps on a real runner (annotation visible, `deploy-script-test` shellcheck green).
2. By **2026-11-30**: re-scan; delete the CVE-2026-78661 entry if a newer Caddy image carries `x/net` >= 0.62.0, otherwise re-decide.
3. By **2026-12-14**: open the Spring Boot 4 / Framework 7 tracking issue and replace `tracking: docs/tasks/083` with it.
