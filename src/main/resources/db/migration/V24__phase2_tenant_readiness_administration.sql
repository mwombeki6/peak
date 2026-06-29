-- ================================================================================
-- Phase 2 tenant bootstrap and readiness administration
-- ================================================================================

INSERT INTO plans (
    id,
    name,
    code,
    max_properties,
    max_rooms,
    max_users,
    max_outlets,
    features,
    monthly_usd,
    annual_usd,
    is_active
) VALUES
    (
        '20202020-0000-0000-0000-000000000001',
        'Starter',
        'starter',
        1,
        50,
        10,
        2,
        '{"tier":"starter"}'::jsonb,
        0,
        0,
        true
    ),
    (
        '20202020-0000-0000-0000-000000000002',
        'Professional',
        'pro',
        5,
        500,
        100,
        20,
        '{"tier":"pro"}'::jsonb,
        0,
        0,
        true
    ),
    (
        '20202020-0000-0000-0000-000000000003',
        'Enterprise',
        'enterprise',
        100,
        10000,
        5000,
        500,
        '{"tier":"enterprise"}'::jsonb,
        0,
        0,
        true
    )
ON CONFLICT (code) DO UPDATE SET
    name = EXCLUDED.name,
    max_properties = EXCLUDED.max_properties,
    max_rooms = EXCLUDED.max_rooms,
    max_users = EXCLUDED.max_users,
    max_outlets = EXCLUDED.max_outlets,
    features = EXCLUDED.features,
    is_active = EXCLUDED.is_active,
    updated_at = now();

CREATE FUNCTION provision_tenant_administrator(
    p_tenant_id uuid,
    p_full_name text,
    p_email text,
    p_issuer text,
    p_subject text
) RETURNS TABLE (
    user_id uuid,
    tenant_role_id uuid,
    identity_link_id uuid,
    changed boolean
)
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = public
    AS $$
DECLARE
    v_actor_id uuid;
    v_user_id uuid;
    v_user_status text;
    v_user_active boolean;
    v_role_id uuid;
    v_role_is_system boolean;
    v_identity_link_id uuid;
    v_existing_tenant_id uuid;
    v_existing_user_id uuid;
    v_changed boolean := false;
    v_rows integer;
    v_email text;
