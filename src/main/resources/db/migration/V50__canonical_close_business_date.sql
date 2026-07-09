-- Phase 5: canonical business-date attribution for every property close input.
-- Existing rows are attributed using the owning property's timezone and retain
-- explicit provenance so an upgrade from a populated V49 database is auditable.

ALTER TABLE folio_charges
    ADD COLUMN IF NOT EXISTS business_date date,
    ADD COLUMN IF NOT EXISTS business_date_provenance text;
ALTER TABLE folio_payments
    ADD COLUMN IF NOT EXISTS business_date date,
    ADD COLUMN IF NOT EXISTS business_date_provenance text;
ALTER TABLE invoices
    ADD COLUMN IF NOT EXISTS business_date date,
    ADD COLUMN IF NOT EXISTS business_date_provenance text;
ALTER TABLE payment_transactions
    ADD COLUMN IF NOT EXISTS business_date date,
    ADD COLUMN IF NOT EXISTS business_date_provenance text;
ALTER TABLE pos_orders
    ADD COLUMN IF NOT EXISTS business_date date,
    ADD COLUMN IF NOT EXISTS business_date_provenance text;
ALTER TABLE journal_entries
    ADD COLUMN IF NOT EXISTS business_date date,
    ADD COLUMN IF NOT EXISTS business_date_provenance text;
ALTER TABLE fiscal_receipts
    ADD COLUMN IF NOT EXISTS business_date date,
    ADD COLUMN IF NOT EXISTS business_date_provenance text;
ALTER TABLE stock_movements
    ADD COLUMN IF NOT EXISTS business_date date,
    ADD COLUMN IF NOT EXISTS business_date_provenance text;
ALTER TABLE cash_sessions
    ADD COLUMN IF NOT EXISTS business_date date,
    ADD COLUMN IF NOT EXISTS business_date_provenance text;
ALTER TABLE pos_sessions
    ADD COLUMN IF NOT EXISTS business_date date,
    ADD COLUMN IF NOT EXISTS business_date_provenance text;

-- Backfills are migration-owned data corrections. Runtime lifecycle triggers
-- intentionally block operational writes for tenants that are not active yet,
-- so bind a platform context locally for this migration transaction.
SELECT set_config('app.current_tenant_id', '', true);
SELECT set_config('app.current_tenant_user_id', '', true);
SELECT set_config(
    'app.current_platform_user_id',
    '00000000-0000-0000-0000-000000000050',
    true
);

UPDATE folio_charges input
SET business_date = (
        input.posted_at AT TIME ZONE property.timezone
    )::date + property.business_date_offset,
    business_date_provenance = 'backfilled_v50'
FROM properties property
WHERE property.tenant_id = input.tenant_id
  AND property.id = input.property_id
  AND input.business_date IS NULL;

UPDATE folio_payments input
SET business_date = (
        COALESCE(input.paid_at, input.created_at)
        AT TIME ZONE property.timezone
    )::date + property.business_date_offset,
    business_date_provenance = 'backfilled_v50'
FROM properties property
WHERE property.tenant_id = input.tenant_id
  AND property.id = input.property_id
  AND input.business_date IS NULL;

UPDATE invoices input
SET business_date = (
        COALESCE(input.issued_at, input.created_at)
        AT TIME ZONE property.timezone
    )::date + property.business_date_offset,
    business_date_provenance = 'backfilled_v50'
FROM properties property
WHERE property.tenant_id = input.tenant_id
  AND property.id = input.property_id
  AND input.business_date IS NULL;

UPDATE payment_transactions input
SET business_date = (
        COALESCE(input.posted_at, input.confirmed_at, input.initiated_at)
        AT TIME ZONE property.timezone
    )::date + property.business_date_offset,
    business_date_provenance = 'backfilled_v50'
FROM properties property
WHERE property.tenant_id = input.tenant_id
  AND property.id = input.property_id
  AND input.business_date IS NULL;

UPDATE pos_orders input
SET business_date = (
        COALESCE(input.settled_at, input.created_at)
        AT TIME ZONE property.timezone
    )::date + property.business_date_offset,
    business_date_provenance = 'backfilled_v50'
FROM properties property
WHERE property.tenant_id = input.tenant_id
  AND property.id = input.property_id
  AND input.business_date IS NULL;

UPDATE journal_entries input
SET business_date = input.entry_date,
    business_date_provenance = 'backfilled_v50'
WHERE input.business_date IS NULL;

UPDATE fiscal_receipts input
SET business_date = (
        input.submitted_at AT TIME ZONE property.timezone
    )::date + property.business_date_offset,
    business_date_provenance = 'backfilled_v50'
FROM properties property
WHERE property.tenant_id = input.tenant_id
  AND property.id = input.property_id
  AND input.business_date IS NULL;

