#!/bin/sh
# Chạy một lần khi volume PostgreSQL được tạo mới.
# Tạo role chỉ-đọc cho EMQX; quyền SELECT trên mqtt_accounts do Flyway V1 cấp.
set -eu

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
     -v pw="$EMQX_AUTH_DB_PASSWORD" <<'SQL'
CREATE ROLE emqx_auth LOGIN PASSWORD :'pw' NOSUPERUSER NOCREATEDB NOCREATEROLE;
SQL
