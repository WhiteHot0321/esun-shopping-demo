#!/usr/bin/env bash
# Config-validation tests for the monitoring overlay (docs/tasks/061, docs/tasks/080).
#
#   bash scripts/test/monitoring-config.test.sh
#
# Needs only Docker (promtool/amtool run from the pinned images used by docker-compose.monitoring.yml). Checks:
#   1. alert rules parse and their promtool unit tests pass; prometheus.yml is valid with the rules mounted;
#   2. both Alertmanager configs pass amtool;
#   3. no committed Alertmanager/alerting file contains a literal URL or inline credential (only *_file references);
#   4. the default and webhook Alertmanager configs share an identical route and inhibit_rules (only receivers differ);
#   5. Compose: without the alerting overlay there is no receiver and no secret; with it but without
#      ALERT_WEBHOOK_URL_FILE rendering fails; with it the webhook config and the secret are wired, and the secret's
#      content never appears in the rendered config.
#
# Not covered: real delivery to a destination (none is configured on purpose). docs/tasks/080 records a local,
# throw-away delivery check against a container on a private Docker network.
set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PROM_IMAGE=prom/prometheus:v2.55.1
AM_IMAGE=prom/alertmanager:v0.27.0
export MSYS_NO_PATHCONV=1   # Git Bash on Windows: keep container paths such as /r untouched

# Host path as Docker expects it (Windows form under Git Bash, unchanged on Linux).
hostpath() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

pass=0; fail=0
ok()  { pass=$((pass + 1)); echo "PASS $1"; }
bad() { fail=$((fail + 1)); echo "FAIL $1"; [ -n "${2:-}" ] && printf '%s\n' "$2" | sed 's/^/     /'; }
check() { # name, command...
  local name="$1"; shift
  local out
  if out="$("$@" 2>&1)"; then ok "$name"; else bad "$name" "$out"; fi
}

PROM_DIR="$(hostpath "$ROOT/monitoring/prometheus")"
AM_DIR="$(hostpath "$ROOT/monitoring/alertmanager")"

# 1. Prometheus rules, rule unit tests, server config
check "promtool check rules" docker run --rm --entrypoint promtool -v "$PROM_DIR:/r:ro" -w /r "$PROM_IMAGE" check rules alerts.yml
check "promtool test rules" docker run --rm --entrypoint promtool -v "$PROM_DIR:/r:ro" -w /r "$PROM_IMAGE" test rules alerts.test.yml
check "promtool check config" docker run --rm --entrypoint promtool \
  -v "$PROM_DIR/prometheus.yml:/etc/prometheus/prometheus.yml:ro" -v "$PROM_DIR/alerts.yml:/etc/prometheus/alerts.yml:ro" \
  "$PROM_IMAGE" check config /etc/prometheus/prometheus.yml

# 2. Alertmanager configs
for f in alertmanager.yml alertmanager.webhook.yml; do
  check "amtool check-config $f" docker run --rm --entrypoint amtool -v "$AM_DIR:/c:ro" "$AM_IMAGE" check-config "/c/$f"
done

# 3. No literal destination or credential is committed
leaks="$(grep -nE '://|^[[:space:]]*-?[[:space:]]*(url|api_url|webhook_url|bot_token|auth_password|password|routing_key|service_key):' \
  "$ROOT"/monitoring/alertmanager/*.yml "$ROOT/docker-compose.alerting.yml" || true)"
if [ -z "$leaks" ]; then ok "no literal URL or credential in alerting config"; else bad "literal URL/credential found" "$leaks"; fi

# 4. Route/inhibit parity (strip comments and the receivers block, compare the rest)
shape() { sed -e 's/[[:space:]]*#.*$//' -e '/^receivers:/,/^$/d' -e '/^[[:space:]]*$/d' "$1"; }
if diff <(shape "$ROOT/monitoring/alertmanager/alertmanager.yml") <(shape "$ROOT/monitoring/alertmanager/alertmanager.webhook.yml") >/dev/null; then
  ok "default and webhook configs share route and inhibit_rules"
else
  bad "route/inhibit_rules drifted between the two Alertmanager configs" \
    "$(diff <(shape "$ROOT/monitoring/alertmanager/alertmanager.yml") <(shape "$ROOT/monitoring/alertmanager/alertmanager.webhook.yml"))"
fi
if grep -qE 'url_file:[[:space:]]*/run/secrets/alert_webhook_url' "$ROOT/monitoring/alertmanager/alertmanager.webhook.yml"; then
  ok "webhook receiver reads the URL from the Compose secret"
else
  bad "webhook receiver does not use url_file /run/secrets/alert_webhook_url"
fi
if grep -qE '_configs:' "$ROOT/monitoring/alertmanager/alertmanager.yml"; then
  bad "default alertmanager.yml must have no notification integration"
else
  ok "default alertmanager.yml has no notification integration"
fi

# 5. Compose wiring (dummy, non-secret values; same set as the CI config-validate job)
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
cat > "$work/ci.env" <<'EOF'
DB_NAME=ci
DB_PASSWORD=ci-not-a-secret
APP_DB_PASSWORD=ci-not-a-secret
REDIS_PASSWORD=ci-not-a-secret
JWT_SECRET=ci-not-a-secret
CORS_ALLOWED_ORIGINS=https://ci.example.com
PAYMENT_PROVIDER=none
GRAFANA_ADMIN_PASSWORD=ci-not-a-secret
EOF
marker="placeholder-secret-content-$$"
printf '%s\n' "$marker" > "$work/webhook-url"
ENVF="$(hostpath "$work/ci.env")"
compose() { (cd "$ROOT" && docker compose -f docker-compose.prod.yml -f docker-compose.monitoring.yml "$@" --env-file "$ENVF" config); }

if base="$(unset ALERT_WEBHOOK_URL_FILE; compose 2>&1)"; then
  if grep -q 'alertmanager.yml' <<<"$base" && ! grep -q 'alertmanager.webhook.yml' <<<"$base" && ! grep -q 'alert_webhook_url' <<<"$base"; then
    ok "default stack: no receiver config, no secret"
  else
    bad "default stack unexpectedly wires webhook delivery"
  fi
else
  bad "default stack does not render" "$base"
fi

if out="$(unset ALERT_WEBHOOK_URL_FILE; compose -f docker-compose.alerting.yml 2>&1)"; then
  bad "alerting overlay rendered without ALERT_WEBHOOK_URL_FILE (must fail closed)"
elif grep -q 'ALERT_WEBHOOK_URL_FILE' <<<"$out"; then
  ok "alerting overlay without ALERT_WEBHOOK_URL_FILE fails with a clear message"
else
  bad "alerting overlay failed for an unexpected reason" "$out"
fi

if wired="$(ALERT_WEBHOOK_URL_FILE="$(hostpath "$work/webhook-url")" compose -f docker-compose.alerting.yml 2>&1)"; then
  if grep -q 'alertmanager.webhook.yml' <<<"$wired" && grep -q 'alert_webhook_url' <<<"$wired"; then
    ok "alerting overlay wires the webhook config and the secret"
  else
    bad "alerting overlay rendered but is not wired" "$wired"
  fi
  if grep -q "$marker" <<<"$wired"; then bad "secret content leaked into the rendered config"; else ok "secret content stays out of the rendered config"; fi
else
  bad "alerting overlay with ALERT_WEBHOOK_URL_FILE does not render" "$wired"
fi

echo "monitoring-config: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
