-- Realtime event replay (REST backfill) route registration.
--
-- Clients that connect after events were committed fetch them from the journal
-- through this route, then resume live on /ws-connect. The route is guarded by
-- the same entitlement as stream subscriptions: the realtime.stream permission
-- plus the realtime module enabled for the tenant and property.
INSERT INTO module_access_matrix (
    module_id, screen_key, screen_label, http_method, api_pattern,
    permission_code, route_scope, guard_mode, access_scope,
    is_tanzania_v1, is_enabled_by_default, notes
)
VALUES (
    'realtime', 'realtime.event.replay', 'Realtime Event Replay', 'GET',
    '/api/properties/:propertyId/realtime/events*',
    'realtime.stream', 'property', 'staff_permission', 'property',
    false, true, 'Replay committed realtime events after a cursor for client backfill'
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