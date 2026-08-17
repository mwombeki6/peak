-- V130 — a wizard can drive tenant and property launch from one nextAction.
--
-- Property go-live already had a step machine (V125). Hotels still had to guess
-- which of seven evidence rows to fix, and activate 409 was a generic refusal.
-- Tenant launch dumped verification, reports, and contacts onto the first
-- Keycloak admin. This migration only publishes the routes the wizard needs.
--
-- sms_routable stays on the property step list for honesty, but activate is
-- not blocked on PEAK_COMMUNICATION_ROUTING_SMS. That env is Peak ops (Beem).
-- The API surfaces it as operatorBlocker, not a hotel nextAction.

INSERT INTO module_access_matrix (
    module_id, screen_key, screen_label, http_method, api_pattern,
    permission_code, route_scope, guard_mode, access_scope,
    is_tanzania_v1, is_enabled_by_default, notes
) VALUES
    (
        'tenant_admin', 'tenant.onboarding', 'Tenant Launch',
        'GET', '/api/tenants/:tenantId/onboarding',
        'tenant.profile.view', 'tenant', 'staff_permission', 'tenant',
        true, true,
        'Short tenant launch machine: registered, Keycloak admin, can create properties. Not operational readiness.'
    ),
    (
        'platform_admin', 'platform.tenants.onboarding', 'Tenant Launch',
        'GET', '/api/platform/tenants/:tenantId/onboarding',
        'platform.tenants.view', 'platform', 'platform_permission', 'platform',
        true, true,
        'Platform read of the short tenant launch machine and the single nextAction.'
    ),
    (
        'property', 'property.bootstrap', 'Bootstrap First Property',
        'POST', '/api/properties/bootstrap',
        'property.manage', 'tenant', 'staff_permission', 'tenant',
        true, true,
        'STRONG manager creates a distinct property, attaches as Property Administrator, seeds no rooms. Returns onboarding nextAction.'
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
        SELECT 1 FROM module_access_matrix
        WHERE module_id = 'tenant_admin'
          AND screen_key = 'tenant.onboarding'
          AND http_method = 'GET'
          AND api_pattern = '/api/tenants/:tenantId/onboarding'
    ) THEN
        RAISE EXCEPTION 'tenant onboarding read route is missing from the access matrix';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM module_access_matrix
        WHERE module_id = 'platform_admin'
          AND screen_key = 'platform.tenants.onboarding'
          AND http_method = 'GET'
          AND api_pattern = '/api/platform/tenants/:tenantId/onboarding'
    ) THEN
        RAISE EXCEPTION 'platform tenant onboarding read route is missing from the access matrix';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM module_access_matrix
        WHERE module_id = 'property'
          AND screen_key = 'property.bootstrap'
          AND http_method = 'POST'
          AND api_pattern = '/api/properties/bootstrap'
    ) THEN
        RAISE EXCEPTION 'property bootstrap route is missing from the access matrix';
    END IF;
END;
$migration$;
