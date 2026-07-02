-- Production activation metadata and RLS-safe readiness guards.

ALTER TABLE payment_provider_accounts
    ADD COLUMN IF NOT EXISTS environment text NOT NULL DEFAULT 'sandbox',
    ADD COLUMN IF NOT EXISTS sandbox_certified_at timestamptz,
    ADD COLUMN IF NOT EXISTS sandbox_evidence_ref text;

ALTER TABLE payment_provider_accounts
    ADD CONSTRAINT chk_payment_provider_accounts_environment
        CHECK (environment IN ('sandbox', 'production')),
    ADD CONSTRAINT chk_payment_provider_accounts_production_certification
        CHECK (
            environment <> 'production'
            OR (
                sandbox_certified_at IS NOT NULL
                AND length(trim(sandbox_evidence_ref)) > 0
            )
        ) NOT VALID;

ALTER TABLE fiscal_provider_configs
    ADD COLUMN IF NOT EXISTS sandbox_certified_at timestamptz,
    ADD COLUMN IF NOT EXISTS sandbox_evidence_ref text,
    ADD CONSTRAINT chk_fiscal_provider_production_certification
        CHECK (
            environment <> 'production'
            OR (
                sandbox_certified_at IS NOT NULL
                AND length(trim(sandbox_evidence_ref)) > 0
            )
        ) NOT VALID;

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
              AND pp.provider_code IN ('contract_mock')
        ),
        (
            SELECT count(*)
            FROM fiscal_provider_configs fpc
            JOIN fiscal_providers fp ON fp.id = fpc.provider_id
            WHERE fpc.is_active = true
              AND fp.is_active = true
              AND fp.provider_code IN (
                  'contract_mock',
                  'signed_simulator'
              )
        );
$$;

CREATE OR REPLACE FUNCTION production_provider_readiness_counts(
    p_approved_payment_codes text[],
    p_approved_fiscal_codes text[]
) RETURNS TABLE (
    unsafe_payment_account_count bigint,
    unsafe_fiscal_config_count bigint
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
              AND (
                  ppa.environment <> 'production'
                  OR NOT (pp.provider_code = ANY(p_approved_payment_codes))
                  OR pp.provider_code <> 'clickpesa'
                  OR ppa.client_id IS NULL
                  OR ppa.api_key_secret_ref IS NULL
                  OR ppa.checksum_key_secret_ref IS NULL
                  OR ppa.api_key_secret_ref LIKE 'literal:%'
                  OR ppa.checksum_key_secret_ref LIKE 'literal:%'
                  OR ppa.sandbox_certified_at IS NULL
                  OR ppa.sandbox_evidence_ref IS NULL
              )
        ),
        (
            SELECT count(*)
            FROM fiscal_provider_configs fpc
            JOIN fiscal_providers fp ON fp.id = fpc.provider_id
            WHERE fpc.is_active = true
              AND (
                  fpc.environment <> 'production'
                  OR NOT (fp.provider_code = ANY(p_approved_fiscal_codes))
                  OR fp.provider_code IN (
                      'contract_mock',
                      'signed_simulator'
                  )
                  OR fpc.secret_ref IS NULL
                  OR fpc.secret_ref LIKE 'literal:%'
                  OR fpc.sandbox_certified_at IS NULL
                  OR fpc.sandbox_evidence_ref IS NULL
              )
        );
$$;

REVOKE ALL ON FUNCTION production_provider_readiness_counts(
    text[],
    text[]
) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION production_provider_readiness_counts(
    text[],
    text[]
) TO pms_app, pms_worker;

CREATE OR REPLACE FUNCTION phase3_operational_metrics()
RETURNS TABLE (
    clickpesa_accounts bigint,
    payment_poll_backlog bigint,
    webhook_failures bigint,
    webhook_replays bigint,
    refund_count bigint,
    reconciliation_backlog bigint,
    fiscal_backlog bigint,
    pos_variance_backlog bigint,
    night_audit_blockers bigint
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
              AND pp.provider_code = 'clickpesa'
        ),
        (
            SELECT count(*)
            FROM payment_transactions
            WHERE status IN ('created', 'initiated', 'pending')
        ),
        (
            SELECT count(*)
            FROM payment_webhook_events
            WHERE status = 'failed'
               OR processing_result = 'failed'
        ),
        (
            SELECT COALESCE(sum(replay_count), 0)::bigint
            FROM payment_webhook_events
        ),
        (
            SELECT count(*)
            FROM payment_transactions
            WHERE transaction_type = 'refund'
              AND status = 'posted'
        ),
        (
            SELECT count(*)
            FROM payment_reconciliations
            WHERE status IN ('draft', 'matched', 'variance')
        ),
        (
            SELECT
                (SELECT count(*) FROM fiscal_receipts
                 WHERE status IN ('pending', 'submitted'))
                +
                (SELECT count(*) FROM fiscal_corrections
                 WHERE status IN ('pending', 'submitted', 'retry'))
        ),
        (
            SELECT count(*)
            FROM pos_sessions
            WHERE status = 'pending_variance_approval'
        ),
        (
            SELECT count(*)
            FROM night_audit_issues
            WHERE blocking = true AND resolved_at IS NULL
        );
$$;

REVOKE ALL ON FUNCTION phase3_operational_metrics() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION phase3_operational_metrics()
TO pms_app, pms_worker;
