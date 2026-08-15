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
  PEAK_HOSPITALITY_APP_ORIGIN \
  PEAK_PLATFORM_APP_ORIGIN \
  PEAK_POS_REDIRECT_URI \
  PEAK_API_BIND_ADDRESS \
  PEAK_PLATFORM_BIND_ADDRESS \
  KEYCLOAK_BIND_ADDRESS \
  POSTGRES_DB \
  POSTGRES_MIGRATOR_USER \
  POSTGRES_APP_USER \
  POSTGRES_PLATFORM_USER \
  POSTGRES_WORKER_USER \
  KEYCLOAK_IMAGE \
  KEYCLOAK_ADMIN \
  KEYCLOAK_DB \
  KEYCLOAK_DB_USER \
  KEYCLOAK_HOSTNAME \
  KEYCLOAK_ADMIN_HOSTNAME \
  KEYCLOAK_PLATFORM_REALM \
  KEYCLOAK_HOSPITALITY_REALM \
  KEYCLOAK_WEBAUTHN_RP_ID \
  KEYCLOAK_CACHE_STACK \
  KEYCLOAK_HTTP_MAX_QUEUED_REQUESTS \
  KEYCLOAK_SHUTDOWN_DELAY \
  KEYCLOAK_DB_POOL_INITIAL_SIZE \
  KEYCLOAK_DB_POOL_MIN_SIZE \
  KEYCLOAK_DB_POOL_MAX_SIZE \
  KEYCLOAK_SMTP_FROM \
  KEYCLOAK_SMTP_FROM_DISPLAY_NAME \
  KEYCLOAK_SMTP_REPLY_TO \
  KEYCLOAK_SMTP_HOST \
  KEYCLOAK_SMTP_PORT \
  KEYCLOAK_SMTP_USER \
  PEAK_SECURITY_JWT_ISSUER_URI \
  PEAK_PLATFORM_JWT_ISSUER_URI \
  PEAK_SECURITY_JWT_AUDIENCE \
  PEAK_CORS_ALLOWED_ORIGINS \
  PEAK_REALTIME_WEBSOCKET_ALLOWED_ORIGINS \
  PEAK_PLATFORM_CORS_ALLOWED_ORIGINS \
  PEAK_PLATFORM_REALTIME_WEBSOCKET_ALLOWED_ORIGINS \
  PEAK_COMMUNICATION_DELIVERY_HTTP_PROVIDER_BASE_URL \
  PEAK_COMMUNICATION_PROVIDERS_BEEM_API_KEY \
  PEAK_COMMUNICATION_PROVIDERS_BEEM_SECRET_KEY \
  PEAK_COMMUNICATION_PROVIDERS_BEEM_SOURCE_ADDR \
  PEAK_INVITATION_ACCEPTANCE_BASE_URL \
  PEAK_ENVELOPE_KEY_REFERENCE \
  PEAK_PAYMENT_PRODUCTION_APPROVED_PROVIDER_CODES \
  PEAK_OUTBOUND_PROVIDER_ALLOWED_HOSTS \
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
  PEAK_GUEST_IDENTITY_HASH_KEY \
  PEAK_GUEST_IDENTITY_HASH_KEY_VERSION \
  PEAK_NIDA_MODE \
  PEAK_REPORT_STORAGE_ENDPOINT \
  PEAK_REPORT_STORAGE_ACCESS_KEY \
  PEAK_REPORT_STORAGE_BUCKET \
  PEAK_REPORT_STORAGE_REGION
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
  PEAK_OUTBOX_WORKER_MAX_PARALLELISM \
  KEYCLOAK_HTTP_MAX_QUEUED_REQUESTS \
  KEYCLOAK_DB_POOL_INITIAL_SIZE \
  KEYCLOAK_DB_POOL_MAX_SIZE \
  KEYCLOAK_SMTP_PORT
do
  require_positive_int "$name"
done

for name in \
  PEAK_DB_POOL_MIN_IDLE \
  PEAK_WORKER_DB_POOL_MIN_IDLE \
  PEAK_MIGRATION_DB_POOL_MIN_IDLE \
  KEYCLOAK_DB_POOL_MIN_SIZE
