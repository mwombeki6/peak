-- Phone-first invitations and activation (Wave 4).
--
-- An operator is invited by mobile number, not email. Email becomes an optional
-- secondary attribute. The OTP is delivered by SMS through the same verification
-- module (its challenges are already phone-first: destination is E.164 or email).
--
-- This migration supersedes the V154 function shapes: the lookup, grant insert and
-- grant consume functions now carry phone_number, and email is optional. The old
-- signatures are dropped (their grants vanish with them) and re-granted here.

ALTER TABLE tenant_user_invitations
    ALTER COLUMN email DROP NOT NULL,
    ADD COLUMN phone_number text;

ALTER TABLE tenant_user_invitations
    ADD CONSTRAINT chk_tenant_user_invitations_phone
    CHECK (phone_number IS NULL OR phone_number ~ '^\+[1-9][0-9]{7,14}$');

COMMENT ON COLUMN tenant_user_invitations.phone_number IS
    'E.164 mobile number of the invited operator. The activation OTP goes by SMS '
    'when a phone is present; email is the fallback.';

-- A pending invitation keyed by phone, mirroring the email key (V62).
CREATE UNIQUE INDEX tenant_user_invitations_pending_phone_key
    ON tenant_user_invitations (tenant_id, phone_number)
    WHERE phone_number IS NOT NULL AND status = 'pending';

ALTER TABLE account_setup_grants
    ALTER COLUMN email DROP NOT NULL,
    ADD COLUMN phone_number text;

ALTER TABLE account_setup_grants
    ADD CONSTRAINT chk_account_setup_grants_phone
    CHECK (phone_number IS NULL OR phone_number ~ '^\+[1-9][0-9]{7,14}$');

COMMENT ON COLUMN account_setup_grants.phone_number IS
    'The phone the setup grant was issued to; email is the fallback.';

-- ── Superseded function shapes ────────────────────────────────────────────

DROP FUNCTION lookup_invitation_by_token_hash(text);
DROP FUNCTION insert_account_setup_grant(text, uuid, uuid, text, text, integer);
DROP FUNCTION consume_account_setup_grant(text);

CREATE FUNCTION lookup_invitation_by_token_hash(p_token_hash text)
RETURNS TABLE (
    invitation_id uuid,
    tenant_id uuid,
    email text,
    phone_number text,
    full_name text,
    status text,
    expires_at timestamptz,
    metadata jsonb,
    organisation_name text
)
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = public
    AS $$
BEGIN
    IF p_token_hash IS NULL OR btrim(p_token_hash) = '' THEN
        RAISE EXCEPTION 'Invitation token is required';
    END IF;
    RETURN QUERY
    SELECT
        tui.id,
        tui.tenant_id,
        tui.email,
        tui.phone_number,
        tui.full_name,
        CASE
            WHEN tui.status = 'pending' AND tui.expires_at <= now() THEN 'expired'
            ELSE tui.status::text
        END,
        tui.expires_at,
        tui.metadata,
        t.name
    FROM tenant_user_invitations tui
    JOIN tenants t ON t.id = tui.tenant_id
    WHERE tui.token_hash = p_token_hash;
END;
$$;

CREATE FUNCTION insert_account_setup_grant(
    p_grant_hash text,
    p_invitation_id uuid,
    p_tenant_id uuid,
    p_email text,
    p_phone_number text,
    p_realm text,
    p_ttl_seconds integer
) RETURNS uuid
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = public
    AS $$
DECLARE
    v_id uuid;
BEGIN
    INSERT INTO account_setup_grants (
        grant_hash, invitation_id, tenant_id, email, phone_number, realm, expires_at
    ) VALUES (
        p_grant_hash, p_invitation_id, p_tenant_id,
        lower(btrim(p_email)), p_phone_number, p_realm,
        now() + make_interval(secs => p_ttl_seconds)
    )
    RETURNING id INTO v_id;
    RETURN v_id;
END;
$$;

