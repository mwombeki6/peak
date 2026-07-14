#!/usr/bin/env sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-$ROOT_DIR/ops/production/compose.yaml}"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/ops/production/.env}"

"$ROOT_DIR/ops/scripts/validate-production-env.sh" "$ENV_FILE"

set -a
. "$ENV_FILE"
set +a

if [ "${PEAK_PLATFORM_BOOTSTRAP_ENABLED:-false}" != "true" ]; then
  echo "PEAK_PLATFORM_BOOTSTRAP_ENABLED must be true for platform recovery." >&2
  exit 1
fi

if [ "${PEAK_PLATFORM_RECOVERY_ENABLED:-false}" != "true" ]; then
  echo "PEAK_PLATFORM_RECOVERY_ENABLED must be true for platform recovery." >&2
  exit 1
fi

podman compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" --profile bootstrap \
  run --rm --no-deps peak-bootstrap

echo "Platform root recovery completed. Disable both flags and clear the four identity values."
