-- V120 — registered devices, pairing, and device-bound operational sessions.
--
-- A terminal is not a POS row. usermanagement owns the identity of a device;
-- pos_terminals remains a POS concern and is never written from here. The
-- leftover that inserted into pos_terminals is what DatabaseOwnershipArchitectureTests
-- fails on, and this migration is the replacement schema that test is waiting for.
--
-- Pending pairing requests have no tenant. Ordinary RLS keyed on
-- current_tenant_id() would either refuse the insert (unbound public request)
-- or, if written with USING (true), leak every waiting code to any bound
-- tenant. So pending rows are reachable only through SECURITY DEFINER
-- functions owned by pms_device_pairing_owner, which is NOLOGIN NOSUPERUSER
-- NOBYPASSRLS — the same shape as resolve_platform_billing_scope (V93).
--
-- The six-digit pairing code is a lookup, not a credential. What the device
-- holds after approval is an opaque device code and an Ed25519 private key.
-- A PIN typed on that device mints an operational session (ops_… bearer),
-- never a Keycloak token, and never something JwtDecoder should see.

DO $migration$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pms_device_pairing_owner') THEN
        CREATE ROLE pms_device_pairing_owner
            NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
    ELSE
        ALTER ROLE pms_device_pairing_owner
            NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
    END IF;
END;
$migration$;

CREATE TABLE paired_devices (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES tenants(id) DEFERRABLE,
    property_id uuid NOT NULL REFERENCES properties(id) DEFERRABLE,
    outlet_id uuid REFERENCES outlets(id) DEFERRABLE,
    device_code text NOT NULL,
    public_key text NOT NULL,
    key_fingerprint text NOT NULL,
    key_version integer NOT NULL DEFAULT 1,
    terminal_name text NOT NULL,
    mode text NOT NULL,
    status text NOT NULL DEFAULT 'active',
    paired_at timestamptz NOT NULL DEFAULT now(),
    paired_by uuid NOT NULL REFERENCES users(id) DEFERRABLE,
    revoked_at timestamptz,
    revoked_by uuid REFERENCES users(id) DEFERRABLE,
    CONSTRAINT chk_paired_devices_mode
        CHECK (mode IN ('POS', 'KITCHEN_DISPLAY', 'BAR_DISPLAY', 'CASHIER')),
    CONSTRAINT chk_paired_devices_status
        CHECK (status IN ('active', 'revoked')),
    CONSTRAINT chk_paired_devices_name
        CHECK (length(btrim(terminal_name)) > 0),
    CONSTRAINT chk_paired_devices_revoked
        CHECK (
            (status = 'active' AND revoked_at IS NULL AND revoked_by IS NULL)
            OR (status = 'revoked' AND revoked_at IS NOT NULL)
        )
);

CREATE UNIQUE INDEX uq_paired_devices_device_code ON paired_devices (device_code);
CREATE UNIQUE INDEX uq_paired_devices_live_public_key
    ON paired_devices (public_key) WHERE status = 'active';
CREATE INDEX idx_paired_devices_tenant_property
    ON paired_devices (tenant_id, property_id);

COMMENT ON TABLE paired_devices IS
    'A terminal that belongs to a hotel. Owned by usermanagement; not a POS terminal row.';

CREATE TABLE device_pairing_requests (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid REFERENCES tenants(id) DEFERRABLE,
    device_code text NOT NULL,
    public_key text NOT NULL,
    key_fingerprint text NOT NULL,
    pairing_code text NOT NULL,
    status text NOT NULL DEFAULT 'pending',
    attempts integer NOT NULL DEFAULT 0,
    expires_at timestamptz NOT NULL,
    approved_at timestamptz,
    approved_by uuid REFERENCES users(id) DEFERRABLE,
    device_id uuid REFERENCES paired_devices(id) DEFERRABLE,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_device_pairing_status
        CHECK (status IN ('pending', 'approved', 'expired', 'abandoned')),
    CONSTRAINT chk_device_pairing_attempts CHECK (attempts >= 0),
    CONSTRAINT chk_device_pairing_pending_tenant
        CHECK (
            (status = 'pending' AND tenant_id IS NULL AND device_id IS NULL)
            OR (status <> 'pending')
        )
);

CREATE UNIQUE INDEX uq_device_pairing_device_code ON device_pairing_requests (device_code);
CREATE UNIQUE INDEX uq_device_pairing_live_code
    ON device_pairing_requests (pairing_code) WHERE status = 'pending';

COMMENT ON TABLE device_pairing_requests IS
    'A terminal waiting for a manager to name it. Pending rows have no tenant, so they are '
    'invisible under ordinary tenant RLS and reachable only through pairing definer functions.';

