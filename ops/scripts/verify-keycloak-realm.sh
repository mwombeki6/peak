#!/usr/bin/env sh
set -eu

BASE_URL="${KEYCLOAK_BASE_URL:-http://localhost:8081}"
REALM="${KEYCLOAK_REALM:-peak}"
ADMIN_USER="${KEYCLOAK_ADMIN:-admin}"
ADMIN_PASSWORD="${KEYCLOAK_ADMIN_PASSWORD:-}"
EXPECTED_ISSUER="${PEAK_SECURITY_JWT_ISSUER_URI:-$BASE_URL/realms/$REALM}"
EXPECTED_AUDIENCE="${PEAK_SECURITY_JWT_AUDIENCE:-peak-api}"
EXPECTED_APP_ORIGIN="${PEAK_APP_ORIGIN:-}"

if [ -z "$ADMIN_PASSWORD" ]; then
  echo "KEYCLOAK_ADMIN_PASSWORD is required" >&2
  exit 1
fi

case "$EXPECTED_APP_ORIGIN" in
  https://*) ;;
  *)
    echo "PEAK_APP_ORIGIN must be an HTTPS origin" >&2
    exit 1
    ;;
esac

TOKEN_RESPONSE="$(curl -fsS \
  -X POST "$BASE_URL/realms/master/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  --data-urlencode "client_id=admin-cli" \
  --data-urlencode "grant_type=password" \
  --data-urlencode "username=$ADMIN_USER" \
  --data-urlencode "password=$ADMIN_PASSWORD")"

ACCESS_TOKEN="$(TOKEN_RESPONSE="$TOKEN_RESPONSE" python3 - <<'PY'
import json
import os

payload = json.loads(os.environ["TOKEN_RESPONSE"])
token = payload.get("access_token")
if not token:
    raise SystemExit("Keycloak admin token response did not include access_token")
print(token)
PY
)"

OIDC_CONFIGURATION="$(curl -fsS "$BASE_URL/realms/$REALM/.well-known/openid-configuration")"
REALM_RESPONSE="$(curl -fsS \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  "$BASE_URL/admin/realms/$REALM")"
CLIENTS_RESPONSE="$(curl -fsS \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  "$BASE_URL/admin/realms/$REALM/clients?clientId=peak-api")"
WEB_CLIENTS_RESPONSE="$(curl -fsS \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  "$BASE_URL/admin/realms/$REALM/clients?clientId=peak-web")"

WEB_CLIENT_ID="$(WEB_CLIENTS_RESPONSE="$WEB_CLIENTS_RESPONSE" python3 - <<'PY'
import json
import os

clients = json.loads(os.environ["WEB_CLIENTS_RESPONSE"])
if len(clients) != 1:
    raise SystemExit(f"Expected exactly one peak-web client, found {len(clients)}")
client_id = clients[0].get("id")
if not client_id:
    raise SystemExit("peak-web client does not include internal id")
print(client_id)
PY
)"

WEB_MAPPERS_RESPONSE="$(curl -fsS \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  "$BASE_URL/admin/realms/$REALM/clients/$WEB_CLIENT_ID/protocol-mappers/models")"

OIDC_CONFIGURATION="$OIDC_CONFIGURATION" \
REALM_RESPONSE="$REALM_RESPONSE" \
CLIENTS_RESPONSE="$CLIENTS_RESPONSE" \
WEB_CLIENTS_RESPONSE="$WEB_CLIENTS_RESPONSE" \
WEB_MAPPERS_RESPONSE="$WEB_MAPPERS_RESPONSE" \
BASE_URL="$BASE_URL" \
REALM="$REALM" \
EXPECTED_ISSUER="$EXPECTED_ISSUER" \
EXPECTED_AUDIENCE="$EXPECTED_AUDIENCE" \
EXPECTED_APP_ORIGIN="$EXPECTED_APP_ORIGIN" \
python3 - <<'PY'
import json
import os

expected_issuer = os.environ["EXPECTED_ISSUER"].rstrip("/")
expected_audience = os.environ["EXPECTED_AUDIENCE"]
expected_app_origin = os.environ["EXPECTED_APP_ORIGIN"].rstrip("/")

