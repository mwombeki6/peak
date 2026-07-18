-- =============================================================================
-- Narrow tenant-administrator continuity lock
--
-- Tenant administration workflows serialize on the tenant row, but the API
-- runtime must not inherit the platform role or receive UPDATE on tenants.
-- Expose only the row-lock capability through a dedicated NOLOGIN owner.
-- =============================================================================

DO $migration$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_catalog.pg_roles
        WHERE rolname = 'pms_tenant_continuity_owner'
    ) THEN
        CREATE ROLE pms_tenant_continuity_owner
            NOLOGIN
            NOSUPERUSER
            NOCREATEDB
            NOCREATEROLE
            NOINHERIT
            NOBYPASSRLS;
    ELSE
        ALTER ROLE pms_tenant_continuity_owner
            NOLOGIN
            NOSUPERUSER
            NOCREATEDB
            NOCREATEROLE
            NOINHERIT
            NOBYPASSRLS;
    END IF;
END;
$migration$;

REVOKE ALL PRIVILEGES ON TABLE public.tenants FROM pms_tenant_continuity_owner;
GRANT USAGE ON SCHEMA public TO pms_tenant_continuity_owner;
GRANT SELECT (id, deleted_at), UPDATE (id)
    ON TABLE public.tenants
    TO pms_tenant_continuity_owner;
GRANT EXECUTE ON FUNCTION public.current_tenant_id()
    TO pms_tenant_continuity_owner;

CREATE OR REPLACE FUNCTION public.lock_tenant_administrator_continuity(
    p_tenant_id pg_catalog.uuid
) RETURNS pg_catalog.bool
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $function$
DECLARE
    v_locked pg_catalog.bool;
BEGIN
    IF p_tenant_id IS NULL
       OR p_tenant_id IS DISTINCT FROM public.current_tenant_id() THEN
        RAISE EXCEPTION USING
            ERRCODE = '42501',
            MESSAGE = 'Tenant continuity lock is not authorized';
    END IF;

    SELECT true
    INTO v_locked
    FROM public.tenants AS tenant
    WHERE tenant.id = p_tenant_id
      AND tenant.deleted_at IS NULL
    FOR UPDATE OF tenant;

    RETURN v_locked IS TRUE;
END;
$function$;

ALTER FUNCTION public.lock_tenant_administrator_continuity(pg_catalog.uuid)
    OWNER TO pms_tenant_continuity_owner;
REVOKE ALL ON FUNCTION public.lock_tenant_administrator_continuity(pg_catalog.uuid)
    FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.lock_tenant_administrator_continuity(pg_catalog.uuid)
    TO pms_app;

COMMENT ON FUNCTION public.lock_tenant_administrator_continuity(pg_catalog.uuid) IS
    'Acquires a transaction-scoped row lock for same-tenant administrator continuity checks without granting tenant mutation rights.';
