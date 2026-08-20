-- An onboarding applicant's own mutations (create case, add document, submit) hit two RLS
-- gaps that stayed invisible until a test actually ran them under the real, constrained
-- pms_app role instead of the elevated role ordinary tests connect as:
--
-- 1. Side-effect audit rows go to platform_audit_logs (no tenant exists yet to attribute a
--    tenant-scoped audit_logs row to), but pms_app was never granted INSERT there (only
--    pms_platform was, per V14) — fixed with a narrow SECURITY DEFINER function, the same
--    shape onboarding_applications itself already uses, not a direct grant widening pms_app's
--    reach onto a platform-only table.
-- 2. The resulting outbox_events row has no home either: the baseline tenant_isolation policy
--    needs tenant_id = current_tenant_id() (both NULL for an applicant, which SQL treats as
--    not-equal), and outbox_platform_request only covers pms_platform. Fixed with a third,
--    narrowly-scoped policy below.

GRANT INSERT ON platform_audit_logs TO pms_onboarding_owner;

-- platform_audit_logs is RLS-enabled (not FORCEd, but pms_onboarding_owner isn't the table
-- owner either) with a single existing policy requiring platform.audit.write — which an
-- applicant, having no platform_user_id at all, can never hold. Additive, permissive INSERT
-- policy scoped to this one role only, mirroring onboarding_owner_all's shape on
-- onboarding_applications in V146, rather than trying to satisfy a policy that was never
-- meant to cover this caller.
CREATE POLICY onboarding_owner_audit_write ON platform_audit_logs
    FOR INSERT TO pms_onboarding_owner WITH CHECK (true);

-- Postgres ORs every applicable permissive policy's WITH CHECK into one expression rather
-- than short-circuiting once one passes (the same non-guarantee V146 already hit), so the
-- existing platform_admin policy's platform_user_has_permission(...) call still gets
-- evaluated for this role's INSERT even though onboarding_owner_audit_write alone would
-- allow it — and pms_onboarding_owner was never granted EXECUTE on that function.
GRANT EXECUTE ON FUNCTION platform_user_has_permission(uuid, text) TO pms_onboarding_owner;

CREATE FUNCTION record_onboarding_platform_audit_event(
    p_action text,
    p_entity_type text,
    p_entity_id uuid,
    p_new_values jsonb,
    p_correlation_id text
) RETURNS void
    LANGUAGE sql
    SECURITY DEFINER
    SET search_path = pg_catalog, public, pg_temp
    AS $$
    INSERT INTO platform_audit_logs (
        platform_user_id, action, entity_type, entity_id, tenant_id,
        new_values, correlation_id, outcome
    ) VALUES (
        NULL, p_action, p_entity_type, p_entity_id, NULL,
        p_new_values, p_correlation_id, 'success'
    );
$$;

ALTER FUNCTION record_onboarding_platform_audit_event(text, text, uuid, jsonb, text)
    OWNER TO pms_onboarding_owner;
REVOKE ALL ON FUNCTION record_onboarding_platform_audit_event(text, text, uuid, jsonb, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION record_onboarding_platform_audit_event(text, text, uuid, jsonb, text) TO pms_app;

-- Narrowly scoped to pms_app sessions actually bound to an onboarding application, and only
-- for the shape TenantTrustControlService.enqueue() ever produces for one (tenant_id null,
-- destination 'platform') — an ordinary tenant pms_app session is untouched, since
-- current_onboarding_application_id() is null for it.
CREATE POLICY outbox_onboarding_applicant ON outbox_events
    FOR INSERT TO pms_app
    WITH CHECK (
        current_onboarding_application_id() IS NOT NULL
        AND tenant_id IS NULL
        AND destination = 'platform'
    );
