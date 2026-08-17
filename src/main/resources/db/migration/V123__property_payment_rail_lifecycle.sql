-- V123 — configuring a provider is not taking mobile money.
--
-- Until now an active payment_provider_accounts row was enough to push USSD.
-- That made "the adapter exists" and "this hotel may collect guest money"
-- the same fact. Cash and post-to-room never needed a PSP; mobile money does,
-- and the steps in between are different things:
--
--   EXISTS      adapter + rail in peak_payment_method_capabilities
--   CONFIGURED  this property has credentials
--   VERIFIED    those credentials resolve
--   CERTIFIED   sandbox evidence is on the row
--   ENABLED     a manager turned collection on for this property
--   ELIGIBLE    enabled, and the catalog rail can actually recover a lost callback
--
-- ELIGIBLE is computed, not stored: it depends on the catalog and the amount.
-- Merchant binding stays per property. A sibling hotel's account is never inferred.

ALTER TABLE payment_provider_accounts
    ADD COLUMN lifecycle_status varchar(20) NOT NULL DEFAULT 'configured',
    ADD COLUMN verified_at timestamptz,
    ADD COLUMN enabled_at timestamptz,
    ADD CONSTRAINT chk_payment_provider_accounts_lifecycle
        CHECK (lifecycle_status IN ('configured', 'verified', 'certified', 'enabled'));

COMMENT ON COLUMN payment_provider_accounts.lifecycle_status IS
    'How far this property has taken a provider toward collecting guest money. '
    'configured means credentials are stored. enabled means staff may initiate. '
    'is_active remains the soft-delete; it is not the collection gate.';

ALTER TABLE payment_provider_accounts
    ADD CONSTRAINT chk_payment_provider_accounts_production_enable
        CHECK (
            environment <> 'production'
            OR lifecycle_status <> 'enabled'
            OR (
                sandbox_certified_at IS NOT NULL
                AND length(trim(coalesce(sandbox_evidence_ref, ''))) > 0
            )
        );

-- ClickPesa is the only guest rail with a complete initiate/callback/status loop.
-- It was missing from the catalog, so EXISTS could not be told apart from "we
-- wrote an adapter". Enabled here is Peak-level existence of a recoverable rail,
-- not a property being allowed to collect — that is lifecycle_status.
INSERT INTO peak_payment_method_capabilities (
    provider, payment_method, currency, min_amount, max_amount,
    requires_msisdn, supports_status_query, is_enabled, notes
) VALUES
    ('clickpesa', 'mobile_money', 'TZS', 1000, 5000000,
     true, true, true,
     'Complete guest loop (initiate, signed callback, status query). Dormant for '
     'production until sandbox-certified on the property account and then enabled.'),
    ('http_gateway', 'mobile_money', 'TZS', 1000, 5000000,
     true, false, false,
     'DISABLED: can initiate and never confirm. Must not be enabled for a property.')
ON CONFLICT DO NOTHING;

INSERT INTO module_access_matrix (
    module_id, screen_key, screen_label, http_method, api_pattern,
    permission_code, route_scope, guard_mode, access_scope,
    is_tanzania_v1, is_enabled_by_default, notes
) VALUES
    (
        'payments', 'payments.providers.verify', 'Verify Payment Provider',
        'POST', '/api/properties/:propertyId/payments/provider-accounts/:providerAccountId/verify',
        'payments.configure', 'property', 'staff_permission', 'property',
        true, true,
        'Confirms stored credentials resolve. Does not enable collection.'
    ),
    (
        'payments', 'payments.providers.certify', 'Certify Payment Provider',
        'POST', '/api/properties/:propertyId/payments/provider-accounts/:providerAccountId/certify',
        'payments.configure', 'property', 'staff_permission', 'property',
        true, true,
        'Records sandbox certification evidence. Required before production enable.'
    ),
    (
        'payments', 'payments.providers.enable', 'Enable Payment Collection',
        'POST', '/api/properties/:propertyId/payments/provider-accounts/:providerAccountId/enable',
        'payments.configure', 'property', 'staff_permission', 'property',
        true, true,
        'Turns guest mobile-money collection on for this property. Strong session. Catalog rail must be recoverable.'
    ),
    (
        'payments', 'payments.providers.disable', 'Disable Payment Collection',
        'POST', '/api/properties/:propertyId/payments/provider-accounts/:providerAccountId/disable',
        'payments.configure', 'property', 'staff_permission', 'property',
        true, true,
        'Stops initiation. Certification is kept so the hotel can turn the rail back on.'
    )
ON CONFLICT (module_id, screen_key, http_method, api_pattern, permission_code)
DO UPDATE SET
    screen_label = EXCLUDED.screen_label,
    route_scope = EXCLUDED.route_scope,
    guard_mode = EXCLUDED.guard_mode,
    access_scope = EXCLUDED.access_scope,
    notes = EXCLUDED.notes,
    updated_at = now();

DO $migration$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'chk_payment_provider_accounts_lifecycle'
          AND conrelid = 'payment_provider_accounts'::regclass
    ) THEN
        RAISE EXCEPTION 'payment_provider_accounts.lifecycle_status has no CHECK';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM peak_payment_method_capabilities
        WHERE provider = 'clickpesa'
          AND payment_method = 'mobile_money'
          AND supports_status_query = true
          AND is_enabled = true
    ) THEN
        RAISE EXCEPTION 'clickpesa/mobile_money must exist in the catalog as a recoverable rail';
    END IF;

    IF EXISTS (
        SELECT 1 FROM peak_payment_method_capabilities
        WHERE is_enabled = true AND supports_status_query = false
    ) THEN
        RAISE EXCEPTION 'an enabled catalog rail cannot admit it cannot recover a lost callback';
    END IF;
END;
$migration$;
