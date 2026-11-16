#!/usr/bin/env bash
# Warns when an exception in .trivyignore.yaml is about to expire, or already has (docs/tasks/086).
#
#   bash scripts/check-trivy-expiry.sh [file] [warn-days]      defaults: .trivyignore.yaml, 14
#
# Why: Trivy silently stops applying an expired entry, so an expiry shows up as a red gate on some unrelated push (it did on
# 2026-11-05 and was only noticed by hand). This prints a GitHub Actions ::warning:: annotation `warn-days` ahead instead.
# Annotations are readable without signing in, unlike run logs.
#
# It never fails the build because of a date. It fails only on input it cannot read (missing file, an expired_at that is not a
# real YYYY-MM-DD date): a checker that quietly stops checking is worse than none.
#
# TODAY=YYYY-MM-DD overrides the current (UTC) date; scripts/test/trivy-expiry.test.sh uses it.
set -u

FILE="${1:-.trivyignore.yaml}"
WARN_DAYS="${2:-14}"

fail() { echo "::error title=Trivy expiry check::$1"; exit 1; }

[ -f "$FILE" ] || fail "$FILE not found"
case "$WARN_DAYS" in '' | *[!0-9]*) fail "warn-days must be a whole number, got '$WARN_DAYS'" ;; esac

TODAY="${TODAY:-$(date -u +%F)}"
today_epoch="$(date -u -d "$TODAY" +%s 2>/dev/null)" || fail "TODAY='$TODAY' is not a valid date"

# One line per entry: id|paths|expired_at (empty when the entry has none). Comment lines are skipped, so the example block in
# the file header is not mistaken for a real entry. (`exp` is an awk built-in, hence `expiry`.)
entries="$(awk '
  /^[[:space:]]*#/ { next }
  /^[[:space:]]*-[[:space:]]+id:/ {
    if (id != "") print id "|" paths "|" expiry
    id = $0; sub(/^[[:space:]]*-[[:space:]]+id:[[:space:]]*/, "", id); sub(/[[:space:]]+$/, "", id)
    paths = ""; expiry = ""; next
  }
  /^[[:space:]]+paths:/ { paths = $0; sub(/^[[:space:]]+paths:[[:space:]]*/, "", paths); sub(/[[:space:]]+$/, "", paths); next }
  /^[[:space:]]+expired_at:/ { expiry = $0; sub(/^[[:space:]]+expired_at:[[:space:]]*/, "", expiry); sub(/[[:space:]]+$/, "", expiry); next }
  END { if (id != "") print id "|" paths "|" expiry }
' "$FILE")" || fail "could not parse $FILE"

# Cross-check against a second, independent count: if the parser ever drops an entry (or dies, as it once did on a reserved
# awk word) this must not read as "nothing to check".
expected="$(grep -cE '^[[:space:]]*-[[:space:]]+id:' "$FILE")"
found=0
[ -n "$entries" ] && found="$(printf '%s\n' "$entries" | wc -l | tr -d '[:space:]')"
[ "$found" -eq "$expected" ] || fail "parsed $found exception(s) from $FILE but it contains $expected '- id:' line(s)"

if [ "$found" -eq 0 ]; then
  echo "no exceptions in $FILE"
  exit 0
fi

checked=0
attention=0
invalid=0
while IFS='|' read -r id paths exp; do
  checked=$((checked + 1))
  paths="$(printf '%s' "$paths" | tr -d '[]"')" # paths: ["usr/bin/caddy"] -> usr/bin/caddy, for display only
  label="$id ${paths:+$paths}"

  if [ -z "$exp" ]; then
    attention=$((attention + 1))
    echo "::warning title=Trivy exception without expiry::$label has no expired_at, so it never lapses. Every exception needs an expiry (see the header of $FILE)."
    continue
  fi

  case "$exp" in
    [0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]) ;;
    *) echo "::error title=Trivy expiry check::$label: expired_at '$exp' is not a bare YYYY-MM-DD date"; invalid=1; continue ;;
  esac
  exp_epoch="$(date -u -d "$exp" +%s 2>/dev/null)" || { echo "::error title=Trivy expiry check::$label: expired_at '$exp' is not a real date"; invalid=1; continue; }

  days=$(((exp_epoch - today_epoch) / 86400))
  if [ "$days" -lt 0 ]; then
    attention=$((attention + 1))
    echo "::warning title=Trivy exception expired::$label expired on $exp ($((-days)) day(s) ago) and Trivy no longer applies it. Delete it if the gate passes without it, otherwise re-decide and renew it."
  elif [ "$days" -le "$WARN_DAYS" ]; then
    attention=$((attention + 1))
    echo "::warning title=Trivy exception expiring::$label expires on $exp (in $days day(s)). Re-scan against the current base images and either delete it or re-decide and renew it before then."
  else
    echo "ok: $label expires on $exp (in $days day(s))"
  fi
done <<<"$entries"

echo "checked $checked exception(s) in $FILE; $attention need attention (warn window: $WARN_DAYS day(s), today: $TODAY)"
[ "$invalid" -eq 0 ] || exit 1
exit 0
