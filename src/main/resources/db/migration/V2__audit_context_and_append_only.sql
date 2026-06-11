-- ================================================================================
-- Peak PMS — Audit context hardening
-- ================================================================================

ALTER TABLE audit_logs
    ADD COLUMN correlation_id text,
    ADD COLUMN outcome text DEFAULT 'success' NOT NULL;

ALTER TABLE audit_logs
    ADD CONSTRAINT chk_audit_logs_outcome
    CHECK (outcome IN ('success', 'failure', 'denied'));

UPDATE audit_logs
SET correlation_id = 'legacy-' || id::text
WHERE correlation_id IS NULL;

ALTER TABLE audit_logs
    ALTER COLUMN correlation_id SET NOT NULL;

ALTER TABLE platform_audit_logs
    ADD COLUMN correlation_id text,
    ADD COLUMN outcome text DEFAULT 'success' NOT NULL;

ALTER TABLE platform_audit_logs
    ADD CONSTRAINT chk_platform_audit_logs_outcome
    CHECK (outcome IN ('success', 'failure', 'denied'));

UPDATE platform_audit_logs
SET correlation_id = 'legacy-' || id::text
WHERE correlation_id IS NULL;

ALTER TABLE platform_audit_logs
    ALTER COLUMN correlation_id SET NOT NULL;

CREATE INDEX idx_audit_logs_correlation
    ON audit_logs (tenant_id, correlation_id, created_at DESC);

CREATE INDEX idx_platform_audit_logs_correlation
    ON platform_audit_logs (correlation_id, created_at DESC);

CREATE FUNCTION prevent_audit_log_mutation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
  RAISE EXCEPTION 'Audit records are append-only';
END;
$$;

CREATE TRIGGER audit_logs_append_only
    BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION prevent_audit_log_mutation();

CREATE TRIGGER platform_audit_logs_append_only
    BEFORE UPDATE OR DELETE ON platform_audit_logs
    FOR EACH ROW EXECUTE FUNCTION prevent_audit_log_mutation();
