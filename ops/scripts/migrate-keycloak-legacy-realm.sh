#!/usr/bin/env sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-$ROOT_DIR/ops/production/compose.yaml}"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/ops/production/.env}"
KEYCLOAK_BASE_URL="${KEYCLOAK_BASE_URL:-http://localhost:8081}"
KEYCLOAK_ADMIN_BASE_URL="${KEYCLOAK_ADMIN_BASE_URL:-$KEYCLOAK_BASE_URL}"
LEGACY_REALM="${KEYCLOAK_LEGACY_REALM:-peak}"
WORK_DIR=""
KEYCLOAK_BACKUP=""
PEAK_BACKUP=""
COMPLETED=false

compose() {
  podman compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"
}

cleanup() {
  if [ -n "$WORK_DIR" ] && [ -d "$WORK_DIR" ]; then
    case "$WORK_DIR" in
      "${TMPDIR:-/tmp}"/peak-keycloak-migration.*)
        find "$WORK_DIR" -depth -delete
        ;;
      *)
        echo "Refusing to remove unexpected migration work directory: $WORK_DIR" >&2
        ;;
    esac
  fi
  if [ "$COMPLETED" != "true" ]; then
    echo "Legacy identity cutover did not complete; API services may remain stopped." >&2
    [ -z "$PEAK_BACKUP" ] || echo "Peak backup: $PEAK_BACKUP" >&2
    [ -z "$KEYCLOAK_BACKUP" ] || echo "Keycloak backup: $KEYCLOAK_BACKUP" >&2
  fi
}
trap cleanup 0 HUP INT TERM

if [ "${KEYCLOAK_LEGACY_REALM_MIGRATION_APPROVED:-false}" != "true" ]; then
  echo "Set KEYCLOAK_LEGACY_REALM_MIGRATION_APPROVED=true for the maintenance-window cutover." >&2
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
export KEYCLOAK_BASE_URL KEYCLOAK_ADMIN_BASE_URL

LEGACY_ISSUER="${KEYCLOAK_LEGACY_ISSUER:-${KEYCLOAK_HOSTNAME%/}/realms/$LEGACY_REALM}"
PLATFORM_ISSUER="${KEYCLOAK_HOSTNAME%/}/realms/$KEYCLOAK_PLATFORM_REALM"
HOSPITALITY_ISSUER="${KEYCLOAK_HOSTNAME%/}/realms/$KEYCLOAK_HOSPITALITY_REALM"
if [ "$LEGACY_ISSUER" = "$PLATFORM_ISSUER" ] || [ "$LEGACY_ISSUER" = "$HOSPITALITY_ISSUER" ]; then
  echo "Legacy issuer must differ from both target issuers" >&2
  exit 1
fi

WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/peak-keycloak-migration.XXXXXX")"
chmod 700 "$WORK_DIR"
mkdir -m 700 "$WORK_DIR/export" "$WORK_DIR/generated"

echo "Starting database services and taking pre-cutover backups"
compose up -d postgres keycloak-db
PEAK_BACKUP="$(BACKUP_DIR="${BACKUP_DIR:-$ROOT_DIR/backups}" "$ROOT_DIR/ops/scripts/backup-postgres.sh")"
KEYCLOAK_BACKUP="$(BACKUP_DIR="${BACKUP_DIR:-$ROOT_DIR/backups}" "$ROOT_DIR/ops/scripts/backup-keycloak.sh")"

echo "Stopping authentication and application runtimes for a consistent export"
for service in peak-api peak-platform peak-worker keycloak; do
  if [ -n "$(compose ps -q "$service")" ]; then
    compose stop "$service"
  fi
done
# The one-shot process runs as container root so a rootless Podman user maps
# writes back to the invoking operator instead of an unmapped subordinate UID.
# The bind mount is a private, randomly named directory removed by the trap.
compose run --rm --no-deps \
  --user 0:0 \
  --volume "$WORK_DIR/export:/opt/keycloak/data/export:Z" \
  keycloak export \
  --dir /opt/keycloak/data/export \
  --realm "$LEGACY_REALM" \
  --users realm_file

LEGACY_EXPORT="$(find "$WORK_DIR/export" -maxdepth 1 -type f -name "$LEGACY_REALM-realm.json" -print -quit)"
if [ -z "$LEGACY_EXPORT" ]; then
  echo "Keycloak did not produce the expected $LEGACY_REALM realm export" >&2
  exit 1
fi

compose exec -T postgres \
  psql -X -qAt -v ON_ERROR_STOP=1 \
    -v legacy_issuer="$LEGACY_ISSUER" \
    -U "$POSTGRES_MIGRATOR_USER" "$POSTGRES_DB" <<'SQL' > "$WORK_DIR/identity-links.json"
