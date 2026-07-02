-- Canonical Phase 3 POS contracts. This migration extends the baseline POS
-- tables without replacing them or weakening existing tenant guards.

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
) VALUES (
    'pos',
    'Point of Sale',
    'operations',
    'property',
    'active',
    false,
    true,
    true,
    70,
    'Outlet cashier sessions, menu-priced orders, accountable settlement, and folio transfer'
)
ON CONFLICT (module_id) DO UPDATE SET
    name = EXCLUDED.name,
    category = EXCLUDED.category,
    access_scope = EXCLUDED.access_scope,
    launch_status = EXCLUDED.launch_status,
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
    ('pos.view', 'pos', 'property', 'View property POS sessions and orders', false, true),
    ('pos.session.manage', 'pos', 'property', 'Open and close own POS cashier sessions', false, true),
    ('pos.variance.approve', 'pos', 'property', 'Independently approve POS cash variances', false, true),
    ('pos.order.manage', 'pos', 'property', 'Create POS orders and add menu-priced items', false, true),
    ('pos.order.settle', 'pos', 'property', 'Settle POS orders by cash, mobile money, or room charge', false, true)
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
      'pos.view',
      'pos.session.manage',
      'pos.variance.approve',
      'pos.order.manage',
      'pos.order.settle'
  )
WHERE t.deleted_at IS NULL
ON CONFLICT ON CONSTRAINT permissions_tenant_id_code_key
DO UPDATE SET description = EXCLUDED.description, updated_at = now();

INSERT INTO tenant_role_permissions (tenant_role_id, permission_id)
SELECT tr.id, p.id
FROM tenant_roles tr
JOIN permissions p ON p.tenant_id = tr.tenant_id
WHERE tr.code = 'tenant_admin'
  AND tr.is_system = true
  AND p.code IN (
      'pos.view',
      'pos.session.manage',
      'pos.variance.approve',
      'pos.order.manage',
      'pos.order.settle'
  )
ON CONFLICT DO NOTHING;

ALTER TABLE pos_sessions
    ADD COLUMN IF NOT EXISTS status text,
    ADD COLUMN IF NOT EXISTS closed_by uuid,
    ADD COLUMN IF NOT EXISTS variance_approved_by uuid,
    ADD COLUMN IF NOT EXISTS variance_approved_at timestamptz,
    ADD COLUMN IF NOT EXISTS variance_approval_reason text;

UPDATE pos_sessions
SET status = CASE WHEN closed_at IS NULL THEN 'open' ELSE 'closed' END,
    expected_cash = COALESCE(expected_cash, opening_float)
WHERE status IS NULL OR expected_cash IS NULL;

ALTER TABLE pos_sessions
    ALTER COLUMN status SET DEFAULT 'open',
    ALTER COLUMN status SET NOT NULL,
    ALTER COLUMN expected_cash SET DEFAULT 0,
    ALTER COLUMN expected_cash SET NOT NULL,
    DROP CONSTRAINT IF EXISTS chk_pos_sessions_status,
    ADD CONSTRAINT chk_pos_sessions_status
        CHECK (status IN ('open', 'pending_variance_approval', 'closed')),
    DROP CONSTRAINT IF EXISTS chk_pos_sessions_amounts,
    ADD CONSTRAINT chk_pos_sessions_amounts
        CHECK (
            opening_float >= 0
            AND expected_cash >= 0
            AND (closing_cash IS NULL OR closing_cash >= 0)
        ),
    DROP CONSTRAINT IF EXISTS chk_pos_sessions_variance_approval,
    ADD CONSTRAINT chk_pos_sessions_variance_approval
        CHECK (
            (status = 'open'
             AND closed_at IS NULL
             AND closed_by IS NULL
             AND closing_cash IS NULL
             AND variance IS NULL
             AND variance_approved_by IS NULL
             AND variance_approved_at IS NULL)
            OR
            (status = 'pending_variance_approval'
             AND closed_at IS NULL
             AND closed_by IS NOT NULL
             AND closing_cash IS NOT NULL
             AND variance IS NOT NULL
             AND variance <> 0
             AND variance_approved_by IS NULL
             AND variance_approved_at IS NULL)
            OR
            (status = 'closed'
             AND closed_at IS NOT NULL
             AND closed_by IS NOT NULL
             AND closing_cash IS NOT NULL
             AND variance IS NOT NULL
             AND (
                 (variance = 0
                  AND variance_approved_by IS NULL
                  AND variance_approved_at IS NULL)
                 OR
                 (variance <> 0
                  AND variance_approved_by IS NOT NULL
                  AND variance_approved_at IS NOT NULL
                  AND variance_approved_by <> closed_by)
             ))
        ) NOT VALID,
    DROP CONSTRAINT IF EXISTS fk_pos_sessions_closed_by,
    ADD CONSTRAINT fk_pos_sessions_closed_by
        FOREIGN KEY (tenant_id, closed_by) REFERENCES users(tenant_id, id) DEFERRABLE NOT VALID,
    DROP CONSTRAINT IF EXISTS fk_pos_sessions_variance_approved_by,
    ADD CONSTRAINT fk_pos_sessions_variance_approved_by
        FOREIGN KEY (tenant_id, variance_approved_by) REFERENCES users(tenant_id, id) DEFERRABLE NOT VALID;

