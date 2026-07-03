-- Phase 4: location stock, append-only movement batches, recipes, and weighted average.

ALTER TABLE inventory_items
    ADD COLUMN IF NOT EXISTS sku text,
    ADD COLUMN IF NOT EXISTS is_active boolean NOT NULL DEFAULT true,
    ALTER COLUMN cost_per_unit TYPE numeric(18,6);

CREATE UNIQUE INDEX idx_inventory_items_tenant_sku
    ON inventory_items (tenant_id, lower(sku))
    WHERE sku IS NOT NULL AND is_active;

ALTER TABLE inventory_locations
    ADD COLUMN IF NOT EXISTS name text;

UPDATE inventory_locations
SET name = initcap(replace(type, '_', ' ')) || ' ' || left(id::text, 8)
WHERE name IS NULL;

ALTER TABLE inventory_locations
    ALTER COLUMN name SET NOT NULL,
    ADD CONSTRAINT chk_inventory_location_type
        CHECK (type IN ('store', 'kitchen', 'bar', 'housekeeping', 'maintenance'));

CREATE UNIQUE INDEX idx_inventory_location_name
    ON inventory_locations (tenant_id, property_id, lower(name));

-- Upgrade-only home for legacy global stock. New commands always require a location.
INSERT INTO inventory_locations (id, tenant_id, property_id, name, type)
SELECT gen_random_uuid(), p.tenant_id, p.id, 'Legacy Store', 'store'
FROM properties p
WHERE p.deleted_at IS NULL
  AND p.id = (
      SELECT p2.id
      FROM properties p2
      WHERE p2.tenant_id = p.tenant_id AND p2.deleted_at IS NULL
      ORDER BY p2.created_at, p2.id
      LIMIT 1
  )
  AND EXISTS (
      SELECT 1 FROM stock_levels sl
      WHERE sl.tenant_id = p.tenant_id AND sl.location_id IS NULL
  )
ON CONFLICT DO NOTHING;

UPDATE stock_levels sl
SET location_id = il.id
FROM inventory_locations il
WHERE sl.tenant_id = il.tenant_id
  AND sl.location_id IS NULL
  AND il.name = 'Legacy Store';

ALTER TABLE stock_levels
    ADD COLUMN IF NOT EXISTS average_cost numeric(18,6) NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_stock_level_quantity CHECK (quantity >= 0),
    ADD CONSTRAINT chk_stock_level_average_cost CHECK (average_cost >= 0),
    ADD CONSTRAINT fk_stock_levels_location
        FOREIGN KEY (tenant_id, location_id)
        REFERENCES inventory_locations(tenant_id, id) DEFERRABLE,
    ADD CONSTRAINT fk_stock_levels_item
        FOREIGN KEY (tenant_id, item_id)
        REFERENCES inventory_items(tenant_id, id) DEFERRABLE;

ALTER TABLE stock_levels ALTER COLUMN location_id SET NOT NULL;

UPDATE stock_levels sl
SET average_cost = ii.cost_per_unit
FROM inventory_items ii
WHERE ii.tenant_id = sl.tenant_id
  AND ii.id = sl.item_id
  AND sl.quantity > 0
  AND sl.average_cost = 0;

CREATE TABLE inventory_movement_batches (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    command_type text NOT NULL,
    command_id uuid NOT NULL,
    created_by uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_inventory_batch_command
        CHECK (command_type IN (
            'opening_balance', 'adjustment', 'waste', 'transfer',
            'purchase_receipt', 'pos_consumption', 'pos_return'
        )),
    CONSTRAINT fk_inventory_batch_property
        FOREIGN KEY (tenant_id, property_id)
        REFERENCES properties(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_inventory_batch_user
        FOREIGN KEY (tenant_id, created_by)
        REFERENCES users(tenant_id, id) DEFERRABLE,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, property_id, command_type, command_id)
);

