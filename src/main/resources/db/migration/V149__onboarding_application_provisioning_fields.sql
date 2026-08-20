-- Tenant provisioning (TenantOnboardingService.registerNewTenant) needs a legal name and a
-- business email that onboarding_applications never captured — request-access only ever
-- asked for a trading name. Nullable, not backfilled: this table has no production data yet
-- (introduced this same branch, V146), and the real requirement is enforced where it belongs —
-- as a precondition an applicant must satisfy before their application can be provisioned, not
-- as a column constraint that would block writing a still-in-progress application.

ALTER TABLE onboarding_applications
    ADD COLUMN legal_name text,
    ADD COLUMN business_email text;

ALTER TABLE onboarding_applications
    ADD CONSTRAINT chk_onboarding_applications_business_email
    CHECK (business_email IS NULL OR business_email ~ '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$');

INSERT INTO module_access_matrix (
    module_id, screen_key, screen_label, http_method, api_pattern,
    permission_code, route_scope, guard_mode, access_scope,
    is_tanzania_v1, is_enabled_by_default, notes
) VALUES
    (
        'tenant_admin', 'onboarding.me.profile', 'Applicant Business Profile',
        'POST', '/api/onboarding/me/profile',
        NULL, 'onboarding_application', 'authenticated_identity', 'platform',
        true, true,
        'An applicant supplies the legal name and business email tenant provisioning needs'
    ),
    (
        'platform_admin', 'platform.onboarding.provision', 'Provision Tenant From Application',
        'POST', '/api/platform/onboarding/:applicationId/provision',
        'platform.tenants.manage', 'platform', 'platform_permission', 'platform',
        true, true,
        'Idempotently provisions a tenant from an APPROVED onboarding application — the '
        'explicit step after KYB approval, never an automatic side effect of reviewing it'
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
