-- ================================================================================
-- Tenant user role management route contracts
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
        'tenant.users.roles.list',
        'Tenant Roles',
        'GET',
        '/api/tenants/:tenantId/roles',
        'tenant.users.manage',
        'tenant',
        'staff_permission',
        'tenant',
        true,
        true,
        'List tenant roles and their assigned permissions'
    ),
    (
        'tenant_admin',
        'tenant.users.permissions.list',
        'Tenant Permissions',
        'GET',
        '/api/tenants/:tenantId/permissions',
        'tenant.users.manage',
        'tenant',
        'staff_permission',
        'tenant',
        true,
        true,
        'List tenant permission codes available for tenant roles'
    ),
    (
        'tenant_admin',
        'tenant.users.roles.assign',
        'Assign Tenant User Role',
        'POST',
        '/api/tenants/:tenantId/users/:userId/roles/:tenantRoleId/assign',
        'tenant.users.manage',
        'tenant',
        'staff_permission',
        'tenant',
        true,
        true,
        'Assign an active tenant role to an active tenant user'
    ),
    (
        'tenant_admin',
        'tenant.users.roles.revoke',
        'Revoke Tenant User Role',
        'POST',
        '/api/tenants/:tenantId/users/:userId/roles/:tenantRoleId/revoke',
        'tenant.users.manage',
        'tenant',
        'staff_permission',
        'tenant',
        true,
        true,
        'Revoke a tenant role assignment from a tenant user'
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
