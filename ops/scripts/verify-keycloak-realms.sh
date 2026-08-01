#!/usr/bin/env sh
set -eu

BASE_URL="${KEYCLOAK_BASE_URL:-http://localhost:8081}"
ADMIN_BASE_URL="${KEYCLOAK_ADMIN_BASE_URL:-$BASE_URL}"
PLATFORM_REALM="${KEYCLOAK_PLATFORM_REALM:-peak-platform}"
HOSPITALITY_REALM="${KEYCLOAK_HOSPITALITY_REALM:-peak-hospitality}"
ADMIN_USER="${KEYCLOAK_ADMIN:-admin}"
ADMIN_PASSWORD="${KEYCLOAK_ADMIN_PASSWORD:-}"
EXPECTED_PLATFORM_ISSUER="${PEAK_PLATFORM_JWT_ISSUER_URI:-$BASE_URL/realms/$PLATFORM_REALM}"
EXPECTED_HOSPITALITY_ISSUER="${PEAK_SECURITY_JWT_ISSUER_URI:-$BASE_URL/realms/$HOSPITALITY_REALM}"
EXPECTED_AUDIENCE="${PEAK_SECURITY_JWT_AUDIENCE:-peak-api}"
EXPECTED_HOSPITALITY_ORIGIN="${PEAK_HOSPITALITY_APP_ORIGIN:-}"
EXPECTED_PLATFORM_ORIGIN="${PEAK_PLATFORM_APP_ORIGIN:-}"
EXPECTED_POS_REDIRECT="${PEAK_POS_REDIRECT_URI:-http://127.0.0.1}"
EXPECTED_WEBAUTHN_RP_ID="${KEYCLOAK_WEBAUTHN_RP_ID:-}"
EXPECTED_SMTP_FROM="${KEYCLOAK_SMTP_FROM:-}"
EXPECTED_SMTP_REPLY_TO="${KEYCLOAK_SMTP_REPLY_TO:-}"
EXPECTED_SMTP_HOST="${KEYCLOAK_SMTP_HOST:-}"
EXPECTED_SMTP_PORT="${KEYCLOAK_SMTP_PORT:-}"

if [ -z "$ADMIN_PASSWORD" ]; then
  echo "KEYCLOAK_ADMIN_PASSWORD is required" >&2
  exit 1
fi

for origin in \
  "$EXPECTED_HOSPITALITY_ORIGIN" \
  "$EXPECTED_PLATFORM_ORIGIN"
do
  case "$origin" in
    https://*) ;;
    *)
      echo "Every Peak browser client origin must be an explicit HTTPS origin" >&2
      exit 1
      ;;
  esac
done

if [ "$EXPECTED_POS_REDIRECT" != "http://127.0.0.1" ]; then
  echo "PEAK_POS_REDIRECT_URI must be the RFC 8252 loopback redirect http://127.0.0.1" >&2
  exit 1
fi

if [ -z "$EXPECTED_WEBAUTHN_RP_ID" ]; then
  echo "KEYCLOAK_WEBAUTHN_RP_ID is required" >&2
  exit 1
fi

# The master token is fetched from the administrative host, not the public one.
# Two reasons, and either alone is sufficient. Production's reverse proxy blocks
# /realms/master/** on the public hostname, so a token fetched from BASE_URL
# would not be obtainable there. And keeping the token and the admin calls it
# authorises on one host makes it impossible to present a token minted by one
# Keycloak to a different one, which is what happens when a caller pins only
# KEYCLOAK_BASE_URL and KEYCLOAK_ADMIN_BASE_URL still points at another instance.
# Checked before the token request so the failure names its own cause. A caller
# that pins only KEYCLOAK_BASE_URL leaves this address wherever the environment
# put it, which in a production environment file is the real administrative
# hostname. Reaching that from a test harness fails as a connection reset, which
# says nothing about which variable is wrong.
if ! curl -fsS -o /dev/null \
    "$ADMIN_BASE_URL/realms/master/.well-known/openid-configuration" 2>/dev/null; then
  echo "Keycloak administrative host is unreachable: $ADMIN_BASE_URL" >&2
  echo "Administrative calls read KEYCLOAK_ADMIN_BASE_URL, which falls back to" >&2
  echo "KEYCLOAK_BASE_URL ($BASE_URL) only when it is unset. Pin both when" >&2
  echo "targeting a specific instance." >&2
  exit 1
