-- Tenant-governed tenant administrator continuity and recovery.

-- Preserve mutable V24-V63 collisions under unambiguous legacy identifiers so
-- platform provisioning and tenant succession can rely on the canonical system role.
UPDATE tenant_roles
SET code = 'tenant_admin_legacy_' || replace(id::text, '-', ''),
    updated_at = now()
WHERE code = 'tenant_admin'
  AND is_system = false;

UPDATE tenant_roles
SET name = 'Tenant Administrator (Legacy ' || id::text || ')',
    updated_at = now()
WHERE lower(name) = lower('Tenant Administrator')
  AND is_system = false;

INSERT INTO permission_catalog (
    code,
    namespace,
    access_scope,
    description,
    is_platform_permission,
    is_tenant_permission
) VALUES (
    'tenant.administrators.manage',
    'tenant',
    'tenant',
    'Appoint and revoke system tenant administrators without orphaning a tenant',
    false,
    true
)
ON CONFLICT (code) DO UPDATE SET
    namespace = EXCLUDED.namespace,
    access_scope = EXCLUDED.access_scope,
    description = EXCLUDED.description,
    is_platform_permission = EXCLUDED.is_platform_permission,
    is_tenant_permission = EXCLUDED.is_tenant_permission,
    updated_at = now();

INSERT INTO permissions (id, tenant_id, code, description)
SELECT gen_random_uuid(), t.id, pc.code, pc.description
FROM tenants t
JOIN permission_catalog pc
  ON pc.code = 'tenant.administrators.manage'
WHERE t.deleted_at IS NULL
ON CONFLICT (tenant_id, code) DO UPDATE SET
    description = EXCLUDED.description,
    updated_at = now();

INSERT INTO tenant_role_permissions (tenant_role_id, permission_id)
SELECT tr.id, administrator_permission.id
FROM tenant_roles tr
JOIN permissions administrator_permission
  ON administrator_permission.tenant_id = tr.tenant_id
 AND administrator_permission.code = 'tenant.administrators.manage'
WHERE tr.is_active = true
  AND (
      (tr.code = 'tenant_admin' AND tr.is_system = true)
      OR EXISTS (
          SELECT 1
          FROM tenant_role_permissions trp
          JOIN permissions p
            ON p.id = trp.permission_id
           AND p.tenant_id = tr.tenant_id
          WHERE trp.tenant_role_id = tr.id
            AND p.code = 'tenant.admin.all'
      )
  )
ON CONFLICT ON CONSTRAINT tenant_role_permissions_pkey DO NOTHING;

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
        'tenant.administrators.list',
        'Tenant Administrators',
        'GET',
        '/api/tenants/:tenantId/administrators',
        'tenant.roles.view',
        'tenant',
        'staff_permission',
        'tenant',
        true,
        true,
        'List system tenant administrator assignments and effective account access state'
    ),
    (
        'tenant_admin',
        'tenant.administrators.assign',
        'Assign Tenant Administrator',
        'POST',
        '/api/tenants/:tenantId/administrators/:userId/assign',
        'tenant.administrators.manage',
        'tenant',
        'staff_permission',
        'tenant',
        true,
        true,
        'Appoint an active tenant user to the immutable system Tenant Administrator role'
    ),
    (
        'tenant_admin',
        'tenant.administrators.revoke',
        'Revoke Tenant Administrator',
        'POST',
        '/api/tenants/:tenantId/administrators/:userId/revoke',
        'tenant.administrators.manage',
        'tenant',
        'staff_permission',
        'tenant',
        true,
        true,
        'Revoke a system Tenant Administrator assignment only when another active administrator remains'
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
