#!/usr/bin/env bash
set -euo pipefail
trap 'echo "Tenant and property foundation failed at line $LINENO." >&2' ERR

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/ops/production/.env}"
COMPOSE_FILE="${COMPOSE_FILE:-$ROOT_DIR/ops/production/compose.yaml}"
OVERLAY_FILE="${OVERLAY_FILE:-$ROOT_DIR/ops/testing/compose.tenant-property-foundation.yaml}"
PROJECT="${COMPOSE_PROJECT_NAME:-peak-tenant-property-foundation}"
BASE_URL="${PEAK_BASE_URL:-http://localhost:8080}"
PLATFORM_BASE_URL="${PEAK_PLATFORM_BASE_URL:-http://localhost:8082}"
KEYCLOAK_URL="${KEYCLOAK_BASE_URL:-http://localhost:8081}"
WS_URL="${PEAK_WS_URL:-ws://localhost:8080/ws-connect}"
ORIGIN="${PEAK_ACCEPTANCE_ORIGIN:-https://localhost:5173}"
RESET="${TENANT_PROPERTY_RESET:-false}"
BUILD_IMAGE="${TENANT_PROPERTY_BUILD_IMAGE:-false}"
EVIDENCE_FILE="${TENANT_PROPERTY_EVIDENCE_FILE:-$ROOT_DIR/build/tenant-property-foundation.json}"

for tool in curl jq podman python3 timeout; do
  command -v "$tool" >/dev/null || {
    echo "Missing required tool: $tool" >&2
    exit 1
  }
done

set -a
. "$ENV_FILE"
set +a

PLATFORM_REALM="${KEYCLOAK_PLATFORM_REALM:-peak-platform}"
HOSPITALITY_REALM="${KEYCLOAK_HOSPITALITY_REALM:-peak-hospitality}"

compose=(
  podman compose
  -p "$PROJECT"
  --env-file "$ENV_FILE"
  -f "$COMPOSE_FILE"
  -f "$OVERLAY_FILE"
)

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

# Readiness the harness establishes for itself, rather than asking the container
# runtime to establish it.
#
# The compose file gates keycloak behind `depends_on: keycloak-db:
# condition: service_healthy`, which is correct for a deployment. It relies on the
# runtime executing healthchecks, and podman schedules those through systemd
# timers. On the CI runner they never ran: postgres sat at "(starting)" for nine
# minutes while its own log said "ready to accept connections", and a check that
# had run and failed would have gone unhealthy inside two minutes. So compose
# waited on a condition nothing was ever going to satisfy.
#
# Polling pg_isready directly is the same question asked without an intermediary,
# and it behaves identically whether or not the runtime runs healthchecks.
wait_pg() {
  local service="$1"
  local user="$2"
  local database="$3"
  local attempts="${4:-90}"
  local count=1
  until "${compose[@]}" exec -T "$service" pg_isready -U "$user" -d "$database" >/dev/null 2>&1; do
    if (( count >= attempts )); then
      echo "Timed out waiting for $service to accept connections" >&2
      "${compose[@]}" ps >&2 || true
      "${compose[@]}" logs --tail 50 "$service" >&2 || true
      exit 1
    fi
    count=$((count + 1))
    sleep 2
  done
}

