-- ================================================================================
-- Platform administration runtime grants and platform-scoped reliability policies
-- ================================================================================

GRANT SELECT, INSERT, UPDATE ON TABLE
    platform_users,
    platform_roles,
    identity_links,
    idempotency_keys,
    outbox_events
TO pms_platform;

GRANT SELECT, INSERT, DELETE ON TABLE
    platform_role_permissions,
    platform_user_roles
TO pms_platform;

DROP POLICY IF EXISTS idempotency_platform_request ON idempotency_keys;

CREATE POLICY idempotency_platform_request ON idempotency_keys
    FOR ALL TO pms_platform
    USING (
        tenant_id IS NULL
        AND current_platform_user_id() IS NOT NULL
        AND actor_type = 'platform_user'
        AND actor_id = current_platform_user_id()
    )
    WITH CHECK (
        tenant_id IS NULL
        AND current_platform_user_id() IS NOT NULL
        AND actor_type = 'platform_user'
        AND actor_id = current_platform_user_id()
    );

DROP POLICY IF EXISTS outbox_platform_request ON outbox_events;

CREATE POLICY outbox_platform_request ON outbox_events
    FOR ALL TO pms_platform
    USING (
        tenant_id IS NULL
        AND current_platform_user_id() IS NOT NULL
        AND destination = 'platform'
    )
    WITH CHECK (
        tenant_id IS NULL
        AND current_platform_user_id() IS NOT NULL
        AND destination = 'platform'
    );
