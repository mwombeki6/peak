#!/usr/bin/env bash
set -euo pipefail
trap 'echo "Control, close, and reporting acceptance failed at line $LINENO." >&2' ERR

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
PROJECT="${COMPOSE_PROJECT_NAME:-peak-close-reporting-acceptance}"
BASE_URL="${PEAK_BASE_URL:-http://localhost:8080}"
KEYCLOAK_URL="${KEYCLOAK_BASE_URL:-http://localhost:8081}"
EVIDENCE_DIR="${CLOSE_REPORTING_EVIDENCE_DIR:-$ROOT_DIR/build/evidence/close-reporting}"
TENANT_PROPERTY_EVIDENCE="$EVIDENCE_DIR/tenant-property-foundation.json"
CORE_HOSPITALITY_EVIDENCE="$EVIDENCE_DIR/core-hospitality-journey.json"
NEWMAN_REPORT="$EVIDENCE_DIR/newman-private.json"
EVIDENCE_FILE="$EVIDENCE_DIR/close-reporting.json"
PDF_FILE="$EVIDENCE_DIR/daily-management-summary.pdf"

for tool in curl date jq openssl podman python3 sha256sum; do
  command -v "$tool" >/dev/null || {
    echo "Missing required tool: $tool" >&2
    exit 1
  }
done

mkdir -p "$EVIDENCE_DIR"

ENV_FILE="${ENV_FILE:-$ROOT_DIR/ops/production/.env}"
set -a
. "$ENV_FILE"
set +a

mock_pid=""
cleanup() {
  if [[ -n "$mock_pid" ]]; then
    kill "$mock_pid" >/dev/null 2>&1 || true
    wait "$mock_pid" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

wait_http() {
  local url="$1"
  local attempts="${2:-90}"
  local count=1
  until curl -fsS "$url" >/dev/null; do
    if (( count >= attempts )); then
      echo "Timed out waiting for $url" >&2
      exit 1
    fi
    count=$((count + 1))
    sleep 2
  done
}

tenant_password="${CLOSE_REPORTING_TENANT_PASSWORD:-CR-$(openssl rand -hex 18)}"
export STAY_FINANCE_TENANT_PASSWORD="$tenant_password"
export CORE_HOSPITALITY_TENANT_PASSWORD="$tenant_password"
export STAY_FINANCE_EVIDENCE_DIR="$EVIDENCE_DIR"
export CORE_HOSPITALITY_EVIDENCE_DIR="$EVIDENCE_DIR"
export COMPOSE_PROJECT_NAME="$PROJECT"
export OVERLAY_FILE="$ROOT_DIR/ops/testing/compose.close-reporting.yaml"

if [[ "${CLOSE_REPORTING_REUSE_FOUNDATION:-false}" != "true" ]]; then
  "$ROOT_DIR/ops/testing/run-core-hospitality-journey.sh"
fi

if ! curl -fsS http://localhost:8090/health >/dev/null 2>&1; then
  python3 "$ROOT_DIR/ops/testing/mock-communication-provider.py" \
    --api-key "$PEAK_COMMUNICATION_DELIVERY_HTTP_PROVIDER_API_KEY" &
  mock_pid="$!"
  wait_http "http://localhost:8090/health" 30
fi

tenant_id="$(jq -er '.tenantId' "$TENANT_PROPERTY_EVIDENCE")"
property_id="$(jq -er '.propertyId' "$CORE_HOSPITALITY_EVIDENCE")"
contact_id="$(jq -er '.contactId' "$TENANT_PROPERTY_EVIDENCE")"
channel_id="$(jq -er '.channelId' "$TENANT_PROPERTY_EVIDENCE")"
pos_session_id="$(jq -er '.posSessionId' "$CORE_HOSPITALITY_EVIDENCE")"
pos_order_id="$(jq -er '.posOrderId' "$CORE_HOSPITALITY_EVIDENCE")"

access_token="$(
  curl -fsS \
    -X POST "$KEYCLOAK_URL/realms/peak/protocol/openid-connect/token" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    --data-urlencode "grant_type=password" \
    --data-urlencode "client_id=peak-acceptance" \
    --data-urlencode "username=acceptance-tenant-admin" \
    --data-urlencode "password=$tenant_password" |
    jq -er '.access_token'
)"

