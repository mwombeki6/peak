-- =============================================================================
-- Teach entitlement resolution about purchased products, and fix two bugs
--
-- effective_tenant_entitlement decides what a tenant is allowed to have. It is
-- what assert_tenant_entitlement_enabled and assert_tenant_capacity consult, so
-- it is the real gate on enabling a module or exceeding a limit. It currently
-- knows about plan entitlements and support overrides. It does not know that a
-- tenant can now buy a product, which is the point of platformbilling.
--
-- Precedence becomes override, then grants, then plan:
--
--   override   a support exception, still wins outright
--   grant      something the tenant bought and has not yet lost
--   plan       what the base tier includes
--
-- Two existing bugs are fixed in the same statement, because both would be made
-- worse by adding a third source on top of them.
--
-- The plan join carries `AND plan.is_active`. is_active governs whether a plan
-- may be *sold*; using it during *resolution* means the day someone deactivates
-- a superseded plan, every tenant still on it silently loses every entitlement
-- at once. Sellability is enforced at quote time instead.
--
-- The subscription lookup is COALESCE(subscription.plan_id, tenant.plan_id). The
-- fallback means a tenant whose subscription is cancelled or expired keeps
-- everything, because tenants.plan_id still points at the plan they used to have.
-- That makes a lapsed subscription mean nothing, which is precisely what
-- self-service billing must not be built on top of.
--
-- So the fallback is narrowed rather than removed: it fires only when a tenant
-- has no subscription row at all, never when one exists in a terminal status.
-- Removing it outright was tried first and was too blunt -- a tenant assigned a
-- plan but never subscribed would lose everything, which is the state every
-- freshly created tenant is in until onboarding writes its trialing row.
--
-- This is still a behaviour change for a tenant whose only subscription rows are
-- terminal. The guard at the end of this migration fails the deployment rather
-- than quietly revoking access from a live tenant.
--
-- granted_entitlements is an object keyed by entitlement code:
--   {"module.pos": {"is_enabled": true, "value": {}},
--    "limit.rooms": {"is_enabled": true, "value": {"limit": 500}}}
-- A tenant may hold several grants naming the same code — two products that both
-- raise limit.rooms, or a per-property add-on bought for several properties — so
-- limits take the maximum and capability flags take the disjunction.
--
-- CREATE OR REPLACE resets proconfig, so the hardened search_path V86 applied to
-- this function is restated. Losing it would return the function to resolving
-- pg_temp first, which is the escalation V86 exists to prevent.
-- =============================================================================

CREATE OR REPLACE FUNCTION effective_tenant_entitlement(
    p_tenant_id uuid,
    p_entitlement_code text
) RETURNS TABLE (
    is_enabled boolean,
    entitlement_value jsonb,
    source text
)
LANGUAGE plpgsql STABLE SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
DECLARE
    v_code text;
