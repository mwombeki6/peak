-- Phase 4: property purchase orders, independent approval, partial receiving.

ALTER TABLE properties
    ADD COLUMN IF NOT EXISTS currency character(3) NOT NULL DEFAULT 'TZS';

ALTER TABLE suppliers
    ADD COLUMN IF NOT EXISTS code text;
CREATE UNIQUE INDEX idx_suppliers_tenant_code
    ON suppliers (tenant_id, lower(code))
    WHERE code IS NOT NULL AND deleted_at IS NULL;

ALTER TABLE purchase_orders
    ADD COLUMN IF NOT EXISTS property_id uuid,
    ADD COLUMN IF NOT EXISTS order_number text,
    ADD COLUMN IF NOT EXISTS currency character(3) NOT NULL DEFAULT 'TZS',
    ADD COLUMN IF NOT EXISTS created_by uuid,
    ADD COLUMN IF NOT EXISTS submitted_at timestamptz,
    ADD COLUMN IF NOT EXISTS approved_by uuid,
    ADD COLUMN IF NOT EXISTS approved_at timestamptz,
    ADD COLUMN IF NOT EXISTS rejected_by uuid,
    ADD COLUMN IF NOT EXISTS rejected_at timestamptz,
    ADD COLUMN IF NOT EXISTS rejection_reason text,
    ADD COLUMN IF NOT EXISTS cancelled_by uuid,
    ADD COLUMN IF NOT EXISTS cancelled_at timestamptz,
    ADD COLUMN IF NOT EXISTS cancellation_reason text;

UPDATE purchase_orders po
SET property_id = (
        SELECT p.id
        FROM properties p
        WHERE p.tenant_id = po.tenant_id AND p.deleted_at IS NULL
        ORDER BY p.created_at, p.id
        LIMIT 1
    ),
    order_number = COALESCE(
        po.order_number,
        'PO-' || upper(substr(replace(po.id::text, '-', ''), 1, 12))
    )
WHERE po.property_id IS NULL OR po.order_number IS NULL;

UPDATE purchase_orders po
SET currency = p.currency
FROM properties p
WHERE p.tenant_id = po.tenant_id AND p.id = po.property_id;

UPDATE purchase_orders
SET status = CASE
    WHEN status = 'partially_delivered' THEN 'partially_received'
    WHEN status = 'delivered' THEN 'received'
    ELSE status
END;

ALTER TABLE purchase_orders
    ALTER COLUMN property_id SET NOT NULL,
    ALTER COLUMN order_number SET NOT NULL,
    DROP CONSTRAINT IF EXISTS chk_purchase_orders_status,
    ADD CONSTRAINT chk_purchase_orders_status CHECK (
        status IN (
            'draft', 'submitted', 'approved', 'rejected',
            'partially_received', 'received', 'cancelled'
        )
    ),
    ADD CONSTRAINT chk_purchase_order_total CHECK (total_amount >= 0),
    ADD CONSTRAINT chk_purchase_order_approval_separation
        CHECK (approved_by IS NULL OR created_by IS NULL OR approved_by <> created_by),
    ADD CONSTRAINT chk_purchase_order_currency
        CHECK (currency ~ '^[A-Z]{3}$'),
    ADD CONSTRAINT fk_purchase_orders_property
        FOREIGN KEY (tenant_id, property_id)
        REFERENCES properties(tenant_id, id) DEFERRABLE,
    ADD CONSTRAINT fk_purchase_orders_creator
        FOREIGN KEY (tenant_id, created_by)
        REFERENCES users(tenant_id, id) DEFERRABLE,
    ADD CONSTRAINT fk_purchase_orders_approver
        FOREIGN KEY (tenant_id, approved_by)
        REFERENCES users(tenant_id, id) DEFERRABLE,
    ADD CONSTRAINT fk_purchase_orders_rejector
        FOREIGN KEY (tenant_id, rejected_by)
        REFERENCES users(tenant_id, id) DEFERRABLE,
    ADD CONSTRAINT fk_purchase_orders_canceller
        FOREIGN KEY (tenant_id, cancelled_by)
        REFERENCES users(tenant_id, id) DEFERRABLE;

CREATE UNIQUE INDEX idx_purchase_order_number
    ON purchase_orders (tenant_id, property_id, order_number);
CREATE INDEX idx_purchase_order_property_status
    ON purchase_orders (tenant_id, property_id, status, created_at DESC);

ALTER TABLE purchase_order_items
    ADD COLUMN IF NOT EXISTS received_quantity numeric(15,3) NOT NULL DEFAULT 0;

ALTER TABLE purchase_order_items
    ADD CONSTRAINT chk_purchase_order_item_quantity CHECK (quantity > 0),
    ADD CONSTRAINT chk_purchase_order_item_unit_price CHECK (unit_price >= 0),
    ADD CONSTRAINT chk_purchase_order_item_total
        CHECK (total_price = round(quantity * unit_price, 2)),
    ADD CONSTRAINT chk_purchase_order_item_received
        CHECK (received_quantity >= 0 AND received_quantity <= quantity);

