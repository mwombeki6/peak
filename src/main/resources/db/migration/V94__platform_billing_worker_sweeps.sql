-- V94 — letting the billing worker see across tenants, narrowly.
--
-- The worker loops run on a session with no tenant bound, because they are
-- sweeps: "every tenant whose grants need converging", "every attempt the
-- provider never answered". But every table they touch carries
-- tenant_isolation USING (tenant_id = current_tenant_id()), and an unbound
-- session has current_tenant_id() = NULL, so tenant_id = NULL is NULL, and every
-- row is filtered out.
--
-- That fails closed, which is right, but it fails *silently*: the sweep reports
-- zero rows and looks perfectly healthy while doing nothing at all. Expiry would
-- never revoke anything and nobody would see an error.
--
-- The fix is two SECURITY DEFINER functions rather than a blanket worker policy.
-- A policy saying "pms_worker with no tenant bound sees everything" would also
-- apply to every future worker that forgot to bind. Confining the cross-tenant
-- reach to two functions means the escape hatch is reviewable in one place, and
-- each one does a single job with a fixed shape.

DO $migration$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pms_platform_billing_sweep_owner') THEN
        CREATE ROLE pms_platform_billing_sweep_owner
            NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
    ELSE
        ALTER ROLE pms_platform_billing_sweep_owner
            NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
    END IF;
END;
$migration$;

GRANT SELECT ON peak_product_grants TO pms_platform_billing_sweep_owner;
GRANT SELECT ON peak_reconciliation_state TO pms_platform_billing_sweep_owner;
GRANT SELECT, UPDATE ON peak_payment_attempts TO pms_platform_billing_sweep_owner;
GRANT SELECT, UPDATE ON peak_purchases TO pms_platform_billing_sweep_owner;

-- The owner is NOBYPASSRLS, so it needs its own policies or it sees nothing
-- either. Scoped to a NOLOGIN role that only these two functions run as.
CREATE POLICY sweep_owner_reads_grants ON peak_product_grants
    FOR SELECT TO pms_platform_billing_sweep_owner USING (true);
CREATE POLICY sweep_owner_reads_reconciliation ON peak_reconciliation_state
    FOR SELECT TO pms_platform_billing_sweep_owner USING (true);
CREATE POLICY sweep_owner_sweeps_attempts ON peak_payment_attempts
    FOR ALL TO pms_platform_billing_sweep_owner USING (true) WITH CHECK (true);
CREATE POLICY sweep_owner_sweeps_purchases ON peak_purchases
    FOR ALL TO pms_platform_billing_sweep_owner USING (true) WITH CHECK (true);

-- -----------------------------------------------------------------------------
-- Which tenants need converging.
--
-- Every tenant holding a grant, not only those whose grants changed. Expiry
-- changes nothing in the data -- it merely happens -- so a change feed would
-- never notice it, and expiry is the whole reason this loop exists.
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION platform_billing_tenants_due(p_limit integer)
RETURNS TABLE (tenant_id uuid)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
    SELECT DISTINCT grant_row.tenant_id
    FROM peak_product_grants grant_row
    LEFT JOIN peak_reconciliation_state state
      ON state.tenant_id = grant_row.tenant_id
    WHERE state.next_run_at IS NULL OR state.next_run_at <= now()
    LIMIT greatest(p_limit, 0);
$$;

ALTER FUNCTION platform_billing_tenants_due(integer)
    OWNER TO pms_platform_billing_sweep_owner;
REVOKE ALL ON FUNCTION platform_billing_tenants_due(integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_billing_tenants_due(integer) TO pms_worker;

-- -----------------------------------------------------------------------------
-- Expiring attempts the provider never answered.
--
-- Both halves are here rather than in Kotlin because they must be one statement
-- pair against the same snapshot: releasing the attempt without returning the
-- purchase to 'quoted' would leave a customer unable to retry, blocked by the
-- one-open-attempt index against an attempt that is already dead.
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION platform_billing_expire_stale_attempts()
RETURNS integer
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
DECLARE
    expired_count integer;
BEGIN
    UPDATE peak_payment_attempts
    SET status = 'expired',
        failure_code = 'no_provider_response',
        updated_at = now()
    WHERE status IN ('created', 'initiated', 'pending')
      AND expires_at IS NOT NULL
      AND expires_at < now();

    GET DIAGNOSTICS expired_count = ROW_COUNT;

    IF expired_count > 0 THEN
        UPDATE peak_purchases purchase
        SET status = 'quoted', updated_at = now()
        WHERE purchase.status = 'awaiting_payment'
          AND NOT EXISTS (
              SELECT 1
              FROM peak_payment_attempts attempt
              WHERE attempt.purchase_id = purchase.id
                AND attempt.status IN ('created', 'initiated', 'pending')
          );
    END IF;

    RETURN expired_count;
END;
$$;

ALTER FUNCTION platform_billing_expire_stale_attempts()
    OWNER TO pms_platform_billing_sweep_owner;
REVOKE ALL ON FUNCTION platform_billing_expire_stale_attempts() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_billing_expire_stale_attempts() TO pms_worker;

DO $migration$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_proc proc
        JOIN pg_roles owner ON owner.oid = proc.proowner
        WHERE proc.proname IN (
                  'platform_billing_tenants_due',
                  'platform_billing_expire_stale_attempts'
              )
          AND (owner.rolsuper OR owner.rolbypassrls)
    ) THEN
        RAISE EXCEPTION
            'platform billing sweep functions must not be owned by a superuser or a BYPASSRLS role';
    END IF;
END;
$migration$;