do
  require_non_negative_int "$name"
done

require_int_gte PEAK_DB_POOL_MAX_SIZE PEAK_DB_POOL_MIN_IDLE
require_int_gte PEAK_WORKER_DB_POOL_MAX_SIZE PEAK_WORKER_DB_POOL_MIN_IDLE
require_int_gte PEAK_MIGRATION_DB_POOL_MAX_SIZE PEAK_MIGRATION_DB_POOL_MIN_IDLE
require_int_gte KEYCLOAK_DB_POOL_MAX_SIZE KEYCLOAK_DB_POOL_INITIAL_SIZE
require_int_gte KEYCLOAK_DB_POOL_MAX_SIZE KEYCLOAK_DB_POOL_MIN_SIZE

for name in \
  POSTGRES_MIGRATOR_PASSWORD \
  POSTGRES_APP_PASSWORD \
  POSTGRES_PLATFORM_PASSWORD \
  POSTGRES_WORKER_PASSWORD \
  POSTGRES_PLATFORM_SUPPORT_PASSWORD \
  KEYCLOAK_ADMIN_PASSWORD \
  KEYCLOAK_DB_PASSWORD \
  KEYCLOAK_SMTP_PASSWORD \
  PEAK_COMMUNICATION_DELIVERY_HTTP_PROVIDER_API_KEY \
  PEAK_ENVELOPE_KEY_BASE64 \
  PEAK_PAYMENT_GATEWAY_CREDENTIAL \
  PEAK_PAYMENT_WEBHOOK_SECRET \
  PEAK_CLICKPESA_API_KEY \
  PEAK_CLICKPESA_CHECKSUM_KEY \
  PEAK_FISCAL_GATEWAY_CREDENTIAL \
  PEAK_GUEST_IDENTITY_HASH_KEY \
  PEAK_REPORT_STORAGE_SECRET_KEY
do
  require_secret "$name"
done

for name in \
  KEYCLOAK_SMTP_AUTH \
  KEYCLOAK_SMTP_STARTTLS \
  KEYCLOAK_SMTP_SSL
do
  require_boolean "$name"
done

require_distinct POSTGRES_APP_USER POSTGRES_PLATFORM_USER
require_distinct POSTGRES_APP_USER POSTGRES_WORKER_USER
require_distinct POSTGRES_PLATFORM_USER POSTGRES_WORKER_USER

if [ "$(value_of PEAK_PAYMENT_PRODUCTION_APPROVED_PROVIDER_CODES)" != "snippe" ] && \
   [ "$(value_of PEAK_PAYMENT_PRODUCTION_APPROVED_PROVIDER_CODES)" != "snippe,clickpesa" ]; then
  fail "PEAK_PAYMENT_PRODUCTION_APPROVED_PROVIDER_CODES must include snippe as the guest rail"
fi

case ",$(value_of PEAK_FISCAL_PRODUCTION_APPROVED_PROVIDER_CODES)," in
  *,contract_mock,*|*,signed_simulator,*)
    fail "mock and simulator fiscal providers cannot be approved in production"
    ;;
esac

case ",$(value_of PEAK_OUTBOUND_PROVIDER_ALLOWED_HOSTS)," in
  *,api.snippe.sh,*) ;;
  *) fail "PEAK_OUTBOUND_PROVIDER_ALLOWED_HOSTS must include api.snippe.sh" ;;
esac

case ",$(value_of PEAK_OUTBOUND_PROVIDER_ALLOWED_HOSTS)," in
  *,apisms.beem.africa,*) ;;
  *) fail "PEAK_OUTBOUND_PROVIDER_ALLOWED_HOSTS must include apisms.beem.africa" ;;
esac

guest_identity_hash_key="$(value_of PEAK_GUEST_IDENTITY_HASH_KEY)"
if [ -n "$guest_identity_hash_key" ] && [ "${#guest_identity_hash_key}" -lt 32 ]; then
  fail "PEAK_GUEST_IDENTITY_HASH_KEY must be at least 32 characters"
