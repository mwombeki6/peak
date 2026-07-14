-- Platform-root succession, continuity, and reserved-role contracts.

UPDATE platform_roles
SET code = 'platform_root_legacy_' || replace(id::text, '-', ''),
    updated_at = now()
WHERE code = 'platform_root'
  AND is_system = false;

UPDATE platform_roles
SET name = 'Platform Root (Legacy ' || id::text || ')',
    updated_at = now()
WHERE lower(name) = lower('Platform Root')
  AND is_system = false;

INSERT INTO permission_catalog (
    code,
    namespace,
    access_scope,
    description,
    is_platform_permission,
    is_tenant_permission
) VALUES (
    'platform.administrators.manage',
    'platform',
    'platform',
    'Appoint and revoke system platform administrators without orphaning the platform',
    true,
    false
)
ON CONFLICT (code) DO UPDATE SET
    namespace = EXCLUDED.namespace,
    access_scope = EXCLUDED.access_scope,
    description = EXCLUDED.description,
    is_platform_permission = EXCLUDED.is_platform_permission,
    is_tenant_permission = EXCLUDED.is_tenant_permission,
    updated_at = now();

INSERT INTO platform_permissions (code, namespace, description) VALUES (
    'platform.administrators.manage',
    'security',
    'Appoint and revoke system platform administrators without orphaning the platform'
)
ON CONFLICT (code) DO UPDATE SET
    namespace = EXCLUDED.namespace,
    description = EXCLUDED.description,
    updated_at = now();

INSERT INTO platform_role_permissions (platform_role_id, platform_permission_id)
SELECT pr.id, administrator_permission.id
FROM platform_roles pr
JOIN platform_permissions administrator_permission
  ON administrator_permission.code = 'platform.administrators.manage'
WHERE pr.is_active = true
  AND (
      (pr.code = 'platform_root' AND pr.is_system = true)
      OR EXISTS (
          SELECT 1
          FROM platform_role_permissions prp
          JOIN platform_permissions pp
            ON pp.id = prp.platform_permission_id
          WHERE prp.platform_role_id = pr.id
            AND pp.code = 'platform.admin.all'
      )
  )
ON CONFLICT ON CONSTRAINT platform_role_permissions_pkey DO NOTHING;

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
        'platform.administrators.list',
        'Platform Administrators',
        'GET',
        '/api/platform/administrators',
        'platform.roles.view',
        'platform',
        'platform_permission',
        'platform',
        true,
        true,
        'List system platform administrators and their effective account access state'
    ),
    (
        'platform_admin',
        'platform.administrators.assign',
        'Assign Platform Administrator',
        'POST',
        '/api/platform/administrators/:platformUserId/assign',
        'platform.administrators.manage',
        'platform',
        'platform_permission',
        'platform',
        true,
        true,
        'Appoint an active platform user to the immutable system Platform Root role'
    ),
    (
        'platform_admin',
        'platform.administrators.revoke',
        'Revoke Platform Administrator',
        'POST',
        '/api/platform/administrators/:platformUserId/revoke',
        'platform.administrators.manage',
        'platform',
        'platform_permission',
        'platform',
        true,
        true,
        'Revoke Platform Root only after another effective platform administrator can sign in'
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
