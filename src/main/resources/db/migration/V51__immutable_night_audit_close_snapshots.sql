-- Phase 5: immutable, versioned close snapshots owned by Night Audit.

ALTER TABLE night_audit_issues
    ADD COLUMN IF NOT EXISTS override_allowed boolean NOT NULL DEFAULT true;

UPDATE night_audit_issues
SET override_allowed = false
WHERE issue_code IN (
    'open_unpaid_folios',
    'revenue_journal_mismatch',
    'payment_allocation_mismatch',
    'closed_pos_orders_unsettled'
);

CREATE TABLE night_audit_close_snapshots (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    night_audit_run_id uuid NOT NULL,
    business_date date NOT NULL,
    schema_version integer NOT NULL DEFAULT 1,
    currency character(3) NOT NULL,
    payload jsonb NOT NULL,
    payload_hash character(64) NOT NULL,
    available_rooms integer NOT NULL,
    rooms_sold integer NOT NULL,
    occupied_rooms integer NOT NULL,
    occupancy numeric(7,2) NOT NULL,
    adr numeric(15,2) NOT NULL,
    revpar numeric(15,2) NOT NULL,
    room_revenue numeric(15,2) NOT NULL,
    pos_revenue numeric(15,2) NOT NULL,
    tax_total numeric(15,2) NOT NULL,
    gross_total numeric(15,2) NOT NULL,
    net_total numeric(15,2) NOT NULL,
    revenue_journal_difference numeric(15,2) NOT NULL,
    payment_allocation_difference numeric(15,2) NOT NULL,
    captured_at timestamptz NOT NULL DEFAULT now(),
    captured_by uuid,
    CONSTRAINT uq_night_audit_close_snapshot_tenant_id
        UNIQUE (tenant_id, id),
    CONSTRAINT uq_night_audit_close_snapshot_run
        UNIQUE (tenant_id, night_audit_run_id),
    CONSTRAINT uq_night_audit_close_snapshot_date
        UNIQUE (tenant_id, property_id, business_date),
    CONSTRAINT chk_night_audit_close_snapshot_schema
        CHECK (schema_version > 0),
    CONSTRAINT chk_night_audit_close_snapshot_currency
        CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_night_audit_close_snapshot_hash
        CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_night_audit_close_snapshot_rooms
        CHECK (
            available_rooms >= 0
            AND rooms_sold >= 0
            AND occupied_rooms >= 0
        ),
    CONSTRAINT chk_night_audit_close_snapshot_ratios
        CHECK (occupancy >= 0 AND adr >= 0 AND revpar >= 0),
    CONSTRAINT fk_night_audit_close_snapshot_property
        FOREIGN KEY (tenant_id, property_id)
        REFERENCES properties(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_night_audit_close_snapshot_run
        FOREIGN KEY (tenant_id, night_audit_run_id)
        REFERENCES night_audit_runs(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_night_audit_close_snapshot_actor
        FOREIGN KEY (tenant_id, captured_by)
        REFERENCES users(tenant_id, id) DEFERRABLE
);

CREATE OR REPLACE FUNCTION guard_night_audit_close_snapshot()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        RAISE EXCEPTION 'Night-audit close snapshots are immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_night_audit_close_snapshot_immutable
    BEFORE UPDATE OR DELETE ON night_audit_close_snapshots
    FOR EACH ROW EXECUTE FUNCTION guard_night_audit_close_snapshot();

CREATE OR REPLACE FUNCTION guard_night_audit_issue_override()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.override_allowed = false
       AND OLD.resolved_at IS NULL
       AND NEW.resolved_at IS NOT NULL THEN
        RAISE EXCEPTION 'Night-audit issue % cannot be overridden', OLD.issue_code;
    END IF;
    IF NEW.override_allowed IS DISTINCT FROM OLD.override_allowed THEN
        RAISE EXCEPTION 'Night-audit issue override policy is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_night_audit_issue_override
    BEFORE UPDATE ON night_audit_issues
    FOR EACH ROW EXECUTE FUNCTION guard_night_audit_issue_override();

ALTER TABLE night_audit_close_snapshots ENABLE ROW LEVEL SECURITY;
ALTER TABLE night_audit_close_snapshots FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON night_audit_close_snapshots
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

CREATE INDEX idx_night_audit_close_snapshot_property_date
    ON night_audit_close_snapshots (
        tenant_id, property_id, business_date DESC
    );

REVOKE DELETE, UPDATE ON night_audit_close_snapshots
FROM pms_app, pms_worker;
GRANT SELECT, INSERT ON night_audit_close_snapshots TO pms_app;
GRANT SELECT ON night_audit_close_snapshots TO pms_worker;

COMMENT ON TABLE night_audit_close_snapshots IS
    'Authoritative immutable close evidence. Reporting consumes this snapshot and never live operational tables.';