fi

TOKEN_RESPONSE="$(curl -fsS \
  -X POST "$ADMIN_BASE_URL/realms/master/protocol/openid-connect/token" \
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

admin_get() {
  realm="$1"
  path="$2"
  curl -fsS \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    "$ADMIN_BASE_URL/admin/realms/$realm$path"
}

PLATFORM_OIDC="$(curl -fsS "$BASE_URL/realms/$PLATFORM_REALM/.well-known/openid-configuration")"
HOSPITALITY_OIDC="$(curl -fsS "$BASE_URL/realms/$HOSPITALITY_REALM/.well-known/openid-configuration")"
PLATFORM_REALM_RESPONSE="$(admin_get "$PLATFORM_REALM" "")"
HOSPITALITY_REALM_RESPONSE="$(admin_get "$HOSPITALITY_REALM" "")"
PLATFORM_ACTIONS="$(admin_get "$PLATFORM_REALM" "/authentication/required-actions")"
HOSPITALITY_ACTIONS="$(admin_get "$HOSPITALITY_REALM" "/authentication/required-actions")"

PLATFORM_API_CLIENT="$(admin_get "$PLATFORM_REALM" "/clients?clientId=peak-api")"
HOSPITALITY_API_CLIENT="$(admin_get "$HOSPITALITY_REALM" "/clients?clientId=peak-api")"
PLATFORM_WEB_CLIENT="$(admin_get "$PLATFORM_REALM" "/clients?clientId=peak-platform-web")"
HOSPITALITY_WEB_CLIENT="$(admin_get "$HOSPITALITY_REALM" "/clients?clientId=peak-hospitality-web")"
POS_CLIENT="$(admin_get "$HOSPITALITY_REALM" "/clients?clientId=peak-pos-desktop")"
PLATFORM_FORBIDDEN_HOSPITALITY_CLIENT="$(admin_get "$PLATFORM_REALM" "/clients?clientId=peak-hospitality-web")"
HOSPITALITY_FORBIDDEN_PLATFORM_CLIENT="$(admin_get "$HOSPITALITY_REALM" "/clients?clientId=peak-platform-web")"
HOSPITALITY_RETIRED_HOTEL_CLIENT="$(admin_get "$HOSPITALITY_REALM" "/clients?clientId=peak-web")"
HOSPITALITY_RETIRED_TENANT_ADMIN_CLIENT="$(admin_get "$HOSPITALITY_REALM" "/clients?clientId=peak-tenant-admin")"

client_mappers() {
  realm="$1"
  client_id="$2"
  clients="$(admin_get "$realm" "/clients?clientId=$client_id")"
  internal_id="$(printf '%s' "$clients" | python3 -c \
    'import json,sys; clients=json.load(sys.stdin); print(clients[0]["id"])')"
  admin_get "$realm" "/clients/$internal_id/protocol-mappers/models"
}

PLATFORM_WEB_MAPPERS="$(client_mappers "$PLATFORM_REALM" peak-platform-web)"
HOSPITALITY_WEB_MAPPERS="$(client_mappers "$HOSPITALITY_REALM" peak-hospitality-web)"
POS_MAPPERS="$(client_mappers "$HOSPITALITY_REALM" peak-pos-desktop)"

