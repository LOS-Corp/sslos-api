#!/usr/bin/env bash
# Regenerates ../database.sql (repo root) by concatenating the Flyway migrations in version order.
# The file is a convenience snapshot for reviewers; the application itself always migrates through Flyway.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
out="${1:-$here/../database.sql}"
{
  echo "-- SSLOS full PostgreSQL schema — GENERATED, do not edit (sslos-api/scripts/generate-database-sql.sh)."
  echo "-- Concatenation of sslos-api/src/main/resources/db/migration in Flyway order."
  echo "-- Usage: createdb sslos && psql -U postgres -d sslos -f database.sql"
  echo "-- Note: the Spring app applies these itself via Flyway; do not run both on the same DB."
  echo
  for f in $(ls "$here/src/main/resources/db/migration"/V*__*.sql | sort -V); do
    echo
    echo "-- ======== $(basename "$f") ========"
    cat "$f"
  done
} > "$out"
echo "Wrote $out"
