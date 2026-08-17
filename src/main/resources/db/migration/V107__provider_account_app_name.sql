-- V107 — a property's own provider application registration.
--
-- AzamPay authenticates three things together: an application registration
-- (appName), a client id and a client secret. Peak carried the first in global
-- configuration because there was one merchant — Peak's own, for subscription
-- collection. The client id and secret were already per-account.
--
-- That is the last thing standing between AzamPay and multi-property guest
-- collection. A hotel collecting into its own AzamPay account registers its own
-- application, and a token minted under Peak's registration carries Peak's
-- authority, not the hotel's. Reusing the global name would either fail to
-- authenticate or, worse, succeed as the wrong merchant.
--
-- Nullable on purpose. Null means "use the configured default", which is what
-- Peak's own platform-billing account wants and what every ClickPesa and Snippe
-- account wants — neither provider has this concept. Only an account whose
-- provider authenticates an application registration needs to fill it in.
--
-- Not a secret. The application name is an identifier AzamPay echoes in its own
-- dashboards; the credential that matters alongside it is client_secret, which
-- continues to live behind secret_ref rather than in this table.

ALTER TABLE payment_provider_accounts
    ADD COLUMN provider_app_name text;

COMMENT ON COLUMN payment_provider_accounts.provider_app_name IS
    'The provider application registration this account authenticates under, for a '
    'provider that authenticates one. NULL means use the configured default, which is '
    'Peak''s own. Set this on a property that collects into its own AzamPay merchant '
    'account, or its token is minted under Peak''s authority instead of the hotel''s.';

-- A blank string is not a missing value, and telling them apart later means
-- reading every call site. Refuse it here instead.
ALTER TABLE payment_provider_accounts
    ADD CONSTRAINT chk_payment_provider_accounts_app_name
    CHECK (provider_app_name IS NULL OR length(btrim(provider_app_name)) > 0);

DO $migration$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'payment_provider_accounts'
          AND column_name = 'provider_app_name'
    ) THEN
        RAISE EXCEPTION
            'provider_app_name is missing, so every property-scoped AzamPay account would '
            'authenticate under Peak''s own application registration';
    END IF;
END;
$migration$;
