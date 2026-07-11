-- ================================================================================
-- Platform permission granularity with platform.security.manage compatibility
-- ================================================================================

INSERT INTO permission_catalog (
    code,
    namespace,
    access_scope,
    description,
    is_platform_permission,
    is_tenant_permission
) VALUES
    ('platform.users.view', 'platform', 'platform', 'View platform users and identity status', true, false),
    ('platform.users.manage', 'platform', 'platform', 'Manage platform users and lifecycle state', true, false),
    ('platform.roles.view', 'platform', 'platform', 'View platform roles and permission grants', true, false),
    ('platform.roles.manage', 'platform', 'platform', 'Manage platform roles and role assignments', true, false),
    ('platform.permissions.view', 'platform', 'platform', 'View platform permission catalog', true, false),
    ('platform.identity_links.manage', 'platform', 'platform', 'Manage platform OIDC identity links', true, false)
ON CONFLICT (code) DO UPDATE SET
    namespace = EXCLUDED.namespace,
    access_scope = EXCLUDED.access_scope,
    description = EXCLUDED.description,
    is_platform_permission = EXCLUDED.is_platform_permission,
    is_tenant_permission = EXCLUDED.is_tenant_permission,
    updated_at = now();

INSERT INTO platform_permissions (code, namespace, description) VALUES
    ('platform.users.view', 'security', 'View platform users and identity status'),
    ('platform.users.manage', 'security', 'Manage platform users and lifecycle state'),
    ('platform.roles.view', 'security', 'View platform roles and permission grants'),
    ('platform.roles.manage', 'security', 'Manage platform roles and role assignments'),
    ('platform.permissions.view', 'security', 'View platform permission catalog'),
    ('platform.identity_links.manage', 'security', 'Manage platform OIDC identity links')
ON CONFLICT (code) DO UPDATE SET
    namespace = EXCLUDED.namespace,
    description = EXCLUDED.description,
    updated_at = now();

INSERT INTO platform_role_permissions (platform_role_id, platform_permission_id)
SELECT pr.id, pp.id
FROM platform_roles pr
JOIN platform_permissions pp
  ON pp.code IN (
      'platform.users.view',
      'platform.users.manage',
      'platform.roles.view',
      'platform.roles.manage',
      'platform.permissions.view',
      'platform.identity_links.manage'
  )
WHERE pr.is_system = true
  AND pr.is_active = true
ON CONFLICT ON CONSTRAINT platform_role_permissions_pkey DO NOTHING;

CREATE OR REPLACE FUNCTION platform_user_has_permission(
    p_platform_user_id uuid,
    p_permission_code text
) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = public
    AS $$
  SELECT
    p_platform_user_id = current_platform_user_id()
    AND current_tenant_id() IS NULL
    AND EXISTS (
      SELECT 1
      FROM platform_users pu
      JOIN platform_user_roles pur
        ON pur.platform_user_id = pu.id
      JOIN platform_roles pr
        ON pr.id = pur.platform_role_id
      JOIN platform_role_permissions prp
        ON prp.platform_role_id = pr.id
      JOIN platform_permissions pp
        ON pp.id = prp.platform_permission_id
      WHERE pu.id = p_platform_user_id
        AND pu.status = 'active'
        AND pu.deleted_at IS NULL
        AND (pu.locked_until IS NULL OR pu.locked_until <= now())
        AND pr.is_active = true
        AND (
            pp.code = p_permission_code
            OR pp.code = 'platform.admin.all'
            OR (
                pp.code = 'platform.security.manage'
                AND p_permission_code IN (
                    'platform.users.view',
                    'platform.users.manage',
                    'platform.roles.view',
                    'platform.roles.manage',
                    'platform.permissions.view',
                    'platform.identity_links.manage'
                )
            )
        )
    );
$$;

REVOKE EXECUTE ON FUNCTION platform_user_has_permission(uuid, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_user_has_permission(uuid, text) TO pms_platform;

UPDATE module_access_matrix
SET permission_code = 'platform.users.manage',
    notes = 'Manage platform users and lifecycle state; GET routes have a more specific view contract',
    updated_at = now()
WHERE module_id = 'platform_admin'
  AND screen_key = 'platform.security.users'
  AND api_pattern = '/api/platform/users*';

UPDATE module_access_matrix
SET permission_code = 'platform.roles.manage',
    notes = 'Manage platform roles and assignments; GET routes have a more specific view contract',
    updated_at = now()
WHERE module_id = 'platform_admin'
  AND screen_key = 'platform.security.roles'
  AND api_pattern = '/api/platform/roles*';

UPDATE module_access_matrix
SET permission_code = 'platform.permissions.view',
    notes = 'List immutable platform permissions available for platform role configuration',
    updated_at = now()
WHERE module_id = 'platform_admin'
  AND screen_key = 'platform.security.permissions'
  AND api_pattern = '/api/platform/permissions';

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
        'platform.users.view',
        'Platform Users',
        'GET',
        '/api/platform/users*',
        'platform.users.view',
        'platform',
        'platform_permission',
        'platform',
        true,
        true,
        'View platform users and identity status'
    ),
    (
        'platform_admin',
        'platform.roles.view',
        'Platform Roles',
        'GET',
        '/api/platform/roles*',
        'platform.roles.view',
        'platform',
        'platform_permission',
        'platform',
        true,
        true,
        'View platform roles and permission grants'
    ),
    (
        'platform_admin',
        'platform.identity_links.manage',
        'Platform Identity Links',
        'ANY',
        '/api/platform/users/:platformUserId/identity-links*',
        'platform.identity_links.manage',
        'platform',
        'platform_permission',
        'platform',
        true,
        true,
        'Manage platform OIDC identity links'
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
