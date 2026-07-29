-- =============================================================================
-- Route contracts for dual-controlled root appointment
--
-- Without these the change-request endpoints would be unregistered, and the
-- route guard denies unregistered API routes by default. The gate added in the
-- service would then be unreachable in both directions: appointment blocked
-- because no request can exist, and requests blocked because the route is not
-- in the matrix.
--
-- Requesting a change requires administrator management authority. Deciding
-- requires security management, matching the identity_mutation seats, so the
-- person who opens a request cannot also satisfy a seat on it through the
-- permission they already hold.
-- =============================================================================

INSERT INTO public.module_access_matrix (
    module_id, screen_key, screen_label, http_method, api_pattern,
    permission_code, route_scope, guard_mode, access_scope,
    is_tanzania_v1, is_enabled_by_default, operation_code, notes
) VALUES
    ('platform_admin', 'platform.administrators.change.request',
     'Request Emergency Administrator Change',
     'POST', '/api/platform/administrators/change-requests',
     'platform.administrators.manage',
     'platform', 'platform_permission', 'platform', true, true, NULL,
     'Open a dual-controlled request to appoint or revoke emergency authority.'),
    ('platform_admin', 'platform.administrators.change.decide',
     'Decide Emergency Administrator Change',
     'POST', '/api/platform/administrators/change-requests/:requestId/decisions',
     'platform.security.manage',
     'platform', 'platform_permission', 'platform', true, true, NULL,
     'Record one seat decision. Requester and target are both excluded.')
ON CONFLICT (
    module_id, screen_key, http_method, api_pattern, permission_code
) DO UPDATE SET
    screen_label = EXCLUDED.screen_label,
    route_scope = EXCLUDED.route_scope,
    guard_mode = EXCLUDED.guard_mode,
    access_scope = EXCLUDED.access_scope,
    is_enabled_by_default = EXCLUDED.is_enabled_by_default,
    notes = EXCLUDED.notes,
    updated_at = now();