CREATE TABLE purchase_order_approvals (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    purchase_order_id uuid NOT NULL,
    action text NOT NULL,
    actor_id uuid NOT NULL,
    reason text,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_purchase_order_approval_action
        CHECK (action IN ('submitted', 'approved', 'rejected', 'cancelled')),
    CONSTRAINT fk_po_approval_property
        FOREIGN KEY (tenant_id, property_id)
        REFERENCES properties(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_po_approval_order
        FOREIGN KEY (tenant_id, purchase_order_id)
        REFERENCES purchase_orders(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_po_approval_actor
        FOREIGN KEY (tenant_id, actor_id)
        REFERENCES users(tenant_id, id) DEFERRABLE
);

CREATE TABLE purchase_receipts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    purchase_order_id uuid NOT NULL,
    receipt_number text NOT NULL,
    supplier_reference text,
    currency character(3) NOT NULL,
    total_amount numeric(15,2) NOT NULL DEFAULT 0,
    received_by uuid NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now(),
    idempotency_key_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_purchase_receipt_total CHECK (total_amount >= 0),
    CONSTRAINT chk_purchase_receipt_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT fk_purchase_receipt_property
        FOREIGN KEY (tenant_id, property_id)
        REFERENCES properties(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_purchase_receipt_order
        FOREIGN KEY (tenant_id, purchase_order_id)
        REFERENCES purchase_orders(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_purchase_receipt_user
        FOREIGN KEY (tenant_id, received_by)
        REFERENCES users(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_purchase_receipt_idempotency
        FOREIGN KEY (idempotency_key_id)
        REFERENCES idempotency_keys(id) DEFERRABLE,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, property_id, receipt_number),
    UNIQUE (tenant_id, purchase_order_id, supplier_reference)
);

CREATE TABLE purchase_receipt_lines (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    receipt_id uuid NOT NULL,
    purchase_order_item_id uuid NOT NULL,
    inventory_item_id uuid NOT NULL,
    location_id uuid NOT NULL,
    quantity numeric(15,3) NOT NULL,
    unit_cost numeric(18,6) NOT NULL,
    line_total numeric(15,2) NOT NULL,
    movement_batch_id uuid NOT NULL,
    stock_movement_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_purchase_receipt_line_quantity CHECK (quantity > 0),
    CONSTRAINT chk_purchase_receipt_line_cost CHECK (unit_cost >= 0),
    CONSTRAINT chk_purchase_receipt_line_total
        CHECK (line_total = round(quantity * unit_cost, 2)),
    CONSTRAINT fk_purchase_receipt_line_property
        FOREIGN KEY (tenant_id, property_id)
        REFERENCES properties(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_purchase_receipt_line_receipt
        FOREIGN KEY (tenant_id, receipt_id)
        REFERENCES purchase_receipts(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_purchase_receipt_line_po_item
        FOREIGN KEY (tenant_id, purchase_order_item_id)
        REFERENCES purchase_order_items(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_purchase_receipt_line_item
        FOREIGN KEY (tenant_id, inventory_item_id)
        REFERENCES inventory_items(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_purchase_receipt_line_location
        FOREIGN KEY (tenant_id, location_id)
        REFERENCES inventory_locations(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_purchase_receipt_line_batch
        FOREIGN KEY (tenant_id, movement_batch_id)
        REFERENCES inventory_movement_batches(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_purchase_receipt_line_movement
        FOREIGN KEY (tenant_id, stock_movement_id)
        REFERENCES stock_movements(tenant_id, id) DEFERRABLE,
    UNIQUE (tenant_id, receipt_id, purchase_order_item_id)
);

CREATE OR REPLACE FUNCTION enforce_purchase_order_property_currency()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_currency character(3);
BEGIN
    SELECT currency INTO v_currency
    FROM properties
    WHERE tenant_id = NEW.tenant_id AND id = NEW.property_id;
    IF NEW.currency <> v_currency THEN
        RAISE EXCEPTION 'Purchase order currency must match property currency';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_purchase_order_currency
    BEFORE INSERT OR UPDATE OF currency, property_id ON purchase_orders
    FOR EACH ROW EXECUTE FUNCTION enforce_purchase_order_property_currency();

CREATE TRIGGER trg_po_approvals_lifecycle
    BEFORE INSERT OR UPDATE OR DELETE ON purchase_order_approvals
    FOR EACH ROW EXECUTE FUNCTION guard_tenant_operational_write();
CREATE TRIGGER trg_purchase_receipts_lifecycle
    BEFORE INSERT OR UPDATE OR DELETE ON purchase_receipts
    FOR EACH ROW EXECUTE FUNCTION guard_tenant_operational_write();
CREATE TRIGGER trg_purchase_receipt_lines_lifecycle
    BEFORE INSERT OR UPDATE OR DELETE ON purchase_receipt_lines
    FOR EACH ROW EXECUTE FUNCTION guard_tenant_operational_write();

ALTER TABLE purchase_order_approvals ENABLE ROW LEVEL SECURITY;
ALTER TABLE purchase_order_approvals FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON purchase_order_approvals
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());
ALTER TABLE purchase_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE purchase_receipts FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON purchase_receipts
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());
ALTER TABLE purchase_receipt_lines ENABLE ROW LEVEL SECURITY;
ALTER TABLE purchase_receipt_lines FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON purchase_receipt_lines
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

ALTER TABLE suppliers FORCE ROW LEVEL SECURITY;
ALTER TABLE purchase_orders FORCE ROW LEVEL SECURITY;
ALTER TABLE purchase_order_items FORCE ROW LEVEL SECURITY;

GRANT SELECT, INSERT, UPDATE ON TABLE
    suppliers,
    purchase_orders,
    purchase_order_items,
    purchase_order_approvals,
    purchase_receipts,
    purchase_receipt_lines
TO pms_app;
GRANT DELETE ON TABLE purchase_order_items TO pms_app;
REVOKE DELETE ON TABLE
    purchase_order_approvals,
    purchase_receipts,
    purchase_receipt_lines
FROM pms_app, pms_worker;
