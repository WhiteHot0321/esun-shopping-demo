#!/usr/bin/env bash
# Stub-based tests for scripts/deploy-vm.sh and scripts/esun-deploy-entry (docs/tasks/075).
#
#   bash scripts/test/deploy-vm.test.sh
#
# `docker`, `curl` and `flock` are replaced by stubs on PATH; git is real (a throw-away bare remote plus clone). The docker stub
# models just enough state to make the gate honest: `up` records which backend image is "running", and that container only
# reports healthy (and curl only returns 200) when the image digest is not the deliberately bad one. So a failing gate and the
# rollback that follows are produced by the script's own control flow, not by a flag that skips the check.
#
# Not covered here (needs a real host): real Docker/Compose behaviour, real GHCR pulls, Caddy certificates, sshd forced
# commands, and flock contention (the flock stub always succeeds).
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY="$SCRIPT_DIR/../deploy-vm.sh"
ENTRY="$SCRIPT_DIR/../esun-deploy-entry"
BASE=ghcr.io/whitehot0321/esun-shopping-demo
GOOD1="sha256:$(printf '1%.0s' $(seq 64))"
GOOD2="sha256:$(printf '2%.0s' $(seq 64))"
FE1="sha256:$(printf '3%.0s' $(seq 64))"
FE2="sha256:$(printf '4%.0s' $(seq 64))"
BAD="sha256:$(printf 'b%.0s' $(seq 64))"

# deploy-vm.sh refuses secret files that are not mode 600, which Git Bash on Windows cannot represent: fail loudly instead of
# reporting a misleading result there. Run this under Linux or WSL (CI does).
probe="$(mktemp)"; chmod 600 "$probe"
if [ "$(stat -c '%a' "$probe")" != 600 ]; then rm -f "$probe"; echo "this filesystem has no POSIX file modes; run the test under Linux/WSL" >&2; exit 2; fi
rm -f "$probe"

pass=0; fail=0
ok() { pass=$((pass + 1)); echo "  ok   $1"; }
bad() { fail=$((fail + 1)); echo "  FAIL $1"; }
assert_eq() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1 (expected '$3', got '$2')"; fi; }
assert_out() { if grep -qE -- "$2" <<<"$OUT"; then ok "$1"; else bad "$1 (output lacks /$2/)"; fi; }
assert_log() { if grep -qE -- "$2" "$T/stub/log" 2>/dev/null; then ok "$1"; else bad "$1 (log lacks /$2/)"; fi; }
assert_no_log() { if grep -qE -- "$2" "$T/stub/log" 2>/dev/null; then bad "$1 (log has /$2/)"; else ok "$1"; fi; }
count_log() { grep -cE -- "$1" "$T/stub/log" 2>/dev/null || true; }
line_of() { grep -nE -- "$1" "$T/stub/log" | head -1 | cut -d: -f1; }
state_val() { grep -E "^$1=" "$T/state/state" 2>/dev/null | cut -d= -f2-; }
head_sha() { git -C "$T/repo" rev-parse HEAD; }

make_fixture() {
  T="$(mktemp -d)"
  mkdir -p "$T/stub" "$T/stubbin" "$T/state"
  : > "$T/stub/log"

  cat > "$T/stubbin/docker" <<'EOF'
#!/usr/bin/env bash
echo "docker $*" >> "$STUB_LOG"
running="$(cat "$STUB_DIR/running_backend" 2>/dev/null || true)"
case "$1" in
  compose)
    sub=""
    for a in "$@"; do case "$a" in config|pull|up|ps) sub="$a"; break ;; esac; done
    case "$sub" in
      config) exit 0 ;;
      pull) [ "${STUB_PULL_FAIL:-0}" = 1 ] && exit 1; exit 0 ;;
      up)
        printf '%s' "$BACKEND_IMAGE" > "$STUB_DIR/running_backend"
        echo "UP flags=[$*] BACKEND_IMAGE=$BACKEND_IMAGE FRONTEND_IMAGE=$FRONTEND_IMAGE" >> "$STUB_LOG"
        exit 0 ;;
      ps) echo "cid-${*: -1}"; exit 0 ;;
    esac ;;
  inspect)
    case "$*" in
      *Health.Status*) if [[ "$running" == *bbbbbbbb* ]]; then echo unhealthy; else echo healthy; fi ;;
      *.Name*) echo /esun-mysql-1 ;;
    esac ;;
  volume) [ -f "$STUB_DIR/has_volume" ]; exit $? ;;
  image) case "${*: -1}" in */backend@*) echo "${STUB_REVISION_BACKEND:-${STUB_REVISION:-}}" ;; */frontend@*) echo "${STUB_REVISION_FRONTEND:-${STUB_REVISION:-}}" ;; esac ;;
