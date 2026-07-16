-- Payment history is read by tenant/property in reverse initiation order.
-- Without this index, populated properties require a parallel scan and sort.
CREATE INDEX idx_payment_transactions_tenant_property_initiated
    ON payment_transactions (
        tenant_id,
        property_id,
        initiated_at DESC,
        id DESC
    )
    WHERE property_id IS NOT NULL;
