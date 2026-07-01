DROP INDEX IF EXISTS idx_idempotency_keys_global_key;
DROP INDEX IF EXISTS idx_idempotency_keys_tenant_key;

CREATE UNIQUE INDEX idx_idempotency_keys_global_key
    ON idempotency_keys (idempotency_key)
    WHERE tenant_id IS NULL AND status <> 'expired';

CREATE UNIQUE INDEX idx_idempotency_keys_tenant_key
    ON idempotency_keys (tenant_id, idempotency_key)
    WHERE tenant_id IS NOT NULL AND status <> 'expired';

CREATE OR REPLACE FUNCTION maintain_idempotency_keys(
    p_retention interval DEFAULT interval '90 days',
    p_limit integer DEFAULT 1000
) RETURNS TABLE(expired_count bigint, deleted_count bigint)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_expired bigint;
    v_deleted bigint;
BEGIN
    IF p_retention < interval '1 day' THEN
        RAISE EXCEPTION 'Idempotency retention must be at least one day';
    END IF;
    IF p_limit < 1 OR p_limit > 10000 THEN
        RAISE EXCEPTION 'Idempotency maintenance limit must be between 1 and 10000';
    END IF;

    WITH candidates AS (
        SELECT id
        FROM idempotency_keys
        WHERE status <> 'expired'
          AND expires_at <= now()
        ORDER BY expires_at
        LIMIT p_limit
        FOR UPDATE SKIP LOCKED
    )
    UPDATE idempotency_keys ik
    SET status = 'expired',
        locked_at = NULL,
        updated_at = now()
    FROM candidates c
    WHERE ik.id = c.id;
    GET DIAGNOSTICS v_expired = ROW_COUNT;

    WITH candidates AS (
        SELECT id
        FROM idempotency_keys
        WHERE status = 'expired'
          AND expires_at <= now() - p_retention
          AND NOT EXISTS (
              SELECT 1
              FROM outbox_events oe
              WHERE oe.idempotency_key_id = idempotency_keys.id
          )
          AND NOT EXISTS (
              SELECT 1
              FROM guest_identity_verification_attempts giva
              WHERE giva.idempotency_key_id = idempotency_keys.id
          )
        ORDER BY expires_at
        LIMIT p_limit
        FOR UPDATE SKIP LOCKED
    )
    DELETE FROM idempotency_keys ik
    USING candidates c
    WHERE ik.id = c.id;
    GET DIAGNOSTICS v_deleted = ROW_COUNT;

    RETURN QUERY SELECT v_expired, v_deleted;
END;
$$;

REVOKE ALL ON FUNCTION maintain_idempotency_keys(interval, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION maintain_idempotency_keys(interval, integer) TO pms_worker;
