-- V146 — an applicant is not a tenant. Reuses the KYB engine already built for tenants
-- (tenant_verification_cases/documents, TenantTrustControlService) rather than cloning it:
-- a verification case's subject becomes either a tenant OR a pre-tenant onboarding
-- application, never both, enforced by a CHECK constraint rather than convention. Tenant
-- provisioning re-points an approved case's subject from the application to the newly
-- created tenant, so evidence carries forward without a second upload.
--
-- Public request-access has no tenant and no onboarding session yet, the same shape
-- device_pairing_requests (V120) and verification_challenges (V144) were already in:
-- reachable only through SECURITY DEFINER functions owned by a dedicated NOBYPASSRLS role.

DO $migration$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pms_onboarding_owner') THEN
        CREATE ROLE pms_onboarding_owner
            NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
    ELSE
        ALTER ROLE pms_onboarding_owner
            NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
    END IF;
END;
$migration$;

-- ================================================================================
-- 1. Session context: an onboarding-applicant session is its own identity, not a
--    weaker tenant session — it carries no tenant_id and must never satisfy a
--    tenant-scoped check.
-- ================================================================================

CREATE FUNCTION current_onboarding_application_id() RETURNS uuid
    LANGUAGE sql STABLE
    AS $$
  SELECT nullif(current_setting('app.current_onboarding_application_id', true), '')::uuid;
$$;

CREATE OR REPLACE FUNCTION assert_no_mixed_context() RETURNS void
    LANGUAGE plpgsql STABLE
    AS $$
BEGIN
  IF current_platform_user_id() IS NOT NULL
     AND (current_tenant_id() IS NOT NULL OR current_tenant_user_id() IS NOT NULL) THEN
    RAISE EXCEPTION 'Mixed tenant and platform context is not allowed';
  END IF;

  IF current_tenant_user_id() IS NOT NULL AND current_tenant_id() IS NULL THEN
    RAISE EXCEPTION 'Tenant user context requires tenant context';
  END IF;

  IF current_onboarding_application_id() IS NOT NULL
     AND (current_tenant_id() IS NOT NULL OR current_platform_user_id() IS NOT NULL) THEN
    RAISE EXCEPTION 'Onboarding applicant context cannot carry tenant or platform authority';
  END IF;
END;
$$;

-- ================================================================================
-- 2. The pre-tenant applicant
-- ================================================================================

CREATE TABLE onboarding_applications (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    status text NOT NULL DEFAULT 'DRAFT',
    representative_full_name text NOT NULL,
    representative_phone text NOT NULL,
    business_name text,
    country_code varchar(2) NOT NULL DEFAULT 'TZ',
    city text,
    property_type text,
    approx_property_count integer,
    approx_room_count integer,
    tenant_id uuid REFERENCES tenants(id) DEFERRABLE,
    decided_at timestamptz,
    decided_by_platform_user_id uuid REFERENCES platform_users(id) DEFERRABLE,
    rejection_reason text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_onboarding_applications_status CHECK (status IN (
        'DRAFT', 'PHONE_VERIFIED', 'IN_PROGRESS', 'SUBMITTED', 'UNDER_REVIEW',
        'INFORMATION_REQUIRED', 'RESUBMITTED', 'VERIFIED', 'REJECTED', 'APPROVED',
        'TENANT_PROVISIONED'
    )),
    CONSTRAINT chk_onboarding_applications_phone_e164
        CHECK (representative_phone ~ '^\+[1-9][0-9]{7,14}$'),
    CONSTRAINT chk_onboarding_applications_provisioned
        CHECK ((status <> 'TENANT_PROVISIONED') OR (tenant_id IS NOT NULL))
);

COMMENT ON TABLE onboarding_applications IS
    'A prospect, not a tenant. Nothing here is tenant-scoped data; a row only ever gains a '
    'tenant_id once APPROVED becomes TENANT_PROVISIONED, and the application row is never '
    'read again as tenant-authoritative after that — the tenant tables are.';

CREATE INDEX idx_onboarding_applications_platform_queue
    ON onboarding_applications (status, created_at)
    WHERE status IN ('SUBMITTED', 'UNDER_REVIEW', 'INFORMATION_REQUIRED', 'RESUBMITTED');

ALTER TABLE onboarding_applications ENABLE ROW LEVEL SECURITY;
ALTER TABLE onboarding_applications FORCE ROW LEVEL SECURITY;

CREATE POLICY applicant_or_platform ON onboarding_applications
    USING (
        id = current_onboarding_application_id()
        OR platform_user_has_permission(current_platform_user_id(), 'platform.tenants.verify')
        OR platform_user_has_permission(current_platform_user_id(), 'platform.tenants.verification.manage')
    )
    WITH CHECK (
        id = current_onboarding_application_id()
        OR platform_user_has_permission(current_platform_user_id(), 'platform.tenants.verification.manage')
    );

