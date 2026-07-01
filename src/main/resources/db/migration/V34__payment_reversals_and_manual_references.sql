ALTER TABLE payment_transactions
    ADD COLUMN IF NOT EXISTS reversal_of_transaction_id uuid;

ALTER TABLE payment_transactions
    ADD CONSTRAINT fk_payment_transactions_reversal_of
        FOREIGN KEY (tenant_id, reversal_of_transaction_id)
        REFERENCES payment_transactions(tenant_id, id)
        DEFERRABLE NOT VALID;

CREATE UNIQUE INDEX idx_payment_transactions_one_reversal
    ON payment_transactions (tenant_id, reversal_of_transaction_id)
    WHERE reversal_of_transaction_id IS NOT NULL;

CREATE UNIQUE INDEX idx_folio_payments_one_reversal
    ON folio_payments (tenant_id, reversal_of)
    WHERE reversal_of IS NOT NULL AND deleted_at IS NULL;

CREATE OR REPLACE FUNCTION recalculate_folio_totals(p_folio_id uuid) RETURNS void
LANGUAGE plpgsql
AS $$
DECLARE
    v_folio record;
    v_subtotal numeric(15,2);
    v_tax numeric(15,2);
    v_charge_total numeric(15,2);
    v_paid numeric(15,2);
BEGIN
    SELECT *
    INTO v_folio
    FROM folios
    WHERE id = p_folio_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Folio % does not exist', p_folio_id;
    END IF;
    IF v_folio.status <> 'open' THEN
        RAISE EXCEPTION 'Cannot recalculate totals for % folio %', v_folio.status, p_folio_id;
    END IF;

    SELECT
        COALESCE(round(sum(fc.subtotal), 2), 0),
        COALESCE(round(sum(fc.tax_amount), 2), 0),
        COALESCE(round(sum(fc.amount), 2), 0)
    INTO v_subtotal, v_tax, v_charge_total
    FROM folio_charges fc
    WHERE fc.tenant_id = v_folio.tenant_id
      AND fc.folio_id = v_folio.id
      AND fc.status = 'POSTED'
      AND fc.deleted_at IS NULL;

    SELECT COALESCE(
        round(
            sum(
                CASE
                    WHEN fp.reversal_of IS NULL THEN fp.amount
                    ELSE -fp.amount
                END
            ),
            2
        ),
        0
    )
    INTO v_paid
    FROM folio_payments fp
    WHERE fp.tenant_id = v_folio.tenant_id
      AND fp.folio_id = v_folio.id
      AND fp.status = 'POSTED'
      AND fp.deleted_at IS NULL;

    UPDATE folios
    SET subtotal = v_subtotal,
        tax_amount = v_tax,
        total_amount = round(v_charge_total + service_charge + tourism_levy, 2),
        total_paid = v_paid,
        updated_at = now()
    WHERE id = v_folio.id;
END;
$$;

CREATE OR REPLACE FUNCTION guard_posted_folio_payment() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Posted folio payments cannot be deleted';
    END IF;
    IF OLD.status = 'POSTED'
       AND (
           NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
           OR NEW.property_id IS DISTINCT FROM OLD.property_id
           OR NEW.folio_id IS DISTINCT FROM OLD.folio_id
           OR NEW.payment_method IS DISTINCT FROM OLD.payment_method
           OR NEW.amount IS DISTINCT FROM OLD.amount
           OR NEW.currency_code IS DISTINCT FROM OLD.currency_code
           OR NEW.payment_transaction_id IS DISTINCT FROM OLD.payment_transaction_id
           OR NEW.cash_session_id IS DISTINCT FROM OLD.cash_session_id
           OR NEW.reversal_of IS DISTINCT FROM OLD.reversal_of
           OR NEW.paid_at IS DISTINCT FROM OLD.paid_at
           OR NEW.processed_by IS DISTINCT FROM OLD.processed_by
       ) THEN
        RAISE EXCEPTION 'Posted folio payment financial fields are immutable';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_folio_payments_financial_immutability ON folio_payments;
CREATE TRIGGER trg_folio_payments_financial_immutability
    BEFORE UPDATE OR DELETE ON folio_payments
    FOR EACH ROW EXECUTE FUNCTION guard_posted_folio_payment();

CREATE OR REPLACE FUNCTION guard_terminal_payment_transaction() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Payment transactions cannot be deleted';
    END IF;
    IF OLD.status IN ('confirmed', 'reversed')
       AND (
           NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
           OR NEW.property_id IS DISTINCT FROM OLD.property_id
           OR NEW.provider_account_id IS DISTINCT FROM OLD.provider_account_id
           OR NEW.folio_id IS DISTINCT FROM OLD.folio_id
           OR NEW.transaction_direction IS DISTINCT FROM OLD.transaction_direction
           OR NEW.transaction_type IS DISTINCT FROM OLD.transaction_type
           OR NEW.provider_reference IS DISTINCT FROM OLD.provider_reference
           OR NEW.internal_reference IS DISTINCT FROM OLD.internal_reference
           OR NEW.amount IS DISTINCT FROM OLD.amount
           OR NEW.currency IS DISTINCT FROM OLD.currency
           OR NEW.idempotency_key_id IS DISTINCT FROM OLD.idempotency_key_id
           OR NEW.reversal_of_transaction_id IS DISTINCT FROM OLD.reversal_of_transaction_id
       ) THEN
        RAISE EXCEPTION 'Terminal payment transaction financial fields are immutable';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_payment_transactions_financial_immutability ON payment_transactions;
CREATE TRIGGER trg_payment_transactions_financial_immutability
    BEFORE UPDATE OR DELETE ON payment_transactions
    FOR EACH ROW EXECUTE FUNCTION guard_terminal_payment_transaction();

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
    ('payments', 'payments.mobile.manual_reference', 'Record Manual Mobile Money',
     'POST', '/api/properties/:propertyId/payments/mobile-money/manual-reference',
     'payments.collect', 'property', 'staff_permission', 'property', true, true,
     'Record a verified manual mobile-money reference against a folio'),
    ('payments', 'payments.transactions.reverse', 'Reverse Payment',
     'POST', '/api/properties/:propertyId/payments/transactions/:transactionId/reverse',
     'payments.reverse', 'property', 'staff_permission', 'property', true, true,
     'Create an append-only linked reversal with a mandatory reason')
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
