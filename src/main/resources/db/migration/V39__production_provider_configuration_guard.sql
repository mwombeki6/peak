CREATE OR REPLACE FUNCTION active_contract_mock_provider_counts()
RETURNS TABLE (
    payment_account_count bigint,
    fiscal_config_count bigint
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
    SELECT
        (
            SELECT count(*)
            FROM payment_provider_accounts ppa
            JOIN payment_providers pp
              ON pp.tenant_id = ppa.tenant_id
             AND pp.id = ppa.provider_id
            WHERE ppa.is_active = true
              AND pp.is_active = true
              AND pp.provider_code = 'contract_mock'
        ),
        (
            SELECT count(*)
            FROM fiscal_provider_configs fpc
            JOIN fiscal_providers fp ON fp.id = fpc.provider_id
            WHERE fpc.is_active = true
              AND fp.is_active = true
              AND fp.provider_code = 'contract_mock'
        );
$$;

REVOKE ALL ON FUNCTION active_contract_mock_provider_counts() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION active_contract_mock_provider_counts() TO pms_app, pms_worker;

COMMENT ON FUNCTION active_contract_mock_provider_counts() IS
    'RLS-safe production startup guard; exposes counts only, never provider configuration.';
