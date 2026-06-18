-- Migration for Platform Governance: Tracking tenant lifecycle changes
CREATE TABLE tenant_lifecycle_logs (
    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id uuid NOT NULL,
    operator_id uuid NOT NULL,
    previous_status character varying(50) NOT NULL,
    new_status character varying(50) NOT NULL,
    reason text,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE INDEX idx_tenant_lifecycle_logs_tenant_id ON tenant_lifecycle_logs(tenant_id);
