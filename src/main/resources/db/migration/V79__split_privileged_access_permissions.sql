-- =============================================================================
-- Split privileged access permissions
--
-- Closes the verified defect that request, approve, activate, revoke and list
-- all required `platform.support.impersonate`, so two support operators with
-- identical authority could approve one another. The second argument passed at
-- those call sites was only an audit label, never a distinct permission.
--
-- The name is also factually wrong. Peak never logs in as a tenant user; it
-- grants a scoped platform session that passes tenant-bound authorization.
-- "Impersonate" describes a mechanism Peak does not implement, and Keycloak's
-- native impersonation role must never be granted to Peak support.
--
-- Three changes must land together or the feature breaks in between:
--   1. Seed the six replacement codes.
--   2. Register exact route contracts so the HTTP guard enforces them.
--   3. Rewrite the row-level security policy that still keys on the old code.
--
-- Point 3 is the subtle one. The policy on platform_break_glass_access reads
-- `platform_user_has_permission(current_platform_user_id(),
-- 'platform.support.impersonate')`. Deprecating the code without rewriting the
-- policy would make break-glass rows invisible to precisely the operators the
-- new codes authorize, and the feature would silently stop working.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. Replacement permission codes
-- -----------------------------------------------------------------------------

INSERT INTO public.permission_catalog (
    code, namespace, access_scope, description,
    is_platform_permission, is_tenant_permission
) VALUES
    ('platform.support.access.view', 'platform', 'platform',
     'View privileged tenant support access requests and evidence flow permission', true, false),
    ('platform.support.access.request', 'platform', 'platform',
     'Request scoped, time-bound privileged tenant support access flow permission', true, false),
    ('platform.support.access.approve', 'platform', 'platform',
     'Approve or deny another operator privileged tenant support access flow permission', true, false),
    ('platform.support.access.activate', 'platform', 'platform',
     'Activate an approved privileged tenant support access grant flow permission', true, false),
    ('platform.support.access.revoke', 'platform', 'platform',
     'Revoke an active privileged tenant support access grant flow permission', true, false),
    ('platform.support.access.audit', 'platform', 'platform',
     'Read privileged tenant support access evidence for review flow permission', true, false)
ON CONFLICT (code) DO NOTHING;

INSERT INTO public.platform_permissions (code, namespace, description) VALUES
    ('platform.support.access.view', 'support',
     'View privileged tenant support access requests and evidence'),
    ('platform.support.access.request', 'support',
     'Request scoped, time-bound privileged tenant support access'),
    ('platform.support.access.approve', 'support',
     'Approve or deny another operator privileged tenant support access'),
    ('platform.support.access.activate', 'support',
     'Activate an approved privileged tenant support access grant'),
    ('platform.support.access.revoke', 'support',
     'Revoke an active privileged tenant support access grant'),
    ('platform.support.access.audit', 'support',
     'Read privileged tenant support access evidence for review')
ON CONFLICT (code) DO NOTHING;

-- -----------------------------------------------------------------------------
-- 2. Exact route contracts
-- -----------------------------------------------------------------------------
-- These routes were previously covered only by the wildcard
-- '/api/platform/support*' contract requiring platform.support.manage, so the
-- HTTP guard could not distinguish requesting access from approving it. Exact
-- rows outrank the wildcard by method and literal specificity.

