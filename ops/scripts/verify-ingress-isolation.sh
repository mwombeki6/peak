#!/usr/bin/env sh
set -eu

# Verify that a PUBLIC Keycloak hostname blocks the administrative surface.
#
# The reverse proxy is expected to terminate TLS for the public Keycloak
# hostname and refuse /admin/** and /realms/master/** there, while the separate
# administrative hostname allows them on the operator network. Nothing in this
# repository could previously prove that block held; the architecture doc states
# it as an unenforced limit for exactly that reason.
#
# This closes the gap the only honest way: by probing the real endpoint the
# operator is about to expose, rather than a mock. Point it at the public
# hostname and it asserts, against that live host, that public realm discovery
# works and the administrative paths do not. A raw Keycloak container fails this
# on purpose, because a raw container is not safe to expose publicly.
#
# Usage:
#   ops/scripts/verify-ingress-isolation.sh https://auth.peak.example.com
#
# The argument is the public Keycloak base URL. It must be the hostname the
# reverse proxy serves, not a loopback listener, or the probe proves nothing.

PUBLIC_KEYCLOAK_URL="${1:-${PEAK_PUBLIC_KEYCLOAK_URL:-}}"
PLATFORM_REALM="${KEYCLOAK_PLATFORM_REALM:-peak-platform}"
HOSPITALITY_REALM="${KEYCLOAK_HOSPITALITY_REALM:-peak-hospitality}"

if [ -z "$PUBLIC_KEYCLOAK_URL" ]; then
  echo "Public Keycloak base URL is required." >&2
  echo "Pass the proxy-served hostname, e.g. https://auth.peak.example.com" >&2
  exit 2
fi

# Trailing slash removed once so path concatenation below is exact.
PUBLIC_KEYCLOAK_URL="${PUBLIC_KEYCLOAK_URL%/}"

case "$PUBLIC_KEYCLOAK_URL" in
  http://localhost*|http://127.*|http://[::1]*)
    echo "Refusing to treat a loopback address as a public hostname: $PUBLIC_KEYCLOAK_URL" >&2
    echo "A direct container listener does not exercise the reverse proxy, so a" >&2
    echo "pass here would be meaningless. Probe the real public hostname." >&2
    exit 2
    ;;
esac

failures=0

status_of() {
  # Prints the HTTP status of a GET, or 000 on a connection-level failure.
  # --max-time bounds a hostname that resolves but never answers.
  curl -s -o /dev/null -w '%{http_code}' --max-time 15 "$1" 2>/dev/null || printf '000'
}

require_reachable() {
  description="$1"
  url="$2"
  status="$(status_of "$url")"
  if [ "$status" = "200" ]; then
    echo "  ok      $description ($status)"
  else
    echo "  FAIL    $description expected 200, got $status" >&2
    failures=$((failures + 1))
  fi
}

require_blocked() {
  description="$1"
  url="$2"
  status="$(status_of "$url")"
  # 401 is deliberately NOT accepted. A 401 means the request reached Keycloak
  # and was merely unauthenticated, which is the exposure this check exists to
  # catch: the administrative surface must be unreachable, not merely guarded.
  case "$status" in
    403|404)
      echo "  ok      $description blocked ($status)"
      ;;
    000)
      echo "  ok      $description unreachable (connection refused)"
      ;;
    *)
      echo "  FAIL    $description must be blocked, got $status" >&2
      failures=$((failures + 1))
      ;;
  esac
}

echo "Probing public Keycloak isolation at $PUBLIC_KEYCLOAK_URL"

# The two Peak realms must remain publicly discoverable; browser and native
# clients complete authorization code flows against them.
require_reachable \
  "platform realm discovery" \
  "$PUBLIC_KEYCLOAK_URL/realms/$PLATFORM_REALM/.well-known/openid-configuration"
require_reachable \
  "hospitality realm discovery" \
  "$PUBLIC_KEYCLOAK_URL/realms/$HOSPITALITY_REALM/.well-known/openid-configuration"

# The administrative surface must not be reachable on the public hostname.
require_blocked "admin base"            "$PUBLIC_KEYCLOAK_URL/admin/"
require_blocked "admin master console"  "$PUBLIC_KEYCLOAK_URL/admin/master/console/"
require_blocked "master realm discovery" \
  "$PUBLIC_KEYCLOAK_URL/realms/master/.well-known/openid-configuration"
require_blocked "master realm token" \
  "$PUBLIC_KEYCLOAK_URL/realms/master/protocol/openid-connect/token"

if [ "$failures" -ne 0 ]; then
  echo "Public Keycloak isolation FAILED with $failures problem(s)." >&2
  echo "The reverse proxy is exposing the administrative surface publicly." >&2
  exit 1
fi

echo "Public Keycloak isolation verified: realms discoverable, admin surface blocked."
