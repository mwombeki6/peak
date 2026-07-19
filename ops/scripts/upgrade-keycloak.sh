#!/usr/bin/env sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-$ROOT_DIR/ops/production/compose.yaml}"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/ops/production/.env}"
KEYCLOAK_BASE_URL="${KEYCLOAK_BASE_URL:-http://localhost:8081}"
BACKUP_FILE=""
TEMP_FILE=""
COMPLETED=false

failure() {
  echo "Keycloak upgrade did not complete. Do not start an older Keycloak against an upgraded database." >&2
  if [ -n "$BACKUP_FILE" ]; then
    echo "Preserved pre-upgrade backup: $BACKUP_FILE" >&2
  fi
}
cleanup() {
  if [ -n "$TEMP_FILE" ]; then
    rm -f "$TEMP_FILE"
  fi
  if [ "$COMPLETED" != "true" ]; then
    failure
  fi
}
trap cleanup 0 HUP INT TERM

if [ "${KEYCLOAK_UPGRADE_APPROVED:-false}" != "true" ]; then
  echo "Set KEYCLOAK_UPGRADE_APPROVED=true after reviewing the Keycloak upgrade notes." >&2
  exit 1
fi
if [ ! -f "$ENV_FILE" ]; then
  echo "Missing env file: $ENV_FILE" >&2
  exit 1
fi
for tool in curl jq podman python3; do
  command -v "$tool" >/dev/null || {
    echo "Missing required tool: $tool" >&2
    exit 1
  }
done

"$ROOT_DIR/ops/scripts/validate-production-env.sh" "$ENV_FILE"
set -a
. "$ENV_FILE"
set +a
export KEYCLOAK_BASE_URL

ADMIN_TOKEN="$(
  curl -fsS -X POST "$KEYCLOAK_BASE_URL/realms/master/protocol/openid-connect/token" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    --data-urlencode "client_id=admin-cli" \
    --data-urlencode "grant_type=password" \
    --data-urlencode "username=$KEYCLOAK_ADMIN" \
    --data-urlencode "password=$KEYCLOAK_ADMIN_PASSWORD" |
    jq -er '.access_token'
)"
TEMP_FILE="$(mktemp)"
legacy_status="$(
  curl -sS -o "$TEMP_FILE" -w '%{http_code}' \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    "$KEYCLOAK_BASE_URL/admin/realms/peak/users/count"
)"
if [ "$legacy_status" = "200" ]; then
  legacy_users="$(jq -er '.' "$TEMP_FILE")"
  realm_status="$(
    curl -sS -o "$TEMP_FILE" -w '%{http_code}' \
      -H "Authorization: Bearer $ADMIN_TOKEN" \
      "$KEYCLOAK_BASE_URL/admin/realms/peak"
  )"
  if [ "$realm_status" != "200" ]; then
    echo "Could not inspect legacy realm peak" >&2
    exit 1
  fi
  legacy_enabled="$(
    jq -er 'if (.enabled | type) == "boolean" then (.enabled | tostring) else error("missing enabled") end' \
      "$TEMP_FILE"
  )"
  if [ "$legacy_users" -gt 0 ] && [ "$legacy_enabled" != "false" ]; then
    echo "Legacy realm peak still contains $legacy_users users and remains enabled." >&2
    echo "Run ops/scripts/migrate-keycloak-legacy-realm.sh before the upgrade." >&2
    exit 1
  fi
fi
rm -f "$TEMP_FILE"
TEMP_FILE=""

legacy_issuer="${KEYCLOAK_LEGACY_ISSUER:-${KEYCLOAK_HOSTNAME%/}/realms/peak}"
legacy_links="$(
  podman compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" exec -T postgres \
    psql -X -qAt -v ON_ERROR_STOP=1 \
      -v legacy_issuer="$legacy_issuer" \
      -U "$POSTGRES_MIGRATOR_USER" "$POSTGRES_DB" <<'SQL'
SELECT pg_catalog.count(*)
FROM public.identity_links il
WHERE il.provider = 'oidc'
  AND il.issuer = :'legacy_issuer'
  AND il.revoked_at IS NULL;
SQL
)"
if [ "$legacy_links" -gt 0 ]; then
  echo "Peak still contains $legacy_links active identity links for $legacy_issuer." >&2
  echo "Run ops/scripts/migrate-keycloak-legacy-realm.sh before the upgrade." >&2
  exit 1
fi

BACKUP_FILE="$(BACKUP_DIR="${BACKUP_DIR:-$ROOT_DIR/backups}" \
  "$ROOT_DIR/ops/scripts/backup-keycloak.sh")"
echo "Pre-upgrade backup: $BACKUP_FILE"

podman compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" pull keycloak
podman compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d keycloak-db
podman compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" stop keycloak
podman compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d --no-deps keycloak

attempt=1
until curl -fsS "$KEYCLOAK_BASE_URL/realms/$KEYCLOAK_PLATFORM_REALM/.well-known/openid-configuration" >/dev/null 2>&1 && \
      curl -fsS "$KEYCLOAK_BASE_URL/realms/$KEYCLOAK_HOSPITALITY_REALM/.well-known/openid-configuration" >/dev/null 2>&1; do
  if [ "$attempt" -ge 90 ]; then
    failure
    exit 1
  fi
  attempt=$((attempt + 1))
  sleep 2
done

python3 "$ROOT_DIR/ops/scripts/reconcile-keycloak-realms.py"
"$ROOT_DIR/ops/scripts/verify-keycloak-realms.sh"
COMPLETED=true
echo "Keycloak upgrade and realm reconciliation completed"
