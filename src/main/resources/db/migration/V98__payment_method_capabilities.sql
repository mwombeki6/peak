-- V98 — separating what a customer buys from how they can pay for it.
--
-- The collection cap lived in the quote: a selection totalling more than
-- 5,000,000 TZS was refused outright. That conflated two independent things.
-- 5,000,000 is a property of the mobile money rail, not of the sale. A Peak Group
-- annual purchase at 8,500,000 is perfectly sellable; it simply cannot be pushed
-- down a USSD prompt.
--
-- Refusing to quote it also created pressure toward genuinely bad answers --
-- splitting one purchase across several USSD pushes, inventing a PARTIALLY_PAID
-- state, or shortening an annual term to fit a limit that has nothing to do with
-- the commercial agreement.
--
--   quote (priced, always)
--        -> which rails can carry this amount?
--             MOBILE_MONEY  no, above the per-transaction limit
--             BANK          yes
--
-- So provider and rail are modelled separately. AzamPay is a provider; mobile
-- money and bank are rails it offers, with different limits and different
-- required payer details.

CREATE TABLE peak_payment_method_capabilities (
    provider varchar(30) NOT NULL,
    payment_method varchar(20) NOT NULL,
    currency char(3) NOT NULL DEFAULT 'TZS',
    -- NULL max means "no ceiling we know of". Deliberately nullable rather than a
    -- huge sentinel: a limit nobody has verified should look absent, not invented.
    min_amount numeric(15,2) NOT NULL DEFAULT 0,
    max_amount numeric(15,2),
    -- Mobile money pushes to a handset and needs the number. A bank transfer does
    -- not, which is why payer_msisdn stops being mandatory below.
    requires_msisdn boolean NOT NULL DEFAULT false,
    -- Whether a lost callback can be recovered by asking the provider. A rail
    -- without this is one where a lost callback is unreconcilable, which is worth
    -- knowing before it is switched on.
    supports_status_query boolean NOT NULL DEFAULT false,
    is_enabled boolean NOT NULL DEFAULT true,
    notes text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (provider, payment_method, currency),
    CONSTRAINT chk_peak_payment_method CHECK (
        payment_method IN ('mobile_money', 'bank', 'card')
    ),
    CONSTRAINT chk_peak_payment_capability_range CHECK (
        max_amount IS NULL OR max_amount > min_amount
    )
);

-- Global reference data, like the product catalog: no tenant column and no RLS.
GRANT SELECT ON peak_payment_method_capabilities
    TO pms_app, pms_worker, pms_platform, pms_readonly_support;
GRANT INSERT, UPDATE ON peak_payment_method_capabilities TO pms_platform;

-- Only mobile money is seeded as enabled, because only the mobile money contract
-- has been verified. The bank row is present and disabled so the shape is
-- reviewable and switching it on is a data change rather than a schema change --
-- but it must not be enabled until AzamPay's bank endpoints, supported banks,
-- limits and reconciliation identifiers are confirmed against their developer
-- documentation or sandbox. A marketing page is not an API contract.
INSERT INTO peak_payment_method_capabilities (
    provider, payment_method, currency, min_amount, max_amount,
    requires_msisdn, supports_status_query, is_enabled, notes
) VALUES
    ('azampay', 'mobile_money', 'TZS', 1000, 5000000,
     true, true, true,
     'USSD push to the payer handset. The 5,000,000 ceiling is the per-transaction '
     'mobile money limit and is the reason larger purchases need another rail.'),
    ('azampay', 'bank', 'TZS', 1000, NULL,
     false, true, false,
     'DISABLED pending verification. AzamPay advertises bank collection, but the '
     'endpoints, supported banks, limits, settlement timing and — critically — '
     'which identifier the status endpoint accepts and whether it is returned at '
     'initiation are unconfirmed. If a bank reference only appears in the callback '
     'then a lost callback is unreconcilable on this rail, which would undo the '
     'guarantee V97 established.'),
    ('snippe', 'mobile_money', 'TZS', 1000, 5000000,
     true, false, false,
     'DISABLED pending an adapter. Listed so eligibility is data rather than code.')
ON CONFLICT DO NOTHING;

-- -----------------------------------------------------------------------------
-- Attempts carry their rail, and stop assuming there is a phone number.
-- -----------------------------------------------------------------------------
ALTER TABLE peak_payment_attempts
    ADD COLUMN payment_method varchar(20) NOT NULL DEFAULT 'mobile_money',
    -- Rail-agnostic. Whatever identifies the payer on this rail beyond the MSISDN;
    -- bank-specific columns are deliberately not invented before the contract is
    -- known, since a guessed column name is worse than none.
    ADD COLUMN payer_reference text,
    ADD CONSTRAINT chk_peak_payment_attempts_method CHECK (
        payment_method IN ('mobile_money', 'bank', 'card')
    );

ALTER TABLE peak_payment_attempts
    ALTER COLUMN payer_msisdn DROP NOT NULL;

-- The rail decides which payer detail is required, so the table enforces the
-- consequence rather than trusting every caller to remember it.
ALTER TABLE peak_payment_attempts
    ADD CONSTRAINT chk_peak_payment_attempts_payer CHECK (
        payment_method <> 'mobile_money' OR payer_msisdn IS NOT NULL
    );

DO $migration$
DECLARE
    enabled_without_recovery text;
BEGIN
    -- A rail that is on but cannot be asked about is one where a lost callback
    -- silently loses a customer's payment. V97 exists to prevent exactly that, so
    -- enabling such a rail should be a deliberate, visible act rather than a row
    -- someone flipped.
    SELECT string_agg(provider || '/' || payment_method, ', ')
    INTO enabled_without_recovery
    FROM peak_payment_method_capabilities
    WHERE is_enabled = true
      AND supports_status_query = false;

    IF enabled_without_recovery IS NOT NULL THEN
        RAISE EXCEPTION
            'These payment methods are enabled but cannot be reconciled by status query, '
            'so a lost callback would silently lose a customer payment: %',
            enabled_without_recovery;
    END IF;
END;
$migration$;
