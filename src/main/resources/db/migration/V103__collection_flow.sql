-- V103 — the rail and the experience are different dimensions.
--
-- The capability registry keyed on (provider, payment_method, currency), which
-- quietly assumed that naming a rail also named how a customer experiences it. It
-- does not, and Snippe makes that obvious: it offers mobile money two ways, and
-- they are different integrations against different endpoints.
--
--   POST /v1/payments          Peak supplies the phone number
--                              Snippe pushes USSD to the handset
--                              the owner enters a PIN
--
--   POST /api/v1/sessions      Snippe returns a checkout URL
--                              the customer opens it, picks a method,
--                              and Snippe's page drives the rest
--
-- Both are mobile money. Only the first is what "click Pay and answer your phone"
-- means. Recording the adapter Peak actually built as plain
-- snippe/mobile_money would therefore have been a lie in the registry: the row
-- would promise a USSD push that the code does not perform.
--
-- AzamPay will need the same distinction — it offers a hosted checkout alongside
-- the mno/checkout push — so this is not a Snippe workaround.

ALTER TABLE peak_payment_method_capabilities
    ADD COLUMN collection_flow varchar(20) NOT NULL DEFAULT 'direct_push',
    ADD CONSTRAINT chk_peak_collection_flow CHECK (
        collection_flow IN ('direct_push', 'hosted_checkout')
    );

-- The key gains the new dimension, because one provider may offer the same rail
-- through both flows and they are separately enablable.
ALTER TABLE peak_payment_method_capabilities
    DROP CONSTRAINT peak_payment_method_capabilities_pkey,
    ADD CONSTRAINT peak_payment_method_capabilities_pkey
        PRIMARY KEY (provider, payment_method, currency, collection_flow);

-- What was built is hosted checkout, and is now labelled as such.
UPDATE peak_payment_method_capabilities
SET collection_flow = 'hosted_checkout',
    requires_msisdn = false,
    notes = 'Hosted checkout: POST /api/v1/sessions returns a checkout URL the customer '
            'opens; Snippe drives method selection and the push from there. Status via '
            'GET /api/v1/sessions/:reference. Webhooks are HMAC-SHA256 over '
            '{timestamp}.{raw_body} in X-Webhook-Signature. DISABLED pending sandbox '
            'certification: amount units, and which credential type sessions accept — the '
            'sessions page documents a JWT while the payments API documents an snp_ API key.',
    updated_at = now()
WHERE provider = 'snippe' AND payment_method = 'mobile_money';

-- The direct push rail Peak actually wants for subscription billing, declared and
-- unimplemented, so the gap is visible rather than assumed away.
INSERT INTO peak_payment_method_capabilities (
    provider, payment_method, currency, collection_flow, min_amount, max_amount,
    requires_msisdn, supports_status_query, is_enabled, notes
) VALUES
    ('snippe', 'mobile_money', 'TZS', 'direct_push', 500, NULL,
     true, true, false,
     'NOT IMPLEMENTED. POST /v1/payments with payment_type=mobile and phone_number sends a '
     'USSD push directly, which is what "click Pay and answer your phone" requires. Note the '
     'path prefix is /v1 rather than /api/v1 and the request shape differs from sessions '
     '(details.amount, details.currency, customer.firstname/lastname/email). Status via '
     'GET /v1/payments/{reference}.')
ON CONFLICT DO NOTHING;

DO $migration$
DECLARE
    wrongly_enabled text;
    unimplemented_enabled text;
BEGIN
    SELECT string_agg(provider || '/' || payment_method || '/' || collection_flow, ', ')
    INTO wrongly_enabled
    FROM peak_payment_method_capabilities
    WHERE is_enabled = true AND supports_status_query = false;

    IF wrongly_enabled IS NOT NULL THEN
        RAISE EXCEPTION
            'These rails are enabled but cannot be reconciled by status query: %',
            wrongly_enabled;
    END IF;

    -- A declared rail with no adapter behind it must never be enabled. The registry
    -- describes intent as well as capability, and only one of those collects money.
    SELECT string_agg(provider || '/' || payment_method || '/' || collection_flow, ', ')
    INTO unimplemented_enabled
    FROM peak_payment_method_capabilities
    WHERE is_enabled = true
      AND notes LIKE 'NOT IMPLEMENTED%';

    IF unimplemented_enabled IS NOT NULL THEN
        RAISE EXCEPTION
            'These rails are enabled but no adapter implements them: %', unimplemented_enabled;
    END IF;
END;
$migration$;