BEGIN
    IF p_tenant_id IS NULL OR NULLIF(btrim(p_entitlement_code), '') IS NULL THEN
        RAISE EXCEPTION 'Tenant id and entitlement code are required';
    END IF;
    IF p_tenant_id IS DISTINCT FROM current_tenant_id()
       AND NOT platform_user_has_permission(current_platform_user_id(), 'platform.billing.manage')
       AND NOT platform_user_has_permission(current_platform_user_id(), 'platform.tenants.manage') THEN
        RAISE EXCEPTION 'Tenant entitlement access is not authorized';
    END IF;

    v_code := lower(btrim(p_entitlement_code));

    RETURN QUERY
    WITH current_subscription AS (
        -- When a subscription row exists it is the only source of truth: a row outside
        -- these statuses grants nothing, which is what makes expiry mean something.
        (
            SELECT subscription.plan_id
            FROM tenant_subscriptions subscription
            JOIN tenants tenant
              ON tenant.id = subscription.tenant_id AND tenant.deleted_at IS NULL
            WHERE subscription.tenant_id = p_tenant_id
              AND subscription.status IN ('trialing', 'active', 'past_due', 'paused')
            ORDER BY subscription.created_at DESC
            LIMIT 1
        )
        UNION ALL
        -- The fallback to tenants.plan_id, deliberately narrowed to the case it was
        -- always meant for: a tenant that holds a plan but has never had a subscription
        -- row at all. The original blanket COALESCE(subscription.plan_id,
        -- tenant.plan_id) was the bug, because it also fired when a row existed in a
        -- terminal status -- so a cancelled subscription kept everything, since
        -- tenants.plan_id still pointed at the plan they used to have.
        --
        -- The two branches are mutually exclusive by construction, so this yields at
        -- most one row.
        (
            SELECT tenant.plan_id
            FROM tenants tenant
            WHERE tenant.id = p_tenant_id
              AND tenant.deleted_at IS NULL
              AND tenant.plan_id IS NOT NULL
              AND NOT EXISTS (
                  SELECT 1
                  FROM tenant_subscriptions subscription
                  WHERE subscription.tenant_id = p_tenant_id
              )
        )
    ), effective_override AS (
        SELECT override.is_enabled, override.entitlement_value,
               'override'::text AS source
        FROM tenant_entitlement_overrides override
        WHERE override.tenant_id = p_tenant_id
          AND override.entitlement_code = v_code
          AND override.revoked_at IS NULL
          AND override.starts_at <= now()
          AND (override.expires_at IS NULL OR override.expires_at > now())
        ORDER BY override.starts_at DESC, override.created_at DESC
        LIMIT 1
    ), grant_value AS (
        SELECT
            bool_or(
                COALESCE(
                    (product_grant.granted_entitlements -> v_code ->> 'is_enabled')::boolean,
                    false
                )
            ) AS is_enabled,
            CASE
                WHEN v_code LIKE 'limit.%' THEN jsonb_build_object(
                    'limit',
                    max(
                        (product_grant.granted_entitlements -> v_code -> 'value' ->> 'limit')::bigint
                    )
                )
                ELSE COALESCE(
                    (
                        array_agg(
                            product_grant.granted_entitlements -> v_code -> 'value'
                            ORDER BY product_grant.starts_at DESC
                        )
                    )[1],
                    '{}'::jsonb
                )
            END AS entitlement_value,
            'grant'::text AS source
        FROM peak_product_grants product_grant
        WHERE product_grant.tenant_id = p_tenant_id
          AND product_grant.status = 'active'
          AND product_grant.revoked_at IS NULL
          AND product_grant.starts_at <= now()
          AND (product_grant.ends_at IS NULL OR product_grant.ends_at > now())
          AND product_grant.granted_entitlements ? v_code
        HAVING count(*) > 0
    ), plan_value AS (
        SELECT COALESCE(entitlement.is_enabled, true) AS is_enabled,
               COALESCE(
                   entitlement.entitlement_value,
                   CASE v_code
                       WHEN 'limit.properties' THEN jsonb_build_object('limit', plan.max_properties)
                       WHEN 'limit.rooms' THEN jsonb_build_object('limit', plan.max_rooms)
                       WHEN 'limit.users' THEN jsonb_build_object('limit', plan.max_users)
                       WHEN 'limit.outlets' THEN jsonb_build_object('limit', plan.max_outlets)
                   END
               ) AS entitlement_value,
               'plan'::text AS source
        FROM current_subscription subscription
        -- No is_active filter: a superseded plan must keep serving the tenants
        -- already on it. Sellability is a quote-time concern.
        JOIN plans plan ON plan.id = subscription.plan_id
        LEFT JOIN plan_entitlements entitlement
          ON entitlement.plan_id = subscription.plan_id
         AND entitlement.entitlement_code = v_code
        WHERE entitlement.id IS NOT NULL
           OR v_code IN (
               'limit.properties', 'limit.rooms', 'limit.users', 'limit.outlets'
           )
    )
    SELECT * FROM effective_override
    UNION ALL
    SELECT * FROM grant_value
    WHERE NOT EXISTS (SELECT 1 FROM effective_override)
    UNION ALL
    SELECT * FROM plan_value
    WHERE NOT EXISTS (SELECT 1 FROM effective_override)
      AND NOT EXISTS (SELECT 1 FROM grant_value)
    LIMIT 1;
END;
$$;

-- The function reads platformbilling's grants now, so the runtimes that call it
-- need to be able to see them. It is SECURITY DEFINER, so this is about the
-- definer's reach rather than the caller's.
GRANT SELECT ON peak_product_grants TO pms_app, pms_platform, pms_worker;

-- Narrowing the tenants.plan_id fallback is the one change here that can take
-- access away. Fail the migration rather than discover it in production.
--
-- The tenants at risk are precisely those that have a subscription row, none of
-- it service-granting, and were relying on the old blanket fallback to keep
-- working. A tenant with no subscription row at all is not at risk: the narrowed
-- fallback still covers it.
DO $migration$
DECLARE
    affected bigint;
    sample text;
BEGIN
    SELECT count(*), string_agg(DISTINCT tenant.id::text, ', ')
    INTO affected, sample
    FROM tenants tenant
    WHERE tenant.deleted_at IS NULL
      AND tenant.plan_id IS NOT NULL
      AND tenant.status IN ('trial', 'active')
      AND EXISTS (
          SELECT 1
          FROM tenant_subscriptions subscription
          WHERE subscription.tenant_id = tenant.id
      )
      AND NOT EXISTS (
          SELECT 1
          FROM tenant_subscriptions subscription
          WHERE subscription.tenant_id = tenant.id
            AND subscription.status IN ('trialing', 'active', 'past_due', 'paused')
      );

    IF affected > 0 THEN
        RAISE EXCEPTION
            'Narrowing the tenants.plan_id entitlement fallback would revoke access from % live tenant(s): %. Each holds a subscription row in a terminal status and was relying on the fallback. Give each a service-granting subscription row before applying this migration.',
            affected, left(coalesce(sample, ''), 400);
    END IF;
END;
$migration$;

DO $migration$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_catalog.pg_proc
        WHERE proname = 'effective_tenant_entitlement'
          AND 'search_path=pg_catalog, public, pg_temp' = ANY(proconfig)
    ) THEN
        RAISE EXCEPTION
            'effective_tenant_entitlement lost its hardened search_path; CREATE OR REPLACE resets proconfig';
    END IF;
END;
$migration$;
