-- V141 — the last two reads the onboarding wizard needs.
--
-- A manager can approve a till and hire a waiter, and can then see neither. The
-- wizard's manager step is the sharper problem: it must name a real user id and
-- has no way to discover one, so the step cannot be completed from the UI at all.
--
-- Both routes mirror the write that already exists beside them — tenant-scoped,
-- same path family — rather than inventing a property-scoped shape that would sit
-- oddly next to POST /api/tenants/:tenantId/staff.
--
-- Neither reuses its sibling's mutation permission. Approving a pairing and seeing
-- which tills are paired are different acts, and a duty manager doing an evening
-- floor check should not need the permission that revokes a terminal. V61
-- established this exact separation for module and role reads; these follow it.

INSERT INTO permission_catalog (
    code, namespace, access_scope, description,
    is_platform_permission, is_tenant_permission
) VALUES
    (
        'admin.devices.view',
        'admin',
        'tenant',
        'View paired devices and their pairing state',
        false,
        true
    ),
    (
        'tenant.users.view',
        'tenant',
        'tenant',
        'View the tenant staff directory',
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
  ON pc.code IN ('admin.devices.view', 'tenant.users.view')
WHERE t.deleted_at IS NULL
ON CONFLICT (tenant_id, code) DO UPDATE SET
    description = EXCLUDED.description,
    updated_at = now();

-- Granted only to roles that already hold the matching mutation permission, so no
-- role gains reach it did not have: anyone who could already change these things
-- can now also look at them. A role with neither is untouched.
WITH inherited_view_permissions(view_permission_code, source_permission_code) AS (
    VALUES
        ('admin.devices.view', 'admin.devices.manage'),
        ('tenant.users.view', 'tenant.users.manage')
)
INSERT INTO tenant_role_permissions (tenant_role_id, permission_id)
SELECT DISTINCT tr.id, view_permission.id
FROM tenant_roles tr
JOIN tenant_role_permissions source_assignment
  ON source_assignment.tenant_role_id = tr.id
JOIN permissions source_permission
  ON source_permission.id = source_assignment.permission_id
 AND source_permission.tenant_id = tr.tenant_id
JOIN inherited_view_permissions inherited
  ON source_permission.code IN (
      inherited.source_permission_code,
      'tenant.admin.all'
  )
JOIN permissions view_permission
  ON view_permission.tenant_id = tr.tenant_id
 AND view_permission.code = inherited.view_permission_code
WHERE tr.is_active = true
ON CONFLICT ON CONSTRAINT tenant_role_permissions_pkey DO NOTHING;

INSERT INTO module_access_matrix (
    module_id, screen_key, screen_label, http_method, api_pattern,
    permission_code, route_scope, guard_mode, access_scope,
    is_tanzania_v1, is_enabled_by_default, notes
) VALUES
    (
        'tenant_admin', 'devices.list', 'Paired Devices',
        'GET', '/api/tenants/:tenantId/devices',
        'admin.devices.view', 'tenant', 'staff_permission', 'tenant',
        true, true,
        'Paired tills and displays for a tenant, optionally narrowed to one property. Read only; approving and revoking stay on admin.devices.manage.'
    ),
    (
        'tenant_admin', 'staff.list', 'Staff Directory',
        'GET', '/api/tenants/:tenantId/staff',
        'tenant.users.view', 'tenant', 'staff_permission', 'tenant',
        true, true,
        'Tenant staff directory. Resolves a real user id for the onboarding manager step, which cannot be completed without one.'
    )
ON CONFLICT (
    module_id,
    screen_key,
    http_method,
    api_pattern,
    permission_code
) DO UPDATE SET
    screen_label = EXCLUDED.screen_label,
    route_scope = EXCLUDED.route_scope,
    guard_mode = EXCLUDED.guard_mode,
    access_scope = EXCLUDED.access_scope,
    is_tanzania_v1 = EXCLUDED.is_tanzania_v1,
    is_enabled_by_default = EXCLUDED.is_enabled_by_default,
    notes = EXCLUDED.notes,
    updated_at = now();

DO $migration$
DECLARE
    missing text;
BEGIN
    SELECT string_agg(expected.code, ', ')
    INTO missing
    FROM (VALUES ('admin.devices.view'), ('tenant.users.view')) AS expected(code)
    WHERE NOT EXISTS (
        SELECT 1 FROM permission_catalog pc WHERE pc.code = expected.code
    );
    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'V141 did not register permissions: %', missing;
    END IF;

    SELECT string_agg(expected.pattern, ', ')
    INTO missing
    FROM (
        VALUES ('/api/tenants/:tenantId/devices'), ('/api/tenants/:tenantId/staff')
    ) AS expected(pattern)
    WHERE NOT EXISTS (
        SELECT 1
        FROM module_access_matrix m
        WHERE m.api_pattern = expected.pattern
          AND m.http_method = 'GET'
    );
    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'V141 did not register GET routes: %', missing;
    END IF;
END
$migration$;
