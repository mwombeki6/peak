-- ================================================================================
-- Phase 3 Engineer A core finance and stay lifecycle contracts
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
    ('reservations', 'Reservations', 'core_pms', 'property', 'active', false, true, true, 10, 'Guest profiles, direct reservations, amendments, cancellation, and availability-safe room assignment'),
    ('frontdesk', 'Front Desk', 'core_pms', 'property', 'active', false, true, true, 20, 'Check-in, walk-in, stay search, departure validation, and checkout'),
    ('billing', 'Billing', 'finance', 'property', 'active', false, true, true, 50, 'Folios, charges, folio payments, invoice issue, and checkout financial controls'),
    ('night_audit', 'Night Audit', 'finance', 'property', 'active', false, true, true, 80, 'Daily operational close checks, blocking issue capture, and audit completion control')
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
    ('guests.view', 'reservations', 'property', 'View guest profiles for a property', false, true),
    ('guests.manage', 'reservations', 'property', 'Create and update guest profiles for reservation workflows', false, true),
    ('reservations.view', 'reservations', 'property', 'View reservations, reservation rooms, and reservation folios', false, true),
    ('reservations.create', 'reservations', 'property', 'Create direct property reservations', false, true),
    ('reservations.amend', 'reservations', 'property', 'Amend confirmed reservations before check-in', false, true),
    ('reservations.cancel', 'reservations', 'property', 'Cancel confirmed reservations with audited reason and optional fee', false, true),
    ('checkin.process', 'frontdesk', 'property', 'Process reservation check-ins and room assignment', false, true),
    ('frontdesk.walkin.create', 'frontdesk', 'property', 'Create walk-in guest reservations and check them in atomically', false, true),
    ('frontdesk.stays.view', 'frontdesk', 'property', 'View active and historical stays for a property', false, true),
    ('checkout.process', 'frontdesk', 'property', 'Process checkout when folio, invoice, payment, and fiscal controls pass', false, true),
    ('checkout.fiscal_override', 'frontdesk', 'property', 'Process checkout with an audited fiscalization override', false, true),
    ('folio.view', 'folio', 'property', 'View folios, posted charges, posted payments, and invoice state', false, true),
    ('folio.post_charge', 'folio', 'property', 'Post audited folio charges', false, true),
    ('folio.reverse_charge', 'folio', 'property', 'Reverse posted folio charges with an audited reason', false, true),
    ('folio.post_payment', 'folio', 'property', 'Post confirmed cash or mobile-money folio payments', false, true),
    ('billing.invoice', 'billing', 'property', 'Issue and view invoices generated from folios', false, true),
    ('night_audit.view', 'finance', 'property', 'View night audit runs and blocking issues', false, true),
    ('night_audit.run', 'finance', 'property', 'Run property night audit close checks', false, true),
    ('night_audit.override', 'finance', 'property', 'Resolve or override blocking night audit issues', false, true)
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
JOIN permission_catalog pc ON pc.is_tenant_permission = true
WHERE t.deleted_at IS NULL
ON CONFLICT ON CONSTRAINT permissions_tenant_id_code_key
DO UPDATE SET
    description = EXCLUDED.description,
    updated_at = now();

INSERT INTO tenant_role_permissions (tenant_role_id, permission_id)
SELECT tr.id, p.id
FROM tenant_roles tr
JOIN permissions p
  ON p.tenant_id = tr.tenant_id
