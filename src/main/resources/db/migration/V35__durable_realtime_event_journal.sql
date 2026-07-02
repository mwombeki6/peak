CREATE TABLE realtime_event_journal (
    sequence_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id uuid NOT NULL DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    event_type text NOT NULL,
    payload jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL DEFAULT now() + interval '24 hours',
    CONSTRAINT uq_realtime_event_journal_event UNIQUE (event_id),
    CONSTRAINT chk_realtime_event_journal_type
        CHECK (event_type ~ '^[A-Za-z][A-Za-z0-9._:-]{1,99}$'),
    CONSTRAINT chk_realtime_event_journal_payload_size
        CHECK (octet_length(payload::text) <= 65536),
    CONSTRAINT fk_realtime_event_journal_property
        FOREIGN KEY (tenant_id, property_id)
        REFERENCES properties(tenant_id, id)
        DEFERRABLE
);

CREATE INDEX idx_realtime_event_journal_scope_sequence
    ON realtime_event_journal (tenant_id, property_id, sequence_id);
CREATE INDEX idx_realtime_event_journal_expiry
    ON realtime_event_journal (expires_at, sequence_id);

ALTER TABLE realtime_event_journal ENABLE ROW LEVEL SECURITY;
ALTER TABLE realtime_event_journal FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON realtime_event_journal
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

CREATE OR REPLACE FUNCTION append_realtime_event(
    p_tenant_id uuid,
    p_property_id uuid,
    p_event_type text,
    p_payload jsonb
) RETURNS TABLE (
    sequence_id bigint,
    event_id uuid,
    tenant_id uuid,
    property_id uuid,
    event_type text,
    payload jsonb,
    created_at timestamptz
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM properties p
        JOIN tenants t ON t.id = p.tenant_id
        WHERE p.tenant_id = p_tenant_id
          AND p.id = p_property_id
          AND p.deleted_at IS NULL
          AND t.deleted_at IS NULL
    ) THEN
        RAISE EXCEPTION 'Realtime property scope does not exist';
    END IF;

    RETURN QUERY
    INSERT INTO realtime_event_journal (
        tenant_id,
        property_id,
        event_type,
        payload
    )
    VALUES (p_tenant_id, p_property_id, p_event_type, p_payload)
    RETURNING
        realtime_event_journal.sequence_id,
        realtime_event_journal.event_id,
        realtime_event_journal.tenant_id,
        realtime_event_journal.property_id,
        realtime_event_journal.event_type,
        realtime_event_journal.payload,
        realtime_event_journal.created_at;
END;
$$;

CREATE OR REPLACE FUNCTION poll_realtime_events(
    p_after_sequence bigint,
    p_limit integer DEFAULT 500
) RETURNS SETOF realtime_event_journal
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    IF p_limit < 1 OR p_limit > 1000 THEN
        RAISE EXCEPTION 'Realtime poll limit must be between 1 and 1000';
    END IF;
    RETURN QUERY
    SELECT *
    FROM realtime_event_journal rej
    WHERE rej.sequence_id > p_after_sequence
      AND rej.expires_at > now()
    ORDER BY rej.sequence_id
    LIMIT p_limit;
END;
$$;

CREATE OR REPLACE FUNCTION latest_realtime_event_sequence() RETURNS bigint
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
    SELECT COALESCE(MAX(sequence_id), 0)
    FROM realtime_event_journal;
$$;

CREATE OR REPLACE FUNCTION replay_realtime_events(
    p_tenant_id uuid,
    p_property_id uuid,
    p_after_sequence bigint,
    p_limit integer DEFAULT 500
) RETURNS SETOF realtime_event_journal
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    IF p_limit < 1 OR p_limit > 1000 THEN
        RAISE EXCEPTION 'Realtime replay limit must be between 1 and 1000';
    END IF;
    RETURN QUERY
    SELECT *
    FROM realtime_event_journal rej
    WHERE rej.tenant_id = p_tenant_id
      AND rej.property_id = p_property_id
      AND rej.sequence_id > p_after_sequence
      AND rej.expires_at > now()
    ORDER BY rej.sequence_id
    LIMIT p_limit;
END;
$$;

CREATE OR REPLACE FUNCTION delete_expired_realtime_events(
    p_limit integer DEFAULT 5000
) RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_deleted integer;
BEGIN
    IF p_limit < 1 OR p_limit > 20000 THEN
        RAISE EXCEPTION 'Realtime cleanup limit must be between 1 and 20000';
    END IF;
    WITH candidates AS (
        SELECT sequence_id
        FROM realtime_event_journal
        WHERE expires_at <= now()
        ORDER BY expires_at, sequence_id
        LIMIT p_limit
        FOR UPDATE SKIP LOCKED
    )
    DELETE FROM realtime_event_journal rej
    USING candidates c
    WHERE rej.sequence_id = c.sequence_id;
    GET DIAGNOSTICS v_deleted = ROW_COUNT;
    RETURN v_deleted;
END;
$$;

REVOKE ALL ON FUNCTION append_realtime_event(uuid, uuid, text, jsonb) FROM PUBLIC;
REVOKE ALL ON FUNCTION poll_realtime_events(bigint, integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION latest_realtime_event_sequence() FROM PUBLIC;
REVOKE ALL ON FUNCTION replay_realtime_events(uuid, uuid, bigint, integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION delete_expired_realtime_events(integer) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION append_realtime_event(uuid, uuid, text, jsonb) TO pms_app;
GRANT EXECUTE ON FUNCTION poll_realtime_events(bigint, integer) TO pms_app;
GRANT EXECUTE ON FUNCTION latest_realtime_event_sequence() TO pms_app;
GRANT EXECUTE ON FUNCTION replay_realtime_events(uuid, uuid, bigint, integer) TO pms_app;
GRANT EXECUTE ON FUNCTION delete_expired_realtime_events(integer) TO pms_app;
