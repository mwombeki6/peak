-- Server-managed POS print jobs. Peak owns intent, canonical document, routing,
-- authorization and audit. A till claims a job, prints locally, then acks.
-- Physical printers cannot prove exactly-once delivery; claim/ack/reprint are
-- the explicit semantics instead.
--
-- No foreign key from this table onto pos_orders, pos_order_items, kitchen_tickets
-- or payment_transactions: a printer failure must not be able to touch payment
-- or order truth.

CREATE TABLE pos_print_jobs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES tenants(id) DEFERRABLE,
    property_id uuid NOT NULL REFERENCES properties(id) DEFERRABLE,
    outlet_id uuid NOT NULL REFERENCES outlets(id) DEFERRABLE,
    printer_route_id uuid REFERENCES pos_printer_routes(id) ON DELETE SET NULL DEFERRABLE,
    job_type text NOT NULL,
    source_type text NOT NULL,
    source_id uuid NOT NULL,
    source_version bigint NOT NULL DEFAULT 0,
    is_reprint boolean NOT NULL DEFAULT false,
    reprinted_from_job_id uuid REFERENCES pos_print_jobs(id) DEFERRABLE,
    status text NOT NULL DEFAULT 'pending',
    document jsonb NOT NULL DEFAULT '{}'::jsonb,
    claimed_by_device_id uuid REFERENCES paired_devices(id) DEFERRABLE,
    claimed_at timestamptz,
    printed_at timestamptz,
    failed_at timestamptz,
    cancelled_at timestamptz,
    attempts integer NOT NULL DEFAULT 0,
    last_error text,
    reclaim_reason text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_pos_print_jobs_job_type CHECK (
        job_type IN (
            'kitchen_ticket', 'bar_ticket', 'expo_ticket',
            'receipt', 'void_ticket', 'shift_report'
        )
    ),
    CONSTRAINT chk_pos_print_jobs_source_type CHECK (
        source_type IN ('kitchen_ticket', 'pos_order', 'pos_session')
    ),
    CONSTRAINT chk_pos_print_jobs_status CHECK (
        status IN ('pending', 'claimed', 'printed', 'failed', 'cancelled')
    ),
    CONSTRAINT chk_pos_print_jobs_source_version CHECK (source_version >= 0),
    CONSTRAINT chk_pos_print_jobs_attempts CHECK (attempts >= 0),
    CONSTRAINT chk_pos_print_jobs_reprint CHECK (
        (is_reprint = false AND reprinted_from_job_id IS NULL)
        OR (is_reprint = true AND reprinted_from_job_id IS NOT NULL)
    ),
    CONSTRAINT chk_pos_print_jobs_claim_state CHECK (
        (status = 'claimed' AND claimed_by_device_id IS NOT NULL AND claimed_at IS NOT NULL)
        OR (status <> 'claimed')
    )
);

COMMENT ON TABLE pos_print_jobs IS
    'Canonical print intent. Tills claim and ack; Peak never treats a local printer as financial truth.';

CREATE UNIQUE INDEX idx_pos_print_jobs_original_dedup
    ON pos_print_jobs (
        tenant_id, source_type, source_id, source_version, job_type, printer_route_id
    )
    NULLS NOT DISTINCT
    WHERE is_reprint = false;

CREATE INDEX idx_pos_print_jobs_claimable
    ON pos_print_jobs (tenant_id, property_id, outlet_id, status, created_at)
    WHERE status IN ('pending', 'claimed');

CREATE INDEX idx_pos_print_jobs_source
    ON pos_print_jobs (tenant_id, source_type, source_id);

CREATE TRIGGER trg_pos_print_jobs_updated_at
    BEFORE UPDATE ON pos_print_jobs
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_pos_print_jobs_lifecycle
    BEFORE INSERT OR UPDATE OR DELETE ON pos_print_jobs
    FOR EACH ROW EXECUTE FUNCTION guard_tenant_operational_write();

ALTER TABLE pos_print_jobs ENABLE ROW LEVEL SECURITY;
ALTER TABLE pos_print_jobs FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON pos_print_jobs
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

GRANT SELECT, INSERT, UPDATE ON pos_print_jobs TO pms_app;
REVOKE DELETE ON pos_print_jobs FROM pms_app, pms_worker;
GRANT SELECT ON pos_printer_routes TO pms_app;

INSERT INTO permission_catalog (
    code, namespace, access_scope, description,
    is_platform_permission, is_tenant_permission
) VALUES
    (
        'pos.print.manage', 'pos', 'property',
        'Claim, ack, reclaim, and reprint POS print jobs',
        false, true
    )
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
JOIN permission_catalog pc ON pc.code = 'pos.print.manage'
WHERE t.deleted_at IS NULL
ON CONFLICT ON CONSTRAINT permissions_tenant_id_code_key
DO UPDATE SET description = EXCLUDED.description, updated_at = now();

INSERT INTO tenant_role_permissions (tenant_role_id, permission_id)
SELECT tr.id, p.id
FROM tenant_roles tr
JOIN permissions p ON p.tenant_id = tr.tenant_id
WHERE tr.code = 'tenant_admin'
  AND tr.is_system
  AND p.code = 'pos.print.manage'
ON CONFLICT DO NOTHING;

INSERT INTO module_access_matrix (
    module_id, screen_key, screen_label, http_method, api_pattern,
    permission_code, route_scope, guard_mode, access_scope,
    is_tanzania_v1, is_enabled_by_default, notes
) VALUES
    (
        'pos', 'pos.print.list', 'POS Print Jobs',
        'GET', '/api/properties/:propertyId/pos-print-jobs',
        'pos.view', 'property', 'staff_permission', 'property',
        true, true,
        'List server-owned print jobs for a property. Document text is canonical; the till only prints.'
    ),
    (
        'pos', 'pos.print.claim', 'Claim POS Print Job',
        'POST', '/api/properties/:propertyId/pos-print-jobs/:jobId/claim',
        'pos.print.manage', 'property', 'staff_permission', 'property',
        true, true,
        'Exactly one till may claim a pending job. Concurrent claims lose with POS_CONFLICT.'
    ),
    (
        'pos', 'pos.print.printed', 'Ack POS Print Job Printed',
        'POST', '/api/properties/:propertyId/pos-print-jobs/:jobId/printed',
        'pos.print.manage', 'property', 'staff_permission', 'property',
        true, true,
        'The claiming device reports a local print success. This does not change order or payment rows.'
    ),
    (
        'pos', 'pos.print.failed', 'Ack POS Print Job Failed',
        'POST', '/api/properties/:propertyId/pos-print-jobs/:jobId/failed',
        'pos.print.manage', 'property', 'staff_permission', 'property',
        true, true,
        'The claiming device reports a local printer failure. Financial rows stay as they were.'
    ),
    (
        'pos', 'pos.print.reclaim', 'Reclaim POS Print Job',
        'POST', '/api/properties/:propertyId/pos-print-jobs/:jobId/reclaim',
        'pos.print.manage', 'property', 'staff_permission', 'property',
        true, true,
        'Return a claimed job to pending after a crash-before-ack. Reason is required.'
    ),
    (
        'pos', 'pos.print.reprint', 'Reprint POS Print Job',
        'POST', '/api/properties/:propertyId/pos-print-jobs/:jobId/reprint',
        'pos.print.manage', 'property', 'staff_permission', 'property',
        true, true,
        'Enqueue a new reprint job. Original job stays printed or failed.'
    )
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