SELECT COALESCE(
    pg_catalog.jsonb_agg(
        pg_catalog.jsonb_build_object(
            'identityLinkId', il.id,
            'identityMode', il.identity_mode,
            'subject', il.subject
        )
        ORDER BY il.id
    ),
    '[]'::jsonb
)
FROM public.identity_links il
WHERE il.provider = 'oidc'
  AND il.issuer = :'legacy_issuer'
  AND il.revoked_at IS NULL;
SQL
chmod 600 "$WORK_DIR/identity-links.json"

python3 "$ROOT_DIR/ops/scripts/legacy-keycloak-realm-migration.py" build \
  --export "$LEGACY_EXPORT" \
  --links "$WORK_DIR/identity-links.json" \
  --output-dir "$WORK_DIR/generated" \
  --legacy-realm "$LEGACY_REALM" \
  --platform-realm "$KEYCLOAK_PLATFORM_REALM" \
  --hospitality-realm "$KEYCLOAK_HOSPITALITY_REALM"

echo "Starting Keycloak, reconciling target realms, and importing preserved identities"
compose up -d --no-deps keycloak
attempt=1
until curl -fsS "$KEYCLOAK_BASE_URL/realms/master/.well-known/openid-configuration" >/dev/null 2>&1; do
  if [ "$attempt" -ge 90 ]; then
    echo "Keycloak did not become ready after the offline export" >&2
    exit 1
  fi
  attempt=$((attempt + 1))
  sleep 2
done
python3 "$ROOT_DIR/ops/scripts/reconcile-keycloak-realms.py"
python3 "$ROOT_DIR/ops/scripts/legacy-keycloak-realm-migration.py" apply \
  --platform-import "$WORK_DIR/generated/platform-users.partial-import.json" \
  --hospitality-import "$WORK_DIR/generated/hospitality-users.partial-import.json" \
  --platform-realm "$KEYCLOAK_PLATFORM_REALM" \
  --hospitality-realm "$KEYCLOAK_HOSPITALITY_REALM"

EXPECTED_PLATFORM="$(jq -er '.platformIdentityLinks' "$WORK_DIR/generated/migration-manifest.json")"
EXPECTED_HOSPITALITY="$(jq -er '.hospitalityIdentityLinks' "$WORK_DIR/generated/migration-manifest.json")"
CORRELATION_ID="$(python3 -c 'import uuid; print(uuid.uuid4())')"

echo "Atomically switching Peak identity issuers with append-only audit evidence"
CUTOVER_RESULT="$(
  compose exec -T postgres \
    psql -X -qAt -v ON_ERROR_STOP=1 \
      -v legacy_issuer="$LEGACY_ISSUER" \
      -v platform_issuer="$PLATFORM_ISSUER" \
      -v hospitality_issuer="$HOSPITALITY_ISSUER" \
      -v expected_platform="$EXPECTED_PLATFORM" \
      -v expected_hospitality="$EXPECTED_HOSPITALITY" \
      -v correlation_id="$CORRELATION_ID" \
      -U "$POSTGRES_MIGRATOR_USER" "$POSTGRES_DB" <<'SQL'
BEGIN;
SET LOCAL lock_timeout = '10s';
CREATE FUNCTION pg_temp.migrate_peak_identity_issuers(
    p_legacy_issuer text,
    p_platform_issuer text,
    p_hospitality_issuer text,
    p_expected_platform integer,
    p_expected_hospitality integer,
    p_correlation_id text
) RETURNS jsonb
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $migration$
DECLARE
    v_platform integer;
    v_hospitality integer;
