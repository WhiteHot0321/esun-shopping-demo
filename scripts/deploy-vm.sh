#!/usr/bin/env bash
# VM deploy for the production Compose stack. Phase 3.3 #18 B3 (docs/tasks/075).
#
#   scripts/deploy-vm.sh deploy <40-hex commit sha> <backend digest> <frontend digest>
#   scripts/deploy-vm.sh rollback      # go back to the version that ran before the current one
#
# Normally started by /usr/local/sbin/esun-deploy-entry, the forced command of the GitHub Actions key; that entry point
# validates the arguments and clears the environment, so none of the tunables below can be set from the pipeline.
#
# Flow: lock -> permission checks -> fetch + ancestry checks -> checkout the exact sha -> pull ONLY backend/caddy by digest
# -> image revision label must equal the sha -> backup (skipped, loudly, on a first deploy) -> up -d -> health gate.
# A failed gate redeploys the last known good digests with `up -d --no-deps backend caddy` (no migrate rerun) and moves the
# checkout back. "Last known good" lives in $STATE_DIR/state and only changes after a passing gate.
#
# Exit codes: 0 deployed | 1 refused before anything was started (checkout restored) | 10 gate failed, rolled back and healthy
#             11 gate failed and the rollback also failed | 12 first deploy failed (nothing to roll back to; stack left as is)
#
# Output hygiene (the repository is public, so the Actions log is public): no `set -x`, `compose config -q` only, no
# `docker inspect` of whole containers, and no compose logs on failure.
set -euo pipefail

REPO_DIR="${REPO_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
PROJECT="${COMPOSE_PROJECT:-esun}"
ENV_FILE="${ENV_FILE:-$REPO_DIR/.env.prod}"
STATE_DIR="${STATE_DIR:-/var/lib/esun-deploy}"
IMAGE_BASE="${IMAGE_BASE:-ghcr.io/whitehot0321/esun-shopping-demo}"
DEPLOY_BRANCH="${DEPLOY_BRANCH:-advanced-v2}"
GIT_REMOTE="${GIT_REMOTE:-origin}"
HEALTH_PATH="${HEALTH_PATH:-/api/products/available}"
# The first deploy also waits for Caddy to obtain a certificate, hence the longer default.
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-180}"
FIRST_DEPLOY_HEALTH_TIMEOUT="${FIRST_DEPLOY_HEALTH_TIMEOUT:-300}"
HEALTH_INTERVAL="${HEALTH_INTERVAL:-3}"
BACKUP_PASSPHRASE_FILE="${BACKUP_PASSPHRASE_FILE:-$REPO_DIR/.backup-passphrase}"
BACKUP_DIR="${BACKUP_DIR:-$REPO_DIR/backups}"
STATE_FILE="$STATE_DIR/state"

SHA_RE='^[0-9a-f]{40}$'
DIGEST_RE='^sha256:[0-9a-f]{64}$'
DOMAIN_RE='^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$'

die() { echo "error: $*" >&2; exit 1; }
note() { echo "[deploy] $*"; }

# Reads KEY from the env file the way Compose does (drops a trailing " # comment", quotes and CR); prints only that value.
envval() { { grep -E "^$1=" "$ENV_FILE" || true; } | head -1 | cut -d= -f2- | sed -E -e 's/\r$//' -e 's/[[:space:]]+#.*$//' -e 's/[[:space:]]+$//' -e 's/^"(.*)"$/\1/' -e "s/^'(.*)'\$/\\1/"; }

require_secret_file_mode() { # <file>: owned by the running user, mode 600 (or 400)
  local m; m="$(stat -c '%a' "$1")"
  [ -O "$1" ] || die "$1 is not owned by the deploying user"
  case "$m" in 600|400) ;; *) die "$1 must be mode 600 (is $m)" ;; esac
}

state_get() { [ -f "$STATE_FILE" ] || return 0; { grep -E "^$1=" "$STATE_FILE" || true; } | head -1 | cut -d= -f2-; }

load_state() {
  CUR_SHA="$(state_get CURRENT_SHA)"; CUR_BE="$(state_get CURRENT_BACKEND)"; CUR_FE="$(state_get CURRENT_FRONTEND)"
  PREV_SHA="$(state_get PREVIOUS_SHA)"; PREV_BE="$(state_get PREVIOUS_BACKEND)"; PREV_FE="$(state_get PREVIOUS_FRONTEND)"
  # Everything read here ends up in git/docker arguments: refuse a state file that is not exactly what we wrote.
  local v
  for v in "$CUR_SHA" "$PREV_SHA"; do [ -z "$v" ] || [[ "$v" =~ $SHA_RE ]] || die "state file is corrupt (sha)"; done
  for v in "$CUR_BE" "$CUR_FE" "$PREV_BE" "$PREV_FE"; do [ -z "$v" ] || [[ "$v" =~ $DIGEST_RE ]] || die "state file is corrupt (digest)"; done
}

