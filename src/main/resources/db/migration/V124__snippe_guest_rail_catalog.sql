-- V124 — Snippe is the guest rail Peak will actually collect on.
--
-- V102 and V104 refused to flip is_enabled because they treated catalog existence
-- as the same fact as a hotel taking live money. V123 split those:
--
--   catalog is_enabled     Peak has a recoverable adapter for this rail
--   lifecycle_status       this property may initiate
--   production ENABLE      still requires sandbox_certified_at on the account
--
-- Contract is docs.snippe.sh/docs/2026-01-25:
--
--   direct_push      POST /v1/payments  payment_type=mobile, USSD to the handset
--                    GET  /v1/payments/{reference}
--                    TZS minimum 500 (integer, smallest unit = shilling)
--   hosted_checkout  POST /api/v1/sessions  returns checkout_url
--                    GET  /api/v1/sessions/:reference
--                    same TZS minimum 500
--
-- Webhooks are HMAC-SHA256 over {timestamp}.{raw_body} in X-Webhook-Signature.
-- Property ENABLE is still the collection gate; production ENABLE still
-- requires sandbox certification on the account.
--
-- ClickPesa stays listed as a complete-loop candidate. It is not the launch rail.

UPDATE peak_payment_method_capabilities
SET is_enabled = true,
    min_amount = 500,
    notes = 'Direct USSD push per Snippe 2026-01-25: POST /v1/payments with '
            'payment_type=mobile, details.amount as a TZS integer (min 500), '
            'phone_number as 255XXXXXXXXX, and customer.firstname/lastname/email. '
            'Status via GET /v1/payments/{reference}. Peak''s reference travels in '
            'metadata. Property ENABLE is still the collection gate.',
    updated_at = now()
WHERE provider = 'snippe'
  AND payment_method = 'mobile_money'
  AND collection_flow = 'direct_push';

UPDATE peak_payment_method_capabilities
SET is_enabled = true,
    min_amount = 500,
    notes = 'Hosted checkout per Snippe 2026-01-25: POST /api/v1/sessions returns '
            'checkout_url; status via GET /api/v1/sessions/:reference. TZS min 500. '
            'Webhooks are HMAC-SHA256 over {timestamp}.{raw_body} in '
            'X-Webhook-Signature. Property ENABLE is still the collection gate.',
    updated_at = now()
WHERE provider = 'snippe'
  AND payment_method = 'mobile_money'
  AND collection_flow = 'hosted_checkout';

DO $migration$
DECLARE
    missing text;
    wrongly_enabled text;
BEGIN
    SELECT string_agg(collection_flow, ', ')
    INTO missing
    FROM (
        SELECT unnest(ARRAY['direct_push', 'hosted_checkout']) AS collection_flow
        EXCEPT
        SELECT collection_flow
        FROM peak_payment_method_capabilities
        WHERE provider = 'snippe'
          AND payment_method = 'mobile_money'
          AND currency = 'TZS'
          AND is_enabled = true
          AND supports_status_query = true
          AND min_amount = 500
    ) absent;

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION
            'Snippe guest rails are not catalog-enabled and recoverable: %', missing;
    END IF;

    SELECT string_agg(provider || '/' || payment_method || '/' || collection_flow, ', ')
    INTO wrongly_enabled
    FROM peak_payment_method_capabilities
    WHERE is_enabled = true
      AND supports_status_query = false;

    IF wrongly_enabled IS NOT NULL THEN
        RAISE EXCEPTION
            'These rails are enabled but cannot be reconciled by status query: %',
            wrongly_enabled;
    END IF;
END;
$migration$;