CREATE UNIQUE INDEX IF NOT EXISTS idx_pos_sessions_one_active_cashier
    ON pos_sessions (tenant_id, outlet_id, cashier_id)
    WHERE status IN ('open', 'pending_variance_approval');

CREATE INDEX IF NOT EXISTS idx_pos_sessions_outlet_status
    ON pos_sessions (tenant_id, outlet_id, status, opened_at DESC);

ALTER TABLE payment_transactions
    ADD COLUMN IF NOT EXISTS pos_order_id uuid;

ALTER TABLE pos_orders
    ADD COLUMN IF NOT EXISTS settlement_status text,
    ADD COLUMN IF NOT EXISTS settlement_method text,
    ADD COLUMN IF NOT EXISTS payment_transaction_id uuid;

UPDATE pos_orders
SET settlement_status = CASE
        WHEN status = 'open' THEN 'unsettled'
        ELSE 'legacy'
    END
WHERE settlement_status IS NULL;

ALTER TABLE pos_orders
    ALTER COLUMN settlement_status SET DEFAULT 'unsettled',
    ALTER COLUMN settlement_status SET NOT NULL,
    DROP CONSTRAINT IF EXISTS chk_pos_orders_settlement_status,
    ADD CONSTRAINT chk_pos_orders_settlement_status
        CHECK (settlement_status IN (
            'unsettled',
            'pending',
            'confirmed',
            'failed',
            'transferred',
            'legacy'
        )),
    DROP CONSTRAINT IF EXISTS chk_pos_orders_settlement_method,
    ADD CONSTRAINT chk_pos_orders_settlement_method
        CHECK (
            settlement_method IS NULL
            OR settlement_method IN ('cash', 'mobile_money', 'room_charge')
        ),
    DROP CONSTRAINT IF EXISTS chk_pos_orders_settlement_state,
    ADD CONSTRAINT chk_pos_orders_settlement_state
        CHECK (
            settlement_status = 'legacy'
            OR
            (settlement_status = 'unsettled'
             AND status = 'open'
             AND settlement_method IS NULL
             AND payment_transaction_id IS NULL
             AND folio_id IS NULL
             AND settled_at IS NULL)
            OR
            (settlement_status IN ('pending', 'failed')
             AND status = 'open'
             AND settlement_method = 'mobile_money'
             AND payment_transaction_id IS NOT NULL
             AND folio_id IS NULL
             AND settled_at IS NULL)
            OR
            (settlement_status = 'confirmed'
             AND status = 'closed'
             AND settlement_method IN ('cash', 'mobile_money')
             AND payment_transaction_id IS NOT NULL
             AND folio_id IS NULL
             AND settled_at IS NOT NULL)
            OR
            (settlement_status = 'transferred'
             AND status = 'closed'
             AND settlement_method = 'room_charge'
             AND payment_transaction_id IS NULL
             AND folio_id IS NOT NULL
             AND settled_at IS NOT NULL)
        ) NOT VALID;

ALTER TABLE pos_order_items
    ADD COLUMN IF NOT EXISTS item_name text;

UPDATE pos_order_items poi
SET item_name = mi.name
FROM menu_items mi
WHERE mi.tenant_id = poi.tenant_id
  AND mi.id = poi.menu_item_id
  AND poi.item_name IS NULL;

ALTER TABLE pos_order_items
    ALTER COLUMN item_name SET NOT NULL;

ALTER TABLE payment_transactions
    DROP CONSTRAINT IF EXISTS fk_payment_transactions_tenant_pos_order,
    ADD CONSTRAINT fk_payment_transactions_tenant_pos_order
        FOREIGN KEY (tenant_id, pos_order_id)
        REFERENCES pos_orders(tenant_id, id) DEFERRABLE NOT VALID,
    DROP CONSTRAINT IF EXISTS chk_payment_transactions_single_sales_target,
    ADD CONSTRAINT chk_payment_transactions_single_sales_target
        CHECK (pos_order_id IS NULL OR folio_id IS NULL) NOT VALID;

