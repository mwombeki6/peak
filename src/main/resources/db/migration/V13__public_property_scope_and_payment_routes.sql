CREATE FUNCTION resolve_public_property_scope(
    p_property_id uuid,
    p_module_id text
) RETURNS TABLE (
    tenant_id uuid,
    property_id uuid
)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = public
    AS $$
    SELECT p.tenant_id, p.id
    FROM properties p
    WHERE p.id = p_property_id
      AND can_access_public_module(p.tenant_id, p.id, p_module_id);
$$;

REVOKE EXECUTE ON FUNCTION resolve_public_property_scope(uuid, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION resolve_public_property_scope(uuid, text) TO pms_app;

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
) VALUES (
    'booking_engine',
    'booking_engine.public_payment',
    'Public Booking Payment',
    'POST',
    '/api/public/properties/:propertyId/booking-engine/payments*',
    NULL,
    'public_property',
    'module_only',
    'property',
    true,
    true,
    'Public booking payment initiation requires booking engine module enablement only'
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