write_state() { # <cur sha> <cur backend> <cur frontend> <prev sha> <prev backend> <prev frontend>
  local tmp="$STATE_FILE.tmp.$$"
  ( umask 077
    printf 'CURRENT_SHA=%s\nCURRENT_BACKEND=%s\nCURRENT_FRONTEND=%s\nPREVIOUS_SHA=%s\nPREVIOUS_BACKEND=%s\nPREVIOUS_FRONTEND=%s\n' "$@" > "$tmp" )
  mv "$tmp" "$STATE_FILE"
}

use_images() { # <backend digest> <frontend digest>: Compose reads these from the process environment (it wins over the env file)
  BACKEND_IMAGE="$IMAGE_BASE/backend@$1"
  FRONTEND_IMAGE="$IMAGE_BASE/frontend@$2"
  export BACKEND_IMAGE FRONTEND_IMAGE
}

checkout_sha() { git checkout --quiet --detach "$1"; }

# Before `up` nothing of the running stack has changed, so refusing only has to put the working tree back.
abort_before_up() {
  echo "error: $*" >&2
  if [ -n "${ORIG_HEAD:-}" ]; then checkout_sha "$ORIG_HEAD" || echo "warning: could not restore the checkout to $ORIG_HEAD" >&2; fi
  exit 1
}

image_revision() { docker image inspect --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}' "$1" 2>/dev/null || true; }

# Backend must be healthy and the public path must answer 200 over HTTPS with a verified certificate (--resolve points the
# real hostname at this host, so the certificate is checked without relying on DNS having propagated).
health_gate() { # <timeout seconds>
  local end=$((SECONDS + $1)) backend_id state="none" code="000"
  while [ "$SECONDS" -lt "$end" ]; do
    backend_id="$("${COMPOSE[@]}" ps -q backend 2>/dev/null | head -1 || true)"
    state="none"; code="000"
    if [ -n "$backend_id" ]; then state="$(docker inspect -f '{{.State.Health.Status}}' "$backend_id" 2>/dev/null || echo none)"; fi
    if [ "$state" = healthy ]; then
      code="$(curl -sS --max-time 10 --resolve "$DOMAIN:443:127.0.0.1" -o /dev/null -w '%{http_code}' "https://$DOMAIN$HEALTH_PATH" 2>/dev/null || true)"
      code="${code:-000}"
    fi
    if [ "$state" = healthy ] && [ "$code" = 200 ]; then note "health gate passed (backend=$state, https://$DOMAIN$HEALTH_PATH=$code)"; return 0; fi
    sleep "$HEALTH_INTERVAL"
  done
  echo "health gate failed (backend=$state, https://$DOMAIN$HEALTH_PATH=$code)" >&2
  return 1
}

# Redeploy recorded digests. Never reruns `migrate`; the schema stays at whatever the failed attempt left (see docs/tasks/075).
redeploy() { # <sha> <backend digest> <frontend digest>
  checkout_sha "$1" || return 1
  use_images "$2" "$3"
  "${COMPOSE[@]}" pull backend caddy || note "pull failed; trying the images already on this host"
  "${COMPOSE[@]}" up -d --no-deps --no-build backend caddy || return 1
  health_gate "$HEALTH_TIMEOUT"
}

setup() {
  [ -d "$REPO_DIR/.git" ] || die "$REPO_DIR is not a git checkout"
  [ -f "$ENV_FILE" ] || die "cannot read $ENV_FILE"
  require_secret_file_mode "$ENV_FILE"
  if [ -e "$BACKUP_PASSPHRASE_FILE" ]; then require_secret_file_mode "$BACKUP_PASSPHRASE_FILE"; fi
  DOMAIN="$(envval DOMAIN)"
  [[ "$DOMAIN" =~ $DOMAIN_RE ]] || die "DOMAIN in $ENV_FILE must be a plain hostname"
  [ "$DOMAIN" != localhost ] || die "DOMAIN in $ENV_FILE is still localhost; set the real public hostname"
  [ -d "$STATE_DIR" ] && [ -w "$STATE_DIR" ] || die "state directory $STATE_DIR is missing or not writable (run vm-bootstrap.sh)"
  exec 9>"$STATE_DIR/lock"
  flock -n 9 || die "another deploy or rollback is already running"
  cd "$REPO_DIR"
  COMPOSE=(docker compose -p "$PROJECT" -f "$REPO_DIR/docker-compose.prod.yml" --env-file "$ENV_FILE")
  load_state
}

