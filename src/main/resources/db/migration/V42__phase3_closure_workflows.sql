-- Phase 3 closure workflows, permissions, routes, and operational state.

ALTER TABLE properties
    ADD COLUMN IF NOT EXISTS business_date date;

UPDATE properties
SET business_date = (
    (now() AT TIME ZONE timezone)::date + business_date_offset
)
WHERE business_date IS NULL;

ALTER TABLE properties
    ALTER COLUMN business_date SET DEFAULT CURRENT_DATE,
    ALTER COLUMN business_date SET NOT NULL;

DROP FUNCTION IF EXISTS resolve_payment_webhook_scope(uuid);
CREATE FUNCTION resolve_payment_webhook_scope(
    p_provider_account_id uuid
) RETURNS TABLE (
    tenant_id uuid,
    property_id uuid,
    provider_code text,
    checksum_key_secret_ref text,
    client_id text,
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
           ppa.checksum_key_secret_ref,
           ppa.client_id,
           ppa.is_active AND pp.is_active
    FROM payment_provider_accounts ppa
    JOIN payment_providers pp
      ON pp.tenant_id = ppa.tenant_id
     AND pp.id = ppa.provider_id
    WHERE ppa.id = p_provider_account_id;
$$;
REVOKE ALL ON FUNCTION resolve_payment_webhook_scope(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION resolve_payment_webhook_scope(uuid) TO pms_app;

DROP INDEX IF EXISTS idx_folio_payments_one_reversal;
CREATE INDEX IF NOT EXISTS idx_folio_payments_reversal_of
    ON folio_payments (tenant_id, reversal_of)
    WHERE reversal_of IS NOT NULL AND deleted_at IS NULL;

ALTER TABLE night_audit_runs
    DROP CONSTRAINT IF EXISTS chk_night_audit_runs_status,
    ADD CONSTRAINT chk_night_audit_runs_status
        CHECK (status IN (
            'pending',
            'running',
            'blocked',
            'ready',
            'completed',
            'failed'
        ));

ALTER TABLE invoices
    ADD COLUMN IF NOT EXISTS voided_at timestamptz,
    ADD COLUMN IF NOT EXISTS voided_by uuid,
    ADD COLUMN IF NOT EXISTS void_reason text;

ALTER TABLE invoices
    ADD CONSTRAINT fk_invoices_voided_by
        FOREIGN KEY (tenant_id, voided_by)
        REFERENCES users(tenant_id, id)
        DEFERRABLE NOT VALID,
    ADD CONSTRAINT chk_invoices_void_metadata
        CHECK (
            (status <> 'voided'
             AND voided_at IS NULL
             AND voided_by IS NULL
             AND void_reason IS NULL)
            OR
            (status = 'voided'
             AND voided_at IS NOT NULL
             AND voided_by IS NOT NULL
             AND length(trim(void_reason)) >= 10)
        ) NOT VALID;

ALTER TABLE credit_notes
    ADD COLUMN IF NOT EXISTS property_id uuid,
    ADD COLUMN IF NOT EXISTS fiscal_status text NOT NULL DEFAULT 'not_required',
    ADD COLUMN IF NOT EXISTS idempotency_key_id uuid;

ALTER TABLE credit_notes
    ADD CONSTRAINT fk_credit_notes_property
        FOREIGN KEY (tenant_id, property_id)
        REFERENCES properties(tenant_id, id)
        DEFERRABLE NOT VALID,
    ADD CONSTRAINT fk_credit_notes_idempotency
        FOREIGN KEY (idempotency_key_id)
        REFERENCES idempotency_keys(id)
        DEFERRABLE NOT VALID,
    ADD CONSTRAINT chk_credit_notes_fiscal_status
        CHECK (fiscal_status IN (
            'not_required',
            'pending',
            'submitted',
            'accepted',
            'rejected'
        ));

CREATE TABLE credit_note_items (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    credit_note_id uuid NOT NULL,
    invoice_item_id uuid NOT NULL,
    description text NOT NULL,
    amount numeric(15,2) NOT NULL,
    tax_amount numeric(15,2) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_credit_note_items_amounts
        CHECK (amount >= 0 AND tax_amount >= 0 AND amount + tax_amount > 0),
    CONSTRAINT fk_credit_note_items_credit_note
        FOREIGN KEY (tenant_id, credit_note_id)
        REFERENCES credit_notes(tenant_id, id)
        DEFERRABLE,
    CONSTRAINT fk_credit_note_items_invoice_item
        FOREIGN KEY (tenant_id, invoice_item_id)
        REFERENCES invoice_items(tenant_id, id)
        DEFERRABLE,
    CONSTRAINT fk_credit_note_items_property
        FOREIGN KEY (tenant_id, property_id)
        REFERENCES properties(tenant_id, id)
        DEFERRABLE,
    UNIQUE (tenant_id, credit_note_id, invoice_item_id)
);

ALTER TABLE credit_note_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE credit_note_items FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON credit_note_items
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

CREATE TABLE fiscal_corrections (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    invoice_id uuid NOT NULL,
    credit_note_id uuid NOT NULL,
    fiscal_receipt_id uuid,
    status text NOT NULL DEFAULT 'pending',
    attempt_count integer NOT NULL DEFAULT 0,
    last_error_code text,
    last_error_message text,
    submitted_at timestamptz,
    accepted_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_fiscal_corrections_status
        CHECK (status IN (
            'pending',
            'submitted',
            'accepted',
            'rejected',
            'retry'
        )),
    CONSTRAINT chk_fiscal_corrections_attempts CHECK (attempt_count >= 0),
    CONSTRAINT fk_fiscal_corrections_property
        FOREIGN KEY (tenant_id, property_id)
        REFERENCES properties(tenant_id, id)
        DEFERRABLE,
    CONSTRAINT fk_fiscal_corrections_invoice
        FOREIGN KEY (tenant_id, invoice_id)
        REFERENCES invoices(tenant_id, id)
        DEFERRABLE,
    CONSTRAINT fk_fiscal_corrections_credit_note
        FOREIGN KEY (tenant_id, credit_note_id)
        REFERENCES credit_notes(tenant_id, id)
        DEFERRABLE,
    CONSTRAINT fk_fiscal_corrections_receipt
        FOREIGN KEY (tenant_id, fiscal_receipt_id)
        REFERENCES fiscal_receipts(tenant_id, id)
        DEFERRABLE,
    UNIQUE (tenant_id, credit_note_id)
);

ALTER TABLE fiscal_corrections ENABLE ROW LEVEL SECURITY;
ALTER TABLE fiscal_corrections FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON fiscal_corrections
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());
CREATE TRIGGER trg_fiscal_corrections_updated_at
    BEFORE UPDATE ON fiscal_corrections
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE unpaid_checkout_overrides (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    stay_id uuid NOT NULL,
    reservation_id uuid NOT NULL,
    folio_id uuid NOT NULL,
    approved_by uuid NOT NULL,
    reason text NOT NULL,
    outstanding_amount numeric(15,2) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_unpaid_checkout_override_reason
        CHECK (length(trim(reason)) >= 10),
    CONSTRAINT chk_unpaid_checkout_override_amount
        CHECK (outstanding_amount > 0),
    CONSTRAINT fk_unpaid_checkout_override_property
        FOREIGN KEY (tenant_id, property_id)
        REFERENCES properties(tenant_id, id)
        DEFERRABLE,
    CONSTRAINT fk_unpaid_checkout_override_stay
        FOREIGN KEY (tenant_id, stay_id)
        REFERENCES stays(tenant_id, id)
        DEFERRABLE,
    CONSTRAINT fk_unpaid_checkout_override_folio
        FOREIGN KEY (tenant_id, folio_id)
        REFERENCES folios(tenant_id, id)
        DEFERRABLE,
    CONSTRAINT fk_unpaid_checkout_override_approver
        FOREIGN KEY (tenant_id, approved_by)
        REFERENCES users(tenant_id, id)
        DEFERRABLE,
    UNIQUE (tenant_id, stay_id)
);

ALTER TABLE unpaid_checkout_overrides ENABLE ROW LEVEL SECURITY;
ALTER TABLE unpaid_checkout_overrides FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON unpaid_checkout_overrides
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

INSERT INTO permission_catalog (
    code,
    namespace,
    access_scope,
    description,
    is_platform_permission,
    is_tenant_permission
) VALUES
    ('payments.status.view', 'payments', 'property',
     'View payment and provider status', false, true),
    ('payments.refund', 'payments', 'property',
     'Issue partial and full payment refunds', false, true),
    ('billing.invoice.void', 'billing', 'property',
     'Void invoices before fiscal acceptance', false, true),
    ('billing.credit_note', 'billing', 'property',
     'Issue line-linked invoice credit notes', false, true),
    ('checkout.unpaid_override', 'frontdesk', 'property',
     'Approve exceptional checkout with an unpaid open folio', false, true),
    ('fiscal.correct', 'fiscal', 'property',
     'Submit and retry fiscal credit-note corrections', false, true),
    ('night_audit.complete', 'finance', 'property',
     'Complete a ready night audit and advance business date', false, true)
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
      'payments.status.view',
      'payments.refund',
      'billing.invoice.void',
      'billing.credit_note',
      'checkout.unpaid_override',
      'fiscal.correct',
      'night_audit.complete'
  )