API_BODY=""
api() {
  local method="$1"
  local path="$2"
  local expected="$3"
  local key="${4:-}"
  local payload="${5:-}"
  local args=(
    -sS
    -X "$method"
    "$BASE_URL$path"
    -H "Authorization: Bearer $access_token"
    -H "X-Correlation-Id: close-reporting-acceptance"
  )
  [[ -n "$key" ]] && args+=(-H "Idempotency-Key: $key")
  [[ -n "$payload" ]] &&
    args+=(-H "Content-Type: application/json" --data "$payload")
  local output status
  output="$(curl "${args[@]}" -w $'\n%{http_code}')"
  status="${output##*$'\n'}"
  API_BODY="${output%$'\n'*}"
  if [[ "$status" != "$expected" ]]; then
    echo "$method $path expected HTTP $expected, received $status" >&2
    echo "$API_BODY" >&2
    exit 1
  fi
}

api POST "/api/v1/tenants/$tenant_id/modules" \
  200 "close-reporting-tenant-reports" '{"moduleId":"reports"}'
api POST "/api/v1/properties/$property_id/modules" \
  200 "close-reporting-property-reports" '{"moduleId":"reports"}'

api POST "/api/v1/properties/$property_id/pos-orders/$pos_order_id/settle" \
  200 "close-reporting-settle-pos-$pos_order_id" '{"paymentMethod":"cash"}'
api GET "/api/v1/properties/$property_id/pos-sessions/$pos_session_id" 200
expected_cash="$(jq -er '.session.expectedCash' <<<"$API_BODY")"
api POST "/api/v1/properties/$property_id/pos-sessions/$pos_session_id/close" \
  200 "close-reporting-close-pos-$pos_session_id" \
  "$(jq -nc --argjson cash "$expected_cash" '{actualCash:$cash}')"

api POST "/api/v1/properties/$property_id/report-subscriptions" \
  200 "close-reporting-daily-subscription" '{
    "reportCode":"daily_management_summary",
    "subscriptionName":"Daily Owner Report",
    "frequency":"after_night_audit",
    "timezone":"Africa/Dar_es_Salaam",
    "languageCode":"en"
  }'
subscription_id="$(jq -er '.id' <<<"$API_BODY")"
api POST \
  "/api/v1/properties/$property_id/report-subscriptions/$subscription_id/recipients" \
  200 "close-reporting-daily-recipient" "$(
    jq -nc --arg contact "$contact_id" --arg channel "$channel_id" \
      '{contactId:$contact,contactChannelId:$channel}'
  )"

api POST "/api/v1/properties/$property_id/night-audit" \
  200 "close-reporting-night-audit" '{}'
jq -e '.status == "ready"' <<<"$API_BODY" >/dev/null || {
  jq '.issues' <<<"$API_BODY" >&2
  exit 1
}
night_audit_run_id="$(jq -er '.id' <<<"$API_BODY")"
api POST \
  "/api/v1/properties/$property_id/night-audit/$night_audit_run_id/complete" \
  200 "close-reporting-night-audit-complete" '{}'
jq -e '.status == "completed" and .reportGenerationQueued == true' \
  <<<"$API_BODY" >/dev/null
audit_date="$(jq -er '.auditDate' <<<"$API_BODY")"
api GET "/api/v1/properties/$property_id/financial-control/briefs/$audit_date" 200
jq -e '
  .close.status == "certified"
  and .close.cleanClose == true
  and .financialTruth.actualProfitCalculated == false
  and .revenueAssurance.openCases == 0
  and (.actions | length) == 0
' <<<"$API_BODY" >/dev/null
daily_control_snapshot_hash="$(jq -er '.close.snapshotHash' <<<"$API_BODY")"

daily_report_run_id=""
close_report_run_id=""
for _ in {1..60}; do
  api GET "/api/v1/tenants/$tenant_id/report-runs" 200
  daily_report_run_id="$(
    jq -r --arg auditDate "$audit_date" '
      [.[] | select(
        .reportCode == "daily_management_summary"
        and .businessDate == $auditDate
        and .state == "GENERATED"
      )][0].id // ""
    ' <<<"$API_BODY"
  )"
  close_report_run_id="$(
    jq -r --arg auditDate "$audit_date" '
      [.[] | select(
        .reportCode == "night_audit_close"
        and .businessDate == $auditDate
        and .state == "GENERATED"
      )][0].id // ""
    ' <<<"$API_BODY"
  )"
  [[ -n "$daily_report_run_id" && -n "$close_report_run_id" ]] && break
  sleep 2
