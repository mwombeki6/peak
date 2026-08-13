-- V97 — telling "we do not know" apart from "it failed".
--
-- Until now a payment attempt the provider never came back about was swept to
-- 'expired' and its purchase returned to 'quoted', so the customer could try
-- again. That is right when the payment genuinely failed. It is badly wrong when
-- the payment succeeded and only the callback was lost:
--
--   customer's account debited
--        -> callback lost
--        -> Peak concludes the payment did not happen
--        -> module never activates
--        -> customer is invited to pay a second time
--
-- Collecting twice for one subscription is among the worst things a billing
-- system can do, and it arises entirely from treating silence as a negative
-- answer. Silence means Peak does not know the outcome. So:
--
--   CREATED -> INITIATED -> PENDING ─┬─> CONFIRMED
--                                    ├─> FAILED
--                                    └─> RECONCILIATION_REQUIRED
--
-- RECONCILIATION_REQUIRED is non-terminal and, crucially, still occupies the
-- one-open-attempt slot: while the outcome is unknown the tenant must not be
-- offered another payment button. They are told we are confirming the last one.

-- 'reconciliation_required' is 23 characters and the column was varchar(20), so
-- the new state would not have fit in the column meant to hold it.
ALTER TABLE peak_payment_attempts
    ALTER COLUMN status TYPE varchar(30);

ALTER TABLE peak_payment_attempts
    DROP CONSTRAINT chk_peak_payment_attempts_status,
    ADD CONSTRAINT chk_peak_payment_attempts_status CHECK (
        status IN (
            'created', 'initiated', 'pending', 'confirmed',
            'failed', 'cancelled', 'expired', 'reconciliation_required'
        )
    );

-- Evidence. An operator must be able to answer "why did Peak decide this
-- customer had, or had not, paid?" from the row rather than from logs that have
-- rotated away.
ALTER TABLE peak_payment_attempts
    ADD COLUMN next_status_check_at timestamptz,
    ADD COLUMN status_check_count integer NOT NULL DEFAULT 0,
    ADD COLUMN last_status_checked_at timestamptz,
    ADD COLUMN last_provider_status text,
    ADD COLUMN last_status_error text;

-- The double-charge guard. An attempt whose outcome is unknown holds the slot
-- exactly as a live one does: the index is what physically prevents a second
-- USSD push while the first may already have taken the money.
DROP INDEX IF EXISTS uq_peak_payment_attempts_open;
CREATE UNIQUE INDEX uq_peak_payment_attempts_open
    ON peak_payment_attempts (purchase_id)
    WHERE status IN ('created', 'initiated', 'pending', 'reconciliation_required');

CREATE INDEX idx_peak_payment_attempts_status_check
    ON peak_payment_attempts (next_status_check_at)
    WHERE status IN ('initiated', 'pending', 'reconciliation_required');

-- Existing pending attempts get a first check shortly after deployment rather
-- than never; a NULL next_status_check_at would exclude them from the sweep.
UPDATE peak_payment_attempts
SET next_status_check_at = now()
WHERE status IN ('created', 'initiated', 'pending')
  AND next_status_check_at IS NULL;

-- -----------------------------------------------------------------------------
-- The sweep no longer decides that anything failed.
--
-- It moves attempts past their window to 'reconciliation_required' and leaves the
-- purchase in 'awaiting_payment'. Only an answer from the provider -- through a
-- callback or a status query -- may conclude that a payment failed, because only
-- the provider knows.
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION platform_billing_expire_stale_attempts()
RETURNS integer
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
DECLARE
    moved_count integer;
BEGIN
    UPDATE peak_payment_attempts
    SET status = 'reconciliation_required',
        last_status_error = coalesce(last_status_error, 'no provider response within window'),
        next_status_check_at = least(
            coalesce(next_status_check_at, now()),
            now()
        ),
        updated_at = now()
    WHERE status IN ('created', 'initiated', 'pending')
      AND expires_at IS NOT NULL
      AND expires_at < now();

    GET DIAGNOSTICS moved_count = ROW_COUNT;

    -- Deliberately no purchase update. Returning it to 'quoted' here is what
    -- would invite the second payment.
    RETURN moved_count;
END;
$$;

ALTER FUNCTION platform_billing_expire_stale_attempts()
    OWNER TO pms_platform_billing_sweep_owner;