PLATFORM_OIDC="$PLATFORM_OIDC" \
HOSPITALITY_OIDC="$HOSPITALITY_OIDC" \
PLATFORM_REALM_RESPONSE="$PLATFORM_REALM_RESPONSE" \
HOSPITALITY_REALM_RESPONSE="$HOSPITALITY_REALM_RESPONSE" \
PLATFORM_ACTIONS="$PLATFORM_ACTIONS" \
HOSPITALITY_ACTIONS="$HOSPITALITY_ACTIONS" \
PLATFORM_API_CLIENT="$PLATFORM_API_CLIENT" \
HOSPITALITY_API_CLIENT="$HOSPITALITY_API_CLIENT" \
PLATFORM_WEB_CLIENT="$PLATFORM_WEB_CLIENT" \
HOSPITALITY_WEB_CLIENT="$HOSPITALITY_WEB_CLIENT" \
POS_CLIENT="$POS_CLIENT" \
PLATFORM_WEB_MAPPERS="$PLATFORM_WEB_MAPPERS" \
HOSPITALITY_WEB_MAPPERS="$HOSPITALITY_WEB_MAPPERS" \
POS_MAPPERS="$POS_MAPPERS" \
PLATFORM_FORBIDDEN_HOSPITALITY_CLIENT="$PLATFORM_FORBIDDEN_HOSPITALITY_CLIENT" \
HOSPITALITY_FORBIDDEN_PLATFORM_CLIENT="$HOSPITALITY_FORBIDDEN_PLATFORM_CLIENT" \
HOSPITALITY_RETIRED_HOTEL_CLIENT="$HOSPITALITY_RETIRED_HOTEL_CLIENT" \
HOSPITALITY_RETIRED_TENANT_ADMIN_CLIENT="$HOSPITALITY_RETIRED_TENANT_ADMIN_CLIENT" \
EXPECTED_PLATFORM_ISSUER="$EXPECTED_PLATFORM_ISSUER" \
EXPECTED_HOSPITALITY_ISSUER="$EXPECTED_HOSPITALITY_ISSUER" \
EXPECTED_AUDIENCE="$EXPECTED_AUDIENCE" \
EXPECTED_PLATFORM_ORIGIN="$EXPECTED_PLATFORM_ORIGIN" \
EXPECTED_HOSPITALITY_ORIGIN="$EXPECTED_HOSPITALITY_ORIGIN" \
EXPECTED_POS_REDIRECT="$EXPECTED_POS_REDIRECT" \
EXPECTED_WEBAUTHN_RP_ID="$EXPECTED_WEBAUTHN_RP_ID" \
EXPECTED_SMTP_FROM="$EXPECTED_SMTP_FROM" \
EXPECTED_SMTP_REPLY_TO="$EXPECTED_SMTP_REPLY_TO" \
EXPECTED_SMTP_HOST="$EXPECTED_SMTP_HOST" \
EXPECTED_SMTP_PORT="$EXPECTED_SMTP_PORT" \
python3 - <<'PY'
import json
import os


def parsed(name: str):
    return json.loads(os.environ[name])


def one_client(name: str, client_id: str):
    clients = parsed(name)
    if len(clients) != 1:
        raise SystemExit(f"Expected exactly one {client_id} client, found {len(clients)}")
    client = clients[0]
    if client.get("clientId") != client_id:
        raise SystemExit(f"Unexpected client id for {client_id}")
    return client


def verify_discovery(name: str, expected_issuer: str):
    discovery = parsed(name)
    issuer = (discovery.get("issuer") or "").rstrip("/")
    if issuer != expected_issuer.rstrip("/"):
        raise SystemExit(f"Unexpected issuer {issuer!r}; expected {expected_issuer!r}")
    if not discovery.get("jwks_uri"):
        raise SystemExit(f"{issuer} does not expose jwks_uri")
    if "S256" not in discovery.get("code_challenge_methods_supported", []):
        raise SystemExit(f"{issuer} does not advertise PKCE S256")


