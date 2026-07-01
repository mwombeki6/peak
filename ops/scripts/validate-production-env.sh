#!/usr/bin/env sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
ENV_FILE="${1:-${ENV_FILE:-$ROOT_DIR/ops/production/.env}}"

if [ ! -f "$ENV_FILE" ]; then
  echo "Missing env file: $ENV_FILE" >&2
  exit 1
fi

set -a
. "$ENV_FILE"
set +a

failures=0

fail() {
  echo "production env: $1" >&2
  failures=$((failures + 1))
}

value_of() {
  eval "printf '%s' \"\${$1:-}\""
}

require_var() {
  name="$1"
  value="$(value_of "$name")"
  if [ -z "$value" ]; then
    fail "$name is required"
  fi
}

reject_placeholder() {
  name="$1"
  value="$(value_of "$name")"
  case "$value" in
    *CHANGE_ME*|*change-me*|*changeme*|*CHANGE-ME*)
      fail "$name still contains a placeholder value"
      ;;
  esac
}

require_secret() {
  name="$1"
  value="$(value_of "$name")"
  require_var "$name"
  reject_placeholder "$name"
  if [ -n "$value" ] && [ "${#value}" -lt 16 ]; then
    fail "$name must be at least 16 characters"
  fi
}

require_boolean() {
  name="$1"
  value="$(value_of "$name")"
  require_var "$name"
  case "$value" in
    true|false) ;;
    *) fail "$name must be true or false" ;;
  esac
}

require_non_negative_int() {
  name="$1"
  value="$(value_of "$name")"
  require_var "$name"
  case "$value" in
    ''|*[!0-9]*) fail "$name must be a non-negative integer" ;;
  esac
}

require_positive_int() {
  name="$1"
  value="$(value_of "$name")"
  require_non_negative_int "$name"
  if [ -n "$value" ] && [ "$value" -lt 1 ]; then
    fail "$name must be greater than zero"
  fi
}

require_int_gte() {
  left="$1"
  right="$2"
  left_value="$(value_of "$left")"
  right_value="$(value_of "$right")"
  if [ -n "$left_value" ] && [ -n "$right_value" ] && [ "$left_value" -lt "$right_value" ]; then
    fail "$left must be greater than or equal to $right"
  fi
}

require_distinct() {
  left="$1"
  right="$2"
  left_value="$(value_of "$left")"
  right_value="$(value_of "$right")"
  if [ -n "$left_value" ] && [ "$left_value" = "$right_value" ]; then
    fail "$left must not equal $right"
  fi
}

for name in \
  PEAK_IMAGE \
  PEAK_PUBLIC_HOST \
  PEAK_APP_ORIGIN \
  POSTGRES_DB \
  POSTGRES_MIGRATOR_USER \
  POSTGRES_APP_USER \
  POSTGRES_WORKER_USER \
  KEYCLOAK_IMAGE \
  KEYCLOAK_ADMIN \
  KEYCLOAK_DB \
  KEYCLOAK_DB_USER \
  KEYCLOAK_HOSTNAME \
  PEAK_SECURITY_JWT_ISSUER_URI \
  PEAK_SECURITY_JWT_AUDIENCE \
  PEAK_CORS_ALLOWED_ORIGINS \
  PEAK_REALTIME_WEBSOCKET_ALLOWED_ORIGINS \
  PEAK_COMMUNICATION_DELIVERY_HTTP_PROVIDER_BASE_URL \
  PEAK_DB_POOL_MAX_SIZE \
  PEAK_DB_POOL_MIN_IDLE \
  PEAK_DB_CONNECTION_TIMEOUT \
  PEAK_DB_VALIDATION_TIMEOUT \
  PEAK_DB_IDLE_TIMEOUT \
  PEAK_DB_MAX_LIFETIME \
  PEAK_WORKER_DB_POOL_MAX_SIZE \
  PEAK_WORKER_DB_POOL_MIN_IDLE \
  PEAK_MIGRATION_DB_POOL_MAX_SIZE \
  PEAK_MIGRATION_DB_POOL_MIN_IDLE \
  PEAK_OUTBOX_WORKER_BATCH_SIZE \
  PEAK_OUTBOX_WORKER_MAX_PARALLELISM \
  PEAK_PAYMENT_VODACOM_MPESA_URL \
  PEAK_GUEST_IDENTITY_HASH_KEY \
  PEAK_GUEST_IDENTITY_HASH_KEY_VERSION \
  PEAK_NIDA_MODE
do
  require_var "$name"
  reject_placeholder "$name"
done

