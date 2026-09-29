#!/bin/sh
# Creates (or re-syncs the password of) the runtime database identity used by the backend: data access only, no DDL.
# Schema changes run as root in the one-shot `migrate` service (docker-compose.prod.yml), never inside the backend.
# Runs automatically on the first start of an empty MySQL data directory (mounted into /docker-entrypoint-initdb.d).
# It is idempotent, so it also serves as the rotation / retrofit step for an existing volume:
#   docker compose -f docker-compose.prod.yml --env-file .env.prod exec mysql sh /docker-entrypoint-initdb.d/10-app-user.sh
set -eu
: "${APP_DB_PASSWORD:?APP_DB_PASSWORD is required}"
: "${MYSQL_ROOT_PASSWORD:?MYSQL_ROOT_PASSWORD is required}"
: "${MYSQL_DATABASE:?MYSQL_DATABASE is required}"
case "$APP_DB_PASSWORD" in
  *"'"*|*'\'*) echo "APP_DB_PASSWORD must not contain quotes or backslashes" >&2; exit 1;;
esac
MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot <<SQL
CREATE USER IF NOT EXISTS 'esun_app'@'%' IDENTIFIED BY '$APP_DB_PASSWORD';
ALTER USER 'esun_app'@'%' IDENTIFIED BY '$APP_DB_PASSWORD';
GRANT SELECT, INSERT, UPDATE, DELETE, EXECUTE ON \`${MYSQL_DATABASE}\`.* TO 'esun_app'@'%';
SQL