esac
EOF
  cat > "$T/stubbin/curl" <<'EOF'
#!/usr/bin/env bash
echo "curl $*" >> "$STUB_LOG"
if [[ "$(cat "$STUB_DIR/running_backend" 2>/dev/null)" == *bbbbbbbb* ]]; then printf 503; else printf 200; fi
EOF
  printf '#!/usr/bin/env bash\nexit 0\n' > "$T/stubbin/flock"
  chmod +x "$T/stubbin/"*

  git init -q --bare "$T/remote.git"
  git clone -q "$T/remote.git" "$T/repo" 2>/dev/null
  (
    cd "$T/repo" || exit 1
    git config user.email t@example.com; git config user.name t; git config core.autocrlf false
    git checkout -q -b advanced-v2
    mkdir -p scripts
    printf 'services:\n  backend:\n    environment:\n      SPRING_PROFILES_ACTIVE: prod\n' > docker-compose.prod.yml
    # shellcheck disable=SC2016 # the stub script must expand these when it runs, not now
    printf '#!/usr/bin/env bash\necho "backup MYSQL_CONTAINER=$MYSQL_CONTAINER cmd=$1" >> "$STUB_LOG"\n' > scripts/mysql-backup.sh
    chmod +x scripts/mysql-backup.sh
    printf '.env.prod\n.backup-passphrase\nbackups/\n' > .gitignore
    git add -A && git commit -q -m c1
    echo two > f.txt && git add -A && git commit -q -m c2
    echo three > f.txt && git add -A && git commit -q -m c3
    git push -q origin advanced-v2 2>/dev/null
  )
  SHA1="$(git -C "$T/repo" rev-parse HEAD~2)"; SHA2="$(git -C "$T/repo" rev-parse HEAD~1)"; SHA3="$(git -C "$T/repo" rev-parse HEAD)"
  git -C "$T/repo" checkout -q --detach "$SHA1"   # like a fresh bootstrap clone: not at any deployed version
  printf 'DB_NAME=esun\nDOMAIN=shop.example.com   # real hostname\n' > "$T/repo/.env.prod"
  chmod 600 "$T/repo/.env.prod"
}

# run_deploy <script args...> (extra env via the STUB_* variables exported by the caller)
run_deploy() {
  OUT="$(env REPO_DIR="$T/repo" ENV_FILE="$T/repo/.env.prod" STATE_DIR="$T/state" HEALTH_TIMEOUT=1 FIRST_DEPLOY_HEALTH_TIMEOUT=1 \
        HEALTH_INTERVAL=0.2 BACKUP_PASSPHRASE_FILE="$T/repo/.backup-passphrase" BACKUP_DIR="$T/repo/backups" \
        STUB_DIR="$T/stub" STUB_LOG="$T/stub/log" PATH="$T/stubbin:$PATH" bash "$DEPLOY" "$@" 2>&1)"
  RC=$?
}

