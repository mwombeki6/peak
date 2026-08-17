-- V106 — a guest callback route that is not one provider's.
--
-- PaymentWebhookService refused any provider that was not ClickPesa, and the
-- route was /api/v1/payments/webhooks/clickpesa/{providerAccountId}. Between
-- them, no second guest rail could ever confirm a payment however complete its
-- adapter was: a hotel connected to anything else would watch collections sit
-- pending until the sweep gave up on them.
--
-- The service was already provider-agnostic underneath — it resolves the account,
-- looks up that account's adapter and asks it to verify. Only the gate and the
-- path named a provider. Both now do not.
--
-- The account is still what identifies the hotel and carries its credentials, so
-- providerCode in the path is decoration for providers that insist on a distinct
-- callback URL per integration. The provider is read from the account, never
-- trusted from the URL, because a callback is unauthenticated until it verifies.

INSERT INTO module_access_matrix (
    module_id, screen_key, screen_label, http_method, api_pattern,
    permission_code, route_scope, guard_mode, access_scope,
    is_tanzania_v1, is_enabled_by_default, notes
) VALUES
    ('payments', 'payments.webhooks.provider', 'Provider Payment Callback', 'POST',
     '/api/payments/webhooks/:providerCode/accounts/:providerAccountId', NULL,
     'public', 'public_token', 'tenant', true, true,
     'Signed provider callback for a property''s own merchant account. The provider is '
     'resolved from the account rather than the URL; the path segment exists only for '
     'providers that require a distinct callback URL per integration.')
ON CONFLICT DO NOTHING;

DO $migration$
BEGIN
    -- PUBLIC, never PUBLIC_PROPERTY: authorizePublicToken requires PUBLIC exactly and
    -- refuses a route carrying tenant or property variables. The wrong one satisfies
    -- the check constraint and then denies every callback at runtime.
    IF EXISTS (
        SELECT 1 FROM module_access_matrix
        WHERE api_pattern LIKE '/api/payments/webhooks/%'
          AND (route_scope <> 'public' OR guard_mode <> 'public_token')
    ) THEN
        RAISE EXCEPTION
            'Every payment webhook route must be public/public_token, or callbacks are '
            'denied at runtime while the migration looks fine';
    END IF;
END;
$migration$;
