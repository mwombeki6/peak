-- V140 — the onboarding wizard can write a property's configuration but cannot
-- read it back. Outlets, base rates and communication templates all have POST
-- routes and no matching GET, so the wizard cannot show a hotel what it has
-- already set up, and cannot tell a finished step from an unstarted one.
--
-- Every route below reuses a view permission that already exists, so no new
-- permission is minted and no role gains reach it did not have. Reads are
-- separated from the manage permission on purpose: a receptionist who may see
-- which outlets exist must not thereby be able to create one.
--
-- property.view is 'both'-scoped and communications.view is tenant-scoped, so
-- the base rate and template rows follow their sibling read contracts exactly
-- rather than inventing a scope for this wizard.

INSERT INTO module_access_matrix (
    module_id, screen_key, screen_label, http_method, api_pattern,
    permission_code, route_scope, guard_mode, access_scope,
    is_tanzania_v1, is_enabled_by_default, notes
) VALUES
    (
        'pos', 'pos.config.outlets.view', 'View POS Outlets',
        'GET', '/api/properties/:propertyId/pos-config/outlets',
        'pos.view', 'property', 'staff_permission', 'property',
        true, true,
        'Property outlet list for onboarding and till setup. Read only; creating an outlet stays on pos.configure.'
    ),
    (
        'property', 'property.rates.list', 'Base Rates',
        'GET', '/api/properties/:propertyId/rates',
        'property.view', 'property', 'staff_permission', 'property',
        true, true,
        'Room type base rates for a property. Pairs with the existing POST rates write on property.manage.'
    ),
    (
        'communications', 'communications.templates.list', 'Communication Templates',
        'GET', '/api/communication/templates',
        'communications.view', 'tenant', 'staff_permission', 'tenant',
        true, true,
        'Tenant communication templates. Pairs with the existing POST templates write on communications.manage.'
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
    conflicting text;
BEGIN
    -- The route guard resolves a contract by (method, pattern). A second enabled
    -- row for the same pair with a different permission makes authorization
    -- depend on row order, and RouteMatrixStartupValidator refuses to boot.
    SELECT string_agg(
               format('%s %s', m.http_method, m.api_pattern), ', '
               ORDER BY m.api_pattern
           )
    INTO conflicting
    FROM (
        SELECT http_method, api_pattern
        FROM module_access_matrix
        WHERE is_enabled_by_default = true
          AND (http_method, api_pattern) IN (
              ('GET', '/api/properties/:propertyId/pos-config/outlets'),
              ('GET', '/api/properties/:propertyId/rates'),
              ('GET', '/api/communication/templates')
          )
        GROUP BY http_method, api_pattern
        HAVING count(DISTINCT (module_id, permission_code, route_scope, guard_mode)) > 1
    ) AS m;

    IF conflicting IS NOT NULL THEN
        RAISE EXCEPTION
            'Ambiguous access contracts for the onboarding read routes: %',
            conflicting;
    END IF;

    SELECT string_agg(expected.pattern, ', ')
    INTO missing
    FROM (VALUES
        ('/api/properties/:propertyId/pos-config/outlets', 'pos.view', 'property'),
        ('/api/properties/:propertyId/rates', 'property.view', 'property'),
        ('/api/communication/templates', 'communications.view', 'tenant')
    ) AS expected(pattern, permission, scope)
    WHERE NOT EXISTS (
        SELECT 1 FROM module_access_matrix m
        WHERE m.http_method = 'GET'
          AND m.api_pattern = expected.pattern
          AND m.permission_code = expected.permission
          AND m.route_scope = expected.scope
          AND m.guard_mode = 'staff_permission'
          AND m.is_enabled_by_default = true
    );

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION
            'Onboarding configuration GET routes are missing from module_access_matrix: %',
            missing;
    END IF;

    -- Reusing a view permission is only safe while that permission still exists
    -- in the catalog as a tenant permission; otherwise these rows would name a
    -- code no role can ever hold and the routes would be unreachable.
    SELECT string_agg(expected.permission, ', ')
    INTO missing
    FROM (VALUES
        ('pos.view'),
        ('property.view'),
        ('communications.view')
    ) AS expected(permission)
    WHERE NOT EXISTS (
        SELECT 1 FROM permission_catalog pc
        WHERE pc.code = expected.permission
          AND pc.is_tenant_permission = true
    );

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION
            'Onboarding read routes name permissions absent from permission_catalog: %',
            missing;
    END IF;
END;
$migration$;