-- -----------------------------------------------------------------------------
-- Attempts due for an independent status query.
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION platform_billing_attempts_due_for_status_check(p_limit integer)
RETURNS TABLE (
    attempt_id uuid,
    tenant_id uuid,
    purchase_id uuid,
    provider text,
    internal_reference text,
    provider_reference text,
    amount numeric,
    currency text,
    status_check_count integer
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
    SELECT attempt.id,
           attempt.tenant_id,
           attempt.purchase_id,
           attempt.provider::text,
           attempt.internal_reference,
           attempt.provider_reference,
           attempt.amount,
           attempt.currency::text,
           attempt.status_check_count
    FROM peak_payment_attempts attempt
    WHERE attempt.status IN ('initiated', 'pending', 'reconciliation_required')
      AND attempt.next_status_check_at IS NOT NULL
      AND attempt.next_status_check_at <= now()
    ORDER BY attempt.next_status_check_at
    LIMIT greatest(p_limit, 0);
$$;

ALTER FUNCTION platform_billing_attempts_due_for_status_check(integer)
    OWNER TO pms_platform_billing_sweep_owner;
REVOKE ALL ON FUNCTION platform_billing_attempts_due_for_status_check(integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_billing_attempts_due_for_status_check(integer) TO pms_worker;

-- -----------------------------------------------------------------------------
-- Recording what a status query said. Separate from settlement, which is shared
-- with the webhook path and must stay the only place a payment is applied.
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION platform_billing_record_status_check(
    p_attempt_id uuid,
    p_status text,
    p_provider_status text,
    p_provider_reference text,
    p_error text,
    p_next_check_at timestamptz
) RETURNS integer
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
DECLARE
    updated_count integer;
BEGIN
    UPDATE peak_payment_attempts
    SET status = coalesce(p_status, status),
        last_status_checked_at = now(),
        status_check_count = status_check_count + 1,
        last_provider_status = p_provider_status,
        last_status_error = p_error,
        provider_reference = coalesce(p_provider_reference, provider_reference),
        next_status_check_at = p_next_check_at,
        updated_at = now()
    WHERE id = p_attempt_id;

    GET DIAGNOSTICS updated_count = ROW_COUNT;
    RETURN updated_count;
END;
$$;

ALTER FUNCTION platform_billing_record_status_check(uuid, text, text, text, text, timestamptz)
    OWNER TO pms_platform_billing_sweep_owner;
REVOKE ALL ON FUNCTION platform_billing_record_status_check(uuid, text, text, text, text, timestamptz)
    FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_billing_record_status_check(uuid, text, text, text, text, timestamptz)
    TO pms_worker;

-- -----------------------------------------------------------------------------
-- What an operator looks at. A stuck payment must be a queryable fact, not a
-- support ticket nobody can reproduce.
-- -----------------------------------------------------------------------------
CREATE OR REPLACE VIEW peak_payments_requiring_reconciliation AS
SELECT attempt.tenant_id,
       tenant.name AS tenant_name,
       attempt.purchase_id,
       attempt.id AS attempt_id,
       attempt.amount,
       attempt.currency,
       attempt.provider,
       -- Masked: an operator needs to recognise the number, not to read it.
       left(attempt.payer_msisdn, 6) || 'xxx' || right(attempt.payer_msisdn, 2)
           AS payer_msisdn_masked,
       attempt.internal_reference,
       attempt.provider_reference,
       attempt.status,
       attempt.created_at AS started_at,
       attempt.last_status_checked_at,
       attempt.status_check_count,
       attempt.last_provider_status,
       attempt.last_status_error,
       purchase.status AS purchase_status
FROM peak_payment_attempts attempt
JOIN peak_purchases purchase ON purchase.id = attempt.purchase_id
LEFT JOIN tenants tenant ON tenant.id = attempt.tenant_id
WHERE attempt.status = 'reconciliation_required';

GRANT SELECT ON peak_payments_requiring_reconciliation
    TO pms_platform, pms_readonly_support;

DO $migration$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_proc proc
        JOIN pg_roles owner ON owner.oid = proc.proowner
        WHERE proc.proname IN (
                  'platform_billing_attempts_due_for_status_check',
                  'platform_billing_record_status_check',
                  'platform_billing_expire_stale_attempts'
              )
          AND (owner.rolsuper OR owner.rolbypassrls)
    ) THEN
        RAISE EXCEPTION
            'platform billing status functions must not be owned by a superuser or BYPASSRLS role';
    END IF;

    -- The guard that makes double collection physically impossible while an
    -- outcome is unknown.
    IF NOT EXISTS (
        SELECT 1 FROM pg_indexes
        WHERE indexname = 'uq_peak_payment_attempts_open'
          AND indexdef LIKE '%reconciliation_required%'
    ) THEN
        RAISE EXCEPTION
            'the open-attempt index must cover reconciliation_required, or a tenant whose '
            'payment outcome is unknown could be charged a second time';
    END IF;
END;
$migration$;
