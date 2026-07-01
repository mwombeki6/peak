DO $$ 
BEGIN
    IF NOT EXISTS (SELECT FROM pg_tables WHERE schemaname = 'public' AND tablename = 'pos_sessions') THEN
        CREATE TABLE pos_sessions (
            id UUID PRIMARY KEY,
            tenant_id UUID NOT NULL,
            property_id UUID NOT NULL,
            opened_by VARCHAR(255) NOT NULL,
            closed_by VARCHAR(255),
            starting_float NUMERIC(19, 4) NOT NULL DEFAULT 0.0000,
            expected_amount NUMERIC(19, 4),
            actual_amount NUMERIC(19, 4),
            variance_amount NUMERIC(19, 4),
            status VARCHAR(50) NOT NULL, -- 'OPEN', 'CLOSED', 'PENDING_VARIANCE_APPROVAL'
            opened_at TIMESTAMP NOT NULL,
            closed_at TIMESTAMP,

            CONSTRAINT chk_positive_starting_float CHECK (starting_float >= 0),
            CONSTRAINT chk_valid_session_status CHECK (status IN ('OPEN', 'CLOSED', 'PENDING_VARIANCE_APPROVAL'))
        );
    ELSE
        -- Table exists in baseline, ensure Phase 3 columns are present
        IF NOT EXISTS (SELECT FROM information_schema.columns WHERE table_name='pos_sessions' AND column_name='property_id') THEN
            ALTER TABLE pos_sessions ADD COLUMN property_id UUID;
        END IF;
        IF NOT EXISTS (SELECT FROM information_schema.columns WHERE table_name='pos_sessions' AND column_name='opened_by') THEN
            ALTER TABLE pos_sessions ADD COLUMN opened_by VARCHAR(255);
        END IF;
        IF NOT EXISTS (SELECT FROM information_schema.columns WHERE table_name='pos_sessions' AND column_name='closed_by') THEN
            ALTER TABLE pos_sessions ADD COLUMN closed_by VARCHAR(255);
        END IF;
        IF NOT EXISTS (SELECT FROM information_schema.columns WHERE table_name='pos_sessions' AND column_name='starting_float') THEN
            ALTER TABLE pos_sessions ADD COLUMN starting_float NUMERIC(19, 4) NOT NULL DEFAULT 0.0000;
        END IF;
        IF NOT EXISTS (SELECT FROM information_schema.columns WHERE table_name='pos_sessions' AND column_name='expected_amount') THEN
            ALTER TABLE pos_sessions ADD COLUMN expected_amount NUMERIC(19, 4);
        END IF;
        IF NOT EXISTS (SELECT FROM information_schema.columns WHERE table_name='pos_sessions' AND column_name='actual_amount') THEN
            ALTER TABLE pos_sessions ADD COLUMN actual_amount NUMERIC(19, 4);
        END IF;
        IF NOT EXISTS (SELECT FROM information_schema.columns WHERE table_name='pos_sessions' AND column_name='variance_amount') THEN
            ALTER TABLE pos_sessions ADD COLUMN variance_amount NUMERIC(19, 4);
        END IF;
        IF NOT EXISTS (SELECT FROM information_schema.columns WHERE table_name='pos_sessions' AND column_name='status') THEN
            ALTER TABLE pos_sessions ADD COLUMN status VARCHAR(50);
        END IF;
        IF NOT EXISTS (SELECT FROM information_schema.columns WHERE table_name='pos_sessions' AND column_name='opened_at') THEN
            ALTER TABLE pos_sessions ADD COLUMN opened_at TIMESTAMP;
        END IF;
        IF NOT EXISTS (SELECT FROM information_schema.columns WHERE table_name='pos_sessions' AND column_name='closed_at') THEN
            ALTER TABLE pos_sessions ADD COLUMN closed_at TIMESTAMP;
        END IF;

        -- Drop legacy baseline constraints that might block Phase 3 logic
        IF EXISTS (SELECT FROM information_schema.columns WHERE table_name='pos_sessions' AND column_name='outlet_id') THEN
            ALTER TABLE pos_sessions ALTER COLUMN outlet_id DROP NOT NULL;
        END IF;
        IF EXISTS (SELECT FROM information_schema.columns WHERE table_name='pos_sessions' AND column_name='cashier_id') THEN
            ALTER TABLE pos_sessions ALTER COLUMN cashier_id DROP NOT NULL;
        END IF;
        IF EXISTS (SELECT FROM information_schema.columns WHERE table_name='pos_sessions' AND column_name='opening_float') THEN
            ALTER TABLE pos_sessions ALTER COLUMN opening_float DROP NOT NULL;
        END IF;
        IF EXISTS (SELECT FROM information_schema.columns WHERE table_name='pos_sessions' AND column_name='opening_time') THEN
            ALTER TABLE pos_sessions ALTER COLUMN opening_time DROP NOT NULL;
        END IF;
    END IF;
END $$;

-- Indexing for blazing fast lookups per property/tenant boundary
CREATE INDEX IF NOT EXISTS idx_pos_sessions_tenant_property ON pos_sessions(tenant_id, property_id, status);