echo "== 1. first deploy succeeds (no mysql volume yet)"
make_fixture
STUB_REVISION="$SHA2" run_deploy deploy "$SHA2" "$GOOD1" "$FE1"
assert_eq "exit 0" "$RC" 0
assert_out "first deploy says the backup is skipped" "FIRST DEPLOY.*backup skipped"
assert_no_log "no backup was attempted" "^backup "
assert_log "backend started by digest" "BACKEND_IMAGE=$BASE/backend@$GOOD1"
assert_log "frontend started by digest" "FRONTEND_IMAGE=$BASE/frontend@$FE1"
assert_log "only backend and caddy are pulled" "compose .*pull backend caddy$"
assert_eq "no whole-stack pull" "$(grep -cE 'compose .* pull$' "$T/stub/log")" 0
assert_log "gate resolves the real hostname to this host over https" "curl .*--resolve shop.example.com:443:127.0.0.1 .*https://shop.example.com/api/products/available"
assert_no_log "certificate verification is never disabled" "curl (.* )?(-k|--insecure)( |$)"
assert_eq "checkout is at the deployed sha" "$(head_sha)" "$SHA2"
assert_eq "state records the version" "$(state_val CURRENT_SHA)" "$SHA2"
assert_eq "no previous version yet" "$(state_val PREVIOUS_SHA)" ""

echo "== 2. second deploy: backup first, state shifts"
touch "$T/stub/has_volume"; : > "$T/stub/log"
STUB_REVISION="$SHA3" run_deploy deploy "$SHA3" "$GOOD2" "$FE2"
assert_eq "exit 0" "$RC" 0
assert_log "backup runs against the running mysql container" "^backup MYSQL_CONTAINER=esun-mysql-1 cmd=backup"
b="$(line_of '^backup ')"; u="$(line_of '^UP ')"
if [ -n "$b" ] && [ -n "$u" ] && [ "$b" -lt "$u" ]; then ok "backup happens before up"; else bad "backup happens before up (backup line=$b, up line=$u)"; fi
assert_eq "current is the new version" "$(state_val CURRENT_SHA)" "$SHA3"
assert_eq "previous is the old version" "$(state_val PREVIOUS_SHA)" "$SHA2"
assert_eq "previous backend digest kept" "$(state_val PREVIOUS_BACKEND)" "$GOOD1"
assert_eq "checkout at the new sha" "$(head_sha)" "$SHA3"

echo "== 3. gate fails -> automatic rollback to the last good version"
make_fixture
STUB_REVISION="$SHA2" run_deploy deploy "$SHA2" "$GOOD1" "$FE1"
touch "$T/stub/has_volume"; : > "$T/stub/log"; before="$(cat "$T/state/state")"
STUB_REVISION="$SHA3" run_deploy deploy "$SHA3" "$BAD" "$FE2"
assert_eq "exit 10 (failed, rolled back, healthy)" "$RC" 10
assert_out "says it rolled back" "rolled back to $SHA2"
assert_eq "exactly one rollback up, with --no-deps" "$(count_log '^UP flags=\[.*--no-deps.*backend caddy')" 1
assert_log "rollback uses the previous good backend digest" "^UP flags=\[.*--no-deps.* BACKEND_IMAGE=$BASE/backend@$GOOD1"
assert_eq "migrate is not rerun: only the failed attempt did a full up" "$(count_log '^UP ' )" 2
assert_eq "checkout moved back" "$(head_sha)" "$SHA2"
assert_eq "state still points at the last good version" "$(cat "$T/state/state")" "$before"
assert_no_log "no compose logs are dumped" "compose .* logs"

echo "== 4. first deploy fails the gate -> nothing to roll back to"
make_fixture
STUB_REVISION="$SHA2" run_deploy deploy "$SHA2" "$BAD" "$FE1"
assert_eq "exit 12" "$RC" 12
assert_out "explains there is no previous version" "no previous version to roll back to"
assert_eq "no rollback up was attempted" "$(count_log '--no-deps')" 0
assert_eq "no state recorded as good" "$(state_val CURRENT_SHA)" ""

