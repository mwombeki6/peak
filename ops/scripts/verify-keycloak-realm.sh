#!/usr/bin/env sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"

echo "verify-keycloak-realm.sh is retained as a compatibility wrapper; verifying both Peak realms." >&2
exec "$ROOT_DIR/ops/scripts/verify-keycloak-realms.sh" "$@"