WHERE tr.code = 'tenant_admin'
  AND tr.is_system = true
  AND p.code IN (
      'guests.view',
      'guests.manage',
      'reservations.view',
      'reservations.create',
      'reservations.amend',
      'reservations.cancel',
      'checkin.process',
      'frontdesk.walkin.create',
      'frontdesk.stays.view',
      'checkout.process',
      'checkout.fiscal_override',
      'folio.view',
      'folio.post_charge',
      'folio.reverse_charge',
      'folio.post_payment',
      'billing.invoice',
      'night_audit.view',
      'night_audit.run',
      'night_audit.override'
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
    ('reservations', 'guests.list', 'Guests', 'GET', '/api/properties/:propertyId/guests', 'guests.view', 'property', 'staff_permission', 'property', true, true, 'List property guest profiles'),
    ('reservations', 'guests.create', 'Create Guest', 'POST', '/api/properties/:propertyId/guests', 'guests.manage', 'property', 'staff_permission', 'property', true, true, 'Create a property guest profile'),
    ('reservations', 'guests.view', 'Guest', 'GET', '/api/properties/:propertyId/guests/:guestId', 'guests.view', 'property', 'staff_permission', 'property', true, true, 'View a guest profile'),
    ('reservations', 'reservations.list', 'Reservations', 'GET', '/api/properties/:propertyId/reservations', 'reservations.view', 'property', 'staff_permission', 'property', true, true, 'List property reservations'),
    ('reservations', 'reservations.create', 'Create Reservation', 'POST', '/api/properties/:propertyId/reservations', 'reservations.create', 'property', 'staff_permission', 'property', true, true, 'Create a confirmed direct reservation and open guest folio'),
    ('reservations', 'reservations.view', 'Reservation', 'GET', '/api/properties/:propertyId/reservations/:reservationId', 'reservations.view', 'property', 'staff_permission', 'property', true, true, 'View reservation details and folio pointer'),
    ('reservations', 'reservations.amend', 'Amend Reservation', 'PATCH', '/api/properties/:propertyId/reservations/:reservationId', 'reservations.amend', 'property', 'staff_permission', 'property', true, true, 'Amend dates, room assignment, guest counts, and notes before check-in'),
    ('reservations', 'reservations.cancel', 'Cancel Reservation', 'POST', '/api/properties/:propertyId/reservations/:reservationId/cancel', 'reservations.cancel', 'property', 'staff_permission', 'property', true, true, 'Cancel a reservation with audited reason'),
    ('frontdesk', 'frontdesk.checkins.create', 'Check In', 'POST', '/api/properties/:propertyId/checkins', 'checkin.process', 'property', 'staff_permission', 'property', true, true, 'Check in a confirmed reservation'),
    ('frontdesk', 'frontdesk.walkins.create', 'Walk-In', 'POST', '/api/properties/:propertyId/walk-ins', 'frontdesk.walkin.create', 'property', 'staff_permission', 'property', true, true, 'Create and check in a walk-in reservation'),
    ('frontdesk', 'frontdesk.stays.list', 'Stays', 'GET', '/api/properties/:propertyId/stays', 'frontdesk.stays.view', 'property', 'staff_permission', 'property', true, true, 'List property stays'),
    ('frontdesk', 'frontdesk.stays.view', 'Stay', 'GET', '/api/properties/:propertyId/stays/:stayId', 'frontdesk.stays.view', 'property', 'staff_permission', 'property', true, true, 'View a stay'),
    ('frontdesk', 'frontdesk.checkout', 'Check Out', 'POST', '/api/properties/:propertyId/checkouts/:stayId', 'checkout.process', 'property', 'staff_permission', 'property', true, true, 'Check out a stay after financial and fiscal validation'),
    ('frontdesk', 'frontdesk.checkout_fiscal_override', 'Fiscal Override Checkout', 'POST', '/api/properties/:propertyId/checkouts/:stayId/fiscal-override', 'checkout.fiscal_override', 'property', 'staff_permission', 'property', true, true, 'Check out with audited fiscal override reason'),
    ('billing', 'billing.folios.list', 'Folios', 'GET', '/api/properties/:propertyId/folios', 'folio.view', 'property', 'staff_permission', 'property', true, true, 'List property folios'),
    ('billing', 'billing.folios.view', 'Folio', 'GET', '/api/properties/:propertyId/folios/:folioId', 'folio.view', 'property', 'staff_permission', 'property', true, true, 'View folio charges, payments, and invoice state'),
    ('billing', 'billing.charges.post', 'Post Charge', 'POST', '/api/properties/:propertyId/folios/:folioId/charges', 'folio.post_charge', 'property', 'staff_permission', 'property', true, true, 'Post a folio charge'),
    ('billing', 'billing.charges.reverse', 'Reverse Charge', 'POST', '/api/properties/:propertyId/folios/:folioId/charges/:chargeId/reverse', 'folio.reverse_charge', 'property', 'staff_permission', 'property', true, true, 'Reverse a posted folio charge'),
    ('billing', 'billing.payments.post', 'Post Payment', 'POST', '/api/properties/:propertyId/folios/:folioId/payments', 'folio.post_payment', 'property', 'staff_permission', 'property', true, true, 'Post a confirmed cash or mobile-money folio payment'),
    ('billing', 'billing.invoices.issue', 'Issue Invoice', 'POST', '/api/properties/:propertyId/folios/:folioId/invoice', 'billing.invoice', 'property', 'staff_permission', 'property', true, true, 'Issue an invoice from a folio'),
    ('billing', 'billing.invoices.list', 'Invoices', 'GET', '/api/properties/:propertyId/invoices', 'billing.invoice', 'property', 'staff_permission', 'property', true, true, 'List property invoices'),
    ('billing', 'billing.invoices.view', 'Invoice', 'GET', '/api/properties/:propertyId/invoices/:invoiceId', 'billing.invoice', 'property', 'staff_permission', 'property', true, true, 'View an invoice'),
    ('night_audit', 'night_audit.list', 'Night Audit Runs', 'GET', '/api/properties/:propertyId/night-audit', 'night_audit.view', 'property', 'staff_permission', 'property', true, true, 'List night audit runs'),
    ('night_audit', 'night_audit.run', 'Run Night Audit', 'POST', '/api/properties/:propertyId/night-audit', 'night_audit.run', 'property', 'staff_permission', 'property', true, true, 'Run night audit close checks'),
    ('night_audit', 'night_audit.view', 'Night Audit Run', 'GET', '/api/properties/:propertyId/night-audit/:runId', 'night_audit.view', 'property', 'staff_permission', 'property', true, true, 'View night audit run details')
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

ALTER TABLE folio_payments
    ADD COLUMN IF NOT EXISTS property_id uuid;

UPDATE folio_payments fp
SET property_id = f.property_id
FROM folios f
WHERE fp.tenant_id = f.tenant_id
  AND fp.folio_id = f.id
  AND fp.property_id IS NULL;

ALTER TABLE folio_payments
    DROP CONSTRAINT IF EXISTS chk_phase3_folio_payments_method_cash_mobile,
    ADD CONSTRAINT chk_phase3_folio_payments_method_cash_mobile
        CHECK (payment_method IN ('cash', 'mobile_money')) NOT VALID;

ALTER TABLE folio_payments
    DROP CONSTRAINT IF EXISTS chk_phase3_folio_payments_amount_positive,
    ADD CONSTRAINT chk_phase3_folio_payments_amount_positive
        CHECK (amount > 0) NOT VALID;

ALTER TABLE folio_payments
    DROP CONSTRAINT IF EXISTS fk_phase3_folio_payments_tenant_property,
    ADD CONSTRAINT fk_phase3_folio_payments_tenant_property
        FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE NOT VALID;

CREATE INDEX IF NOT EXISTS idx_folio_payments_property_paid
    ON folio_payments (tenant_id, property_id, paid_at DESC)
    WHERE property_id IS NOT NULL AND deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS night_audit_issues (
    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    run_id uuid NOT NULL,
    severity text NOT NULL,
    issue_code text NOT NULL,
    message text NOT NULL,
    blocking boolean DEFAULT true NOT NULL,
    payload jsonb DEFAULT '{}'::jsonb NOT NULL,
    resolved_at timestamp with time zone,
    resolved_by uuid,
    resolution_note text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_night_audit_issues_severity CHECK (severity IN ('info', 'warning', 'blocking')),
    CONSTRAINT chk_night_audit_issues_resolution CHECK (
        (resolved_at IS NULL AND resolved_by IS NULL)
        OR (resolved_at IS NOT NULL AND resolved_by IS NOT NULL)
    ),
    CONSTRAINT fk_night_audit_issues_run
        FOREIGN KEY (tenant_id, run_id) REFERENCES night_audit_runs(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_night_audit_issues_property
        FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_night_audit_issues_resolved_by
        FOREIGN KEY (tenant_id, resolved_by) REFERENCES users(tenant_id, id) DEFERRABLE
);

CREATE INDEX IF NOT EXISTS idx_night_audit_issues_run
    ON night_audit_issues (tenant_id, run_id, blocking, severity);

CREATE INDEX IF NOT EXISTS idx_night_audit_issues_property_open
    ON night_audit_issues (tenant_id, property_id, created_at DESC)
    WHERE resolved_at IS NULL;

ALTER TABLE night_audit_issues ENABLE ROW LEVEL SECURITY;
ALTER TABLE ONLY night_audit_issues FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS tenant_isolation ON night_audit_issues;
CREATE POLICY tenant_isolation ON night_audit_issues
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

DROP TRIGGER IF EXISTS trg_night_audit_issues_updated_at ON night_audit_issues;
CREATE TRIGGER trg_night_audit_issues_updated_at
    BEFORE UPDATE ON night_audit_issues
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

GRANT SELECT, INSERT, UPDATE ON TABLE
    guests,
    guest_contacts,
    reservations,
    reservation_guests,
    reservation_notes,
    reservation_rooms,
    reservation_room_nights,
    stays,
    folios,
    folio_charges,
    folio_charge_taxes,
    folio_payments,
    invoices,
    invoice_items,
    invoice_item_taxes,
    document_sequences,
    fiscal_receipts,
    night_audit_runs,
    night_audit_issues
TO pms_app;

GRANT SELECT ON TABLE
    rooms,
    room_types,
    revenue_centers,
    tax_rates,
    property_modules
TO pms_app;

GRANT SELECT, INSERT, UPDATE ON TABLE
    night_audit_runs,
    night_audit_issues
TO pms_worker;

GRANT EXECUTE ON FUNCTION
    allocate_document_number(uuid, text, smallint),
    recalculate_folio_totals(uuid),
    assert_folio_can_close(uuid),
    recalculate_invoice_totals(uuid),
    assert_invoice_totals(uuid)
TO pms_app;