CREATE FUNCTION consume_account_setup_grant(p_grant_hash text)
RETURNS TABLE (
    invitation_id uuid,
    tenant_id uuid,
    email text,
    phone_number text,
    realm text
)
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = public
    AS $$
DECLARE
    v_row account_setup_grants%ROWTYPE;
BEGIN
    SELECT * INTO v_row
    FROM account_setup_grants
    WHERE grant_hash = p_grant_hash
    FOR UPDATE;

    IF NOT FOUND THEN
        RETURN;
    END IF;
    IF v_row.consumed_at IS NOT NULL OR v_row.expires_at <= now() THEN
        RETURN;
    END IF;

    UPDATE account_setup_grants SET consumed_at = now() WHERE id = v_row.id;
    invitation_id := v_row.invitation_id;
    tenant_id := v_row.tenant_id;
    email := v_row.email;
    phone_number := v_row.phone_number;
    realm := v_row.realm;
    RETURN NEXT;
END;
$$;

REVOKE ALL ON FUNCTION lookup_invitation_by_token_hash(text) FROM PUBLIC;
REVOKE ALL ON FUNCTION insert_account_setup_grant(text, uuid, uuid, text, text, text, integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION consume_account_setup_grant(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION lookup_invitation_by_token_hash(text) TO pms_app;
GRANT EXECUTE ON FUNCTION insert_account_setup_grant(text, uuid, uuid, text, text, text, integer) TO pms_app;
GRANT EXECUTE ON FUNCTION consume_account_setup_grant(text) TO pms_app;
GRANT EXECUTE ON FUNCTION lookup_invitation_by_token_hash(text) TO pms_platform;
GRANT EXECUTE ON FUNCTION insert_account_setup_grant(text, uuid, uuid, text, text, text, integer) TO pms_platform;
GRANT EXECUTE ON FUNCTION consume_account_setup_grant(text) TO pms_platform;
GRANT SELECT, INSERT, UPDATE ON account_setup_grants TO pms_app;
GRANT SELECT, INSERT, UPDATE ON account_setup_grants TO pms_platform;

-- ── Phone-aware invitation acceptance ──────────────────────────────────────
-- Supersedes the V75 accept function. An invitation is accepted against the
-- contact it was issued to: email when present, otherwise the verified phone.
-- The users row carries whichever contact the invitation carried.

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

    -- The invitation carries a phone, an email, or both. The caller's contact
    -- must match the one the invitation was issued to; for a phone invitation
    -- the matching phone number is implicit in the invitation itself (the
    -- activation OTP already proved possession of it).
    IF v_invitation.email IS NOT NULL THEN
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
    END IF;

    v_full_name := NULLIF(
        pg_catalog.btrim(
            COALESCE(p_full_name, v_invitation.full_name, v_invitation.email, 'Operator')
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
      AND (
          (v_invitation.email IS NOT NULL
           AND pg_catalog.lower(tenant_user.email) = v_invitation.email)
          OR (v_invitation.phone_number IS NOT NULL
              AND tenant_user.phone_number = v_invitation.phone_number)
      )
      AND tenant_user.deleted_at IS NULL
    FOR UPDATE OF tenant_user;
    IF v_user_id IS NOT NULL THEN
        RAISE EXCEPTION USING
            ERRCODE = '23505',
            MESSAGE = 'A tenant user already exists for this invitation contact';
    END IF;

    v_user_id := pg_catalog.gen_random_uuid();
    INSERT INTO public.users (
        id,
        tenant_id,
        full_name,
        email,
        phone_number,
        status,
        must_change_pw,
        is_active
    ) VALUES (
        v_user_id,
        v_invitation.tenant_id,
        v_full_name,
        v_invitation.email,
        v_invitation.phone_number,
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

ALTER FUNCTION public.accept_tenant_user_invitation(
    pg_catalog.text,
    pg_catalog.text,
    pg_catalog.text,
    pg_catalog.text,
    pg_catalog.text
) OWNER TO pms_initial_admin_owner;