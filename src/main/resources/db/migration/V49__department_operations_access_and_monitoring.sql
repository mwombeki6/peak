-- Phase 4: route contracts, least privilege grants, summaries, and monitoring.

ALTER TABLE outbox_events
    DROP CONSTRAINT IF EXISTS chk_outbox_events_destination,
    ADD CONSTRAINT chk_outbox_events_destination CHECK (
        destination IN (
            'fiscal', 'payment', 'notification', 'analytics', 'audit',
            'edge_sync', 'webhook', 'email', 'sms', 'whatsapp', 'pos',
            'housekeeping', 'platform'
        )
    );

INSERT INTO permission_catalog (
    code, namespace, access_scope, description,
    is_platform_permission, is_tenant_permission
) VALUES
    ('housekeeping.view', 'housekeeping', 'property', 'View housekeeping board and tasks', false, true),
    ('housekeeping.manage', 'housekeeping', 'property', 'Create, assign, and progress housekeeping tasks', false, true),
    ('housekeeping.inspect', 'housekeeping', 'property', 'Independently inspect cleaned rooms', false, true),
    ('lost_found.view', 'housekeeping', 'property', 'View lost-and-found custody records', false, true),
    ('lost_found.manage', 'housekeeping', 'property', 'Record and transition lost-and-found custody', false, true),
    ('maintenance.view', 'maintenance', 'property', 'View corrective maintenance', false, true),
    ('maintenance.manage', 'maintenance', 'property', 'Manage corrective requests and work orders', false, true),
    ('maintenance.room_block', 'maintenance', 'property', 'Block and release rooms for maintenance', false, true),
    ('inventory.view', 'inventory', 'property', 'View items, recipes, stock, and movements', false, true),
    ('inventory.manage', 'inventory', 'property', 'Manage inventory items, locations, and recipes', false, true),
    ('inventory.adjust', 'inventory', 'property', 'Post balances, adjustments, waste, and transfers', false, true),
    ('procurement.view', 'procurement', 'property', 'View suppliers, purchase orders, and receipts', false, true),
    ('procurement.manage', 'procurement', 'property', 'Manage suppliers and draft purchase orders', false, true),
    ('procurement.approve', 'procurement', 'property', 'Approve or reject submitted purchase orders', false, true),
    ('procurement.receive', 'procurement', 'property', 'Receive approved purchase orders into stock', false, true),
    ('pos.kitchen.view', 'pos', 'property', 'View kitchen display tickets', false, true),
    ('pos.kitchen.manage', 'pos', 'property', 'Progress and void kitchen tickets', false, true),
    ('pos.item.void', 'pos', 'property', 'Void POS items with audited stock disposition', false, true)
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
JOIN permission_catalog pc ON pc.code IN (
    'housekeeping.view', 'housekeeping.manage', 'housekeeping.inspect',
    'lost_found.view', 'lost_found.manage',
    'maintenance.view', 'maintenance.manage', 'maintenance.room_block',
    'inventory.view', 'inventory.manage', 'inventory.adjust',
    'procurement.view', 'procurement.manage', 'procurement.approve',
    'procurement.receive', 'pos.kitchen.view', 'pos.kitchen.manage',
    'pos.item.void'
)
WHERE t.deleted_at IS NULL
ON CONFLICT ON CONSTRAINT permissions_tenant_id_code_key
DO UPDATE SET description = EXCLUDED.description, updated_at = now();

INSERT INTO tenant_role_permissions (tenant_role_id, permission_id)
SELECT tr.id, p.id
FROM tenant_roles tr
JOIN permissions p ON p.tenant_id = tr.tenant_id
WHERE tr.code = 'tenant_admin'
  AND tr.is_system
  AND p.code IN (
      'housekeeping.view', 'housekeeping.manage', 'housekeeping.inspect',
      'lost_found.view', 'lost_found.manage',
      'maintenance.view', 'maintenance.manage', 'maintenance.room_block',
      'inventory.view', 'inventory.manage', 'inventory.adjust',
      'procurement.view', 'procurement.manage', 'procurement.approve',
      'procurement.receive', 'pos.kitchen.view', 'pos.kitchen.manage',
      'pos.item.void'
  )
ON CONFLICT DO NOTHING;

