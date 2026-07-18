#!/usr/bin/env sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-$ROOT_DIR/ops/production/compose.yaml}"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/ops/production/.env}"
BOOTSTRAP_SQL="${BOOTSTRAP_SQL:-$ROOT_DIR/ops/production/role-bootstrap.sql}"

if [ ! -f "$ENV_FILE" ]; then
  echo "Missing env file: $ENV_FILE" >&2
  exit 1
fi

"$ROOT_DIR/ops/scripts/validate-production-env.sh" "$ENV_FILE"

set -a
. "$ENV_FILE"
set +a

podman compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" exec -T postgres \
  psql \
    -v "peak_app_password=$POSTGRES_APP_PASSWORD" \
    -v "peak_worker_password=$POSTGRES_WORKER_PASSWORD" \
    -v "peak_platform_password=$POSTGRES_PLATFORM_PASSWORD" \
    -v "peak_platform_support_password=${POSTGRES_PLATFORM_SUPPORT_PASSWORD:-$POSTGRES_PLATFORM_PASSWORD}" \
    -U "$POSTGRES_MIGRATOR_USER" \
    "$POSTGRES_DB" \
  < "$BOOTSTRAP_SQL"