INSERT INTO public.module_access_matrix (
    module_id, screen_key, screen_label, http_method, api_pattern,
    permission_code, route_scope, guard_mode, access_scope,
    is_tanzania_v1, is_enabled_by_default, operation_code, notes
) VALUES
    ('platform_admin', 'platform.support.access.list', 'List Privileged Access',
     'GET', '/api/platform/support/access', 'platform.support.access.view',
     'platform', 'platform_permission', 'platform', true, true, NULL,
     'Read privileged access requests. Separated from approval authority.'),
    ('platform_admin', 'platform.support.access.request', 'Request Privileged Access',
     'POST', '/api/platform/support/access', 'platform.support.access.request',
     'platform', 'platform_permission', 'platform', true, true, NULL,
     'Request scoped tenant access. Cannot approve.'),
    ('platform_admin', 'platform.support.access.decision', 'Decide Privileged Access',
     'POST', '/api/platform/support/access/:accessId/decision', 'platform.support.access.approve',
     'platform', 'platform_permission', 'platform', true, true, NULL,
     'Approve or deny another operator request. Distinct from requesting.'),
    ('platform_admin', 'platform.support.access.activate', 'Activate Privileged Access',
     'POST', '/api/platform/support/access/:accessId/activate', 'platform.support.access.activate',
     'platform', 'platform_permission', 'platform', true, true, NULL,
     'Activate an approved grant and start its window.'),
    ('platform_admin', 'platform.support.access.revoke', 'Revoke Privileged Access',
     'POST', '/api/platform/support/access/:accessId/revoke', 'platform.support.access.revoke',
     'platform', 'platform_permission', 'platform', true, true, NULL,
     'Revoke an active grant before expiry.')
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

-- -----------------------------------------------------------------------------
-- 3. Rewrite the row-level security policy off the deprecated code
-- -----------------------------------------------------------------------------
-- Reading a grant and changing one are separated: viewing requires read-side
-- authority, while writing requires an authority that can actually act.

DROP POLICY IF EXISTS platform_admin ON public.platform_break_glass_access;
CREATE POLICY platform_admin ON public.platform_break_glass_access
    USING (
        public.platform_user_has_permission(
            public.current_platform_user_id(), 'platform.support.access.view')
        OR public.platform_user_has_permission(
            public.current_platform_user_id(), 'platform.support.access.approve')
        OR public.platform_user_has_permission(
            public.current_platform_user_id(), 'platform.support.access.audit')
    )
    WITH CHECK (
        public.platform_user_has_permission(
            public.current_platform_user_id(), 'platform.support.access.request')
        OR public.platform_user_has_permission(
            public.current_platform_user_id(), 'platform.support.access.approve')
        OR public.platform_user_has_permission(
            public.current_platform_user_id(), 'platform.support.access.activate')
        OR public.platform_user_has_permission(
            public.current_platform_user_id(), 'platform.support.access.revoke')
    );

COMMENT ON POLICY platform_admin ON public.platform_break_glass_access IS
    'Privileged access rows are readable by view, approve or audit authority, and writable only by an authority that can act on them.';

-- -----------------------------------------------------------------------------
-- 4. Migrate legacy holders to read and request only
-- -----------------------------------------------------------------------------
-- An existing holder of the combined permission must not silently become an
-- approver or activator. They receive the two weakest replacements; approval
-- and activation require an explicit security review and a deliberate grant.

INSERT INTO public.platform_role_permissions (platform_role_id, platform_permission_id)
SELECT DISTINCT legacy.platform_role_id, replacement.id
FROM public.platform_role_permissions AS legacy
JOIN public.platform_permissions AS old_permission
  ON old_permission.id = legacy.platform_permission_id
 AND old_permission.code = 'platform.support.impersonate'
JOIN public.platform_permissions AS replacement
  ON replacement.code IN (
      'platform.support.access.view',
      'platform.support.access.request'
  )
ON CONFLICT DO NOTHING;

-- -----------------------------------------------------------------------------
-- 5. Deprecate the combined permission
-- -----------------------------------------------------------------------------
-- The catalog row is retained so historical grants and audit records remain
-- interpretable. Nothing authorizes on it any more: the policy above no longer
-- references it, and the services now require the exact replacement codes.

UPDATE public.permission_catalog
SET description = 'DEPRECATED, replaced by platform.support.access.* codes. '
                  || 'Peak does not impersonate tenant users; it grants a scoped '
                  || 'platform session. Retained for history and scheduled for '
                  || 'removal once no role references it.'
WHERE code = 'platform.support.impersonate';

UPDATE public.platform_permissions
SET description = 'DEPRECATED, replaced by platform.support.access.* codes. '
                  || 'Grants no authority; retained for historical grants.'
WHERE code = 'platform.support.impersonate';
