-- ================================================================================
-- Phase 2 module catalog, permission catalog, and route contracts
-- ================================================================================

INSERT INTO module_catalog (
    module_id,
    name,
    category,
    access_scope,
    launch_status,
    is_platform_visible,
    is_tenant_visible,
    is_property_scoped,
    display_order,
    description
) VALUES
    (
        'property',
        'Property Setup',
        'core_pms',
        'both',
        'active',
        false,
        true,
        true,
        110,
        'Property profile, structures, rooms, tax setup, revenue centers, and property modules'
    ),
    (
        'communications',
        'Communications',
        'operations',
        'tenant',
        'active',
        false,
        true,
        false,
        320,
        'Tenant contacts, channels, templates, verification, and notification dispatch'
    ),
    (
        'realtime',
        'Realtime Streams',
        'operations',
        'property',
        'active',
        false,
        true,
        true,
        330,
        'Property-scoped server-sent event and WebSocket live operational streams'
    )
ON CONFLICT (module_id) DO UPDATE SET
    name = EXCLUDED.name,
    category = EXCLUDED.category,
    access_scope = EXCLUDED.access_scope,
    launch_status = EXCLUDED.launch_status,
    is_platform_visible = EXCLUDED.is_platform_visible,
    is_tenant_visible = EXCLUDED.is_tenant_visible,
    is_property_scoped = EXCLUDED.is_property_scoped,
    display_order = EXCLUDED.display_order,
    description = EXCLUDED.description,
    updated_at = now();

INSERT INTO permission_catalog (
    code,
    namespace,
    access_scope,
    description,
    is_platform_permission,
    is_tenant_permission
) VALUES
    ('property.view', 'property', 'both', 'View property setup and readiness state', false, true),
    ('property.manage', 'property', 'both', 'Manage property setup, structures, rooms, rates, taxes, and modules', false, true),
    ('property.lifecycle', 'property', 'property', 'Activate, suspend, archive, and delete properties', false, true),
    ('communications.view', 'module', 'tenant', 'View tenant communication contacts and channels', false, true),
    ('communications.manage', 'module', 'tenant', 'Manage communication contacts, templates, and channel verification', false, true),
    ('communications.send', 'module', 'tenant', 'Enqueue tenant notifications through the outbox', false, true),
    ('realtime.stream', 'module', 'property', 'Subscribe to property realtime event streams', false, true)