CREATE POLICY onboarding_owner_all ON onboarding_applications
    FOR ALL TO pms_onboarding_owner USING (true) WITH CHECK (true);

-- The RLS policies above call platform_user_has_permission() in every OR branch that isn't
-- an exact id/tenant match. Postgres does not guarantee short-circuit evaluation of RLS USING
-- clauses, so a pms_app session whose earlier branches are false (the common, correct-to-deny
-- case) still needs EXECUTE here or the query errors instead of returning zero rows. The
-- function itself only checks a supplied platform_user_id, so this grant does not enable
-- anything a pms_app session doesn't already fail to satisfy (current_platform_user_id() is
-- NULL under an ordinary applicant/tenant session).
GRANT EXECUTE ON FUNCTION platform_user_has_permission(uuid, text) TO pms_app;

GRANT SELECT, UPDATE ON onboarding_applications TO pms_app, pms_platform;
GRANT SELECT, INSERT, UPDATE ON onboarding_applications TO pms_onboarding_owner;

-- ================================================================================
-- 3. The onboarding session — a bearer token, hashed at rest, bound to exactly one
--    application. Same shape as operational_sessions (V120): reachable only through a
--    SECURITY DEFINER lookup, never read/written directly by the app role.
-- ================================================================================

CREATE TABLE onboarding_sessions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id uuid NOT NULL REFERENCES onboarding_applications(id) DEFERRABLE,
    token_hash text NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_onboarding_session_hash CHECK (length(token_hash) = 64)
);

CREATE UNIQUE INDEX uq_onboarding_sessions_token_hash ON onboarding_sessions (token_hash);
CREATE INDEX idx_onboarding_sessions_application
    ON onboarding_sessions (application_id) WHERE revoked_at IS NULL;

COMMENT ON TABLE onboarding_sessions IS
    'The bearer is hashed at rest; Peak never stores the onb_ token, the same discipline as '
    'operational_sessions for the ops_ token.';

ALTER TABLE onboarding_sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE onboarding_sessions FORCE ROW LEVEL SECURITY;
CREATE POLICY onboarding_owner_all ON onboarding_sessions
    FOR ALL TO pms_onboarding_owner USING (true) WITH CHECK (true);

GRANT SELECT, INSERT, UPDATE ON onboarding_sessions TO pms_onboarding_owner;

-- ================================================================================
-- 4. SECURITY DEFINER functions — the only way into either table above before an
--    onboarding session exists, and the only way to mint one.
-- ================================================================================

CREATE FUNCTION create_onboarding_application(
    p_representative_full_name text,
    p_representative_phone text,
    p_business_name text,
    p_country_code text
) RETURNS uuid
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, public, pg_temp
    AS $$
DECLARE
    v_id uuid;
BEGIN
    INSERT INTO onboarding_applications (
        representative_full_name, representative_phone, business_name, country_code
    ) VALUES (
        p_representative_full_name, p_representative_phone, p_business_name,
        COALESCE(NULLIF(p_country_code, ''), 'TZ')
    )
    RETURNING id INTO v_id;
    RETURN v_id;
END;
$$;

CREATE FUNCTION mark_onboarding_phone_verified(p_application_id uuid) RETURNS void
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, public, pg_temp
    AS $$
BEGIN
    UPDATE onboarding_applications
    SET status = 'PHONE_VERIFIED', updated_at = now()
    WHERE id = p_application_id AND status = 'DRAFT';

    IF NOT FOUND THEN
        RAISE EXCEPTION
            'Onboarding application % is not in a state that can be phone-verified',
            p_application_id;
    END IF;
END;
$$;

CREATE FUNCTION issue_onboarding_session(
    p_application_id uuid,
    p_token_hash text,
    p_ttl_seconds double precision
) RETURNS TABLE (id uuid, expires_at timestamptz)
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, public, pg_temp
    AS $$
BEGIN
    RETURN QUERY
    INSERT INTO onboarding_sessions (application_id, token_hash, expires_at)
    VALUES (
        p_application_id, p_token_hash, now() + make_interval(secs => p_ttl_seconds)
    )
    RETURNING onboarding_sessions.id, onboarding_sessions.expires_at;
END;
$$;

CREATE FUNCTION lookup_onboarding_session(
    p_token_hash text
) RETURNS TABLE (id uuid, application_id uuid)
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, public, pg_temp
    AS $$
    SELECT s.id, s.application_id
    FROM onboarding_sessions s
    WHERE s.token_hash = p_token_hash
      AND s.revoked_at IS NULL
      AND s.expires_at > now();
