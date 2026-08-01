-- =============================================================================
-- Platform Acquire: secure initial tenant-administrator bootstrap
--
-- A fresh tenant has no tenant_admin role, while ordinary tenant invitations
-- must never assign system roles. These narrowly scoped SECURITY DEFINER
-- functions prepare only the immutable tenant_admin role and allow that role
-- to be accepted only from a platform-issued initial-onboarding invitation.
-- Concurrent acceptances serialize on the tenant row.
-- =============================================================================

DO $migration$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_catalog.pg_roles
        WHERE rolname = 'pms_initial_admin_owner'
    ) THEN
        CREATE ROLE pms_initial_admin_owner
            NOLOGIN
            NOSUPERUSER
            NOCREATEDB
            NOCREATEROLE
            NOINHERIT
            NOBYPASSRLS;
    ELSE
        ALTER ROLE pms_initial_admin_owner
            NOLOGIN
            NOSUPERUSER
            NOCREATEDB
            NOCREATEROLE
            NOINHERIT
            NOBYPASSRLS;
    END IF;
END;
$migration$;

GRANT USAGE ON SCHEMA public TO pms_initial_admin_owner;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

REVOKE ALL PRIVILEGES ON TABLE
    public.tenants,
    public.permission_catalog,
    public.permissions,
    public.tenant_roles,
    public.tenant_role_permissions,
    public.tenant_modules,
    public.tenant_user_invitations,
    public.users,
    public.user_tenant_roles,
    public.identity_links
FROM pms_initial_admin_owner;

GRANT SELECT (id, status, deleted_at), UPDATE (id)
    ON TABLE public.tenants
    TO pms_initial_admin_owner;
GRANT SELECT ON TABLE public.permission_catalog TO pms_initial_admin_owner;
GRANT SELECT, INSERT (id, tenant_id, code, description),
    UPDATE (description, updated_at)
    ON TABLE public.permissions
    TO pms_initial_admin_owner;
GRANT SELECT, INSERT (
        id, tenant_id, name, code, description, is_system, is_active
    ), UPDATE (is_active, updated_at)
    ON TABLE public.tenant_roles
    TO pms_initial_admin_owner;
GRANT SELECT, INSERT (tenant_role_id, permission_id)
    ON TABLE public.tenant_role_permissions
    TO pms_initial_admin_owner;
GRANT SELECT, INSERT (
        tenant_id, module_id, is_enabled, is_configured, source, configured_at
    ), UPDATE (
        is_enabled, is_configured, source, configured_at, updated_at
    )
    ON TABLE public.tenant_modules
    TO pms_initial_admin_owner;
GRANT SELECT, UPDATE (
        status, accepted_at, accepted_user_id, updated_at
    )
    ON TABLE public.tenant_user_invitations
    TO pms_initial_admin_owner;
GRANT SELECT, INSERT (
        id, tenant_id, full_name, email, status, must_change_pw, is_active
    ), UPDATE (id)
    ON TABLE public.users
    TO pms_initial_admin_owner;
GRANT SELECT, INSERT (user_id, tenant_id, tenant_role_id)
    ON TABLE public.user_tenant_roles
    TO pms_initial_admin_owner;
GRANT SELECT, INSERT (
        id, identity_mode, provider, issuer, subject, tenant_id, user_id,
        email, linked_by_user_id
    )
    ON TABLE public.identity_links
    TO pms_initial_admin_owner;

GRANT EXECUTE ON FUNCTION public.platform_user_has_permission(
    pg_catalog.uuid,
    pg_catalog.text
) TO pms_initial_admin_owner;

DROP POLICY IF EXISTS initial_admin_owner_tenants ON public.tenants;
CREATE POLICY initial_admin_owner_tenants ON public.tenants
    FOR SELECT TO pms_initial_admin_owner
    USING (true);
DROP POLICY IF EXISTS initial_admin_owner_tenants_update ON public.tenants;
CREATE POLICY initial_admin_owner_tenants_update ON public.tenants
    FOR UPDATE TO pms_initial_admin_owner
    USING (true)
    WITH CHECK (true);

