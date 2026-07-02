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
    ('payments', 'Payments', 'finance', 'property', 'active', false, true, true, 60,
     'Cash accountability, mobile-money transactions, provider callbacks, reversals, and reconciliation')
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
    ('payments.view', 'payments', 'property', 'View cash sessions and payment transactions', false, true),
    ('payments.collect', 'payments', 'property', 'Collect accountable cash and initiate mobile-money payments', false, true),
    ('payments.cash.manage', 'payments', 'property', 'Open and close cashier sessions', false, true),
    ('payments.configure', 'payments', 'property', 'Configure property payment provider accounts', false, true),
    ('payments.reconcile', 'payments', 'property', 'Create, review, and approve payment reconciliations', false, true),
    ('payments.reverse', 'payments', 'property', 'Reverse or refund confirmed payments', false, true)
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
      'payments.view',
      'payments.collect',
      'payments.cash.manage',
      'payments.configure',
      'payments.reconcile',
      'payments.reverse'
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
      'payments.view',
      'payments.collect',
      'payments.cash.manage',
      'payments.configure',
      'payments.reconcile',
      'payments.reverse'
  )
ON CONFLICT DO NOTHING;

CREATE TABLE cash_sessions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    cashier_id uuid NOT NULL,
    status text NOT NULL DEFAULT 'open',
    opening_float numeric(15,2) NOT NULL DEFAULT 0,
    expected_cash numeric(15,2) NOT NULL DEFAULT 0,
    actual_cash numeric(15,2),
    variance numeric(15,2),
    opened_at timestamptz NOT NULL DEFAULT now(),
    closed_at timestamptz,
    closed_by uuid,
    notes text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_cash_sessions_status CHECK (status IN ('open', 'closed')),
    CONSTRAINT chk_cash_sessions_amounts CHECK (
        opening_float >= 0
        AND expected_cash >= 0
        AND (actual_cash IS NULL OR actual_cash >= 0)
    ),
    CONSTRAINT chk_cash_sessions_close_state CHECK (
        (status = 'open' AND closed_at IS NULL AND closed_by IS NULL AND actual_cash IS NULL AND variance IS NULL)
        OR
        (status = 'closed' AND closed_at IS NOT NULL AND closed_by IS NOT NULL AND actual_cash IS NOT NULL AND variance IS NOT NULL)
    ),
    CONSTRAINT fk_cash_sessions_property
        FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_cash_sessions_cashier
        FOREIGN KEY (tenant_id, cashier_id) REFERENCES users(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_cash_sessions_closed_by
        FOREIGN KEY (tenant_id, closed_by) REFERENCES users(tenant_id, id) DEFERRABLE
);

CREATE UNIQUE INDEX idx_cash_sessions_one_open_cashier
    ON cash_sessions (tenant_id, property_id, cashier_id)
    WHERE status = 'open';
CREATE INDEX idx_cash_sessions_property_opened
    ON cash_sessions (tenant_id, property_id, opened_at DESC);
CREATE UNIQUE INDEX idx_cash_sessions_tenant_id_id
    ON cash_sessions (tenant_id, id);

ALTER TABLE cash_sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE cash_sessions FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cash_sessions
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());
CREATE TRIGGER trg_cash_sessions_updated_at
    BEFORE UPDATE ON cash_sessions
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE payment_transactions
    ADD COLUMN IF NOT EXISTS folio_id uuid,
    ADD COLUMN IF NOT EXISTS initiated_by uuid,
    ADD COLUMN IF NOT EXISTS idempotency_key_id uuid;

ALTER TABLE payment_transactions
    ADD CONSTRAINT fk_payment_transactions_tenant_folio
        FOREIGN KEY (tenant_id, folio_id) REFERENCES folios(tenant_id, id) DEFERRABLE NOT VALID,
    ADD CONSTRAINT fk_payment_transactions_initiated_by
        FOREIGN KEY (tenant_id, initiated_by) REFERENCES users(tenant_id, id) DEFERRABLE NOT VALID,
    ADD CONSTRAINT fk_payment_transactions_idempotency
        FOREIGN KEY (idempotency_key_id) REFERENCES idempotency_keys(id) DEFERRABLE NOT VALID;

ALTER TABLE folio_payments
    ADD COLUMN IF NOT EXISTS payment_transaction_id uuid,
    ADD COLUMN IF NOT EXISTS cash_session_id uuid;

CREATE UNIQUE INDEX idx_folio_payments_payment_transaction
    ON folio_payments (tenant_id, payment_transaction_id)
    WHERE payment_transaction_id IS NOT NULL;

