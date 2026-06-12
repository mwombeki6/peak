-- ================================================================================
-- User identity links and tenant user invitations
-- ================================================================================

CREATE TABLE identity_links (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    identity_mode character varying(20) NOT NULL,
    provider character varying(50) DEFAULT 'oidc' NOT NULL,
    issuer text NOT NULL,
    subject text NOT NULL,
    tenant_id uuid,
    user_id uuid,
    platform_user_id uuid,
    email text,
    linked_by_user_id uuid,
    linked_by_platform_user_id uuid,
    last_seen_at timestamp with time zone,
    revoked_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_identity_links_mode CHECK (((identity_mode)::text = ANY ((ARRAY['tenant', 'platform'])::text[]))),
    CONSTRAINT chk_identity_links_target CHECK (
        (
            (identity_mode)::text = 'tenant'
            AND tenant_id IS NOT NULL
            AND user_id IS NOT NULL
            AND platform_user_id IS NULL
        )
        OR (
            (identity_mode)::text = 'platform'
            AND tenant_id IS NULL
            AND user_id IS NULL
            AND platform_user_id IS NOT NULL
        )
    )
);

ALTER TABLE ONLY identity_links
    ADD CONSTRAINT identity_links_pkey PRIMARY KEY (id);

CREATE UNIQUE INDEX identity_links_active_subject_key
    ON identity_links (issuer, subject)
    WHERE revoked_at IS NULL;

CREATE INDEX idx_identity_links_tenant_user
    ON identity_links (tenant_id, user_id)
    WHERE identity_mode = 'tenant' AND revoked_at IS NULL;

CREATE INDEX idx_identity_links_platform_user
    ON identity_links (platform_user_id)
    WHERE identity_mode = 'platform' AND revoked_at IS NULL;

ALTER TABLE ONLY identity_links
    ADD CONSTRAINT fk_identity_links_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY identity_links
    ADD CONSTRAINT fk_identity_links_tenant_user FOREIGN KEY (tenant_id, user_id) REFERENCES users(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY identity_links
    ADD CONSTRAINT fk_identity_links_platform_user FOREIGN KEY (platform_user_id) REFERENCES platform_users(id) DEFERRABLE;

ALTER TABLE ONLY identity_links
    ADD CONSTRAINT fk_identity_links_linked_by_user FOREIGN KEY (tenant_id, linked_by_user_id) REFERENCES users(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY identity_links
    ADD CONSTRAINT fk_identity_links_linked_by_platform_user FOREIGN KEY (linked_by_platform_user_id) REFERENCES platform_users(id) DEFERRABLE;

CREATE TRIGGER trg_identity_links_updated_at
    BEFORE UPDATE ON identity_links
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE identity_links ENABLE ROW LEVEL SECURITY;
ALTER TABLE ONLY identity_links FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_identity_links ON identity_links
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

CREATE POLICY platform_identity_links ON identity_links
    USING (
        (platform_user_id = current_platform_user_id())
        OR platform_user_has_permission(current_platform_user_id(), 'platform.security.manage')
    )
    WITH CHECK (platform_user_has_permission(current_platform_user_id(), 'platform.security.manage'));

CREATE TABLE tenant_user_invitations (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    email text NOT NULL,
    full_name text,
    tenant_role_id uuid NOT NULL,
    status character varying(20) DEFAULT 'pending' NOT NULL,
    token_hash text NOT NULL,
    invited_by_user_id uuid,
    invited_by_platform_user_id uuid,
    expires_at timestamp with time zone NOT NULL,
    accepted_at timestamp with time zone,
    accepted_user_id uuid,
    revoked_at timestamp with time zone,
    revoked_by_user_id uuid,
    metadata jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_tenant_user_invitations_status CHECK (((status)::text = ANY ((ARRAY['pending', 'accepted', 'revoked', 'expired'])::text[]))),
    CONSTRAINT chk_tenant_user_invitations_email CHECK (email = lower(btrim(email))),
    CONSTRAINT chk_tenant_user_invitations_expiry CHECK (expires_at > created_at),
    CONSTRAINT chk_tenant_user_invitations_inviter CHECK (
        invited_by_user_id IS NOT NULL OR invited_by_platform_user_id IS NOT NULL
    ),
    CONSTRAINT chk_tenant_user_invitations_acceptance CHECK (
        (
            status = 'accepted'
            AND accepted_at IS NOT NULL
            AND accepted_user_id IS NOT NULL
        )
        OR (
            status <> 'accepted'
            AND accepted_at IS NULL
            AND accepted_user_id IS NULL
        )
    )
);

ALTER TABLE ONLY tenant_user_invitations
    ADD CONSTRAINT tenant_user_invitations_pkey PRIMARY KEY (id);

ALTER TABLE ONLY tenant_user_invitations
    ADD CONSTRAINT tenant_user_invitations_token_hash_key UNIQUE (token_hash);

CREATE UNIQUE INDEX tenant_user_invitations_pending_email_key
    ON tenant_user_invitations (tenant_id, email)
    WHERE status = 'pending' AND revoked_at IS NULL;

CREATE INDEX idx_tenant_user_invitations_tenant_status
    ON tenant_user_invitations (tenant_id, status, expires_at);

ALTER TABLE ONLY tenant_user_invitations
    ADD CONSTRAINT fk_tenant_user_invitations_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY tenant_user_invitations
    ADD CONSTRAINT fk_tenant_user_invitations_role FOREIGN KEY (tenant_id, tenant_role_id) REFERENCES tenant_roles(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY tenant_user_invitations
    ADD CONSTRAINT fk_tenant_user_invitations_invited_by_user FOREIGN KEY (tenant_id, invited_by_user_id) REFERENCES users(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY tenant_user_invitations
    ADD CONSTRAINT fk_tenant_user_invitations_invited_by_platform_user FOREIGN KEY (invited_by_platform_user_id) REFERENCES platform_users(id) DEFERRABLE;

ALTER TABLE ONLY tenant_user_invitations
    ADD CONSTRAINT fk_tenant_user_invitations_accepted_user FOREIGN KEY (tenant_id, accepted_user_id) REFERENCES users(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY tenant_user_invitations
    ADD CONSTRAINT fk_tenant_user_invitations_revoked_by_user FOREIGN KEY (tenant_id, revoked_by_user_id) REFERENCES users(tenant_id, id) DEFERRABLE;

CREATE TRIGGER trg_tenant_user_invitations_updated_at
    BEFORE UPDATE ON tenant_user_invitations
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE tenant_user_invitations ENABLE ROW LEVEL SECURITY;
ALTER TABLE ONLY tenant_user_invitations FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_user_invitations_tenant ON tenant_user_invitations
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

CREATE POLICY tenant_user_invitations_platform ON tenant_user_invitations
    USING (platform_user_has_permission(current_platform_user_id(), 'platform.security.manage'))
    WITH CHECK (platform_user_has_permission(current_platform_user_id(), 'platform.security.manage'));
