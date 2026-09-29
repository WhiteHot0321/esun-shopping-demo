#!/usr/bin/env bash
# Encrypted MySQL backups for the Compose stack (Linux/macOS/Git Bash). Phase 3.3 #21 SEC-04.
#
#   MYSQL_CONTAINER=<name> scripts/mysql-backup.sh backup
#   MYSQL_CONTAINER=<name> scripts/mysql-backup.sh restore <file.sql.gz.enc> <target-database> --force
#   MYSQL_CONTAINER=<name> scripts/mysql-backup.sh drill      # backup -> restore into a scratch DB -> compare -> drop it
#   scripts/mysql-backup.sh prune                              # apply retention only
#
# Environment (all optional except MYSQL_CONTAINER):
#   BACKUP_DIR              where encrypted dumps are written              (default: ./backups, git-ignored)
#   BACKUP_PASSPHRASE_FILE  file holding the encryption passphrase, mode 600 (default: ./.backup-passphrase, git-ignored)
#   BACKUP_KEEP             newest N generations to keep                    (default: 14)
#   BACKUP_OFFHOST_CMD      command run after each backup with the file path as $1, e.g. 'rclone copyto "$1" remote:bucket/'
#   ENV_FILE                file with DB_NAME/DB_PASSWORD (+ optional BACKUP_DB_PASSWORD)   (default: ./.env.prod)
#
# The dump runs as the least-privilege `esun_backup` user when BACKUP_DB_PASSWORD is set (see scripts/mysql-init/20-backup-user.sh),
# otherwise as root. Restores always run as root. Nothing is ever restored over the source database without --force AND a
# different target name. Ciphertext integrity is checked with a sha256 sidecar (openssl enc is not authenticated).
set -euo pipefail
export MSYS_NO_PATHCONV=1

BACKUP_DIR="${BACKUP_DIR:-./backups}"
PASSFILE="${BACKUP_PASSPHRASE_FILE:-./.backup-passphrase}"
KEEP="${BACKUP_KEEP:-14}"
ENV_FILE="${ENV_FILE:-./.env.prod}"
: "${MYSQL_CONTAINER:?set MYSQL_CONTAINER to the running mysql container name}"

die() { echo "error: $*" >&2; exit 1; }
envval() { grep -E "^$1=" "$ENV_FILE" | head -1 | cut -d= -f2-; }
[ -r "$ENV_FILE" ] || die "cannot read $ENV_FILE"
DB_NAME="$(envval DB_NAME)"; ROOT_PW="$(envval DB_PASSWORD)"; BACKUP_PW="$(envval BACKUP_DB_PASSWORD || true)"
[ -n "$DB_NAME" ] && [ -n "$ROOT_PW" ] || die "DB_NAME/DB_PASSWORD missing in $ENV_FILE"

check_passphrase() {
  [ -r "$PASSFILE" ] || die "passphrase file $PASSFILE not found (create one: openssl rand -base64 48 > $PASSFILE && chmod 600 $PASSFILE)"
  [ -s "$PASSFILE" ] || die "passphrase file is empty"
  # POSIX permission bits are meaningless on Windows filesystems (Git Bash), so only enforce them elsewhere.
  case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) ;; *) case "$(stat -c '%a' "$PASSFILE" 2>/dev/null || echo 600)" in 600|400) ;; *) die "$PASSFILE must be mode 600";; esac;; esac
}
rootsql() { docker exec -i -e MYSQL_PWD="$ROOT_PW" "$MYSQL_CONTAINER" mysql -uroot "$@"; }

do_backup() {
  check_passphrase; mkdir -p "$BACKUP_DIR"
  local ts out user pw
  ts="$(date -u +%Y%m%dT%H%M%SZ)"; out="$BACKUP_DIR/${DB_NAME}-$ts.sql.gz.enc"
  if [ -n "$BACKUP_PW" ]; then user=esun_backup; pw="$BACKUP_PW"; else user=root; pw="$ROOT_PW"; echo "note: BACKUP_DB_PASSWORD not set, dumping as root" >&2; fi
  docker exec -e MYSQL_PWD="$pw" "$MYSQL_CONTAINER" mysqldump -u"$user" --single-transaction --routines --triggers --events \
      --no-tablespaces --set-gtid-purged=OFF --default-character-set=utf8mb4 "$DB_NAME" \
    | gzip -9 | openssl enc -aes-256-cbc -pbkdf2 -iter 200000 -salt -pass "file:$PASSFILE" -out "$out"
  [ -s "$out" ] || { rm -f "$out"; die "backup produced an empty file"; }
  ( cd "$BACKUP_DIR" && sha256sum "$(basename "$out")" > "$(basename "$out").sha256" )
  echo "backup: $out ($(wc -c < "$out") bytes)"
  if [ -n "${BACKUP_OFFHOST_CMD:-}" ]; then
    bash -c "$BACKUP_OFFHOST_CMD" _ "$out" && echo "off-host command succeeded for $(basename "$out")" || die "off-host command failed"
  fi
  do_prune
  BACKUP_LAST="$out"
}

