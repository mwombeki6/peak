-- V129 — an operational session already knows which property (and optionally
-- which outlet) the till was paired into. lookup_operational_session dropped
-- outlet_id even though the join to paired_devices had it. REST and STOMP
-- then could not refuse a PIN session that subscribed to another outlet.

DROP FUNCTION IF EXISTS lookup_operational_session(text);

CREATE FUNCTION lookup_operational_session(
    p_token_hash text
) RETURNS TABLE (
    id uuid,
    tenant_id uuid,
    user_id uuid,
    device_id uuid,
    property_id uuid,
    outlet_id uuid
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
    SELECT s.id, s.tenant_id, s.user_id, s.device_id, s.property_id, d.outlet_id
    FROM operational_sessions s
    JOIN paired_devices d ON d.id = s.device_id
    WHERE s.token_hash = p_token_hash
      AND s.revoked_at IS NULL
      AND s.expires_at > now()
      AND d.status = 'active';
$$;

DO $migration$
BEGIN
    EXECUTE 'ALTER FUNCTION lookup_operational_session(text) OWNER TO pms_device_pairing_owner';
    EXECUTE 'REVOKE ALL ON FUNCTION lookup_operational_session(text) FROM PUBLIC';
    EXECUTE 'GRANT EXECUTE ON FUNCTION lookup_operational_session(text) TO pms_app';
END;
$migration$;

DO $migration$
BEGIN
    IF pg_get_function_result('lookup_operational_session(text)'::regprocedure)
        NOT LIKE '%outlet_id%'
    THEN
        RAISE EXCEPTION
            'lookup_operational_session must return outlet_id so a PIN session stays bound to its till';
    END IF;
END;
$migration$;
