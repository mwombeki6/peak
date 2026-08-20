-- FBC could provision an approved application (V149) but had no route to reach "approved" in
-- the first place: every platform verification-case route lives under
-- /api/platform/tenants/:tenantId/verification-cases*, and a pre-tenant application has no
-- tenantId. PlatformOnboardingController now exposes the queue, the application detail and the
-- Application-subject review action; this registers those routes the same way V149 registered
-- the provision one, as separate literal rows rather than a wildcard so this cluster never
-- becomes ambiguous with the existing 'platform.onboarding.provision' registration.

INSERT INTO module_access_matrix (
    module_id, screen_key, screen_label, http_method, api_pattern,
    permission_code, route_scope, guard_mode, access_scope,
    is_tanzania_v1, is_enabled_by_default, notes
) VALUES
    (
        'platform_admin', 'platform.onboarding.queue', 'Onboarding Review Queue',
        'GET', '/api/platform/onboarding',
        'platform.tenants.verification.manage', 'platform', 'platform_permission', 'platform',
        true, true,
        'Applications joined to their most recent verification case, for FBC to work from'
    ),
    (
        'platform_admin', 'platform.onboarding.detail', 'Onboarding Application Detail',
        'GET', '/api/platform/onboarding/:applicationId',
        'platform.tenants.verification.manage', 'platform', 'platform_permission', 'platform',
        true, true,
        'A single application''s own fields, not the case — case detail already exists at ' ||
        'GET /api/platform/onboarding/:applicationId/verification-cases'
    ),
    (
        'platform_admin', 'platform.onboarding.cases', 'Onboarding Verification Cases',
        'GET', '/api/platform/onboarding/:applicationId/verification-cases',
        'platform.tenants.verification.manage', 'platform', 'platform_permission', 'platform',
        true, true,
        'Application-subject mirror of GET /api/platform/tenants/:tenantId/verification-cases'
    ),
    (
        'platform_admin', 'platform.onboarding.review', 'Review Onboarding Verification Case',
        'POST', '/api/platform/onboarding/:applicationId/verification-cases/:caseId/review',
        'platform.tenants.verification.manage', 'platform', 'platform_permission', 'platform',
        true, true,
        'Application-subject mirror of the tenant-scoped verification-cases/:caseId/review action'
    ),
    (
        'platform_admin', 'platform.onboarding.document_view', 'View Onboarding Verification Document',
        'GET',
        '/api/platform/onboarding/:applicationId/verification-cases/:caseId/documents/:documentId/view-url',
        'platform.tenants.verification.manage', 'platform', 'platform_permission', 'platform',
        true, true,
        'A short-lived read URL so FBC can look at what an applicant submitted. The tenant-scoped ' ||
        'equivalent is already covered by the /api/platform/tenants/:tenantId/verification-cases* ' ||
        'wildcard registered in V71.'
    )
ON CONFLICT (module_id, screen_key, http_method, api_pattern, permission_code)
DO UPDATE SET
    screen_label = EXCLUDED.screen_label,
    route_scope = EXCLUDED.route_scope,
    guard_mode = EXCLUDED.guard_mode,
    access_scope = EXCLUDED.access_scope,
    is_tanzania_v1 = EXCLUDED.is_tanzania_v1,
    is_enabled_by_default = EXCLUDED.is_enabled_by_default,
    notes = EXCLUDED.notes,
    updated_at = now();