ON CONFLICT (code) DO UPDATE SET
    namespace = EXCLUDED.namespace,
    access_scope = EXCLUDED.access_scope,
    description = EXCLUDED.description,
    is_platform_permission = EXCLUDED.is_platform_permission,
    is_tenant_permission = EXCLUDED.is_tenant_permission,
    updated_at = now();

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
    ('property', 'property.list', 'Properties', 'GET', '/api/properties', 'property.view', 'tenant', 'staff_permission', 'tenant', true, true, 'List tenant properties'),
    ('property', 'property.create', 'Create Property', 'POST', '/api/properties', 'property.manage', 'tenant', 'staff_permission', 'tenant', true, true, 'Create a tenant property shell and initialize property module state'),
    ('property', 'property.tax.list', 'Tax Rates', 'GET', '/api/properties/taxes', 'property.view', 'tenant', 'staff_permission', 'tenant', true, true, 'List tenant tax rates used by property readiness'),
    ('property', 'property.tax.create', 'Create Tax Rate', 'POST', '/api/properties/taxes', 'property.manage', 'tenant', 'staff_permission', 'tenant', true, true, 'Create tenant tax rates used by property billing setup'),
    ('property', 'property.view', 'Property Profile', 'GET', '/api/properties/:propertyId', 'property.view', 'property', 'staff_permission', 'property', true, true, 'View property profile'),
    ('property', 'property.update', 'Update Property', 'PUT', '/api/properties/:propertyId', 'property.manage', 'property', 'staff_permission', 'property', true, true, 'Update property profile'),
    ('property', 'property.delete', 'Delete Property', 'DELETE', '/api/properties/:propertyId', 'property.lifecycle', 'property', 'staff_permission', 'property', true, true, 'Soft-delete and archive a property'),
    ('property', 'property.readiness', 'Property Readiness', 'GET', '/api/properties/:propertyId/readiness', 'property.view', 'property', 'staff_permission', 'property', true, true, 'Check property structural readiness before activation'),
    ('property', 'property.activate', 'Activate Property', 'POST', '/api/properties/:propertyId/activate', 'property.lifecycle', 'property', 'staff_permission', 'property', true, true, 'Activate a property that meets readiness requirements'),
    ('property', 'property.suspend', 'Suspend Property', 'POST', '/api/properties/:propertyId/suspend', 'property.lifecycle', 'property', 'staff_permission', 'property', true, true, 'Suspend a property'),
    ('property', 'property.archive', 'Archive Property', 'POST', '/api/properties/:propertyId/archive', 'property.lifecycle', 'property', 'staff_permission', 'property', true, true, 'Archive a property'),
    ('property', 'property.buildings.create', 'Create Building', 'POST', '/api/properties/:propertyId/buildings', 'property.manage', 'property', 'staff_permission', 'property', true, true, 'Create a property building'),
    ('property', 'property.floors.create', 'Create Floor', 'POST', '/api/properties/:propertyId/floors', 'property.manage', 'property', 'staff_permission', 'property', true, true, 'Create a building floor'),
    ('property', 'property.room_types.create', 'Create Room Type', 'POST', '/api/properties/:propertyId/room-types', 'property.manage', 'property', 'staff_permission', 'property', true, true, 'Create a property room type'),
    ('property', 'property.rooms.create', 'Create Room', 'POST', '/api/properties/:propertyId/rooms', 'property.manage', 'property', 'staff_permission', 'property', true, true, 'Create a room in a property'),
    ('property', 'property.rooms.status', 'Update Room Status', 'PUT', '/api/properties/:propertyId/rooms/:roomId/status', 'property.manage', 'property', 'staff_permission', 'property', true, true, 'Update room lifecycle status'),
    ('property', 'property.revenue_centers.create', 'Create Revenue Center', 'POST', '/api/properties/:propertyId/revenue-centers', 'property.manage', 'property', 'staff_permission', 'property', true, true, 'Create a revenue center'),
    ('property', 'property.departments.create', 'Create Department', 'POST', '/api/properties/:propertyId/departments', 'property.manage', 'property', 'staff_permission', 'property', true, true, 'Create an operational department'),
    ('property', 'property.rates.configure', 'Configure Base Rate', 'POST', '/api/properties/:propertyId/rates', 'property.manage', 'property', 'staff_permission', 'property', true, true, 'Set room type base rates'),
    ('property', 'property.modules.list', 'Property Modules', 'GET', '/api/properties/:propertyId/modules', 'property.view', 'property', 'staff_permission', 'property', true, true, 'List enabled property modules'),
    ('property', 'property.modules.enable', 'Enable Property Module', 'POST', '/api/properties/:propertyId/modules', 'property.manage', 'property', 'staff_permission', 'property', true, true, 'Enable a property module'),
    ('property', 'property.modules.disable', 'Disable Property Module', 'DELETE', '/api/properties/:propertyId/modules/:moduleId', 'property.manage', 'property', 'staff_permission', 'property', true, true, 'Disable a property module'),
    ('communications', 'communications.notifications.enqueue', 'Enqueue Notification', 'POST', '/api/communication/notifications', 'communications.send', 'tenant', 'staff_permission', 'tenant', true, true, 'Enqueue tenant notification through the reliability outbox'),
    ('communications', 'communications.contacts.create', 'Create Contact', 'POST', '/api/communication/contacts', 'communications.manage', 'tenant', 'staff_permission', 'tenant', true, true, 'Create tenant communication contact and channels'),
    ('communications', 'communications.contacts.list', 'Communication Contacts', 'GET', '/api/communication/contacts', 'communications.view', 'tenant', 'staff_permission', 'tenant', true, true, 'List tenant communication contacts'),
    ('communications', 'communications.templates.create', 'Create Template', 'POST', '/api/communication/templates', 'communications.manage', 'tenant', 'staff_permission', 'tenant', true, true, 'Create tenant communication template'),
    ('communications', 'communications.channels.request_verification', 'Request Channel Verification', 'POST', '/api/communication/channels/:channelId/request-verification', 'communications.manage', 'tenant', 'staff_permission', 'tenant', true, true, 'Request verification token for a contact channel'),
    ('communications', 'communications.channels.verify', 'Verify Channel', 'POST', '/api/communication/channels/:channelId/verify', 'communications.manage', 'tenant', 'staff_permission', 'tenant', true, true, 'Verify a contact channel token'),
    ('realtime', 'realtime.property.stream', 'Realtime Property Stream', 'GET', '/api/realtime/tenants/:tenantId/properties/:propertyId/stream', 'realtime.stream', 'property', 'staff_permission', 'property', true, true, 'Subscribe to a property-scoped realtime SSE stream')
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

ALTER TABLE communication_templates
    ADD CONSTRAINT fk_communication_templates_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

CREATE INDEX IF NOT EXISTS idx_communication_templates_tenant
    ON communication_templates (tenant_id, channel_type)
    WHERE deleted_at IS NULL;

ALTER TABLE communication_templates ENABLE ROW LEVEL SECURITY;
ALTER TABLE ONLY communication_templates FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON communication_templates
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

CREATE TRIGGER trg_communication_templates_updated_at
    BEFORE UPDATE ON communication_templates
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pms_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE communication_templates TO pms_app;
    END IF;
END $$;