CREATE TABLE device_key_history (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES tenants(id) DEFERRABLE,
    device_id uuid NOT NULL REFERENCES paired_devices(id) DEFERRABLE,
    public_key text NOT NULL,
    key_fingerprint text NOT NULL,
    key_version integer NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_device_key_history_device
    ON device_key_history (tenant_id, device_id, key_version);

CREATE TABLE device_login_challenges (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES tenants(id) DEFERRABLE,
    device_id uuid NOT NULL REFERENCES paired_devices(id) DEFERRABLE,
    nonce bytea NOT NULL,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_device_login_challenge_nonce CHECK (octet_length(nonce) = 32)
);

CREATE INDEX idx_device_login_challenges_device
    ON device_login_challenges (device_id, created_at DESC);

CREATE TABLE operational_sessions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES tenants(id) DEFERRABLE,
    user_id uuid NOT NULL REFERENCES users(id) DEFERRABLE,
    device_id uuid NOT NULL REFERENCES paired_devices(id) DEFERRABLE,
    property_id uuid NOT NULL REFERENCES properties(id) DEFERRABLE,
    token_hash text NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_operational_session_hash CHECK (length(token_hash) = 64)
);

CREATE UNIQUE INDEX uq_operational_sessions_token_hash ON operational_sessions (token_hash);
CREATE INDEX idx_operational_sessions_device
    ON operational_sessions (device_id) WHERE revoked_at IS NULL;

COMMENT ON TABLE operational_sessions IS
    'A device-bound PIN session. The bearer is hashed at rest; Peak never stores the ops_ token.';

ALTER TABLE paired_devices ENABLE ROW LEVEL SECURITY;
ALTER TABLE paired_devices FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON paired_devices
    USING (tenant_id = current_tenant_id());

ALTER TABLE device_pairing_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE device_pairing_requests FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON device_pairing_requests
    USING (tenant_id = current_tenant_id());

ALTER TABLE device_key_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE device_key_history FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON device_key_history
    USING (tenant_id = current_tenant_id());

ALTER TABLE device_login_challenges ENABLE ROW LEVEL SECURITY;
ALTER TABLE device_login_challenges FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON device_login_challenges
    USING (tenant_id = current_tenant_id());

ALTER TABLE operational_sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE operational_sessions FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON operational_sessions
    USING (tenant_id = current_tenant_id());

-- The owner is NOBYPASSRLS, so without these it would see nothing on an unbound
-- session. Read/write is scoped to a NOLOGIN role nothing else runs as.
CREATE POLICY device_pairing_owner_all ON device_pairing_requests
    FOR ALL
    TO pms_device_pairing_owner
    USING (true)
    WITH CHECK (true);

CREATE POLICY device_pairing_owner_reads_devices ON paired_devices
    FOR SELECT
    TO pms_device_pairing_owner
    USING (true);

CREATE POLICY device_pairing_owner_reads_sessions ON operational_sessions
    FOR SELECT
    TO pms_device_pairing_owner
    USING (true);

GRANT SELECT ON paired_devices TO pms_device_pairing_owner;
GRANT SELECT, INSERT, UPDATE ON device_pairing_requests TO pms_device_pairing_owner;
GRANT SELECT ON operational_sessions TO pms_device_pairing_owner;

GRANT SELECT, INSERT, UPDATE ON paired_devices TO pms_app;
GRANT SELECT, INSERT, UPDATE ON device_pairing_requests TO pms_app;
GRANT SELECT, INSERT, UPDATE ON device_key_history TO pms_app;
GRANT SELECT, INSERT, UPDATE ON device_login_challenges TO pms_app;
GRANT SELECT, INSERT, UPDATE ON operational_sessions TO pms_app;

REVOKE DELETE ON paired_devices FROM pms_app, pms_worker;
REVOKE DELETE ON device_pairing_requests FROM pms_app, pms_worker;
REVOKE DELETE ON device_key_history FROM pms_app, pms_worker;
REVOKE DELETE ON device_login_challenges FROM pms_app, pms_worker;
REVOKE DELETE ON operational_sessions FROM pms_app, pms_worker;

CREATE OR REPLACE FUNCTION abandon_colliding_device_pairings(
    p_pairing_code text,
    p_public_key text
) RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
DECLARE
    v_count integer;
BEGIN
    UPDATE device_pairing_requests
    SET status = 'abandoned'
    WHERE status = 'pending'
      AND (pairing_code = p_pairing_code OR public_key = p_public_key);
    GET DIAGNOSTICS v_count = ROW_COUNT;
    RETURN v_count;
END;
$$;

CREATE OR REPLACE FUNCTION insert_pending_device_pairing(
    p_device_code text,
    p_public_key text,
    p_key_fingerprint text,
    p_pairing_code text,
    p_expires_at timestamptz
) RETURNS uuid
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
DECLARE
    v_id uuid;
