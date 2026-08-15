-- V128 — a till that has paired and signed in still cannot load the menu it
-- is supposed to sell. Write routes exist; the matching reads do not.
--
-- pos.view is already operational (V114). This only publishes GET catalog
-- routes so a PIN session can load categories and items without reaching
-- pos.configure, which stays strong.

INSERT INTO module_access_matrix (
    module_id, screen_key, screen_label, http_method, api_pattern,
    permission_code, route_scope, guard_mode, access_scope,
    is_tanzania_v1, is_enabled_by_default, notes
) VALUES
    (
        'pos', 'pos.config.menu_items.view', 'View POS Menu Items',
        'GET', '/api/properties/:propertyId/pos-config/menu-items',
        'pos.view', 'property', 'staff_permission', 'property',
        true, true,
        'Outlet-scoped menu catalog for a till. Operational session class via pos.view.'
    ),
    (
        'pos', 'pos.config.menu_categories.view', 'View POS Menu Categories',
        'GET', '/api/properties/:propertyId/pos-config/menu-categories',
        'pos.view', 'property', 'staff_permission', 'property',
        true, true,
        'Outlet-scoped menu categories for a till. Operational session class via pos.view.'
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
        WHERE code = 'pos.view'
          AND minimum_session_class = 'operational'
    ) THEN
        RAISE EXCEPTION
            'pos.view must remain operational so a PIN session can load the till catalog';
    END IF;

    SELECT string_agg(expected.pattern, ', ')
    INTO missing
    FROM (VALUES
        ('/api/properties/:propertyId/pos-config/menu-items'),
        ('/api/properties/:propertyId/pos-config/menu-categories')
    ) AS expected(pattern)
    WHERE NOT EXISTS (
        SELECT 1 FROM module_access_matrix m
        WHERE m.http_method = 'GET'
          AND m.api_pattern = expected.pattern
          AND m.permission_code = 'pos.view'
          AND m.guard_mode = 'staff_permission'
          AND m.route_scope = 'property'
    );

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION
            'POS menu catalog GET routes are missing from module_access_matrix: %',
            missing;
    END IF;
END;
$migration$;
