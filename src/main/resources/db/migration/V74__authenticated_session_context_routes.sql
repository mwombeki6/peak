-- Authenticated frontend bootstrap routes expose only the caller's
-- database-authoritative identity and access context. They require a resolved
-- active identity but no pre-existing business permission.

ALTER TABLE module_access_matrix
    DROP CONSTRAINT chk_module_access_matrix_guard_mode;

ALTER TABLE module_access_matrix
    ADD CONSTRAINT chk_module_access_matrix_guard_mode CHECK (
        (guard_mode)::text = ANY (
            (
                ARRAY[
                    'staff_permission',
                    'module_only',
                    'platform_permission',
                    'public_token',
                    'authenticated_identity'
                ]
            )::text[]
        )
    );

INSERT INTO module_access_matrix (
    module_id,
    screen_key,
    screen_label,
    http_method,
    api_pattern,
    permission_code,
    route_scope,
    guard_mode,
    access_scope,
    is_tanzania_v1,
    is_enabled_by_default,
    notes
) VALUES
    (
        'tenant_admin',
        'session.hospitality.current',
        'Current Hospitality Session',
        'GET',
        '/api/session',
        NULL,
        'tenant',
        'authenticated_identity',
        'tenant',
        true,
        true,
        'Bootstrap the current active tenant user, assigned properties, effective role permissions, and enabled modules'
    ),
    (
        'platform_admin',
        'session.platform.current',
        'Current Platform Session',
        'GET',
        '/api/platform/session',
        NULL,
        'platform',
        'authenticated_identity',
        'platform',
        true,
        true,
        'Bootstrap the current active platform operator and effective platform permissions'
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
