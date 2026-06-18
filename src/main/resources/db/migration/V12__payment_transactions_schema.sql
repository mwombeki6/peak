-- Migration for payment transactions
CREATE TABLE payment_transactions (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL, -- Links to booking_sessions
    reference_id TEXT UNIQUE NOT NULL, -- The "PAY-XXXX" reference
    provider TEXT NOT NULL, -- e.g., 'VODACOM_MPESA'
    method TEXT NOT NULL, -- 'MOBILE_MONEY' or 'BANK_TRANSFER'
    phone_number TEXT,
    account_number TEXT,
    amount DECIMAL(19, 4) NOT NULL,
    status TEXT NOT NULL, -- 'PENDING', 'COMPLETED', 'FAILED'
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_payment_transactions_session_id ON payment_transactions(session_id);
CREATE INDEX idx_payment_transactions_reference_id ON payment_transactions(reference_id);
