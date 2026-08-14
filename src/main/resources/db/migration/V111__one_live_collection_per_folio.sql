-- V111 — a guest may hold one live payment prompt, not several.
--
-- Nothing stopped a second collection being started against a folio that already
-- had one in flight. The idempotency key stops a *replayed* request; it does not
-- stop a deliberate second one, and a receptionist whose first push seemed slow
-- has every reason to press the button again.
--
-- Two live USSD prompts for one bill is a guest who can approve both. The hotel
-- takes TZS 370,000 for a TZS 185,000 stay, and refunding mobile money in
-- Tanzania is slow and manual, so the error is expensive in a way an overcharge
-- on a card is not.
--
-- This becomes worse, not better, as the front desk gains ways to ask. A QR code
-- and an SMS payment link are two ways to reach one payment; if each opened its
-- own attempt, a guest who received the link and then scanned the code would be
-- holding two.
--
-- platformbilling already had this as uq_peak_payment_attempts_open, for exactly
-- the same reason. Guest collection did not.
--
-- Safe because there is a way out: PaymentStatusOutboxHandler expires an attempt
-- once expires_at passes, which releases the slot. Without that this index would
-- trade a double-charge for a folio nobody can ever pay, which is the worse of
-- the two — it strands a guest at the desk.

CREATE UNIQUE INDEX uq_payment_transactions_open_folio_collection
    ON payment_transactions (tenant_id, folio_id)
    WHERE folio_id IS NOT NULL
      AND transaction_direction = 'inbound'
      AND transaction_type = 'collection'
      AND status IN ('created', 'initiated', 'pending');

-- A POS order is settled the same way and has the same exposure.
CREATE UNIQUE INDEX uq_payment_transactions_open_pos_collection
    ON payment_transactions (tenant_id, pos_order_id)
    WHERE pos_order_id IS NOT NULL
      AND transaction_direction = 'inbound'
      AND transaction_type = 'collection'
      AND status IN ('created', 'initiated', 'pending');

DO $migration$
DECLARE
    already_doubled integer;
BEGIN
    -- If any folio already holds two live collections, the index above would have
    -- failed to build and this migration would stop. Report it as what it is
    -- rather than as a constraint violation, because it means money may already
    -- have been taken twice and someone has to look.
    SELECT count(*)
    INTO already_doubled
    FROM (
        SELECT tenant_id, folio_id
        FROM payment_transactions
        WHERE folio_id IS NOT NULL
          AND transaction_direction = 'inbound'
          AND transaction_type = 'collection'
          AND status IN ('created', 'initiated', 'pending')
        GROUP BY tenant_id, folio_id
        HAVING count(*) > 1
    ) doubled;

    IF already_doubled > 0 THEN
        RAISE EXCEPTION
            '% folios are holding more than one live collection. Resolve them before '
            'applying this constraint: each is a guest who could have been charged twice.',
            already_doubled;
    END IF;
END;
$migration$;
