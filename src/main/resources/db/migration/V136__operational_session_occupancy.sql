-- V136 — one occupant per till, and a PIN session can lock itself.
--
-- A second PIN login on the same device must displace the first. The unique
-- live-session index is the last line of defence; login also revokes siblings
-- in the same transaction. DELETE /staff/sessions/current is Switch Staff /
-- Lock: an ops_ bearer, not a STRONG permission.

CREATE UNIQUE INDEX IF NOT EXISTS uq_operational_sessions_one_live_occupant
    ON operational_sessions (device_id)
    WHERE revoked_at IS NULL;

INSERT INTO module_access_matrix (
    module_id, screen_key, screen_label, http_method, api_pattern,
    permission_code, route_scope, guard_mode, access_scope,
    is_tanzania_v1, is_enabled_by_default, notes
) VALUES (
    'tenant_admin', 'staff.sessions.current.delete', 'Lock Operational Session',
    'DELETE', '/api/staff/sessions/current',
    NULL, 'tenant', 'authenticated_identity', 'tenant',
    true, true,
    'Revokes the calling ops_ session so the till can lock or switch staff without closing the drawer.'
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
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_indexes
        WHERE schemaname = 'public'
          AND indexname = 'uq_operational_sessions_one_live_occupant'
    ) THEN
        RAISE EXCEPTION
            'operational_sessions must allow only one live session per device';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM module_access_matrix
        WHERE http_method = 'DELETE'
          AND api_pattern = '/api/staff/sessions/current'
          AND guard_mode = 'authenticated_identity'
          AND route_scope = 'tenant'
          AND permission_code IS NULL
    ) THEN
        RAISE EXCEPTION
            'DELETE /staff/sessions/current must be an authenticated-identity lock route';
    END IF;
END;
$migration$;