for name in \
  PEAK_DB_POOL_MAX_SIZE \
  PEAK_DB_CONNECTION_TIMEOUT \
  PEAK_DB_VALIDATION_TIMEOUT \
  PEAK_DB_IDLE_TIMEOUT \
  PEAK_DB_MAX_LIFETIME \
  PEAK_WORKER_DB_POOL_MAX_SIZE \
  PEAK_MIGRATION_DB_POOL_MAX_SIZE \
  PEAK_OUTBOX_WORKER_BATCH_SIZE \
  PEAK_OUTBOX_WORKER_MAX_PARALLELISM
do
  require_positive_int "$name"
done

for name in \
  PEAK_DB_POOL_MIN_IDLE \
  PEAK_WORKER_DB_POOL_MIN_IDLE \
  PEAK_MIGRATION_DB_POOL_MIN_IDLE
do
  require_non_negative_int "$name"
done

require_int_gte PEAK_DB_POOL_MAX_SIZE PEAK_DB_POOL_MIN_IDLE
require_int_gte PEAK_WORKER_DB_POOL_MAX_SIZE PEAK_WORKER_DB_POOL_MIN_IDLE
require_int_gte PEAK_MIGRATION_DB_POOL_MAX_SIZE PEAK_MIGRATION_DB_POOL_MIN_IDLE

for name in \
  POSTGRES_MIGRATOR_PASSWORD \
  POSTGRES_APP_PASSWORD \
  POSTGRES_WORKER_PASSWORD \
  POSTGRES_PLATFORM_SUPPORT_PASSWORD \
  KEYCLOAK_ADMIN_PASSWORD \
  KEYCLOAK_DB_PASSWORD \
  PEAK_COMMUNICATION_DELIVERY_HTTP_PROVIDER_API_KEY \
  PEAK_PAYMENT_VODACOM_MPESA_API_KEY \
  PEAK_PAYMENT_VODACOM_MPESA_API_SECRET \
  PEAK_GUEST_IDENTITY_HASH_KEY
do
  require_secret "$name"
done

guest_identity_hash_key="$(value_of PEAK_GUEST_IDENTITY_HASH_KEY)"
if [ -n "$guest_identity_hash_key" ] && [ "${#guest_identity_hash_key}" -lt 32 ]; then
  fail "PEAK_GUEST_IDENTITY_HASH_KEY must be at least 32 characters"
fi
previous_guest_identity_hash_key="$(value_of PEAK_GUEST_IDENTITY_PREVIOUS_HASH_KEY)"
previous_guest_identity_hash_version="$(value_of PEAK_GUEST_IDENTITY_PREVIOUS_HASH_KEY_VERSION)"
if [ -n "$previous_guest_identity_hash_key" ] || [ -n "$previous_guest_identity_hash_version" ]; then
  if [ -z "$previous_guest_identity_hash_key" ] || [ -z "$previous_guest_identity_hash_version" ]; then
    fail "previous guest identity hash key and version must be configured together"
  fi
  reject_placeholder PEAK_GUEST_IDENTITY_PREVIOUS_HASH_KEY
  if [ -n "$previous_guest_identity_hash_key" ] && [ "${#previous_guest_identity_hash_key}" -lt 32 ]; then
    fail "PEAK_GUEST_IDENTITY_PREVIOUS_HASH_KEY must be at least 32 characters"
  fi
  if [ "$previous_guest_identity_hash_version" = "$(value_of PEAK_GUEST_IDENTITY_HASH_KEY_VERSION)" ]; then
    fail "current and previous guest identity hash key versions must differ"
  fi
fi

case "$(value_of PEAK_NIDA_MODE)" in
  disabled) ;;
  simulator) fail "PEAK_NIDA_MODE must not be simulator in production" ;;
  cig) fail "PEAK_NIDA_MODE=cig is unavailable until the official private CIG contract is implemented" ;;
  *) fail "PEAK_NIDA_MODE must be disabled, simulator, or cig" ;;
esac

for name in \
  PEAK_ALLOW_HEADER_IDENTITY \
  PEAK_ALLOW_TRUSTED_JWT_IDENTITY_CLAIMS \
  PEAK_COMMUNICATION_DELIVERY_LOCAL_PROVIDER_ENABLED \
  PEAK_COMMUNICATION_DELIVERY_HTTP_PROVIDER_ENABLED \
  PEAK_ACCEPTANCE_MODE \
  PEAK_PLATFORM_BOOTSTRAP_ENABLED \
  PEAK_OTLP_METRICS_EXPORT_ENABLED \
  PEAK_OTLP_LOGGING_EXPORT_ENABLED \
  PEAK_OTLP_TRACING_EXPORT_ENABLED
do
  require_boolean "$name"
done

