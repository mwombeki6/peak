#!/usr/bin/env sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-$ROOT_DIR/ops/production/compose.yaml}"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/ops/production/.env}"
BACKUP_DIR="${BACKUP_DIR:-$ROOT_DIR/backups}"

if [ ! -f "$ENV_FILE" ]; then
  echo "Missing env file: $ENV_FILE" >&2
  exit 1
fi

mkdir -p "$BACKUP_DIR"

set -a
. "$ENV_FILE"
set +a

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
BACKUP_FILE="$BACKUP_DIR/keycloak-$STAMP.sql.gz"

podman compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" exec -T keycloak-db \
  pg_dump -U "$KEYCLOAK_DB_USER" "$KEYCLOAK_DB" | gzip > "$BACKUP_FILE"

echo "$BACKUP_FILE"
