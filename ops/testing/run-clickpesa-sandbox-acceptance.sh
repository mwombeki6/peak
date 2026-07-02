#!/usr/bin/env bash
set -euo pipefail

for name in \
  PEAK_BASE_URL PEAK_ACCESS_TOKEN PEAK_PROPERTY_ID PEAK_FOLIO_ID \
  PEAK_PROVIDER_ACCOUNT_ID CLICKPESA_CLIENT_ID CLICKPESA_CHECKSUM_KEY \
  CLICKPESA_PHONE_NUMBER CLICKPESA_AMOUNT
do
  if [[ -z "${!name:-}" ]]; then
    echo "$name is required" >&2
    exit 1
  fi
done

evidence_dir="${EVIDENCE_DIR:-build/evidence/clickpesa}"
mkdir -p "$evidence_dir"
run_id="$(date -u +%Y%m%dT%H%M%SZ)-${GITHUB_RUN_ID:-local}"
evidence_file="$evidence_dir/$run_id.json"
base_url="${PEAK_BASE_URL%/}"

api() {
  curl --fail-with-body --silent --show-error \
    -H "Authorization: Bearer $PEAK_ACCESS_TOKEN" \
    -H "Content-Type: application/json" \
    -H "X-Correlation-Id: clickpesa-sandbox-$run_id" \
    "$@"
}

initiation="$(
  api \
    -H "Idempotency-Key: clickpesa-sandbox-$run_id" \
    -X POST \
    --data "$(
      jq -cn \
        --arg folioId "$PEAK_FOLIO_ID" \
        --arg providerAccountId "$PEAK_PROVIDER_ACCOUNT_ID" \
        --arg phoneNumber "$CLICKPESA_PHONE_NUMBER" \
        --arg amount "$CLICKPESA_AMOUNT" \
        '{
          folioId: $folioId,
          providerAccountId: $providerAccountId,
          phoneNumber: $phoneNumber,
          amount: ($amount | tonumber)
        }'
    )" \
    "$base_url/api/v1/properties/$PEAK_PROPERTY_ID/payments/mobile-money"
)"
transaction_id="$(jq -er '.id' <<<"$initiation")"
[[ "$(jq -r '.status' <<<"$initiation")" == "CREATED" ]]

transaction="$initiation"
for _ in $(seq 1 "${CLICKPESA_STATUS_POLL_ATTEMPTS:-60}"); do
  sleep "${CLICKPESA_STATUS_POLL_SECONDS:-10}"
  transaction="$(
    api "$base_url/api/v1/properties/$PEAK_PROPERTY_ID/payments/transactions/$transaction_id"
  )"
  status="$(jq -r '.status' <<<"$transaction")"
  case "$status" in
    POSTED) break ;;
    FAILED|EXPIRED)
      echo "ClickPesa collection ended in $status" >&2
      exit 1
      ;;
  esac
done
[[ "$(jq -r '.status' <<<"$transaction")" == "POSTED" ]]

provider_reference="$(jq -er '.providerReference' <<<"$transaction")"
order_reference="$(jq -er '.internalReference' <<<"$transaction")"
provider_timestamp="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
unsigned_payload="$(
  jq -cnS \
    --arg event "PAYMENT RECEIVED" \
    --arg id "$provider_reference" \
    --arg orderReference "$order_reference" \
    --arg amount "$CLICKPESA_AMOUNT" \
    --arg clientId "$CLICKPESA_CLIENT_ID" \
    --arg updatedAt "$provider_timestamp" \
    '{
      event: $event,
      data: {
        id: $id,
        status: "SUCCESS",
        orderReference: $orderReference,
        collectedAmount: $amount,
        collectedCurrency: "TZS",
        updatedAt: $updatedAt,
        clientId: $clientId
      }
    }'
)"
checksum="$(
  printf '%s' "$unsigned_payload" |
    openssl dgst -sha256 -hmac "$CLICKPESA_CHECKSUM_KEY" -hex |
    awk '{print $NF}'
)"
callback_payload="$(
  jq -c \
    --arg checksum "$checksum" \
    '. + {checksumMethod: "HMAC-SHA256", checksum: $checksum}' \
    <<<"$unsigned_payload"
)"
callback_url="$base_url/api/v1/payments/webhooks/clickpesa/$PEAK_PROVIDER_ACCOUNT_ID"
callback_first="$(api -X POST --data "$callback_payload" "$callback_url")"
callback_duplicate="$(api -X POST --data "$callback_payload" "$callback_url")"
[[ "$(jq -r '.status' <<<"$callback_first")" == "POSTED" ]]
[[ "$(jq -r '.replayed' <<<"$callback_duplicate")" == "true" ]]

today="$(date -u +%F)"
reconciliation="$(
  api \
    -H "Idempotency-Key: clickpesa-reconciliation-$run_id" \
    -X POST \
    --data "$(
      jq -cn \
        --arg providerAccountId "$PEAK_PROVIDER_ACCOUNT_ID" \
        --arg date "$today" \
        '{
          providerAccountId: $providerAccountId,
          startDate: $date,
          endDate: $date,
          currency: "TZS"
        }'
    )" \
    "$base_url/api/v1/properties/$PEAK_PROPERTY_ID/payments/reconciliations/import"
)"
jq -e '.accepted == true' <<<"$reconciliation" >/dev/null

jq -n \
  --arg runId "$run_id" \
  --arg capturedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --argjson initiation "$initiation" \
  --argjson terminalTransaction "$transaction" \
  --argjson callbackFirst "$callback_first" \
  --argjson callbackDuplicate "$callback_duplicate" \
  --argjson reconciliationImport "$reconciliation" \
  '{
    runId: $runId,
    capturedAt: $capturedAt,
    initiation: $initiation,
    terminalTransaction: $terminalTransaction,
    checksumWebhook: $callbackFirst,
    duplicateWebhook: $callbackDuplicate,
    reconciliationImport: $reconciliationImport
  }' >"$evidence_file"

echo "$evidence_file"
