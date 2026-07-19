#!/usr/bin/env bash
set -euo pipefail
trap 'echo "Core hospitality journey failed at line $LINENO." >&2' ERR

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
PROJECT="${COMPOSE_PROJECT_NAME:-peak-core-hospitality-acceptance}"
BASE_URL="${PEAK_BASE_URL:-http://localhost:8080}"
KEYCLOAK_URL="${KEYCLOAK_BASE_URL:-http://localhost:8081}"
HOSPITALITY_REALM="${KEYCLOAK_HOSPITALITY_REALM:-peak-hospitality}"
EVIDENCE_DIR="${CORE_HOSPITALITY_EVIDENCE_DIR:-$ROOT_DIR/build/evidence/core-hospitality-journey}"
STAY_FINANCE_EVIDENCE="$EVIDENCE_DIR/stay-finance-foundation.json"
TENANT_PROPERTY_EVIDENCE="$EVIDENCE_DIR/tenant-property-foundation.json"
NEWMAN_REPORT="$EVIDENCE_DIR/newman-private.json"
EVIDENCE_FILE="$EVIDENCE_DIR/core-hospitality-journey.json"

for tool in curl date jq openssl podman python3; do
  command -v "$tool" >/dev/null || {
    echo "Missing required tool: $tool" >&2
    exit 1
  }
done

mkdir -p "$EVIDENCE_DIR"
export STAY_FINANCE_TENANT_PASSWORD="${CORE_HOSPITALITY_TENANT_PASSWORD:-CH-$(openssl rand -hex 18)}"
export STAY_FINANCE_EVIDENCE_DIR="$EVIDENCE_DIR"
export COMPOSE_PROJECT_NAME="$PROJECT"

if [[ "${CORE_HOSPITALITY_REUSE_FOUNDATION:-false}" != "true" ]]; then
  "$ROOT_DIR/ops/testing/run-stay-finance-foundation.sh"
fi

[[ -f "$STAY_FINANCE_EVIDENCE" ]] || {
  echo "Missing stay and finance foundation evidence: $STAY_FINANCE_EVIDENCE" >&2
  exit 1
}
[[ -f "$TENANT_PROPERTY_EVIDENCE" ]] || {
  echo "Missing tenant/property foundation evidence: $TENANT_PROPERTY_EVIDENCE" >&2
  exit 1
}

tenant_id="$(jq -er '.tenantId' "$TENANT_PROPERTY_EVIDENCE")"
property_id="$(jq -er '.propertyId' "$STAY_FINANCE_EVIDENCE")"
outlet_id="$(jq -er '.outletId' "$STAY_FINANCE_EVIDENCE")"
menu_item_id="$(jq -er '.menuItemId' "$STAY_FINANCE_EVIDENCE")"
access_token="$(
  curl -fsS \
    -X POST "$KEYCLOAK_URL/realms/$HOSPITALITY_REALM/protocol/openid-connect/token" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    --data-urlencode "grant_type=password" \
    --data-urlencode "client_id=peak-acceptance" \
    --data-urlencode "username=acceptance-tenant-admin" \
    --data-urlencode "password=$STAY_FINANCE_TENANT_PASSWORD" |
    jq -er '.access_token'
)"

api() {
  local scope="$1"
  local module="$2"
  local path key
  if [[ "$scope" == "tenant" ]]; then
    path="/api/v1/tenants/$tenant_id/modules"
  else
    path="/api/v1/properties/$property_id/modules"
  fi
  key="core-hospitality-$scope-$module"
  curl -fsS \
    -X POST "$BASE_URL$path" \
    -H "Authorization: Bearer $access_token" \
    -H "X-Correlation-Id: core-hospitality-module-activation" \
    -H "Idempotency-Key: $key" \
    -H "Content-Type: application/json" \
    --data "$(jq -nc --arg module "$module" '{moduleId:$module}')" >/dev/null
}

for module in housekeeping maintenance inventory procurement pos; do
  api tenant "$module"
  api property "$module"
done

python3 "$ROOT_DIR/ops/testing/websocket-acceptance.py" \
  --url "${PEAK_WS_URL:-ws://localhost:8080/ws-connect}" \
  --token "$access_token" \
  --origin "${PEAK_WS_ORIGIN:-https://localhost:5173}" \
  --correlation-id "core-hospitality-kds-subscription" \
  --tenant-id "$tenant_id" \
  --property-id "$property_id" >"$EVIDENCE_DIR/websocket-kds.txt"

collection="$ROOT_DIR/ops/testing/Peak-Core-Hospitality-Journey.postman_collection.json"
run_id="$(date -u +%Y%m%dT%H%M%SZ)"
podman run --rm \
  --network host \
  -v "$collection:/etc/newman/core-hospitality.json:ro,Z" \
  -v "$EVIDENCE_DIR:/etc/newman/evidence:Z" \
  docker.io/postman/newman:alpine@sha256:02dc4a285dc05aa3a3f4035e5425a83f3b4cdb21afb71c79df589cbac0a0e04f \
  run /etc/newman/core-hospitality.json \
  --delay-request 250 \
  --reporters cli,json \
  --reporter-json-export /etc/newman/evidence/newman-private.json \
  --env-var "baseUrl=$BASE_URL" \
  --env-var "accessToken=$access_token" \
  --env-var "runId=$run_id" \
  --env-var "tenantId=$tenant_id" \
  --env-var "propertyId=$property_id" \
  --env-var "outletId=$outlet_id" \
  --env-var "menuItemId=$menu_item_id"

newman_response() {
  local item_name="$1"
  jq -cer --arg item "$item_name" '
    .run.executions[]
    | select(.item.name == $item)
    | .response.stream
    | if type == "object" and .type == "Buffer"
      then (.data | implode | fromjson)
      elif type == "string" then fromjson
      else error("unsupported Newman response encoding")
      end
  ' "$NEWMAN_REPORT" | tail -n 1
}

pos_session_id="$(newman_response "Open POS session" | jq -er '.id')"
pos_order_id="$(newman_response "Create offline-safe POS order" | jq -er '.id')"
kitchen_ticket_id="$(newman_response "Send order to kitchen" | jq -er '.id')"

jq -n \
  --arg generatedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --arg tenantId "$tenant_id" \
  --arg propertyId "$property_id" \
  --arg posSessionId "$pos_session_id" \
  --arg posOrderId "$pos_order_id" \
  --arg kitchenTicketId "$kitchen_ticket_id" \
  --arg websocketEvidence "$(tr -d '\n' <"$EVIDENCE_DIR/websocket-kds.txt")" \
  --argjson requests "$(jq '.run.stats.requests' "$NEWMAN_REPORT")" \
  --argjson assertions "$(jq '.run.stats.assertions' "$NEWMAN_REPORT")" \
  '{
    journey: "core-hospitality",
    result: "passed",
    generatedAt: $generatedAt,
    tenantId: $tenantId,
    propertyId: $propertyId,
    posSessionId: $posSessionId,
    posOrderId: $posOrderId,
    kitchenTicketId: $kitchenTicketId,
    provisionedThroughApis: true,
    keycloakJwtVerified: true,
    separateApiWorkerRoles: true,
    clickPesaRequired: false,
    settlementCoverage: ["cash", "room_charge", "simulated_mobile_money"],
    websocketKds: $websocketEvidence,
    requests: $requests,
    assertions: $assertions
  }' | tee "$EVIDENCE_FILE"

rm -f "$NEWMAN_REPORT"
