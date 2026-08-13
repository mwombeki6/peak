-- V104 — the direct push rail now has an adapter behind it.
--
-- V103 declared snippe/mobile_money/direct_push with a NOT IMPLEMENTED note, and
-- added a guard refusing to enable anything wearing that note. The adapter now
-- exists, so leaving the note would make the registry wrong in the opposite
-- direction to the mistake V103 corrected -- and a registry that is wrong either
-- way stops being worth consulting.
--
-- Still disabled. Implementing an adapter is not certifying a rail, and for this
-- one the open questions are specific:
--
--   * amount units. Snippe documents "Integer (smallest unit)"; the documented
--     minimum of 500 and a request example of 500 TZS both behave as whole
--     shillings, which is what the adapter sends. A wrong reading here rejects
--     every payment on the amount check rather than settling at a hundredth, so
--     it fails safe -- but one sandbox payment ends the argument.
--   * request shape. The mobile-money page carries a literal example
--     (payment_type, details.amount, details.currency, phone_number,
--     customer.firstname/lastname/email) while the payments overview page
--     describes a different one (amount as an object, channel required,
--     first_name rather than firstname). The flow-specific page is treated as
--     authoritative. A wrong guess is a 400 from Snippe, which is loud.
--   * credentials. The sessions page documents a JWT bearer while the payments
--     and authentication pages document an snp_ API key.
--   * correlation. The create-payment body has no external_reference field, so
--     Peak's reference travels in metadata. That the callback echoes metadata is
--     shown in their webhook example but has not been observed live.

UPDATE peak_payment_method_capabilities
SET notes = 'Direct USSD push: POST /v1/payments with payment_type=mobile and phone_number '
            '(note the /v1 prefix, not /api/v1). Status via GET /v1/payments/{reference}, '
            'keyed on the reference Snippe issues at initiation. Peak''s own reference '
            'travels in metadata, since the create body has no external_reference field. '
            'Requires the payer''s name and email. DISABLED pending sandbox certification: '
            'amount units, the request shape the two documentation pages disagree on, which '
            'credential type is accepted, and whether metadata is echoed on the callback.',
    updated_at = now()
WHERE provider = 'snippe'
  AND payment_method = 'mobile_money'
  AND collection_flow = 'direct_push';

DO $migration$
DECLARE
    stale text;
BEGIN
    -- The V103 guard still stands for any rail that genuinely has no adapter. This
    -- only asserts that Snippe's is no longer among them.
    SELECT string_agg(provider || '/' || payment_method || '/' || collection_flow, ', ')
    INTO stale
    FROM peak_payment_method_capabilities
    WHERE provider = 'snippe'
      AND notes LIKE 'NOT IMPLEMENTED%';

    IF stale IS NOT NULL THEN
        RAISE EXCEPTION
            'These Snippe rails are still marked NOT IMPLEMENTED but the adapter covers '
            'them: %', stale;
    END IF;

    -- And nothing uncertified has been switched on in passing.
    IF EXISTS (
        SELECT 1 FROM peak_payment_method_capabilities
        WHERE provider = 'snippe' AND is_enabled = true
    ) THEN
        RAISE EXCEPTION
            'Snippe is enabled but has not been certified in sandbox; see this migration';
    END IF;
END;
$migration$;