WHERE t.deleted_at IS NULL
ON CONFLICT ON CONSTRAINT permissions_tenant_id_code_key
DO UPDATE SET
    description = EXCLUDED.description,
    updated_at = now();

INSERT INTO tenant_role_permissions (tenant_role_id, permission_id)
SELECT tr.id, p.id
FROM tenant_roles tr
JOIN permissions p ON p.tenant_id = tr.tenant_id
WHERE tr.code = 'tenant_admin'
  AND tr.is_system = true
  AND p.code IN (
      'payments.status.view',
      'payments.refund',
      'billing.invoice.void',
      'billing.credit_note',
      'checkout.unpaid_override',
      'fiscal.correct',
      'night_audit.complete'
  )
ON CONFLICT DO NOTHING;

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
    ('payments', 'payments.transactions.refund', 'Refund Payment', 'POST',
     '/api/properties/:propertyId/payments/transactions/:transactionId/refund',
     'payments.refund', 'property', 'staff_permission', 'property', true, true,
     'Create a linked partial or full cash/mobile-money refund'),
    ('payments', 'payments.reconciliations.list', 'Payment Reconciliations', 'GET',
     '/api/properties/:propertyId/payments/reconciliations',
     'payments.status.view', 'property', 'staff_permission', 'property', true, true,
     'List payment reconciliations'),
    ('payments', 'payments.reconciliations.view', 'Payment Reconciliation', 'GET',
     '/api/properties/:propertyId/payments/reconciliations/:reconciliationId',
     'payments.status.view', 'property', 'staff_permission', 'property', true, true,
     'View a payment reconciliation'),
    ('payments', 'payments.reconciliations.import', 'Import Provider Statement', 'POST',
     '/api/properties/:propertyId/payments/reconciliations/import',
     'payments.reconcile', 'property', 'staff_permission', 'property', true, true,
     'Queue a ClickPesa statement import'),
    ('billing', 'billing.invoices.void', 'Void Invoice', 'POST',
     '/api/properties/:propertyId/invoices/:invoiceId/void',
     'billing.invoice.void', 'property', 'staff_permission', 'property', true, true,
     'Void an invoice only before fiscal acceptance'),
    ('billing', 'billing.invoices.credit_note', 'Invoice Credit Note', 'POST',
     '/api/properties/:propertyId/invoices/:invoiceId/credit-notes',
     'billing.credit_note', 'property', 'staff_permission', 'property', true, true,
     'Issue a line-linked credit note and queue fiscal correction'),
    ('frontdesk', 'frontdesk.checkout_unpaid_override', 'Unpaid Checkout Override', 'POST',
     '/api/properties/:propertyId/checkouts/:stayId/unpaid-override',
     'checkout.unpaid_override', 'property', 'staff_permission', 'property', true, true,
     'Checkout while retaining an unpaid open folio and night-audit blocker'),
    ('night_audit', 'night_audit.issue.override', 'Override Night Audit Issue', 'POST',
     '/api/properties/:propertyId/night-audit/:runId/issues/:issueId/override',
     'night_audit.override', 'property', 'staff_permission', 'property', true, true,
     'Override a night-audit issue with supervisor reason'),
    ('night_audit', 'night_audit.complete', 'Complete Night Audit', 'POST',
     '/api/properties/:propertyId/night-audit/:runId/complete',
     'night_audit.complete', 'property', 'staff_permission', 'property', true, true,
     'Revalidate live summaries and advance business date once'),
    ('pos', 'pos.config.outlets', 'Configure POS Outlet', 'POST',
     '/api/properties/:propertyId/pos-config/outlets',
     'pos.configure', 'property', 'staff_permission', 'property', true, true,
     'Create an outlet through the authenticated configuration API'),
    ('pos', 'pos.config.menu_categories', 'Configure POS Menu Category', 'POST',
     '/api/properties/:propertyId/pos-config/menu-categories',
     'pos.configure', 'property', 'staff_permission', 'property', true, true,
     'Create an outlet-scoped menu category'),
    ('pos', 'pos.config.menu_items', 'Configure POS Menu Item', 'POST',
     '/api/properties/:propertyId/pos-config/menu-items',
     'pos.configure', 'property', 'staff_permission', 'property', true, true,
     'Create a server-priced menu item with a canonical tax rate'),
    ('payments', 'payments.clickpesa.webhook', 'ClickPesa Webhook', 'POST',
     '/api/payments/webhooks/clickpesa/:providerAccountId',
     NULL, 'public', 'public_token', 'tenant', true, true,
     'Receive checksum-verified ClickPesa callbacks without tenant headers')
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

UPDATE module_access_matrix
SET is_enabled_by_default = false,
    notes = 'Replaced by ClickPesa checksum webhook',
    updated_at = now()
WHERE module_id = 'payments'
  AND screen_key = 'payments.webhooks.receive';

GRANT SELECT, INSERT, UPDATE ON TABLE
    credit_notes,
    credit_note_items,
    fiscal_corrections,
    unpaid_checkout_overrides
TO pms_app;

GRANT SELECT, INSERT, UPDATE ON TABLE
    credit_notes,
    credit_note_items,
    fiscal_corrections
TO pms_worker;

GRANT SELECT, INSERT, UPDATE ON TABLE
    outlets,
    menu_categories,
    menu_items
TO pms_app;
