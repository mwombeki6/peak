-- ================================================================================
-- Phase 3 Night Audit and Property Business Date
-- ================================================================================

ALTER TABLE properties
    ADD COLUMN IF NOT EXISTS business_date date DEFAULT CURRENT_DATE NOT NULL,
    ADD COLUMN IF NOT EXISTS last_night_audit_at timestamp with time zone;

-- Grant access to pms_app and pms_worker for property date updates
GRANT UPDATE(business_date, last_night_audit_at) ON properties TO pms_app;
GRANT UPDATE(business_date, last_night_audit_at) ON properties TO pms_worker;

-- Ensure night_audit_runs has a unique constraint to prevent duplicate runs for the same date
-- Only one completed or running audit per property per date
CREATE UNIQUE INDEX IF NOT EXISTS idx_night_audit_runs_property_date_active
    ON night_audit_runs (tenant_id, property_id, audit_date)
    WHERE status IN ('running', 'completed');
