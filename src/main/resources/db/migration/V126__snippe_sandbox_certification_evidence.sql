-- V126 — Snippe guest-rail certification evidence is a recovery record, not a label.
--
-- V124 catalog-enabled the recoverable Snippe adapter. That means Peak can
-- initiate a direct USSD push and ask GET /v1/payments/{reference} if the
-- callback is dropped. It does not mean any hotel may collect, and it does not
-- mean production is live.
--
-- Property certify records the V97/V98 shape on the account:
--   initiated, confirmed, and independently recovered by status query.
-- Sandbox ENABLE may collect after certify (or verify). Production ENABLE
-- still requires sandbox_certified_at + that evidence on the row.
--
-- Contract is the snippe-integration skill / docs.snippe.sh/docs/2026-01-25:
--   POST /v1/payments  payment_type=mobile, TZS integers, min 500
--   Peak's handle travels in metadata.external_reference
--   webhook data.external_reference is Selcom's, not Peak's
--   status recovery is GET /v1/payments/{reference} using Snippe's issued reference
--
-- ClickPesa stays a dormant complete-loop candidate. http_gateway stays off.

COMMENT ON COLUMN payment_provider_accounts.sandbox_evidence_ref IS
    'JSON recording a sandbox collection that was initiated, confirmed, and '
    'independently recovered by status query. Required before production ENABLE. '
    'A non-empty label is not evidence. Shape: provider, collection_flow, '
    'initiated_reference (provider-issued), confirmed_status, recovered_by_status_query.';

UPDATE peak_payment_method_capabilities
SET notes = 'Direct USSD push per Snippe 2026-01-25: POST /v1/payments with '
            'payment_type=mobile, details.amount as a TZS integer (min 500), '
            'phone_number as 255XXXXXXXXX, and customer.firstname/lastname/email. '
            'Peak''s handle travels in metadata.external_reference; webhook '
            'data.external_reference is Selcom''s. Status via GET /v1/payments/{reference} '
            'using Snippe''s issued reference. Catalog enable is adapter recoverability. '
            'Property ENABLE is the collection gate. Production ENABLE still requires '
            'sandbox evidence of initiate + confirm + status-query recovery on the account.',
    updated_at = now()
WHERE provider = 'snippe'
  AND payment_method = 'mobile_money'
  AND collection_flow = 'direct_push';

UPDATE peak_payment_method_capabilities
SET notes = 'Hosted checkout per Snippe 2026-01-25: POST /api/v1/sessions returns '
            'checkout_url; status via GET /api/v1/sessions/:reference. TZS min 500. '
            'Webhooks are HMAC-SHA256 over {timestamp}.{raw_body} in '
            'X-Webhook-Signature. Catalog enable is adapter recoverability. Property '
            'ENABLE is the collection gate. Production ENABLE still requires sandbox '
            'evidence on the account. Guest/POS collection uses direct_push, not this flow.',
    updated_at = now()
WHERE provider = 'snippe'
  AND payment_method = 'mobile_money'
  AND collection_flow = 'hosted_checkout';

DO $migration$
DECLARE
    stale text;
    missing text;
    gateway_on boolean;
BEGIN
    SELECT string_agg(collection_flow, ', ')
    INTO stale
    FROM peak_payment_method_capabilities
    WHERE provider = 'snippe'
      AND payment_method = 'mobile_money'
      AND (
          notes ILIKE '%DISABLED pending%'
          OR notes ILIKE '%NOT IMPLEMENTED%'
          OR notes ILIKE '%production is live%'
      );

    IF stale IS NOT NULL THEN
        RAISE EXCEPTION
            'Snippe catalog notes still claim the rail is disabled, unimplemented, or live: %',
            stale;
    END IF;

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

    SELECT EXISTS (
        SELECT 1 FROM peak_payment_method_capabilities
        WHERE provider = 'http_gateway' AND is_enabled = true
    ) INTO gateway_on;

    IF gateway_on THEN
        RAISE EXCEPTION 'http_gateway must stay disabled: it can initiate and never confirm';
    END IF;

    IF EXISTS (
        SELECT 1 FROM peak_payment_method_capabilities
        WHERE is_enabled = true AND supports_status_query = false
    ) THEN
        RAISE EXCEPTION
            'an enabled catalog rail cannot admit it cannot recover a lost callback';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_description d
        JOIN pg_class c ON c.oid = d.objoid
        JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = d.objsubid
        WHERE c.relname = 'payment_provider_accounts'
          AND a.attname = 'sandbox_evidence_ref'
          AND d.description ILIKE '%recovered by status query%'
    ) THEN
        RAISE EXCEPTION
            'payment_provider_accounts.sandbox_evidence_ref has no recovery-evidence comment';
    END IF;
END;
$migration$;
