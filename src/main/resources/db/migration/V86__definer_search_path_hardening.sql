-- =============================================================================
-- Close a demonstrated SECURITY DEFINER hijack via the temporary schema
--
-- Twenty-nine SECURITY DEFINER functions were created with
-- `SET search_path = public`. That looks like it pins resolution, and for
-- schemas it does. It does not pin the temporary schema: when pg_temp is not
-- named in search_path, PostgreSQL searches it FIRST for relation names. Every
-- unqualified table reference inside these bodies was therefore resolvable to a
-- table the caller had created moments earlier.
--
-- This was not theoretical. Executed as peak_app, the application's own login,
-- against can_access_public_module:
--
--     SELECT can_access_public_module(fake_tenant, fake_property, 'booking_engine');
--     -> false                                   -- neither row exists
--
--     CREATE TEMP TABLE tenants (...);           -- shadow the four relations
--     CREATE TEMP TABLE properties (...);        -- the body reads unqualified
--     CREATE TEMP TABLE tenant_modules (...);
--     CREATE TEMP TABLE property_modules (...);
--     INSERT the rows the checks want to find;
--
--     SELECT can_access_public_module(fake_tenant, fake_property, 'booking_engine');
--     -> true                                    -- for a tenant that does not exist
--
-- The function runs as peak_migrator, which is both SUPERUSER and BYPASSRLS, so
-- a forged answer is returned by the most privileged role in the cluster. The
-- affected set includes the functions row-level security policies themselves
-- invoke -- platform_user_has_permission, can_platform_admin_access_tenant,
-- can_support_session_access_tenant -- which is to say the functions that decide
-- tenant isolation.
--
-- Reachability today is limited rather than absent. The application builds every
-- statement through bound parameters and contains no string-assembled SQL, so
-- there is no route from an HTTP request to a CREATE TEMP TABLE. Exploiting this
-- needs a database session, which means leaked application credentials or a SQL
-- injection introduced later. The point is what it converts those into: either
-- one becomes a full escalation to superuser-evaluated authorization rather than
-- staying the smaller problem it would otherwise be.
--
-- The fix names pg_temp explicitly and places it last, so it is searched last
-- instead of first. Bodies are untouched; only resolution order changes.
-- Verified against the same probe, which returns false after the change with the
-- shadow tables still present.
--
-- Six functions already did this correctly, which is how the inconsistency was
-- found. Two more resolve entirely through qualified public. names, and one
-- lists pg_temp after public, so all three were already safe and are left alone.
-- =============================================================================

ALTER FUNCTION public.active_contract_mock_provider_counts()
    SET search_path = pg_catalog, public, pg_temp;
ALTER FUNCTION public.append_realtime_event(p_tenant_id uuid, p_property_id uuid, p_event_type text, p_payload jsonb)
    SET search_path = pg_catalog, public, pg_temp;
ALTER FUNCTION public.assert_tenant_capacity(p_tenant_id uuid, p_entitlement_code text)
    SET search_path = pg_catalog, public, pg_temp;
ALTER FUNCTION public.assert_tenant_entitlement_enabled(p_tenant_id uuid, p_entitlement_code text)
    SET search_path = pg_catalog, public, pg_temp;
ALTER FUNCTION public.can_access_public_module(p_tenant_id uuid, p_property_id uuid, p_module_id text)
    SET search_path = pg_catalog, public, pg_temp;
ALTER FUNCTION public.can_platform_admin_access_tenant(p_platform_user_id uuid, p_tenant_id uuid, p_action_code text)
    SET search_path = pg_catalog, public, pg_temp;
ALTER FUNCTION public.can_support_session_access_tenant(p_platform_user_id uuid, p_support_session_id uuid, p_tenant_id uuid, p_action_code text)
    SET search_path = pg_catalog, public, pg_temp;
ALTER FUNCTION public.claim_expired_report_artifacts(p_limit integer)
    SET search_path = pg_catalog, public, pg_temp;
ALTER FUNCTION public.claim_outbox_events(p_worker_id text, p_destination text, p_limit integer)
    SET search_path = pg_catalog, public, pg_temp;
ALTER FUNCTION public.complete_outbox_event(p_event_id uuid, p_worker_id text)
    SET search_path = pg_catalog, public, pg_temp;
ALTER FUNCTION public.dead_letter_outbox_event(p_event_id uuid, p_worker_id text, p_error_message text)
    SET search_path = pg_catalog, public, pg_temp;
