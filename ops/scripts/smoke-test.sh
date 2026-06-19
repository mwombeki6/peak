#!/usr/bin/env sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/ops/production/.env}"
API_BASE_URL="${1:-${PEAK_API_BASE_URL:-http://localhost:8080}}"
KEYCLOAK_BASE_URL="${2:-${KEYCLOAK_BASE_URL:-http://localhost:8081}}"

if [ -f "$ENV_FILE" ]; then
  set -a
  . "$ENV_FILE"
  set +a
fi

EXPECTED_ISSUER="${PEAK_SECURITY_JWT_ISSUER_URI:-$KEYCLOAK_BASE_URL/realms/peak}"

"$ROOT_DIR/ops/scripts/healthcheck.sh" "$API_BASE_URL/actuator/health"

OIDC_CONFIGURATION="$(curl -fsS "$KEYCLOAK_BASE_URL/realms/peak/.well-known/openid-configuration")"
OIDC_CONFIGURATION="$OIDC_CONFIGURATION" EXPECTED_ISSUER="$EXPECTED_ISSUER" python3 - <<'PY'
import json
import os

oidc = json.loads(os.environ["OIDC_CONFIGURATION"])
issuer = (oidc.get("issuer") or "").rstrip("/")
expected = os.environ["EXPECTED_ISSUER"].rstrip("/")
if issuer != expected:
    raise SystemExit(f"Unexpected Keycloak issuer {issuer!r}; expected {expected!r}")
if not oidc.get("jwks_uri"):
    raise SystemExit("Keycloak discovery document does not expose jwks_uri")
PY

swagger_status="$(curl -k -s -o /dev/null -w '%{http_code}' "$API_BASE_URL/swagger-ui.html")"
case "$swagger_status" in
  200|301|302|307|308)
    echo "SpringDoc UI is exposed in production: HTTP $swagger_status" >&2
    exit 1
    ;;
esac

secured_status="$(curl -k -s -o /dev/null -w '%{http_code}' \
  "$API_BASE_URL/api/v1/platform/tenants/00000000-0000-0000-0000-000000000000")"
case "$secured_status" in
  401|403) ;;
  *)
    echo "Secured API route returned unexpected anonymous status: HTTP $secured_status" >&2
    exit 1
    ;;
esac

echo "Peak production smoke test passed"
