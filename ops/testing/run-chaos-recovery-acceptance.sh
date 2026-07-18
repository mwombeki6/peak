#!/usr/bin/env bash
set -euo pipefail
trap 'echo "Chaos recovery acceptance failed at line $LINENO." >&2' ERR

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
PROJECT="${COMPOSE_PROJECT_NAME:-peak-real-hotel-acceptance}"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/ops/production/.env}"
COMPOSE_FILE="${COMPOSE_FILE:-$ROOT_DIR/ops/production/compose.yaml}"
OVERLAY_FILE="${OVERLAY_FILE:-$ROOT_DIR/ops/testing/compose.close-reporting.yaml}"
EVIDENCE_DIR="${REAL_HOTEL_EVIDENCE_DIR:-$ROOT_DIR/build/evidence/real-hotel}"
BASE_URL="${PEAK_BASE_URL:-http://localhost:8080}"
KEYCLOAK_URL="${KEYCLOAK_BASE_URL:-http://localhost:8081}"
TENANT_PASSWORD="${CHAOS_TENANT_PASSWORD:?CHAOS_TENANT_PASSWORD is required}"

set -a
. "$ENV_FILE"
set +a
HOSPITALITY_REALM="${KEYCLOAK_HOSPITALITY_REALM:-peak-hospitality}"

compose=(
  podman compose
  -p "$PROJECT"
  --env-file "$ENV_FILE"
  -f "$COMPOSE_FILE"
  -f "$OVERLAY_FILE"
)

foundation="$EVIDENCE_DIR/tenant-property-foundation.json"
close_evidence="$EVIDENCE_DIR/close-reporting.json"
tenant_id="$(jq -er '.tenantId' "$foundation")"
property_id="$(jq -er '.propertyId' "$foundation")"
channel_id="$(jq -er '.channelId' "$foundation")"
report_run_id="$(jq -er '.dailyReportRunId' "$close_evidence")"
report_hash="$(jq -er '.contentHash' "$close_evidence")"

token_for() {
  curl -fsS -X POST \
    --connect-timeout 2 \
    --max-time 20 \
    "$KEYCLOAK_URL/realms/$HOSPITALITY_REALM/protocol/openid-connect/token" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    --data-urlencode "grant_type=password" \
    --data-urlencode "client_id=peak-acceptance" \
    --data-urlencode "username=acceptance-tenant-admin" \
    --data-urlencode "password=$TENANT_PASSWORD" |
    jq -er '.access_token'
}

wait_http() {
  local url="$1" attempts="${2:-90}"
  for _ in $(seq 1 "$attempts"); do
    curl -fsS --connect-timeout 2 --max-time 5 "$url" >/dev/null 2>&1 && return 0
    sleep 2
  done
  echo "Timed out waiting for $url" >&2
  return 1
}

download_report() {
  local url="$1"
  podman run --rm \
    --network "${PROJECT}_default" \
    docker.io/curlimages/curl:8.16.0 \
    -fsSL "$url"
}

api() {
  local method="$1" path="$2" expected="$3" payload="${4:-}"
  local args=(
    -sS -X "$method" "$BASE_URL$path"
    -H "Authorization: Bearer $token"
    -H "X-Correlation-Id: chaos-${method,,}"
  )
  if [[ -n "$payload" ]]; then
    args+=(-H "Content-Type: application/json" -H "Idempotency-Key: chaos-$(date +%s%N)" --data "$payload")
  fi
  local output status
  output="$(curl --connect-timeout 2 --max-time 20 "${args[@]}" -w $'\n%{http_code}')"
  status="${output##*$'\n'}"
  API_BODY="${output%$'\n'*}"
  [[ "$status" == "$expected" ]] || {
    echo "$method $path returned $status, expected $expected: $API_BODY" >&2
    return 1
  }
}

db_snapshot() {
  "${compose[@]}" exec -T postgres \
    psql -XAt -F '|' -U "$POSTGRES_MIGRATOR_USER" -d "$POSTGRES_DB" -c "
      SELECT
        (SELECT count(*) FROM tenants WHERE deleted_at IS NULL),
        (SELECT count(*) FROM payment_transactions WHERE tenant_id = '$tenant_id'),
        (SELECT COALESCE(round(sum(amount), 2), 0) FROM payment_transactions WHERE tenant_id = '$tenant_id'),
        (SELECT count(*) FROM report_artifacts WHERE tenant_id = '$tenant_id'),
        (SELECT count(*) FROM audit_logs WHERE tenant_id = '$tenant_id');
    "
}

token="$(token_for)"
before="$(db_snapshot)"
IFS='|' read -r before_tenants before_payments before_payment_total before_reports before_audit <<<"$before"