API_BODY=""
api() {
  local method="$1"
  local path="$2"
  local token="$3"
  local expected="$4"
  local idempotency_key="${5:-}"
  local payload="${6:-}"
  local service_base_url="$BASE_URL"
  local output
  local status
  if [[ "$path" == /api/v1/platform/* ]]; then
    service_base_url="$PLATFORM_BASE_URL"
  fi
  local args=(
    -sS
    -X "$method"
    "$service_base_url$path"
    -H "Authorization: Bearer $token"
    -H "X-Correlation-Id: tenant-property-${idempotency_key:-read}"
  )
  if [[ -n "$idempotency_key" ]]; then
    args+=(-H "Idempotency-Key: $idempotency_key")
  fi
  if [[ -n "$payload" ]]; then
    args+=(-H "Content-Type: application/json" --data "$payload")
  fi
  output="$(curl "${args[@]}" -w $'\n%{http_code}')"
  status="${output##*$'\n'}"
  API_BODY="${output%$'\n'*}"
  if [[ "$status" != "$expected" ]]; then
    echo "$method $path expected HTTP $expected, received $status" >&2
    echo "$API_BODY" >&2
    exit 1
  fi
}

kc_admin() {
  local realm="$1"
  local method="$2"
  local path="$3"
  local payload="${4:-}"
  local args=(
    -fsS
    -X "$method"
    "$KEYCLOAK_URL/admin/realms/$realm$path"
    -H "Authorization: Bearer $ADMIN_TOKEN"
  )
  if [[ -n "$payload" ]]; then
    args+=(-H "Content-Type: application/json" --data "$payload")
  fi
  curl "${args[@]}"
}

ensure_keycloak_user() {
  local realm="$1"
  local username="$2"
  local email="$3"
  local password="$4"
  local user_id
  local user_payload
  user_id="$(
    kc_admin "$realm" GET "/users?username=$username&exact=true" |
      jq -r '.[0].id // empty'
  )"
  if [[ -z "$user_id" ]]; then
    kc_admin "$realm" POST "/users" "$(
      jq -nc \
        --arg username "$username" \
        --arg email "$email" \
        '{
          username: $username,
          email: $email,
          enabled: true,
          emailVerified: true,
          firstName: "Foundation",
          lastName: "Acceptance"
        }'
    )" >/dev/null
    user_id="$(
      kc_admin "$realm" GET "/users?username=$username&exact=true" |
        jq -r '.[0].id'
    )"
  fi
  kc_admin "$realm" PUT "/users/$user_id/reset-password" "$(
    jq -nc --arg value "$password" \
      '{type: "password", value: $value, temporary: false}'
  )" >/dev/null
  if [[ "$realm" == "$PLATFORM_REALM" ]]; then
    user_payload="$(kc_admin "$realm" GET "/users/$user_id" | jq '.requiredActions = []')"
    kc_admin "$realm" PUT "/users/$user_id" "$user_payload" >/dev/null
  fi
  printf '%s' "$user_id"
}

token_for() {
  local realm="$1"
  local username="$2"
  local password="$3"
  curl -fsS \
    -X POST "$KEYCLOAK_URL/realms/$realm/protocol/openid-connect/token" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    --data-urlencode "grant_type=password" \
    --data-urlencode "client_id=peak-acceptance" \
    --data-urlencode "username=$username" \
    --data-urlencode "password=$password" |
    jq -r '.access_token'
}

ensure_acceptance_client() {
  local realm="$1"
  local client_id
  client_id="$(
    kc_admin "$realm" GET "/clients?clientId=peak-acceptance" |
      jq -r '.[0].id // empty'
  )"
  if [[ -z "$client_id" ]]; then
    kc_admin "$realm" POST "/clients" '{
      "clientId": "peak-acceptance",
      "enabled": true,
      "publicClient": true,
      "standardFlowEnabled": false,
      "implicitFlowEnabled": false,
      "directAccessGrantsEnabled": true,
      "serviceAccountsEnabled": false,
      "protocol": "openid-connect",
      "protocolMappers": [{
        "name": "peak-api-audience",
        "protocol": "openid-connect",
        "protocolMapper": "oidc-audience-mapper",
        "consentRequired": false,
        "config": {
          "included.custom.audience": "peak-api",
          "access.token.claim": "true",
          "id.token.claim": "false",
          "userinfo.token.claim": "false"
        }
      }]
    }' >/dev/null
  fi
}

random_password() {
  python3 -c 'import secrets; print("P2-" + secrets.token_urlsafe(24))'
}

if [[ "$BUILD_IMAGE" == "true" ]]; then
  "$ROOT_DIR/gradlew" bootJar
  podman build -t "$PEAK_IMAGE" "$ROOT_DIR"
fi

if [[ "$RESET" == "true" ]]; then
  "${compose[@]}" down -v --remove-orphans || true
fi

"$ROOT_DIR/ops/scripts/validate-production-env.sh" "$ENV_FILE"

# keycloak declares `depends_on: keycloak-db: condition: service_healthy`, and
# podman-compose waits on that with no deadline of its own. When the database never
# reports healthy this command blocks forever rather than failing: on the first CI run
# that reached it, it printed the postgres and keycloak-db container ids and then sat
# silent for 65 minutes until the whole job was cancelled. A cancelled job is worse than
# a failed one — it reports no error, uploads no evidence, and reads as an infrastructure
# blip rather than a defect.
#
# The bound is generous because the images may still be pulling. The logs are what turns
# the next occurrence into a diagnosis instead of a repeat of the same silence; the wait
# below already reports its own timeout, so this only covers the compose call itself.
#
# Started with --no-deps and gated by wait_pg rather than by the compose condition, for
# the reason wait_pg documents: the health condition is right for a deployment but is
# only as reliable as the runtime's healthcheck scheduling, and on CI that scheduling
# does not happen. Ordering is preserved — the database is accepting connections before
# keycloak is asked to start — it is simply this script asserting it.
if ! timeout 600 "${compose[@]}" up -d --no-deps postgres keycloak-db; then
  echo "Bringing up postgres and keycloak-db failed or timed out." >&2
  "${compose[@]}" ps >&2 || true
  "${compose[@]}" logs --tail 100 postgres keycloak-db >&2 || true
  exit 1
fi
wait_pg postgres "$POSTGRES_MIGRATOR_USER" "$POSTGRES_DB"
wait_pg keycloak-db "$KEYCLOAK_DB_USER" "$KEYCLOAK_DB"

if ! timeout 600 "${compose[@]}" up -d --no-deps keycloak; then
  echo "Bringing up keycloak failed or timed out." >&2
  "${compose[@]}" ps >&2 || true
  "${compose[@]}" logs --tail 100 keycloak >&2 || true
  exit 1
fi
wait_http "$KEYCLOAK_URL/realms/$PLATFORM_REALM/.well-known/openid-configuration"
wait_http "$KEYCLOAK_URL/realms/$HOSPITALITY_REALM/.well-known/openid-configuration"

"${compose[@]}" --profile migration run --rm --no-deps peak-migration
COMPOSE_PROJECT_NAME="$PROJECT" \
  COMPOSE_FILE="$COMPOSE_FILE" \
  ENV_FILE="$ENV_FILE" \
  "$ROOT_DIR/ops/scripts/bootstrap-db-roles.sh"

ADMIN_TOKEN="$(
  curl -fsS \
    -X POST "$KEYCLOAK_URL/realms/master/protocol/openid-connect/token" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    --data-urlencode "grant_type=password" \
    --data-urlencode "client_id=admin-cli" \
    --data-urlencode "username=$KEYCLOAK_ADMIN" \
    --data-urlencode "password=$KEYCLOAK_ADMIN_PASSWORD" |
    jq -r '.access_token'
)"

# Both URLs are pinned to the harness Keycloak, which serves the public and
# administrative planes on one port. The environment file supplies a real
# administrative hostname for production, so overriding only the public URL
# would send the verifier's master token request to that hostname instead of to
# the container under test.
KEYCLOAK_BASE_URL="$KEYCLOAK_URL" \
KEYCLOAK_ADMIN_BASE_URL="$KEYCLOAK_URL" \
  "$ROOT_DIR/ops/scripts/verify-keycloak-realms.sh"

ensure_acceptance_client "$PLATFORM_REALM"
ensure_acceptance_client "$HOSPITALITY_REALM"

root_password="${TENANT_PROPERTY_ROOT_PASSWORD:-$(random_password)}"
tenant_password="${TENANT_PROPERTY_TENANT_PASSWORD:-$(random_password)}"
other_password="${TENANT_PROPERTY_OTHER_PASSWORD:-$(random_password)}"
root_subject="$(ensure_keycloak_user "$PLATFORM_REALM" "acceptance-platform-root" "acceptance.root@example.com" "$root_password")"
# Production bootstrap provisions two distinct emergency administrator
# custodians in one transaction, so dual control holds from the first minute.
# The acceptance stack is a production-shaped first install and performs the
# same ceremony rather than a development single-custodian shortcut.
second_root_password="${TENANT_PROPERTY_SECOND_ROOT_PASSWORD:-$(random_password)}"
second_root_subject="$(ensure_keycloak_user "$PLATFORM_REALM" "acceptance-platform-root-two" "acceptance.root.two@example.com" "$second_root_password")"
tenant_subject="$(ensure_keycloak_user "$HOSPITALITY_REALM" "acceptance-tenant-admin" "acceptance.tenant@example.com" "$tenant_password")"
other_subject="$(ensure_keycloak_user "$HOSPITALITY_REALM" "acceptance-other-admin" "acceptance.other@example.com" "$other_password")"

"${compose[@]}" --profile bootstrap run --rm --no-deps \
  -e PEAK_PLATFORM_BOOTSTRAP_ENABLED=true \
  -e PEAK_PLATFORM_BOOTSTRAP_FULL_NAME="Foundation Platform Root" \
  -e PEAK_PLATFORM_BOOTSTRAP_EMAIL="acceptance.root@example.com" \
  -e PEAK_PLATFORM_BOOTSTRAP_ISSUER="$PEAK_PLATFORM_JWT_ISSUER_URI" \
  -e PEAK_PLATFORM_BOOTSTRAP_SUBJECT="$root_subject" \
  -e PEAK_PLATFORM_BOOTSTRAP_SECOND_FULL_NAME="Foundation Platform Root Two" \
  -e PEAK_PLATFORM_BOOTSTRAP_SECOND_EMAIL="acceptance.root.two@example.com" \
  -e PEAK_PLATFORM_BOOTSTRAP_SECOND_ISSUER="$PEAK_PLATFORM_JWT_ISSUER_URI" \
  -e PEAK_PLATFORM_BOOTSTRAP_SECOND_SUBJECT="$second_root_subject" \
  peak-bootstrap

if ! curl -fsS http://localhost:8090/health >/dev/null 2>&1; then
  python3 "$ROOT_DIR/ops/testing/mock-communication-provider.py" \
    --api-key "$PEAK_COMMUNICATION_DELIVERY_HTTP_PROVIDER_API_KEY" &
  mock_pid="$!"
  wait_http "http://localhost:8090/health" 30
fi

if "${compose[@]}" config --services | grep -Fxq minio-init; then
  "${compose[@]}" up -d minio
  wait_http "http://localhost:9000/minio/health/live"
  "${compose[@]}" run --rm --no-deps minio-init
fi

# Dependencies are already ready; do not re-traverse removed one-shot containers.
"${compose[@]}" up -d --no-deps peak-api peak-platform peak-worker
wait_http "$BASE_URL/actuator/health"
wait_http "$PLATFORM_BASE_URL/actuator/health/readiness"

platform_token="$(token_for "$PLATFORM_REALM" "acceptance-platform-root" "$root_password")"
tenant_token_unlinked="$(token_for "$HOSPITALITY_REALM" "acceptance-tenant-admin" "$tenant_password")"
other_token_unlinked="$(token_for "$HOSPITALITY_REALM" "acceptance-other-admin" "$other_password")"

api GET "/api/v1/platform/permissions" "$platform_token" 200
jq -e 'map(.code) | index("platform.security.manage") != null' <<<"$API_BODY" >/dev/null

tenant_payload="$(
  jq -nc '{
    name: "Foundation Acceptance Hotel Group",
    slug: "tenant-property-acceptance-group",
    planId: "20202020-0000-0000-0000-000000000001",
    legalName: "Foundation Acceptance Hotel Group Limited",
    tradingName: "Foundation Acceptance",
    entityType: "limited_company",
    businessRegistrationNumber: "P2-ACCEPTANCE-001",
    businessEmail: "business.acceptance@example.com",
    businessPhone: "+255712345678",
    registeredAddress: {
      line1: "Foundation Road",
      city: "Dar es Salaam",
      countryCode: "TZ"
    },
    countryCode: "TZ",
    currencyCode: "TZS"
  }'
)"
api POST "/api/v1/platform/tenants" "$platform_token" 201 \
  "tenant-create" "$tenant_payload"
tenant_id="$(jq -r '.id' <<<"$API_BODY")"
api POST "/api/v1/platform/tenants" "$platform_token" 201 \
  "tenant-create" "$tenant_payload"
[[ "$(jq -r '.id' <<<"$API_BODY")" == "$tenant_id" ]]

api POST "/api/v1/platform/tenants/$tenant_id/administrators" "$platform_token" 201 \
  "tenant-admin-provision" "$(
    jq -nc \
      --arg issuer "$PEAK_SECURITY_JWT_ISSUER_URI" \
      --arg subject "$tenant_subject" \
      '{
        fullName: "Foundation Tenant Administrator",
        email: "acceptance.tenant@example.com",
        issuer: $issuer,
        subject: $subject
      }'
  )"
tenant_user_id="$(jq -r '.tenantUserId' <<<"$API_BODY")"
tenant_token="$(token_for "$HOSPITALITY_REALM" "acceptance-tenant-admin" "$tenant_password")"

api POST "/api/v1/platform/tenants/$tenant_id/profile/verify" "$platform_token" 200 \
  "tenant-profile-verify"

for module in property communications realtime; do
  api POST "/api/v1/tenants/$tenant_id/modules" "$tenant_token" 200 \
    "tenant-module-$module" "$(jq -nc --arg module "$module" '{moduleId: $module}')"
done

api POST "/api/v1/properties" "$tenant_token" 200 "property-create" '{
  "name": "Foundation Acceptance Hotel",
  "location": "Dar es Salaam",
  "code": "P2A001",
  "type": "HOTEL"
}'
property_id="$(jq -r '.propertyId' <<<"$API_BODY")"

for module in property realtime; do
  api POST "/api/v1/properties/$property_id/modules" "$tenant_token" 200 \
    "property-module-$module" "$(jq -nc --arg module "$module" '{moduleId: $module}')"
done

api POST "/api/v1/properties/$property_id/buildings" "$tenant_token" 200 \
  "building-create" '{"name":"Main Building","description":"Acceptance building"}'
building_id="$(jq -r '.resourceId' <<<"$API_BODY")"
api POST "/api/v1/properties/$property_id/floors" "$tenant_token" 200 \
  "floor-create" "$(
    jq -nc --arg id "$building_id" \
      '{buildingId:$id,floorNumber:1,name:"Ground Floor",capacity:20}'
  )"
api POST "/api/v1/properties/$property_id/room-types" "$tenant_token" 200 \
  "room-type-create" '{
    "name":"Deluxe King",
    "code":"DLX",
    "basePrice":0,
    "maxAdults":2,
    "maxChildren":1,
    "maxOccupancy":3
  }'
room_type_id="$(jq -r '.resourceId' <<<"$API_BODY")"
api POST "/api/v1/properties/$property_id/rooms" "$tenant_token" 200 \
  "room-create" "$(
    jq -nc --arg building "$building_id" --arg roomType "$room_type_id" \
      '{
        buildingId:$building,
        roomNumber:"101",
        roomTypeId:$roomType,
        floorNumber:1
      }'
  )"
room_id="$(jq -r '.resourceId' <<<"$API_BODY")"
api POST "/api/v1/properties/$property_id/revenue-centers" "$tenant_token" 200 \
  "revenue-center-create" '{
    "name":"Rooms Revenue",
    "code":"ROOMS",
    "centerType":"rooms",
    "isRoomsRevenue":true,
    "displayOrder":10
  }'
api POST "/api/v1/properties/taxes" "$tenant_token" 200 \
  "tax-create" '{
  "name":"Value Added Tax",
  "code":"VAT18",
  "rate":0.18,
    "taxType":"VAT",
    "isCompound":false,
    "isInclusive":true
  }'
api POST "/api/v1/properties/$property_id/rates" "$tenant_token" 200 \
  "base-rate-create" "$(
    jq -nc --arg roomType "$room_type_id" \
      '{roomTypeId:$roomType,amount:175000,currency:"TZS"}'
  )"

contact_email="director.acceptance@example.com"
api POST "/api/v1/communication/contacts" "$tenant_token" 200 \
  "contact-create" "$(
    jq -nc --arg email "$contact_email" \
      '{fullName:"Foundation Managing Director",jobTitle:"Managing Director",email:$email}'
  )"
contact_id="$(jq -r '.contactId' <<<"$API_BODY")"
channel_id="$(jq -r '.channelIds[0]' <<<"$API_BODY")"
api POST "/api/v1/communication/channels/$channel_id/request-verification" \
  "$tenant_token" 202 "channel-verification-request"

verification_token=""
for _ in {1..30}; do
  provider_message="$(
    curl -sS -G \
      --data-urlencode "recipient=$contact_email" \
      http://localhost:8090/v1/messages/latest || true
  )"
  verification_token="$(
    jq -r '.content // ""' <<<"$provider_message" |
      sed -n 's/.*token is: //p'
  )"
  [[ -n "$verification_token" ]] && break
  sleep 1
done
[[ -n "$verification_token" ]] || {
  echo "Verification notification was not delivered by the worker." >&2
  exit 1
}

api POST "/api/v1/communication/channels/$channel_id/verify" "$tenant_token" 200 \
  "channel-verify" "$(jq -nc --arg token "$verification_token" '{token:$token}')"
api POST "/api/v1/communication/contacts/$contact_id/roles" "$tenant_token" 200 \
  "contact-role" '{"roleCode":"owner_managing_director","primary":true}'
api POST "/api/v1/communication/contacts/$contact_id/channels/$channel_id/consents" \
  "$tenant_token" 200 "contact-consent" '{
    "purpose":"operational_reports",
    "policyVersion":"acceptance-v1",
    "status":"active"
  }'
api POST "/api/v1/communication/report-recipients" "$tenant_token" 200 \
  "report-recipient" "$(
    jq -nc --arg contact "$contact_id" --arg channel "$channel_id" \
      '{
        contactId:$contact,
        channelId:$channel,
        reportCode:"monthly_executive_summary",
        subscriptionName:"Executive Management Pack",
        frequency:"monthly",
        timezone:"Africa/Dar_es_Salaam",
        deliveryFormat:"pdf"
      }'
  )"

api GET "/api/v1/properties/$property_id/readiness" "$tenant_token" 200
jq -e '.isReady == true' <<<"$API_BODY" >/dev/null
api POST "/api/v1/properties/$property_id/activate" "$tenant_token" 200 \
  "property-activate"
jq -e '.isReady == true' <<<"$API_BODY" >/dev/null
api GET "/api/v1/tenants/$tenant_id/readiness" "$tenant_token" 200
jq -e '.isReady == true and (.missingRequirements | length == 0)' <<<"$API_BODY" >/dev/null

api POST "/api/v1/communication/notifications" "$tenant_token" 200 \
  "notification-send" "$(
    jq -nc --arg property "$property_id" --arg channel "$channel_id" \
      '{
        propertyId:$property,
        contactChannelId:$channel,
        purpose:"operational_reports",
        subject:"Foundation acceptance",
        content:"Foundation communication delivery accepted."
      }'
  )"
delivery_request_id="$(jq -r '.deliveryRequestId' <<<"$API_BODY")"
delivery_status=""
for _ in {1..30}; do
  api GET "/api/v1/communication/delivery-requests/$delivery_request_id" \
    "$tenant_token" 200
  delivery_status="$(jq -r '.status' <<<"$API_BODY")"
  [[ "$delivery_status" == "delivered" ]] && break
  sleep 1
done
[[ "$delivery_status" == "delivered" ]]

python3 "$ROOT_DIR/ops/testing/websocket-acceptance.py" \
  --url "$WS_URL" \
  --token "$tenant_token" \
  --origin "$ORIGIN" \
  --correlation-id "tenant-property-websocket-authorized" \
  --tenant-id "$tenant_id" \
  --property-id "$property_id" >/dev/null

sse_output="$(mktemp)"
timeout 8 curl -sS -N \
  -H "Authorization: Bearer $tenant_token" \
  "$BASE_URL/api/v1/realtime/tenants/$tenant_id/properties/$property_id/stream" \
  >"$sse_output" &
sse_pid="$!"
sleep 1
api PUT "/api/v1/properties/$property_id/rooms/$room_id/status" "$tenant_token" 200 \
  "room-status-maintenance" '{"status":"maintenance"}'
wait "$sse_pid" || true
grep -q "$room_id" "$sse_output"
rm -f "$sse_output"

other_payload="$(
  jq -nc '{
    name:"Foundation Other Tenant",
    slug:"acceptance-other-tenant",
    planId:"20202020-0000-0000-0000-000000000001",
    legalName:"Foundation Other Tenant Limited",
    entityType:"limited_company",
    businessEmail:"business.other.acceptance@example.com",
    businessPhone:"+255713456789",
    countryCode:"TZ",
    currencyCode:"TZS"
  }'
)"
api POST "/api/v1/platform/tenants" "$platform_token" 201 \
  "other-tenant-create" "$other_payload"
other_tenant_id="$(jq -r '.id' <<<"$API_BODY")"
api POST "/api/v1/platform/tenants/$other_tenant_id/administrators" \
  "$platform_token" 201 "other-admin-provision" "$(
    jq -nc \
      --arg issuer "$PEAK_SECURITY_JWT_ISSUER_URI" \
      --arg subject "$other_subject" \
      '{
        fullName:"Other Tenant Administrator",
        email:"acceptance.other@example.com",
        issuer:$issuer,
        subject:$subject
      }'
  )"
other_token="$(token_for "$HOSPITALITY_REALM" "acceptance-other-admin" "$other_password")"

api GET "/api/v1/properties/$property_id" "$other_token" 403
api GET "/api/v1/platform/permissions" "$tenant_token" 401
api GET "/api/v1/properties/$property_id" "$platform_token" 401
python3 "$ROOT_DIR/ops/testing/websocket-acceptance.py" \
  --url "$WS_URL" \
  --token "$other_token" \
  --origin "$ORIGIN" \
  --correlation-id "tenant-property-websocket-cross-tenant-denied" \
  --tenant-id "$tenant_id" \
  --property-id "$property_id" \
  --expect-denied >/dev/null

realtime_denial_audit_count="$(
  "${compose[@]}" exec -T postgres \
    psql \
      -U "$POSTGRES_MIGRATOR_USER" \
      -d "$POSTGRES_DB" \
      -tAc "
        SELECT count(*)
        FROM audit_logs
        WHERE action = 'realtime.subscription_denied'
          AND correlation_id = 'tenant-property-websocket-cross-tenant-denied'
          AND tenant_id = '$other_tenant_id'::uuid
          AND new_values ->> 'target_tenant_id' = '$tenant_id';
      " |
    tr -d '[:space:]'
)"
[[ "$realtime_denial_audit_count" == "1" ]] || {
  echo "Cross-tenant WebSocket denial was not audited exactly once." >&2
  exit 1
}

mkdir -p "$(dirname "$EVIDENCE_FILE")"
jq -n \
  --arg generatedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --arg tenantId "$tenant_id" \
  --arg tenantUserId "$tenant_user_id" \
  --arg propertyId "$property_id" \
  --arg roomId "$room_id" \
  --arg contactId "$contact_id" \
  --arg channelId "$channel_id" \
  --arg deliveryRequestId "$delivery_request_id" \
  '{
    journey: "tenant-property-foundation",
    result: "passed",
    generatedAt: $generatedAt,
    noManualApplicationSql: true,
    keycloakJwtVerified: true,
    tenantReadiness: true,
    propertyReadiness: true,
    communicationDelivered: true,
    sseDelivered: true,
    websocketAuthorized: true,
    crossTenantDenied: true,
    realtimeDenialAudited: true,
    tenantId: $tenantId,
    tenantUserId: $tenantUserId,
    propertyId: $propertyId,
    roomId: $roomId,
    contactId: $contactId,
    channelId: $channelId,
    deliveryRequestId: $deliveryRequestId
  }' | tee "$EVIDENCE_FILE"
