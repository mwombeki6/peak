-- Phase 4: replay-safe POS operations, kitchen tickets, and recipe snapshots.

ALTER TABLE pos_orders
    ADD COLUMN IF NOT EXISTS client_operation_id text;
UPDATE pos_orders SET client_operation_id = id::text WHERE client_operation_id IS NULL;
ALTER TABLE pos_orders
    ALTER COLUMN client_operation_id SET NOT NULL,
    ADD CONSTRAINT chk_pos_order_client_operation
        CHECK (length(trim(client_operation_id)) BETWEEN 1 AND 100);
CREATE UNIQUE INDEX idx_pos_order_client_operation
    ON pos_orders (tenant_id, property_id, client_operation_id);

ALTER TABLE pos_order_items
    ADD COLUMN IF NOT EXISTS client_operation_id text,
    ADD COLUMN IF NOT EXISTS service_state text NOT NULL DEFAULT 'unsent',
    ADD COLUMN IF NOT EXISTS sent_quantity numeric(10,3) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS void_disposition text,
    ADD COLUMN IF NOT EXISTS voided_by uuid,
    ADD COLUMN IF NOT EXISTS voided_at timestamptz,
    ADD COLUMN IF NOT EXISTS void_reason text;

UPDATE pos_order_items SET client_operation_id = id::text WHERE client_operation_id IS NULL;

ALTER TABLE pos_order_items
    ALTER COLUMN client_operation_id SET NOT NULL,
    ADD CONSTRAINT chk_pos_item_client_operation
        CHECK (length(trim(client_operation_id)) BETWEEN 1 AND 100),
    ADD CONSTRAINT chk_pos_item_service_state
        CHECK (service_state IN ('unsent', 'sent', 'preparing', 'ready', 'delivered', 'voided')),
    ADD CONSTRAINT chk_pos_item_sent_quantity
        CHECK (sent_quantity >= 0 AND sent_quantity <= quantity),
    ADD CONSTRAINT chk_pos_item_void_disposition
        CHECK (
            void_disposition IS NULL
            OR void_disposition IN ('no_stock_effect', 'return_to_stock', 'waste')
        ),
    ADD CONSTRAINT fk_pos_item_voided_by
        FOREIGN KEY (tenant_id, voided_by)
        REFERENCES users(tenant_id, id) DEFERRABLE;
CREATE UNIQUE INDEX idx_pos_item_client_operation
    ON pos_order_items (tenant_id, order_id, client_operation_id);

ALTER TABLE kitchen_tickets
    ADD COLUMN IF NOT EXISTS property_id uuid,
    ADD COLUMN IF NOT EXISTS ticket_number text,
    ADD COLUMN IF NOT EXISTS client_operation_id text,
    ADD COLUMN IF NOT EXISTS sent_by uuid,
    ADD COLUMN IF NOT EXISTS sent_at timestamptz,
    ADD COLUMN IF NOT EXISTS consumption_batch_id uuid,
    ADD COLUMN IF NOT EXISTS voided_by uuid,
    ADD COLUMN IF NOT EXISTS voided_at timestamptz,
    ADD COLUMN IF NOT EXISTS void_reason text;

UPDATE kitchen_tickets kt
SET property_id = po.property_id,
    ticket_number = COALESCE(
        kt.ticket_number,
        'KDS-' || upper(substr(replace(kt.id::text, '-', ''), 1, 12))
    ),
    client_operation_id = COALESCE(kt.client_operation_id, kt.id::text),
    sent_at = COALESCE(kt.sent_at, kt.created_at)
FROM pos_orders po
WHERE po.tenant_id = kt.tenant_id AND po.id = kt.order_id;