$$;

CREATE FUNCTION revoke_onboarding_session(p_token_hash text) RETURNS void
    LANGUAGE sql
    SECURITY DEFINER
    SET search_path = pg_catalog, public, pg_temp
    AS $$
    UPDATE onboarding_sessions SET revoked_at = now()
    WHERE token_hash = p_token_hash AND revoked_at IS NULL;
$$;

DO $migration$
DECLARE
    fn text;
BEGIN
    FOREACH fn IN ARRAY ARRAY[
        'create_onboarding_application(text, text, text, text)',
        'mark_onboarding_phone_verified(uuid)',
        'issue_onboarding_session(uuid, text, double precision)',
        'lookup_onboarding_session(text)',
        'revoke_onboarding_session(text)'
    ]
    LOOP
        EXECUTE format('ALTER FUNCTION %s OWNER TO pms_onboarding_owner', fn);
        EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC', fn);
        EXECUTE format('GRANT EXECUTE ON FUNCTION %s TO pms_app', fn);
    END LOOP;
END;
$migration$;

-- ================================================================================
-- 5. Widen the existing KYB engine's subject: a case/document belongs to exactly one
--    of a tenant or an onboarding application, never both, never neither. Every
--    existing column, index, FK and grant on these two tables is untouched.
-- ================================================================================

ALTER TABLE tenant_verification_cases ALTER COLUMN tenant_id DROP NOT NULL;
ALTER TABLE tenant_verification_cases
    ADD COLUMN onboarding_application_id uuid REFERENCES onboarding_applications(id) DEFERRABLE;
ALTER TABLE tenant_verification_cases
    ADD CONSTRAINT chk_tenant_verification_cases_subject
    CHECK ((tenant_id IS NOT NULL) <> (onboarding_application_id IS NOT NULL));

ALTER TABLE tenant_verification_documents ALTER COLUMN tenant_id DROP NOT NULL;
ALTER TABLE tenant_verification_documents
    ADD COLUMN onboarding_application_id uuid REFERENCES onboarding_applications(id) DEFERRABLE;
ALTER TABLE tenant_verification_documents
    ADD CONSTRAINT chk_tenant_verification_documents_subject
    CHECK ((tenant_id IS NOT NULL) <> (onboarding_application_id IS NOT NULL));

-- The existing composite FK (tenant_id, verification_case_id) -> (tenant_id, id) uses
-- MATCH SIMPLE, so it is silently skipped whenever tenant_id is NULL — exactly the rows a
-- pre-tenant document now has. A plain FK on verification_case_id alone closes that gap
-- without touching the composite one, which still guards the tenant-scoped rows.
ALTER TABLE tenant_verification_documents
    ADD CONSTRAINT fk_tenant_verification_documents_case_id
    FOREIGN KEY (verification_case_id) REFERENCES tenant_verification_cases(id) DEFERRABLE;

DROP POLICY tenant_or_platform_verification ON tenant_verification_cases;
CREATE POLICY tenant_or_platform_verification ON tenant_verification_cases
    USING (
        (tenant_id IS NOT NULL AND tenant_id = current_tenant_id())
        OR (onboarding_application_id IS NOT NULL
            AND onboarding_application_id = current_onboarding_application_id())
        OR platform_user_has_permission(current_platform_user_id(), 'platform.tenants.verification.manage')
        OR platform_user_has_permission(current_platform_user_id(), 'platform.tenants.verify')
    )
    WITH CHECK (
        (tenant_id IS NOT NULL AND tenant_id = current_tenant_id())
        OR (onboarding_application_id IS NOT NULL
            AND onboarding_application_id = current_onboarding_application_id())
        OR platform_user_has_permission(current_platform_user_id(), 'platform.tenants.verification.manage')
    );

DROP POLICY tenant_or_platform_verification ON tenant_verification_documents;
CREATE POLICY tenant_or_platform_verification ON tenant_verification_documents
    USING (
        (tenant_id IS NOT NULL AND tenant_id = current_tenant_id())
        OR (onboarding_application_id IS NOT NULL
            AND onboarding_application_id = current_onboarding_application_id())
        OR platform_user_has_permission(current_platform_user_id(), 'platform.tenants.verification_documents.view')
        OR platform_user_has_permission(current_platform_user_id(), 'platform.tenants.verification.manage')
    )
    WITH CHECK (
        (tenant_id IS NOT NULL AND tenant_id = current_tenant_id())
        OR (onboarding_application_id IS NOT NULL
            AND onboarding_application_id = current_onboarding_application_id())
        OR platform_user_has_permission(current_platform_user_id(), 'platform.tenants.verification.manage')
    );
