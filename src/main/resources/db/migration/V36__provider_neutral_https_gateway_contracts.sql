ALTER TABLE payment_provider_accounts
    ADD COLUMN IF NOT EXISTS endpoint_url text;

ALTER TABLE payment_provider_accounts
    DROP CONSTRAINT IF EXISTS chk_payment_provider_accounts_endpoint_url;

ALTER TABLE payment_provider_accounts
    ADD CONSTRAINT chk_payment_provider_accounts_endpoint_url
    CHECK (
        endpoint_url IS NULL
        OR endpoint_url ~ '^https://[^[:space:]]+$'
    );

ALTER TABLE fiscal_provider_configs
    DROP CONSTRAINT IF EXISTS chk_fiscal_provider_configs_endpoint_url;

ALTER TABLE fiscal_provider_configs
    ADD CONSTRAINT chk_fiscal_provider_configs_endpoint_url
    CHECK (
        endpoint_url IS NULL
        OR endpoint_url ~ '^https://[^[:space:]]+$'
    );

COMMENT ON COLUMN payment_provider_accounts.endpoint_url IS
    'Exact HTTPS collection endpoint for the provider-neutral http_gateway adapter.';

COMMENT ON COLUMN fiscal_provider_configs.endpoint_url IS
    'Exact HTTPS submission endpoint for the provider-neutral http_gateway adapter.';