if [ "$(value_of PEAK_PLATFORM_BOOTSTRAP_ENABLED)" = "true" ]; then
  for name in \
    PEAK_PLATFORM_BOOTSTRAP_FULL_NAME \
    PEAK_PLATFORM_BOOTSTRAP_EMAIL \
    PEAK_PLATFORM_BOOTSTRAP_ISSUER \
    PEAK_PLATFORM_BOOTSTRAP_SUBJECT
  do
    require_var "$name"
    reject_placeholder "$name"
  done

  if [ "$(value_of PEAK_PLATFORM_BOOTSTRAP_ISSUER)" != "$(value_of PEAK_SECURITY_JWT_ISSUER_URI)" ]; then
    fail "PEAK_PLATFORM_BOOTSTRAP_ISSUER must equal PEAK_SECURITY_JWT_ISSUER_URI"
  fi
fi

if [ "$(value_of PEAK_ALLOW_HEADER_IDENTITY)" != "false" ]; then
  fail "PEAK_ALLOW_HEADER_IDENTITY must be false in production"
fi

if [ "$(value_of PEAK_ALLOW_TRUSTED_JWT_IDENTITY_CLAIMS)" != "false" ]; then
  fail "PEAK_ALLOW_TRUSTED_JWT_IDENTITY_CLAIMS must be false in production"
fi

if [ "$(value_of PEAK_COMMUNICATION_DELIVERY_LOCAL_PROVIDER_ENABLED)" != "false" ]; then
  fail "PEAK_COMMUNICATION_DELIVERY_LOCAL_PROVIDER_ENABLED must be false in production"
fi

if [ "$(value_of PEAK_COMMUNICATION_DELIVERY_HTTP_PROVIDER_ENABLED)" != "true" ]; then
  fail "PEAK_COMMUNICATION_DELIVERY_HTTP_PROVIDER_ENABLED must be true in production"
fi

if [ "$(value_of PEAK_SECURITY_JWT_AUDIENCE)" != "peak-api" ]; then
  fail "PEAK_SECURITY_JWT_AUDIENCE must be peak-api"
fi

case "$(value_of PEAK_SECURITY_JWT_ISSUER_URI)" in
  http://*|https://*) ;;
  *) fail "PEAK_SECURITY_JWT_ISSUER_URI must be an absolute http(s) URL" ;;
esac

case "$(value_of PEAK_PAYMENT_VODACOM_MPESA_URL)" in
  https://*) ;;
  *) fail "PEAK_PAYMENT_VODACOM_MPESA_URL must use https" ;;
esac

if [ "$(value_of PEAK_ACCEPTANCE_MODE)" != "true" ]; then
  case "$(value_of PEAK_COMMUNICATION_DELIVERY_HTTP_PROVIDER_BASE_URL)" in
    https://*) ;;
    *) fail "PEAK_COMMUNICATION_DELIVERY_HTTP_PROVIDER_BASE_URL must use https" ;;
  esac
fi

case "$(value_of PEAK_APP_ORIGIN)" in
  https://*) ;;
  *) fail "PEAK_APP_ORIGIN must use https" ;;
esac

case "$(value_of PEAK_CORS_ALLOWED_ORIGINS)" in
  *"*"*) fail "PEAK_CORS_ALLOWED_ORIGINS must not contain wildcards" ;;
esac

case "$(value_of PEAK_CORS_ALLOWED_ORIGINS)" in
  *http://*) fail "PEAK_CORS_ALLOWED_ORIGINS must use https origins" ;;
esac

case "$(value_of PEAK_REALTIME_WEBSOCKET_ALLOWED_ORIGINS)" in
  *"*"*) fail "PEAK_REALTIME_WEBSOCKET_ALLOWED_ORIGINS must not contain wildcards" ;;
esac

case "$(value_of PEAK_REALTIME_WEBSOCKET_ALLOWED_ORIGINS)" in
  *http://*) fail "PEAK_REALTIME_WEBSOCKET_ALLOWED_ORIGINS must use https origins" ;;
esac

if [ "$(value_of POSTGRES_APP_USER)" = "$(value_of POSTGRES_MIGRATOR_USER)" ]; then
  fail "POSTGRES_APP_USER must not equal POSTGRES_MIGRATOR_USER"
fi

if [ "$(value_of POSTGRES_WORKER_USER)" = "$(value_of POSTGRES_MIGRATOR_USER)" ]; then
  fail "POSTGRES_WORKER_USER must not equal POSTGRES_MIGRATOR_USER"
fi

require_distinct POSTGRES_MIGRATOR_PASSWORD POSTGRES_APP_PASSWORD
require_distinct POSTGRES_MIGRATOR_PASSWORD POSTGRES_WORKER_PASSWORD
require_distinct POSTGRES_APP_PASSWORD POSTGRES_WORKER_PASSWORD
require_distinct KEYCLOAK_ADMIN_PASSWORD KEYCLOAK_DB_PASSWORD

if [ "$failures" -gt 0 ]; then
  exit 1
fi

echo "Production environment validated: $ENV_FILE"
