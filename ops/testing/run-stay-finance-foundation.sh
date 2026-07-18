#!/usr/bin/env bash
set -euo pipefail
trap 'echo "Stay and finance foundation acceptance failed at line $LINENO." >&2' ERR

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/ops/production/.env}"
PROJECT="${COMPOSE_PROJECT_NAME:-peak-stay-finance-acceptance}"
BASE_URL="${PEAK_BASE_URL:-http://localhost:8080}"
KEYCLOAK_URL="${KEYCLOAK_BASE_URL:-http://localhost:8081}"
EVIDENCE_DIR="${STAY_FINANCE_EVIDENCE_DIR:-$ROOT_DIR/build/evidence/stay-finance-foundation}"
TENANT_PROPERTY_EVIDENCE="$EVIDENCE_DIR/tenant-property-foundation.json"
NEWMAN_REPORT="$EVIDENCE_DIR/newman-private.json"
EVIDENCE_FILE="$EVIDENCE_DIR/stay-finance-foundation.json"

for tool in curl date jq podman; do
  command -v "$tool" >/dev/null || {
    echo "Missing required tool: $tool" >&2
    exit 1
  }
done

mkdir -p "$EVIDENCE_DIR"
tenant_password="${STAY_FINANCE_TENANT_PASSWORD:-SF-$(openssl rand -hex 18)}"
export TENANT_PROPERTY_TENANT_PASSWORD="$tenant_password"
export TENANT_PROPERTY_ROOT_PASSWORD="${STAY_FINANCE_ROOT_PASSWORD:-SF-$(openssl rand -hex 18)}"
export TENANT_PROPERTY_OTHER_PASSWORD="${STAY_FINANCE_OTHER_PASSWORD:-SF-$(openssl rand -hex 18)}"
export TENANT_PROPERTY_EVIDENCE_FILE="$TENANT_PROPERTY_EVIDENCE"
export COMPOSE_PROJECT_NAME="$PROJECT"
export OVERLAY_FILE="${OVERLAY_FILE:-$ROOT_DIR/ops/testing/compose.core-hospitality-foundation.yaml}"

if [[ "${STAY_FINANCE_REUSE_FOUNDATION:-false}" == "true" ]]; then
  [[ -f "$TENANT_PROPERTY_EVIDENCE" ]] || {
    echo "Cannot reuse tenant/property foundation without $TENANT_PROPERTY_EVIDENCE" >&2
    exit 1
  }
else
  "$ROOT_DIR/ops/testing/run-tenant-property-foundation.sh"
fi

property_id="$(jq -er '.propertyId' "$TENANT_PROPERTY_EVIDENCE")"
room_id="$(jq -er '.roomId' "$TENANT_PROPERTY_EVIDENCE")"
tenant_id="$(jq -er '.tenantId' "$TENANT_PROPERTY_EVIDENCE")"
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
    -H "X-Correlation-Id: stay-finance-acceptance"
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

stay_finance_modules=(
  reservations
  frontdesk
  billing
  payments
  fiscal
  night_audit
  pos
)
for module in "${stay_finance_modules[@]}"; do
  api POST "/api/v1/tenants/$tenant_id/modules" 200 \
    "stay-finance-tenant-module-$module" "$(jq -nc --arg module "$module" '{moduleId: $module}')"
  api POST "/api/v1/properties/$property_id/modules" 200 \
    "stay-finance-property-module-$module" "$(jq -nc --arg module "$module" '{moduleId: $module}')"
done

api GET "/api/v1/properties/$property_id/room-types" 200
room_type_id="$(jq -er '.[0].id // .[0].resourceId' <<<"$API_BODY")"
api GET "/api/v1/properties/$property_id/revenue-centers" 200
revenue_center_id="$(jq -er '.[0].id // .[0].resourceId' <<<"$API_BODY")"
api GET "/api/v1/properties/taxes" 200
tax_rate_id="$(jq -er '.[0].id // .[0].resourceId' <<<"$API_BODY")"

api PUT "/api/v1/properties/$property_id/rooms/$room_id/status" 200 \
  "stay-finance-room-ready" '{"status":"vacant_clean"}'

api POST "/api/v1/properties/$property_id/pos-config/outlets" 201 \
  "stay-finance-outlet" "$(
    jq -nc --arg revenueCenterId "$revenue_center_id" \
      '{
        revenueCenterId: $revenueCenterId,
        name: "Stay Finance Acceptance Restaurant",
        type: "RESTAURANT"
      }'
  )"
outlet_id="$(jq -er '.id' <<<"$API_BODY")"
api POST "/api/v1/properties/$property_id/pos-config/menu-categories" 201 \
  "stay-finance-menu-category" "$(
    jq -nc --arg outletId "$outlet_id" \
      '{outletId: $outletId, name: "Acceptance Meals"}'
  )"
category_id="$(jq -er '.id' <<<"$API_BODY")"
api POST "/api/v1/properties/$property_id/pos-config/menu-items" 201 \
  "stay-finance-menu-item" "$(
    jq -nc \
      --arg categoryId "$category_id" \
      --arg taxRateId "$tax_rate_id" \
      '{
        categoryId: $categoryId,
        taxRateId: $taxRateId,
        name: "Acceptance Breakfast",
        price: 10000
      }'
  )"
menu_item_id="$(jq -er '.id' <<<"$API_BODY")"

check_in_date="$(date +%F)"
check_out_date="$(date -d "$check_in_date + 1 day" +%F)"
collection="$ROOT_DIR/ops/testing/Peak-Stay-Finance-Foundation.postman_collection.json"

podman run --rm \
  --network host \
  -v "$collection:/etc/newman/stay-finance.json:ro,Z" \
  -v "$EVIDENCE_DIR:/etc/newman/evidence:Z" \
  docker.io/postman/newman:alpine@sha256:02dc4a285dc05aa3a3f4035e5425a83f3b4cdb21afb71c79df589cbac0a0e04f \
  run /etc/newman/stay-finance.json \
  --delay-request 500 \
  --reporters cli,json \
  --reporter-json-export /etc/newman/evidence/newman-private.json \
  --env-var "baseUrl=$BASE_URL" \
  --env-var "accessToken=$access_token" \
  --env-var "runId=$(date -u +%Y%m%dT%H%M%SZ)" \
  --env-var "tenantId=$tenant_id" \
  --env-var "propertyId=$property_id" \
  --env-var "roomTypeId=$room_type_id" \
  --env-var "roomId=$room_id" \
  --env-var "revenueCenterId=$revenue_center_id" \
  --env-var "outletId=$outlet_id" \
  --env-var "menuItemId=$menu_item_id" \
  --env-var "checkInDate=$check_in_date" \
  --env-var "checkOutDate=$check_out_date"

jq -n \
  --arg generatedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --arg propertyId "$property_id" \
  --arg roomId "$room_id" \
  --arg outletId "$outlet_id" \
  --arg menuItemId "$menu_item_id" \
  --argjson requests "$(jq '.run.stats.requests' "$NEWMAN_REPORT")" \
  --argjson assertions "$(jq '.run.stats.assertions' "$NEWMAN_REPORT")" \
  '{
    journey: "stay-finance-foundation",
    result: "passed",
    generatedAt: $generatedAt,
    provisionedThroughApis: true,
    keycloakJwtVerified: true,
    separateApiWorkerRoles: true,
    signedFiscalSimulator: true,
    requests: $requests,
    assertions: $assertions,
    propertyId: $propertyId,
    roomId: $roomId,
    outletId: $outletId,
    menuItemId: $menuItemId
  }' | tee "$EVIDENCE_FILE"

rm -f "$NEWMAN_REPORT"