fi

envelope_key="$(value_of PEAK_ENVELOPE_KEY_BASE64)"
if [ -n "$envelope_key" ]; then
  if ! printf '%s' "$envelope_key" | base64 -d >/dev/null 2>&1; then
    fail "PEAK_ENVELOPE_KEY_BASE64 must be valid base64"
  else
    envelope_key_bytes="$(printf '%s' "$envelope_key" | base64 -d | wc -c | tr -d ' ')"
    if [ "$envelope_key_bytes" -ne 32 ]; then
      fail "PEAK_ENVELOPE_KEY_BASE64 must decode to exactly 32 bytes"
    fi
  fi
fi

previous_envelope_reference="$(value_of PEAK_ENVELOPE_PREVIOUS_KEY_REFERENCE)"
previous_envelope_key="$(value_of PEAK_ENVELOPE_PREVIOUS_KEY_BASE64)"
if [ -n "$previous_envelope_reference" ] || [ -n "$previous_envelope_key" ]; then
  if [ "$previous_envelope_reference" != "env:PEAK_ENVELOPE_PREVIOUS_KEY_BASE64" ]; then
    fail "PEAK_ENVELOPE_PREVIOUS_KEY_REFERENCE must be env:PEAK_ENVELOPE_PREVIOUS_KEY_BASE64"
  fi
  if [ -z "$previous_envelope_key" ]; then
    fail "PEAK_ENVELOPE_PREVIOUS_KEY_BASE64 is required during envelope key rotation"
  elif ! printf '%s' "$previous_envelope_key" | base64 -d >/dev/null 2>&1; then
    fail "PEAK_ENVELOPE_PREVIOUS_KEY_BASE64 must be valid base64"
  else
    previous_envelope_key_bytes="$(
      printf '%s' "$previous_envelope_key" | base64 -d | wc -c | tr -d ' '
    )"
    if [ "$previous_envelope_key_bytes" -ne 32 ]; then
      fail "PEAK_ENVELOPE_PREVIOUS_KEY_BASE64 must decode to exactly 32 bytes"
    fi
  fi
  if [ -n "$previous_envelope_key" ] && [ "$previous_envelope_key" = "$envelope_key" ]; then
    fail "current and previous envelope keys must differ"
  fi
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
  PEAK_COMMUNICATION_PROVIDERS_BEEM_ENABLED \
  PEAK_ACCEPTANCE_MODE \
  PEAK_PLATFORM_BOOTSTRAP_ENABLED \
  PEAK_PLATFORM_RECOVERY_ENABLED \
  PEAK_OTLP_METRICS_EXPORT_ENABLED \
  PEAK_OTLP_LOGGING_EXPORT_ENABLED \
  PEAK_OTLP_TRACING_EXPORT_ENABLED \
  PEAK_REPORT_STORAGE_ENABLED
do
  require_boolean "$name"
done

if [ "$(value_of PEAK_PLATFORM_RECOVERY_ENABLED)" = "true" ] && \
   [ "$(value_of PEAK_PLATFORM_BOOTSTRAP_ENABLED)" != "true" ]; then
  fail "PEAK_PLATFORM_RECOVERY_ENABLED requires PEAK_PLATFORM_BOOTSTRAP_ENABLED=true"
fi

