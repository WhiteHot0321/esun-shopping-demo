#!/bin/sh
# Optional least-privilege identity for scripts/mysql-backup.sh: read-only dump rights, no write or DDL. Does nothing when
# BACKUP_DB_PASSWORD is empty. Idempotent, so it also serves as the rotation / retrofit step for an existing volume:
#   docker exec <mysql-container> sh /docker-entrypoint-initdb.d/20-backup-user.sh
set -eu
[ -n "${BACKUP_DB_PASSWORD:-}" ] || { echo "BACKUP_DB_PASSWORD not set; skipping backup user"; exit 0; }
: "${MYSQL_ROOT_PASSWORD:?MYSQL_ROOT_PASSWORD is required}"
: "${MYSQL_DATABASE:?MYSQL_DATABASE is required}"
case "$BACKUP_DB_PASSWORD" in
  *"'"*|*'\'*) echo "BACKUP_DB_PASSWORD must not contain quotes or backslashes" >&2; exit 1;;
esac
MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot <<SQL
CREATE USER IF NOT EXISTS 'esun_backup'@'%' IDENTIFIED BY '$BACKUP_DB_PASSWORD';
ALTER USER 'esun_backup'@'%' IDENTIFIED BY '$BACKUP_DB_PASSWORD';
GRANT SELECT, SHOW VIEW, TRIGGER, EVENT ON \`${MYSQL_DATABASE}\`.* TO 'esun_backup'@'%';
GRANT SHOW_ROUTINE ON *.* TO 'esun_backup'@'%';
SQL
