#!/usr/bin/env sh
set -eu

if [ "$#" -ne 1 ]; then
  echo "Usage: $0 BACKUP_FILE.sql.gz" >&2
  exit 1
fi

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-$ROOT_DIR/ops/production/compose.yaml}"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/ops/production/.env}"
BACKUP_FILE="$1"

if [ ! -f "$ENV_FILE" ]; then
  echo "Missing env file: $ENV_FILE" >&2
  exit 1
fi

if [ ! -f "$BACKUP_FILE" ]; then
  echo "Missing backup file: $BACKUP_FILE" >&2
  exit 1
fi

set -a
. "$ENV_FILE"
set +a

gunzip -c "$BACKUP_FILE" | podman compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" exec -T postgres \
  psql -U "$POSTGRES_MIGRATOR_USER" "$POSTGRES_DB"
