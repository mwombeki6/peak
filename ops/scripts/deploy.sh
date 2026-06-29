#!/usr/bin/env sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-$ROOT_DIR/ops/production/compose.yaml}"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/ops/production/.env}"

if [ ! -f "$ENV_FILE" ]; then
  echo "Missing env file: $ENV_FILE" >&2
  echo "Create it from ops/production/.env.example and set real secrets." >&2
  exit 1
fi

"$ROOT_DIR/ops/scripts/validate-production-env.sh" "$ENV_FILE"

podman compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" pull
podman compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d postgres keycloak-db keycloak

attempt=1
until podman compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" exec -T postgres \
  sh -c 'pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"' >/dev/null 2>&1; do
  if [ "$attempt" -ge 30 ]; then
    echo "PostgreSQL did not become ready for migration." >&2
    exit 1
  fi
  attempt=$((attempt + 1))
  sleep 2
done

podman compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" --profile migration \
  run --rm --no-deps peak-migration
podman compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d peak-api peak-worker
"$ROOT_DIR/ops/scripts/smoke-test.sh"
