-- ================================================================================
-- Phase 2 property management completion
-- ================================================================================

ALTER TABLE properties
    DROP CONSTRAINT chk_properties_status;

ALTER TABLE properties
    ADD CONSTRAINT chk_properties_status CHECK (
        status::text = ANY (
            ARRAY['draft', 'active', 'suspended', 'frozen', 'archived', 'terminated']::text[]
        )
    );

ALTER TABLE buildings
    ADD COLUMN IF NOT EXISTS description text,
    ADD COLUMN IF NOT EXISTS deleted_at timestamp with time zone;

ALTER TABLE floors
    ADD COLUMN IF NOT EXISTS name text,
    ADD COLUMN IF NOT EXISTS deleted_at timestamp with time zone;

ALTER TABLE departments
    ADD COLUMN IF NOT EXISTS code character varying(50),
    ADD COLUMN IF NOT EXISTS deleted_at timestamp with time zone;

CREATE UNIQUE INDEX IF NOT EXISTS idx_buildings_tenant_property_name_active
    ON buildings (tenant_id, property_id, lower(name))
    WHERE deleted_at IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS idx_floors_tenant_building_number_active
    ON floors (tenant_id, building_id, floor_number)
    WHERE deleted_at IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS idx_departments_tenant_property_code_active
    ON departments (tenant_id, property_id, code)
    WHERE code IS NOT NULL AND deleted_at IS NULL;

CREATE OR REPLACE FUNCTION property_allows_setup_writes(
    p_tenant_id uuid,
    p_property_id uuid
) RETURNS boolean
    LANGUAGE sql STABLE
    AS $$
  SELECT
    p_property_id IS NULL
    OR EXISTS (
      SELECT 1
      FROM properties p
      WHERE p.tenant_id = p_tenant_id
        AND p.id = p_property_id
        AND p.deleted_at IS NULL
        AND p.status IN ('draft', 'active', 'suspended', 'frozen')
    );
$$;

CREATE OR REPLACE FUNCTION guard_tenant_operational_write() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
  v_row jsonb;
  v_tenant_id uuid;
  v_property_id uuid;
  v_setup_scoped boolean;
BEGIN
  IF current_platform_user_id() IS NOT NULL AND current_tenant_id() IS NULL THEN
    IF TG_OP = 'DELETE' THEN
      RETURN OLD;
    END IF;
    RETURN NEW;
  END IF;

  PERFORM assert_no_mixed_context();

  v_row := CASE WHEN TG_OP = 'DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
  v_tenant_id := NULLIF(v_row->>'tenant_id', '')::uuid;
  v_property_id := NULLIF(v_row->>'property_id', '')::uuid;

  IF v_tenant_id IS NULL THEN
    IF TG_OP = 'DELETE' THEN
      RETURN OLD;
    END IF;
    RETURN NEW;
  END IF;

  IF NOT tenant_allows_operational_writes(v_tenant_id) THEN
    RAISE EXCEPTION 'Tenant % is not writable in its current lifecycle state', v_tenant_id;
  END IF;

  v_setup_scoped := TG_TABLE_NAME IN (
    'property_modules',
    'property_module_configs',
    'idempotency_keys',
    'outbox_events'
  );

  IF v_setup_scoped THEN
    IF NOT property_allows_setup_writes(v_tenant_id, v_property_id) THEN
      RAISE EXCEPTION 'Property % for tenant % is not setup-writable in its current lifecycle state', v_property_id, v_tenant_id;
    END IF;
  ELSE
    IF NOT property_allows_operational_writes(v_tenant_id, v_property_id) THEN
      RAISE EXCEPTION 'Property % for tenant % is not writable in its current lifecycle state', v_property_id, v_tenant_id;
    END IF;
  END IF;

  IF TG_OP = 'DELETE' THEN
    RETURN OLD;
  END IF;
  RETURN NEW;