# A stopped worker must not interrupt API operations, and queued work must replay.
"${compose[@]}" stop peak-worker
api GET "/api/v1/properties/$property_id" 200
api POST "/api/v1/communication/notifications" 200 "$(
  jq -nc --arg property "$property_id" --arg channel "$channel_id" '{
    propertyId:$property,
    contactChannelId:$channel,
    purpose:"operational_reports",
    subject:"Worker recovery acceptance",
    content:"This notification must survive a worker restart."
  }'
)"
delivery_id="$(jq -er '.deliveryRequestId' <<<"$API_BODY")"
api GET "/api/v1/communication/delivery-requests/$delivery_id" 200
[[ "$(jq -r '.status' <<<"$API_BODY")" != "delivered" ]]
"${compose[@]}" up -d --no-deps peak-worker
delivery_status=""
for _ in {1..60}; do
  api GET "/api/v1/communication/delivery-requests/$delivery_id" 200
  delivery_status="$(jq -r '.status' <<<"$API_BODY")"
  [[ "$delivery_status" == "delivered" ]] && break
  sleep 2
done
[[ "$delivery_status" == "delivered" ]]

# Cached JWT validation must keep working while identity issuance is unavailable.
"${compose[@]}" stop keycloak
api GET "/api/v1/properties/$property_id" 200
if token_for >/dev/null 2>&1; then
  echo "Keycloak issued a token while stopped" >&2
  exit 1
fi
"${compose[@]}" up -d --no-deps keycloak
wait_http "$KEYCLOAK_URL/realms/$HOSPITALITY_REALM/.well-known/openid-configuration"
token="$(token_for)"

# API process replacement must retain all database-backed state.
"${compose[@]}" stop peak-api
if curl -fsS --connect-timeout 2 --max-time 10 "$BASE_URL/actuator/health" >/dev/null 2>&1; then
  echo "API remained reachable after its container stopped" >&2
  exit 1
fi
"${compose[@]}" up -d --no-deps peak-api
wait_http "$BASE_URL/actuator/health"
api GET "/api/v1/properties/$property_id" 200

# Object storage failure must be observable and the same artifact recoverable.
api POST "/api/v1/tenants/$tenant_id/report-runs/$report_run_id/download-link" 200
signed_url="$(jq -er '.url' <<<"$API_BODY")"
"${compose[@]}" stop minio
if download_report "$signed_url" >/dev/null 2>&1; then
  echo "Report artifact remained retrievable while object storage was stopped" >&2
  exit 1
fi
"${compose[@]}" up -d --no-deps minio
wait_http "http://localhost:9000/minio/health/live"
api POST "/api/v1/tenants/$tenant_id/report-runs/$report_run_id/download-link" 200
signed_url="$(jq -er '.url' <<<"$API_BODY")"
restored_pdf="$(mktemp)"
download_report "$signed_url" >"$restored_pdf"
[[ "$(sha256sum "$restored_pdf" | awk '{print $1}')" == "$report_hash" ]]
rm -f "$restored_pdf"

# A database outage must fail health, then recover without financial drift.
"${compose[@]}" stop postgres
if curl -fsS --connect-timeout 2 --max-time 10 "$BASE_URL/actuator/health" >/dev/null 2>&1; then
  echo "API health remained UP while PostgreSQL was stopped" >&2
  exit 1
fi
"${compose[@]}" up -d --no-deps postgres
for _ in {1..60}; do
  if "${compose[@]}" exec -T postgres \
      pg_isready -U "$POSTGRES_MIGRATOR_USER" -d "$POSTGRES_DB" >/dev/null 2>&1; then
    break
  fi
  sleep 2
done
wait_http "$BASE_URL/actuator/health"
api GET "/api/v1/properties/$property_id" 200

after="$(db_snapshot)"
IFS='|' read -r after_tenants after_payments after_payment_total after_reports after_audit <<<"$after"
[[ "$before_tenants" == "$after_tenants" ]]
[[ "$before_payments" == "$after_payments" ]]
[[ "$before_payment_total" == "$after_payment_total" ]]
[[ "$before_reports" == "$after_reports" ]]
[[ "$after_audit" -ge "$before_audit" ]]

jq -n \
  --arg generatedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --arg tenantId "$tenant_id" \
  --arg propertyId "$property_id" \
  --arg deliveryRequestId "$delivery_id" \
  --arg reportHash "$report_hash" \
  --arg paymentTotal "$after_payment_total" \
  --argjson tenantCount "$after_tenants" \
  --argjson paymentCount "$after_payments" \
  --argjson reportCount "$after_reports" \
  --argjson auditBefore "$before_audit" \
  --argjson auditAfter "$after_audit" \
  '{
    suite:"service-chaos-recovery",
    result:"passed",
    generatedAt:$generatedAt,
    tenantId:$tenantId,
    propertyId:$propertyId,
    failuresInjected:["worker_stop","keycloak_stop","api_stop","object_storage_stop","postgres_stop"],
    recovery:{
      queuedDeliveryRecovered:true,
      cachedJwtContinued:true,
      identityIssuanceRecovered:true,
      apiRestartRecovered:true,
      reportHashRecovered:true,
      databaseHealthRecovered:true
    },
    invariants:{
      tenantCount:$tenantCount,
      paymentCount:$paymentCount,
      paymentTotal:$paymentTotal,
      reportCount:$reportCount,
      auditBefore:$auditBefore,
      auditAfter:$auditAfter,
      deliveryRequestId:$deliveryRequestId,
      reportHash:$reportHash
    }
  }' | tee "$EVIDENCE_DIR/chaos-recovery.json"