ALTER TABLE folio_payments
    ADD CONSTRAINT fk_folio_payments_transaction
        FOREIGN KEY (tenant_id, payment_transaction_id)
        REFERENCES payment_transactions(tenant_id, id) DEFERRABLE NOT VALID,
    ADD CONSTRAINT fk_folio_payments_cash_session
        FOREIGN KEY (tenant_id, cash_session_id)
        REFERENCES cash_sessions(tenant_id, id) DEFERRABLE NOT VALID,
    ADD CONSTRAINT chk_folio_payments_accountability
        CHECK (
            (payment_method = 'cash' AND cash_session_id IS NOT NULL AND payment_transaction_id IS NOT NULL)
            OR
            (payment_method = 'mobile_money' AND cash_session_id IS NULL AND payment_transaction_id IS NOT NULL)
        ) NOT VALID;

CREATE OR REPLACE FUNCTION resolve_payment_webhook_scope(
    p_provider_account_id uuid
) RETURNS TABLE (
    tenant_id uuid,
    property_id uuid,
    provider_code text,
    webhook_secret_ref text,
    account_active boolean
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
    SELECT ppa.tenant_id,
           ppa.property_id,
           pp.provider_code::text,
           ppa.webhook_secret_ref,
           ppa.is_active AND pp.is_active
    FROM payment_provider_accounts ppa
    JOIN payment_providers pp
      ON pp.tenant_id = ppa.tenant_id
     AND pp.id = ppa.provider_id
    WHERE ppa.id = p_provider_account_id;
$$;

REVOKE ALL ON FUNCTION resolve_payment_webhook_scope(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION resolve_payment_webhook_scope(uuid) TO pms_app;

UPDATE module_access_matrix
SET is_enabled_by_default = false,
    notes = 'Disabled: confirmed payments must enter through the payment module',
    updated_at = now()
WHERE module_id = 'billing'
  AND screen_key = 'billing.payments.post';

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
    ('payments', 'payments.cash_sessions.open', 'Open Cash Session', 'POST', '/api/properties/:propertyId/payments/cash-sessions', 'payments.cash.manage', 'property', 'staff_permission', 'property', true, true, 'Open one accountable cashier session'),
    ('payments', 'payments.cash_sessions.current', 'Current Cash Session', 'GET', '/api/properties/:propertyId/payments/cash-sessions/current', 'payments.view', 'property', 'staff_permission', 'property', true, true, 'View the current cashier session'),
    ('payments', 'payments.cash_sessions.close', 'Close Cash Session', 'POST', '/api/properties/:propertyId/payments/cash-sessions/:cashSessionId/close', 'payments.cash.manage', 'property', 'staff_permission', 'property', true, true, 'Close and reconcile a cashier session'),
    ('payments', 'payments.cash.collect', 'Collect Cash Payment', 'POST', '/api/properties/:propertyId/payments/cash', 'payments.collect', 'property', 'staff_permission', 'property', true, true, 'Collect cash against a folio under an open cashier session'),
    ('payments', 'payments.mobile.initiate', 'Initiate Mobile Money', 'POST', '/api/properties/:propertyId/payments/mobile-money', 'payments.collect', 'property', 'staff_permission', 'property', true, true, 'Create an idempotent mobile-money collection intent'),
    ('payments', 'payments.transactions.list', 'Payment Transactions', 'GET', '/api/properties/:propertyId/payments/transactions', 'payments.view', 'property', 'staff_permission', 'property', true, true, 'List property payment transactions'),
    ('payments', 'payments.transactions.view', 'Payment Transaction', 'GET', '/api/properties/:propertyId/payments/transactions/:transactionId', 'payments.view', 'property', 'staff_permission', 'property', true, true, 'View payment transaction state'),
    ('payments', 'payments.providers.configure', 'Configure Payment Provider', 'POST', '/api/properties/:propertyId/payments/provider-accounts', 'payments.configure', 'property', 'staff_permission', 'property', true, true, 'Configure a property provider account using secret references'),
    ('payments', 'payments.providers.list', 'Payment Providers', 'GET', '/api/properties/:propertyId/payments/provider-accounts', 'payments.view', 'property', 'staff_permission', 'property', true, true, 'List property provider accounts without secrets'),
    ('payments', 'payments.reconciliations.create', 'Create Reconciliation', 'POST', '/api/properties/:propertyId/payments/reconciliations', 'payments.reconcile', 'property', 'staff_permission', 'property', true, true, 'Create a provider reconciliation statement'),
    ('payments', 'payments.reconciliations.approve', 'Approve Reconciliation', 'POST', '/api/properties/:propertyId/payments/reconciliations/:reconciliationId/approve', 'payments.reconcile', 'property', 'staff_permission', 'property', true, true, 'Approve a zero-variance reconciliation'),
    ('payments', 'payments.webhooks.receive', 'Payment Provider Webhook', 'POST', '/api/payments/webhooks/:providerAccountId', NULL, 'public', 'public_token', 'tenant', true, true, 'Receive signed provider callbacks without trusted tenant headers')
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
    cash_sessions,
    payment_providers,
    payment_provider_accounts,
    payment_webhook_events,
    payment_transactions,
    payment_reconciliations,
    payment_reconciliation_items
TO pms_app;

GRANT SELECT, UPDATE ON TABLE
    payment_provider_accounts,
    payment_providers,
    payment_transactions
TO pms_worker;
