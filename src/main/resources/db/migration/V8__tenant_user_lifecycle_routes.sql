-- ================================================================================
-- Tenant user lifecycle route contracts
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
        'tenant_admin',
        'tenant.users.lifecycle.disable',
        'Disable Tenant User',
        'POST',
        '/api/tenants/:tenantId/users/:userId/disable',
        'tenant.users.manage',
        'tenant',
        'staff_permission',
        'tenant',
        true,
        true,
        'Disable a tenant user and prevent future OIDC identity resolution'
    ),
    (
        'tenant_admin',
        'tenant.users.lifecycle.reactivate',
        'Reactivate Tenant User',
        'POST',
        '/api/tenants/:tenantId/users/:userId/reactivate',
        'tenant.users.manage',
        'tenant',
        'staff_permission',
        'tenant',
        true,
        true,
        'Reactivate a disabled or locked tenant user'
    ),
    (
        'tenant_admin',
        'tenant.users.lifecycle.lock',
        'Lock Tenant User',
        'POST',
        '/api/tenants/:tenantId/users/:userId/lock',
        'tenant.users.manage',
        'tenant',
        'staff_permission',
        'tenant',
        true,
        true,
        'Lock a tenant user and prevent future OIDC identity resolution until unlocked'
    ),
    (
        'tenant_admin',
        'tenant.users.lifecycle.unlock',
        'Unlock Tenant User',
        'POST',
        '/api/tenants/:tenantId/users/:userId/unlock',
        'tenant.users.manage',
        'tenant',
        'staff_permission',
        'tenant',
        true,
        true,
        'Unlock a locked tenant user'
    ),
    (
        'tenant_admin',
        'tenant.users.identity_links.revoke',
        'Revoke Tenant User Identity Link',
        'POST',
        '/api/tenants/:tenantId/users/:userId/identity-links/:identityLinkId/revoke',
        'tenant.users.manage',
        'tenant',
        'staff_permission',
        'tenant',
        true,
        true,
        'Revoke a tenant user OIDC identity link'
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
