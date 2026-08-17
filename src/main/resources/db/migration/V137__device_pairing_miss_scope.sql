-- A wrong pairing code names no hotel, so V120 charged the miss to every hotel at once:
--
--     UPDATE device_pairing_requests SET attempts = attempts + 1 WHERE status = 'pending'
--
-- That has no tenant predicate. Five mistyped codes at one hotel pushed every waiting
-- terminal on the platform past max-attempts, and their managers were then told the
-- pairing was denied. One tenant could stop every other tenant onboarding a till.
--
-- The miss cannot be attributed to the pairing it was aimed at, but it can always be
-- attributed to the manager who submitted it — approval is a strongly authenticated,
-- tenant-scoped route. So the counter moves off the pending rows and onto the approving
-- tenant. Brute force is still bounded (a guesser burns their own tenant's budget), and
-- a hotel's waiting terminal is now unreachable by anyone outside it.

CREATE TABLE device_pairing_approval_misses (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES tenants(id) DEFERRABLE,
    actor_id uuid REFERENCES users(id) DEFERRABLE,
    occurred_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_device_pairing_misses_tenant_time
    ON device_pairing_approval_misses (tenant_id, occurred_at DESC);

COMMENT ON TABLE device_pairing_approval_misses IS
    'One row per rejected pairing-approval attempt, charged to the tenant that submitted '
    'it. Replaces the global attempts counter, which let any tenant deny every other '
    'tenant''s pending pairing.';

ALTER TABLE device_pairing_approval_misses ENABLE ROW LEVEL SECURITY;
ALTER TABLE device_pairing_approval_misses FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON device_pairing_approval_misses
    USING (tenant_id = current_tenant_id());

-- The definer owner is NOBYPASSRLS, so without this it would count nothing.
CREATE POLICY device_pairing_owner_all ON device_pairing_approval_misses
    FOR ALL
    TO pms_device_pairing_owner
    USING (true)
    WITH CHECK (true);

GRANT SELECT, INSERT, DELETE ON device_pairing_approval_misses TO pms_device_pairing_owner;
GRANT SELECT ON device_pairing_approval_misses TO pms_app;
REVOKE DELETE ON device_pairing_approval_misses FROM pms_app, pms_worker;

-- The signature changes, so the old zero-argument version has to go rather than be
-- overloaded. Leaving it in place would leave the cross-tenant UPDATE one call away.
DROP FUNCTION IF EXISTS record_device_pairing_miss();

CREATE OR REPLACE FUNCTION record_device_pairing_miss(
    p_tenant_id uuid,
    p_actor_id uuid,
    p_window interval
) RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
DECLARE
    v_count integer;
BEGIN
    -- Bounded growth without a scheduled job. A day is kept regardless of the
    -- throttling window so the rows remain useful as evidence of a guessing run.
    DELETE FROM device_pairing_approval_misses
    WHERE tenant_id = p_tenant_id
      AND occurred_at < now() - GREATEST(p_window, interval '1 day');

    INSERT INTO device_pairing_approval_misses (tenant_id, actor_id)
    VALUES (p_tenant_id, p_actor_id);

    SELECT count(*) INTO v_count
    FROM device_pairing_approval_misses
    WHERE tenant_id = p_tenant_id
      AND occurred_at > now() - p_window;

    RETURN v_count;
END;
$$;

CREATE OR REPLACE FUNCTION count_recent_device_pairing_misses(
    p_tenant_id uuid,
    p_window interval
) RETURNS integer
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
    SELECT count(*)::integer
    FROM device_pairing_approval_misses
    WHERE tenant_id = p_tenant_id
      AND occurred_at > now() - p_window;
$$;

DO $migration$
DECLARE
    fn text;
BEGIN
    FOREACH fn IN ARRAY ARRAY[
        'record_device_pairing_miss(uuid, uuid, interval)',
        'count_recent_device_pairing_misses(uuid, interval)'
    ]
    LOOP
        EXECUTE format('ALTER FUNCTION %s OWNER TO pms_device_pairing_owner', fn);
        EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC', fn);
        EXECUTE format('GRANT EXECUTE ON FUNCTION %s TO pms_app', fn);
    END LOOP;
END;
$migration$;

DO $migration$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_proc proc
        JOIN pg_namespace ns ON ns.oid = proc.pronamespace
        WHERE ns.nspname = 'public'
          AND proc.proname = 'record_device_pairing_miss'
          AND proc.pronargs = 0
    ) THEN
        RAISE EXCEPTION
            'the tenant-blind record_device_pairing_miss() still exists';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_class
        WHERE relname = 'device_pairing_approval_misses'
          AND relrowsecurity AND relforcerowsecurity
    ) THEN
        RAISE EXCEPTION
            'device_pairing_approval_misses must have row level security enabled and forced';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM pg_proc proc
        JOIN pg_roles owner ON owner.oid = proc.proowner
        JOIN pg_namespace ns ON ns.oid = proc.pronamespace
        WHERE ns.nspname = 'public'
          AND proc.proname IN (
              'record_device_pairing_miss',
              'count_recent_device_pairing_misses'
          )
          AND (owner.rolsuper OR owner.rolbypassrls)
    ) THEN
        RAISE EXCEPTION
            'device pairing miss functions must not be owned by a superuser or a BYPASSRLS role';
    END IF;
END;
$migration$;