ALTER TABLE kitchen_tickets
    ALTER COLUMN property_id SET NOT NULL,
    ALTER COLUMN ticket_number SET NOT NULL,
    ALTER COLUMN client_operation_id SET NOT NULL,
    ADD CONSTRAINT fk_kitchen_ticket_property
        FOREIGN KEY (tenant_id, property_id)
        REFERENCES properties(tenant_id, id) DEFERRABLE,
    ADD CONSTRAINT fk_kitchen_ticket_sender
        FOREIGN KEY (tenant_id, sent_by)
        REFERENCES users(tenant_id, id) DEFERRABLE,
    ADD CONSTRAINT fk_kitchen_ticket_batch
        FOREIGN KEY (tenant_id, consumption_batch_id)
        REFERENCES inventory_movement_batches(tenant_id, id) DEFERRABLE,
    ADD CONSTRAINT fk_kitchen_ticket_voider
        FOREIGN KEY (tenant_id, voided_by)
        REFERENCES users(tenant_id, id) DEFERRABLE;

CREATE UNIQUE INDEX idx_kitchen_ticket_number
    ON kitchen_tickets (tenant_id, property_id, ticket_number);
CREATE UNIQUE INDEX idx_kitchen_ticket_operation
    ON kitchen_tickets (tenant_id, property_id, order_id, client_operation_id);
CREATE INDEX idx_kitchen_ticket_board
    ON kitchen_tickets (tenant_id, property_id, status, created_at);

