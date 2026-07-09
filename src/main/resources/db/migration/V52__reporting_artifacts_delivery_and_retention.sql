-- Phase 5: reporting artifacts, append-only attempts, retention, and delivery expiry.

ALTER TABLE report_catalog
    ADD COLUMN IF NOT EXISTS generator_available boolean NOT NULL DEFAULT false;

UPDATE report_catalog
SET generator_available = report_code IN (
        'daily_management_summary',
        'night_audit_close'
    ),
    supports_email = CASE
        WHEN report_code IN ('daily_management_summary', 'night_audit_close')
            THEN true
        ELSE supports_email
    END,
    supports_whatsapp = CASE
        WHEN report_code IN ('daily_management_summary', 'night_audit_close')
            THEN true
        ELSE supports_whatsapp
    END;

INSERT INTO report_catalog (
    report_code, module_id, name, description, scope, sensitivity_level,
    supports_email, supports_sms, supports_whatsapp, supports_in_app,
    default_format, is_active, display_order, generator_available
) VALUES
    (
        'daily_management_summary', 'reports', 'Daily Management Summary',
        'Daily rooms, revenue, payments, fiscal and operational close summary',
        'property', 'confidential', true, false, true, false,
        'pdf', true, 10, true
    ),
    (
        'night_audit_close', 'reports', 'Night Audit Close',
        'Authoritative night-audit reconciliation and close evidence',
        'property', 'regulated', true, false, true, false,
        'pdf', true, 20, true
    )
ON CONFLICT (report_code) DO UPDATE SET
    module_id = EXCLUDED.module_id,
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    scope = EXCLUDED.scope,
    sensitivity_level = EXCLUDED.sensitivity_level,
    supports_email = EXCLUDED.supports_email,
    supports_sms = EXCLUDED.supports_sms,
    supports_whatsapp = EXCLUDED.supports_whatsapp,
    supports_in_app = EXCLUDED.supports_in_app,
    default_format = EXCLUDED.default_format,
    is_active = EXCLUDED.is_active,
    display_order = EXCLUDED.display_order,
    generator_available = EXCLUDED.generator_available,
    updated_at = now();

ALTER TABLE report_runs
    ADD COLUMN IF NOT EXISTS close_snapshot_id uuid,
    ADD COLUMN IF NOT EXISTS requested_by uuid,
    ADD COLUMN IF NOT EXISTS run_source text NOT NULL DEFAULT 'manual',
    ADD COLUMN IF NOT EXISTS generation_attempts integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_attempt_at timestamptz;

ALTER TABLE report_runs
    ADD CONSTRAINT fk_report_runs_close_snapshot
        FOREIGN KEY (tenant_id, close_snapshot_id)
        REFERENCES night_audit_close_snapshots(tenant_id, id)
        DEFERRABLE NOT VALID,
    ADD CONSTRAINT chk_report_runs_source
        CHECK (run_source IN ('night_audit', 'manual', 'retry')),
    ADD CONSTRAINT chk_report_runs_generation_attempts
        CHECK (generation_attempts >= 0);

