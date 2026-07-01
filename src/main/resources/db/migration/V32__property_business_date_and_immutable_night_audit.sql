ALTER TABLE properties
    ADD COLUMN IF NOT EXISTS timezone varchar(50) NOT NULL DEFAULT 'Africa/Dar_es_Salaam',
    ADD COLUMN IF NOT EXISTS business_date_offset integer NOT NULL DEFAULT 0;

ALTER TABLE properties
    ADD CONSTRAINT chk_properties_business_date_offset
        CHECK (business_date_offset BETWEEN -1 AND 1) NOT VALID;

ALTER TABLE night_audit_runs
    ADD COLUMN IF NOT EXISTS attempt_no integer NOT NULL DEFAULT 1;

ALTER TABLE night_audit_runs
    ADD CONSTRAINT chk_night_audit_runs_attempt_no
        CHECK (attempt_no > 0) NOT VALID;

DROP INDEX IF EXISTS idx_night_audit_property_date;

CREATE UNIQUE INDEX idx_night_audit_property_date_attempt
    ON night_audit_runs (tenant_id, property_id, audit_date, attempt_no);

CREATE UNIQUE INDEX idx_night_audit_property_date_completed
    ON night_audit_runs (tenant_id, property_id, audit_date)
    WHERE status = 'completed';

CREATE OR REPLACE FUNCTION guard_night_audit_run_immutability() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Night audit runs are append-only';
    END IF;
    IF OLD.status = 'completed' THEN
        RAISE EXCEPTION 'Completed night audit runs are immutable';
    END IF;
    IF (NEW.tenant_id, NEW.property_id, NEW.audit_date, NEW.attempt_no, NEW.created_at)
       IS DISTINCT FROM
       (OLD.tenant_id, OLD.property_id, OLD.audit_date, OLD.attempt_no, OLD.created_at) THEN
        RAISE EXCEPTION 'Night audit run identity is immutable';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_night_audit_run_immutability ON night_audit_runs;
CREATE TRIGGER trg_night_audit_run_immutability
    BEFORE UPDATE OR DELETE ON night_audit_runs
    FOR EACH ROW EXECUTE FUNCTION guard_night_audit_run_immutability();

REVOKE DELETE ON TABLE night_audit_runs, night_audit_issues FROM pms_app, pms_worker;
