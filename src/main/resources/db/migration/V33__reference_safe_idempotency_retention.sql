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
        SELECT ik.id
        FROM idempotency_keys ik
        WHERE ik.status = 'expired'
          AND ik.expires_at <= now() - p_retention
          AND NOT EXISTS (
              SELECT 1
              FROM outbox_events oe
              WHERE oe.idempotency_key_id = ik.id
          )
          AND NOT EXISTS (
              SELECT 1
              FROM guest_identity_verification_attempts giva
              WHERE giva.idempotency_key_id = ik.id
          )
          AND NOT EXISTS (
              SELECT 1
              FROM payment_transactions pt
              WHERE pt.idempotency_key_id = ik.id
          )
          AND NOT EXISTS (
              SELECT 1
              FROM fiscal_receipts fr
              WHERE fr.idempotency_key_id = ik.id
          )
        ORDER BY ik.expires_at
        LIMIT p_limit
        FOR UPDATE OF ik SKIP LOCKED
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