echo "== 5. refused: downgrade, and a commit that is not on advanced-v2"
make_fixture
STUB_REVISION="$SHA2" run_deploy deploy "$SHA2" "$GOOD1" "$FE1"
: > "$T/stub/log"
STUB_REVISION="$SHA1" run_deploy deploy "$SHA1" "$GOOD2" "$FE2"
assert_eq "downgrade refused (exit 1)" "$RC" 1
assert_out "says why" "not a descendant"
assert_eq "nothing pulled or started" "$(count_log '^UP |pull')" 0
assert_eq "checkout unchanged" "$(head_sha)" "$SHA2"
git -C "$T/repo" checkout -q --detach "$SHA3" && git -C "$T/repo" commit -q --allow-empty -m local-only
LOCAL="$(git -C "$T/repo" rev-parse HEAD)"; git -C "$T/repo" checkout -q --detach "$SHA2"
STUB_REVISION="$LOCAL" run_deploy deploy "$LOCAL" "$GOOD2" "$FE2"
assert_eq "unpushed commit refused" "$RC" 1
assert_out "says it is not on the branch" "is not on advanced-v2"
assert_eq "still nothing started" "$(count_log '^UP |pull')" 0

echo "== 6. refused: image revision label does not match the sha"
make_fixture
STUB_REVISION="$SHA1" run_deploy deploy "$SHA2" "$GOOD1" "$FE1"
assert_eq "exit 1" "$RC" 1
assert_out "names the label" "revision label does not match"
assert_eq "nothing started" "$(count_log '^UP ')" 0
assert_eq "checkout restored" "$(head_sha)" "$SHA1"
assert_eq "no state written" "$(state_val CURRENT_SHA)" ""
STUB_REVISION_BACKEND="$SHA2" STUB_REVISION_FRONTEND="$SHA1" run_deploy deploy "$SHA2" "$GOOD1" "$FE1"
assert_eq "frontend label mismatch alone is refused" "$RC" 1
assert_out "names the label (frontend)" "frontend image revision label"
STUB_REVISION_BACKEND="$SHA1" STUB_REVISION_FRONTEND="$SHA2" run_deploy deploy "$SHA2" "$GOOD1" "$FE1"
assert_eq "backend label mismatch alone is refused" "$RC" 1
assert_out "names the label (backend)" "backend image revision label"
assert_eq "still nothing started" "$(count_log '^UP ')" 0

echo "== 7. refused: loose secret-file permissions, localhost domain, bad arguments"
make_fixture
chmod 644 "$T/repo/.env.prod"
STUB_REVISION="$SHA2" run_deploy deploy "$SHA2" "$GOOD1" "$FE1"
assert_eq ".env.prod mode 644 refused" "$RC" 1
assert_out "says mode 600 is required" "must be mode 600"
assert_eq "docker never called" "$(count_log '^docker ')" 0
chmod 600 "$T/repo/.env.prod"; printf 'x' > "$T/repo/.backup-passphrase"; chmod 644 "$T/repo/.backup-passphrase"
STUB_REVISION="$SHA2" run_deploy deploy "$SHA2" "$GOOD1" "$FE1"
assert_eq ".backup-passphrase mode 644 refused" "$RC" 1
chmod 600 "$T/repo/.backup-passphrase"
printf 'DOMAIN=localhost\n' > "$T/repo/.env.prod"
STUB_REVISION="$SHA2" run_deploy deploy "$SHA2" "$GOOD1" "$FE1"
assert_eq "DOMAIN=localhost refused" "$RC" 1
printf 'DOMAIN=shop.example.com\n' > "$T/repo/.env.prod"
run_deploy deploy "${SHA2:0:39}" "$GOOD1" "$FE1";   assert_eq "short sha refused" "$RC" 1
run_deploy deploy "$SHA2" "sha256:zz" "$FE1";        assert_eq "malformed backend digest refused" "$RC" 1
run_deploy deploy "$SHA2" "$GOOD1";                  assert_eq "missing argument refused" "$RC" 1
run_deploy frobnicate;                               assert_eq "unknown command refused" "$RC" 1

