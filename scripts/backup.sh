#!/usr/bin/env bash
# Phase 16 backup tooling.
#
# Takes a compressed, custom-format PostgreSQL dump of a single database. Custom format is used
# because it restores selectively and with parallelism, which matters for an RTO test.
#
# Usage:
#   scripts/backup.sh <database-url> <output-file>
#
# The connection string must come from the environment, never from this script or a committed
# file. Nothing here prints credentials.
set -euo pipefail

DATABASE_URL="${1:?usage: backup.sh <database-url> <output-file>}"
OUTPUT_FILE="${2:?usage: backup.sh <database-url> <output-file>}"

if ! command -v pg_dump >/dev/null 2>&1; then
  echo "pg_dump is required but was not found on PATH" >&2
  exit 1
fi

mkdir -p "$(dirname "$OUTPUT_FILE")"

# --no-owner/--no-privileges keep the dump restorable into a differently-owned instance.
# --format=custom enables selective, parallel restore.
pg_dump --dbname="$DATABASE_URL" \
        --format=custom \
        --compress=9 \
        --no-owner \
        --no-privileges \
        --file="$OUTPUT_FILE"

chmod 600 "$OUTPUT_FILE"

# A checksum is what makes an off-site copy verifiable rather than merely present.
if command -v sha256sum >/dev/null 2>&1; then
  sha256sum "$OUTPUT_FILE" > "$OUTPUT_FILE.sha256"
elif command -v shasum >/dev/null 2>&1; then
  shasum -a 256 "$OUTPUT_FILE" > "$OUTPUT_FILE.sha256"
fi

echo "backup written to $OUTPUT_FILE ($(du -h "$OUTPUT_FILE" | cut -f1))"
