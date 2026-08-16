-- The platform runtime could not create a tenant.
--
--   POST /api/v1/platform/tenants -> 500
--   PSQLException: permission denied for table tenant_modules
--   at TenantLaunchEvaluator.propertyModuleEnabled(TenantLaunchEvaluator.kt:290)
--
-- registerNewTenant evaluates launch readiness, and that evaluation reads module
-- enablement and role assignments. pms_platform could already read tenants,
-- properties, users, tenant_roles, tenant_subscriptions, identity_links and
-- peak_purchases — but not tenant_modules, property_modules or user_tenant_roles.
-- The set was incomplete, not deliberate.
--
-- A GRANT on its own would have been worse than the crash. All three carry
-- tenant_isolation (tenant_id = current_tenant_id()) applying to every role, and a
-- platform session binds app.current_platform_user_id rather than a tenant. The
-- read would have returned zero rows, EXISTS would have answered false, and the
-- evaluator would have reported "property module not enabled" for a tenant where
-- it is — a silent wrong answer in place of a loud failure.
--
-- So each table also gains a policy in the shape properties and tenants already
-- use: scoped to pms_platform and gated on an explicit platform permission, never
-- USING (true).

GRANT SELECT ON tenant_modules, property_modules, user_tenant_roles TO pms_platform;

CREATE POLICY platform_control_read ON tenant_modules
    FOR SELECT
    TO pms_platform
    USING (platform_user_has_permission(current_platform_user_id(), 'platform.tenants.view'));

CREATE POLICY platform_control_read ON property_modules
    FOR SELECT
    TO pms_platform
    USING (platform_user_has_permission(current_platform_user_id(), 'platform.tenants.view'));

CREATE POLICY platform_control_read ON user_tenant_roles
    FOR SELECT
    TO pms_platform
    USING (platform_user_has_permission(current_platform_user_id(), 'platform.tenants.view'));

-- The platform runtime also schedules the operational metrics probe, which failed
-- every interval with "permission denied for function phase3_operational_metrics".
-- V43 granted EXECUTE to pms_app and pms_worker and stopped there. The function is
-- SECURITY DEFINER, so this exposes nothing pms_app and pms_worker cannot already
-- read; it stops a scheduled task logging an error on every tick.
GRANT EXECUTE ON FUNCTION phase3_operational_metrics() TO pms_platform;

DO $migration$
DECLARE
    missing text;
BEGIN
    SELECT string_agg(t, ', ')
    INTO missing
    FROM unnest(ARRAY['tenant_modules', 'property_modules', 'user_tenant_roles']) AS t
    WHERE NOT pg_catalog.has_table_privilege('pms_platform', 'public.' || t, 'SELECT')
       OR NOT EXISTS (
           SELECT 1 FROM pg_policies
           WHERE tablename = t
             AND policyname = 'platform_control_read'
             AND 'pms_platform' = ANY (roles)
       );

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION
            'pms_platform still cannot read launch readiness from: %. A grant without a '
            'policy reads zero rows under tenant_isolation, which is why both are asserted.',
            missing;
    END IF;

    IF NOT pg_catalog.has_function_privilege(
        'pms_platform', 'public.phase3_operational_metrics()', 'EXECUTE'
    ) THEN
        RAISE EXCEPTION 'pms_platform cannot execute phase3_operational_metrics()';
    END IF;
END;
$migration$;