INSERT INTO module_access_matrix (
    module_id, screen_key, screen_label, http_method, api_pattern,
    permission_code, route_scope, guard_mode, access_scope,
    is_tanzania_v1, is_enabled_by_default, notes
) VALUES
    ('housekeeping', 'housekeeping.settings.view', 'Housekeeping Settings', 'GET', '/api/properties/:propertyId/housekeeping/settings', 'housekeeping.view', 'property', 'staff_permission', 'property', true, true, 'View property housekeeping settings'),
    ('housekeeping', 'housekeeping.settings.update', 'Update Housekeeping Settings', 'PUT', '/api/properties/:propertyId/housekeeping/settings', 'housekeeping.manage', 'property', 'staff_permission', 'property', true, true, 'Configure cleaning and inspection'),
    ('housekeeping', 'housekeeping.board', 'Housekeeping Board', 'GET', '/api/properties/:propertyId/housekeeping/board', 'housekeeping.view', 'property', 'staff_permission', 'property', true, true, 'View daily housekeeping board'),
    ('housekeeping', 'housekeeping.tasks.create', 'Create Housekeeping Task', 'POST', '/api/properties/:propertyId/housekeeping/tasks', 'housekeeping.manage', 'property', 'staff_permission', 'property', true, true, 'Create a room task'),
    ('housekeeping', 'housekeeping.tasks.assign', 'Assign Housekeeping Task', 'POST', '/api/properties/:propertyId/housekeeping/tasks/:taskId/assign', 'housekeeping.manage', 'property', 'staff_permission', 'property', true, true, 'Assign room task'),
    ('housekeeping', 'housekeeping.tasks.start', 'Start Housekeeping Task', 'POST', '/api/properties/:propertyId/housekeeping/tasks/:taskId/start', 'housekeeping.manage', 'property', 'staff_permission', 'property', true, true, 'Start room task'),
    ('housekeeping', 'housekeeping.tasks.complete', 'Complete Housekeeping Task', 'POST', '/api/properties/:propertyId/housekeeping/tasks/:taskId/complete', 'housekeeping.manage', 'property', 'staff_permission', 'property', true, true, 'Complete cleaning work'),
    ('housekeeping', 'housekeeping.tasks.inspect', 'Inspect Housekeeping Task', 'POST', '/api/properties/:propertyId/housekeeping/tasks/:taskId/inspect', 'housekeeping.inspect', 'property', 'staff_permission', 'property', true, true, 'Approve or fail independent inspection'),
    ('housekeeping', 'housekeeping.tasks.skip', 'Skip Housekeeping Task', 'POST', '/api/properties/:propertyId/housekeeping/tasks/:taskId/skip', 'housekeeping.manage', 'property', 'staff_permission', 'property', true, true, 'Skip a pending task'),
    ('housekeeping', 'housekeeping.tasks.cancel', 'Cancel Housekeeping Task', 'POST', '/api/properties/:propertyId/housekeeping/tasks/:taskId/cancel', 'housekeeping.manage', 'property', 'staff_permission', 'property', true, true, 'Cancel a task'),
    ('housekeeping', 'lost_found.list', 'Lost and Found', 'GET', '/api/properties/:propertyId/lost-and-found', 'lost_found.view', 'property', 'staff_permission', 'property', true, true, 'View custody records'),
    ('housekeeping', 'lost_found.create', 'Record Lost Item', 'POST', '/api/properties/:propertyId/lost-and-found', 'lost_found.manage', 'property', 'staff_permission', 'property', true, true, 'Record found property'),
    ('housekeeping', 'lost_found.return', 'Return Lost Item', 'POST', '/api/properties/:propertyId/lost-and-found/:itemId/return', 'lost_found.manage', 'property', 'staff_permission', 'property', true, true, 'Return item to owner'),
    ('housekeeping', 'lost_found.claim', 'Claim Lost Item', 'POST', '/api/properties/:propertyId/lost-and-found/:itemId/claim', 'lost_found.manage', 'property', 'staff_permission', 'property', true, true, 'Record claimant'),
    ('housekeeping', 'lost_found.dispose', 'Dispose Lost Item', 'POST', '/api/properties/:propertyId/lost-and-found/:itemId/dispose', 'lost_found.manage', 'property', 'staff_permission', 'property', true, true, 'Dispose held item'),
    ('housekeeping', 'lost_found.donate', 'Donate Lost Item', 'POST', '/api/properties/:propertyId/lost-and-found/:itemId/donate', 'lost_found.manage', 'property', 'staff_permission', 'property', true, true, 'Donate held item'),

    ('maintenance', 'maintenance.requests.list', 'Maintenance Requests', 'GET', '/api/properties/:propertyId/maintenance/requests', 'maintenance.view', 'property', 'staff_permission', 'property', true, true, 'View maintenance requests'),
    ('maintenance', 'maintenance.requests.create', 'Create Maintenance Request', 'POST', '/api/properties/:propertyId/maintenance/requests', 'maintenance.manage', 'property', 'staff_permission', 'property', true, true, 'Report corrective maintenance'),
    ('maintenance', 'maintenance.work_orders.list', 'Work Orders', 'GET', '/api/properties/:propertyId/maintenance/work-orders', 'maintenance.view', 'property', 'staff_permission', 'property', true, true, 'View work orders'),
    ('maintenance', 'maintenance.work_orders.create', 'Create Work Order', 'POST', '/api/properties/:propertyId/maintenance/work-orders', 'maintenance.manage', 'property', 'staff_permission', 'property', true, true, 'Create corrective work order'),
    ('maintenance', 'maintenance.work_orders.assign', 'Assign Work Order', 'POST', '/api/properties/:propertyId/maintenance/work-orders/:workOrderId/assign', 'maintenance.manage', 'property', 'staff_permission', 'property', true, true, 'Assign work order'),
    ('maintenance', 'maintenance.work_orders.start', 'Start Work Order', 'POST', '/api/properties/:propertyId/maintenance/work-orders/:workOrderId/start', 'maintenance.manage', 'property', 'staff_permission', 'property', true, true, 'Start work order'),
    ('maintenance', 'maintenance.work_orders.hold', 'Hold Work Order', 'POST', '/api/properties/:propertyId/maintenance/work-orders/:workOrderId/hold', 'maintenance.manage', 'property', 'staff_permission', 'property', true, true, 'Put work order on hold'),
    ('maintenance', 'maintenance.work_orders.complete', 'Complete Work Order', 'POST', '/api/properties/:propertyId/maintenance/work-orders/:workOrderId/complete', 'maintenance.manage', 'property', 'staff_permission', 'property', true, true, 'Submit work for verification'),
    ('maintenance', 'maintenance.work_orders.verify', 'Verify Work Order', 'POST', '/api/properties/:propertyId/maintenance/work-orders/:workOrderId/verify', 'maintenance.manage', 'property', 'staff_permission', 'property', true, true, 'Verify completed corrective work'),
    ('maintenance', 'maintenance.work_orders.cancel', 'Cancel Work Order', 'POST', '/api/properties/:propertyId/maintenance/work-orders/:workOrderId/cancel', 'maintenance.manage', 'property', 'staff_permission', 'property', true, true, 'Cancel work order'),
    ('maintenance', 'maintenance.room_blocks.create', 'Block Room', 'POST', '/api/properties/:propertyId/maintenance/rooms/:roomId/blocks', 'maintenance.room_block', 'property', 'staff_permission', 'property', true, true, 'Block unoccupied room'),
    ('maintenance', 'maintenance.room_blocks.release', 'Release Room Block', 'POST', '/api/properties/:propertyId/maintenance/room-blocks/:blockId/release', 'maintenance.room_block', 'property', 'staff_permission', 'property', true, true, 'Release room as vacant dirty'),

    ('inventory', 'inventory.items.collection', 'Inventory Items', 'ANY', '/api/properties/:propertyId/inventory/items', 'inventory.view', 'property', 'staff_permission', 'property', true, true, 'List or create items'),
    ('inventory', 'inventory.items.resource', 'Inventory Item', 'ANY', '/api/properties/:propertyId/inventory/items/:itemId', 'inventory.manage', 'property', 'staff_permission', 'property', true, true, 'View or update item'),
    ('inventory', 'inventory.locations.collection', 'Inventory Locations', 'ANY', '/api/properties/:propertyId/inventory/locations', 'inventory.view', 'property', 'staff_permission', 'property', true, true, 'List or create locations'),
    ('inventory', 'inventory.locations.resource', 'Inventory Location', 'ANY', '/api/properties/:propertyId/inventory/locations/:locationId', 'inventory.manage', 'property', 'staff_permission', 'property', true, true, 'View or update location'),
    ('inventory', 'inventory.recipes.collection', 'Recipes', 'ANY', '/api/properties/:propertyId/inventory/recipes', 'inventory.manage', 'property', 'staff_permission', 'property', true, true, 'Configure POS recipes'),
    ('inventory', 'inventory.recipes.delete', 'Delete Recipe', 'DELETE', '/api/properties/:propertyId/inventory/recipes/:menuItemId', 'inventory.manage', 'property', 'staff_permission', 'property', true, true, 'Delete POS recipe'),
    ('inventory', 'inventory.levels', 'Inventory Levels', 'GET', '/api/properties/:propertyId/inventory/levels', 'inventory.view', 'property', 'staff_permission', 'property', true, true, 'View stock by location'),
    ('inventory', 'inventory.movements', 'Inventory Movements', 'GET', '/api/properties/:propertyId/inventory/movements', 'inventory.view', 'property', 'staff_permission', 'property', true, true, 'View movement ledger'),
    ('inventory', 'inventory.opening_balances', 'Opening Balances', 'POST', '/api/properties/:propertyId/inventory/opening-balances', 'inventory.adjust', 'property', 'staff_permission', 'property', true, true, 'Post opening stock'),
    ('inventory', 'inventory.adjustments', 'Inventory Adjustment', 'POST', '/api/properties/:propertyId/inventory/adjustments', 'inventory.adjust', 'property', 'staff_permission', 'property', true, true, 'Post stock adjustment'),
    ('inventory', 'inventory.waste', 'Inventory Waste', 'POST', '/api/properties/:propertyId/inventory/waste', 'inventory.adjust', 'property', 'staff_permission', 'property', true, true, 'Record stock waste'),
    ('inventory', 'inventory.transfers', 'Inventory Transfer', 'POST', '/api/properties/:propertyId/inventory/transfers', 'inventory.adjust', 'property', 'staff_permission', 'property', true, true, 'Atomically transfer stock'),

    ('procurement', 'procurement.suppliers.collection', 'Suppliers', 'ANY', '/api/properties/:propertyId/procurement/suppliers', 'procurement.view', 'property', 'staff_permission', 'property', true, true, 'List or create tenant suppliers'),
    ('procurement', 'procurement.suppliers.resource', 'Supplier', 'ANY', '/api/properties/:propertyId/procurement/suppliers/:supplierId', 'procurement.manage', 'property', 'staff_permission', 'property', true, true, 'View or update supplier'),
    ('procurement', 'procurement.orders.collection', 'Purchase Orders', 'ANY', '/api/properties/:propertyId/purchase-orders', 'procurement.view', 'property', 'staff_permission', 'property', true, true, 'List or create draft orders'),
    ('procurement', 'procurement.orders.resource', 'Purchase Order', 'ANY', '/api/properties/:propertyId/purchase-orders/:purchaseOrderId', 'procurement.manage', 'property', 'staff_permission', 'property', true, true, 'View or edit draft order'),
    ('procurement', 'procurement.orders.submit', 'Submit Purchase Order', 'POST', '/api/properties/:propertyId/purchase-orders/:purchaseOrderId/submit', 'procurement.manage', 'property', 'staff_permission', 'property', true, true, 'Submit order'),
    ('procurement', 'procurement.orders.approve', 'Approve Purchase Order', 'POST', '/api/properties/:propertyId/purchase-orders/:purchaseOrderId/approve', 'procurement.approve', 'property', 'staff_permission', 'property', true, true, 'Independent approval'),
    ('procurement', 'procurement.orders.reject', 'Reject Purchase Order', 'POST', '/api/properties/:propertyId/purchase-orders/:purchaseOrderId/reject', 'procurement.approve', 'property', 'staff_permission', 'property', true, true, 'Reject submitted order'),
    ('procurement', 'procurement.orders.cancel', 'Cancel Purchase Order', 'POST', '/api/properties/:propertyId/purchase-orders/:purchaseOrderId/cancel', 'procurement.manage', 'property', 'staff_permission', 'property', true, true, 'Cancel open order'),
    ('procurement', 'procurement.orders.receive', 'Receive Purchase Order', 'POST', '/api/properties/:propertyId/purchase-orders/:purchaseOrderId/receipts', 'procurement.receive', 'property', 'staff_permission', 'property', true, true, 'Receive remaining quantities'),

    ('pos', 'pos.orders.send', 'Send to Kitchen', 'POST', '/api/properties/:propertyId/pos-orders/:orderId/send', 'pos.order.manage', 'property', 'staff_permission', 'property', true, true, 'Consume recipes and create kitchen ticket'),
    ('pos', 'pos.orders.item_void', 'Void POS Item', 'POST', '/api/properties/:propertyId/pos-orders/:orderId/items/:itemId/void', 'pos.item.void', 'property', 'staff_permission', 'property', true, true, 'Void with controlled stock disposition'),
    ('pos', 'pos.kitchen.list', 'Kitchen Display', 'GET', '/api/properties/:propertyId/kitchen-tickets', 'pos.kitchen.view', 'property', 'staff_permission', 'property', true, true, 'View kitchen tickets'),
    ('pos', 'pos.kitchen.prepare', 'Prepare Kitchen Ticket', 'POST', '/api/properties/:propertyId/kitchen-tickets/:ticketId/prepare', 'pos.kitchen.manage', 'property', 'staff_permission', 'property', true, true, 'Start preparation'),
    ('pos', 'pos.kitchen.ready', 'Ready Kitchen Ticket', 'POST', '/api/properties/:propertyId/kitchen-tickets/:ticketId/ready', 'pos.kitchen.manage', 'property', 'staff_permission', 'property', true, true, 'Mark ready'),
    ('pos', 'pos.kitchen.deliver', 'Deliver Kitchen Ticket', 'POST', '/api/properties/:propertyId/kitchen-tickets/:ticketId/deliver', 'pos.kitchen.manage', 'property', 'staff_permission', 'property', true, true, 'Mark delivered'),
    ('pos', 'pos.kitchen.void', 'Void Kitchen Ticket', 'POST', '/api/properties/:propertyId/kitchen-tickets/:ticketId/void', 'pos.kitchen.manage', 'property', 'staff_permission', 'property', true, true, 'Void ticket with reason')
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