if [ "$(value_of PEAK_PLATFORM_BOOTSTRAP_ENABLED)" = "true" ]; then
  # Both custodians, on every path that sets the flag. Production provisions two
  # Platform Emergency Administrators so that no window exists in which a single
  # account can appoint another root, and offline recovery restores them on the
  # same terms rather than reinstating a lone root.
  #
  # Checked here because this runs before any container starts. The runtime
  # validator also rejects a partial set, but only once the bootstrap process is
  # up, and it reports a Spring property path rather than the variable an
  # operator actually set.
  for name in \
    PEAK_PLATFORM_BOOTSTRAP_FULL_NAME \
    PEAK_PLATFORM_BOOTSTRAP_EMAIL \
    PEAK_PLATFORM_BOOTSTRAP_ISSUER \
    PEAK_PLATFORM_BOOTSTRAP_SUBJECT \
    PEAK_PLATFORM_BOOTSTRAP_SECOND_FULL_NAME \
    PEAK_PLATFORM_BOOTSTRAP_SECOND_EMAIL \
    PEAK_PLATFORM_BOOTSTRAP_SECOND_ISSUER \
    PEAK_PLATFORM_BOOTSTRAP_SECOND_SUBJECT
  do
    require_var "$name"
    reject_placeholder "$name"
  done

  # Two custodians who are the same person satisfy the letter of dual control
  # and defeat it. The runtime rejects this too; catching it here means the
  # ceremony is not started with an arrangement that cannot complete.
  if [ "$(value_of PEAK_PLATFORM_BOOTSTRAP_EMAIL)" = \
       "$(value_of PEAK_PLATFORM_BOOTSTRAP_SECOND_EMAIL)" ]; then
    fail "PEAK_PLATFORM_BOOTSTRAP_EMAIL and PEAK_PLATFORM_BOOTSTRAP_SECOND_EMAIL must differ"
  fi
  if [ "$(value_of PEAK_PLATFORM_BOOTSTRAP_ISSUER)" = \
       "$(value_of PEAK_PLATFORM_BOOTSTRAP_SECOND_ISSUER)" ] && \
     [ "$(value_of PEAK_PLATFORM_BOOTSTRAP_SUBJECT)" = \
       "$(value_of PEAK_PLATFORM_BOOTSTRAP_SECOND_SUBJECT)" ]; then
    fail "the two bootstrap custodians must have distinct identity subjects"
  fi

  for name in PEAK_PLATFORM_BOOTSTRAP_ISSUER PEAK_PLATFORM_BOOTSTRAP_SECOND_ISSUER; do
    if [ "$(value_of "$name")" != "$(value_of PEAK_PLATFORM_JWT_ISSUER_URI)" ]; then
      fail "$name must equal PEAK_PLATFORM_JWT_ISSUER_URI"
    fi
  done
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

# Enabling an adapter is not the same as routing a channel to it. Without these, the
# worker starts cleanly, reports healthy, and every invitation and password reset fails
# in the outbox — discovered days later by a locked-out user, not by a dashboard.
for channel in EMAIL SMS; do
  var="PEAK_COMMUNICATION_ROUTING_${channel}"
  if [ -z "$(value_of "$var")" ]; then
    fail "$var must name the adapter that delivers ${channel} in production"
  fi
done

if [ "$(value_of PEAK_COMMUNICATION_ROUTING_SMS)" != "beem" ]; then
  fail "PEAK_COMMUNICATION_ROUTING_SMS must be beem"
fi

whatsapp_route="$(value_of PEAK_COMMUNICATION_ROUTING_WHATSAPP)"
if [ "$whatsapp_route" = "local" ]; then
  fail "PEAK_COMMUNICATION_ROUTING_WHATSAPP must not be local in production"
fi
if [ "$whatsapp_route" = "beem" ]; then
  if [ -z "$(value_of PEAK_COMMUNICATION_PROVIDERS_BEEM_WHATSAPP_FROM)" ]; then
    fail "PEAK_COMMUNICATION_PROVIDERS_BEEM_WHATSAPP_FROM is required when WhatsApp is routed to beem"
  fi
  callback="$(value_of PEAK_COMMUNICATION_PROVIDERS_BEEM_WHATSAPP_CALLBACK_URL)"
  if [ -z "$callback" ]; then
    fail "PEAK_COMMUNICATION_PROVIDERS_BEEM_WHATSAPP_CALLBACK_URL is required when WhatsApp is routed to beem"
  fi
  case "$callback" in
    https://*) ;;
    *) fail "PEAK_COMMUNICATION_PROVIDERS_BEEM_WHATSAPP_CALLBACK_URL must use https" ;;
  esac
  case ",$(value_of PEAK_OUTBOUND_PROVIDER_ALLOWED_HOSTS)," in
    *,apichatcore.beem.africa,*) ;;
    *) fail "PEAK_OUTBOUND_PROVIDER_ALLOWED_HOSTS must include apichatcore.beem.africa when WhatsApp is routed to beem" ;;
  esac