BEGIN
    v_actor_id := assert_platform_context();

    IF NOT platform_user_has_permission(v_actor_id, 'platform.tenants.manage')
       OR NOT platform_user_has_permission(v_actor_id, 'platform.security.manage') THEN
        RAISE EXCEPTION 'Tenant administrator provisioning requires tenant and security management permissions';
    END IF;

    IF p_tenant_id IS NULL THEN
        RAISE EXCEPTION 'Tenant id is required';
    END IF;
    IF NULLIF(btrim(p_full_name), '') IS NULL THEN
        RAISE EXCEPTION 'Tenant administrator full name is required';
    END IF;
    IF NULLIF(btrim(p_email), '') IS NULL THEN
        RAISE EXCEPTION 'Tenant administrator email is required';
    END IF;
    IF NULLIF(btrim(p_issuer), '') IS NULL OR NULLIF(btrim(p_subject), '') IS NULL THEN
        RAISE EXCEPTION 'OIDC issuer and subject are required';
    END IF;

    v_email := lower(btrim(p_email));

    PERFORM 1
    FROM tenants t
    WHERE t.id = p_tenant_id
      AND t.deleted_at IS NULL
      AND t.status IN ('trial', 'active')
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Active or trial tenant was not found';
    END IF;

    SELECT u.id, u.status, u.is_active
    INTO v_user_id, v_user_status, v_user_active
    FROM users u
    WHERE u.tenant_id = p_tenant_id
      AND lower(u.email) = v_email
      AND u.deleted_at IS NULL
    FOR UPDATE;

    IF v_user_id IS NULL THEN
        v_user_id := gen_random_uuid();
        INSERT INTO users (
            id,
            tenant_id,
            full_name,
            email,
            status,
            must_change_pw,
            is_active
        )
        VALUES (
            v_user_id,
            p_tenant_id,
            btrim(p_full_name),
            v_email,
            'active',
            false,
            true
        );
        v_changed := true;
    ELSE
        IF v_user_status IN ('disabled', 'locked') OR NOT v_user_active THEN
            RAISE EXCEPTION 'Existing tenant administrator user is not active';
        END IF;

        UPDATE users
        SET full_name = btrim(p_full_name),
            status = 'active',
            must_change_pw = false,
            locked_until = NULL,
            updated_at = now()
        WHERE id = v_user_id
          AND (
              full_name IS DISTINCT FROM btrim(p_full_name)
              OR status IS DISTINCT FROM 'active'
              OR must_change_pw
              OR locked_until IS NOT NULL
          );
        GET DIAGNOSTICS v_rows = ROW_COUNT;
        v_changed := v_changed OR v_rows > 0;
    END IF;

    INSERT INTO permissions (id, tenant_id, code, description)
    SELECT gen_random_uuid(), p_tenant_id, pc.code, pc.description
    FROM permission_catalog pc
    WHERE pc.is_tenant_permission = true
    ON CONFLICT ON CONSTRAINT permissions_tenant_id_code_key
    DO UPDATE SET
        description = EXCLUDED.description,
        updated_at = now();

    SELECT tr.id, tr.is_system
    INTO v_role_id, v_role_is_system
    FROM tenant_roles tr
    WHERE tr.tenant_id = p_tenant_id
      AND tr.code = 'tenant_admin'
    FOR UPDATE;

    IF v_role_id IS NULL THEN
        v_role_id := gen_random_uuid();
        INSERT INTO tenant_roles (
            id,
            tenant_id,
            name,
            code,
            description,
            is_system,
            is_active
        )
        VALUES (
            v_role_id,
            p_tenant_id,
            'Tenant Administrator',
            'tenant_admin',
            'Immutable tenant administrator role provisioned by the platform',
            true,
            true
        );
        v_changed := true;
    ELSIF NOT v_role_is_system THEN
        RAISE EXCEPTION 'The tenant_admin role code is occupied by a mutable role';
    ELSE
        UPDATE tenant_roles
        SET is_active = true,
            updated_at = now()
        WHERE id = v_role_id
          AND is_active = false;
        GET DIAGNOSTICS v_rows = ROW_COUNT;
        v_changed := v_changed OR v_rows > 0;
    END IF;

    INSERT INTO tenant_role_permissions (tenant_role_id, permission_id)
    SELECT v_role_id, p.id
    FROM permissions p
    JOIN permission_catalog pc
      ON pc.code = p.code
     AND pc.is_tenant_permission = true
    WHERE p.tenant_id = p_tenant_id
    ON CONFLICT ON CONSTRAINT tenant_role_permissions_pkey DO NOTHING;

    INSERT INTO user_tenant_roles (
        user_id,
        tenant_id,
        tenant_role_id,
        assigned_by
    )
    VALUES (v_user_id, p_tenant_id, v_role_id, NULL)
    ON CONFLICT ON CONSTRAINT user_tenant_roles_pkey DO NOTHING;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    v_changed := v_changed OR v_rows > 0;

    INSERT INTO tenant_modules (
        tenant_id,
        module_id,
        is_enabled,
        is_configured,
        source,
        configured_at
    )
    VALUES (
        p_tenant_id,
        'tenant_admin',
        true,
        true,
        'system',
        now()
    )
    ON CONFLICT ON CONSTRAINT tenant_modules_tenant_id_module_id_key
    DO UPDATE SET
        is_enabled = true,
        is_configured = true,
        source = 'system',
        configured_at = COALESCE(tenant_modules.configured_at, now()),
        updated_at = now();

    SELECT il.id, il.tenant_id, il.user_id
    INTO v_identity_link_id, v_existing_tenant_id, v_existing_user_id
    FROM identity_links il
    WHERE il.provider = 'oidc'
      AND il.issuer = btrim(p_issuer)
      AND il.subject = btrim(p_subject)
      AND il.revoked_at IS NULL
    FOR UPDATE;

    IF v_identity_link_id IS NULL THEN
        v_identity_link_id := gen_random_uuid();
        INSERT INTO identity_links (
            id,
            identity_mode,
            provider,
            issuer,
            subject,
            tenant_id,
            user_id,
            email,
            linked_by_platform_user_id
        )
        VALUES (
            v_identity_link_id,
            'tenant',
            'oidc',
            btrim(p_issuer),
            btrim(p_subject),
            p_tenant_id,
            v_user_id,
            v_email,
            v_actor_id
        );
        v_changed := true;
    ELSIF v_existing_tenant_id IS DISTINCT FROM p_tenant_id
       OR v_existing_user_id IS DISTINCT FROM v_user_id THEN
        RAISE EXCEPTION 'OIDC identity is already linked to another user';
    END IF;

    RETURN QUERY SELECT v_user_id, v_role_id, v_identity_link_id, v_changed;
END;
$$;

CREATE FUNCTION verify_tenant_business_profile(
    p_tenant_id uuid
) RETURNS TABLE (
    verification_status text,
    changed boolean
)
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = public
    AS $$
DECLARE
    v_actor_id uuid;
    v_rows integer;
