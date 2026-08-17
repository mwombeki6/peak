-- V105 — carrying the mobile network a guest collection must be pushed to.
--
-- initiateMobileMoney took a folio, a provider account, a phone number and an
-- amount, and nothing else. The outbox event carried a transaction id, the worker
-- loaded the transaction, and the adapter was called with no network at all.
--
-- That was survivable only because guest collections currently run through
-- ClickPesa, which infers the network itself. AzamPay's mno/checkout requires the
-- network explicitly, so the moment a property is connected to AzamPay every
-- collection would have failed *inside the worker* — after the outbox event was
-- committed, retrying until dead-lettered, with a front desk watching a payment
-- that never arrives and no way to tell why.
--
-- Validation belongs at the request boundary. A payment that cannot possibly be
-- initiated should be refused while the receptionist is still looking at the
-- screen, not queued and failed later.
--
-- WHY THE NETWORK IS ASKED FOR RATHER THAN DERIVED
--
-- It is tempting to read it off the prefix — 075/078 M-Pesa, 071 Tigo, 068 Airtel.
-- Tanzania has mobile number portability, so a prefix records which operator was
-- originally allocated the range, not who serves the number today. Deriving the
-- network from it would silently route some payments to the wrong operator, and
-- the failure would look like a customer who did not pay.
--
-- A prefix may reasonably preselect a network in the interface. It must not be
-- what Peak sends.

ALTER TABLE payment_transactions
    ADD COLUMN mobile_network varchar(30);

-- Constrained to what the adapters actually accept, so a typo is refused by the
-- database rather than discovered by a customer whose prompt never arrived.
ALTER TABLE payment_transactions
    ADD CONSTRAINT chk_payment_transactions_mobile_network CHECK (
        mobile_network IS NULL
        OR mobile_network IN ('Airtel', 'Tigo', 'Halopesa', 'Azampesa', 'Mpesa')
    );

COMMENT ON COLUMN payment_transactions.mobile_network IS
    'The network to push the prompt to, supplied by the caller. Never derived from the '
    'phone number prefix: Tanzania has number portability, so a prefix identifies the '
    'original allocation rather than the current operator.';

CREATE INDEX idx_payment_transactions_network
    ON payment_transactions (tenant_id, mobile_network)
    WHERE mobile_network IS NOT NULL;