do_prune() {
  local count; count="$(ls -1 "$BACKUP_DIR"/*.sql.gz.enc 2>/dev/null | wc -l)"
  if [ "$count" -gt "$KEEP" ]; then
    ls -1t "$BACKUP_DIR"/*.sql.gz.enc | tail -n +"$((KEEP + 1))" | while read -r old; do rm -f "$old" "$old.sha256"; echo "pruned: $old"; done
  fi
}

verify_and_decrypt() { # <file> -> plaintext SQL on stdout
  local f="$1"; check_passphrase
  [ -f "$f" ] || die "no such file: $f"
  if [ -f "$f.sha256" ]; then ( cd "$(dirname "$f")" && sha256sum -c "$(basename "$f").sha256" >/dev/null ) || die "sha256 mismatch: $f is corrupt or tampered"; else echo "warning: no sha256 sidecar for $f" >&2; fi
  openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass "file:$PASSFILE" -in "$f" 2>/dev/null | gunzip 2>/dev/null \
    || die "decryption failed: wrong passphrase or corrupt file"
}

do_restore() {
  local f="${1:?backup file}" target="${2:?target database}" force="${3:-}"
  [ "$force" = "--force" ] || die "restore replaces the target database; pass --force"
  [ "$target" != "$DB_NAME" ] || die "refusing to restore over the source database '$DB_NAME'; restore to a different name and swap deliberately"
  case "$target" in *[!A-Za-z0-9_]*) die "target name must be [A-Za-z0-9_]";; esac
  verify_and_decrypt "$f" > /dev/null   # prove integrity and passphrase before touching any database
  rootsql -e "DROP DATABASE IF EXISTS \`$target\`; CREATE DATABASE \`$target\` CHARACTER SET utf8mb4;"
  verify_and_decrypt "$f" | rootsql "$target"
  echo "restored $(basename "$f") into $target"
}

tables_and_counts() { # <db>
  rootsql -N -e "SELECT table_name FROM information_schema.tables WHERE table_schema='$1' AND table_type='BASE TABLE' ORDER BY table_name" \
   | while read -r t; do printf '%s=%s\n' "$t" "$(rootsql -N -e "SELECT COUNT(*) FROM \`$1\`.\`$t\`" < /dev/null)"; done  # </dev/null: docker exec -i would swallow the loop's input
}
data_hash() { # <db>
  docker exec -e MYSQL_PWD="$ROOT_PW" "$MYSQL_CONTAINER" mysqldump -uroot --no-create-info --skip-comments --skip-extended-insert \
      --order-by-primary --no-tablespaces --set-gtid-purged=OFF --default-character-set=utf8mb4 "$1" | sha256sum | cut -d' ' -f1
}

do_drill() {
  local scratch="esun_restore_drill_$RANDOM" start end
  start=$(date +%s); do_backup; local file="$BACKUP_LAST"
  local before_t before_h; before_t="$(tables_and_counts "$DB_NAME")"; before_h="$(data_hash "$DB_NAME")"
  do_restore "$file" "$scratch" --force
  local after_t after_h; after_t="$(tables_and_counts "$scratch")"; after_h="$(data_hash "$scratch")"
  local routines; routines="$(rootsql -N -e "SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema='$scratch'")"
  rootsql -e "DROP DATABASE \`$scratch\`"; end=$(date +%s)
  local left; left="$(rootsql -N -e "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='$scratch'")"
  [ "$left" = 0 ] || die "scratch database was not removed"
  [ "$before_t" = "$after_t" ] || { echo "--- before"; echo "$before_t"; echo "--- after"; echo "$after_t"; die "table list or row counts differ after restore"; }
  [ "$before_h" = "$after_h" ] || die "data hash differs after restore"
  echo "DRILL_OK tables=$(echo "$before_t" | wc -l) routines=$routines rows_and_data_hash_match=yes restore_seconds=$((end - start)) scratch_removed=yes"
}

case "${1:-}" in
  backup)  do_backup ;;
  restore) shift; do_restore "$@" ;;
  drill)   do_drill ;;
  prune)   do_prune ;;
  *) sed -n '2,13p' "$0"; exit 2 ;;
esac