BEGIN
    v_actor_id := assert_platform_context();
    IF NOT platform_user_has_permission(v_actor_id, 'platform.tenants.verify') THEN
        RAISE EXCEPTION 'Tenant profile verification permission is required';
    END IF;

    PERFORM 1
    FROM tenants t
    WHERE t.id = p_tenant_id
      AND t.deleted_at IS NULL
      AND t.status IN ('trial', 'active')
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Active or trial tenant was not found';
    END IF;

    UPDATE tenant_profiles tp
    SET verification_status = 'verified',
        verified_at = now(),
        verified_by_platform_user_id = v_actor_id,
        rejection_reason = NULL,
        updated_at = now()
    WHERE tp.tenant_id = p_tenant_id
      AND (
          tp.verification_status <> 'verified'
          OR tp.verified_at IS NULL
          OR tp.verified_by_platform_user_id IS DISTINCT FROM v_actor_id
      );
    GET DIAGNOSTICS v_rows = ROW_COUNT;

    IF NOT EXISTS (
        SELECT 1
        FROM tenant_profiles tp
        WHERE tp.tenant_id = p_tenant_id
    ) THEN
        RAISE EXCEPTION 'Tenant business profile was not found';
    END IF;

    RETURN QUERY SELECT 'verified'::text, v_rows > 0;
END;
$$;

REVOKE EXECUTE ON FUNCTION provision_tenant_administrator(uuid, text, text, text, text) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION verify_tenant_business_profile(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION provision_tenant_administrator(uuid, text, text, text, text) TO pms_platform;
GRANT EXECUTE ON FUNCTION verify_tenant_business_profile(uuid) TO pms_platform;

GRANT SELECT ON TABLE contact_role_catalog, report_catalog TO pms_app;
GRANT SELECT, INSERT, UPDATE ON TABLE tenant_contact_roles TO pms_app;
GRANT SELECT, INSERT ON TABLE communication_consents TO pms_app;
GRANT SELECT, INSERT, UPDATE ON TABLE
    report_subscriptions,
    report_subscription_recipients
TO pms_app;

CREATE UNIQUE INDEX idx_tenant_contact_roles_active_assignment
    ON tenant_contact_roles (
        tenant_id,
        contact_id,
        COALESCE(property_id, '00000000-0000-0000-0000-000000000000'::uuid),
        role_code
    )
    WHERE effective_to IS NULL;

CREATE UNIQUE INDEX idx_report_subscription_recipient_channel
    ON report_subscription_recipients (
        tenant_id,
        subscription_id,
        contact_id,
        contact_channel_id
    );

INSERT INTO module_access_matrix (
    module_id,
    screen_key,
    screen_label,
    http_method,
    api_pattern,
    permission_code,
    route_scope,
    guard_mode,
    access_scope,
    is_tanzania_v1,
    is_enabled_by_default,
    notes
) VALUES
    (
        'platform_admin',
        'platform.tenant_administrator.provision',
        'Provision Tenant Administrator',
        'POST',
        '/api/platform/tenants/:tenantId/administrators',
        'platform.security.manage',
        'platform',
        'platform_permission',
        'platform',
        true,
        true,
        'Provision the first immutable tenant administrator role, user, permissions, and OIDC identity link'
    ),
    (
        'platform_admin',
        'platform.tenant_profile.verify',
        'Verify Tenant Business Profile',
        'POST',
        '/api/platform/tenants/:tenantId/profile/verify',
        'platform.tenants.verify',
        'platform',
        'platform_permission',
        'platform',
        true,
        true,
        'Verify a tenant business profile after platform review'
    ),
    (
        'communications',
        'communications.contact_roles.assign',
        'Assign Contact Role',
        'POST',
        '/api/communication/contacts/:contactId/roles',
        'communications.manage',
        'tenant',
        'staff_permission',
        'tenant',
        true,
        true,
        'Assign a tenant or property business role to an active contact'
    ),
    (
        'communications',
        'communications.contact_consents.record',
        'Record Contact Consent',
        'POST',
        '/api/communication/contacts/:contactId/channels/:channelId/consents',
        'communications.manage',
        'tenant',
        'staff_permission',
        'tenant',
        true,
        true,
        'Append a consent state for a verified contact channel'
    ),
    (
        'communications',
        'communications.report_recipients.configure',
        'Configure Report Recipient',
        'POST',
        '/api/communication/report-recipients',
        'communications.manage',
        'tenant',
        'staff_permission',
        'tenant',
        true,
        true,
        'Configure a consent-aware operational report subscription recipient'
    ),
    (
        'communications',
        'communications.report_recipients.list',
        'Report Recipients',
        'GET',
        '/api/communication/report-recipients',
        'communications.view',
        'tenant',
        'staff_permission',
        'tenant',
        true,
        true,
        'List configured operational report recipients'
    )
ON CONFLICT (module_id, screen_key, http_method, api_pattern, permission_code)
DO UPDATE SET
    screen_label = EXCLUDED.screen_label,
    route_scope = EXCLUDED.route_scope,
    guard_mode = EXCLUDED.guard_mode,
    access_scope = EXCLUDED.access_scope,
    is_tanzania_v1 = EXCLUDED.is_tanzania_v1,
    is_enabled_by_default = EXCLUDED.is_enabled_by_default,
    notes = EXCLUDED.notes,
    updated_at = now();