ALTER FUNCTION public.delete_expired_realtime_events(p_limit integer)
    SET search_path = pg_catalog, public, pg_temp;
ALTER FUNCTION public.effective_tenant_entitlement(p_tenant_id uuid, p_entitlement_code text)
    SET search_path = pg_catalog, public, pg_temp;
ALTER FUNCTION public.enqueue_report_delivery_outbox_event(p_event_id uuid, p_tenant_id uuid, p_property_id uuid, p_delivery_id uuid, p_headers jsonb, p_correlation_id text)
    SET search_path = pg_catalog, public, pg_temp;
ALTER FUNCTION public.fail_outbox_event(p_event_id uuid, p_worker_id text, p_error_message text, p_retry_delay interval)
    SET search_path = pg_catalog, public, pg_temp;
ALTER FUNCTION public.latest_realtime_event_sequence()
    SET search_path = pg_catalog, public, pg_temp;
ALTER FUNCTION public.maintain_idempotency_keys(p_retention interval, p_limit integer)
    SET search_path = pg_catalog, public, pg_temp;
ALTER FUNCTION public.mirror_property_outbox_to_realtime_journal()
    SET search_path = pg_catalog, public, pg_temp;
ALTER FUNCTION public.phase3_operational_metrics()
    SET search_path = pg_catalog, public, pg_temp;
ALTER FUNCTION public.platform_user_has_permission(p_platform_user_id uuid, p_permission_code text)
    SET search_path = pg_catalog, public, pg_temp;
ALTER FUNCTION public.poll_realtime_events(p_after_sequence bigint, p_limit integer)
    SET search_path = pg_catalog, public, pg_temp;
ALTER FUNCTION public.production_provider_readiness_counts(p_approved_payment_codes text[], p_approved_fiscal_codes text[])
    SET search_path = pg_catalog, public, pg_temp;
ALTER FUNCTION public.provision_tenant_administrator(p_tenant_id uuid, p_full_name text, p_email text, p_issuer text, p_subject text)
    SET search_path = pg_catalog, public, pg_temp;
ALTER FUNCTION public.reclaim_stale_outbox_events(p_locked_before timestamp with time zone, p_limit integer)
    SET search_path = pg_catalog, public, pg_temp;
ALTER FUNCTION public.replay_realtime_events(p_tenant_id uuid, p_property_id uuid, p_after_sequence bigint, p_limit integer)
    SET search_path = pg_catalog, public, pg_temp;
ALTER FUNCTION public.resolve_oidc_identity_link(p_issuer text, p_subject text)
    SET search_path = pg_catalog, public, pg_temp;
ALTER FUNCTION public.resolve_payment_webhook_scope(p_provider_account_id uuid)
    SET search_path = pg_catalog, public, pg_temp;
ALTER FUNCTION public.resolve_public_property_scope(p_property_id uuid, p_module_id text)
    SET search_path = pg_catalog, public, pg_temp;
ALTER FUNCTION public.verify_tenant_business_profile(p_tenant_id uuid)
    SET search_path = pg_catalog, public, pg_temp;

-- -----------------------------------------------------------------------------
-- Keep the next one from being written the old way
-- -----------------------------------------------------------------------------
-- Listing twenty-nine functions is a one-time correction, not a control. The
-- control is the accompanying assertion in DefinerSearchPathIntegrationTests,
-- which fails if any SECURITY DEFINER function in this schema omits pg_temp
-- from its search_path. A thirtieth function written the old way breaks that
-- test rather than quietly reopening this.
DO $$
DECLARE
    v_unsafe pg_catalog.text;
BEGIN
    SELECT string_agg(function_name.proname, ', ' ORDER BY function_name.proname)
    INTO v_unsafe
    FROM pg_catalog.pg_proc AS function_name
    JOIN pg_catalog.pg_namespace AS schema_name
      ON schema_name.oid = function_name.pronamespace
    WHERE schema_name.nspname = 'public'
      AND function_name.prosecdef
      AND COALESCE(
          array_to_string(function_name.proconfig, ',') NOT LIKE '%pg_temp%',
          true
      );

    IF v_unsafe IS NOT NULL THEN
        RAISE EXCEPTION
            'SECURITY DEFINER functions still resolve pg_temp first: %', v_unsafe;
    END IF;
END;
$$;
