-- V102 — the Snippe adapter exists; the rail is still not certified.
--
-- V98 recorded snippe/mobile_money with supports_status_query = false, because at
-- that point no adapter existed and nothing could query anything. That is no
-- longer true: SnippePaymentProvider implements queryStatus against
-- GET /api/v1/sessions/:reference, keyed on the `reference` that
-- POST /api/v1/sessions returns at initiation. So a lost callback is recoverable
-- on this rail, which is the condition the V98 guard cares about.
--
-- It stays disabled. Three things are still open, and none of them can be settled
-- by reading:
--
--   * amount units. Snippe documents "Integer (smallest unit)". For TZS the
--     smallest circulating unit is the shilling, and the magnitudes agree -- a
--     documented minimum of 500 is sensible in shillings and absurd in
--     hundredths -- so the adapter treats the value as whole shillings. If that
--     is wrong the failure is safe rather than silent: settlement refuses a
--     callback whose amount disagrees with the attempt, so every payment would be
--     rejected loudly instead of settled at a hundredth of its value. One sandbox
--     payment settles it.
--   * the real base URL and whether sessions are the right rail for a USSD push,
--     or only for hosted checkout.
--   * whether the documented direct payments endpoint is /v1/payments or
--     /api/v1/payments -- the docs use both -- and its request schema, which is
--     not published. The adapter deliberately implements only hosted checkout.
--
-- Enabling is a data change once a sandbox payment has been initiated, confirmed
-- by callback, and independently recovered by status query with the callback
-- dropped.

UPDATE peak_payment_method_capabilities
SET supports_status_query = true,
    notes = 'Hosted checkout via POST /api/v1/sessions; status via '
            'GET /api/v1/sessions/:reference, keyed on the reference returned at '
            'initiation. Webhooks are HMAC-SHA256 over {timestamp}.{raw_body} in '
            'X-Webhook-Signature. DISABLED pending sandbox certification: amount '
            'units, base URL, and whether sessions serve a USSD push or only '
            'hosted checkout.',
    updated_at = now()
WHERE provider = 'snippe'
  AND payment_method = 'mobile_money'
  AND currency = 'TZS';

DO $migration$
DECLARE
    wrongly_enabled text;
BEGIN
    -- The V98 invariant, restated because this migration touches the column it
    -- depends on: a rail that is on but cannot be asked about is one where a lost
    -- callback silently loses a customer's payment.
    SELECT string_agg(provider || '/' || payment_method, ', ')
    INTO wrongly_enabled
    FROM peak_payment_method_capabilities
    WHERE is_enabled = true
      AND supports_status_query = false;

    IF wrongly_enabled IS NOT NULL THEN
        RAISE EXCEPTION
            'These payment methods are enabled but cannot be reconciled by status query: %',
            wrongly_enabled;
    END IF;

    -- Implementing an adapter is not certifying a rail. If this ever passes, someone
    -- has enabled Snippe without the sandbox run that settles its amount units, and
    -- a wrong guess there rejects every payment.
    IF EXISTS (
        SELECT 1 FROM peak_payment_method_capabilities
        WHERE provider = 'snippe' AND is_enabled = true
    ) THEN
        RAISE EXCEPTION
            'Snippe is enabled but has not been certified in sandbox; see this migration';
    END IF;
END;
$migration$;
