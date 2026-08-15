-- V131 — one Snippe brand, two merchants; guest rail is not go-live.
--
-- Tenant Pay Peak collects into Peak's own merchant (peak.platformbilling,
-- primary-provider snippe, webhook /api/v1/platform-billing/webhooks/snippe).
-- That is not a hotel payment_provider_accounts row.
--
-- guest_rail_configured stays on the property step list (V125 CHECK) for
-- honesty, but activate is not blocked on a hotel Snippe account. Collecting
-- guest USSD is a later CONFIGURE/ENABLE on the hotel merchant. Hotels must
-- not think they are onboarding Peak twice.

COMMENT ON TABLE property_onboarding_steps IS
    'Canonical go-live steps for one property. frontline_path and sms_routable '
    'are skipped when POS/front desk are not in scope. guest_rail_configured is '
    'optional after activate (hotel guest collection), not a go-live blocker. '
    'Peak SaaS payment uses Peak''s own Snippe merchant, not this step.';

UPDATE module_access_matrix
SET notes = 'Short tenant launch: registered, Keycloak admin (email/OIDC), Pay Peak when unpaid (platform billing / Peak merchant), then create properties. POS cashier PIN is not this wizard.',
    updated_at = now()
WHERE module_id = 'tenant_admin'
  AND screen_key = 'tenant.onboarding'
  AND http_method = 'GET'
  AND api_pattern = '/api/tenants/:tenantId/onboarding';

UPDATE module_access_matrix
SET notes = 'Platform read of tenant launch: registered, Keycloak admin, Pay Peak when unpaid, then create properties.',
    updated_at = now()
WHERE module_id = 'platform_admin'
  AND screen_key = 'platform.tenants.onboarding'
  AND http_method = 'GET'
  AND api_pattern = '/api/platform/tenants/:tenantId/onboarding';

DO $migration$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM module_access_matrix
        WHERE module_id = 'tenant_admin'
          AND screen_key = 'tenant.onboarding'
          AND notes ILIKE '%Pay Peak%'
    ) THEN
        RAISE EXCEPTION 'tenant onboarding matrix notes must describe Pay Peak';
    END IF;
END;
$migration$;