CREATE TABLE kitchen_ticket_items (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    kitchen_ticket_id uuid NOT NULL,
    pos_order_item_id uuid NOT NULL,
    quantity numeric(10,3) NOT NULL,
    item_name text NOT NULL,
    modifiers jsonb NOT NULL DEFAULT '[]',
    special_request text,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_kitchen_ticket_item_quantity CHECK (quantity > 0),
    CONSTRAINT fk_kitchen_ticket_item_property
        FOREIGN KEY (tenant_id, property_id)
        REFERENCES properties(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_kitchen_ticket_item_ticket
        FOREIGN KEY (tenant_id, kitchen_ticket_id)
        REFERENCES kitchen_tickets(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_kitchen_ticket_item_order_item
        FOREIGN KEY (tenant_id, pos_order_item_id)
        REFERENCES pos_order_items(tenant_id, id) DEFERRABLE,
    UNIQUE (tenant_id, kitchen_ticket_id, pos_order_item_id)
);

CREATE TABLE pos_recipe_consumption_snapshots (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    kitchen_ticket_id uuid NOT NULL,
    pos_order_item_id uuid NOT NULL,
    menu_item_id uuid NOT NULL,
    inventory_item_id uuid NOT NULL,
    location_id uuid NOT NULL,
    quantity_per_item numeric(15,3) NOT NULL,
    order_item_quantity numeric(10,3) NOT NULL,
    consumed_quantity numeric(15,3) NOT NULL,
    unit_cost numeric(18,6) NOT NULL,
    stock_movement_id uuid NOT NULL,
    returned_quantity numeric(15,3) NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_pos_recipe_snapshot_quantities CHECK (
        quantity_per_item > 0
        AND order_item_quantity > 0
        AND consumed_quantity > 0
        AND returned_quantity >= 0
        AND returned_quantity <= consumed_quantity
    ),
    CONSTRAINT chk_pos_recipe_snapshot_cost CHECK (unit_cost >= 0),
    CONSTRAINT fk_pos_recipe_snapshot_property
        FOREIGN KEY (tenant_id, property_id)
        REFERENCES properties(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_pos_recipe_snapshot_ticket
        FOREIGN KEY (tenant_id, kitchen_ticket_id)
        REFERENCES kitchen_tickets(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_pos_recipe_snapshot_order_item
        FOREIGN KEY (tenant_id, pos_order_item_id)
        REFERENCES pos_order_items(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_pos_recipe_snapshot_inventory_item
        FOREIGN KEY (tenant_id, inventory_item_id)
        REFERENCES inventory_items(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_pos_recipe_snapshot_location
        FOREIGN KEY (tenant_id, location_id)
        REFERENCES inventory_locations(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_pos_recipe_snapshot_movement
        FOREIGN KEY (tenant_id, stock_movement_id)
        REFERENCES stock_movements(tenant_id, id) DEFERRABLE,
    UNIQUE (
        tenant_id, kitchen_ticket_id, pos_order_item_id,
        inventory_item_id, location_id
    )
);

CREATE TABLE pos_item_void_dispositions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    pos_order_id uuid NOT NULL,
    pos_order_item_id uuid NOT NULL,
    disposition text NOT NULL,
    reason text NOT NULL,
    actor_id uuid NOT NULL,
    return_batch_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_pos_void_disposition
        CHECK (disposition IN ('no_stock_effect', 'return_to_stock', 'waste')),
    CONSTRAINT chk_pos_void_reason CHECK (length(trim(reason)) >= 3),
    CONSTRAINT fk_pos_void_property
        FOREIGN KEY (tenant_id, property_id)
        REFERENCES properties(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_pos_void_order
        FOREIGN KEY (tenant_id, pos_order_id)
        REFERENCES pos_orders(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_pos_void_order_item
        FOREIGN KEY (tenant_id, pos_order_item_id)
        REFERENCES pos_order_items(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_pos_void_actor
        FOREIGN KEY (tenant_id, actor_id)
        REFERENCES users(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_pos_void_return_batch
        FOREIGN KEY (tenant_id, return_batch_id)
        REFERENCES inventory_movement_batches(tenant_id, id) DEFERRABLE,
    UNIQUE (tenant_id, pos_order_item_id)
);

CREATE OR REPLACE FUNCTION reject_pos_snapshot_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'POS recipe snapshots and void dispositions are append-only';
END;
$$;

CREATE TRIGGER trg_kitchen_ticket_items_lifecycle
    BEFORE INSERT OR UPDATE OR DELETE ON kitchen_ticket_items
    FOR EACH ROW EXECUTE FUNCTION guard_tenant_operational_write();
CREATE TRIGGER trg_pos_recipe_snapshots_lifecycle
    BEFORE INSERT OR UPDATE OR DELETE ON pos_recipe_consumption_snapshots
    FOR EACH ROW EXECUTE FUNCTION guard_tenant_operational_write();
CREATE TRIGGER trg_pos_void_dispositions_lifecycle
    BEFORE INSERT OR UPDATE OR DELETE ON pos_item_void_dispositions
    FOR EACH ROW EXECUTE FUNCTION guard_tenant_operational_write();
CREATE TRIGGER trg_kitchen_ticket_items_append_only
    BEFORE UPDATE OR DELETE ON kitchen_ticket_items
    FOR EACH ROW EXECUTE FUNCTION reject_pos_snapshot_mutation();
CREATE TRIGGER trg_pos_recipe_snapshots_append_only
    BEFORE DELETE ON pos_recipe_consumption_snapshots
    FOR EACH ROW EXECUTE FUNCTION reject_pos_snapshot_mutation();
CREATE TRIGGER trg_pos_void_dispositions_append_only
    BEFORE UPDATE OR DELETE ON pos_item_void_dispositions
    FOR EACH ROW EXECUTE FUNCTION reject_pos_snapshot_mutation();

ALTER TABLE kitchen_ticket_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE kitchen_ticket_items FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON kitchen_ticket_items
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());
ALTER TABLE pos_recipe_consumption_snapshots ENABLE ROW LEVEL SECURITY;
ALTER TABLE pos_recipe_consumption_snapshots FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON pos_recipe_consumption_snapshots
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());
ALTER TABLE pos_item_void_dispositions ENABLE ROW LEVEL SECURITY;
ALTER TABLE pos_item_void_dispositions FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON pos_item_void_dispositions
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

ALTER TABLE pos_orders FORCE ROW LEVEL SECURITY;
ALTER TABLE pos_order_items FORCE ROW LEVEL SECURITY;
ALTER TABLE kitchen_tickets FORCE ROW LEVEL SECURITY;

GRANT SELECT, INSERT, UPDATE ON TABLE
    pos_orders,
    pos_order_items,
    kitchen_tickets,
    pos_recipe_consumption_snapshots
TO pms_app;
GRANT SELECT, INSERT ON TABLE
    kitchen_ticket_items,
    pos_item_void_dispositions
TO pms_app;
REVOKE DELETE ON TABLE
    kitchen_ticket_items,
    pos_recipe_consumption_snapshots,
    pos_item_void_dispositions
FROM pms_app, pms_worker;