fi

if [ "$(value_of PEAK_COMMUNICATION_PROVIDERS_BEEM_ENABLED)" != "true" ]; then
  fail "PEAK_COMMUNICATION_PROVIDERS_BEEM_ENABLED must be true in production"
fi

if [ "$(value_of PEAK_REPORT_STORAGE_ENABLED)" != "true" ]; then
  fail "PEAK_REPORT_STORAGE_ENABLED must be true in production"
fi

if [ "$(value_of PEAK_SECURITY_JWT_AUDIENCE)" != "peak-api" ]; then
  fail "PEAK_SECURITY_JWT_AUDIENCE must be peak-api"
fi

case "$(value_of PEAK_SECURITY_JWT_ISSUER_URI)" in
  http://*|https://*) ;;
  *) fail "PEAK_SECURITY_JWT_ISSUER_URI must be an absolute http(s) URL" ;;
esac

case "$(value_of PEAK_PLATFORM_JWT_ISSUER_URI)" in
  http://*|https://*) ;;
  *) fail "PEAK_PLATFORM_JWT_ISSUER_URI must be an absolute http(s) URL" ;;
esac

if [ "$(value_of KEYCLOAK_PLATFORM_REALM)" != "peak-platform" ]; then
  fail "KEYCLOAK_PLATFORM_REALM must be peak-platform"
fi

if [ "$(value_of KEYCLOAK_HOSPITALITY_REALM)" != "peak-hospitality" ]; then
  fail "KEYCLOAK_HOSPITALITY_REALM must be peak-hospitality"
fi

if [ "$(value_of PEAK_SECURITY_JWT_ISSUER_URI)" != \
     "$(value_of KEYCLOAK_HOSTNAME)/realms/$(value_of KEYCLOAK_HOSPITALITY_REALM)" ]; then
  fail "PEAK_SECURITY_JWT_ISSUER_URI must identify the hospitality realm on KEYCLOAK_HOSTNAME"
fi

if [ "$(value_of PEAK_PLATFORM_JWT_ISSUER_URI)" != \
     "$(value_of KEYCLOAK_HOSTNAME)/realms/$(value_of KEYCLOAK_PLATFORM_REALM)" ]; then
  fail "PEAK_PLATFORM_JWT_ISSUER_URI must identify the platform realm on KEYCLOAK_HOSTNAME"
fi

require_distinct PEAK_SECURITY_JWT_ISSUER_URI PEAK_PLATFORM_JWT_ISSUER_URI

case "$(value_of KEYCLOAK_IMAGE)" in
  *@sha256:????????????????????????????????????????????????????????????????) ;;
  *) fail "KEYCLOAK_IMAGE must be pinned to an immutable sha256 manifest digest" ;;
esac

case "$(value_of KEYCLOAK_IMAGE)" in
  *:latest*|*:nightly*) fail "KEYCLOAK_IMAGE must not use latest or nightly tags" ;;
esac

if [ "$(value_of KEYCLOAK_CACHE_STACK)" != "jdbc-ping" ]; then
  fail "KEYCLOAK_CACHE_STACK must be jdbc-ping for database-discovered clustering"
fi

if [ "$(value_of KEYCLOAK_SMTP_AUTH)" != "true" ]; then
  fail "KEYCLOAK_SMTP_AUTH must be true"
fi
if [ "$(value_of KEYCLOAK_SMTP_STARTTLS)" = "$(value_of KEYCLOAK_SMTP_SSL)" ]; then
  fail "exactly one of KEYCLOAK_SMTP_STARTTLS or KEYCLOAK_SMTP_SSL must be true"
fi
case "$(value_of KEYCLOAK_SMTP_HOST)" in
  *"*"*|*"://"*|*"/"*|*","*|*" "*|"")
    fail "KEYCLOAK_SMTP_HOST must be one exact DNS hostname"
    ;;
