#!/usr/bin/env sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/ops/production/.env}"
API_BASE_URL="${1:-${PEAK_API_BASE_URL:-http://localhost:8080}}"
KEYCLOAK_BASE_URL="${2:-${KEYCLOAK_BASE_URL:-http://localhost:8081}}"
PLATFORM_BASE_URL="${3:-${PEAK_PLATFORM_BASE_URL:-http://localhost:8082}}"

if [ -f "$ENV_FILE" ]; then
  set -a
  . "$ENV_FILE"
  set +a
fi

HOSPITALITY_REALM="${KEYCLOAK_HOSPITALITY_REALM:-peak-hospitality}"
PLATFORM_REALM="${KEYCLOAK_PLATFORM_REALM:-peak-platform}"
EXPECTED_HOSPITALITY_ISSUER="${PEAK_SECURITY_JWT_ISSUER_URI:-$KEYCLOAK_BASE_URL/realms/$HOSPITALITY_REALM}"
EXPECTED_PLATFORM_ISSUER="${PEAK_PLATFORM_JWT_ISSUER_URI:-$KEYCLOAK_BASE_URL/realms/$PLATFORM_REALM}"

"$ROOT_DIR/ops/scripts/healthcheck.sh" "$API_BASE_URL/actuator/health/liveness"
"$ROOT_DIR/ops/scripts/healthcheck.sh" "$API_BASE_URL/actuator/health/readiness"

HOSPITALITY_OIDC="$(curl -fsS "$KEYCLOAK_BASE_URL/realms/$HOSPITALITY_REALM/.well-known/openid-configuration")"
PLATFORM_OIDC="$(curl -fsS "$KEYCLOAK_BASE_URL/realms/$PLATFORM_REALM/.well-known/openid-configuration")"
HOSPITALITY_OIDC="$HOSPITALITY_OIDC" \
PLATFORM_OIDC="$PLATFORM_OIDC" \
EXPECTED_HOSPITALITY_ISSUER="$EXPECTED_HOSPITALITY_ISSUER" \
EXPECTED_PLATFORM_ISSUER="$EXPECTED_PLATFORM_ISSUER" \
python3 - <<'PY'
import json
import os

for payload_name, expected_name in (
    ("HOSPITALITY_OIDC", "EXPECTED_HOSPITALITY_ISSUER"),
    ("PLATFORM_OIDC", "EXPECTED_PLATFORM_ISSUER"),
):
    oidc = json.loads(os.environ[payload_name])
    issuer = (oidc.get("issuer") or "").rstrip("/")
    expected = os.environ[expected_name].rstrip("/")
    if issuer != expected:
        raise SystemExit(f"Unexpected Keycloak issuer {issuer!r}; expected {expected!r}")
    if not oidc.get("jwks_uri"):
        raise SystemExit(f"{issuer} discovery document does not expose jwks_uri")
    if "S256" not in oidc.get("code_challenge_methods_supported", []):
        raise SystemExit(f"{issuer} discovery document does not advertise PKCE S256")
PY

KEYCLOAK_BASE_URL="$KEYCLOAK_BASE_URL" "$ROOT_DIR/ops/scripts/verify-keycloak-realms.sh"

swagger_status="$(curl -s -o /dev/null -w '%{http_code}' "$API_BASE_URL/swagger-ui.html")"
case "$swagger_status" in
  200|301|302|307|308)
    echo "SpringDoc UI is exposed in production: HTTP $swagger_status" >&2
    exit 1
    ;;
esac

secured_status="$(curl -s -o /dev/null -w '%{http_code}' \
  "$API_BASE_URL/api/v1/platform/tenants/00000000-0000-0000-0000-000000000000")"
case "$secured_status" in
  401|403) ;;
  *)
    echo "Secured API route returned unexpected anonymous status: HTTP $secured_status" >&2
    exit 1
    ;;
esac

platform_status="$(curl -s -o /dev/null -w '%{http_code}' \
  "$PLATFORM_BASE_URL/api/v1/platform/tenants/00000000-0000-0000-0000-000000000000")"
case "$platform_status" in
  401|403) ;;
  *)
    echo "Isolated platform route returned unexpected anonymous status: HTTP $platform_status" >&2
    exit 1
    ;;
esac

# Public Keycloak isolation. The checks above use the loopback listeners, which
# bypass the reverse proxy, so they cannot see whether the public hostname
# blocks the administrative surface. That is only observable against the real
# proxied hostname, so it runs only when one is supplied. Left unset it prints a
# warning rather than passing silently, because an unverified isolation control
# is exactly what should not be assumed.
PUBLIC_KEYCLOAK_URL="${4:-${PEAK_PUBLIC_KEYCLOAK_URL:-}}"
if [ -n "$PUBLIC_KEYCLOAK_URL" ]; then
  "$ROOT_DIR/ops/scripts/verify-ingress-isolation.sh" "$PUBLIC_KEYCLOAK_URL"
else
  echo "NOTE: public Keycloak isolation was not checked." >&2
  echo "      Pass the proxy-served hostname as the 4th argument, or set" >&2
  echo "      PEAK_PUBLIC_KEYCLOAK_URL, to verify /admin/** and /realms/master/**" >&2
  echo "      are blocked publicly." >&2
fi

echo "Peak production smoke test passed"
