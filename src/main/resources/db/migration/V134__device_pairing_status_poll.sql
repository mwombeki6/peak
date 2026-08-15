-- V134 — a waiting till can poll pairing status without learning which hotel
-- it belongs to. Pending rows still have no tenant, so the lookup is a
-- SECURITY DEFINER function owned by pms_device_pairing_owner — the same
-- shape as the rest of pairing (V120). The pairing code stays a six-digit
-- lookup, never a JWT, and this does not introduce a cache.

CREATE OR REPLACE FUNCTION lookup_device_pairing_status(
    p_id uuid
) RETURNS TABLE (
    status text,
    device_code text,
    expires_at timestamptz,
    attempts integer,
    terminal_name text,
    mode text
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
    SELECT r.status,
           r.device_code,
           r.expires_at,
           r.attempts,
           d.terminal_name,
           d.mode
    FROM device_pairing_requests r
    LEFT JOIN paired_devices d
      ON d.id = r.device_id
     AND r.status = 'approved'
    WHERE r.id = p_id
$$;

COMMENT ON FUNCTION lookup_device_pairing_status(uuid) IS
    'Unauthenticated pairing wait. Returns workspace name and mode only after approval; never tenant or property.';

DO $migration$
BEGIN
    EXECUTE 'ALTER FUNCTION lookup_device_pairing_status(uuid) OWNER TO pms_device_pairing_owner';
    EXECUTE 'REVOKE ALL ON FUNCTION lookup_device_pairing_status(uuid) FROM PUBLIC';
    EXECUTE 'GRANT EXECUTE ON FUNCTION lookup_device_pairing_status(uuid) TO pms_app';
END;
$migration$;

INSERT INTO module_access_matrix (
    module_id, screen_key, screen_label, http_method, api_pattern,
    permission_code, route_scope, guard_mode, access_scope,
    is_tanzania_v1, is_enabled_by_default, notes
) VALUES (
    'tenant_admin', 'devices.pairing.status', 'Device Pairing Status',
    'GET', '/api/devices/pairing-requests/:pairingRequestId',
    NULL, 'public', 'public_token', 'tenant',
    true, true,
    'Unauthenticated poll after the till shows the pairing code. Pending returns status only. '
    'Approved may return terminalName and mode (workspace), never tenant, property, or guest data. '
    'An expired code is replaced by posting the same public key again.'
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
    IF NOT EXISTS (
        SELECT 1 FROM pg_proc WHERE proname = 'lookup_device_pairing_status'
    ) THEN
        RAISE EXCEPTION 'V134 did not create lookup_device_pairing_status';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM pg_proc proc
        JOIN pg_roles owner ON owner.oid = proc.proowner
        WHERE proc.proname = 'lookup_device_pairing_status'
          AND (owner.rolsuper OR owner.rolbypassrls)
    ) THEN
        RAISE EXCEPTION
            'lookup_device_pairing_status must not be owned by a superuser or a BYPASSRLS role';
    END IF;

    SELECT string_agg(expected.pattern, ', ')
    INTO missing
    FROM (VALUES
        ('/api/devices/pairing-requests/:pairingRequestId')
    ) AS expected(pattern)
    WHERE NOT EXISTS (
        SELECT 1 FROM module_access_matrix m
        WHERE m.http_method = 'GET'
          AND m.api_pattern = expected.pattern
          AND m.permission_code IS NULL
          AND m.guard_mode = 'public_token'
          AND m.route_scope = 'public'
    );

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION
            'pairing status GET route is missing from module_access_matrix: %',
            missing;
    END IF;
END;
$migration$;