DROP POLICY IF EXISTS initial_admin_owner_permissions ON public.permissions;
CREATE POLICY initial_admin_owner_permissions ON public.permissions
    FOR ALL TO pms_initial_admin_owner
    USING (true)
    WITH CHECK (true);
DROP POLICY IF EXISTS initial_admin_owner_roles ON public.tenant_roles;
CREATE POLICY initial_admin_owner_roles ON public.tenant_roles
    FOR ALL TO pms_initial_admin_owner
    USING (true)
    WITH CHECK (true);
DROP POLICY IF EXISTS initial_admin_owner_role_permissions
    ON public.tenant_role_permissions;
CREATE POLICY initial_admin_owner_role_permissions
    ON public.tenant_role_permissions
    FOR ALL TO pms_initial_admin_owner
    USING (true)
    WITH CHECK (true);
DROP POLICY IF EXISTS initial_admin_owner_modules ON public.tenant_modules;
CREATE POLICY initial_admin_owner_modules ON public.tenant_modules
    FOR ALL TO pms_initial_admin_owner
    USING (true)
    WITH CHECK (true);
DROP POLICY IF EXISTS initial_admin_owner_invitations
    ON public.tenant_user_invitations;
CREATE POLICY initial_admin_owner_invitations
    ON public.tenant_user_invitations
    FOR ALL TO pms_initial_admin_owner
    USING (true)
    WITH CHECK (true);
DROP POLICY IF EXISTS initial_admin_owner_users ON public.users;
CREATE POLICY initial_admin_owner_users ON public.users
    FOR ALL TO pms_initial_admin_owner
    USING (true)
    WITH CHECK (true);
DROP POLICY IF EXISTS initial_admin_owner_user_roles
    ON public.user_tenant_roles;
CREATE POLICY initial_admin_owner_user_roles
    ON public.user_tenant_roles
    FOR ALL TO pms_initial_admin_owner
    USING (true)
    WITH CHECK (true);
DROP POLICY IF EXISTS initial_admin_owner_identity_links
    ON public.identity_links;
CREATE POLICY initial_admin_owner_identity_links
    ON public.identity_links
    FOR ALL TO pms_initial_admin_owner
    USING (true)
    WITH CHECK (true);

CREATE OR REPLACE FUNCTION public.prepare_initial_tenant_administrator(
    p_tenant_id pg_catalog.uuid
) RETURNS pg_catalog.uuid
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $function$
DECLARE
    v_actor_id pg_catalog.uuid;
    v_role_id pg_catalog.uuid;
    v_role_is_system pg_catalog.bool;