CREATE TABLE report_artifacts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    report_run_id uuid NOT NULL,
    report_code text NOT NULL,
    business_date date NOT NULL,
    object_key text NOT NULL,
    bucket_name text NOT NULL,
    content_type text NOT NULL DEFAULT 'application/pdf',
    content_length bigint NOT NULL,
    content_hash character(64) NOT NULL,
    storage_etag text,
    retention_days integer NOT NULL,
    expires_at timestamptz NOT NULL,
    generated_at timestamptz NOT NULL DEFAULT now(),
    expired_at timestamptz,
    object_deleted_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_report_artifact_run UNIQUE (tenant_id, report_run_id),
    CONSTRAINT uq_report_artifact_object UNIQUE (bucket_name, object_key),
    CONSTRAINT chk_report_artifact_hash
        CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_report_artifact_pdf
        CHECK (content_type = 'application/pdf' AND content_length > 0),
    CONSTRAINT chk_report_artifact_retention
        CHECK (retention_days BETWEEN 30 AND 3650),
    CONSTRAINT chk_report_artifact_expiry
        CHECK (expires_at > generated_at),
    CONSTRAINT fk_report_artifact_property
        FOREIGN KEY (tenant_id, property_id)
        REFERENCES properties(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_report_artifact_run
        FOREIGN KEY (tenant_id, report_run_id)
        REFERENCES report_runs(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_report_artifact_catalog
        FOREIGN KEY (report_code) REFERENCES report_catalog(report_code)
);

ALTER TABLE report_deliveries
    ADD COLUMN IF NOT EXISTS report_code text,
    ADD COLUMN IF NOT EXISTS link_expires_at timestamptz,
    ADD COLUMN IF NOT EXISTS retry_requested_at timestamptz,
    ADD COLUMN IF NOT EXISTS retry_requested_by uuid;

UPDATE report_deliveries delivery
SET report_code = run.report_code
FROM report_runs run
WHERE run.tenant_id = delivery.tenant_id
  AND run.id = delivery.report_run_id
  AND delivery.report_code IS NULL;

ALTER TABLE report_deliveries
    ADD CONSTRAINT chk_report_delivery_link_expiry
        CHECK (
            link_expires_at IS NULL
            OR link_expires_at > queued_at
        ) NOT VALID,
    ADD CONSTRAINT fk_report_delivery_catalog
        FOREIGN KEY (report_code) REFERENCES report_catalog(report_code)
        NOT VALID;

CREATE TABLE report_delivery_attempts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    report_delivery_id uuid NOT NULL,
    attempt_number integer NOT NULL,
    channel_type text NOT NULL,
    provider_code text,
    provider_message_id text,
    status text NOT NULL,
    error_code text,
    error_message text,
    link_expires_at timestamptz NOT NULL,
    started_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_report_delivery_attempt
        UNIQUE (tenant_id, report_delivery_id, attempt_number),
    CONSTRAINT chk_report_delivery_attempt_number
        CHECK (attempt_number > 0),
    CONSTRAINT chk_report_delivery_attempt_channel
        CHECK (channel_type IN ('email', 'whatsapp')),
    CONSTRAINT chk_report_delivery_attempt_status
        CHECK (status IN ('sending', 'sent', 'delivered', 'failed')),
    CONSTRAINT chk_report_delivery_attempt_expiry
        CHECK (link_expires_at > started_at),
    CONSTRAINT fk_report_delivery_attempt_delivery
        FOREIGN KEY (tenant_id, report_delivery_id)
        REFERENCES report_deliveries(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_report_delivery_attempt_property
        FOREIGN KEY (tenant_id, property_id)
        REFERENCES properties(tenant_id, id) DEFERRABLE
);

CREATE TABLE reporting_retention_policies (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    property_id uuid,
    retention_days integer NOT NULL,
    created_by uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_reporting_retention_scope
        UNIQUE NULLS NOT DISTINCT (tenant_id, property_id),
    CONSTRAINT chk_reporting_retention_days
        CHECK (retention_days BETWEEN 30 AND 3650),
    CONSTRAINT fk_reporting_retention_property
        FOREIGN KEY (tenant_id, property_id)
        REFERENCES properties(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_reporting_retention_actor
        FOREIGN KEY (tenant_id, created_by)
        REFERENCES users(tenant_id, id) DEFERRABLE
);

CREATE OR REPLACE FUNCTION guard_report_artifact_evidence()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Report artifact metadata is retained';
    END IF;
    IF (
        NEW.tenant_id,
        NEW.property_id,
        NEW.report_run_id,
        NEW.report_code,
        NEW.business_date,
        NEW.object_key,
        NEW.bucket_name,
        NEW.content_type,
        NEW.content_length,
        NEW.content_hash,
        NEW.retention_days,
        NEW.expires_at,
        NEW.generated_at,
        NEW.created_at
    ) IS DISTINCT FROM (
        OLD.tenant_id,
        OLD.property_id,
        OLD.report_run_id,
        OLD.report_code,
        OLD.business_date,
        OLD.object_key,
        OLD.bucket_name,
        OLD.content_type,
        OLD.content_length,
        OLD.content_hash,
        OLD.retention_days,
        OLD.expires_at,
        OLD.generated_at,
        OLD.created_at
    ) THEN
        RAISE EXCEPTION 'Report artifact evidence fields are immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_report_artifact_evidence
    BEFORE UPDATE OR DELETE ON report_artifacts
    FOR EACH ROW EXECUTE FUNCTION guard_report_artifact_evidence();

CREATE OR REPLACE FUNCTION guard_report_delivery_attempt_append_only()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        RAISE EXCEPTION 'Report delivery attempts are append-only';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_report_delivery_attempt_append_only
    BEFORE UPDATE OR DELETE ON report_delivery_attempts
    FOR EACH ROW EXECUTE FUNCTION guard_report_delivery_attempt_append_only();

ALTER TABLE report_artifacts ENABLE ROW LEVEL SECURITY;
ALTER TABLE report_artifacts FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON report_artifacts
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());
ALTER TABLE report_delivery_attempts ENABLE ROW LEVEL SECURITY;
ALTER TABLE report_delivery_attempts FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON report_delivery_attempts
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());
ALTER TABLE reporting_retention_policies ENABLE ROW LEVEL SECURITY;
ALTER TABLE reporting_retention_policies FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON reporting_retention_policies
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

CREATE INDEX idx_report_runs_generation_queue
    ON report_runs (status, created_at)
    WHERE status IN ('queued', 'failed');
WITH duplicate_recipient AS (
    SELECT id,
           row_number() OVER (
               PARTITION BY tenant_id, subscription_id, contact_id,
                            contact_channel_id
               ORDER BY created_at, id
           ) AS duplicate_number
    FROM report_subscription_recipients
    WHERE is_enabled = true
)
UPDATE report_subscription_recipients recipient
SET is_enabled = false,
    updated_at = now()
FROM duplicate_recipient duplicate
WHERE duplicate.id = recipient.id
  AND duplicate.duplicate_number > 1;
CREATE UNIQUE INDEX uq_report_subscription_enabled_recipient
    ON report_subscription_recipients (
        tenant_id, subscription_id, contact_id, contact_channel_id
    )
    WHERE is_enabled = true;
CREATE INDEX idx_report_artifacts_expiry
    ON report_artifacts (expires_at)
    WHERE object_deleted_at IS NULL;
CREATE INDEX idx_report_deliveries_retry
    ON report_deliveries (status, next_attempt_at)
    WHERE status IN ('queued', 'failed', 'retry_scheduled');
CREATE INDEX idx_report_delivery_attempts_delivery
    ON report_delivery_attempts (
        tenant_id, report_delivery_id, attempt_number DESC
    );

REVOKE DELETE ON report_artifacts, report_delivery_attempts
FROM pms_app, pms_worker;