do_deploy() { # <sha> <backend digest> <frontend digest>
  local sha="$1" be="$2" fe="$3" first=0 timeout="$HEALTH_TIMEOUT" volume
  [[ "$sha" =~ $SHA_RE ]] || die "first argument must be a 40-character lowercase commit sha"
  [[ "$be" =~ $DIGEST_RE ]] && [[ "$fe" =~ $DIGEST_RE ]] || die "image arguments must be sha256:<64 hex> digests"
  setup
  if [ -z "$CUR_SHA" ]; then first=1; timeout="$FIRST_DEPLOY_HEALTH_TIMEOUT"; note "no last known good version recorded: this is a first deploy (no rollback target)"; fi

  git fetch --quiet "$GIT_REMOTE" "+refs/heads/$DEPLOY_BRANCH:refs/remotes/$GIT_REMOTE/$DEPLOY_BRANCH" || die "git fetch failed"
  git cat-file -e "$sha^{commit}" 2>/dev/null || die "commit $sha is not in the repository"
  git merge-base --is-ancestor "$sha" "refs/remotes/$GIT_REMOTE/$DEPLOY_BRANCH" || die "commit $sha is not on $DEPLOY_BRANCH"
  if [ -n "$CUR_SHA" ]; then
    git merge-base --is-ancestor "$CUR_SHA" "$sha" || die "refusing to deploy $sha: it is not a descendant of the deployed $CUR_SHA (no downgrades)"
  fi

  ORIG_HEAD="$(git rev-parse HEAD)"
  checkout_sha "$sha" || die "git checkout $sha failed (local changes in $REPO_DIR?)"
  use_images "$be" "$fe"

  grep -Eq '^[[:space:]]+SPRING_PROFILES_ACTIVE:[[:space:]]*prod[[:space:]]*$' docker-compose.prod.yml \
    || abort_before_up "docker-compose.prod.yml at $sha does not pin SPRING_PROFILES_ACTIVE: prod"
  "${COMPOSE[@]}" config -q || abort_before_up "compose configuration is invalid at $sha"

  "${COMPOSE[@]}" pull backend caddy || abort_before_up "could not pull the backend/frontend images"
  [ "$(image_revision "$BACKEND_IMAGE")" = "$sha" ] || abort_before_up "backend image revision label does not match $sha"
  [ "$(image_revision "$FRONTEND_IMAGE")" = "$sha" ] || abort_before_up "frontend image revision label does not match $sha"

  volume="${PROJECT}_mysql-data"
  if docker volume inspect "$volume" >/dev/null 2>&1; then
    local mysql_id mysql_name
    mysql_id="$("${COMPOSE[@]}" ps -q mysql | head -1 || true)"
    [ -n "$mysql_id" ] || abort_before_up "volume $volume exists but mysql is not running; start it first, then deploy"
    mysql_name="$(docker inspect -f '{{.Name}}' "$mysql_id" | sed 's#^/##')"
    MYSQL_CONTAINER="$mysql_name" ENV_FILE="$ENV_FILE" BACKUP_DIR="$BACKUP_DIR" BACKUP_PASSPHRASE_FILE="$BACKUP_PASSPHRASE_FILE" \
      scripts/mysql-backup.sh backup || abort_before_up "backup failed; deploy aborted"
  else
    note "FIRST DEPLOY: no $volume volume exists, so there is nothing to back up; backup skipped"
  fi

  note "starting the stack at $sha"
  if "${COMPOSE[@]}" up -d --no-build && health_gate "$timeout"; then
    write_state "$sha" "$be" "$fe" "$CUR_SHA" "$CUR_BE" "$CUR_FE"
    note "deploy ok: $sha"
    return 0
  fi

  echo "error: deploy of $sha failed" >&2
  if [ "$first" = 1 ]; then
    echo "error: first deploy: there is no previous version to roll back to; the stack is left as is for inspection" >&2
    exit 12
  fi
  note "rolling back to $CUR_SHA"
  if redeploy "$CUR_SHA" "$CUR_BE" "$CUR_FE"; then
    echo "error: rolled back to $CUR_SHA and it is healthy; the failed deploy is not recorded as good" >&2
    exit 10
  fi
  echo "error: rollback to $CUR_SHA also failed; inspect the stack on the host" >&2
  exit 11
}

do_rollback() {
  setup
  [ -n "$PREV_SHA" ] || die "no previous version recorded; nothing to roll back to"
  note "rolling back from $CUR_SHA to $PREV_SHA"
  if redeploy "$PREV_SHA" "$PREV_BE" "$PREV_FE"; then
    write_state "$PREV_SHA" "$PREV_BE" "$PREV_FE" "" "" ""
    note "rollback ok: $PREV_SHA"
    return 0
  fi
  echo "error: rollback to $PREV_SHA failed; returning to $CUR_SHA" >&2
  if redeploy "$CUR_SHA" "$CUR_BE" "$CUR_FE"; then exit 10; fi
  echo "error: could not return to $CUR_SHA either; inspect the stack on the host" >&2
  exit 11
}

main() {
  case "${1:-}" in
    deploy) [ "$#" -eq 4 ] || die "usage: $0 deploy <sha> <backend digest> <frontend digest>"; do_deploy "$2" "$3" "$4" ;;
    rollback) [ "$#" -eq 1 ] || die "usage: $0 rollback"; do_rollback ;;
    *) die "usage: $0 deploy <sha> <backend digest> <frontend digest> | rollback" ;;
  esac
}

# One line on purpose: bash must have parsed the whole command before the checkout swaps this file underneath it.
main "$@"; exit $?