UPDATE stock_movements input
SET business_date = (
        input.created_at AT TIME ZONE property.timezone
    )::date + property.business_date_offset,
    business_date_provenance = 'backfilled_v50'
FROM properties property
WHERE property.tenant_id = input.tenant_id
  AND property.id = input.property_id
  AND input.business_date IS NULL;

UPDATE cash_sessions input
SET business_date = (
        input.opened_at AT TIME ZONE property.timezone
    )::date + property.business_date_offset,
    business_date_provenance = 'backfilled_v50'
FROM properties property
WHERE property.tenant_id = input.tenant_id
  AND property.id = input.property_id
  AND input.business_date IS NULL;

UPDATE pos_sessions input
SET business_date = (
        input.opened_at AT TIME ZONE property.timezone
    )::date + property.business_date_offset,
    business_date_provenance = 'backfilled_v50'
FROM outlets outlet
JOIN properties property
  ON property.tenant_id = outlet.tenant_id
 AND property.id = outlet.property_id
WHERE outlet.tenant_id = input.tenant_id
  AND outlet.id = input.outlet_id
  AND input.business_date IS NULL;

ALTER TABLE folio_charges
    ALTER COLUMN business_date SET NOT NULL,
    ALTER COLUMN business_date_provenance SET NOT NULL,
    ALTER COLUMN business_date_provenance SET DEFAULT 'runtime';
ALTER TABLE folio_payments
    ALTER COLUMN business_date_provenance SET DEFAULT 'runtime';
ALTER TABLE invoices
    ALTER COLUMN business_date_provenance SET DEFAULT 'runtime';
ALTER TABLE payment_transactions
    ALTER COLUMN business_date_provenance SET DEFAULT 'runtime';
ALTER TABLE pos_orders
    ALTER COLUMN business_date SET NOT NULL,
    ALTER COLUMN business_date_provenance SET NOT NULL,
    ALTER COLUMN business_date_provenance SET DEFAULT 'runtime';
ALTER TABLE journal_entries
    ALTER COLUMN business_date SET NOT NULL,
    ALTER COLUMN business_date_provenance SET NOT NULL,
    ALTER COLUMN business_date_provenance SET DEFAULT 'runtime';
ALTER TABLE fiscal_receipts
    ALTER COLUMN business_date_provenance SET DEFAULT 'runtime';
ALTER TABLE stock_movements
    ALTER COLUMN business_date SET NOT NULL,
    ALTER COLUMN business_date_provenance SET NOT NULL,
    ALTER COLUMN business_date_provenance SET DEFAULT 'runtime';
ALTER TABLE cash_sessions
    ALTER COLUMN business_date SET NOT NULL,
    ALTER COLUMN business_date_provenance SET NOT NULL,
    ALTER COLUMN business_date_provenance SET DEFAULT 'runtime';
ALTER TABLE pos_sessions
    ALTER COLUMN business_date SET NOT NULL,
    ALTER COLUMN business_date_provenance SET NOT NULL,
    ALTER COLUMN business_date_provenance SET DEFAULT 'runtime';

ALTER TABLE folio_charges
    ADD CONSTRAINT chk_folio_charges_business_date_provenance
        CHECK (business_date_provenance IN ('runtime', 'backfilled_v50'));
ALTER TABLE folio_payments
    ADD CONSTRAINT chk_folio_payments_business_date_provenance
        CHECK (
            business_date_provenance IS NULL
            OR business_date_provenance IN ('runtime', 'backfilled_v50')
        );
ALTER TABLE invoices
    ADD CONSTRAINT chk_invoices_business_date_provenance
        CHECK (
            business_date_provenance IS NULL
            OR business_date_provenance IN ('runtime', 'backfilled_v50')
        );
ALTER TABLE payment_transactions
    ADD CONSTRAINT chk_payment_transactions_business_date_provenance
        CHECK (
            business_date_provenance IS NULL
            OR business_date_provenance IN ('runtime', 'backfilled_v50')
        );
ALTER TABLE pos_orders
    ADD CONSTRAINT chk_pos_orders_business_date_provenance
        CHECK (business_date_provenance IN ('runtime', 'backfilled_v50'));
ALTER TABLE journal_entries
    ADD CONSTRAINT chk_journal_entries_business_date_provenance
        CHECK (business_date_provenance IN ('runtime', 'backfilled_v50'));
ALTER TABLE fiscal_receipts
    ADD CONSTRAINT chk_fiscal_receipts_business_date_provenance
        CHECK (
            business_date_provenance IS NULL
            OR business_date_provenance IN ('runtime', 'backfilled_v50')
        );
ALTER TABLE stock_movements
    ADD CONSTRAINT chk_stock_movements_business_date_provenance
        CHECK (business_date_provenance IN ('runtime', 'backfilled_v50'));
