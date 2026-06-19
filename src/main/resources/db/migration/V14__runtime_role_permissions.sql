-- ================================================================================
-- Runtime role privileges for production API, platform, and worker roles
-- ================================================================================

GRANT USAGE ON SCHEMA public TO pms_app, pms_platform, pms_worker, pms_readonly_support;

-- Read-only route/catalog data required by HTTP route guard and tenant onboarding.
GRANT SELECT ON TABLE
    module_access_matrix,
    module_catalog,
    permission_catalog,
    plans
TO pms_app, pms_platform;

-- Tenant/public API runtime reads.
GRANT SELECT ON TABLE
    tenants,
    tenant_profiles,
    tenant_modules,
    properties,
    property_modules,
    users,
    tenant_roles,
    tenant_role_permissions,
    permissions,
    user_tenant_roles,
    identity_links,
    tenant_user_invitations,
    booking_sessions,
    booking_session_rooms,
    booking_payment_attempts,
    idempotency_keys,
    audit_logs,
    outbox_events
TO pms_app;

-- Tenant/public API runtime writes. RLS policies still constrain tenant scope.
GRANT INSERT, UPDATE ON TABLE
    users,
    identity_links,
    tenant_user_invitations,
    booking_sessions,
    booking_session_rooms,
    booking_payment_attempts,
    idempotency_keys,
    audit_logs,
    outbox_events
TO pms_app;

GRANT INSERT, UPDATE, DELETE ON TABLE user_tenant_roles TO pms_app;

-- Platform governance runtime writes. RLS policies require platform permissions.
GRANT SELECT ON TABLE
    tenants,
    tenant_profiles,
    tenant_lifecycle_events,
    platform_audit_logs,
    platform_users,
    platform_roles,
    platform_permissions,
    platform_role_permissions,
    platform_user_roles
TO pms_platform;

GRANT INSERT, UPDATE ON TABLE
    tenants,
    tenant_profiles,
    tenant_lifecycle_events,
    platform_audit_logs
TO pms_platform;

-- Keep platform RLS predicates out of tenant/public API sessions. Otherwise
-- tenant-scoped writes can require execute privileges on platform-only helpers.
ALTER POLICY platform_tenant_governance ON tenants TO pms_platform;
ALTER POLICY platform_identity_links ON identity_links TO pms_platform;
ALTER POLICY tenant_user_invitations_platform ON tenant_user_invitations TO pms_platform;

DROP POLICY tenant_or_platform ON tenant_profiles;

CREATE POLICY tenant_profiles_tenant ON tenant_profiles
    TO pms_app
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

CREATE POLICY tenant_profiles_platform ON tenant_profiles
    TO pms_platform
    USING (platform_user_has_permission(current_platform_user_id(), 'platform.tenants.view'))
    WITH CHECK (platform_user_has_permission(current_platform_user_id(), 'platform.tenants.manage'));

-- Public invitation acceptance reserves idempotency before a tenant identity is
-- known. Tenant-scoped records remain protected by the existing tenant policy.
CREATE POLICY idempotency_public_request ON idempotency_keys
    FOR ALL TO pms_app
    USING (
        tenant_id IS NULL
        AND current_tenant_id() IS NULL
        AND current_tenant_user_id() IS NULL
        AND current_platform_user_id() IS NULL
    )
    WITH CHECK (
        tenant_id IS NULL
        AND current_tenant_id() IS NULL
        AND current_tenant_user_id() IS NULL
        AND current_platform_user_id() IS NULL
    );

-- Make callable runtime functions explicit instead of relying on PostgreSQL's
-- default PUBLIC execute privilege.
REVOKE EXECUTE ON FUNCTION can_access_module(uuid, uuid, uuid, text, text) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION resolve_oidc_identity_link(text, text) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION accept_tenant_user_invitation(text, text, text, text, text) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION assert_no_mixed_context() FROM PUBLIC;

GRANT EXECUTE ON FUNCTION can_access_module(uuid, uuid, uuid, text, text) TO pms_app;
GRANT EXECUTE ON FUNCTION can_access_public_module(uuid, uuid, text) TO pms_app;
GRANT EXECUTE ON FUNCTION resolve_public_property_scope(uuid, text) TO pms_app;
GRANT EXECUTE ON FUNCTION resolve_oidc_identity_link(text, text) TO pms_app, pms_platform;
GRANT EXECUTE ON FUNCTION accept_tenant_user_invitation(text, text, text, text, text) TO pms_app;
GRANT EXECUTE ON FUNCTION assert_no_mixed_context() TO pms_app, pms_platform;

GRANT EXECUTE ON FUNCTION platform_user_has_permission(uuid, text) TO pms_platform;
GRANT EXECUTE ON FUNCTION can_platform_admin_access_tenant(uuid, uuid, text) TO pms_platform;

GRANT EXECUTE ON FUNCTION claim_outbox_events(text, text, integer) TO pms_worker;
GRANT EXECUTE ON FUNCTION complete_outbox_event(uuid, text) TO pms_worker;
GRANT EXECUTE ON FUNCTION fail_outbox_event(uuid, text, text, interval) TO pms_worker;
GRANT EXECUTE ON FUNCTION reclaim_stale_outbox_events(timestamp with time zone, integer) TO pms_worker;
GRANT EXECUTE ON FUNCTION dead_letter_outbox_event(uuid, text, text) TO pms_worker;

-- Optional readonly support login. RLS still applies on protected tables.
GRANT SELECT ON ALL TABLES IN SCHEMA public TO pms_readonly_support;
