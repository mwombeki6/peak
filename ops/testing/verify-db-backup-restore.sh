#!/usr/bin/env bash
#
# Proves a Peak database can actually be restored into a fresh PostgreSQL cluster.
#
#   fresh cluster -> role-bootstrap.sql -> flyway migrate -> pg_dump
#                 -> fresh cluster -> role-bootstrap.sql -> restore -> verify
#
# and, as a negative control, that the same dump FAILS to restore into a cluster
# where role-bootstrap.sql was not applied.
#
# ## Why this exists
#
# PostgreSQL roles are cluster-level; a database dump is not. `pg_dump` of one
# database emits the objects a role owns and no `CREATE ROLE` for the role
# itself, so a restore into a fresh cluster dies on an unknown owner and takes
# the whole recovery with it. Peak hit this for real: three SECURITY DEFINER
# owner roles created by migrations were missing from role-bootstrap.sql, and
# nothing noticed until an acceptance drill twenty migrations later.
#
# The negative control matters as much as the positive one. Without it, a future
# change to pg_dump defaults or to how ownership is emitted could make the happy
# path pass for the wrong reason — a restore that succeeds because it no longer
# references roles at all would look identical to one that succeeds because the
# roles are correctly bootstrapped.
#
# ## What it needs
#
#   podman or docker, and a PostgreSQL image
#   this repository's migrations and ops/production/role-bootstrap.sql
#
# Deliberately nothing else. No Peak API, Redis, Keycloak, Caddy, payment or
# notification providers. The full ops/testing/run-backup-restore-drill.sh needs
# the entire compose stack, which is why it only ever ran in CI — and CI is
# exactly what you do not have when you are restoring from a backup.
#
# ## Usage
#
#   ./ops/testing/verify-db-backup-restore.sh                  # default matrix
#   PG_VERSIONS="18.4" ./ops/testing/verify-db-backup-restore.sh
#   KEEP_EVIDENCE=1 ./ops/testing/verify-db-backup-restore.sh
#
# Exits 0 only if every version in the matrix passes both the positive path and
# the negative control.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MIGRATIONS_DIR="${MIGRATIONS_DIR:-$ROOT_DIR/src/main/resources/db/migration}"
BOOTSTRAP_SQL="${BOOTSTRAP_SQL:-$ROOT_DIR/ops/production/role-bootstrap.sql}"
EVIDENCE_DIR="${EVIDENCE_DIR:-$ROOT_DIR/build/evidence/db-restore}"

# The supported matrix. Keep this in step with the migration-matrix workflow:
# a version Peak claims to support and never restores on is an assumption.
PG_VERSIONS="${PG_VERSIONS:-16.14 18.4}"
PG_IMAGE_REPO="${PG_IMAGE_REPO:-docker.io/library/postgres}"
FLYWAY_IMAGE="${FLYWAY_IMAGE:-docker.io/flyway/flyway:latest}"

DB_NAME="${DB_NAME:-peak}"
MIGRATOR="${MIGRATOR:-peak_migrator}"

# Local throwaway containers, so these are secrets in name only. The real
# credentials live in the secrets layer and are never needed to prove a restore.
SUPERUSER_PASSWORD="drill-only-not-a-secret"
ROLE_PASSWORD="drill-only-not-a-secret"

if command -v podman >/dev/null 2>&1; then
  CONTAINER_CLI="${CONTAINER_CLI:-podman}"
elif command -v docker >/dev/null 2>&1; then
  CONTAINER_CLI="${CONTAINER_CLI:-docker}"
else
  echo "Neither podman nor docker is available; one of them is required." >&2
  exit 1
fi

[[ -d "$MIGRATIONS_DIR" ]] || { echo "Missing migrations: $MIGRATIONS_DIR" >&2; exit 1; }
[[ -f "$BOOTSTRAP_SQL" ]] || { echo "Missing bootstrap SQL: $BOOTSTRAP_SQL" >&2; exit 1; }
mkdir -p "$EVIDENCE_DIR"

RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)"
CONTAINERS=()

cleanup() {
  for name in "${CONTAINERS[@]:-}"; do
    [[ -n "$name" ]] && "$CONTAINER_CLI" rm -f "$name" >/dev/null 2>&1 || true
  done
}
trap cleanup EXIT

# ---------------------------------------------------------------------------

