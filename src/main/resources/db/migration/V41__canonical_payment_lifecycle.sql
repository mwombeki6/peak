-- Phase 3 closure: canonical payment lifecycle. V1 remains immutable.

DROP TRIGGER IF EXISTS trg_payment_transactions_financial_immutability
    ON payment_transactions;
DROP FUNCTION IF EXISTS guard_terminal_payment_transaction();

ALTER TABLE payment_transactions
    DISABLE TRIGGER trg_lifecycle_payment_transactions;

ALTER TABLE payment_transactions
    DROP CONSTRAINT IF EXISTS chk_payment_transactions_status;

UPDATE payment_transactions
SET status = CASE status
        WHEN 'confirmed' THEN 'posted'
        WHEN 'cancelled' THEN 'expired'
        ELSE lower(status)
    END;

ALTER TABLE payment_transactions
    ADD COLUMN IF NOT EXISTS posted_at timestamptz,
    ADD COLUMN IF NOT EXISTS expired_at timestamptz,
    ADD COLUMN IF NOT EXISTS reconciled_at timestamptz,
    ADD COLUMN IF NOT EXISTS provider_status text,
    ADD COLUMN IF NOT EXISTS status_version bigint NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS expires_at timestamptz,
    ADD COLUMN IF NOT EXISTS next_status_check_at timestamptz,
    ADD COLUMN IF NOT EXISTS last_status_check_at timestamptz,
    ADD COLUMN IF NOT EXISTS refunded_amount numeric(15,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS refund_of_transaction_id uuid,
    ADD COLUMN IF NOT EXISTS external_refund_evidence text,
    ADD COLUMN IF NOT EXISTS refund_reason text;

UPDATE payment_transactions
SET posted_at = COALESCE(posted_at, confirmed_at, initiated_at)
WHERE status IN (
    'posted',
    'reconciled',
    'reversed',
    'partially_refunded',
    'refunded'
);

UPDATE payment_transactions
SET expired_at = COALESCE(expired_at, failed_at, updated_at)
WHERE status = 'expired';

UPDATE payment_transactions
SET reconciled_at = COALESCE(reconciled_at, updated_at)
WHERE status = 'reconciled';

ALTER TABLE payment_transactions
    ENABLE TRIGGER trg_lifecycle_payment_transactions;

ALTER TABLE payment_transactions
    ADD CONSTRAINT chk_payment_transactions_status
        CHECK (status IN (
            'created',
            'initiated',
            'pending',
            'posted',
            'failed',
            'expired',
            'reversed',
            'partially_refunded',
            'refunded',
            'reconciled'
        )),
    ADD CONSTRAINT chk_payment_transactions_refunded_amount
        CHECK (
            refunded_amount >= 0
            AND refunded_amount <= amount
            AND (refunded_amount = 0 OR transaction_type = 'collection')
        ),
    ADD CONSTRAINT chk_payment_transactions_canonical_timestamps
        CHECK (
            (status NOT IN (
                'posted',
                'reconciled',
                'reversed',
                'partially_refunded',
                'refunded'
            ) OR posted_at IS NOT NULL)
            AND (status <> 'failed' OR failed_at IS NOT NULL)
            AND (status <> 'expired' OR expired_at IS NOT NULL)
            AND (status <> 'reconciled' OR reconciled_at IS NOT NULL)
        ) NOT VALID,
    ADD CONSTRAINT fk_payment_transactions_refund_of
        FOREIGN KEY (tenant_id, refund_of_transaction_id)
        REFERENCES payment_transactions(tenant_id, id)
        DEFERRABLE NOT VALID,
    ADD CONSTRAINT chk_payment_transactions_refund_link
        CHECK (
            (transaction_type = 'refund'
             AND refund_of_transaction_id IS NOT NULL
             AND reversal_of_transaction_id IS NULL)
            OR
            (transaction_type <> 'refund'
             AND refund_of_transaction_id IS NULL)
        ) NOT VALID;

CREATE INDEX IF NOT EXISTS idx_payment_transactions_status_poll
    ON payment_transactions (
        tenant_id,
        status,
        next_status_check_at,
        initiated_at
    )
    WHERE status IN ('created', 'initiated', 'pending');

CREATE INDEX IF NOT EXISTS idx_payment_transactions_refund_of
    ON payment_transactions (tenant_id, refund_of_transaction_id)
    WHERE refund_of_transaction_id IS NOT NULL;

CREATE OR REPLACE FUNCTION enforce_payment_transaction_lifecycle()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_transition_allowed boolean;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Payment transactions are append-only';
    END IF;

    IF NEW.refunded_amount < OLD.refunded_amount THEN
        RAISE EXCEPTION 'Refunded amount cannot decrease';
    END IF;
    IF NEW.refunded_amount > NEW.amount THEN
        RAISE EXCEPTION 'Refunded amount exceeds the original amount';
    END IF;

    IF OLD.status IN (
        'posted',
        'reconciled',
        'reversed',
        'partially_refunded',
        'refunded'
    )
       AND (
           NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
           OR NEW.property_id IS DISTINCT FROM OLD.property_id
           OR NEW.provider_account_id IS DISTINCT FROM OLD.provider_account_id
           OR NEW.folio_id IS DISTINCT FROM OLD.folio_id
           OR NEW.pos_order_id IS DISTINCT FROM OLD.pos_order_id
           OR NEW.transaction_direction IS DISTINCT FROM OLD.transaction_direction
           OR NEW.transaction_type IS DISTINCT FROM OLD.transaction_type
           OR NEW.internal_reference IS DISTINCT FROM OLD.internal_reference
           OR NEW.provider_reference IS DISTINCT FROM OLD.provider_reference
           OR NEW.payer_identifier IS DISTINCT FROM OLD.payer_identifier
           OR NEW.payee_identifier IS DISTINCT FROM OLD.payee_identifier
           OR NEW.amount IS DISTINCT FROM OLD.amount
           OR NEW.fee_amount IS DISTINCT FROM OLD.fee_amount
           OR NEW.currency IS DISTINCT FROM OLD.currency
           OR NEW.idempotency_key_id IS DISTINCT FROM OLD.idempotency_key_id
           OR NEW.reversal_of_transaction_id
                IS DISTINCT FROM OLD.reversal_of_transaction_id
           OR NEW.refund_of_transaction_id
                IS DISTINCT FROM OLD.refund_of_transaction_id
           OR NEW.external_refund_evidence
                IS DISTINCT FROM OLD.external_refund_evidence
           OR NEW.refund_reason IS DISTINCT FROM OLD.refund_reason
       ) THEN
        RAISE EXCEPTION 'Posted payment financial fields are immutable';
    END IF;

    IF NEW.status IS DISTINCT FROM OLD.status THEN
        v_transition_allowed := CASE OLD.status
            WHEN 'created' THEN NEW.status IN (
                'initiated', 'pending', 'failed', 'expired'
            )
            WHEN 'initiated' THEN NEW.status IN (
                'pending', 'posted', 'failed', 'expired'
            )
            WHEN 'pending' THEN NEW.status IN (
                'posted', 'failed', 'expired'
            )
            WHEN 'posted' THEN NEW.status IN (
                'reconciled', 'reversed', 'partially_refunded', 'refunded'
            )
            WHEN 'reconciled' THEN NEW.status IN (
                'partially_refunded', 'refunded'
            )
            WHEN 'partially_refunded' THEN NEW.status = 'refunded'
            ELSE false
        END;
        IF NOT v_transition_allowed THEN
            RAISE EXCEPTION 'Invalid payment transition from % to %',
                OLD.status, NEW.status;
        END IF;
        NEW.status_version := OLD.status_version + 1;
    ELSIF NEW.status_version IS DISTINCT FROM OLD.status_version THEN
        RAISE EXCEPTION 'Payment status version is managed by the database';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_payment_transaction_lifecycle
    BEFORE UPDATE OR DELETE ON payment_transactions
    FOR EACH ROW EXECUTE FUNCTION enforce_payment_transaction_lifecycle();

ALTER TABLE payment_provider_accounts
    ADD COLUMN IF NOT EXISTS client_id text,
    ADD COLUMN IF NOT EXISTS api_key_secret_ref text,
    ADD COLUMN IF NOT EXISTS checksum_key_secret_ref text,
    ADD COLUMN IF NOT EXISTS provider_account_reference text;

UPDATE payment_provider_accounts
SET client_id = COALESCE(client_id, merchant_id),
    api_key_secret_ref = COALESCE(api_key_secret_ref, secret_ref),
    checksum_key_secret_ref = COALESCE(
        checksum_key_secret_ref,
        webhook_secret_ref
    );

ALTER TABLE payment_webhook_events
    ADD COLUMN IF NOT EXISTS payload_hash character(64),
    ADD COLUMN IF NOT EXISTS event_key text,
    ADD COLUMN IF NOT EXISTS provider_timestamp timestamptz,
    ADD COLUMN IF NOT EXISTS checksum_method text,
    ADD COLUMN IF NOT EXISTS processing_result text,
    ADD COLUMN IF NOT EXISTS replay_count bigint NOT NULL DEFAULT 0;

UPDATE payment_webhook_events
SET payload_hash = encode(
        digest(payload::text, 'sha256'),
        'hex'
    ),
    event_key = COALESCE(event_key, provider_event_id),
    processing_result = COALESCE(processing_result, status)
WHERE payload_hash IS NULL
   OR event_key IS NULL
   OR processing_result IS NULL;

ALTER TABLE payment_webhook_events
    ALTER COLUMN payload_hash SET NOT NULL,
    ALTER COLUMN event_key SET NOT NULL,
    ADD CONSTRAINT chk_payment_webhook_payload_hash
        CHECK (payload_hash ~ '^[0-9a-f]{64}$') NOT VALID,
    ADD CONSTRAINT chk_payment_webhook_replay_count
        CHECK (replay_count >= 0);

CREATE UNIQUE INDEX IF NOT EXISTS idx_payment_webhook_event_key
    ON payment_webhook_events (
        tenant_id,
        provider_account_id,
        event_key
    );

CREATE OR REPLACE FUNCTION guard_payment_webhook_evidence()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Payment webhook evidence is append-only';
    END IF;
    IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
       OR NEW.provider_account_id IS DISTINCT FROM OLD.provider_account_id
       OR NEW.provider_event_id IS DISTINCT FROM OLD.provider_event_id
       OR NEW.event_type IS DISTINCT FROM OLD.event_type
       OR NEW.payload IS DISTINCT FROM OLD.payload
       OR NEW.payload_hash IS DISTINCT FROM OLD.payload_hash
       OR NEW.event_key IS DISTINCT FROM OLD.event_key
       OR NEW.provider_timestamp IS DISTINCT FROM OLD.provider_timestamp
       OR NEW.checksum_method IS DISTINCT FROM OLD.checksum_method THEN
        RAISE EXCEPTION 'Payment webhook evidence fields are immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_payment_webhook_evidence
    BEFORE UPDATE OR DELETE ON payment_webhook_events
    FOR EACH ROW EXECUTE FUNCTION guard_payment_webhook_evidence();

COMMENT ON COLUMN payment_transactions.confirmed_at IS
    'Legacy compatibility timestamp; canonical payment completion uses posted_at.';
COMMENT ON COLUMN payment_transactions.initiated_at IS
    'Legacy creation timestamp retained for compatible V40 upgrades.';
COMMENT ON COLUMN payment_webhook_events.payload IS
    'Sanitized provider fields only; raw webhook payloads must never be stored.';

GRANT SELECT, INSERT, UPDATE ON TABLE
    payment_transactions,
    payment_webhook_events,
    payment_provider_accounts
TO pms_app;

GRANT SELECT, INSERT, UPDATE ON TABLE
    payment_transactions,
    payment_webhook_events,
    payment_provider_accounts
TO pms_worker;
