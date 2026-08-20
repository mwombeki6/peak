-- Peak-owned invitation activation and recovery (Wave 3).
-- Unauthenticated callers have no tenant context, so invitation lookup and
-- setup-grant consume go through SECURITY DEFINER functions — the same shape
-- as accept_tenant_user_invitation (V5) and verification_challenges (V144).
-- The full email never leaves these functions into an HTTP body; Peak masks it.

CREATE TABLE account_setup_grants (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    grant_hash text NOT NULL,
    invitation_id uuid REFERENCES tenant_user_invitations(id) DEFERRABLE,
    tenant_id uuid REFERENCES tenants(id) DEFERRABLE,
    email text NOT NULL,
    realm text NOT NULL,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_account_setup_grants_realm CHECK (realm IN ('peak-hospitality', 'peak-platform')),
    CONSTRAINT chk_account_setup_grants_email CHECK (email = lower(btrim(email))),
    CONSTRAINT chk_account_setup_grants_target CHECK (
        invitation_id IS NOT NULL
        OR tenant_id IS NOT NULL
        OR realm = 'peak-platform'
    )
);

CREATE UNIQUE INDEX account_setup_grants_hash_key ON account_setup_grants (grant_hash);
CREATE INDEX account_setup_grants_invitation_idx ON account_setup_grants (invitation_id)
    WHERE consumed_at IS NULL;

ALTER TABLE account_setup_grants ENABLE ROW LEVEL SECURITY;
ALTER TABLE account_setup_grants FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON account_setup_grants
    USING (tenant_id IS NULL OR tenant_id = current_tenant_id())
    WITH CHECK (tenant_id IS NULL OR tenant_id = current_tenant_id());

COMMENT ON TABLE account_setup_grants IS
    'Opaque single-use grant issued after an invitation or recovery code is confirmed. '
    'grant_hash is SHA-256 of the bearer token; Peak never stores the token.';

CREATE FUNCTION lookup_invitation_by_token_hash(p_token_hash text)
RETURNS TABLE (
    invitation_id uuid,
    tenant_id uuid,
    email text,
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
        grant_hash, invitation_id, tenant_id, email, realm, expires_at
    ) VALUES (
        p_grant_hash, p_invitation_id, p_tenant_id, lower(btrim(p_email)), p_realm,
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
    realm := v_row.realm;
    RETURN NEXT;
END;
$$;

REVOKE ALL ON FUNCTION lookup_invitation_by_token_hash(text) FROM PUBLIC;
REVOKE ALL ON FUNCTION insert_account_setup_grant(text, uuid, uuid, text, text, integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION consume_account_setup_grant(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION lookup_invitation_by_token_hash(text) TO pms_app;
GRANT EXECUTE ON FUNCTION insert_account_setup_grant(text, uuid, uuid, text, text, integer) TO pms_app;
GRANT EXECUTE ON FUNCTION consume_account_setup_grant(text) TO pms_app;
GRANT SELECT, INSERT, UPDATE ON account_setup_grants TO pms_app;

INSERT INTO module_access_matrix (
    module_id, screen_key, screen_label, http_method, api_pattern,
    permission_code, route_scope, guard_mode, access_scope,
    is_tanzania_v1, is_enabled_by_default, notes
) VALUES
    (
        'tenant_admin', 'account.invitation.lookup', 'Look up invitation',
        'GET', '/api/invitations/:invitationToken',
        NULL, 'public', 'public_token', 'tenant',
        true, true,
        'Unauthenticated: Peak masks the email. Unknown tokens return invitation_not_found.'
    ),
    (
        'tenant_admin', 'account.invitation.send_code', 'Send invitation code',
        'POST', '/api/invitations/:invitationToken/send-code',
        NULL, 'public', 'public_token', 'tenant',
        true, true,
        'Unauthenticated: dispatches a six-digit code to the invited address'
    ),
    (
        'tenant_admin', 'account.invitation.verify_code', 'Verify invitation code',
        'POST', '/api/invitations/:invitationToken/verify-code',
        NULL, 'public', 'public_token', 'tenant',
        true, true,
        'Unauthenticated: issues a single-use setup grant after the code is confirmed'
    ),
    (
        'tenant_admin', 'account.invitation.set_credential', 'Set invitation credential',
        'POST', '/api/invitations/:invitationToken/set-credential',
        NULL, 'public', 'public_token', 'tenant',
        true, true,
        'Unauthenticated: establishes the identity-provider credential and Peak session'
    ),
    (
        'tenant_admin', 'account.invitation.confirm_recovery', 'Confirm recovery enrolment',
        'POST', '/api/invitations/:invitationToken/confirm-recovery-code',
        NULL, 'public', 'public_token', 'tenant',
        true, true,
        'Unauthenticated, platform only: confirms the authenticator-app backup code'
    ),
    (
        'tenant_admin', 'account.recovery.start', 'Start account recovery',
        'POST', '/api/auth/recovery/start',
        NULL, 'public', 'public_token', 'tenant',
        true, true,
        'Unauthenticated: never discloses whether the address exists'
    ),
    (
        'tenant_admin', 'account.recovery.verify_code', 'Verify recovery code',
        'POST', '/api/auth/recovery/verify-code',
        NULL, 'public', 'public_token', 'tenant',
        true, true,
        'Unauthenticated: same shape as invitation verify-code'
    ),
    (
        'tenant_admin', 'account.recovery.set_credential', 'Set recovery credential',
        'POST', '/api/auth/recovery/set-credential',
        NULL, 'public', 'public_token', 'tenant',
        true, true,
        'Unauthenticated: replaces the identity-provider credential after a verified recovery'
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
