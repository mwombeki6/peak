-- =============================================================================
-- Scope tenant privileged-access evidence to the bound tenant
--
-- V82 defined tenant_privileged_access_evidence over platform_break_glass_access
-- and the usage ledger. Both carry row-level security keyed on platform
-- permissions, but a PostgreSQL view executes with the privileges of its owner
-- rather than its caller, so those policies would not constrain a tenant
-- reading through the view. Correctness would have depended entirely on every
-- caller remembering a WHERE clause.
--
-- The view now filters on current_tenant_id() itself. A tenant sees exactly
-- their own privileged-access history; a caller with no bound tenant context
-- sees nothing, so the failure mode is an empty result rather than another
-- tenant's data.
--
-- Platform operators do not read tenant evidence through this view. They have
-- direct, RLS-governed access to the underlying tables.
-- =============================================================================

CREATE OR REPLACE VIEW public.tenant_privileged_access_evidence AS
SELECT
    access.tenant_id,
    access.id AS access_id,
    access.support_ticket_id,
    access.platform_user_id,
    operator.full_name AS operator_name,
    access.action_code,
    access.operation_code,
    access.reason,
    'grant_' || access.status AS event_type,
    access.requested_at AS occurred_at,
    access.starts_at,
    access.expires_at,
    access.max_uses,
    access.use_count,
    NULL::text AS denial_reason
FROM public.platform_break_glass_access AS access
JOIN public.platform_users AS operator
  ON operator.id = access.platform_user_id
WHERE access.tenant_id = public.current_tenant_id()

UNION ALL

SELECT
    usage.tenant_id,
    usage.access_id,
    access.support_ticket_id,
    usage.platform_user_id,
    operator.full_name AS operator_name,
    access.action_code,
    usage.operation_code,
    access.reason,
    'use_' || usage.decision AS event_type,
    usage.occurred_at,
    access.starts_at,
    access.expires_at,
    access.max_uses,
    access.use_count,
    usage.denial_reason
FROM public.platform_privileged_access_usage AS usage
JOIN public.platform_break_glass_access AS access
  ON access.id = usage.access_id
JOIN public.platform_users AS operator
  ON operator.id = usage.platform_user_id
WHERE usage.tenant_id = public.current_tenant_id();

COMMENT ON VIEW public.tenant_privileged_access_evidence IS
    'Tenant-readable timeline of privileged Peak staff access, scoped to the bound tenant session. Grant lifecycle plus every consumed or denied use. Internal decision notes are excluded. An unbound session sees nothing.';

REVOKE ALL ON public.tenant_privileged_access_evidence FROM PUBLIC;
GRANT SELECT ON public.tenant_privileged_access_evidence TO pms_app;

-- -----------------------------------------------------------------------------
-- Tenant permission and route contract
-- -----------------------------------------------------------------------------

INSERT INTO public.permission_catalog (
    code, namespace, access_scope, description,
    is_platform_permission, is_tenant_permission
) VALUES
    ('tenant.privileged_access.view', 'tenant', 'tenant',
     'View Peak staff privileged access to this tenant flow permission',
     false, true)
ON CONFLICT (code) DO NOTHING;

INSERT INTO public.permissions (id, tenant_id, code, description)
SELECT gen_random_uuid(), tenant.id, catalog.code, catalog.description
FROM public.tenants AS tenant
JOIN public.permission_catalog AS catalog
  ON catalog.code = 'tenant.privileged_access.view'
WHERE tenant.deleted_at IS NULL
ON CONFLICT ON CONSTRAINT permissions_tenant_id_code_key
DO UPDATE SET description = EXCLUDED.description, updated_at = now();

-- The tenant administrator role can read it without a further grant, since
-- transparency about staff access is part of administering the account.
INSERT INTO public.tenant_role_permissions (tenant_role_id, permission_id)
SELECT role.id, permission.id
FROM public.tenant_roles AS role
JOIN public.permissions AS permission
  ON permission.tenant_id = role.tenant_id
 AND permission.code = 'tenant.privileged_access.view'
WHERE role.code = 'tenant_admin'
  AND role.is_system = true
ON CONFLICT DO NOTHING;

INSERT INTO public.module_access_matrix (
    module_id, screen_key, screen_label, http_method, api_pattern,
    permission_code, route_scope, guard_mode, access_scope,
    is_tanzania_v1, is_enabled_by_default, operation_code, notes
) VALUES
    ('tenant_admin', 'tenant.privileged_access.view',
     'Peak Staff Access History',
     'GET', '/api/tenants/:tenantId/privileged-access',
     'tenant.privileged_access.view',
     'tenant', 'staff_permission', 'tenant', true, true, NULL,
     'Tenant-readable record of privileged Peak staff access to this account.')
ON CONFLICT (
    module_id, screen_key, http_method, api_pattern, permission_code
) DO UPDATE SET
    screen_label = EXCLUDED.screen_label,
    route_scope = EXCLUDED.route_scope,
    guard_mode = EXCLUDED.guard_mode,
    access_scope = EXCLUDED.access_scope,
    is_enabled_by_default = EXCLUDED.is_enabled_by_default,
    notes = EXCLUDED.notes,
    updated_at = now();