BEGIN
    IF NULLIF(pg_catalog.btrim(p_legacy_issuer), '') IS NULL
       OR NULLIF(pg_catalog.btrim(p_platform_issuer), '') IS NULL
       OR NULLIF(pg_catalog.btrim(p_hospitality_issuer), '') IS NULL
       OR p_legacy_issuer IN (p_platform_issuer, p_hospitality_issuer)
       OR p_platform_issuer = p_hospitality_issuer THEN
        RAISE EXCEPTION 'Legacy and target issuers must be non-empty and distinct';
    END IF;

    CREATE TEMP TABLE keycloak_legacy_cutover_links ON COMMIT DROP AS
    SELECT il.id, il.identity_mode, il.tenant_id, il.platform_user_id, il.subject
    FROM public.identity_links il
    WHERE il.provider = 'oidc'
      AND il.issuer = p_legacy_issuer
      AND il.revoked_at IS NULL
    FOR UPDATE;

    SELECT pg_catalog.count(*) FILTER (WHERE identity_mode = 'platform'),
           pg_catalog.count(*) FILTER (WHERE identity_mode = 'tenant')
    INTO v_platform, v_hospitality
    FROM pg_temp.keycloak_legacy_cutover_links;

    IF v_platform <> p_expected_platform OR v_hospitality <> p_expected_hospitality THEN
        RAISE EXCEPTION 'Legacy identity inventory changed after Keycloak export';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM public.identity_links existing
        JOIN pg_temp.keycloak_legacy_cutover_links legacy
          ON existing.subject = legacy.subject
         AND existing.issuer = CASE legacy.identity_mode
             WHEN 'platform' THEN p_platform_issuer
             ELSE p_hospitality_issuer
         END
        WHERE existing.revoked_at IS NULL
          AND existing.id <> legacy.id
    ) THEN
        RAISE EXCEPTION 'A target issuer and subject is already linked elsewhere';
    END IF;

    INSERT INTO public.platform_audit_logs (
        action, entity_type, entity_id, old_values, new_values, correlation_id, outcome
    )
    SELECT 'identity.issuer.migrated', 'identity_links', legacy.id,
           pg_catalog.jsonb_build_object('issuer', p_legacy_issuer),
           pg_catalog.jsonb_build_object('issuer', p_platform_issuer),
           p_correlation_id, 'success'
    FROM pg_temp.keycloak_legacy_cutover_links legacy
    WHERE legacy.identity_mode = 'platform';

    INSERT INTO public.audit_logs (
        tenant_id, action, entity_type, entity_id, old_values, new_values,
        correlation_id, outcome
    )
    SELECT legacy.tenant_id, 'identity.issuer.migrated', 'identity_links', legacy.id,
           pg_catalog.jsonb_build_object('issuer', p_legacy_issuer),
           pg_catalog.jsonb_build_object('issuer', p_hospitality_issuer),
           p_correlation_id, 'success'
    FROM pg_temp.keycloak_legacy_cutover_links legacy
    WHERE legacy.identity_mode = 'tenant';

    UPDATE public.identity_links target
    SET issuer = CASE legacy.identity_mode
            WHEN 'platform' THEN p_platform_issuer
            ELSE p_hospitality_issuer
        END,
        updated_at = pg_catalog.now()
    FROM pg_temp.keycloak_legacy_cutover_links legacy
    WHERE target.id = legacy.id;

    RETURN pg_catalog.jsonb_build_object(
        'platformIdentityLinks', v_platform,
        'hospitalityIdentityLinks', v_hospitality
    );
END;
$migration$;
SELECT pg_temp.migrate_peak_identity_issuers(
    :'legacy_issuer',
    :'platform_issuer',
    :'hospitality_issuer',
    :'expected_platform'::integer,
    :'expected_hospitality'::integer,
    :'correlation_id'
);
COMMIT;
SQL
)"
echo "$CUTOVER_RESULT" | jq -e \
  --argjson platform "$EXPECTED_PLATFORM" \
  --argjson hospitality "$EXPECTED_HOSPITALITY" \
  '.platformIdentityLinks == $platform and .hospitalityIdentityLinks == $hospitality' >/dev/null

python3 "$ROOT_DIR/ops/scripts/legacy-keycloak-realm-migration.py" disable \
  --legacy-realm "$LEGACY_REALM"
"$ROOT_DIR/ops/scripts/verify-keycloak-realms.sh"

echo "Starting Peak runtimes and running post-cutover smoke checks"
compose up -d --no-deps peak-api peak-platform peak-worker
"$ROOT_DIR/ops/scripts/smoke-test.sh" \
  "${PEAK_API_LOCAL_URL:-http://localhost:8080}" \
  "$KEYCLOAK_BASE_URL" \
  "${PEAK_PLATFORM_LOCAL_URL:-http://localhost:8082}"

mkdir -p "$ROOT_DIR/build/evidence"
EVIDENCE="$ROOT_DIR/build/evidence/keycloak-legacy-realm-migration-$CORRELATION_ID.json"
jq \
  --arg correlationId "$CORRELATION_ID" \
  --arg peakBackup "$PEAK_BACKUP" \
  --arg keycloakBackup "$KEYCLOAK_BACKUP" \
  --argjson cutover "$CUTOVER_RESULT" \
  '. + {
      result: "passed",
      correlationId: $correlationId,
      peakBackup: $peakBackup,
      keycloakBackup: $keycloakBackup,
      cutover: $cutover
  }' "$WORK_DIR/generated/migration-manifest.json" > "$EVIDENCE"
chmod 600 "$EVIDENCE"

COMPLETED=true
echo "Legacy Keycloak realm migration completed: $EVIDENCE"