start_postgres() {
  local name="$1" version="$2"
  "$CONTAINER_CLI" rm -f "$name" >/dev/null 2>&1 || true
  "$CONTAINER_CLI" run -d --name "$name" \
    -e POSTGRES_PASSWORD="$SUPERUSER_PASSWORD" \
    -e POSTGRES_DB="$DB_NAME" \
    "$PG_IMAGE_REPO:$version" >/dev/null
  CONTAINERS+=("$name")

  # The official image runs a temporary server for initdb, then shuts it down and
  # starts the real one. pg_isready happily answers the temporary server, so a
  # single successful probe is not readiness — it is a race that 16.14 won and
  # 18.4 lost. Require consecutive real queries instead.
  local waited=0 streak=0
  while [[ $streak -lt 3 ]]; do
    if "$CONTAINER_CLI" exec "$name" psql -U postgres -d "$DB_NAME" -XAt \
        -c 'SELECT 1' >/dev/null 2>&1; then
      streak=$((streak + 1))
    else
      streak=0
    fi
    sleep 1
    waited=$((waited + 1))
    if [[ $waited -ge 120 ]]; then
      echo "PostgreSQL $version did not become ready within 120s" >&2
      return 1
    fi
  done
}

psql_q() {
  local name="$1" sql="$2"
  "$CONTAINER_CLI" exec "$name" psql -U postgres -d "$DB_NAME" -XAt -c "$sql"
}

# role-bootstrap.sql creates the no-login roles migrations depend on, plus the
# login roles, whose passwords it takes as psql variables.
apply_bootstrap() {
  local name="$1"
  "$CONTAINER_CLI" exec -i "$name" psql -U postgres -d "$DB_NAME" \
    -v ON_ERROR_STOP=1 \
    -v peak_app_password="$ROLE_PASSWORD" \
    -v peak_worker_password="$ROLE_PASSWORD" \
    -v peak_platform_password="$ROLE_PASSWORD" \
    -v peak_platform_support_password="$ROLE_PASSWORD" \
    -f - < "$BOOTSTRAP_SQL" >/dev/null
}

# The migrator is a LOGIN role; role-bootstrap.sql does not create it because
# provisioning owns it. Superuser here only so the drill needs no grant dance.
create_migrator() {
  local name="$1"
  psql_q "$name" \
    "CREATE ROLE $MIGRATOR LOGIN SUPERUSER PASSWORD '$SUPERUSER_PASSWORD'" >/dev/null
}

run_migrations() {
  local name="$1" log="$2"
  "$CONTAINER_CLI" run --rm --network "container:$name" \
    -v "$MIGRATIONS_DIR:/flyway/sql:ro,Z" \
    "$FLYWAY_IMAGE" \
    "-url=jdbc:postgresql://127.0.0.1:5432/$DB_NAME" \
    "-user=$MIGRATOR" \
    "-password=$SUPERUSER_PASSWORD" \
    -connectRetries=10 \
    migrate > "$log" 2>&1
}

# ---------------------------------------------------------------------------

