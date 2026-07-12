-- Resource read routes must not inherit broader mutating permissions from
-- legacy ANY matrix rows. Exact GET rows win in the route matcher and keep
-- read-only operators from needing manage permissions for resource detail
-- views.

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
        'inventory.locations.view',
        'Inventory Location',
        'GET',
        '/api/properties/:propertyId/inventory/locations/:locationId',
        'inventory.view',
        'property',
        'staff_permission',
        'property',
        true,
        true,
        'View one inventory location without requiring inventory mutation permission'
    ),
    (
        'procurement',
        'procurement.orders.view',
        'Purchase Order',
        'GET',
        '/api/properties/:propertyId/purchase-orders/:purchaseOrderId',
        'procurement.view',
        'property',
        'staff_permission',
        'property',
        true,
        true,
        'View one purchase order without requiring procurement mutation permission'
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