esac
case "$(value_of KEYCLOAK_SMTP_FROM)" in
  *@*.*) ;;
  *) fail "KEYCLOAK_SMTP_FROM must be an email address" ;;
esac
case "$(value_of KEYCLOAK_SMTP_REPLY_TO)" in
  *@*.*) ;;
  *) fail "KEYCLOAK_SMTP_REPLY_TO must be an email address" ;;
esac

case "$(value_of KEYCLOAK_SHUTDOWN_DELAY)" in
  *s) ;;
  *) fail "KEYCLOAK_SHUTDOWN_DELAY must be an explicit seconds duration" ;;
esac

case "$(value_of KEYCLOAK_WEBAUTHN_RP_ID)" in
  *"*"*|*"://"*|*"/"*|*","*|"")
    fail "KEYCLOAK_WEBAUTHN_RP_ID must be one exact registrable DNS domain"
    ;;
esac

if [ "$(value_of PEAK_ENVELOPE_KEY_REFERENCE)" != "env:PEAK_ENVELOPE_KEY_BASE64" ]; then
  fail "PEAK_ENVELOPE_KEY_REFERENCE must be env:PEAK_ENVELOPE_KEY_BASE64"
fi

case "$(value_of PEAK_INVITATION_ACCEPTANCE_BASE_URL)" in
  https://*) ;;
  *) fail "PEAK_INVITATION_ACCEPTANCE_BASE_URL must use https" ;;
esac

if [ "$(value_of PEAK_ACCEPTANCE_MODE)" != "true" ]; then
  case "$(value_of PEAK_COMMUNICATION_DELIVERY_HTTP_PROVIDER_BASE_URL)" in
    https://*) ;;
    *) fail "PEAK_COMMUNICATION_DELIVERY_HTTP_PROVIDER_BASE_URL must use https" ;;
  esac
fi

case "$(value_of PEAK_HOSPITALITY_APP_ORIGIN)" in
  https://*) ;;
  *) fail "PEAK_HOSPITALITY_APP_ORIGIN must use https" ;;
esac

case "$(value_of PEAK_PLATFORM_APP_ORIGIN)" in
  https://*) ;;
  *) fail "PEAK_PLATFORM_APP_ORIGIN must use https" ;;
esac

if [ "$(value_of PEAK_POS_REDIRECT_URI)" != "http://127.0.0.1" ]; then
  fail "PEAK_POS_REDIRECT_URI must use the RFC 8252 loopback redirect http://127.0.0.1"
fi

require_distinct PEAK_HOSPITALITY_APP_ORIGIN PEAK_PLATFORM_APP_ORIGIN

if [ "$(value_of PEAK_ACCEPTANCE_MODE)" != "true" ]; then
  case "$(value_of KEYCLOAK_HOSTNAME)" in
    https://*) ;;
    *) fail "KEYCLOAK_HOSTNAME must be a full https URL" ;;
  esac
  case "$(value_of KEYCLOAK_ADMIN_HOSTNAME)" in
    https://*) ;;
    *) fail "KEYCLOAK_ADMIN_HOSTNAME must be a full https URL" ;;
  esac
fi

require_distinct KEYCLOAK_HOSTNAME KEYCLOAK_ADMIN_HOSTNAME

case "$(value_of PEAK_OUTBOUND_PROVIDER_ALLOWED_HOSTS)" in
  *"*"*|*"://"*|*"/"*|*"localhost"*|*"127.0.0.1"*|*"0.0.0.0"*|*"::1"*)
    fail "PEAK_OUTBOUND_PROVIDER_ALLOWED_HOSTS must contain exact external DNS hostnames only"
    ;;
esac
outbound_host='([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)+[A-Za-z]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?'
if ! printf '%s' "$(value_of PEAK_OUTBOUND_PROVIDER_ALLOWED_HOSTS)" |
  grep -Eq "^${outbound_host}(,${outbound_host})*$"