-- Split collection routes by method so create/update never inherit view-only access.
UPDATE module_access_matrix
SET is_enabled_by_default = false,
    notes = 'Superseded by method-specific Phase 4 route',
    updated_at = now()
WHERE screen_key IN (
    'inventory.items.collection', 'inventory.locations.collection',
    'procurement.suppliers.collection', 'procurement.orders.collection'
)
  AND http_method = 'ANY';

INSERT INTO module_access_matrix (
    module_id, screen_key, screen_label, http_method, api_pattern,
    permission_code, route_scope, guard_mode, access_scope,
    is_tanzania_v1, is_enabled_by_default, notes
) VALUES
    ('inventory', 'inventory.items.list', 'Inventory Items', 'GET', '/api/properties/:propertyId/inventory/items', 'inventory.view', 'property', 'staff_permission', 'property', true, true, 'List items'),
    ('inventory', 'inventory.items.create', 'Create Inventory Item', 'POST', '/api/properties/:propertyId/inventory/items', 'inventory.manage', 'property', 'staff_permission', 'property', true, true, 'Create item'),
    ('inventory', 'inventory.locations.list', 'Inventory Locations', 'GET', '/api/properties/:propertyId/inventory/locations', 'inventory.view', 'property', 'staff_permission', 'property', true, true, 'List locations'),
    ('inventory', 'inventory.locations.create', 'Create Inventory Location', 'POST', '/api/properties/:propertyId/inventory/locations', 'inventory.manage', 'property', 'staff_permission', 'property', true, true, 'Create location'),
    ('procurement', 'procurement.suppliers.list', 'Suppliers', 'GET', '/api/properties/:propertyId/procurement/suppliers', 'procurement.view', 'property', 'staff_permission', 'property', true, true, 'List suppliers'),
    ('procurement', 'procurement.suppliers.create', 'Create Supplier', 'POST', '/api/properties/:propertyId/procurement/suppliers', 'procurement.manage', 'property', 'staff_permission', 'property', true, true, 'Create supplier'),
    ('procurement', 'procurement.orders.list', 'Purchase Orders', 'GET', '/api/properties/:propertyId/purchase-orders', 'procurement.view', 'property', 'staff_permission', 'property', true, true, 'List purchase orders'),
    ('procurement', 'procurement.orders.create', 'Create Purchase Order', 'POST', '/api/properties/:propertyId/purchase-orders', 'procurement.manage', 'property', 'staff_permission', 'property', true, true, 'Create draft order')
