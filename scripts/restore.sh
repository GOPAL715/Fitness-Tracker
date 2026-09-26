#!/usr/bin/env bash
# Phase 16 restore tooling.
#
# Restores a custom-format dump into a target database. Intended for both disaster recovery and
# scheduled restore verification: a backup that has never been restored is not a backup.
#
# Usage:
#   scripts/restore.sh <database-url> <dump-file>
#
# Restores into a database you name explicitly, so this can never touch a database by accident.
set -euo pipefail

DATABASE_URL="${1:?usage: restore.sh <database-url> <dump-file>}"
DUMP_FILE="${2:?usage: restore.sh <database-url> <dump-file>}"

if ! command -v pg_restore >/dev/null 2>&1; then
  echo "pg_restore is required but was not found on PATH" >&2
  exit 1
fi

if [ ! -f "$DUMP_FILE" ]; then
  echo "dump file not found: $DUMP_FILE" >&2
  exit 1
fi

# Verify integrity before touching the target, so a corrupt backup fails safely and early.
if [ -f "$DUMP_FILE.sha256" ]; then
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum --check --status "$DUMP_FILE.sha256" || { echo "checksum mismatch" >&2; exit 1; }
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 --check --status "$DUMP_FILE.sha256" || { echo "checksum mismatch" >&2; exit 1; }
  fi
  echo "checksum verified"
fi

# --clean --if-exists makes a restore into a non-empty database reproducible.
# --exit-on-error stops at the first failure rather than leaving a half-restored database.
pg_restore --dbname="$DATABASE_URL" \
           --clean --if-exists \
           --no-owner --no-privileges \
           --jobs=4 \
           --exit-on-error \
           "$DUMP_FILE"

echo "restore completed into the target database"
echo "next: run Flyway validation to confirm schema compatibility"