then
  fail "PEAK_OUTBOUND_PROVIDER_ALLOWED_HOSTS must be a comma-separated exact DNS host list"
fi

if [ "$(value_of PEAK_API_BIND_ADDRESS)" != "127.0.0.1" ]; then
  fail "PEAK_API_BIND_ADDRESS must be 127.0.0.1; publish Peak through a TLS reverse proxy"
fi

if [ "$(value_of KEYCLOAK_BIND_ADDRESS)" != "127.0.0.1" ]; then
  fail "KEYCLOAK_BIND_ADDRESS must be 127.0.0.1; publish Keycloak through a TLS reverse proxy"
fi

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

case "$(value_of PEAK_PLATFORM_CORS_ALLOWED_ORIGINS)" in
  *"*"*) fail "PEAK_PLATFORM_CORS_ALLOWED_ORIGINS must not contain wildcards" ;;
  *http://*) fail "PEAK_PLATFORM_CORS_ALLOWED_ORIGINS must use https origins" ;;
esac

case "$(value_of PEAK_PLATFORM_REALTIME_WEBSOCKET_ALLOWED_ORIGINS)" in
  *"*"*) fail "PEAK_PLATFORM_REALTIME_WEBSOCKET_ALLOWED_ORIGINS must not contain wildcards" ;;
  *http://*) fail "PEAK_PLATFORM_REALTIME_WEBSOCKET_ALLOWED_ORIGINS must use https origins" ;;
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

# --- Keycloak administration separation -------------------------------------
# Bootstrap administrators are temporary. Retaining them in the ordinary
# runtime environment re-provisions a permanent master-realm human admin on
# every restart, which is exactly the account that must not exist in steady
# state. First installation and offline recovery set them deliberately, run the
# ceremony, then remove them.
if [ -n "$(value_of KEYCLOAK_BOOTSTRAP_ADMIN)" ] || \
   [ -n "$(value_of KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD)" ]; then
  fail "KEYCLOAK_BOOTSTRAP_ADMIN and KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD must be unset in steady-state production; set them only for a first-install or recovery ceremony"
fi

# Administrative endpoints must be reached over the operator network, not the
# public hostname. Keycloak warns that --hostname-admin alone does not stop
# Admin REST access through the public frontend URL, so the reverse proxy must
# block /admin/** and /realms/master/** publicly and automation must target the
# admin base explicitly.
if [ "$(value_of KEYCLOAK_ADMIN_HOSTNAME)" = "$(value_of KEYCLOAK_HOSTNAME)" ]; then
  fail "KEYCLOAK_ADMIN_HOSTNAME must differ from KEYCLOAK_HOSTNAME so administrative endpoints are not served on the public host"
fi

if [ -z "$(value_of KEYCLOAK_ADMIN_BASE_URL)" ]; then
  fail "KEYCLOAK_ADMIN_BASE_URL is required so realm reconciliation targets the administration hostname instead of the public one"
fi

if [ "$(value_of KEYCLOAK_ADMIN_BASE_URL)" = "$(value_of KEYCLOAK_BASE_URL)" ]; then
  fail "KEYCLOAK_ADMIN_BASE_URL must differ from KEYCLOAK_BASE_URL"
fi

# Steady-state reconciliation must use the least-privileged service account, not
# a master-realm administrator password grant. The bootstrap escape hatch is
# refused here so it cannot become the normal path by omission.
if [ -z "$(value_of KEYCLOAK_RECONCILER_SECRET)" ]; then
  fail "KEYCLOAK_RECONCILER_SECRET is required so realm reconciliation uses the least-privileged service account instead of a master-realm administrator password grant"
fi

if [ "$(value_of KEYCLOAK_ALLOW_BOOTSTRAP_ADMIN)" = "true" ]; then
  fail "KEYCLOAK_ALLOW_BOOTSTRAP_ADMIN must not be true in steady-state production; it is a first-install and recovery ceremony switch only"
fi

if [ "$failures" -gt 0 ]; then
  exit 1
fi

echo "Production environment validated: $ENV_FILE"
