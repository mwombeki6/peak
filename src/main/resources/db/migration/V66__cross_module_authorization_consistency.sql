-- Cross-module route authorization, support-session scope, and report permission consistency.

CREATE OR REPLACE FUNCTION can_support_session_access_tenant(
    p_platform_user_id uuid,
    p_support_session_id uuid,
    p_tenant_id uuid,
    p_action_code text
) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = public
    AS $$
  SELECT
    current_tenant_id() IS NULL
    AND p_support_session_id IS NOT NULL
    AND platform_user_has_permission(p_platform_user_id, p_action_code)
    AND EXISTS (
      SELECT 1
      FROM tenants tenant
      WHERE tenant.id = p_tenant_id
        AND tenant.deleted_at IS NULL
    )
    AND EXISTS (
      SELECT 1
      FROM platform_break_glass_access access
      WHERE access.id = p_support_session_id
        AND access.platform_user_id = p_platform_user_id
        AND access.tenant_id = p_tenant_id
        AND access.action_code = p_action_code
        AND access.status = 'active'
        AND access.approved_by IS NOT NULL
        AND access.approved_at IS NOT NULL
        AND access.activated_at IS NOT NULL
        AND access.activated_at <= now()
        AND access.revoked_at IS NULL
        AND access.starts_at <= now()
        AND access.expires_at > now()
    );
$$;

REVOKE EXECUTE ON FUNCTION can_support_session_access_tenant(
    uuid, uuid, uuid, text
) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION can_support_session_access_tenant(
    uuid, uuid, uuid, text
) TO pms_platform;

COMMENT ON FUNCTION can_support_session_access_tenant(uuid, uuid, uuid, text) IS
    'Checks the exact approved support session, tenant scope, platform permission, and active time window.';

UPDATE module_access_matrix
SET api_pattern = '/api/platform/tenants/:tenantId',
    notes = 'View one tenant; support identities require an exact active tenant-scoped session',
    updated_at = now()
WHERE module_id = 'platform_admin'
  AND screen_key = 'platform.tenants.view'
  AND http_method = 'GET'
  AND api_pattern = '/api/platform/tenants/:id';

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
        'platform.tenants.approve',
        'Approve Tenant',
        'POST',
        '/api/platform/tenants/:tenantId/approve',
        'platform.tenants.manage',
        'platform',
        'platform_permission',
        'platform',
        true,
        true,
        'Activate a tenant; support identities require an exact active tenant-scoped session'
    ),
    (
        'platform_admin',
        'platform.tenants.suspend',
        'Suspend Tenant',
        'POST',
        '/api/platform/tenants/:tenantId/suspend',
        'platform.tenants.manage',
        'platform',
        'platform_permission',
        'platform',
        true,
        true,
        'Suspend a tenant; support identities require an exact active tenant-scoped session'
    )
ON CONFLICT (
    module_id, screen_key, http_method, api_pattern, permission_code
) DO UPDATE SET
    screen_label = EXCLUDED.screen_label,
    route_scope = EXCLUDED.route_scope,
    guard_mode = EXCLUDED.guard_mode,
    access_scope = EXCLUDED.access_scope,
    is_tanzania_v1 = EXCLUDED.is_tanzania_v1,
    is_enabled_by_default = EXCLUDED.is_enabled_by_default,
    notes = EXCLUDED.notes,
    updated_at = now();

INSERT INTO permissions (id, tenant_id, code, description)
SELECT gen_random_uuid(), tenant.id, catalog.code, catalog.description
FROM tenants tenant
JOIN permission_catalog catalog
  ON catalog.code = 'reports.generate'
WHERE tenant.deleted_at IS NULL
ON CONFLICT ON CONSTRAINT permissions_tenant_id_code_key
DO UPDATE SET
    description = EXCLUDED.description,
    updated_at = now();

INSERT INTO tenant_role_permissions (tenant_role_id, permission_id)
SELECT old_grant.tenant_role_id, new_permission.id
FROM tenant_role_permissions old_grant
JOIN permissions old_permission
  ON old_permission.id = old_grant.permission_id
 AND old_permission.code = 'reports.manual_generate'
JOIN permissions new_permission
  ON new_permission.tenant_id = old_permission.tenant_id
 AND new_permission.code = 'reports.generate'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT old_grant.role_id, new_permission.id
FROM role_permissions old_grant
JOIN roles role
  ON role.id = old_grant.role_id
JOIN permissions old_permission
  ON old_permission.id = old_grant.permission_id
 AND old_permission.tenant_id = role.tenant_id
 AND old_permission.code = 'reports.manual_generate'
JOIN permissions new_permission
  ON new_permission.tenant_id = role.tenant_id
 AND new_permission.code = 'reports.generate'
ON CONFLICT DO NOTHING;

UPDATE module_access_matrix
SET is_enabled_by_default = false,
    notes = concat_ws(
        ' ',
        nullif(notes, ''),
        'Disabled by V66 in favor of the canonical Phase 5 reporting route contract.'
    ),
    updated_at = now()
WHERE module_id = 'reports'
  AND screen_key IN (
      'reports.manual_generate.tenant',
      'reports.manual_generate.property',
      'reports.subscriptions.tenant.manage',
      'reports.subscriptions.property.manage',
      'reports.delivery.retry'
  );

UPDATE workflow_steps
SET permission_code = 'reports.generate',
    api_pattern = '/api/properties/:propertyId/reports/:reportCode/runs'
WHERE workflow_code = 'management_report_delivery'
  AND step_key = 'generate';
