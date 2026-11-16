#!/usr/bin/env bash
# Tests for scripts/check-trivy-expiry.sh (docs/tasks/086).
#
#   bash scripts/test/trivy-expiry.test.sh
#
# Fixtures are written to a throw-away directory and the clock is pinned with TODAY, so the result does not depend on the date
# the test runs. One case reads the real .trivyignore.yaml, but only structurally (the checker sees every entry); it never
# asserts a date, because those change every time an exception is renewed.
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHECK="$SCRIPT_DIR/../check-trivy-expiry.sh"
REAL="$SCRIPT_DIR/../../.trivyignore.yaml"
T="$(mktemp -d)"
trap 'rm -rf "$T"' EXIT

pass=0; fail=0
ok() { pass=$((pass + 1)); echo "  ok   $1"; }
bad() { fail=$((fail + 1)); echo "  FAIL $1"; }
assert_eq() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1 (expected '$3', got '$2')"; fi; }
assert_out() { if grep -qE -- "$2" <<<"$OUT"; then ok "$1"; else bad "$1 (output lacks /$2/)"; fi; }
assert_no_out() { if grep -qE -- "$2" <<<"$OUT"; then bad "$1 (output has /$2/)"; else ok "$1"; fi; }

# run <today> <file> [warn-days]: sets OUT and RC.
run() {
  local today="$1"; shift
  OUT="$(TODAY="$today" bash "$CHECK" "$@" 2>&1)"; RC=$?
}
# entry <id> <path> <expiry-line>: a minimal fixture entry (the expiry line is verbatim, so it can be empty).
entry() {
  printf '  - id: %s\n    paths: ["%s"]\n    statement: "fixture"\n' "$1" "$2"
  [ -n "$3" ] && printf '    %s\n' "$3"
  return 0
}
fixture() { local f="$T/$1.yaml"; { echo "vulnerabilities:"; cat; } >"$f"; echo "$f"; }

echo "window boundary (today 2026-11-16, warn window 14 days)"
f="$(entry CVE-A usr/bin/a 'expired_at: 2026-11-30' | fixture edge14)"
run 2026-11-16 "$f";   assert_eq "14 days ahead: exit 0" "$RC" 0; assert_out "14 days ahead: warns" '::warning title=Trivy exception expiring::CVE-A'
f="$(entry CVE-B usr/bin/b 'expired_at: 2026-12-01' | fixture edge15)"
run 2026-11-16 "$f";   assert_eq "15 days ahead: exit 0" "$RC" 0; assert_no_out "15 days ahead: silent" '::warning'
f="$(entry CVE-C usr/bin/c 'expired_at: 2026-11-16' | fixture today)"
run 2026-11-16 "$f";   assert_out "expires today: warns" '::warning title=Trivy exception expiring::CVE-C'
f="$(entry CVE-D usr/bin/d 'expired_at: 2026-11-15' | fixture past)"
run 2026-11-16 "$f";   assert_eq "expired: exit 0 (a date never fails the build)" "$RC" 0; assert_out "expired: warns as expired" '::warning title=Trivy exception expired::CVE-D usr/bin/d'
assert_out "expired: says how long ago" '1 day\(s\) ago'

echo "window argument"
f="$(entry CVE-E usr/bin/e 'expired_at: 2027-01-10' | fixture far)"
run 2026-11-16 "$f";      assert_no_out "55 days ahead, default window: silent" '::warning'
run 2026-11-16 "$f" 60;   assert_out "55 days ahead, 60-day window: warns" '::warning title=Trivy exception expiring::CVE-E'

echo "several entries, one comment that looks like an entry"
f="$T/mixed.yaml"
cat >"$f" <<'EOF'
# vulnerabilities:
#   - id: CVE-YYYY-NNNNN
#     expired_at: 2026-11-17
vulnerabilities:
  - id: CVE-F
    paths: ["x"]
    statement: >-
      folded text that mentions expired_at: 2026-11-17 inside a statement is not a field of its own line
    expired_at: 2027-06-01
  - id: CVE-G
    paths: ["y"]
    expired_at: 2026-11-20
  - id: CVE-H
    paths: ["z"]
    statement: &anchor >-
      anchored
    expired_at: 2026-11-10
EOF
run 2026-11-16 "$f"
assert_out "counts three real entries" 'checked 3 exception'
assert_out "far entry is ok" 'ok: CVE-F x expires on 2027-06-01'
assert_out "soon entry warns" '::warning title=Trivy exception expiring::CVE-G y'
assert_out "past entry warns" '::warning title=Trivy exception expired::CVE-H z'
assert_no_out "comment block is ignored" 'CVE-YYYY'

echo "entry without an expiry"
f="$(entry CVE-I usr/bin/i '' | fixture noexp)"
run 2026-11-16 "$f"; assert_eq "no expiry: exit 0" "$RC" 0; assert_out "no expiry: warns" '::warning title=Trivy exception without expiry::CVE-I'

echo "input the checker cannot trust fails loudly"
f="$(entry CVE-J usr/bin/j 'expired_at: 2026/12/14' | fixture badfmt)"
run 2026-11-16 "$f"; assert_eq "wrong format: exit 1" "$RC" 1; assert_out "wrong format: error" '::error title=Trivy expiry check::CVE-J'
f="$(entry CVE-K usr/bin/k 'expired_at: 2026-02-30' | fixture baddate)"
run 2026-11-16 "$f"; assert_eq "impossible date: exit 1" "$RC" 1; assert_out "impossible date: error" '::error title=Trivy expiry check::CVE-K'
f="$(entry CVE-L usr/bin/l 'expired_at: "2026-12-14"' | fixture quoted)"
run 2026-11-16 "$f"; assert_eq "quoted date: exit 1" "$RC" 1
run 2026-11-16 "$T/does-not-exist.yaml"; assert_eq "missing file: exit 1" "$RC" 1; assert_out "missing file: error" '::error'
run 2026-11-16 "$T/past.yaml" abc;       assert_eq "non-numeric window: exit 1" "$RC" 1
run not-a-date "$T/past.yaml";           assert_eq "bad TODAY: exit 1" "$RC" 1
printf 'vulnerabilities: []\n' >"$T/empty.yaml"
run 2026-11-16 "$T/empty.yaml"; assert_eq "no entries: exit 0" "$RC" 0

echo "the real .trivyignore.yaml"
run 2026-11-16 "$REAL"
assert_eq "real file parses: exit 0" "$RC" 0
want="$(grep -cE '^[[:space:]]*-[[:space:]]+id:' "$REAL")"
if [ "$want" -eq 0 ]; then assert_out "real file with no entries says so" 'no exceptions'
else assert_out "checker sees all $want real entries" "checked $want exception"; fi

echo
echo "passed: $pass, failed: $fail"
[ "$fail" -eq 0 ]
