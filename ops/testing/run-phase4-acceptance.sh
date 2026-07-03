#!/usr/bin/env bash
set -euo pipefail
trap 'echo "Phase 4 acceptance failed at line $LINENO." >&2' ERR

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
PROJECT="${COMPOSE_PROJECT_NAME:-peak-phase4-acceptance}"
BASE_URL="${PEAK_BASE_URL:-http://localhost:8080}"
KEYCLOAK_URL="${KEYCLOAK_BASE_URL:-http://localhost:8081}"
EVIDENCE_DIR="${PHASE4_EVIDENCE_DIR:-$ROOT_DIR/build/evidence/phase4}"
PHASE3_EVIDENCE="$EVIDENCE_DIR/phase3-acceptance.json"
PHASE2_EVIDENCE="$EVIDENCE_DIR/phase2-foundation.json"
NEWMAN_REPORT="$EVIDENCE_DIR/newman-private.json"
EVIDENCE_FILE="$EVIDENCE_DIR/phase4-acceptance.json"

for tool in curl date jq openssl podman python3; do
  command -v "$tool" >/dev/null || {
    echo "Missing required tool: $tool" >&2
    exit 1
  }
done

mkdir -p "$EVIDENCE_DIR"
export PHASE3_TENANT_PASSWORD="${PHASE4_TENANT_PASSWORD:-P4-$(openssl rand -hex 18)}"
export PHASE3_EVIDENCE_DIR="$EVIDENCE_DIR"
export COMPOSE_PROJECT_NAME="$PROJECT"

if [[ "${PHASE4_REUSE_FOUNDATION:-false}" != "true" ]]; then
  "$ROOT_DIR/ops/testing/run-phase3-acceptance.sh"
fi

[[ -f "$PHASE3_EVIDENCE" ]] || {
  echo "Missing Phase 3 foundation evidence: $PHASE3_EVIDENCE" >&2
  exit 1
}
[[ -f "$PHASE2_EVIDENCE" ]] || {
  echo "Missing Phase 2 foundation evidence: $PHASE2_EVIDENCE" >&2
  exit 1
}

tenant_id="$(jq -er '.tenantId' "$PHASE2_EVIDENCE")"
property_id="$(jq -er '.propertyId' "$PHASE3_EVIDENCE")"
outlet_id="$(jq -er '.outletId' "$PHASE3_EVIDENCE")"
menu_item_id="$(jq -er '.menuItemId' "$PHASE3_EVIDENCE")"
access_token="$(
  curl -fsS \
    -X POST "$KEYCLOAK_URL/realms/peak/protocol/openid-connect/token" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    --data-urlencode "grant_type=password" \
    --data-urlencode "client_id=peak-acceptance" \
    --data-urlencode "username=phase2-tenant-admin" \
    --data-urlencode "password=$PHASE3_TENANT_PASSWORD" |
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
  key="phase4-$scope-$module"
  curl -fsS \
    -X POST "$BASE_URL$path" \
    -H "Authorization: Bearer $access_token" \
    -H "X-Correlation-Id: phase4-module-activation" \
    -H "Idempotency-Key: $key" \
    -H "Content-Type: application/json" \
    --data "$(jq -nc --arg module "$module" '{moduleId:$module}')" >/dev/null
}

for module in housekeeping maintenance inventory procurement pos; do
  api tenant "$module"
  api property "$module"
done

python3 "$ROOT_DIR/ops/testing/websocket-acceptance.py" \
  --url "${PEAK_WS_URL:-ws://localhost:8080/ws}" \
  --token "$access_token" \
  --origin "${PEAK_WS_ORIGIN:-http://localhost:8080}" \
  --correlation-id "phase4-kds-subscription" \
  --tenant-id "$tenant_id" \
  --property-id "$property_id" >"$EVIDENCE_DIR/websocket-kds.txt"

collection="$ROOT_DIR/ops/testing/Peak-Phase-4.postman_collection.json"
run_id="$(date -u +%Y%m%dT%H%M%SZ)"
podman run --rm \
  --network host \
  -v "$collection:/etc/newman/phase4.json:ro,Z" \
  -v "$EVIDENCE_DIR:/etc/newman/evidence:Z" \
  docker.io/postman/newman:alpine \
  run /etc/newman/phase4.json \
  --delay-request 250 \
  --reporters cli,json \
  --reporter-json-export /etc/newman/evidence/newman-private.json \
  --env-var "baseUrl=http://127.0.0.1:8080" \
  --env-var "accessToken=$access_token" \
  --env-var "runId=$run_id" \
  --env-var "tenantId=$tenant_id" \
  --env-var "propertyId=$property_id" \
  --env-var "outletId=$outlet_id" \
  --env-var "menuItemId=$menu_item_id"

jq -n \
  --arg generatedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --arg tenantId "$tenant_id" \
  --arg propertyId "$property_id" \
  --arg websocketEvidence "$(tr -d '\n' <"$EVIDENCE_DIR/websocket-kds.txt")" \
  --argjson requests "$(jq '.run.stats.requests' "$NEWMAN_REPORT")" \
  --argjson assertions "$(jq '.run.stats.assertions' "$NEWMAN_REPORT")" \
  '{
    phase: 4,
    result: "passed",
    generatedAt: $generatedAt,
    tenantId: $tenantId,
    propertyId: $propertyId,
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
