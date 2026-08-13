-- V101 — making the billing permissions actually grantable.
--
-- V99 and V100 added platform.billing.view and platform.billing.reconcile to
-- permission_catalog, which is what the route matrix validates against. But the
-- guard does not read permission_catalog. platform_user_has_permission joins
-- platform_users -> platform_user_roles -> platform_roles ->
-- platform_role_permissions -> platform_permissions, and neither code existed
-- there.
--
-- The consequence was subtle rather than loud, which is why no test caught it.
-- platform_user_has_permission also matches 'platform.admin.all', so a full
-- platform administrator could reach every billing route and nothing looked
-- broken. What was impossible was the thing the split was for: granting a support
-- engineer the right to *see* the reconciliation queue without the right to
-- declare that money arrived. Neither permission could be attached to any role,
-- so the only way to let anyone near billing was to make them a superuser.
--
-- The same omission silently weakened the V100 row-level security policies, which
-- gate on platform.billing.view and would therefore have admitted only
-- platform.admin.all holders.

INSERT INTO platform_permissions (code, namespace, description) VALUES
    ('platform.billing.view', 'billing',
     'View Peak subscription revenue, tenant commercial standing and stuck payments'),
    ('platform.billing.reconcile', 'billing',
     'Re-query a provider and record a resolution for a payment Peak could not determine')
ON CONFLICT (code) DO UPDATE SET
    namespace = EXCLUDED.namespace,
    description = EXCLUDED.description,
    updated_at = now();

DO $migration$
DECLARE
    missing text;
BEGIN
    -- Every platform permission a route depends on must be grantable, or the route
    -- is reachable only by platform.admin.all and the permission split it claims to
    -- enforce is decorative.
    SELECT string_agg(DISTINCT matrix.permission_code, ', ')
    INTO missing
    FROM module_access_matrix matrix
    WHERE matrix.guard_mode = 'platform_permission'
      AND matrix.permission_code IS NOT NULL
      AND matrix.is_enabled_by_default = true
      AND NOT EXISTS (
          SELECT 1 FROM platform_permissions perm
          WHERE perm.code = matrix.permission_code
      );

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION
            'These platform routes require permissions that cannot be granted to any role, '
            'so only platform.admin.all can reach them: %', missing;
    END IF;
END;
$migration$;
