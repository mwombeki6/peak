-- V145 — the route guard denies any API route that isn't explicitly registered in
-- module_access_matrix (V120's denyUnregisteredApiRoutes default). Both verification routes
-- are intentionally public: a phone number isn't yet a tenant, a user, or anything else that
-- could hold a permission.

INSERT INTO module_access_matrix (
    module_id, screen_key, screen_label, http_method, api_pattern,
    permission_code, route_scope, guard_mode, access_scope,
    is_tanzania_v1, is_enabled_by_default, notes
) VALUES
    (
        'tenant_admin', 'verifications.request', 'Request Verification Code',
        'POST', '/api/verifications',
        NULL, 'public', 'public_token', 'tenant',
        true, true,
        'Public: issues a purpose-bound OTP challenge (phone verification, tenant/account activation, guest phone verification). Rate-limited; never returns the code.'
    ),
    (
        'tenant_admin', 'verifications.confirm', 'Confirm Verification Code',
        'POST', '/api/verifications/confirm',
        NULL, 'public', 'public_token', 'tenant',
        true, true,
        'Public: confirms a purpose-bound OTP challenge. Attempt-budgeted and single-use.'
    )
ON CONFLICT (module_id, screen_key, http_method, api_pattern)
WHERE permission_code IS NULL
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
        WHERE api_pattern = '/api/verifications'
          AND http_method = 'POST'
          AND permission_code IS NULL
          AND guard_mode = 'public_token'
          AND route_scope = 'public'
    ) THEN
        RAISE EXCEPTION 'verification request route is missing from module_access_matrix';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM module_access_matrix
        WHERE api_pattern = '/api/verifications/confirm'
          AND http_method = 'POST'
          AND permission_code IS NULL
          AND guard_mode = 'public_token'
          AND route_scope = 'public'
    ) THEN
        RAISE EXCEPTION 'verification confirm route is missing from module_access_matrix';
    END IF;
END;
$migration$;