verify_version() {
  local version="$1"
  local tag="${version//./_}"
  local source_name="peak-drill-src-$tag"
  local target_name="peak-drill-tgt-$tag"
  local control_name="peak-drill-ctl-$tag"
  local dump_file="$EVIDENCE_DIR/peak-$tag-$RUN_ID.sql"
  local report="$EVIDENCE_DIR/report-$tag-$RUN_ID.txt"
  local restore_err="$EVIDENCE_DIR/restore-stderr-$tag-$RUN_ID.txt"
  local failures=0

  echo "PostgreSQL $version"

  start_postgres "$source_name" "$version"
  create_migrator "$source_name"
  apply_bootstrap "$source_name"

  if run_migrations "$source_name" "$EVIDENCE_DIR/flyway-$tag-$RUN_ID.log"; then
    echo "  migrations              PASS"
  else
    echo "  migrations              FAIL (see $EVIDENCE_DIR/flyway-$tag-$RUN_ID.log)"
    return 1
  fi

  "$CONTAINER_CLI" exec "$source_name" pg_dump -U "$MIGRATOR" -d "$DB_NAME" > "$dump_file"
  if [[ -s "$dump_file" ]]; then
    echo "  dump                    PASS"
  else
    echo "  dump                    FAIL (empty)"
    return 1
  fi

  # The whole reason this drill exists. If a dump ever starts carrying its own
  # roles, the negative control below stops being meaningful and should be
  # revisited rather than deleted.
  local roles_in_dump
  roles_in_dump="$(grep -c '^CREATE ROLE' "$dump_file" || true)"

  # --- negative control: restore without the bootstrap must fail -------------
  start_postgres "$control_name" "$version"
  create_migrator "$control_name"
  local control_out
  control_out="$("$CONTAINER_CLI" exec -i "$control_name" \
    psql -U postgres -d "$DB_NAME" -v ON_ERROR_STOP=1 < "$dump_file" 2>&1 || true)"
  local control_error
  control_error="$(grep -m1 '^ERROR:.*role .* does not exist' <<<"$control_out" || true)"

  if [[ -n "$control_error" ]]; then
    echo "  negative control        PASS ($control_error)"
  else
    echo "  negative control        FAIL — a dump restored into a cluster with no roles."
    echo "                          Either bootstrap is no longer required, or this drill"
    echo "                          is no longer testing what it claims."
    failures=$((failures + 1))
  fi

  # --- positive path --------------------------------------------------------
  start_postgres "$target_name" "$version"
  create_migrator "$target_name"
  apply_bootstrap "$target_name"

  "$CONTAINER_CLI" exec -i "$target_name" \
    psql -U postgres -d "$DB_NAME" -v ON_ERROR_STOP=1 < "$dump_file" > /dev/null 2> "$restore_err" || true
  local restore_errors
  restore_errors="$(grep -c '^ERROR:' "$restore_err" || true)"

  if [[ "$restore_errors" -eq 0 ]]; then
    echo "  restore                 PASS"
  else
    echo "  restore                 FAIL ($restore_errors errors, see $restore_err)"
    failures=$((failures + 1))
  fi

  # --- what actually landed -------------------------------------------------
  local head tables functions roles_present roles_expected
  head="$(psql_q "$target_name" \
    "SELECT coalesce(max(version::numeric)::text, 'none') FROM flyway_schema_history WHERE success")"
  tables="$(psql_q "$target_name" \
    "SELECT count(*) FROM information_schema.tables WHERE table_schema='public'")"
  # Peak's own functions only. Counting everything in public makes the number
  # differ by PostgreSQL version for a reason that has nothing to do with Peak:
  # pgcrypto and btree_gist ship 224 functions on 16.14 and 249 on 18.4, which
  # showed up as a 25-function delta and cost time to explain. Excluding
  # extension-owned objects makes the count comparable across the matrix.
  functions="$(psql_q "$target_name" \
    "SELECT count(*) FROM pg_proc p
       JOIN pg_namespace n ON n.oid = p.pronamespace
      WHERE n.nspname = 'public'
        AND NOT EXISTS (
          SELECT 1 FROM pg_depend d
          WHERE d.objid = p.oid AND d.deptype = 'e'
        )")"
  roles_present="$(psql_q "$target_name" \
    "SELECT count(*) FROM pg_roles WHERE rolname LIKE 'pms\\_%'")"
  roles_expected="$(grep -cE '^\s*CREATE ROLE pms_' "$BOOTSTRAP_SQL" || true)"

  local expected_head
  expected_head="$(ls "$MIGRATIONS_DIR" | sed -n 's/^V\([0-9][0-9]*\)__.*/\1/p' | sort -n | tail -1)"

  if [[ "$head" == "$expected_head" ]]; then
    echo "  flyway head             PASS (V$head)"
  else
    echo "  flyway head             FAIL (restored V$head, migrations go to V$expected_head)"
    failures=$((failures + 1))
  fi

  if [[ "$roles_present" -ge "$roles_expected" ]]; then
    echo "  roles                   PASS ($roles_present/$roles_expected)"
  else
    echo "  roles                   FAIL ($roles_present/$roles_expected)"
    failures=$((failures + 1))
  fi

  # --- evidence -------------------------------------------------------------
  {
    echo "run                  $RUN_ID"
    echo "postgres             $version"
    echo "postgres full        $(psql_q "$target_name" 'SHOW server_version')"
    echo "flyway head          V$head (expected V$expected_head)"
    echo "dump sha256          $(sha256sum "$dump_file" | cut -d' ' -f1)"
    echo "dump bytes           $(wc -c < "$dump_file")"
    echo "roles in dump        $roles_in_dump (expected 0: roles are cluster-level)"
    echo "pms_ roles present   $roles_present/$roles_expected"
    echo "tables restored      $tables"
    echo "peak functions       $functions (extension-owned excluded)"
    echo "restore errors       $restore_errors"
    echo "negative control     ${control_error:-DID NOT FAIL}"
    echo "result               $([[ $failures -eq 0 ]] && echo PASS || echo FAIL)"
  } > "$report"

  echo "  tables/functions        $tables / $functions"
  echo "  evidence                $report"

  "$CONTAINER_CLI" rm -f "$source_name" "$target_name" "$control_name" >/dev/null 2>&1 || true

  if [[ "${KEEP_EVIDENCE:-0}" != "1" ]]; then
    rm -f "$dump_file"
  fi

  if [[ $failures -eq 0 ]]; then
    echo "  RESULT                  PASS"
    return 0
  fi
  echo "  RESULT                  FAIL"
  return 1
}

# ---------------------------------------------------------------------------

echo "Peak database backup/restore drill — $RUN_ID"
echo "container runtime: $CONTAINER_CLI"
echo

overall=0
for version in $PG_VERSIONS; do
  verify_version "$version" || overall=1
  echo
done

if [[ $overall -eq 0 ]]; then
  echo "All supported PostgreSQL versions restore cleanly."
else
  echo "At least one PostgreSQL version failed the drill." >&2
fi
exit $overall