ALTER TABLE pos_orders
    DROP CONSTRAINT IF EXISTS fk_pos_orders_tenant_payment_transaction,
    ADD CONSTRAINT fk_pos_orders_tenant_payment_transaction
        FOREIGN KEY (tenant_id, payment_transaction_id)
        REFERENCES payment_transactions(tenant_id, id) DEFERRABLE NOT VALID;

CREATE INDEX IF NOT EXISTS idx_payment_transactions_pos_order
    ON payment_transactions (tenant_id, pos_order_id, initiated_at DESC)
    WHERE pos_order_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_pos_orders_pending_settlement
    ON pos_orders (tenant_id, property_id, settlement_status, updated_at)
    WHERE status = 'open' AND settlement_status IN ('pending', 'failed');

CREATE OR REPLACE FUNCTION guard_terminal_payment_transaction() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Payment transactions cannot be deleted';
    END IF;
    IF OLD.status IN ('confirmed', 'reversed')
       AND (
           NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
           OR NEW.property_id IS DISTINCT FROM OLD.property_id
           OR NEW.provider_account_id IS DISTINCT FROM OLD.provider_account_id
           OR NEW.folio_id IS DISTINCT FROM OLD.folio_id
           OR NEW.pos_order_id IS DISTINCT FROM OLD.pos_order_id
           OR NEW.transaction_direction IS DISTINCT FROM OLD.transaction_direction
           OR NEW.transaction_type IS DISTINCT FROM OLD.transaction_type
           OR NEW.provider_reference IS DISTINCT FROM OLD.provider_reference
           OR NEW.internal_reference IS DISTINCT FROM OLD.internal_reference
           OR NEW.amount IS DISTINCT FROM OLD.amount
           OR NEW.currency IS DISTINCT FROM OLD.currency
           OR NEW.idempotency_key_id IS DISTINCT FROM OLD.idempotency_key_id
           OR NEW.reversal_of_transaction_id IS DISTINCT FROM OLD.reversal_of_transaction_id
       ) THEN
        RAISE EXCEPTION 'Terminal payment transaction financial fields are immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION guard_pos_order_settlement() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'POS orders cannot be deleted';
    END IF;
    IF OLD.status IN ('closed', 'voided', 'cancelled')
       AND (
           NEW.settlement_status IS DISTINCT FROM OLD.settlement_status
           OR NEW.settlement_method IS DISTINCT FROM OLD.settlement_method
           OR NEW.payment_transaction_id IS DISTINCT FROM OLD.payment_transaction_id
           OR NEW.folio_id IS DISTINCT FROM OLD.folio_id
       ) THEN
        RAISE EXCEPTION 'Terminal POS order settlement fields are immutable';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_pos_orders_settlement_guard ON pos_orders;
CREATE TRIGGER trg_pos_orders_settlement_guard
    BEFORE UPDATE OR DELETE ON pos_orders
    FOR EACH ROW EXECUTE FUNCTION guard_pos_order_settlement();

CREATE OR REPLACE FUNCTION guard_pos_order_item_mutation() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_tenant_id uuid;
    v_order_id uuid;
    v_status text;
    v_settlement_status text;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'POS order items cannot be deleted; void the item instead';
    END IF;

    v_tenant_id := NEW.tenant_id;
    v_order_id := NEW.order_id;
    SELECT status, settlement_status
    INTO v_status, v_settlement_status
    FROM pos_orders
    WHERE tenant_id = v_tenant_id
      AND id = v_order_id
    FOR SHARE;

    IF v_status IS NULL THEN
        RAISE EXCEPTION 'POS order % was not found', v_order_id;
    END IF;
    IF v_status <> 'open' OR v_settlement_status NOT IN ('unsettled', 'failed') THEN
        RAISE EXCEPTION 'POS order items are immutable after settlement starts';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_pos_order_items_mutation_guard ON pos_order_items;
CREATE TRIGGER trg_pos_order_items_mutation_guard
    BEFORE INSERT OR UPDATE OR DELETE ON pos_order_items
    FOR EACH ROW EXECUTE FUNCTION guard_pos_order_item_mutation();

