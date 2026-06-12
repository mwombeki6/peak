-- ================================================================================
-- Tenant user invitation acceptance
-- ================================================================================

CREATE FUNCTION accept_tenant_user_invitation(
    p_token_hash text,
    p_issuer text,
    p_subject text,
    p_email text,
    p_full_name text DEFAULT NULL
) RETURNS TABLE (
    invitation_id uuid,
    tenant_id uuid,
    user_id uuid,
    tenant_role_id uuid,
    email text,
    identity_link_id uuid
)
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = public
    AS $$
DECLARE
    v_invitation tenant_user_invitations%ROWTYPE;
    v_email text;
    v_full_name text;
    v_user_id uuid;
    v_identity_link_id uuid;
BEGIN
    IF p_token_hash IS NULL OR btrim(p_token_hash) = '' THEN
        RAISE EXCEPTION 'Invitation token is required';
    END IF;

    IF p_issuer IS NULL OR btrim(p_issuer) = '' THEN
        RAISE EXCEPTION 'OIDC issuer is required';
    END IF;

    IF p_subject IS NULL OR btrim(p_subject) = '' THEN
        RAISE EXCEPTION 'OIDC subject is required';
    END IF;

    SELECT *
    INTO v_invitation
    FROM tenant_user_invitations tui
    WHERE tui.token_hash = p_token_hash
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Invitation token is invalid';
    END IF;

    IF v_invitation.status <> 'pending' OR v_invitation.revoked_at IS NOT NULL THEN
        RAISE EXCEPTION 'Invitation is not pending';
    END IF;

    IF v_invitation.expires_at <= now() THEN
        UPDATE tenant_user_invitations
        SET status = 'expired'
        WHERE id = v_invitation.id;
        RAISE EXCEPTION 'Invitation has expired';
    END IF;

    v_email := lower(btrim(COALESCE(p_email, '')));
    IF v_email = '' THEN
        v_email := v_invitation.email;
    END IF;

    IF v_email <> v_invitation.email THEN
        RAISE EXCEPTION 'Invitation email does not match authenticated identity';
    END IF;

    v_full_name := NULLIF(btrim(COALESCE(p_full_name, v_invitation.full_name, v_email)), '');

    IF EXISTS (
        SELECT 1
        FROM identity_links il
        WHERE il.issuer = p_issuer
          AND il.subject = p_subject
          AND il.revoked_at IS NULL
    ) THEN
        RAISE EXCEPTION 'OIDC identity is already linked';
    END IF;

    SELECT u.id
    INTO v_user_id
    FROM users u
    WHERE u.tenant_id = v_invitation.tenant_id
      AND lower(u.email) = v_invitation.email
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
            v_invitation.tenant_id,
            v_full_name,
            v_invitation.email,
            'active',
            false,
            true
        );
    ELSE
        UPDATE users
        SET full_name = COALESCE(NULLIF(full_name, ''), v_full_name),
            status = 'active',
            must_change_pw = false,
            is_active = true,
            locked_until = NULL
        WHERE id = v_user_id;
    END IF;

    INSERT INTO user_tenant_roles (user_id, tenant_id, tenant_role_id)
    VALUES (v_user_id, v_invitation.tenant_id, v_invitation.tenant_role_id)
    ON CONFLICT ON CONSTRAINT user_tenant_roles_pkey DO NOTHING;

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
        linked_by_user_id
    )
    VALUES (
        v_identity_link_id,
        'tenant',
        'oidc',
        btrim(p_issuer),
        btrim(p_subject),
        v_invitation.tenant_id,
        v_user_id,
        v_invitation.email,
        v_user_id
    );

    UPDATE tenant_user_invitations
    SET status = 'accepted',
        accepted_at = now(),
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
$$;
