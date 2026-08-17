-- V144 — a purpose-bound, short-lived numeric code, replacing nothing (the existing
-- `contact_channels` verification stays exactly as it is — a one-time 256-bit token proving
-- channel ownership, a different job). This is for the repeatable case: phone verification
-- before a tenant exists, tenant/account activation, account recovery, guest phone
-- verification — all sharing one attempt-budgeted, throttled, HMAC-verified mechanism instead
-- of four ad-hoc ones.
--
-- Public phone verification has no tenant yet, the same shape device_pairing_requests (V120)
-- was in: ordinary RLS keyed on current_tenant_id() would either refuse the insert (unbound
-- public request) or, written as USING (true), leak every live challenge to any bound tenant.
-- Pre-tenant rows are reachable only through SECURITY DEFINER functions owned by
-- pms_verification_owner — NOLOGIN NOBYPASSRLS, the same shape as pms_device_pairing_owner.

DO $migration$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pms_verification_owner') THEN
        CREATE ROLE pms_verification_owner
            NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
    ELSE
        ALTER ROLE pms_verification_owner
            NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
    END IF;
END;
$migration$;

CREATE TABLE verification_challenges (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid REFERENCES tenants(id) DEFERRABLE,
    purpose text NOT NULL,
    destination text NOT NULL,
    subject_ref text,
    code_hash text NOT NULL,
    attempts integer NOT NULL DEFAULT 0,
    max_attempts integer NOT NULL DEFAULT 5,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_verification_challenges_purpose CHECK (
        purpose IN (
            'phone_verification', 'tenant_activation', 'account_activation',
            'account_recovery', 'guest_phone_verification'
        )
    ),
    CONSTRAINT chk_verification_challenges_attempts CHECK (attempts >= 0 AND attempts <= max_attempts + 1)
);

COMMENT ON TABLE verification_challenges IS
    'A purpose-bound OTP challenge. code_hash is HMAC-SHA256 keyed by a secret held outside '
    'the database (see StaffCredentialService.pepper for the same pattern) — never a bare '
    'hash, which would be brute-forceable offline for a 6-digit code in the way it is not for '
    'the 256-bit contact_channels verification token.';

-- One live challenge per (purpose, destination). Resending invalidates the previous code by
-- overwriting code_hash/expires_at in place rather than inserting a new row, which is what
-- lets the attempt budget survive a resend: an attacker who spent 4 guesses does not get a
-- fresh budget merely because the legitimate destination asked for a new code.
CREATE UNIQUE INDEX uq_verification_challenges_live
    ON verification_challenges (purpose, destination)
    WHERE consumed_at IS NULL;

ALTER TABLE verification_challenges ENABLE ROW LEVEL SECURITY;
ALTER TABLE verification_challenges FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON verification_challenges
    USING (tenant_id = current_tenant_id());
CREATE POLICY verification_owner_all ON verification_challenges
    FOR ALL
    TO pms_verification_owner
    USING (true)
    WITH CHECK (true);

GRANT SELECT, INSERT, UPDATE ON verification_challenges TO pms_verification_owner;

CREATE FUNCTION request_verification_challenge(
    p_tenant_id uuid,
    p_purpose text,
    p_destination text,
    p_subject_ref text,
    p_code_hash text,
    p_ttl_seconds double precision,
    p_max_attempts integer
) RETURNS TABLE (id uuid, expires_at timestamptz)
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, public, pg_temp
    AS $$
BEGIN
    RETURN QUERY
    INSERT INTO verification_challenges (
        tenant_id, purpose, destination, subject_ref, code_hash, max_attempts, expires_at
    )
    VALUES (
        p_tenant_id, p_purpose, p_destination, p_subject_ref, p_code_hash, p_max_attempts,
        now() + make_interval(secs => p_ttl_seconds)
    )
    ON CONFLICT (purpose, destination) WHERE consumed_at IS NULL
    DO UPDATE SET
        code_hash = EXCLUDED.code_hash,
        subject_ref = EXCLUDED.subject_ref,
        expires_at = EXCLUDED.expires_at,
        created_at = now()
        -- attempts is deliberately not in this SET list: it survives the resend.
    RETURNING verification_challenges.id, verification_challenges.expires_at;
END;
$$;

CREATE FUNCTION confirm_verification_challenge(
    p_purpose text,
    p_destination text,
    p_code_hash text
) RETURNS TABLE (verified boolean, subject_ref text)
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, public, pg_temp
    AS $$
DECLARE
    v_row verification_challenges%ROWTYPE;
BEGIN
    SELECT * INTO v_row
    FROM verification_challenges
    WHERE purpose = p_purpose
      AND destination = p_destination
      AND consumed_at IS NULL
      AND expires_at > now()
    FOR UPDATE;

    IF NOT FOUND OR v_row.attempts >= v_row.max_attempts THEN
        verified := false;
        subject_ref := NULL;
        RETURN NEXT;
        RETURN;
    END IF;

    IF v_row.code_hash = p_code_hash THEN
        UPDATE verification_challenges SET consumed_at = now() WHERE id = v_row.id;
        verified := true;
        subject_ref := v_row.subject_ref;
    ELSE
        UPDATE verification_challenges SET attempts = attempts + 1 WHERE id = v_row.id;
        verified := false;
        subject_ref := NULL;
    END IF;
    RETURN NEXT;
END;
$$;

DO $migration$
DECLARE
    fn text;
BEGIN
    FOREACH fn IN ARRAY ARRAY[
        'request_verification_challenge(uuid, text, text, text, text, double precision, integer)',
        'confirm_verification_challenge(text, text, text)'
    ]
    LOOP
        EXECUTE format('ALTER FUNCTION %s OWNER TO pms_verification_owner', fn);
        EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC', fn);
        EXECUTE format('GRANT EXECUTE ON FUNCTION %s TO pms_app', fn);
    END LOOP;
END;
$migration$;
