#!/usr/bin/env bash
set -euo pipefail
trap 'echo "Real hotel acceptance failed at line $LINENO." >&2' ERR

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
PROJECT="${COMPOSE_PROJECT_NAME:-peak-real-hotel-acceptance}"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/ops/production/.env}"
COMPOSE_FILE="${COMPOSE_FILE:-$ROOT_DIR/ops/production/compose.yaml}"
OVERLAY_FILE="${OVERLAY_FILE:-$ROOT_DIR/ops/testing/compose.close-reporting.yaml}"
EVIDENCE_DIR="${REAL_HOTEL_EVIDENCE_DIR:-$ROOT_DIR/build/evidence/real-hotel}"
BASE_URL="${PEAK_BASE_URL:-http://localhost:8080}"
KEYCLOAK_URL="${KEYCLOAK_BASE_URL:-http://localhost:8081}"

for tool in curl jq openssl podman python3; do
  command -v "$tool" >/dev/null || {
    echo "Missing required tool: $tool" >&2
    exit 1
  }
done

mkdir -p "$EVIDENCE_DIR"
set -a
. "$ENV_FILE"
set +a

provider_pid=""
cleanup() {
  if [[ -n "$provider_pid" ]]; then
    kill "$provider_pid" >/dev/null 2>&1 || true
    wait "$provider_pid" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

wait_http() {
  local url="$1"
  for _ in {1..30}; do
    curl -fsS "$url" >/dev/null && return 0
    sleep 1
  done
  echo "Timed out waiting for $url" >&2
  return 1
}

random_password() {
  printf 'RH-%s' "$(openssl rand -hex 18)"
}

tenant_password="${REAL_HOTEL_TENANT_PASSWORD:-$(random_password)}"
other_password="${REAL_HOTEL_OTHER_PASSWORD:-$(random_password)}"
staff_password="${REAL_HOTEL_STAFF_PASSWORD:-$(random_password)}"
root_password="${REAL_HOTEL_ROOT_PASSWORD:-$(random_password)}"

export COMPOSE_PROJECT_NAME="$PROJECT"
export OVERLAY_FILE
export CORE_HOSPITALITY_EVIDENCE_DIR="$EVIDENCE_DIR"
export STAY_FINANCE_EVIDENCE_DIR="$EVIDENCE_DIR"
export CLOSE_REPORTING_EVIDENCE_DIR="$EVIDENCE_DIR"
export CORE_HOSPITALITY_TENANT_PASSWORD="$tenant_password"
export STAY_FINANCE_TENANT_PASSWORD="$tenant_password"
export STAY_FINANCE_OTHER_PASSWORD="$other_password"
export STAY_FINANCE_ROOT_PASSWORD="$root_password"
export CLOSE_REPORTING_TENANT_PASSWORD="$tenant_password"

"$ROOT_DIR/ops/testing/run-core-hospitality-journey.sh"

if ! curl -fsS http://localhost:8090/health >/dev/null 2>&1; then
  python3 "$ROOT_DIR/ops/testing/mock-communication-provider.py" \
    --api-key "$PEAK_COMMUNICATION_DELIVERY_HTTP_PROVIDER_API_KEY" &
  provider_pid="$!"
  wait_http http://localhost:8090/health
fi

python3 "$ROOT_DIR/ops/testing/real_hotel_departments.py" \
  --base-url "$BASE_URL" \
  --keycloak-url "$KEYCLOAK_URL" \
  --evidence-dir "$EVIDENCE_DIR" \
  --tenant-password "$tenant_password" \
  --staff-password "$staff_password" \
  --communication-provider-url "http://localhost:8090" \
  --keycloak-admin "$KEYCLOAK_ADMIN" \
  --keycloak-admin-password "$KEYCLOAK_ADMIN_PASSWORD"

python3 "$ROOT_DIR/ops/testing/api_security_acceptance.py" \
  --base-url "$BASE_URL" \
  --keycloak-url "$KEYCLOAK_URL" \
  --evidence-dir "$EVIDENCE_DIR" \
  --tenant-password "$tenant_password" \
  --other-password "$other_password"

python3 "$ROOT_DIR/ops/testing/api_penetration_test.py" \
  --base-url "$BASE_URL" \
  --keycloak-url "$KEYCLOAK_URL" \
  --evidence-dir "$EVIDENCE_DIR" \
  --openapi "$ROOT_DIR/src/test/resources/contracts/openapi-v1.json" \
  --tenant-password "$tenant_password"

write_load_args=(
  --base-url "$BASE_URL"
  --keycloak-url "$KEYCLOAK_URL"
  --evidence-dir "$EVIDENCE_DIR"
  --staff-password "$staff_password"
  --orders "${REAL_HOTEL_WRITE_ORDERS:-40}"
  --concurrency "${REAL_HOTEL_WRITE_CONCURRENCY:-8}"
  --max-error-rate "${REAL_HOTEL_WRITE_MAX_ERROR_RATE:-0}"
  --max-p95-ms "${REAL_HOTEL_WRITE_MAX_P95_MS:-3000}"
)
if [[ "${REAL_HOTEL_WRITE_DURATION_SECONDS:-0}" -gt 0 ]]; then
  write_load_args+=(--duration-seconds "$REAL_HOTEL_WRITE_DURATION_SECONDS")
fi
python3 "$ROOT_DIR/ops/testing/api_write_load_test.py" "${write_load_args[@]}"

python3 "$ROOT_DIR/ops/testing/api_load_test.py" \
  --base-url "$BASE_URL" \
  --keycloak-url "$KEYCLOAK_URL" \
  --evidence-dir "$EVIDENCE_DIR" \
  --tenant-password "$tenant_password" \
  --staff-password "$staff_password" \
  --requests "${REAL_HOTEL_LOAD_REQUESTS:-800}" \
  --concurrency "${REAL_HOTEL_LOAD_CONCURRENCY:-24}" \
  --max-error-rate "${REAL_HOTEL_LOAD_MAX_ERROR_RATE:-0.005}" \
  --max-p95-ms "${REAL_HOTEL_LOAD_MAX_P95_MS:-2000}" \
  --max-p99-ms "${REAL_HOTEL_LOAD_MAX_P99_MS:-4000}" \
  --min-throughput "${REAL_HOTEL_LOAD_MIN_THROUGHPUT:-5}"

export CLOSE_REPORTING_REUSE_FOUNDATION=true
"$ROOT_DIR/ops/testing/run-close-reporting-acceptance.sh"

CHAOS_TENANT_PASSWORD="$tenant_password" \
  "$ROOT_DIR/ops/testing/run-chaos-recovery-acceptance.sh"

SOURCE_PROJECT="$PROJECT" \
RESTORE_PROJECT="${PROJECT}-restore" \
EVIDENCE_DIR="$EVIDENCE_DIR/backup-restore" \
  "$ROOT_DIR/ops/testing/run-backup-restore-drill.sh"

tenant_id="$(jq -er '.tenantId' "$EVIDENCE_DIR/tenant-property-foundation.json")"
compose=(
  podman compose
  -p "$PROJECT"
  --env-file "$ENV_FILE"
  -f "$COMPOSE_FILE"
  -f "$OVERLAY_FILE"
)
# Two bounds, because this call hung the whole job and reported nothing.
#
# statement_timeout covers the likelier cause: these counts run immediately after chaos
# recovery and a restore drill, and a transaction either left open would block them forever,
# since PostgreSQL waits on a lock indefinitely by default. The outer timeout covers the rest,
# because a wedged exec channel is not something the database can time out on our behalf.
database_counts="$(timeout 120 "${compose[@]}" exec -T \
  -e PGOPTIONS='-c statement_timeout=60000' postgres \
  psql -U "$POSTGRES_MIGRATOR_USER" -d "$POSTGRES_DB" -At -F '|' -c "
    SELECT
      (SELECT count(*) FROM audit_logs WHERE tenant_id = '$tenant_id'),
      (SELECT count(*) FROM outbox_events WHERE tenant_id = '$tenant_id'),
      (SELECT count(*) FROM outbox_events WHERE tenant_id = '$tenant_id' AND status = 'dead_letter'),
      (SELECT count(*) FROM payment_transactions WHERE tenant_id = '$tenant_id'),
      (SELECT count(*) FROM stock_movements WHERE tenant_id = '$tenant_id'),
      (SELECT count(*) FROM report_runs WHERE tenant_id = '$tenant_id');
  ")"
IFS='|' read -r audit_count outbox_count dead_letter_count payment_count stock_movement_count report_count \
  <<<"$database_counts"

[[ "$audit_count" -gt 0 ]]
[[ "$outbox_count" -gt 0 ]]
[[ "$dead_letter_count" -eq 0 ]]
[[ "$payment_count" -gt 0 ]]
[[ "$stock_movement_count" -gt 0 ]]
[[ "$report_count" -gt 0 ]]

jq -n \
  --arg generatedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --arg tenantId "$tenant_id" \
  --arg propertyId "$(jq -er '.propertyId' "$EVIDENCE_DIR/core-hospitality-journey.json")" \
  --argjson auditRecords "$audit_count" \
  --argjson outboxEvents "$outbox_count" \
  --argjson deadLetterEvents "$dead_letter_count" \
  --argjson paymentTransactions "$payment_count" \
  --argjson stockMovements "$stock_movement_count" \
  --argjson reportRuns "$report_count" \
  --slurpfile departments "$EVIDENCE_DIR/real-hotel-departments.json" \
  --slurpfile security "$EVIDENCE_DIR/api-security.json" \
  --slurpfile penetration "$EVIDENCE_DIR/api-penetration.json" \
  --slurpfile writeLoad "$EVIDENCE_DIR/api-write-load.json" \
  --slurpfile load "$EVIDENCE_DIR/api-load.json" \
  --slurpfile close "$EVIDENCE_DIR/close-reporting.json" \
  --slurpfile chaos "$EVIDENCE_DIR/chaos-recovery.json" \
  --slurpfile restore "$EVIDENCE_DIR/backup-restore/backup-restore-drill.json" \
  '{
    suite: "real-hotel-end-to-end",
    result: "passed",
    generatedAt: $generatedAt,
    tenantId: $tenantId,
    propertyId: $propertyId,
    coverage: {
      publicApiJourney: true,
      distinctDepartmentIdentities: true,
      negativeOperationalPaths: true,
      apiSecurityAttacks: true,
      mixedDepartmentLoad: true,
      closeReportingAndPdf: true,
      databaseInvariantAssertions: true,
      serviceFailureRecovery: true,
      populatedBackupRestore: true
    },
    databaseEvidence: {
      auditRecords: $auditRecords,
      outboxEvents: $outboxEvents,
      deadLetterEvents: $deadLetterEvents,
      paymentTransactions: $paymentTransactions,
      stockMovements: $stockMovements,
      reportRuns: $reportRuns
    },
    departmentRequestCount: $departments[0].requestCount,
    securityCheckCount: ($security[0].checks | length),
    penetrationProbeCount: $penetration[0].probeCount,
    writeLoad: $writeLoad[0].workload,
    writeLoadFinancial: $writeLoad[0].financial,
    writeLoadLatency: $writeLoad[0].latency,
    load: $load[0].workload,
    loadLatency: $load[0].latency,
    certifiedBusinessDate: $close[0].generatedAt,
    chaosRecovery: $chaos[0].recovery,
    backupRestore: $restore[0],
    evidenceFiles: [
      "tenant-property-foundation.json",
      "stay-finance-foundation.json",
      "core-hospitality-journey.json",
      "real-hotel-departments.json",
      "api-security.json",
      "api-penetration.json",
      "api-write-load.json",
      "api-load.json",
      "close-reporting.json",
      "chaos-recovery.json",
      "backup-restore/backup-restore-drill.json",
      "daily-management-summary.pdf"
    ]
  }' | tee "$EVIDENCE_DIR/real-hotel-acceptance.json"
