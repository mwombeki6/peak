-- ================================================================================
-- Property RBAC bootstrap contracts
-- ================================================================================

UPDATE roles
SET is_system = true,
    is_active = true,
    updated_at = now()
WHERE name = 'Property Administrator'
  AND is_system = false;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p
  ON p.tenant_id = r.tenant_id
 AND p.code IN (
      'admin.all',
      'property.view',
      'property.manage',
      'property.lifecycle',
      'property.roles.view',
      'property.roles.manage',
      'realtime.stream'
 )
WHERE r.name = 'Property Administrator'
  AND r.is_system = true
  AND r.is_active = true
ON CONFLICT ON CONSTRAINT role_permissions_pkey DO NOTHING;

COMMENT ON TABLE roles IS
    'Tenant-owned property role templates. Assignments are scoped to properties through user_property_roles.';

COMMENT ON TABLE user_property_roles IS
    'Property-scoped user role assignments using tenant-owned property role templates.';
