-- V108 — the suspended-checkout allowance was unusable.
--
-- V91 gave a suspended tenant 'checkout.%' with the rationale "A guest in the
-- building must always be able to leave". It did not give them 'billing.invoice',
-- and FrontDeskService.checkOut refuses with "Checkout requires an issued
-- invoice" before it refuses for any other reason. So the allowance existed, read
-- correctly, and could not be exercised: a suspended hotel could reach the
-- checkout route and then be stopped one step earlier by a permission nobody had
-- thought of as part of departure.
--
-- Nothing detected it because every layer was individually right. can_access_module
-- permitted checkout. The guest-serving modules never read commercial state. The
-- allowance list said what it meant. The gap was between 'what checkout is called'
-- and 'what checkout needs', and only walking the route finds that.
--
-- RESTRICTED was never affected: it already allows 'billing.%'.
--
-- Deliberately 'billing.invoice' rather than 'billing.%'. Suspension is meant to
-- be read-only plus the indefensible-to-withhold, and voiding invoices or issuing
-- credit notes is not on the path out of the building. If a departing guest turns
-- out to need one of those, it should be added here with its own reason rather
-- than acquired by widening a pattern.

INSERT INTO peak_restriction_allowances (restriction_state, permission_pattern, rationale)
VALUES (
    'suspended',
    'billing.invoice',
    'Checkout refuses without an issued invoice, so withholding this withholds departure'
)
ON CONFLICT DO NOTHING;

DO $migration$
DECLARE
    missing text;
BEGIN
    -- Every permission a lawful departure passes through, checked against the
    -- allowances rather than against someone remembering to look. These are the
    -- checks in FrontDeskService.checkOut and the calls that satisfy them.
    SELECT string_agg(required.code, ', ')
    INTO missing
    FROM (VALUES
        ('checkout.process'),
        ('billing.invoice'),
        ('folio.view'),
        ('payments.collect')
    ) AS required(code)
    WHERE NOT EXISTS (
        SELECT 1 FROM peak_restriction_allowances allowance
        WHERE allowance.restriction_state = 'suspended'
          AND required.code LIKE allowance.permission_pattern
    );

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION
            'A suspended tenant cannot complete a checkout without: %. A guest in the '
            'building must always be able to leave, and the allowance list is where that '
            'promise is either kept or quietly broken.',
            missing;
    END IF;
END;
$migration$;