ALTER TABLE cash_sessions
    ADD CONSTRAINT chk_cash_sessions_business_date_provenance
        CHECK (business_date_provenance IN ('runtime', 'backfilled_v50'));
ALTER TABLE pos_sessions
    ADD CONSTRAINT chk_pos_sessions_business_date_provenance
        CHECK (business_date_provenance IN ('runtime', 'backfilled_v50'));

CREATE OR REPLACE FUNCTION attribute_close_input_business_date()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.business_date IS NULL AND NEW.property_id IS NOT NULL THEN
            SELECT property.business_date
            INTO NEW.business_date
            FROM properties property
            WHERE property.tenant_id = NEW.tenant_id
              AND property.id = NEW.property_id;
        END IF;
        NEW.business_date_provenance :=
            COALESCE(NEW.business_date_provenance, 'runtime');
        RETURN NEW;
    END IF;
    IF NEW.business_date IS DISTINCT FROM OLD.business_date
       OR NEW.business_date_provenance IS DISTINCT FROM
          OLD.business_date_provenance THEN
        RAISE EXCEPTION 'Close input business-date attribution is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_folio_charges_business_date
    BEFORE INSERT OR UPDATE ON folio_charges
    FOR EACH ROW EXECUTE FUNCTION attribute_close_input_business_date();
CREATE TRIGGER trg_folio_payments_business_date
    BEFORE INSERT OR UPDATE ON folio_payments
    FOR EACH ROW EXECUTE FUNCTION attribute_close_input_business_date();
CREATE TRIGGER trg_invoices_business_date
    BEFORE INSERT OR UPDATE ON invoices
    FOR EACH ROW EXECUTE FUNCTION attribute_close_input_business_date();
CREATE TRIGGER trg_payment_transactions_business_date
    BEFORE INSERT OR UPDATE ON payment_transactions
    FOR EACH ROW EXECUTE FUNCTION attribute_close_input_business_date();
CREATE TRIGGER trg_pos_orders_business_date
    BEFORE INSERT OR UPDATE ON pos_orders
    FOR EACH ROW EXECUTE FUNCTION attribute_close_input_business_date();
CREATE TRIGGER trg_journal_entries_business_date
    BEFORE INSERT OR UPDATE ON journal_entries
    FOR EACH ROW EXECUTE FUNCTION attribute_close_input_business_date();
CREATE TRIGGER trg_fiscal_receipts_business_date
    BEFORE INSERT OR UPDATE ON fiscal_receipts
    FOR EACH ROW EXECUTE FUNCTION attribute_close_input_business_date();
CREATE TRIGGER trg_stock_movements_business_date
    BEFORE INSERT OR UPDATE ON stock_movements
    FOR EACH ROW EXECUTE FUNCTION attribute_close_input_business_date();
CREATE TRIGGER trg_cash_sessions_business_date
    BEFORE INSERT OR UPDATE ON cash_sessions
    FOR EACH ROW EXECUTE FUNCTION attribute_close_input_business_date();

CREATE OR REPLACE FUNCTION attribute_pos_session_business_date()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.business_date IS NULL THEN
            SELECT property.business_date
            INTO NEW.business_date
            FROM outlets outlet
            JOIN properties property
              ON property.tenant_id = outlet.tenant_id
             AND property.id = outlet.property_id
            WHERE outlet.tenant_id = NEW.tenant_id
              AND outlet.id = NEW.outlet_id;
        END IF;
        NEW.business_date_provenance :=
            COALESCE(NEW.business_date_provenance, 'runtime');
        RETURN NEW;
    END IF;
    IF NEW.business_date IS DISTINCT FROM OLD.business_date
       OR NEW.business_date_provenance IS DISTINCT FROM
          OLD.business_date_provenance THEN
        RAISE EXCEPTION 'Close input business-date attribution is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_pos_sessions_business_date
    BEFORE INSERT OR UPDATE ON pos_sessions
    FOR EACH ROW EXECUTE FUNCTION attribute_pos_session_business_date();

SELECT set_config('app.current_platform_user_id', '', true);

CREATE INDEX idx_folio_charges_close_date
    ON folio_charges (tenant_id, property_id, business_date, status);
CREATE INDEX idx_folio_payments_close_date
    ON folio_payments (tenant_id, property_id, business_date, status);
CREATE INDEX idx_payment_transactions_close_date
    ON payment_transactions (tenant_id, property_id, business_date, status);
CREATE INDEX idx_pos_orders_close_date
    ON pos_orders (tenant_id, property_id, business_date, status, settlement_status);
CREATE INDEX idx_journal_entries_close_date
    ON journal_entries (tenant_id, property_id, business_date, status);

COMMENT ON COLUMN folio_charges.business_date_provenance IS
    'runtime for authoritative attribution; backfilled_v50 for migration-derived attribution.';