ON CONFLICT (
    module_id, screen_key, http_method, api_pattern, permission_code
) DO UPDATE SET is_enabled_by_default = true, updated_at = now();

CREATE OR REPLACE VIEW housekeeping_operational_summary
WITH (security_invoker = true)
AS
SELECT tenant_id, property_id, scheduled_date, status, count(*) AS task_count
FROM housekeeping_tasks
GROUP BY tenant_id, property_id, scheduled_date, status;

CREATE OR REPLACE VIEW inventory_operational_summary
WITH (security_invoker = true)
AS
SELECT il.tenant_id, il.property_id, sl.location_id,
       count(*) AS stocked_item_count,
       count(*) FILTER (WHERE sl.quantity <= sl.reorder_level) AS low_stock_count,
       round(sum(sl.quantity * sl.average_cost), 2) AS stock_value
FROM stock_levels sl
JOIN inventory_locations il
  ON il.tenant_id = sl.tenant_id AND il.id = sl.location_id
GROUP BY il.tenant_id, il.property_id, sl.location_id;

CREATE OR REPLACE VIEW procurement_operational_summary
WITH (security_invoker = true)
AS
SELECT tenant_id, property_id, status, count(*) AS order_count,
       round(sum(total_amount), 2) AS order_value
FROM purchase_orders
GROUP BY tenant_id, property_id, status;

