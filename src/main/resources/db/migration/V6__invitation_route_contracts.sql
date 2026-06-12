-- ================================================================================
-- Invitation HTTP route contracts
-- ================================================================================

ALTER TABLE module_access_matrix
    DROP CONSTRAINT chk_module_access_matrix_guard_mode;

ALTER TABLE module_access_matrix
    ADD CONSTRAINT chk_module_access_matrix_guard_mode CHECK (
        (guard_mode)::text = ANY (
            (ARRAY['staff_permission', 'module_only', 'platform_permission', 'public_token'])::text[]
        )
    );

ALTER TABLE module_access_matrix
    DROP CONSTRAINT chk_module_access_matrix_route_scope;

ALTER TABLE module_access_matrix
    ADD CONSTRAINT chk_module_access_matrix_route_scope CHECK (
        (route_scope)::text = ANY (
            (ARRAY['tenant', 'property', 'public_property', 'public', 'platform'])::text[]
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
        'tenant.users.invitations.create',
        'Invite Tenant User',
        'POST',
        '/api/tenants/:tenantId/users/invitations',
        'tenant.users.manage',
        'tenant',
        'staff_permission',
        'tenant',
        true,
        true,
        'Invite tenant staff and assign tenant role through idempotent audit/outbox workflow'
    ),
    (
        'tenant_admin',
        'tenant.users.invitations.accept',
        'Accept Tenant User Invitation',
        'POST',
        '/api/invitations/accept',
        NULL,
        'public',
        'public_token',
        'tenant',
        true,
        true,
        'Public invitation acceptance is allowed only when the submitted token is valid'
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