CREATE TABLE inventory_transfers (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    source_location_id uuid NOT NULL,
    destination_location_id uuid NOT NULL,
    status text NOT NULL DEFAULT 'completed',
    created_by uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_inventory_transfer_locations
        CHECK (source_location_id <> destination_location_id),
    CONSTRAINT chk_inventory_transfer_status CHECK (status = 'completed'),
    CONSTRAINT fk_inventory_transfer_property
        FOREIGN KEY (tenant_id, property_id)
        REFERENCES properties(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_inventory_transfer_source
        FOREIGN KEY (tenant_id, source_location_id)
        REFERENCES inventory_locations(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_inventory_transfer_destination
        FOREIGN KEY (tenant_id, destination_location_id)
        REFERENCES inventory_locations(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_inventory_transfer_user
        FOREIGN KEY (tenant_id, created_by)
        REFERENCES users(tenant_id, id) DEFERRABLE,
    UNIQUE (tenant_id, id)
);

ALTER TABLE stock_movements
    ADD COLUMN IF NOT EXISTS property_id uuid,
    ADD COLUMN IF NOT EXISTS batch_id uuid,
    ADD COLUMN IF NOT EXISTS transfer_id uuid,
    ADD COLUMN IF NOT EXISTS paired_movement_id uuid,
    ADD COLUMN IF NOT EXISTS source_type text,
    ADD COLUMN IF NOT EXISTS source_id uuid,
    ADD COLUMN IF NOT EXISTS balance_after numeric(15,3),
    ADD COLUMN IF NOT EXISTS average_cost_after numeric(18,6),
    ADD COLUMN IF NOT EXISTS created_by uuid;

UPDATE stock_movements sm
SET location_id = il.id
FROM inventory_locations il
WHERE sm.tenant_id = il.tenant_id
  AND sm.location_id IS NULL
  AND il.name = 'Legacy Store';

UPDATE stock_movements sm
SET property_id = il.property_id
FROM inventory_locations il
WHERE il.tenant_id = sm.tenant_id
  AND il.id = sm.location_id
  AND sm.property_id IS NULL;

UPDATE stock_movements
SET type = CASE
    WHEN type = 'purchase' THEN 'receipt'
    WHEN type = 'adjustment' AND quantity >= 0 THEN 'positive_adjustment'
    WHEN type = 'adjustment' AND quantity < 0 THEN 'negative_adjustment'
    ELSE type
END;
UPDATE stock_movements SET quantity = abs(quantity);

INSERT INTO inventory_movement_batches (
    id, tenant_id, property_id, command_type, command_id, created_at
)
SELECT gen_random_uuid(), sm.tenant_id, sm.property_id, 'adjustment', sm.id, sm.created_at
FROM stock_movements sm
WHERE sm.batch_id IS NULL AND sm.property_id IS NOT NULL
ON CONFLICT DO NOTHING;

UPDATE stock_movements sm
SET batch_id = b.id
FROM inventory_movement_batches b
WHERE b.tenant_id = sm.tenant_id
  AND b.command_id = sm.id
  AND b.command_type = 'adjustment'
  AND sm.batch_id IS NULL;

ALTER TABLE stock_movements
    ALTER COLUMN unit_cost TYPE numeric(18,6),
    DROP CONSTRAINT IF EXISTS chk_stock_movements_costs,
    DROP CONSTRAINT IF EXISTS chk_stock_movements_quantity_direction,
    DROP CONSTRAINT IF EXISTS chk_stock_movements_total_cost,
    DROP CONSTRAINT IF EXISTS chk_stock_movements_type,
    ADD CONSTRAINT chk_stock_movements_costs
        CHECK (unit_cost >= 0 AND total_cost >= 0),
    ADD CONSTRAINT chk_stock_movements_quantity CHECK (quantity > 0),
    ADD CONSTRAINT chk_stock_movements_total_cost
        CHECK (total_cost = round(quantity * unit_cost, 2)),
    ADD CONSTRAINT chk_stock_movements_type CHECK (
        type IN (
            'receipt', 'consumption', 'waste', 'positive_adjustment',
            'negative_adjustment', 'transfer_in', 'transfer_out',
            'return', 'opening_balance'
        )
    ),
    ADD CONSTRAINT fk_stock_movements_property
        FOREIGN KEY (tenant_id, property_id)
        REFERENCES properties(tenant_id, id) DEFERRABLE,
    ADD CONSTRAINT fk_stock_movements_batch
        FOREIGN KEY (tenant_id, batch_id)
        REFERENCES inventory_movement_batches(tenant_id, id) DEFERRABLE,
    ADD CONSTRAINT fk_stock_movements_transfer
        FOREIGN KEY (tenant_id, transfer_id)
        REFERENCES inventory_transfers(tenant_id, id) DEFERRABLE,
    ADD CONSTRAINT fk_stock_movements_pair
        FOREIGN KEY (tenant_id, paired_movement_id)
        REFERENCES stock_movements(tenant_id, id) DEFERRABLE,
    ADD CONSTRAINT fk_stock_movements_user
        FOREIGN KEY (tenant_id, created_by)
        REFERENCES users(tenant_id, id) DEFERRABLE;

ALTER TABLE stock_movements
    ALTER COLUMN property_id SET NOT NULL,
    ALTER COLUMN location_id SET NOT NULL,
    ALTER COLUMN batch_id SET NOT NULL;

ALTER TABLE menu_item_recipes
    ADD COLUMN IF NOT EXISTS property_id uuid,
    ADD COLUMN IF NOT EXISTS location_id uuid;

UPDATE menu_item_recipes mir
SET property_id = o.property_id
FROM menu_items mi
JOIN menu_categories mc ON mc.tenant_id = mi.tenant_id AND mc.id = mi.category_id
JOIN outlets o ON o.tenant_id = mc.tenant_id AND o.id = mc.outlet_id
WHERE mi.tenant_id = mir.tenant_id
  AND mi.id = mir.menu_item_id
  AND mir.property_id IS NULL;

UPDATE menu_item_recipes mir
SET location_id = il.id
FROM menu_items mi
JOIN menu_categories mc ON mc.tenant_id = mi.tenant_id AND mc.id = mi.category_id
JOIN outlets o ON o.tenant_id = mc.tenant_id AND o.id = mc.outlet_id
JOIN LATERAL (
    SELECT x.id
    FROM inventory_locations x
    WHERE x.tenant_id = o.tenant_id
      AND x.property_id = o.property_id
      AND (x.outlet_id = o.id OR x.outlet_id IS NULL)
    ORDER BY (x.outlet_id = o.id) DESC, x.created_at
    LIMIT 1
) il ON true
WHERE mi.tenant_id = mir.tenant_id
  AND mi.id = mir.menu_item_id
  AND mir.location_id IS NULL;

ALTER TABLE menu_item_recipes
    ADD CONSTRAINT chk_menu_item_recipe_quantity CHECK (quantity > 0),
    ADD CONSTRAINT fk_menu_item_recipe_property
        FOREIGN KEY (tenant_id, property_id)
        REFERENCES properties(tenant_id, id) DEFERRABLE,
    ADD CONSTRAINT fk_menu_item_recipe_location
        FOREIGN KEY (tenant_id, location_id)
        REFERENCES inventory_locations(tenant_id, id) DEFERRABLE,
    ADD CONSTRAINT fk_menu_item_recipe_item
        FOREIGN KEY (tenant_id, inventory_item_id)
        REFERENCES inventory_items(tenant_id, id) DEFERRABLE;

CREATE UNIQUE INDEX idx_menu_item_recipe_component
    ON menu_item_recipes (tenant_id, menu_item_id, inventory_item_id, location_id);

CREATE TABLE low_stock_crossings (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    item_id uuid NOT NULL,
    location_id uuid NOT NULL,
    movement_id uuid NOT NULL,
    quantity numeric(15,3) NOT NULL,
    reorder_level numeric(15,3) NOT NULL,
    resolved_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_low_stock_property
        FOREIGN KEY (tenant_id, property_id)
        REFERENCES properties(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_low_stock_item
        FOREIGN KEY (tenant_id, item_id)
        REFERENCES inventory_items(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_low_stock_location
        FOREIGN KEY (tenant_id, location_id)
        REFERENCES inventory_locations(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_low_stock_movement
        FOREIGN KEY (tenant_id, movement_id)
        REFERENCES stock_movements(tenant_id, id) DEFERRABLE
);
CREATE UNIQUE INDEX idx_low_stock_one_open
    ON low_stock_crossings (tenant_id, item_id, location_id)
    WHERE resolved_at IS NULL;

CREATE OR REPLACE FUNCTION apply_stock_movement_to_levels()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_delta numeric(15,3);
    v_old_quantity numeric(15,3);
    v_old_average numeric(18,6);
    v_new_quantity numeric(15,3);
    v_new_average numeric(18,6);
    v_reorder numeric(15,3);
    v_lock_key bigint;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'Stock movements are append-only; create a reversing movement instead';
    END IF;
    IF NEW.location_id IS NULL OR NEW.property_id IS NULL OR NEW.batch_id IS NULL THEN
        RAISE EXCEPTION 'Canonical stock movements require property, location, and batch';
    END IF;

    v_delta := CASE
        WHEN NEW.type IN (
            'receipt', 'transfer_in', 'return', 'opening_balance',
            'positive_adjustment'
        ) THEN NEW.quantity
        ELSE -NEW.quantity
    END;
    v_lock_key := hashtextextended(
        NEW.tenant_id::text || ':' || NEW.item_id::text || ':' || NEW.location_id::text,
        0
    );
    PERFORM pg_advisory_xact_lock(v_lock_key);

    INSERT INTO stock_levels (
        tenant_id, item_id, location_id, quantity, reorder_level, average_cost
    )
    SELECT NEW.tenant_id, NEW.item_id, NEW.location_id, 0,
           ii.reorder_level, 0
    FROM inventory_items ii
    WHERE ii.tenant_id = NEW.tenant_id AND ii.id = NEW.item_id
    ON CONFLICT (tenant_id, item_id, location_id)
        WHERE location_id IS NOT NULL DO NOTHING;

    SELECT quantity, average_cost, reorder_level
    INTO v_old_quantity, v_old_average, v_reorder
    FROM stock_levels
    WHERE tenant_id = NEW.tenant_id
      AND item_id = NEW.item_id
      AND location_id = NEW.location_id
    FOR UPDATE;

    v_new_quantity := v_old_quantity + v_delta;
    IF v_new_quantity < 0 THEN
        RAISE EXCEPTION 'Stock movement would make item % negative at location %',
            NEW.item_id, NEW.location_id;
    END IF;

    IF v_delta > 0 THEN
        v_new_average := CASE
            WHEN v_new_quantity = 0 THEN 0
            ELSE round(
                ((v_old_quantity * v_old_average) + (NEW.quantity * NEW.unit_cost))
                / v_new_quantity,
                6
            )
        END;
    ELSE
        IF NEW.unit_cost <> v_old_average THEN
            RAISE EXCEPTION 'Outgoing movement must use locked source average cost';
        END IF;
        v_new_average := v_old_average;
    END IF;

    UPDATE stock_levels
    SET quantity = v_new_quantity,
        average_cost = v_new_average,
        last_updated_at = now(),
        updated_at = now()
    WHERE tenant_id = NEW.tenant_id
      AND item_id = NEW.item_id
      AND location_id = NEW.location_id;

    NEW.balance_after := v_new_quantity;
    NEW.average_cost_after := v_new_average;

    UPDATE inventory_items ii
    SET current_stock = (
            SELECT COALESCE(sum(sl.quantity), 0)
            FROM stock_levels sl
            WHERE sl.tenant_id = NEW.tenant_id AND sl.item_id = NEW.item_id
        ),
        cost_per_unit = v_new_average,
        updated_at = now()
    WHERE ii.tenant_id = NEW.tenant_id AND ii.id = NEW.item_id;

    IF v_old_quantity > v_reorder AND v_new_quantity <= v_reorder THEN
        INSERT INTO low_stock_crossings (
            tenant_id, property_id, item_id, location_id, movement_id,
            quantity, reorder_level
        )
        VALUES (
            NEW.tenant_id, NEW.property_id, NEW.item_id, NEW.location_id,
            NEW.id, v_new_quantity, v_reorder
        )
        ON CONFLICT DO NOTHING;
    ELSIF v_new_quantity > v_reorder THEN
        UPDATE low_stock_crossings
        SET resolved_at = now()
        WHERE tenant_id = NEW.tenant_id
          AND item_id = NEW.item_id
          AND location_id = NEW.location_id
          AND resolved_at IS NULL;
    END IF;

    RETURN NEW;
END;
$$;

-- The derived balance snapshots must be assigned before the row is stored.
DROP TRIGGER IF EXISTS trg_stock_movements_apply ON stock_movements;
DROP TRIGGER IF EXISTS trg_stock_movements_append_only ON stock_movements;
CREATE TRIGGER trg_stock_movements_apply
    BEFORE INSERT OR UPDATE OR DELETE ON stock_movements
    FOR EACH ROW EXECUTE FUNCTION apply_stock_movement_to_levels();

CREATE TRIGGER trg_inventory_batches_lifecycle
    BEFORE INSERT OR UPDATE OR DELETE ON inventory_movement_batches
    FOR EACH ROW EXECUTE FUNCTION guard_tenant_operational_write();
CREATE TRIGGER trg_inventory_transfers_lifecycle
    BEFORE INSERT OR UPDATE OR DELETE ON inventory_transfers
    FOR EACH ROW EXECUTE FUNCTION guard_tenant_operational_write();
CREATE TRIGGER trg_low_stock_lifecycle
    BEFORE INSERT OR UPDATE OR DELETE ON low_stock_crossings
    FOR EACH ROW EXECUTE FUNCTION guard_tenant_operational_write();

ALTER TABLE inventory_movement_batches ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_movement_batches FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inventory_movement_batches
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());
ALTER TABLE inventory_transfers ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_transfers FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inventory_transfers
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());
ALTER TABLE low_stock_crossings ENABLE ROW LEVEL SECURITY;
ALTER TABLE low_stock_crossings FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON low_stock_crossings
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

ALTER TABLE inventory_items FORCE ROW LEVEL SECURITY;
ALTER TABLE inventory_locations FORCE ROW LEVEL SECURITY;
ALTER TABLE stock_levels FORCE ROW LEVEL SECURITY;
ALTER TABLE stock_movements FORCE ROW LEVEL SECURITY;
ALTER TABLE menu_item_recipes FORCE ROW LEVEL SECURITY;

GRANT SELECT, INSERT, UPDATE ON TABLE
    inventory_items,
    inventory_locations,
    stock_levels,
    inventory_movement_batches,
    inventory_transfers,
    menu_item_recipes,
    low_stock_crossings
TO pms_app;
GRANT DELETE ON TABLE menu_item_recipes TO pms_app;
GRANT DELETE ON TABLE inventory_locations TO pms_app;
GRANT SELECT, INSERT ON TABLE stock_movements TO pms_app;
REVOKE UPDATE, DELETE ON TABLE
    stock_movements,
    inventory_movement_batches,
    inventory_transfers
FROM pms_app, pms_worker;
