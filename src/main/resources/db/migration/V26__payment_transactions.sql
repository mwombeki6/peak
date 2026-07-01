DO $$ 
BEGIN
    IF NOT EXISTS (SELECT FROM pg_tables WHERE schemaname = 'public' AND tablename = 'payment_transactions') THEN
        CREATE TABLE payment_transactions (
                                              id UUID PRIMARY KEY,
                                              tenant_id UUID NOT NULL,
                                              property_id UUID NOT NULL,
                                              pos_session_id UUID NOT NULL,
                                              folio_id UUID NOT NULL,
                                              amount NUMERIC(19, 4) NOT NULL,
                                              currency VARCHAR(3) NOT NULL DEFAULT 'TZS',
                                              payment_method VARCHAR(50) NOT NULL, -- 'CASH', 'MOBILE_MONEY'
                                              status VARCHAR(50) NOT NULL,         -- 'CREATED', 'POSTED', 'FAILED'
                                              provider_reference VARCHAR(255),     -- The M-Pesa / ClickPesa transaction ID number
                                              created_at TIMESTAMP NOT NULL,
                                              posted_at TIMESTAMP,

            -- Safety Constraints
                                              CONSTRAINT chk_positive_payment_amount CHECK (amount > 0),
                                              CONSTRAINT chk_valid_payment_method CHECK (payment_method IN ('CASH', 'MOBILE_MONEY')),
                                              CONSTRAINT chk_valid_payment_status CHECK (status IN ('CREATED', 'POSTED', 'FAILED')),
                                              CONSTRAINT fk_payment_pos_session FOREIGN KEY (pos_session_id) REFERENCES pos_sessions(id)
        );
    ELSE
        -- Table exists, ensure Phase 3 columns are present
        IF NOT EXISTS (SELECT FROM information_schema.columns WHERE table_name='payment_transactions' AND column_name='property_id') THEN
            ALTER TABLE payment_transactions ADD COLUMN property_id UUID;
        END IF;
        IF NOT EXISTS (SELECT FROM information_schema.columns WHERE table_name='payment_transactions' AND column_name='pos_session_id') THEN
            ALTER TABLE payment_transactions ADD COLUMN pos_session_id UUID;
        END IF;
        IF NOT EXISTS (SELECT FROM information_schema.columns WHERE table_name='payment_transactions' AND column_name='folio_id') THEN
            ALTER TABLE payment_transactions ADD COLUMN folio_id UUID;
        END IF;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_payments_folio ON payment_transactions(tenant_id, folio_id);