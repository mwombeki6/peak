DO $$ 
BEGIN
    IF NOT EXISTS (SELECT FROM pg_tables WHERE schemaname = 'public' AND tablename = 'pos_orders') THEN
        CREATE TABLE pos_orders (
            id UUID PRIMARY KEY,
            tenant_id UUID NOT NULL,
            property_id UUID NOT NULL,
            session_id UUID NOT NULL REFERENCES pos_sessions(id),
            status VARCHAR(50) NOT NULL, -- 'OPEN', 'PENDING_PAYMENT', 'PAID', 'CANCELLED'
            total_amount NUMERIC(19, 4) NOT NULL DEFAULT 0.0000,
            created_at TIMESTAMP NOT NULL DEFAULT NOW(),
            updated_at TIMESTAMP NOT NULL DEFAULT NOW(),

            CONSTRAINT chk_positive_total_amount CHECK (total_amount >= 0)
        );
    ELSE
        -- Table exists, ensure Phase 3 columns are present
        IF NOT EXISTS (SELECT FROM information_schema.columns WHERE table_name='pos_orders' AND column_name='property_id') THEN
            ALTER TABLE pos_orders ADD COLUMN property_id UUID;
        END IF;
    END IF;

    IF NOT EXISTS (SELECT FROM pg_tables WHERE schemaname = 'public' AND tablename = 'pos_order_items') THEN
        CREATE TABLE pos_order_items (
            id UUID PRIMARY KEY,
            order_id UUID NOT NULL REFERENCES pos_orders(id) ON DELETE CASCADE,
            description VARCHAR(255) NOT NULL,
            quantity INTEGER NOT NULL,
            unit_price NUMERIC(19, 4) NOT NULL,
            total_price NUMERIC(19, 4) NOT NULL,

            CONSTRAINT chk_positive_quantity CHECK (quantity > 0),
            CONSTRAINT chk_positive_unit_price CHECK (unit_price >= 0)
        );
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_pos_orders_session ON pos_orders(session_id);
CREATE INDEX IF NOT EXISTS idx_pos_orders_tenant_property ON pos_orders(tenant_id, property_id);
