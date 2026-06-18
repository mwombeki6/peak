#!/usr/bin/env sh
set -eu

if [ "$#" -gt 1 ]; then
  echo "Usage: $0 [HEALTH_URL]" >&2
  exit 1
fi

HEALTH_URL="${1:-${PEAK_HEALTH_URL:-http://localhost:8080/actuator/health}}"
ATTEMPTS="${PEAK_HEALTH_ATTEMPTS:-30}"
SLEEP_SECONDS="${PEAK_HEALTH_SLEEP_SECONDS:-2}"

i=1
while [ "$i" -le "$ATTEMPTS" ]; do
  if curl -fsS "$HEALTH_URL" >/dev/null; then
    echo "Peak health check passed: $HEALTH_URL"
    exit 0
  fi
  i=$((i + 1))
  sleep "$SLEEP_SECONDS"
done

echo "Peak health check failed: $HEALTH_URL" >&2
exit 1
