#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/ops/production/.env}"
COMPOSE_FILE="${COMPOSE_FILE:-$ROOT_DIR/ops/production/compose.yaml}"
SOURCE_PROJECT="${SOURCE_PROJECT:-peak}"
RESTORE_PROJECT="${RESTORE_PROJECT:-peak-restore-drill}"
EVIDENCE_DIR="${EVIDENCE_DIR:-$ROOT_DIR/build/evidence/backup-restore}"

[[ -f "$ENV_FILE" ]] || { echo "Missing env file: $ENV_FILE" >&2; exit 1; }
mkdir -p "$EVIDENCE_DIR"
set -a
source "$ENV_FILE"
set +a

cleanup() {
  podman compose -p "$RESTORE_PROJECT" --env-file "$ENV_FILE" \
    -f "$COMPOSE_FILE" down -v --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT

source_db() {
  podman compose -p "$SOURCE_PROJECT" --env-file "$ENV_FILE" -f "$COMPOSE_FILE" \
    exec -T postgres psql -XAt -U "$POSTGRES_MIGRATOR_USER" -d "$POSTGRES_DB" -c "$1"
}

restored_db() {
  podman compose -p "$RESTORE_PROJECT" --env-file "$ENV_FILE" -f "$COMPOSE_FILE" \
    exec -T postgres psql -XAt -U "$POSTGRES_MIGRATOR_USER" -d "$POSTGRES_DB" -c "$1"
}

source_keycloak_db() {
  podman compose -p "$SOURCE_PROJECT" --env-file "$ENV_FILE" -f "$COMPOSE_FILE" \
    exec -T keycloak-db psql -XAt -U "$KEYCLOAK_DB_USER" -d "$KEYCLOAK_DB" -c "$1"
}

restored_keycloak_db() {
  podman compose -p "$RESTORE_PROJECT" --env-file "$ENV_FILE" -f "$COMPOSE_FILE" \
    exec -T keycloak-db psql -XAt -U "$KEYCLOAK_DB_USER" -d "$KEYCLOAK_DB" -c "$1"
}

export BACKUP_DIR="$EVIDENCE_DIR/backups"
export COMPOSE_FILE ENV_FILE
export COMPOSE_PROJECT_NAME="$SOURCE_PROJECT"
postgres_backup="$($ROOT_DIR/ops/scripts/backup-postgres.sh)"
keycloak_backup="$($ROOT_DIR/ops/scripts/backup-keycloak.sh)"

source_schema="$(source_db "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1")"
source_tenants="$(source_db "SELECT count(*) FROM tenants WHERE deleted_at IS NULL")"
source_financial="$(source_db "SELECT COALESCE(round(sum(amount),2),0) FROM folio_charges WHERE status='POSTED' AND deleted_at IS NULL")"
source_reports="$(source_db "SELECT count(*) FROM report_artifacts")"
source_realms="$(source_keycloak_db "SELECT count(*) FROM realm")"
source_clients="$(source_keycloak_db "SELECT count(*) FROM client")"
source_users="$(source_keycloak_db "SELECT count(*) FROM user_entity")"

export COMPOSE_PROJECT_NAME="$RESTORE_PROJECT"
podman compose -p "$RESTORE_PROJECT" --env-file "$ENV_FILE" -f "$COMPOSE_FILE" \
  up -d postgres keycloak-db
for _ in $(seq 1 60); do
  if podman compose -p "$RESTORE_PROJECT" --env-file "$ENV_FILE" -f "$COMPOSE_FILE" \
      exec -T postgres pg_isready -U "$POSTGRES_MIGRATOR_USER" -d "$POSTGRES_DB" >/dev/null 2>&1 &&
    podman compose -p "$RESTORE_PROJECT" --env-file "$ENV_FILE" -f "$COMPOSE_FILE" \
      exec -T keycloak-db pg_isready -U "$KEYCLOAK_DB_USER" -d "$KEYCLOAK_DB" >/dev/null 2>&1; then
    break
  fi
  sleep 2
done
podman compose -p "$RESTORE_PROJECT" --env-file "$ENV_FILE" -f "$COMPOSE_FILE" \
  exec -T postgres pg_isready -U "$POSTGRES_MIGRATOR_USER" -d "$POSTGRES_DB" >/dev/null
podman compose -p "$RESTORE_PROJECT" --env-file "$ENV_FILE" -f "$COMPOSE_FILE" \
  exec -T keycloak-db pg_isready -U "$KEYCLOAK_DB_USER" -d "$KEYCLOAK_DB" >/dev/null
$ROOT_DIR/ops/scripts/restore-postgres.sh "$postgres_backup"
$ROOT_DIR/ops/scripts/restore-keycloak.sh "$keycloak_backup"

assert_same() {
  local name="$1" expected="$2" actual="$3"
  [[ "$expected" == "$actual" ]] || {
    echo "$name mismatch: source=$expected restored=$actual" >&2
    exit 1
  }
}

restored_schema="$(restored_db "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1")"
restored_tenants="$(restored_db "SELECT count(*) FROM tenants WHERE deleted_at IS NULL")"
restored_financial="$(restored_db "SELECT COALESCE(round(sum(amount),2),0) FROM folio_charges WHERE status='POSTED' AND deleted_at IS NULL")"
restored_reports="$(restored_db "SELECT count(*) FROM report_artifacts")"
restored_realms="$(restored_keycloak_db "SELECT count(*) FROM realm")"
restored_clients="$(restored_keycloak_db "SELECT count(*) FROM client")"
restored_users="$(restored_keycloak_db "SELECT count(*) FROM user_entity")"

KEYCLOAK_BIND_ADDRESS=127.0.0.2 \
KEYCLOAK_HOSTNAME=http://127.0.0.2:8081 \
podman compose -p "$RESTORE_PROJECT" --env-file "$ENV_FILE" -f "$COMPOSE_FILE" \
  up -d keycloak
for _ in $(seq 1 60); do
  if curl -fsS http://127.0.0.2:8081/realms/peak/.well-known/openid-configuration \
    > "$EVIDENCE_DIR/restored-oidc-discovery.json"; then
    break
  fi
  sleep 2
done
[[ -s "$EVIDENCE_DIR/restored-oidc-discovery.json" ]] || {
  echo "Restored Keycloak did not publish the peak realm" >&2
  exit 1
}
restored_authentication="$(
  curl -fsS -X POST http://127.0.0.2:8081/realms/master/protocol/openid-connect/token \
    --data-urlencode "client_id=admin-cli" \
    --data-urlencode "username=$KEYCLOAK_ADMIN" \
    --data-urlencode "password=$KEYCLOAK_ADMIN_PASSWORD" \
    --data-urlencode "grant_type=password" |
    jq -er '.access_token | length > 100'
)"
[[ "$restored_authentication" == "true" ]] || {
  echo "Restored Keycloak rejected the administrative authentication probe" >&2
  exit 1
}

