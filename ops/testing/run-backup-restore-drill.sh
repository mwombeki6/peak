#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/ops/production/.env}"
COMPOSE_FILE="${COMPOSE_FILE:-$ROOT_DIR/ops/production/compose.yaml}"
# The restore stack is a fresh Keycloak, so it needs the same temporary
# bootstrap administrator the other acceptance stacks use. Production compose
# deliberately omits it.
KEYCLOAK_ADMIN_OVERLAY="${KEYCLOAK_ADMIN_OVERLAY:-$ROOT_DIR/ops/testing/compose.keycloak-bootstrap-admin.yaml}"
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
    -f "$COMPOSE_FILE" -f "$KEYCLOAK_ADMIN_OVERLAY" down -v --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT

source_db() {
  podman compose -p "$SOURCE_PROJECT" --env-file "$ENV_FILE" -f "$COMPOSE_FILE" -f "$KEYCLOAK_ADMIN_OVERLAY" \
    exec -T postgres psql -XAt -U "$POSTGRES_MIGRATOR_USER" -d "$POSTGRES_DB" -c "$1"
}

restored_db() {
  podman compose -p "$RESTORE_PROJECT" --env-file "$ENV_FILE" -f "$COMPOSE_FILE" -f "$KEYCLOAK_ADMIN_OVERLAY" \
    exec -T postgres psql -XAt -U "$POSTGRES_MIGRATOR_USER" -d "$POSTGRES_DB" -c "$1"
}

source_keycloak_db() {
  podman compose -p "$SOURCE_PROJECT" --env-file "$ENV_FILE" -f "$COMPOSE_FILE" -f "$KEYCLOAK_ADMIN_OVERLAY" \
    exec -T keycloak-db psql -XAt -U "$KEYCLOAK_DB_USER" -d "$KEYCLOAK_DB" -c "$1"
}

