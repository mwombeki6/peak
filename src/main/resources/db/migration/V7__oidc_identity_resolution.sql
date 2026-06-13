-- ================================================================================
-- OIDC identity resolution
-- ================================================================================

CREATE FUNCTION resolve_oidc_identity_link(
    p_issuer text,
    p_subject text
) RETURNS TABLE (
    identity_mode text,
    tenant_id uuid,
    user_id uuid,
    platform_user_id uuid
)
    LANGUAGE sql
    SECURITY DEFINER
    SET search_path = public
    AS $$
WITH candidate AS MATERIALIZED (
    SELECT
        il.id AS identity_link_id,
        il.identity_mode::text AS identity_mode,
        il.tenant_id,
        il.user_id,
        il.platform_user_id
    FROM identity_links il
    LEFT JOIN tenants t
        ON t.id = il.tenant_id
    LEFT JOIN users u
        ON u.tenant_id = il.tenant_id
       AND u.id = il.user_id
    LEFT JOIN platform_users pu
        ON pu.id = il.platform_user_id
    WHERE il.provider = 'oidc'
      AND il.issuer = btrim(p_issuer)
      AND il.subject = btrim(p_subject)
      AND il.revoked_at IS NULL
      AND (
          (
              il.identity_mode = 'tenant'
              AND t.status IN ('trial', 'active')
              AND t.deleted_at IS NULL
              AND u.status = 'active'
              AND u.is_active = true
              AND u.deleted_at IS NULL
              AND (u.locked_until IS NULL OR u.locked_until <= now())
          )
          OR
          (
              il.identity_mode = 'platform'
              AND pu.status = 'active'
              AND pu.deleted_at IS NULL
              AND (pu.locked_until IS NULL OR pu.locked_until <= now())
          )
      )
    LIMIT 1
),
touched AS (
    UPDATE identity_links il
    SET last_seen_at = now()
    FROM candidate c
    WHERE il.id = c.identity_link_id
      AND (
          il.last_seen_at IS NULL
          OR il.last_seen_at < now() - interval '5 minutes'
      )
    RETURNING il.id
)
SELECT
    c.identity_mode,
    c.tenant_id,
    c.user_id,
    c.platform_user_id
FROM candidate c;
$$;
