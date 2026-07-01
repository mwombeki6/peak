CREATE TABLE fiscal_receipts (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    property_id UUID NOT NULL,
    invoice_id UUID NOT NULL UNIQUE,
    status TEXT NOT NULL,
    fiscal_reference TEXT,
    signed_payload TEXT,
    error_message TEXT,
    attempts INTEGER NOT NULL DEFAULT 0,
    last_attempt_at TIMESTAMPTZ,
    verified_at TIMESTAMPTZ,
    overridden BOOLEAN NOT NULL DEFAULT FALSE,
    override_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_fiscal_receipts_tenant_property ON fiscal_receipts(tenant_id, property_id);
CREATE INDEX idx_fiscal_receipts_status ON fiscal_receipts(status);

-- Permissions
GRANT SELECT, INSERT, UPDATE ON fiscal_receipts TO peak_app;
GRANT SELECT ON fiscal_receipts TO peak_read_only;
