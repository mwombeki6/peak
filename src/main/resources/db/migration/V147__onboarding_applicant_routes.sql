-- An onboarding applicant is not a tenant and not a platform operator, so neither existing
-- AUTHENTICATED_IDENTITY route scope fits its session. This adds a third one, scoped to the
-- single applicationId carried by an ONBOARDING_APPLICANT session — never a path variable,
-- since the session itself is the only credential that can exist for it.

ALTER TABLE module_access_matrix
    DROP CONSTRAINT chk_module_access_matrix_route_scope;

ALTER TABLE module_access_matrix
    ADD CONSTRAINT chk_module_access_matrix_route_scope CHECK (
        (route_scope)::text = ANY (
            (ARRAY['tenant', 'property', 'public_property', 'public', 'platform', 'onboarding_application'])::text[]
        )
    );

-- An applicant's mutations (create case, add document, submit) still go through the same
-- idempotency ledger as every other actor type — it just isn't one of the five that existed
-- before there was a pre-tenant identity at all.
ALTER TABLE idempotency_keys
    DROP CONSTRAINT chk_idempotency_keys_actor_type;

ALTER TABLE idempotency_keys
    ADD CONSTRAINT chk_idempotency_keys_actor_type CHECK (
        (actor_type)::text = ANY (
            (ARRAY['platform_user', 'tenant_user', 'guest', 'system', 'integration', 'onboarding_applicant'])::text[]
        )
    );

INSERT INTO module_access_matrix (
    module_id, screen_key, screen_label, http_method, api_pattern,
    permission_code, route_scope, guard_mode, access_scope,
    is_tanzania_v1, is_enabled_by_default, notes
) VALUES
    (
        'tenant_admin', 'onboarding.request_access', 'Request Enterprise Access',
        'POST', '/api/onboarding/request-access',
        NULL, 'public', 'public_token', 'platform',
        true, true,
        'Unauthenticated: a prospect submits minimal contact details and a phone challenge is sent'
    ),
    (
        'tenant_admin', 'onboarding.verify_phone', 'Verify Applicant Phone',
        'POST', '/api/onboarding/verify-phone',
        NULL, 'public', 'public_token', 'platform',
        true, true,
        'Unauthenticated: proves control of the phone and issues an ONBOARDING_ONLY session'
    ),
    (
        'tenant_admin', 'onboarding.me.verification', 'Applicant Verification',
        'ANY', '/api/onboarding/me/verification-cases*',
        NULL, 'onboarding_application', 'authenticated_identity', 'platform',
        true, true,
        'An ONBOARDING_ONLY session manages KYB on its own pre-tenant application only'
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