CREATE INDEX idx_inventory_movements_monitoring
    ON stock_movements (tenant_id, property_id, created_at DESC, type);
CREATE INDEX idx_purchase_receipts_monitoring
    ON purchase_receipts (tenant_id, property_id, received_at DESC);
CREATE INDEX idx_room_blocks_monitoring
    ON room_blocks (tenant_id, property_id, status, blocked_at DESC);

CREATE TRIGGER trg_lifecycle_housekeeping_tasks
    BEFORE INSERT OR UPDATE OR DELETE ON housekeeping_tasks
    FOR EACH ROW EXECUTE FUNCTION guard_tenant_operational_write();
CREATE TRIGGER trg_lifecycle_maintenance_requests
    BEFORE INSERT OR UPDATE OR DELETE ON maintenance_requests
    FOR EACH ROW EXECUTE FUNCTION guard_tenant_operational_write();
CREATE TRIGGER trg_lifecycle_work_orders
    BEFORE INSERT OR UPDATE OR DELETE ON work_orders
    FOR EACH ROW EXECUTE FUNCTION guard_tenant_operational_write();
CREATE TRIGGER trg_lifecycle_lost_and_found
    BEFORE INSERT OR UPDATE OR DELETE ON lost_and_found
    FOR EACH ROW EXECUTE FUNCTION guard_tenant_operational_write();
