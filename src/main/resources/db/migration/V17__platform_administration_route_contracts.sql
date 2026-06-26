-- ================================================================================
-- Platform administration API route contracts
-- ================================================================================

INSERT INTO module_access_matrix (
    module_id,
    screen_key,
    screen_label,
    http_method,
    api_pattern,
    permission_code,
    route_scope,
    guard_mode,
    access_scope,
    is_tanzania_v1,
    is_enabled_by_default,
    notes
) VALUES
    (
        'platform_admin',
        'platform.security.users',
        'Platform Users',
        'ANY',
        '/api/platform/users*',
        'platform.security.manage',
        'platform',
        'platform_permission',
        'platform',
        true,
        true,
        'Create, view, update, lock, disable, reactivate, and manage OIDC links for platform users'
    ),
    (
        'platform_admin',
        'platform.security.roles',
        'Platform Roles',
        'ANY',
        '/api/platform/roles*',
        'platform.security.manage',
        'platform',
        'platform_permission',
        'platform',
        true,
        true,
        'Create, view, update, deactivate, assign, and revoke platform roles'
    ),
    (
        'platform_admin',
        'platform.security.permissions',
        'Platform Permissions',
        'GET',
        '/api/platform/permissions',
        'platform.security.manage',
        'platform',
        'platform_permission',
        'platform',
        true,
        true,
        'List immutable platform permissions available for platform role configuration'
    )
ON CONFLICT (module_id, screen_key, http_method, api_pattern, permission_code)
DO UPDATE SET
    screen_label = EXCLUDED.screen_label,
    route_scope = EXCLUDED.route_scope,
    guard_mode = EXCLUDED.guard_mode,
    access_scope = EXCLUDED.access_scope,
    is_tanzania_v1 = EXCLUDED.is_tanzania_v1,
    is_enabled_by_default = EXCLUDED.is_enabled_by_default,
    notes = EXCLUDED.notes,
    updated_at = now();
