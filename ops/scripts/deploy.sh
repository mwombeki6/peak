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

podman compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" pull
podman compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" --profile migration up --abort-on-container-exit peak-migration
podman compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d peak-api peak-worker
"$ROOT_DIR/ops/scripts/healthcheck.sh"