def verify_realm(name: str, actions_name: str, *, platform: bool):
    realm = parsed(name)
    expected = {
        "enabled": True,
        "registrationAllowed": False,
        "resetPasswordAllowed": True,
        "rememberMe": False,
        "verifyEmail": True,
        "duplicateEmailsAllowed": False,
        "sslRequired": "external",
        "organizationsEnabled": False,
        "bruteForceProtected": True,
        "eventsEnabled": True,
        "adminEventsEnabled": True,
        "adminEventsDetailsEnabled": True,
        "revokeRefreshToken": True,
        "refreshTokenMaxReuse": 0,
        "defaultSignatureAlgorithm": "RS256",
    }
    for field, value in expected.items():
        if realm.get(field) != value:
            raise SystemExit(f"{realm.get('realm')} must set {field}={value!r}")
    if realm.get("failureFactor", 999) > 5:
        raise SystemExit(f"{realm.get('realm')} failureFactor must be at most five")
    if realm.get("accessTokenLifespan", 999999) > 300:
        raise SystemExit(f"{realm.get('realm')} access tokens must expire within five minutes")
    if realm.get("otpPolicyAlgorithm") not in {"HmacSHA256", "HmacSHA512"}:
        raise SystemExit(f"{realm.get('realm')} OTP must use SHA-256 or SHA-512")
    if realm.get("webAuthnPolicyRpId") != os.environ["EXPECTED_WEBAUTHN_RP_ID"]:
        raise SystemExit(f"{realm.get('realm')} WebAuthn RP ID is incorrect")
    if realm.get("webAuthnPolicyPasswordlessUserVerificationRequirement") != "required":
        raise SystemExit(f"{realm.get('realm')} passwordless WebAuthn must require user verification")
    if realm.get("webAuthnPolicyPasswordlessResidentKey") != "required":
        raise SystemExit(f"{realm.get('realm')} passwordless WebAuthn must require discoverable credentials")
    if realm.get("webAuthnPolicyPasswordlessPasskeysEnabled") is not True:
        raise SystemExit(f"{realm.get('realm')} must enable passkey authentication")
    if realm.get("webAuthnPolicyPasswordlessMediation") != "conditional":
        raise SystemExit(f"{realm.get('realm')} passkeys must use unobtrusive conditional mediation")
    smtp = realm.get("smtpServer") or {}
    smtp_expected = {
        "from": os.environ["EXPECTED_SMTP_FROM"],
        "replyTo": os.environ["EXPECTED_SMTP_REPLY_TO"],
        "host": os.environ["EXPECTED_SMTP_HOST"],
        "port": os.environ["EXPECTED_SMTP_PORT"],
        "auth": "true",
    }
    for field, value in smtp_expected.items():
        if smtp.get(field) != value:
            raise SystemExit(f"{realm.get('realm')} SMTP {field} is incorrect")
    if smtp.get("starttls") == smtp.get("ssl"):
        raise SystemExit(f"{realm.get('realm')} SMTP must enable exactly one TLS mode")
    if not smtp.get("user") or not smtp.get("password"):
        raise SystemExit(f"{realm.get('realm')} SMTP authentication is incomplete")
    policy = realm.get("passwordPolicy") or ""
    minimum = "length(15)"
    for requirement in (
        minimum,
        "digits(1)",
        "upperCase(1)",
        "lowerCase(1)",
        "specialChars(1)",
        "notUsername(undefined)",
        "notEmail(undefined)",
        "passwordHistory(5)",
    ):
        if requirement not in policy:
            raise SystemExit(f"{realm.get('realm')} password policy is missing {requirement}")
    if "forceExpiredPasswordChange" in policy:
        raise SystemExit(f"{realm.get('realm')} must not force periodic password rotation")

    actions = {action.get("alias"): action for action in parsed(actions_name)}
    for alias in ("VERIFY_EMAIL", "CONFIGURE_TOTP", "webauthn-register", "webauthn-register-passwordless"):
        if actions.get(alias, {}).get("enabled") is not True:
            raise SystemExit(f"{realm.get('realm')} must enable required action {alias}")
    # Platform operators must be steered to a phishing-resistant authenticator.
    # TOTP stays enabled so it remains available for recovery, but it must not be
    # the default enrolment on either realm, and passwordless WebAuthn must be
    # the default on the platform realm.
    if actions["CONFIGURE_TOTP"].get("defaultAction") is not False:
        raise SystemExit(
            f"{realm.get('realm')} CONFIGURE_TOTP defaultAction must be False so "
            "operators are not enrolled onto a phishable factor by default"
        )
    expected_passwordless_default = platform
    if (
        actions["webauthn-register-passwordless"].get("defaultAction")
        is not expected_passwordless_default
    ):
        raise SystemExit(
            f"{realm.get('realm')} webauthn-register-passwordless defaultAction "
            f"must be {expected_passwordless_default}"
        )


def verify_api(name: str):
    client = one_client(name, "peak-api")
    if client.get("bearerOnly") is not True:
        raise SystemExit("peak-api must be bearer-only")
    for capability in ("standardFlowEnabled", "implicitFlowEnabled", "directAccessGrantsEnabled", "serviceAccountsEnabled"):
        if client.get(capability):
            raise SystemExit(f"peak-api must disable {capability}")


def verify_audience_mapper(name: str):
    expected_audience = os.environ["EXPECTED_AUDIENCE"]
    matches = [
        mapper for mapper in parsed(name)
        if mapper.get("name") == "peak-api-audience"
        and mapper.get("protocolMapper") == "oidc-audience-mapper"
        and mapper.get("config", {}).get("included.custom.audience") == expected_audience
        and mapper.get("config", {}).get("access.token.claim") == "true"
        and mapper.get("config", {}).get("id.token.claim") == "false"
    ]
    if len(matches) != 1:
        raise SystemExit(f"{name} must contain exactly one access-token audience mapper")


