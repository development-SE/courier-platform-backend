#!/bin/bash
set -euo pipefail

create_user_and_db() {
  local db_name="$1"
  local db_user="$2"
  local db_password="$3"

  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<EOSQL
DO \$\$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = '${db_user}') THEN
    CREATE ROLE ${db_user} LOGIN PASSWORD '${db_password}';
  END IF;
END
\$\$;

SELECT 'CREATE DATABASE ${db_name} OWNER ${db_user}'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = '${db_name}')\gexec

GRANT CONNECT ON DATABASE ${db_name} TO ${db_user};
EOSQL
}

enable_extensions() {
  local db_name="$1"
  shift

  for extension in "$@"; do
    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$db_name" \
      -c "CREATE EXTENSION IF NOT EXISTS ${extension};"
  done
}

create_user_and_db "$AUTH_DB_NAME" "$AUTH_DB_USERNAME" "$AUTH_DB_PASSWORD"
create_user_and_db "$USER_DB_NAME" "$USER_DB_USERNAME" "$USER_DB_PASSWORD"
create_user_and_db "$ORDER_DB_NAME" "$ORDER_DB_USERNAME" "$ORDER_DB_PASSWORD"
create_user_and_db "$COMPANY_DB_NAME" "$COMPANY_DB_USERNAME" "$COMPANY_DB_PASSWORD"
create_user_and_db "$LOGISTICS_DB_NAME" "$LOGISTICS_DB_USERNAME" "$LOGISTICS_DB_PASSWORD"
create_user_and_db "$NOTIFICATION_DB_NAME" "$NOTIFICATION_DB_USERNAME" "$NOTIFICATION_DB_PASSWORD"

enable_extensions "$AUTH_DB_NAME" '"uuid-ossp"' '"pgcrypto"'
enable_extensions "$USER_DB_NAME" '"uuid-ossp"'
enable_extensions "$ORDER_DB_NAME" '"uuid-ossp"' postgis 'earthdistance CASCADE' '"btree_gist"'
enable_extensions "$COMPANY_DB_NAME" '"uuid-ossp"'
enable_extensions "$LOGISTICS_DB_NAME" '"uuid-ossp"' postgis '"btree_gist"'
enable_extensions "$NOTIFICATION_DB_NAME" '"uuid-ossp"'