BEGIN
    INSERT INTO device_pairing_requests (
        device_code, public_key, key_fingerprint, pairing_code, expires_at
    ) VALUES (
        p_device_code, p_public_key, p_key_fingerprint, p_pairing_code, p_expires_at
    )
    RETURNING id INTO v_id;
    RETURN v_id;
END;
$$;

CREATE OR REPLACE FUNCTION lock_pending_device_pairing(
    p_pairing_code text
) RETURNS TABLE (
    id uuid,
    device_code text,
    public_key text,
    key_fingerprint text,
    attempts integer,
    expires_at timestamptz
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
BEGIN
    RETURN QUERY
    SELECT r.id, r.device_code, r.public_key, r.key_fingerprint, r.attempts, r.expires_at
    FROM device_pairing_requests r
    WHERE r.pairing_code = p_pairing_code
      AND r.status = 'pending'
    FOR UPDATE;
END;
$$;

CREATE OR REPLACE FUNCTION record_device_pairing_miss() RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
DECLARE
    v_count integer;
BEGIN
    UPDATE device_pairing_requests
    SET attempts = attempts + 1
    WHERE status = 'pending';
    GET DIAGNOSTICS v_count = ROW_COUNT;
    RETURN v_count;
END;
$$;

CREATE OR REPLACE FUNCTION mark_device_pairing_expired(
    p_id uuid
) RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
DECLARE
    v_count integer;
BEGIN
    UPDATE device_pairing_requests
    SET status = 'expired'
    WHERE id = p_id
      AND status = 'pending';
    GET DIAGNOSTICS v_count = ROW_COUNT;
    RETURN v_count;
END;
$$;

CREATE OR REPLACE FUNCTION mark_device_pairing_approved(
    p_id uuid,
    p_tenant_id uuid,
    p_approved_by uuid,
    p_device_id uuid
) RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
DECLARE
    v_count integer;
BEGIN
    UPDATE device_pairing_requests
    SET status = 'approved',
        tenant_id = p_tenant_id,
        approved_at = now(),
        approved_by = p_approved_by,
        device_id = p_device_id
    WHERE id = p_id
      AND status = 'pending';
    GET DIAGNOSTICS v_count = ROW_COUNT;
    RETURN v_count;
END;
$$;

CREATE OR REPLACE FUNCTION lookup_active_paired_device(
    p_device_code text
) RETURNS TABLE (
    id uuid,
    tenant_id uuid,
    property_id uuid,
    outlet_id uuid,
    public_key text,
    status text
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
    SELECT d.id, d.tenant_id, d.property_id, d.outlet_id, d.public_key, d.status
    FROM paired_devices d
    WHERE d.device_code = p_device_code;
$$;

CREATE OR REPLACE FUNCTION lookup_operational_session(
    p_token_hash text
) RETURNS TABLE (
    id uuid,
    tenant_id uuid,
    user_id uuid,
    device_id uuid,
    property_id uuid
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
    SELECT s.id, s.tenant_id, s.user_id, s.device_id, s.property_id
    FROM operational_sessions s
    JOIN paired_devices d ON d.id = s.device_id
    WHERE s.token_hash = p_token_hash
      AND s.revoked_at IS NULL
      AND s.expires_at > now()
      AND d.status = 'active';
$$;

DO $migration$
DECLARE
    fn text;
BEGIN
    FOREACH fn IN ARRAY ARRAY[
        'abandon_colliding_device_pairings(text, text)',
        'insert_pending_device_pairing(text, text, text, text, timestamptz)',
        'lock_pending_device_pairing(text)',
        'record_device_pairing_miss()',
        'mark_device_pairing_expired(uuid)',
        'mark_device_pairing_approved(uuid, uuid, uuid, uuid)',
        'lookup_active_paired_device(text)',
        'lookup_operational_session(text)'
    ]
    LOOP
        EXECUTE format('ALTER FUNCTION %s OWNER TO pms_device_pairing_owner', fn);
        EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC', fn);
        EXECUTE format('GRANT EXECUTE ON FUNCTION %s TO pms_app', fn);
    END LOOP;
END;
$migration$;

INSERT INTO permission_catalog (
    code, namespace, access_scope, description,
    is_platform_permission, is_tenant_permission
) VALUES (
    'admin.devices.manage', 'admin', 'tenant',
    'Pair and revoke registered devices',
    false, true
)
ON CONFLICT (code) DO UPDATE SET
    description = EXCLUDED.description,
    namespace = EXCLUDED.namespace,
    access_scope = EXCLUDED.access_scope,
    is_platform_permission = EXCLUDED.is_platform_permission,
    is_tenant_permission = EXCLUDED.is_tenant_permission,
    updated_at = now();

INSERT INTO module_access_matrix (
    module_id, screen_key, screen_label, http_method, api_pattern,
    permission_code, route_scope, guard_mode, access_scope,
    is_tanzania_v1, is_enabled_by_default, notes
) VALUES
    (
        'tenant_admin', 'devices.pairing.approve', 'Approve Device Pairing',
        'POST', '/api/tenants/:tenantId/devices/pairing-approvals',
        'admin.devices.manage', 'tenant', 'staff_permission', 'tenant',
        true, true,
        'A strongly authenticated manager binds a waiting terminal to a property of their tenant'
    ),
    (
        'tenant_admin', 'devices.revoke', 'Revoke Registered Device',
        'POST', '/api/tenants/:tenantId/devices/:deviceId/revoke',
        'admin.devices.manage', 'tenant', 'staff_permission', 'tenant',
        true, true,
        'Revoking a device ends its operational sessions. Strong session class required.'
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

-- Public-token rows have a NULL permission_code, so they hit the partial unique
-- index rather than the permission_code constraint used above.
INSERT INTO module_access_matrix (
    module_id, screen_key, screen_label, http_method, api_pattern,
    permission_code, route_scope, guard_mode, access_scope,
    is_tanzania_v1, is_enabled_by_default, notes
) VALUES
    (
        'tenant_admin', 'devices.pairing.request', 'Request Device Pairing',
        'POST', '/api/devices/pairing-requests',
        NULL, 'public', 'public_token', 'tenant',
        true, true,
        'Unauthenticated: a terminal presents a public key and waits. Pending rows are not tenant-readable.'
    ),
    (
        'tenant_admin', 'devices.login.challenge', 'Device Login Challenge',
        'POST', '/api/devices/challenges',
        NULL, 'public', 'public_token', 'tenant',
        true, true,
        'Issues a one-time nonce for the device to sign with its pairing private key'
    ),
    (
        'tenant_admin', 'staff.sessions.create', 'Staff Operational Session',
        'POST', '/api/staff/sessions',
        NULL, 'public', 'public_token', 'tenant',
        true, true,
        'PIN login on a registered device mints an ops_ bearer. Never a Keycloak token.'
    )
ON CONFLICT (module_id, screen_key, http_method, api_pattern)
WHERE permission_code IS NULL
DO UPDATE SET
    screen_label = EXCLUDED.screen_label,
    route_scope = EXCLUDED.route_scope,
    guard_mode = EXCLUDED.guard_mode,
    access_scope = EXCLUDED.access_scope,
    notes = EXCLUDED.notes,
    updated_at = now();

DO $migration$
DECLARE
    missing text;
BEGIN
    SELECT string_agg(expected.name, ', ')
    INTO missing
    FROM (VALUES
        ('abandon_colliding_device_pairings'),
        ('insert_pending_device_pairing'),
        ('lock_pending_device_pairing'),
        ('record_device_pairing_miss'),
        ('mark_device_pairing_expired'),
        ('mark_device_pairing_approved'),
        ('lookup_active_paired_device'),
        ('lookup_operational_session')
    ) AS expected(name)
    WHERE NOT EXISTS (
        SELECT 1 FROM pg_proc WHERE proname = expected.name
    );

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'V120 did not create: %', missing;
    END IF;

    IF EXISTS (
        SELECT 1
        FROM pg_proc proc
        JOIN pg_roles owner ON owner.oid = proc.proowner
        WHERE proc.proname IN (
            'abandon_colliding_device_pairings',
            'insert_pending_device_pairing',
            'lock_pending_device_pairing',
            'record_device_pairing_miss',
            'mark_device_pairing_expired',
            'mark_device_pairing_approved',
            'lookup_active_paired_device',
            'lookup_operational_session'
        )
          AND (owner.rolsuper OR owner.rolbypassrls)
    ) THEN
        RAISE EXCEPTION
            'device pairing definer functions must not be owned by a superuser or a BYPASSRLS role';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_class
        WHERE relname = 'paired_devices' AND relrowsecurity AND relforcerowsecurity
    ) THEN
        RAISE EXCEPTION 'paired_devices must have row level security enabled and forced';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_class
        WHERE relname = 'device_pairing_requests' AND relrowsecurity AND relforcerowsecurity
    ) THEN
        RAISE EXCEPTION 'device_pairing_requests must have row level security enabled and forced';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM permission_catalog
        WHERE code = 'admin.devices.manage'
          AND minimum_session_class = 'strong'
    ) THEN
        RAISE EXCEPTION 'admin.devices.manage must remain strong so a PIN session cannot pair devices';
    END IF;
END;
$migration$;
