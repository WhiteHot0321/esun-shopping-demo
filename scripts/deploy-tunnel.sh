#!/usr/bin/env bash
# Local deploy for the tunnel-mode stack. Phase 3.3 #18 B3 (no-VM variant, see docs/tasks/060).
#
#   scripts/deploy-tunnel.sh deploy     # backup -> remember current images -> build+up -> health gate -> auto-rollback on failure
#   scripts/deploy-tunnel.sh rollback   # redeploy the images remembered by the last deploy (no build)
#
# Environment (all optional):
#   COMPOSE_PROJECT   Compose project name                      (default: esun-tunnel)
#   ENV_FILE          env file passed to Compose                (default: ./.env.prod)
#   HEALTH_URL        URL that must return 200 through Caddy    (default: http://127.0.0.1:8088/)
#   HEALTH_TIMEOUT    seconds to wait for the gate              (default: 120)
#   BACKUP_SKIP=1     skip the pre-deploy backup (never the default)
#   DRY_RUN=1         print the docker/backup commands instead of running them
set -euo pipefail
export MSYS_NO_PATHCONV=1

PROJECT="${COMPOSE_PROJECT:-esun-tunnel}"
ENV_FILE="${ENV_FILE:-./.env.prod}"
HEALTH_URL="${HEALTH_URL:-http://127.0.0.1:8088/}"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-120}"
BACKEND_REPO="${BACKEND_REPO:-esun-shopping-backend}"
FRONTEND_REPO="${FRONTEND_REPO:-esun-shopping-frontend}"
COMPOSE=(docker compose -p "$PROJECT" -f docker-compose.prod.yml -f docker-compose.tunnel.yml --env-file "$ENV_FILE")

die() { echo "error: $*" >&2; exit 1; }
run() { if [ "${DRY_RUN:-0}" = 1 ]; then echo "[dry-run] $*"; else "$@"; fi; }

health_gate() {
  [ "${DRY_RUN:-0}" = 1 ] && { echo "[dry-run] health gate $HEALTH_URL"; return 0; }
  local end=$((SECONDS + HEALTH_TIMEOUT)) backend_id state code
  while [ "$SECONDS" -lt "$end" ]; do
    backend_id="$("${COMPOSE[@]}" ps -q backend 2>/dev/null || true)"
    state="$([ -n "$backend_id" ] && docker inspect -f '{{.State.Health.Status}}' "$backend_id" 2>/dev/null || echo none)"
    code="$(curl -s -o /dev/null -w '%{http_code}' "$HEALTH_URL" || true)"
    if [ "$state" = healthy ] && [ "$code" = 200 ]; then echo "health gate passed (backend=$state, $HEALTH_URL=$code)"; return 0; fi
    sleep 3
  done
  echo "health gate failed (backend=${state:-?}, $HEALTH_URL=${code:-?})" >&2
  return 1
}

redeploy_previous() {
  docker image inspect "$BACKEND_REPO:previous" >/dev/null 2>&1 || die "no $BACKEND_REPO:previous image; nothing to roll back to"
  docker image inspect "$FRONTEND_REPO:previous" >/dev/null 2>&1 || die "no $FRONTEND_REPO:previous image; nothing to roll back to"
  echo "rolling back to the previous images"
  BACKEND_IMAGE="$BACKEND_REPO:previous" FRONTEND_IMAGE="$FRONTEND_REPO:previous" run "${COMPOSE[@]}" up -d --no-build backend caddy
  health_gate
}

cmd="${1:-deploy}"
[ -r "$ENV_FILE" ] || die "cannot read $ENV_FILE"
case "$cmd" in
  rollback) redeploy_previous ;;
  deploy)
    "${COMPOSE[@]}" config -q || die "compose config invalid"
    if [ "${BACKUP_SKIP:-0}" = 1 ]; then echo "warning: backup skipped"
    else
      MYSQL_CONTAINER="$("${COMPOSE[@]}" ps -q mysql | head -1)"
      [ -n "$MYSQL_CONTAINER" ] || die "mysql is not running; start the stack once or set BACKUP_SKIP=1"
      MYSQL_CONTAINER="$(docker inspect -f '{{.Name}}' "$MYSQL_CONTAINER" | sed 's#^/##')"
      MYSQL_CONTAINER="$MYSQL_CONTAINER" ENV_FILE="$ENV_FILE" run scripts/mysql-backup.sh backup || die "backup failed; deploy aborted"
    fi
    # Remember what is running now so a failed gate (or a later `rollback`) can return to it.
    for pair in "$BACKEND_REPO:backend" "$FRONTEND_REPO:caddy"; do
      repo="${pair%%:*}"; svc="${pair##*:}"
      id="$("${COMPOSE[@]}" images -q "$svc" 2>/dev/null | head -1 || true)"
      if [ -n "$id" ]; then run docker tag "$id" "$repo:previous"; else echo "note: no running $svc image to remember"; fi
    done
    run "${COMPOSE[@]}" up -d --build
    if health_gate; then echo "deploy ok"
    else echo "deploy failed the health gate" >&2; redeploy_previous && die "rolled back to the previous images" || die "rollback also failed; inspect '${COMPOSE[*]} ps'"
    fi ;;
  *) die "usage: $0 [deploy|rollback]" ;;
esac