assert_same schema "$source_schema" "$restored_schema"
assert_same tenants "$source_tenants" "$restored_tenants"
assert_same financialTotal "$source_financial" "$restored_financial"
assert_same reportArtifacts "$source_reports" "$restored_reports"
assert_same keycloakRealms "$source_realms" "$restored_realms"
assert_same keycloakClients "$source_clients" "$restored_clients"
assert_same keycloakUsers "$source_users" "$restored_users"

jq -n \
  --arg schema "$restored_schema" \
  --arg tenants "$restored_tenants" \
  --arg financialTotal "$restored_financial" \
  --arg reportArtifacts "$restored_reports" \
  --arg keycloakRealms "$restored_realms" \
  --arg keycloakClients "$restored_clients" \
  --arg keycloakUsers "$restored_users" \
  --argjson keycloakAuthentication "$restored_authentication" \
  --arg postgresBackup "$(sha256sum "$postgres_backup" | cut -d' ' -f1)" \
  --arg keycloakBackup "$(sha256sum "$keycloak_backup" | cut -d' ' -f1)" \
  '{status:"passed", schema:$schema, tenants:($tenants|tonumber), financialTotal:$financialTotal,
    reportArtifacts:($reportArtifacts|tonumber), keycloak:{realms:($keycloakRealms|tonumber),
    clients:($keycloakClients|tonumber), users:($keycloakUsers|tonumber),
    authenticationVerified:$keycloakAuthentication},
    backupSha256:{postgres:$postgresBackup,keycloak:$keycloakBackup}}' \
  > "$EVIDENCE_DIR/backup-restore-drill.json"

echo "$EVIDENCE_DIR/backup-restore-drill.json"