done
[[ -n "$daily_report_run_id" && -n "$close_report_run_id" ]]

daily_content_hash="$(
  jq -er --arg id "$daily_report_run_id" \
    '.[] | select(.id == $id) | .contentHash' <<<"$API_BODY"
)"

delivery_state=""
delivery_id=""
for _ in {1..60}; do
  api GET \
    "/api/v1/tenants/$tenant_id/report-runs/$daily_report_run_id/deliveries" \
    200
  delivery_id="$(jq -r '.[0].id // ""' <<<"$API_BODY")"
  delivery_state="$(jq -r '.[0].state // ""' <<<"$API_BODY")"
  [[ "$delivery_state" == "SENT" || "$delivery_state" == "DELIVERED" ]] && break
  sleep 2
done
[[ "$delivery_state" == "SENT" || "$delivery_state" == "DELIVERED" ]]

api POST \
  "/api/v1/tenants/$tenant_id/report-runs/$daily_report_run_id/download-link" \
  200
signed_url="$(jq -er '.url' <<<"$API_BODY")"
podman run --rm \
  --network "${PROJECT}_default" \
  docker.io/curlimages/curl:8.16.0 \
  -fsSL "$signed_url" >"$PDF_FILE"
head -c 4 "$PDF_FILE" | grep -q '%PDF'
downloaded_hash="$(sha256sum "$PDF_FILE" | awk '{print $1}')"
[[ "$downloaded_hash" == "$daily_content_hash" ]]

collection="$ROOT_DIR/ops/testing/Peak-Close-Reporting.postman_collection.json"
podman run --rm \
  --network host \
  -v "$collection:/etc/newman/close-reporting.json:ro,Z" \
  -v "$EVIDENCE_DIR:/etc/newman/evidence:Z" \
  docker.io/postman/newman:alpine@sha256:02dc4a285dc05aa3a3f4035e5425a83f3b4cdb21afb71c79df589cbac0a0e04f \
  run /etc/newman/close-reporting.json \
  --reporters cli,json \
  --reporter-json-export /etc/newman/evidence/newman-private.json \
  --env-var "baseUrl=http://127.0.0.1:8080" \
  --env-var "accessToken=$access_token" \
  --env-var "tenantId=$tenant_id" \
  --env-var "propertyId=$property_id" \
  --env-var "nightAuditRunId=$night_audit_run_id" \
  --env-var "dailyReportRunId=$daily_report_run_id"

jq -n \
  --arg generatedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --arg tenantId "$tenant_id" \
  --arg propertyId "$property_id" \
  --arg nightAuditRunId "$night_audit_run_id" \
  --arg dailyReportRunId "$daily_report_run_id" \
  --arg closeReportRunId "$close_report_run_id" \
  --arg deliveryId "$delivery_id" \
  --arg contentHash "$daily_content_hash" \
  --arg dailyControlSnapshotHash "$daily_control_snapshot_hash" \
  --argjson requests "$(jq '.run.stats.requests' "$NEWMAN_REPORT")" \
  --argjson assertions "$(jq '.run.stats.assertions' "$NEWMAN_REPORT")" \
  '{
    journey: "close-reporting",
    result: "passed",
    generatedAt: $generatedAt,
    tenantId: $tenantId,
    propertyId: $propertyId,
    nightAuditRunId: $nightAuditRunId,
    dailyReportRunId: $dailyReportRunId,
    closeReportRunId: $closeReportRunId,
    deliveryId: $deliveryId,
    contentHash: $contentHash,
    dailyControlSnapshotHash: $dailyControlSnapshotHash,
    dailyFinancialTruthCertified: true,
    actualProfitClaimed: false,
    privateObjectRetrieved: true,
    pdfMagicValidated: true,
    contentHashValidated: true,
    signedUrlPersisted: false,
    requests: $requests,
    assertions: $assertions
  }' | tee "$EVIDENCE_FILE"

rm -f "$NEWMAN_REPORT"