END;
$$;

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
    ('property', 'property.buildings.list', 'Buildings', 'GET', '/api/properties/:propertyId/buildings', 'property.view', 'property', 'staff_permission', 'property', true, true, 'List property buildings'),
    ('property', 'property.buildings.view', 'Building', 'GET', '/api/properties/:propertyId/buildings/:buildingId', 'property.view', 'property', 'staff_permission', 'property', true, true, 'View a property building'),
    ('property', 'property.buildings.update', 'Update Building', 'PUT', '/api/properties/:propertyId/buildings/:buildingId', 'property.manage', 'property', 'staff_permission', 'property', true, true, 'Update a property building'),
    ('property', 'property.buildings.delete', 'Delete Building', 'DELETE', '/api/properties/:propertyId/buildings/:buildingId', 'property.manage', 'property', 'staff_permission', 'property', true, true, 'Soft-delete a property building'),
    ('property', 'property.floors.list', 'Floors', 'GET', '/api/properties/:propertyId/floors', 'property.view', 'property', 'staff_permission', 'property', true, true, 'List property floors'),
    ('property', 'property.floors.view', 'Floor', 'GET', '/api/properties/:propertyId/floors/:floorId', 'property.view', 'property', 'staff_permission', 'property', true, true, 'View a property floor'),
    ('property', 'property.floors.update', 'Update Floor', 'PUT', '/api/properties/:propertyId/floors/:floorId', 'property.manage', 'property', 'staff_permission', 'property', true, true, 'Update a property floor'),
    ('property', 'property.floors.delete', 'Delete Floor', 'DELETE', '/api/properties/:propertyId/floors/:floorId', 'property.manage', 'property', 'staff_permission', 'property', true, true, 'Soft-delete a property floor'),
    ('property', 'property.room_types.list', 'Room Types', 'GET', '/api/properties/:propertyId/room-types', 'property.view', 'property', 'staff_permission', 'property', true, true, 'List property room types'),
    ('property', 'property.room_types.view', 'Room Type', 'GET', '/api/properties/:propertyId/room-types/:roomTypeId', 'property.view', 'property', 'staff_permission', 'property', true, true, 'View a property room type'),
    ('property', 'property.room_types.update', 'Update Room Type', 'PUT', '/api/properties/:propertyId/room-types/:roomTypeId', 'property.manage', 'property', 'staff_permission', 'property', true, true, 'Update a property room type'),
    ('property', 'property.room_types.delete', 'Delete Room Type', 'DELETE', '/api/properties/:propertyId/room-types/:roomTypeId', 'property.manage', 'property', 'staff_permission', 'property', true, true, 'Soft-delete a property room type'),
    ('property', 'property.rooms.list', 'Rooms', 'GET', '/api/properties/:propertyId/rooms', 'property.view', 'property', 'staff_permission', 'property', true, true, 'List property rooms'),
    ('property', 'property.rooms.view', 'Room', 'GET', '/api/properties/:propertyId/rooms/:roomId', 'property.view', 'property', 'staff_permission', 'property', true, true, 'View a property room'),
    ('property', 'property.rooms.update', 'Update Room', 'PUT', '/api/properties/:propertyId/rooms/:roomId', 'property.manage', 'property', 'staff_permission', 'property', true, true, 'Update a property room'),
    ('property', 'property.rooms.delete', 'Delete Room', 'DELETE', '/api/properties/:propertyId/rooms/:roomId', 'property.manage', 'property', 'staff_permission', 'property', true, true, 'Soft-delete a property room'),
    ('property', 'property.revenue_centers.list', 'Revenue Centers', 'GET', '/api/properties/:propertyId/revenue-centers', 'property.view', 'property', 'staff_permission', 'property', true, true, 'List property revenue centers'),
    ('property', 'property.revenue_centers.view', 'Revenue Center', 'GET', '/api/properties/:propertyId/revenue-centers/:revenueCenterId', 'property.view', 'property', 'staff_permission', 'property', true, true, 'View a property revenue center'),
    ('property', 'property.revenue_centers.update', 'Update Revenue Center', 'PUT', '/api/properties/:propertyId/revenue-centers/:revenueCenterId', 'property.manage', 'property', 'staff_permission', 'property', true, true, 'Update a property revenue center'),
    ('property', 'property.revenue_centers.delete', 'Delete Revenue Center', 'DELETE', '/api/properties/:propertyId/revenue-centers/:revenueCenterId', 'property.manage', 'property', 'staff_permission', 'property', true, true, 'Soft-delete a property revenue center'),
    ('property', 'property.departments.list', 'Departments', 'GET', '/api/properties/:propertyId/departments', 'property.view', 'property', 'staff_permission', 'property', true, true, 'List property departments'),
    ('property', 'property.departments.view', 'Department', 'GET', '/api/properties/:propertyId/departments/:departmentId', 'property.view', 'property', 'staff_permission', 'property', true, true, 'View a property department'),
    ('property', 'property.departments.update', 'Update Department', 'PUT', '/api/properties/:propertyId/departments/:departmentId', 'property.manage', 'property', 'staff_permission', 'property', true, true, 'Update a property department'),
    ('property', 'property.departments.delete', 'Delete Department', 'DELETE', '/api/properties/:propertyId/departments/:departmentId', 'property.manage', 'property', 'staff_permission', 'property', true, true, 'Soft-delete a property department'),
    ('property', 'property.tax.view', 'Tax Rate', 'GET', '/api/properties/taxes/:taxRateId', 'property.view', 'tenant', 'staff_permission', 'tenant', true, true, 'View tenant tax rate'),
    ('property', 'property.tax.update', 'Update Tax Rate', 'PUT', '/api/properties/taxes/:taxRateId', 'property.manage', 'tenant', 'staff_permission', 'tenant', true, true, 'Update tenant tax rate'),
    ('property', 'property.tax.delete', 'Delete Tax Rate', 'DELETE', '/api/properties/taxes/:taxRateId', 'property.manage', 'tenant', 'staff_permission', 'tenant', true, true, 'Deactivate tenant tax rate')
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