oidc = json.loads(os.environ["OIDC_CONFIGURATION"])
realm = json.loads(os.environ["REALM_RESPONSE"])
issuer = (oidc.get("issuer") or "").rstrip("/")
jwks_uri = oidc.get("jwks_uri")
if issuer != expected_issuer:
    raise SystemExit(f"Unexpected issuer: {issuer!r}; expected {expected_issuer!r}")
if not jwks_uri:
    raise SystemExit("OIDC configuration does not expose jwks_uri")

security_expectations = {
    "bruteForceProtected": True,
    "organizationsEnabled": True,
    "eventsEnabled": True,
    "adminEventsEnabled": True,
    "adminEventsDetailsEnabled": True,
    "revokeRefreshToken": True,
}
for field, expected in security_expectations.items():
    if realm.get(field) is not expected:
        raise SystemExit(f"Keycloak realm must set {field}={expected!r}")
if realm.get("failureFactor", 999) > 5:
    raise SystemExit("Keycloak brute-force failureFactor must be at most 5")
if realm.get("accessTokenLifespan", 999999) > 300:
    raise SystemExit("Keycloak access tokens must expire within five minutes")
if realm.get("otpPolicyAlgorithm") not in {"HmacSHA256", "HmacSHA512"}:
    raise SystemExit("Keycloak OTP must use SHA-256 or SHA-512")
if realm.get("otpPolicyDigits", 0) < 6:
    raise SystemExit("Keycloak OTP must use at least six digits")
password_policy = realm.get("passwordPolicy") or ""
for requirement in ("length(12)", "digits(1)", "upperCase(1)", "lowerCase(1)", "specialChars(1)"):
    if requirement not in password_policy:
        raise SystemExit(f"Keycloak password policy is missing {requirement}")

api_clients = json.loads(os.environ["CLIENTS_RESPONSE"])
if len(api_clients) != 1:
    raise SystemExit(f"Expected exactly one peak-api client, found {len(api_clients)}")
api_client = api_clients[0]
if api_client.get("clientId") != expected_audience:
    raise SystemExit("peak-api client id does not match PEAK_SECURITY_JWT_AUDIENCE")
if api_client.get("bearerOnly") is not True:
    raise SystemExit("peak-api client must be bearer-only")

web_clients = json.loads(os.environ["WEB_CLIENTS_RESPONSE"])
if len(web_clients) != 1:
    raise SystemExit(f"Expected exactly one peak-web client, found {len(web_clients)}")
web_client = web_clients[0]
if web_client.get("publicClient") is not True:
    raise SystemExit("peak-web client must be public")
if not web_client.get("standardFlowEnabled"):
    raise SystemExit("peak-web client must enable authorization code flow")
if web_client.get("directAccessGrantsEnabled"):
    raise SystemExit("peak-web client must not enable direct access grants")
if web_client.get("implicitFlowEnabled"):
    raise SystemExit("peak-web client must not enable implicit flow")
if web_client.get("redirectUris") != [f"{expected_app_origin}/*"]:
    raise SystemExit("peak-web redirect URIs must contain only PEAK_APP_ORIGIN")
if web_client.get("webOrigins") != [expected_app_origin]:
    raise SystemExit("peak-web origins must contain only PEAK_APP_ORIGIN")
if web_client.get("attributes", {}).get("pkce.code.challenge.method") != "S256":
    raise SystemExit("peak-web client must require PKCE S256")

mappers = json.loads(os.environ["WEB_MAPPERS_RESPONSE"])
audience_mappers = [
    mapper for mapper in mappers
    if mapper.get("name") == "peak-api-audience"
    and mapper.get("protocolMapper") == "oidc-audience-mapper"
    and mapper.get("config", {}).get("included.custom.audience") == expected_audience
    and mapper.get("config", {}).get("access.token.claim") == "true"
]
if len(audience_mappers) != 1:
    raise SystemExit("peak-web client must include one access-token peak-api audience mapper")

print("Keycloak realm verified")
PY
