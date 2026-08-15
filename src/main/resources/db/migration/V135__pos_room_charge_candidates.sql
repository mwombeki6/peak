-- V135 — a PIN till can find an in-house room to charge without becoming
-- STRONG and without reading GET /rooms or GET /reservations.
--
-- pos.order.settle is already operational (V114) and is the permission the
-- existing room-charge settle command requires. This publishes a purpose-built
-- candidate search on that same permission. It does not reclassify property.view
-- or reservations.view.

INSERT INTO module_access_matrix (
    module_id, screen_key, screen_label, http_method, api_pattern,
    permission_code, route_scope, guard_mode, access_scope,
    is_tanzania_v1, is_enabled_by_default, notes
) VALUES (
    'pos', 'pos.room_charge.candidates', 'POS Room Charge Candidates',
    'GET', '/api/properties/:propertyId/pos/room-charge-candidates',
    'pos.order.settle', 'property', 'staff_permission', 'property',
    true, true,
    'In-house stay search for post-to-room. Returns stay, room, and a display name only. '
    'Operational session class via pos.order.settle — the same permission that posts the charge.'
)
ON CONFLICT (
    module_id,
    screen_key,
    http_method,
    api_pattern,
    permission_code
) DO UPDATE SET
    screen_label = EXCLUDED.screen_label,
    route_scope = EXCLUDED.route_scope,
    guard_mode = EXCLUDED.guard_mode,
    access_scope = EXCLUDED.access_scope,
    is_tanzania_v1 = EXCLUDED.is_tanzania_v1,
    is_enabled_by_default = EXCLUDED.is_enabled_by_default,
    notes = EXCLUDED.notes,
    updated_at = now();

DO $migration$
DECLARE
    missing text;
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM permission_catalog
        WHERE code = 'pos.order.settle'
          AND minimum_session_class = 'operational'
    ) THEN
        RAISE EXCEPTION
            'pos.order.settle must remain operational so a PIN session can search for a room charge';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM module_access_matrix m
        JOIN permission_catalog pc ON pc.code = m.permission_code
        WHERE m.http_method = 'GET'
          AND m.api_pattern = '/api/properties/:propertyId/rooms'
          AND pc.minimum_session_class = 'operational'
    ) THEN
        RAISE EXCEPTION
            'GET /rooms must stay strong; waiters search via pos/room-charge-candidates';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM module_access_matrix m
        JOIN permission_catalog pc ON pc.code = m.permission_code
        WHERE m.http_method = 'GET'
          AND m.api_pattern = '/api/properties/:propertyId/reservations'
          AND pc.minimum_session_class = 'operational'
    ) THEN
        RAISE EXCEPTION
            'GET /reservations must stay strong; waiters search via pos/room-charge-candidates';
    END IF;

    SELECT string_agg(expected.pattern, ', ')
    INTO missing
    FROM (VALUES
        ('/api/properties/:propertyId/pos/room-charge-candidates')
    ) AS expected(pattern)
    WHERE NOT EXISTS (
        SELECT 1 FROM module_access_matrix m
        WHERE m.http_method = 'GET'
          AND m.api_pattern = expected.pattern
          AND m.permission_code = 'pos.order.settle'
          AND m.guard_mode = 'staff_permission'
          AND m.route_scope = 'property'
    );

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION
            'POS room-charge candidate GET route is missing from module_access_matrix: %',
            missing;
    END IF;
END;
$migration$;
