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
    ('fiscal', 'Fiscal', 'finance', 'property', 'active', false, true, true, 70,
     'Fiscal document submission, provider responses, retries, and recovery')
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
    ('fiscal.view', 'fiscal', 'property', 'View fiscal receipts and submission attempts', false, true),
    ('fiscal.configure', 'fiscal', 'property', 'Configure property fiscal provider', false, true),
    ('fiscal.retry', 'fiscal', 'property', 'Retry rejected or failed fiscal submissions', false, true)
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
  ON pc.code IN ('fiscal.view', 'fiscal.configure', 'fiscal.retry')
WHERE t.deleted_at IS NULL
ON CONFLICT ON CONSTRAINT permissions_tenant_id_code_key
DO UPDATE SET description = EXCLUDED.description, updated_at = now();

INSERT INTO tenant_role_permissions (tenant_role_id, permission_id)
SELECT tr.id, p.id
FROM tenant_roles tr
JOIN permissions p ON p.tenant_id = tr.tenant_id
WHERE tr.code = 'tenant_admin'
  AND tr.is_system = true
  AND p.code IN ('fiscal.view', 'fiscal.configure', 'fiscal.retry')
ON CONFLICT DO NOTHING;

ALTER TABLE fiscal_receipts
    ADD COLUMN IF NOT EXISTS property_id uuid,
    ADD COLUMN IF NOT EXISTS idempotency_key_id uuid;

UPDATE fiscal_receipts fr
SET property_id = i.property_id
FROM invoices i
WHERE i.tenant_id = fr.tenant_id
  AND i.id = fr.invoice_id
  AND fr.property_id IS NULL;

ALTER TABLE fiscal_receipts
    ADD CONSTRAINT fk_fiscal_receipts_tenant_property
        FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE NOT VALID,
    ADD CONSTRAINT fk_fiscal_receipts_idempotency
        FOREIGN KEY (idempotency_key_id) REFERENCES idempotency_keys(id) DEFERRABLE NOT VALID;

CREATE UNIQUE INDEX idx_fiscal_receipts_invoice_once
    ON fiscal_receipts (tenant_id, invoice_id);
CREATE INDEX idx_fiscal_receipts_property_status
    ON fiscal_receipts (tenant_id, property_id, status, submitted_at DESC);

CREATE OR REPLACE FUNCTION guard_fiscal_receipts_financial_state() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF TG_OP = 'INSERT' THEN
    RETURN NEW;
  END IF;

  IF OLD.status = 'accepted' THEN
    RAISE EXCEPTION 'Accepted fiscal receipts are immutable';
  END IF;

  IF OLD.status = 'rejected' AND NEW.status <> 'pending' THEN
    RAISE EXCEPTION 'Rejected fiscal receipts can only be queued for retry';
  END IF;

  IF NEW.status NOT IN ('pending', 'submitted', 'accepted', 'rejected') THEN
    RAISE EXCEPTION 'Invalid fiscal receipt status %', NEW.status;
  END IF;

  IF (NEW.tenant_id, NEW.invoice_id, NEW.fiscal_mode, NEW.receipt_number, NEW.submitted_at, NEW.created_at)
     IS DISTINCT FROM
     (OLD.tenant_id, OLD.invoice_id, OLD.fiscal_mode, OLD.receipt_number, OLD.submitted_at, OLD.created_at) THEN
    RAISE EXCEPTION 'Fiscal receipt identity fields are immutable after submission';
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
    ('fiscal', 'fiscal.receipts.list', 'Fiscal Receipts', 'GET', '/api/properties/:propertyId/fiscal/receipts', 'fiscal.view', 'property', 'staff_permission', 'property', true, true, 'List property fiscal receipts'),
    ('fiscal', 'fiscal.receipts.view', 'Fiscal Receipt', 'GET', '/api/properties/:propertyId/fiscal/receipts/:receiptId', 'fiscal.view', 'property', 'staff_permission', 'property', true, true, 'View fiscal receipt and latest state'),
    ('fiscal', 'fiscal.receipts.retry', 'Retry Fiscal Receipt', 'POST', '/api/properties/:propertyId/fiscal/receipts/:receiptId/retry', 'fiscal.retry', 'property', 'staff_permission', 'property', true, true, 'Retry a rejected or failed fiscal submission'),
    ('fiscal', 'fiscal.providers.configure', 'Configure Fiscal Provider', 'POST', '/api/properties/:propertyId/fiscal/provider-configs', 'fiscal.configure', 'property', 'staff_permission', 'property', true, true, 'Configure a property fiscal provider using a secret reference'),
    ('fiscal', 'fiscal.providers.list', 'Fiscal Provider Configurations', 'GET', '/api/properties/:propertyId/fiscal/provider-configs', 'fiscal.view', 'property', 'staff_permission', 'property', true, true, 'List fiscal provider configurations without secrets')
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
    fiscal_receipts,
    fiscal_providers,
    fiscal_provider_configs,
    fiscal_submission_batches,
    fiscal_submission_attempts,
    fiscal_document_mappings
TO pms_app;

GRANT SELECT ON TABLE
    invoices,
    invoice_items,
    invoice_item_taxes
TO pms_worker;

GRANT SELECT, INSERT, UPDATE ON TABLE
    fiscal_receipts,
    fiscal_provider_configs,
    fiscal_providers,
    fiscal_submission_attempts,
    fiscal_document_mappings
TO pms_worker;