BEGIN
    IF NULLIF(
        pg_catalog.current_setting('app.current_tenant_id', true),
        ''
    ) IS NOT NULL OR NULLIF(
        pg_catalog.current_setting('app.current_tenant_user_id', true),
        ''
    ) IS NOT NULL THEN
        RAISE EXCEPTION USING
            ERRCODE = '42501',
            MESSAGE = 'Initial tenant administrator preparation requires an unmixed platform context';
    END IF;
    v_actor_id := NULLIF(
        pg_catalog.current_setting('app.current_platform_user_id', true),
        ''
    )::pg_catalog.uuid;

    IF v_actor_id IS NULL OR NOT public.platform_user_has_permission(
        v_actor_id,
        'platform.tenants.manage'
    ) OR NOT public.platform_user_has_permission(
        v_actor_id,
        'platform.security.manage'
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '42501',
            MESSAGE = 'Initial tenant administrator preparation is not authorized';
    END IF;

    IF p_tenant_id IS NULL THEN
        RAISE EXCEPTION USING
            ERRCODE = '22023',
            MESSAGE = 'Tenant id is required';
    END IF;

    PERFORM 1
    FROM public.tenants AS tenant
    WHERE tenant.id = p_tenant_id
      AND tenant.deleted_at IS NULL
      AND tenant.status IN ('trial', 'active')
    FOR UPDATE OF tenant;

    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = 'Active or trial tenant was not found';
    END IF;

    INSERT INTO public.permissions (id, tenant_id, code, description)
    SELECT
        pg_catalog.gen_random_uuid(),
        p_tenant_id,
        catalog.code,
        catalog.description
    FROM public.permission_catalog AS catalog
    WHERE catalog.is_tenant_permission = true
    ON CONFLICT ON CONSTRAINT permissions_tenant_id_code_key
    DO UPDATE SET
        description = EXCLUDED.description,
        updated_at = pg_catalog.now();

    SELECT role.id, role.is_system
    INTO v_role_id, v_role_is_system
    FROM public.tenant_roles AS role
    WHERE role.tenant_id = p_tenant_id
      AND role.code = 'tenant_admin'
    FOR UPDATE OF role;

    IF v_role_id IS NULL THEN
        v_role_id := pg_catalog.gen_random_uuid();
        INSERT INTO public.tenant_roles (
            id,
            tenant_id,
            name,
            code,
            description,
            is_system,
            is_active
        ) VALUES (
            v_role_id,
            p_tenant_id,
            'Tenant Administrator',
            'tenant_admin',
            'Immutable tenant administrator role provisioned by the platform',
            true,
            true
        );
    ELSIF NOT v_role_is_system THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'The tenant_admin role code is occupied by a mutable role';
    ELSE
        UPDATE public.tenant_roles
        SET is_active = true,
            updated_at = pg_catalog.now()
        WHERE id = v_role_id
          AND tenant_id = p_tenant_id
          AND is_active = false;
    END IF;

    INSERT INTO public.tenant_role_permissions (
        tenant_role_id,
        permission_id
    )
    SELECT v_role_id, permission.id
    FROM public.permissions AS permission
    JOIN public.permission_catalog AS catalog
      ON catalog.code = permission.code
     AND catalog.is_tenant_permission = true
    WHERE permission.tenant_id = p_tenant_id
    ON CONFLICT ON CONSTRAINT tenant_role_permissions_pkey DO NOTHING;

    INSERT INTO public.tenant_modules (
        tenant_id,
        module_id,
        is_enabled,
        is_configured,
        source,
        configured_at
    ) VALUES (
        p_tenant_id,
        'tenant_admin',
        true,
        true,
        'system',
        pg_catalog.now()
    )
    ON CONFLICT ON CONSTRAINT tenant_modules_tenant_id_module_id_key
    DO UPDATE SET
        is_enabled = true,
        is_configured = true,
        source = 'system',
        configured_at = COALESCE(
            public.tenant_modules.configured_at,
            pg_catalog.now()
        ),
        updated_at = pg_catalog.now();

    RETURN v_role_id;
END;
$function$;

CREATE OR REPLACE FUNCTION public.tenant_administrator_readiness(
    p_tenant_id pg_catalog.uuid
) RETURNS TABLE (
    effective_administrators pg_catalog.int4,
    pending_initial_invitations pg_catalog.int4,
    administrator_status pg_catalog.text
)
LANGUAGE plpgsql
SECURITY DEFINER
STABLE
SET search_path = pg_catalog, public, pg_temp
AS $function$
DECLARE
    v_actor_id pg_catalog.uuid;
BEGIN
    IF NULLIF(
        pg_catalog.current_setting('app.current_tenant_id', true),
        ''
    ) IS NOT NULL OR NULLIF(
        pg_catalog.current_setting('app.current_tenant_user_id', true),
        ''
    ) IS NOT NULL THEN
        RAISE EXCEPTION USING
            ERRCODE = '42501',
            MESSAGE = 'Tenant administrator readiness requires an unmixed platform context';
    END IF;
    v_actor_id := NULLIF(
        pg_catalog.current_setting('app.current_platform_user_id', true),
        ''
    )::pg_catalog.uuid;
    IF v_actor_id IS NULL OR NOT (
        public.platform_user_has_permission(v_actor_id, 'platform.tenants.view')
        OR public.platform_user_has_permission(
            v_actor_id,
            'platform.tenants.manage'
        )
        OR public.platform_user_has_permission(
            v_actor_id,
            'platform.security.manage'
        )
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '42501',
            MESSAGE = 'Tenant administrator readiness is not authorized';
    END IF;

    SELECT pg_catalog.count(DISTINCT tenant_user.id)::pg_catalog.int4
    INTO effective_administrators
    FROM public.users AS tenant_user
    JOIN public.user_tenant_roles AS assignment
      ON assignment.tenant_id = tenant_user.tenant_id
     AND assignment.user_id = tenant_user.id
    JOIN public.tenant_roles AS role
      ON role.tenant_id = assignment.tenant_id
     AND role.id = assignment.tenant_role_id
    WHERE tenant_user.tenant_id = p_tenant_id
      AND tenant_user.status = 'active'
      AND tenant_user.is_active = true
      AND tenant_user.deleted_at IS NULL
      AND (
          tenant_user.locked_until IS NULL
          OR tenant_user.locked_until <= pg_catalog.now()
      )
      AND role.code = 'tenant_admin'
      AND role.is_system = true
      AND role.is_active = true
      AND EXISTS (
          SELECT 1
          FROM public.identity_links AS identity
          WHERE identity.tenant_id = tenant_user.tenant_id
            AND identity.user_id = tenant_user.id
            AND identity.identity_mode = 'tenant'
            AND identity.provider = 'oidc'
            AND identity.revoked_at IS NULL
      );

    SELECT pg_catalog.count(*)::pg_catalog.int4
    INTO pending_initial_invitations
    FROM public.tenant_user_invitations AS invitation
    JOIN public.tenant_roles AS role
      ON role.tenant_id = invitation.tenant_id
     AND role.id = invitation.tenant_role_id
    WHERE invitation.tenant_id = p_tenant_id
      AND invitation.status = 'pending'
      AND invitation.revoked_at IS NULL
      AND invitation.expires_at > pg_catalog.now()
      AND invitation.invited_by_platform_user_id IS NOT NULL
      AND invitation.invited_by_user_id IS NULL
      AND invitation.metadata ->> 'source' = 'platform_onboarding'
      AND role.code = 'tenant_admin'
      AND role.is_system = true
      AND role.is_active = true;

    administrator_status := CASE
        WHEN effective_administrators > 0 THEN 'ready'
        WHEN pending_initial_invitations > 0 THEN 'invited'
        ELSE 'missing'
    END;

    RETURN NEXT;
END;
$function$;

CREATE OR REPLACE FUNCTION public.accept_tenant_user_invitation(
    p_token_hash pg_catalog.text,
    p_issuer pg_catalog.text,
    p_subject pg_catalog.text,
    p_email pg_catalog.text,
    p_full_name pg_catalog.text DEFAULT NULL
) RETURNS TABLE (
    invitation_id pg_catalog.uuid,
    tenant_id pg_catalog.uuid,
    user_id pg_catalog.uuid,
    tenant_role_id pg_catalog.uuid,
    email pg_catalog.text,
    identity_link_id pg_catalog.uuid
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $function$
DECLARE
    v_invitation public.tenant_user_invitations%ROWTYPE;
    v_role public.tenant_roles%ROWTYPE;
    v_email pg_catalog.text;
    v_full_name pg_catalog.text;
    v_user_id pg_catalog.uuid;
    v_identity_link_id pg_catalog.uuid;
    v_initial_platform_invitation pg_catalog.bool;
BEGIN
    IF p_token_hash IS NULL OR pg_catalog.btrim(p_token_hash) = '' THEN
        RAISE EXCEPTION USING
            ERRCODE = '22023',
            MESSAGE = 'Invitation token is required';
    END IF;
    IF p_issuer IS NULL OR pg_catalog.btrim(p_issuer) = '' THEN
        RAISE EXCEPTION USING
            ERRCODE = '22023',
            MESSAGE = 'OIDC issuer is required';
    END IF;
    IF p_subject IS NULL OR pg_catalog.btrim(p_subject) = '' THEN
        RAISE EXCEPTION USING
            ERRCODE = '22023',
            MESSAGE = 'OIDC subject is required';
    END IF;

    SELECT invitation.*
    INTO v_invitation
    FROM public.tenant_user_invitations AS invitation
    WHERE invitation.token_hash = p_token_hash
    FOR UPDATE OF invitation;

    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = 'Invitation token is invalid';
    END IF;

    PERFORM 1
    FROM public.tenants AS tenant
    WHERE tenant.id = v_invitation.tenant_id
      AND tenant.deleted_at IS NULL
      AND tenant.status IN ('trial', 'active')
    FOR UPDATE OF tenant;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = 'Active or trial tenant was not found';
    END IF;

    IF v_invitation.status <> 'pending'
       OR v_invitation.revoked_at IS NOT NULL THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'Invitation is not pending';
    END IF;
    IF v_invitation.expires_at <= pg_catalog.now() THEN
        UPDATE public.tenant_user_invitations
        SET status = 'expired'
        WHERE id = v_invitation.id;
        RAISE EXCEPTION USING
            ERRCODE = '22023',
            MESSAGE = 'Invitation has expired';
    END IF;

    SELECT role.*
    INTO v_role
    FROM public.tenant_roles AS role
    WHERE role.tenant_id = v_invitation.tenant_id
      AND role.id = v_invitation.tenant_role_id
      AND role.is_active = true;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'Invitation tenant role is no longer assignable';
    END IF;

    v_initial_platform_invitation :=
        v_role.is_system = true
        AND v_role.code = 'tenant_admin'
        AND v_invitation.invited_by_platform_user_id IS NOT NULL
        AND v_invitation.invited_by_user_id IS NULL
        AND v_invitation.metadata ->> 'source' = 'platform_onboarding';

    IF v_role.is_system AND NOT v_initial_platform_invitation THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'Invitation tenant role is no longer assignable';
    END IF;

    IF v_initial_platform_invitation AND EXISTS (
        SELECT 1
        FROM public.users AS tenant_user
        JOIN public.user_tenant_roles AS assignment
          ON assignment.tenant_id = tenant_user.tenant_id
         AND assignment.user_id = tenant_user.id
        JOIN public.tenant_roles AS role
          ON role.tenant_id = assignment.tenant_id
         AND role.id = assignment.tenant_role_id
        WHERE tenant_user.tenant_id = v_invitation.tenant_id
          AND tenant_user.status = 'active'
          AND tenant_user.is_active = true
          AND tenant_user.deleted_at IS NULL
          AND (
              tenant_user.locked_until IS NULL
              OR tenant_user.locked_until <= pg_catalog.now()
          )
          AND role.code = 'tenant_admin'
          AND role.is_system = true
          AND role.is_active = true
          AND EXISTS (
              SELECT 1
              FROM public.identity_links AS identity
              WHERE identity.tenant_id = tenant_user.tenant_id
                AND identity.user_id = tenant_user.id
                AND identity.identity_mode = 'tenant'
                AND identity.provider = 'oidc'
                AND identity.revoked_at IS NULL
          )
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'Initial tenant administrator already exists';
    END IF;

    v_email := pg_catalog.lower(
        pg_catalog.btrim(COALESCE(p_email, ''))
    );
    IF v_email = '' THEN
        v_email := v_invitation.email;
    END IF;
    IF v_email <> v_invitation.email THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'Invitation email does not match authenticated identity';
    END IF;

    v_full_name := NULLIF(
        pg_catalog.btrim(
            COALESCE(p_full_name, v_invitation.full_name, v_email)
        ),
        ''
    );

    IF EXISTS (
        SELECT 1
        FROM public.identity_links AS identity
        WHERE identity.issuer = p_issuer
          AND identity.subject = p_subject
          AND identity.revoked_at IS NULL
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23505',
            MESSAGE = 'OIDC identity is already linked';
    END IF;

    SELECT tenant_user.id
    INTO v_user_id
    FROM public.users AS tenant_user
    WHERE tenant_user.tenant_id = v_invitation.tenant_id
      AND pg_catalog.lower(tenant_user.email) = v_invitation.email
      AND tenant_user.deleted_at IS NULL
    FOR UPDATE OF tenant_user;
    IF v_user_id IS NOT NULL THEN
        RAISE EXCEPTION USING
            ERRCODE = '23505',
            MESSAGE = 'A tenant user already exists for this invitation email';
    END IF;

    v_user_id := pg_catalog.gen_random_uuid();
    INSERT INTO public.users (
        id,
        tenant_id,
        full_name,
        email,
        status,
        must_change_pw,
        is_active
    ) VALUES (
        v_user_id,
        v_invitation.tenant_id,
        v_full_name,
        v_invitation.email,
        'active',
        false,
        true
    );

    INSERT INTO public.user_tenant_roles (
        user_id,
        tenant_id,
        tenant_role_id
    ) VALUES (
        v_user_id,
        v_invitation.tenant_id,
        v_invitation.tenant_role_id
    );

    v_identity_link_id := pg_catalog.gen_random_uuid();
    INSERT INTO public.identity_links (
        id,
        identity_mode,
        provider,
        issuer,
        subject,
        tenant_id,
        user_id,
        email,
        linked_by_user_id
    ) VALUES (
        v_identity_link_id,
        'tenant',
        'oidc',
        pg_catalog.btrim(p_issuer),
        pg_catalog.btrim(p_subject),
        v_invitation.tenant_id,
        v_user_id,
        v_invitation.email,
        v_user_id
    );

    UPDATE public.tenant_user_invitations
    SET status = 'accepted',
        accepted_at = pg_catalog.now(),
        accepted_user_id = v_user_id
    WHERE id = v_invitation.id;

    RETURN QUERY
    SELECT
        v_invitation.id,
        v_invitation.tenant_id,
        v_user_id,
        v_invitation.tenant_role_id,
        v_invitation.email,
        v_identity_link_id;
END;
$function$;

ALTER FUNCTION public.prepare_initial_tenant_administrator(pg_catalog.uuid)
    OWNER TO pms_initial_admin_owner;
ALTER FUNCTION public.tenant_administrator_readiness(pg_catalog.uuid)
    OWNER TO pms_initial_admin_owner;
ALTER FUNCTION public.accept_tenant_user_invitation(
    pg_catalog.text,
    pg_catalog.text,
    pg_catalog.text,
    pg_catalog.text,
    pg_catalog.text
) OWNER TO pms_initial_admin_owner;

REVOKE ALL ON FUNCTION
    public.prepare_initial_tenant_administrator(pg_catalog.uuid)
    FROM PUBLIC;
REVOKE ALL ON FUNCTION
    public.tenant_administrator_readiness(pg_catalog.uuid)
    FROM PUBLIC;
REVOKE ALL ON FUNCTION public.accept_tenant_user_invitation(
    pg_catalog.text,
    pg_catalog.text,
    pg_catalog.text,
    pg_catalog.text,
    pg_catalog.text
) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION
    public.prepare_initial_tenant_administrator(pg_catalog.uuid)
    TO pms_platform;
GRANT EXECUTE ON FUNCTION
    public.tenant_administrator_readiness(pg_catalog.uuid)
    TO pms_platform;
GRANT EXECUTE ON FUNCTION public.accept_tenant_user_invitation(
    pg_catalog.text,
    pg_catalog.text,
    pg_catalog.text,
    pg_catalog.text,
    pg_catalog.text
) TO pms_app;

GRANT SELECT, INSERT, UPDATE ON TABLE public.tenant_user_invitations
    TO pms_platform;

COMMENT ON FUNCTION
    public.prepare_initial_tenant_administrator(pg_catalog.uuid) IS
    'Prepares only the immutable tenant_admin role and permissions for a platform-issued initial invitation.';
COMMENT ON FUNCTION
    public.tenant_administrator_readiness(pg_catalog.uuid) IS
    'Returns aggregate initial-administrator readiness without exposing tenant user records.';
COMMENT ON FUNCTION public.accept_tenant_user_invitation(
    pg_catalog.text,
    pg_catalog.text,
    pg_catalog.text,
    pg_catalog.text,
    pg_catalog.text
) IS
    'Accepts ordinary dynamic-role invitations and a serialized platform-issued initial tenant_admin invitation.';
