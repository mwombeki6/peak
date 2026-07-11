-- Tenant-wide inventory items and suppliers are master data, even when managed
-- through property-context routes. Writes require tenant-role permissions; reads
-- remain property-scoped for operational use.

INSERT INTO permission_catalog (
    code,
    namespace,
    access_scope,
    description,
    is_platform_permission,
    is_tenant_permission
) VALUES
    (
        'inventory.catalog.manage',
        'inventory',
        'tenant',
        'Manage tenant-wide inventory item catalog entries',
        false,
        true
    ),
    (
        'procurement.suppliers.manage',
        'procurement',
        'tenant',
        'Manage tenant-wide procurement suppliers',
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
  ON pc.code IN (
      'inventory.catalog.manage',
      'procurement.suppliers.manage'
  )
WHERE t.deleted_at IS NULL
ON CONFLICT (tenant_id, code) DO UPDATE SET
    description = EXCLUDED.description,
    updated_at = now();

INSERT INTO tenant_role_permissions (tenant_role_id, permission_id)
SELECT tr.id, p.id
FROM tenant_roles tr
JOIN permissions p
  ON p.tenant_id = tr.tenant_id
 AND p.code IN (
      'inventory.catalog.manage',
      'procurement.suppliers.manage'
 )
WHERE tr.is_active = true
  AND (
      tr.code = 'tenant_admin'
      OR EXISTS (
          SELECT 1
          FROM tenant_role_permissions trp
          JOIN permissions admin_permission
            ON admin_permission.id = trp.permission_id
           AND admin_permission.tenant_id = tr.tenant_id
          WHERE trp.tenant_role_id = tr.id
            AND admin_permission.code = 'tenant.admin.all'
      )
  )
ON CONFLICT ON CONSTRAINT tenant_role_permissions_pkey DO NOTHING;

UPDATE module_access_matrix
SET permission_code = 'inventory.catalog.manage',
    route_scope = 'tenant',
    access_scope = 'tenant',
    notes = 'Create tenant-wide inventory catalog entries through a property context',
    updated_at = now()
WHERE screen_key = 'inventory.items.create'
  AND http_method = 'POST'
  AND api_pattern = '/api/properties/:propertyId/inventory/items';

UPDATE module_access_matrix
SET permission_code = 'procurement.suppliers.manage',
    route_scope = 'tenant',
    access_scope = 'tenant',
    notes = 'Create tenant-wide suppliers through a property context',
    updated_at = now()
WHERE screen_key = 'procurement.suppliers.create'
  AND http_method = 'POST'
  AND api_pattern = '/api/properties/:propertyId/procurement/suppliers';

UPDATE module_access_matrix
SET is_enabled_by_default = false,
    notes = 'Superseded by method-specific read and tenant-wide catalog write routes',
    updated_at = now()
WHERE screen_key IN (
    'inventory.items.resource',
    'procurement.suppliers.resource'
)
  AND http_method = 'ANY';

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
        'inventory',
        'inventory.items.view',
        'Inventory Item',
        'GET',
        '/api/properties/:propertyId/inventory/items/:itemId',
        'inventory.view',
        'property',
        'staff_permission',
        'property',
        true,
        true,
        'View inventory catalog item in a property context'
    ),
    (
        'inventory',
        'inventory.items.update_catalog',
        'Update Inventory Item Catalog Entry',
        'PUT',
        '/api/properties/:propertyId/inventory/items/:itemId',
        'inventory.catalog.manage',
        'tenant',
        'staff_permission',
        'tenant',
        true,
        true,
        'Update tenant-wide inventory catalog item through a property context'
    ),
    (
        'inventory',
        'inventory.items.deactivate_catalog',
        'Deactivate Inventory Item Catalog Entry',
        'DELETE',
        '/api/properties/:propertyId/inventory/items/:itemId',
        'inventory.catalog.manage',
        'tenant',
        'staff_permission',
        'tenant',
        true,
        true,
        'Deactivate tenant-wide inventory catalog item through a property context'
    ),
    (
        'procurement',
        'procurement.suppliers.view',
        'Supplier',
        'GET',
        '/api/properties/:propertyId/procurement/suppliers/:supplierId',
        'procurement.view',
        'property',
        'staff_permission',
        'property',
        true,
        true,
        'View tenant supplier in a property context'
    ),
    (
        'procurement',
        'procurement.suppliers.update_catalog',
        'Update Supplier',
        'PUT',
        '/api/properties/:propertyId/procurement/suppliers/:supplierId',
        'procurement.suppliers.manage',
        'tenant',
        'staff_permission',
        'tenant',
        true,
        true,
        'Update tenant-wide supplier through a property context'
    ),
    (
        'procurement',
        'procurement.suppliers.deactivate_catalog',
        'Deactivate Supplier',
        'DELETE',
        '/api/properties/:propertyId/procurement/suppliers/:supplierId',
        'procurement.suppliers.manage',
        'tenant',
        'staff_permission',
        'tenant',
        true,
        true,
        'Deactivate tenant-wide supplier through a property context'
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