CREATE TRIGGER trg_lifecycle_inventory_items
    BEFORE INSERT OR UPDATE OR DELETE ON inventory_items
    FOR EACH ROW EXECUTE FUNCTION guard_tenant_operational_write();
CREATE TRIGGER trg_lifecycle_inventory_locations
    BEFORE INSERT OR UPDATE OR DELETE ON inventory_locations
    FOR EACH ROW EXECUTE FUNCTION guard_tenant_operational_write();
CREATE TRIGGER trg_lifecycle_suppliers
    BEFORE INSERT OR UPDATE OR DELETE ON suppliers
    FOR EACH ROW EXECUTE FUNCTION guard_tenant_operational_write();
CREATE TRIGGER trg_lifecycle_kitchen_tickets
    BEFORE INSERT OR UPDATE OR DELETE ON kitchen_tickets
    FOR EACH ROW EXECUTE FUNCTION guard_tenant_operational_write();

GRANT SELECT ON
    housekeeping_operational_summary,
    inventory_operational_summary,
    procurement_operational_summary
TO pms_app, pms_readonly_support;

GRANT SELECT ON TABLE
    properties, rooms, users, stays,
    property_housekeeping_settings, housekeeping_tasks
TO pms_worker;
GRANT INSERT, UPDATE ON TABLE housekeeping_tasks TO pms_worker;