def verify_spa(client_name: str, client_id: str, origin: str, mappers_name: str):
    client = one_client(client_name, client_id)
    if client.get("publicClient") is not True:
        raise SystemExit(f"{client_id} must be public")
    if client.get("standardFlowEnabled") is not True:
        raise SystemExit(f"{client_id} must enable authorization code flow")
    for capability in ("implicitFlowEnabled", "directAccessGrantsEnabled", "serviceAccountsEnabled"):
        if client.get(capability):
            raise SystemExit(f"{client_id} must disable {capability}")
    expected_redirects = [f"{origin}/auth/callback", f"{origin}/silent-check-sso.html"]
    if sorted(client.get("redirectUris") or []) != sorted(expected_redirects):
        raise SystemExit(f"{client_id} redirect URIs are not exact")
    if client.get("webOrigins") != [origin]:
        raise SystemExit(f"{client_id} web origin is not exact")
    attributes = client.get("attributes", {})
    if attributes.get("pkce.code.challenge.method") != "S256":
        raise SystemExit(f"{client_id} must require PKCE S256")
    if attributes.get("post.logout.redirect.uris") != f"{origin}/*":
        raise SystemExit(f"{client_id} post-logout redirect is not constrained")
    verify_audience_mapper(mappers_name)


def verify_pos():
    client = one_client("POS_CLIENT", "peak-pos-desktop")
    if client.get("publicClient") is not True or client.get("standardFlowEnabled") is not True:
        raise SystemExit("peak-pos-desktop must be a public authorization-code client")
    for capability in ("implicitFlowEnabled", "directAccessGrantsEnabled", "serviceAccountsEnabled"):
        if client.get(capability):
            raise SystemExit(f"peak-pos-desktop must disable {capability}")
    redirect = os.environ["EXPECTED_POS_REDIRECT"]
    if client.get("redirectUris") != [redirect] or client.get("webOrigins") not in ([], None):
        raise SystemExit("peak-pos-desktop must use only the RFC 8252 loopback redirect")
    if client.get("attributes", {}).get("pkce.code.challenge.method") != "S256":
        raise SystemExit("peak-pos-desktop must require PKCE S256")
    verify_audience_mapper("POS_MAPPERS")


platform_issuer = os.environ["EXPECTED_PLATFORM_ISSUER"]
hospitality_issuer = os.environ["EXPECTED_HOSPITALITY_ISSUER"]
if platform_issuer.rstrip("/") == hospitality_issuer.rstrip("/"):
    raise SystemExit("Platform and hospitality issuers must be distinct")

verify_discovery("PLATFORM_OIDC", platform_issuer)
verify_discovery("HOSPITALITY_OIDC", hospitality_issuer)
verify_realm("PLATFORM_REALM_RESPONSE", "PLATFORM_ACTIONS", platform=True)
verify_realm("HOSPITALITY_REALM_RESPONSE", "HOSPITALITY_ACTIONS", platform=False)
verify_api("PLATFORM_API_CLIENT")
verify_api("HOSPITALITY_API_CLIENT")
verify_spa(
    "PLATFORM_WEB_CLIENT",
    "peak-platform-web",
    os.environ["EXPECTED_PLATFORM_ORIGIN"],
    "PLATFORM_WEB_MAPPERS",
)
verify_spa(
    "HOSPITALITY_WEB_CLIENT",
    "peak-hospitality-web",
    os.environ["EXPECTED_HOSPITALITY_ORIGIN"],
    "HOSPITALITY_WEB_MAPPERS",
)
verify_pos()

if parsed("PLATFORM_FORBIDDEN_HOSPITALITY_CLIENT"):
    raise SystemExit("The platform realm must not contain the hospitality web client")
if parsed("HOSPITALITY_FORBIDDEN_PLATFORM_CLIENT"):
    raise SystemExit("The hospitality realm must not contain the platform administration client")
if parsed("HOSPITALITY_RETIRED_HOTEL_CLIENT"):
    raise SystemExit("The retired peak-web client must not remain in the hospitality realm")
if parsed("HOSPITALITY_RETIRED_TENANT_ADMIN_CLIENT"):
    raise SystemExit("The retired peak-tenant-admin client must not remain in the hospitality realm")

print("Keycloak platform and hospitality realms verified")
PY