restored_keycloak_db() {
  podman compose -p "$RESTORE_PROJECT" --env-file "$ENV_FILE" -f "$COMPOSE_FILE" -f "$KEYCLOAK_ADMIN_OVERLAY" \
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
podman compose -p "$RESTORE_PROJECT" --env-file "$ENV_FILE" -f "$COMPOSE_FILE" -f "$KEYCLOAK_ADMIN_OVERLAY" \
  up -d postgres keycloak-db
for _ in $(seq 1 60); do
  if podman compose -p "$RESTORE_PROJECT" --env-file "$ENV_FILE" -f "$COMPOSE_FILE" -f "$KEYCLOAK_ADMIN_OVERLAY" \
      exec -T postgres pg_isready -U "$POSTGRES_MIGRATOR_USER" -d "$POSTGRES_DB" >/dev/null 2>&1 &&
    podman compose -p "$RESTORE_PROJECT" --env-file "$ENV_FILE" -f "$COMPOSE_FILE" -f "$KEYCLOAK_ADMIN_OVERLAY" \
      exec -T keycloak-db pg_isready -U "$KEYCLOAK_DB_USER" -d "$KEYCLOAK_DB" >/dev/null 2>&1; then
    break
  fi
  sleep 2
done
podman compose -p "$RESTORE_PROJECT" --env-file "$ENV_FILE" -f "$COMPOSE_FILE" -f "$KEYCLOAK_ADMIN_OVERLAY" \
  exec -T postgres pg_isready -U "$POSTGRES_MIGRATOR_USER" -d "$POSTGRES_DB" >/dev/null
podman compose -p "$RESTORE_PROJECT" --env-file "$ENV_FILE" -f "$COMPOSE_FILE" -f "$KEYCLOAK_ADMIN_OVERLAY" \
  exec -T keycloak-db pg_isready -U "$KEYCLOAK_DB_USER" -d "$KEYCLOAK_DB" >/dev/null
$ROOT_DIR/ops/scripts/restore-postgres.sh "$postgres_backup" \
  > "$EVIDENCE_DIR/postgres-restore.log"
$ROOT_DIR/ops/scripts/restore-keycloak.sh "$keycloak_backup" \
  > "$EVIDENCE_DIR/keycloak-restore.log"

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
restored_runtime_roles="$(restored_db "
  SELECT count(*)
  FROM pg_roles
  WHERE rolname IN (
    'pms_app', 'pms_platform', 'pms_worker', 'pms_readonly_support',
    'pms_tenant_continuity_owner', 'pms_initial_admin_owner',
    'peak_app', 'peak_worker', 'peak_platform', 'peak_platform_support'
  )
    AND rolsuper = false
    AND rolcreatedb = false
    AND rolcreaterole = false
    AND rolbypassrls = false
")"
restored_runtime_memberships="$(restored_db "
  SELECT count(*)
  FROM pg_auth_members memberships
  JOIN pg_roles granted_role ON granted_role.oid = memberships.roleid
  JOIN pg_roles member_role ON member_role.oid = memberships.member
  WHERE (member_role.rolname, granted_role.rolname) IN (
    ('peak_app', 'pms_app'),
    ('peak_platform', 'pms_platform'),
    ('peak_worker', 'pms_worker'),
    ('peak_platform_support', 'pms_readonly_support')
  )
")"
restored_runtime_owned_relations="$(restored_db "
  SELECT count(*)
  FROM pg_class relation
  JOIN pg_roles owner_role ON owner_role.oid = relation.relowner
  WHERE relation.relkind IN ('r', 'p', 'v', 'm', 'S')
    AND owner_role.rolname IN (
      'pms_app', 'pms_platform', 'pms_worker', 'pms_readonly_support',
      'pms_tenant_continuity_owner', 'pms_initial_admin_owner',
      'peak_app', 'peak_worker', 'peak_platform', 'peak_platform_support'
    )
")"
restored_continuity_owner="$(restored_db "
  SELECT owner_role.rolname
  FROM pg_catalog.pg_proc AS function
  JOIN pg_catalog.pg_namespace AS namespace
    ON namespace.oid = function.pronamespace
  JOIN pg_catalog.pg_roles AS owner_role
    ON owner_role.oid = function.proowner
  WHERE namespace.nspname = 'public'
    AND function.proname = 'lock_tenant_administrator_continuity'
    AND function.pronargs = 1
")"
restored_continuity_owner_hardened="$(restored_db "
  SELECT count(*)
  FROM pg_catalog.pg_roles
  WHERE rolname = 'pms_tenant_continuity_owner'
    AND rolcanlogin = false
    AND rolinherit = false
    AND rolsuper = false
    AND rolcreatedb = false
    AND rolcreaterole = false
    AND rolbypassrls = false
")"
restored_initial_admin_functions_hardened="$(restored_db "
  SELECT count(*)
  FROM pg_catalog.pg_proc AS function
  JOIN pg_catalog.pg_namespace AS namespace
    ON namespace.oid = function.pronamespace
  JOIN pg_catalog.pg_roles AS owner_role
    ON owner_role.oid = function.proowner
  WHERE namespace.nspname = 'public'
    AND owner_role.rolname = 'pms_initial_admin_owner'
    AND function.prosecdef = true
    AND COALESCE(
      'search_path=pg_catalog, public, pg_temp' = ANY(function.proconfig),
      false
    )
    AND (
      (function.proname = 'prepare_initial_tenant_administrator' AND function.pronargs = 1)
      OR (function.proname = 'tenant_administrator_readiness' AND function.pronargs = 1)
      OR (function.proname = 'accept_tenant_user_invitation' AND function.pronargs = 5)
    )
")"
restored_initial_admin_owner_hardened="$(restored_db "
  SELECT count(*)
  FROM pg_catalog.pg_roles
  WHERE rolname = 'pms_initial_admin_owner'
    AND rolcanlogin = false
    AND rolinherit = false
    AND rolsuper = false
    AND rolcreatedb = false
    AND rolcreaterole = false
    AND rolbypassrls = false
")"
restored_initial_admin_owner_memberships="$(restored_db "
  SELECT count(*)
  FROM pg_catalog.pg_auth_members AS membership
  JOIN pg_catalog.pg_roles AS granted_role
    ON granted_role.oid = membership.roleid
  JOIN pg_catalog.pg_roles AS member_role
    ON member_role.oid = membership.member
  WHERE granted_role.rolname = 'pms_initial_admin_owner'
     OR member_role.rolname = 'pms_initial_admin_owner'
")"

KEYCLOAK_BIND_ADDRESS=127.0.0.2 \
KEYCLOAK_HOSTNAME=http://127.0.0.2:8081 \
KEYCLOAK_ADMIN_HOSTNAME=http://127.0.0.2:8081 \
podman compose -p "$RESTORE_PROJECT" --env-file "$ENV_FILE" -f "$COMPOSE_FILE" -f "$KEYCLOAK_ADMIN_OVERLAY" \
  up -d keycloak
for _ in $(seq 1 60); do
  if curl -fsS \
      "http://127.0.0.2:8081/realms/$KEYCLOAK_PLATFORM_REALM/.well-known/openid-configuration" \
      > "$EVIDENCE_DIR/restored-platform-oidc-discovery.json" && \
    curl -fsS \
      "http://127.0.0.2:8081/realms/$KEYCLOAK_HOSPITALITY_REALM/.well-known/openid-configuration" \
      > "$EVIDENCE_DIR/restored-hospitality-oidc-discovery.json"; then
    break
  fi
  sleep 2
done
[[ -s "$EVIDENCE_DIR/restored-platform-oidc-discovery.json" ]] && \
  [[ -s "$EVIDENCE_DIR/restored-hospitality-oidc-discovery.json" ]] || {
  echo "Restored Keycloak did not publish both Peak realms" >&2
  exit 1
}
# Both hosts are pinned to the restored stack. The environment file exports
# KEYCLOAK_ADMIN_BASE_URL for the source stack, so overriding only the public
# URL would send the restored instance's token to the source instance's admin
# API, which rejects it as an unknown issuer.
KEYCLOAK_BASE_URL=http://127.0.0.2:8081 \
KEYCLOAK_ADMIN_BASE_URL=http://127.0.0.2:8081 \
PEAK_PLATFORM_JWT_ISSUER_URI="http://127.0.0.2:8081/realms/$KEYCLOAK_PLATFORM_REALM" \
PEAK_SECURITY_JWT_ISSUER_URI="http://127.0.0.2:8081/realms/$KEYCLOAK_HOSPITALITY_REALM" \
  "$ROOT_DIR/ops/scripts/verify-keycloak-realms.sh" \
  > "$EVIDENCE_DIR/restored-keycloak-verification.log"
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
assert_same hardenedRuntimeRoles 10 "$restored_runtime_roles"
assert_same runtimeRoleMemberships 4 "$restored_runtime_memberships"
assert_same runtimeOwnedRelations 0 "$restored_runtime_owned_relations"
assert_same continuityFunctionOwner pms_tenant_continuity_owner "$restored_continuity_owner"
assert_same hardenedContinuityOwner 1 "$restored_continuity_owner_hardened"
assert_same hardenedInitialAdministratorFunctions 3 "$restored_initial_admin_functions_hardened"
assert_same hardenedInitialAdministratorOwner 1 "$restored_initial_admin_owner_hardened"
assert_same initialAdministratorOwnerMemberships 0 "$restored_initial_admin_owner_memberships"

jq -n \
  --arg schema "$restored_schema" \
  --arg tenants "$restored_tenants" \
  --arg financialTotal "$restored_financial" \
  --arg reportArtifacts "$restored_reports" \
  --arg keycloakRealms "$restored_realms" \
  --arg keycloakClients "$restored_clients" \
  --arg keycloakUsers "$restored_users" \
  --arg runtimeRoles "$restored_runtime_roles" \
  --arg runtimeMemberships "$restored_runtime_memberships" \
  --arg runtimeOwnedRelations "$restored_runtime_owned_relations" \
  --arg continuityFunctionOwner "$restored_continuity_owner" \
  --arg continuityOwnerHardened "$restored_continuity_owner_hardened" \
  --arg initialAdministratorFunctionsHardened "$restored_initial_admin_functions_hardened" \
  --arg initialAdministratorOwnerHardened "$restored_initial_admin_owner_hardened" \
  --arg initialAdministratorOwnerMemberships "$restored_initial_admin_owner_memberships" \
  --argjson keycloakAuthentication "$restored_authentication" \
  --arg postgresBackup "$(sha256sum "$postgres_backup" | cut -d' ' -f1)" \
  --arg keycloakBackup "$(sha256sum "$keycloak_backup" | cut -d' ' -f1)" \
  '{status:"passed", schema:$schema, tenants:($tenants|tonumber), financialTotal:$financialTotal,
    reportArtifacts:($reportArtifacts|tonumber), keycloak:{realms:($keycloakRealms|tonumber),
    clients:($keycloakClients|tonumber), users:($keycloakUsers|tonumber),
    authenticationVerified:$keycloakAuthentication},
    runtimeRoles:{hardened:($runtimeRoles|tonumber), memberships:($runtimeMemberships|tonumber),
    ownedRelations:($runtimeOwnedRelations|tonumber),
    continuityFunctionOwner:$continuityFunctionOwner,
    continuityOwnerHardened:($continuityOwnerHardened|tonumber),
    initialAdministratorFunctionsHardened:($initialAdministratorFunctionsHardened|tonumber),
    initialAdministratorOwnerHardened:($initialAdministratorOwnerHardened|tonumber),
    initialAdministratorOwnerMemberships:($initialAdministratorOwnerMemberships|tonumber),
    verified:true},
    backupSha256:{postgres:$postgresBackup,keycloak:$keycloakBackup}}' \
  > "$EVIDENCE_DIR/backup-restore-drill.json"

echo "$EVIDENCE_DIR/backup-restore-drill.json"
