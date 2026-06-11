-- ================================================================================
-- Peak PMS — Outbox correlation context hardening
-- ================================================================================

ALTER TABLE outbox_events
    ALTER COLUMN correlation_id TYPE text
    USING correlation_id::text;

DROP FUNCTION claim_outbox_events(text, text, integer);

CREATE FUNCTION claim_outbox_events(
    p_worker_id text,
    p_destination text DEFAULT NULL,
    p_limit integer DEFAULT 50
) RETURNS TABLE (
    id uuid,
    tenant_id uuid,
    property_id uuid,
    aggregate_type text,
    aggregate_id uuid,
    event_type text,
    destination character varying(50),
    payload jsonb,
    headers jsonb,
    correlation_id text,
    idempotency_key_id uuid,
    status character varying(20),
    priority smallint,
    attempt_count integer,
    max_attempts integer,
    next_attempt_at timestamp with time zone,
    locked_by text,
    locked_at timestamp with time zone,
    delivered_at timestamp with time zone,
    failed_at timestamp with time zone,
    error_message text,
    created_at timestamp with time zone,
    updated_at timestamp with time zone
)
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path = public
    AS $$
BEGIN
  IF p_worker_id IS NULL OR btrim(p_worker_id) = '' THEN
    RAISE EXCEPTION 'Outbox worker id is required';
  END IF;

  IF p_limit IS NULL OR p_limit < 1 OR p_limit > 500 THEN
    RAISE EXCEPTION 'Outbox claim limit must be between 1 and 500';
  END IF;

  RETURN QUERY
  WITH claimable AS (
    SELECT oe.id
    FROM outbox_events oe
    WHERE oe.status IN ('pending', 'failed')
      AND oe.next_attempt_at <= now()
      AND oe.attempt_count < oe.max_attempts
      AND (p_destination IS NULL OR oe.destination = p_destination)
    ORDER BY oe.priority ASC, oe.created_at ASC
    FOR UPDATE SKIP LOCKED
    LIMIT p_limit
  )
  UPDATE outbox_events oe
  SET status = 'locked',
      locked_by = p_worker_id,
      locked_at = now(),
      attempt_count = oe.attempt_count + 1,
      updated_at = now()
  FROM claimable c
  WHERE oe.id = c.id
  RETURNING
    oe.id,
    oe.tenant_id,
    oe.property_id,
    oe.aggregate_type,
    oe.aggregate_id,
    oe.event_type,
    oe.destination,
    oe.payload,
    oe.headers,
    oe.correlation_id,
    oe.idempotency_key_id,
    oe.status,
    oe.priority,
    oe.attempt_count,
    oe.max_attempts,
    oe.next_attempt_at,
    oe.locked_by,
    oe.locked_at,
    oe.delivered_at,
    oe.failed_at,
    oe.error_message,
    oe.created_at,
    oe.updated_at;
END;
$$;

REVOKE EXECUTE ON FUNCTION claim_outbox_events(text, text, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION claim_outbox_events(text, text, integer) TO pms_worker;