echo "== 8. manual rollback goes to the previous version once"
make_fixture
STUB_REVISION="$SHA2" run_deploy deploy "$SHA2" "$GOOD1" "$FE1"
touch "$T/stub/has_volume"
STUB_REVISION="$SHA3" run_deploy deploy "$SHA3" "$GOOD2" "$FE2"
: > "$T/stub/log"
run_deploy rollback
assert_eq "exit 0" "$RC" 0
assert_log "rollback up has --no-deps and the previous digest" "^UP flags=\[.*--no-deps.* BACKEND_IMAGE=$BASE/backend@$GOOD1"
assert_eq "checkout at the previous sha" "$(head_sha)" "$SHA2"
assert_eq "current is now the rolled-back version" "$(state_val CURRENT_SHA)" "$SHA2"
assert_eq "no previous left" "$(state_val PREVIOUS_SHA)" ""
run_deploy rollback
assert_eq "second rollback refused" "$RC" 1
assert_out "says nothing to roll back to" "nothing to roll back to"

echo "== 9. forced-command entry point"
make_fixture
sed "s#^readonly DEPLOY_SCRIPT=.*#readonly DEPLOY_SCRIPT=$T/fake-deploy#" "$ENTRY" > "$T/entry"
printf '#!/usr/bin/env bash\necho "called $*" > "%s/entry-called"\nprintenv | grep -E "^(HEALTH_TIMEOUT|BACKEND_IMAGE|STATE_DIR)=" >> "%s/entry-called" || true\n' "$T" "$T" > "$T/fake-deploy"
chmod +x "$T/fake-deploy"
entry() { rm -f "$T/entry-called"; OUT="$(env SSH_ORIGINAL_COMMAND="$1" HEALTH_TIMEOUT=999 BACKEND_IMAGE=evil STATE_DIR=/evil bash "$T/entry" 2>&1)"; RC=$?; }
entry "deploy $SHA2 $GOOD1 $FE1"
assert_eq "valid deploy accepted" "$RC" 0
assert_eq "arguments passed through" "$(head -1 "$T/entry-called")" "called deploy $SHA2 $GOOD1 $FE1"
assert_eq "caller environment is not passed on" "$(tail -n +2 "$T/entry-called" | wc -l | tr -d ' ')" 0
entry "rollback"
assert_eq "rollback accepted" "$RC" 0
assert_eq "rollback arguments" "$(head -1 "$T/entry-called")" "called rollback"
nl=$'\n'
while IFS= read -r c; do
  entry "$c"
  if [ "$RC" = 2 ] && [ ! -e "$T/entry-called" ]; then ok "rejected: ${c//$nl/\\n}"; else bad "rejected: ${c//$nl/\\n} (rc=$RC)"; fi
done <<EOF
bash
ls -la
 deploy $SHA2 $GOOD1 $FE1
echo hi; deploy $SHA2 $GOOD1 $FE1
deploy $SHA2 $GOOD1 $FE1; id
deploy $SHA2 $GOOD1 $FE1 extra
deploy $SHA2 $GOOD1
deploy ${SHA2^^} $GOOD1 $FE1
deploy ${SHA2:0:39} $GOOD1 $FE1
deploy $SHA2 sha256:zz $FE1
rollback now
rollback; id
EOF
entry "deploy $SHA2 $GOOD1 $FE1${nl}rm -rf /"
if [ "$RC" = 2 ] && [ ! -e "$T/entry-called" ]; then ok "rejected: embedded newline"; else bad "rejected: embedded newline (rc=$RC)"; fi
OUT="$(env -u SSH_ORIGINAL_COMMAND bash "$T/entry" 2>&1)"; RC=$?
if [ "$RC" = 2 ] && [ ! -e "$T/entry-called" ]; then ok "rejected: interactive login (no command)"; else bad "rejected: interactive login (rc=$RC)"; fi

echo
echo "passed: $pass, failed: $fail"
[ "$fail" -eq 0 ]