CREATE OR REPLACE FUNCTION guard_pos_session_history() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'POS sessions cannot be deleted';
    END IF;
    IF OLD.status = 'closed'
       AND (
           NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
           OR NEW.outlet_id IS DISTINCT FROM OLD.outlet_id
           OR NEW.cashier_id IS DISTINCT FROM OLD.cashier_id
           OR NEW.status IS DISTINCT FROM OLD.status
           OR NEW.opening_float IS DISTINCT FROM OLD.opening_float
           OR NEW.expected_cash IS DISTINCT FROM OLD.expected_cash
           OR NEW.closing_cash IS DISTINCT FROM OLD.closing_cash
           OR NEW.variance IS DISTINCT FROM OLD.variance
           OR NEW.closed_at IS DISTINCT FROM OLD.closed_at
           OR NEW.closed_by IS DISTINCT FROM OLD.closed_by
           OR NEW.variance_approved_by IS DISTINCT FROM OLD.variance_approved_by
           OR NEW.variance_approved_at IS DISTINCT FROM OLD.variance_approved_at
           OR NEW.variance_approval_reason IS DISTINCT FROM OLD.variance_approval_reason
       ) THEN
        RAISE EXCEPTION 'Closed POS sessions are financially immutable';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_pos_sessions_history_guard ON pos_sessions;
CREATE TRIGGER trg_pos_sessions_history_guard
    BEFORE UPDATE OR DELETE ON pos_sessions
    FOR EACH ROW EXECUTE FUNCTION guard_pos_session_history();

CREATE OR REPLACE FUNCTION guard_cash_float_movement_history() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Cash float movements are append-only';
END;
$$;

DROP TRIGGER IF EXISTS trg_cash_float_movements_append_only ON cash_float_movements;
CREATE TRIGGER trg_cash_float_movements_append_only
    BEFORE UPDATE OR DELETE ON cash_float_movements
    FOR EACH ROW EXECUTE FUNCTION guard_cash_float_movement_history();

ALTER TABLE outbox_events
    DROP CONSTRAINT IF EXISTS chk_outbox_events_destination,
    ADD CONSTRAINT chk_outbox_events_destination CHECK (
        destination IN (
            'fiscal',
            'payment',
            'notification',
            'analytics',
            'audit',
            'edge_sync',
            'webhook',
            'email',
            'sms',
            'whatsapp',
            'pos',
            'platform'
        )
    );

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
    ('pos', 'pos.sessions.open', 'Open POS Session', 'POST', '/api/properties/:propertyId/pos-sessions/open', 'pos.session.manage', 'property', 'staff_permission', 'property', true, true, 'Open an outlet-scoped cashier session'),
    ('pos', 'pos.sessions.view', 'POS Session Summary', 'GET', '/api/properties/:propertyId/pos-sessions/:sessionId', 'pos.view', 'property', 'staff_permission', 'property', true, true, 'View an outlet cashier session and sales summary'),
    ('pos', 'pos.sessions.close', 'Close POS Session', 'POST', '/api/properties/:propertyId/pos-sessions/:sessionId/close', 'pos.session.manage', 'property', 'staff_permission', 'property', true, true, 'Close own cashier session and capture declared cash'),
    ('pos', 'pos.sessions.variance_approve', 'Approve POS Variance', 'POST', '/api/properties/:propertyId/pos-sessions/:sessionId/variance-approve', 'pos.variance.approve', 'property', 'staff_permission', 'property', true, true, 'Independently approve a non-zero cash variance'),
    ('pos', 'pos.orders.create', 'Create POS Order', 'POST', '/api/properties/:propertyId/pos-orders', 'pos.order.manage', 'property', 'staff_permission', 'property', true, true, 'Create an order under the current cashier session'),
    ('pos', 'pos.orders.item_add', 'Add POS Order Item', 'POST', '/api/properties/:propertyId/pos-orders/:orderId/items', 'pos.order.manage', 'property', 'staff_permission', 'property', true, true, 'Add a server-priced available menu item'),
    ('pos', 'pos.orders.view', 'View POS Order', 'GET', '/api/properties/:propertyId/pos-orders/:orderId', 'pos.view', 'property', 'staff_permission', 'property', true, true, 'View a tenant and property scoped POS order'),
    ('pos', 'pos.orders.settle', 'Settle POS Order', 'POST', '/api/properties/:propertyId/pos-orders/:orderId/settle', 'pos.order.settle', 'property', 'staff_permission', 'property', true, true, 'Settle using accountable cash, confirmed mobile money, or room folio transfer')
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

GRANT SELECT, INSERT, UPDATE ON TABLE
    pos_sessions,
    pos_orders,
    pos_order_items
TO pms_app;

GRANT SELECT, INSERT ON TABLE cash_float_movements TO pms_app;
REVOKE UPDATE, DELETE ON TABLE cash_float_movements FROM pms_app, pms_worker;
REVOKE DELETE ON TABLE pos_sessions, pos_orders, pos_order_items FROM pms_app, pms_worker;

GRANT SELECT ON TABLE
    outlets,
    menu_categories,
    menu_items,
    tax_rates
TO pms_app;

GRANT SELECT, UPDATE ON TABLE
    pos_orders,
    payment_transactions
TO pms_worker;
