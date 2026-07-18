-- =============================================================================
-- Hospitality platform control plane
--
-- Adds durable desired/actual state, commercial controls, privacy and identity
-- governance, fleet releases, support evidence and portfolio hierarchy without
-- changing any existing V1 response or mutating an applied migration.
-- =============================================================================

CREATE TABLE tenant_control_states (
    tenant_id uuid PRIMARY KEY REFERENCES tenants(id) DEFERRABLE,
    lifecycle_status varchar(30) NOT NULL DEFAULT 'trial',
    verification_status varchar(30) NOT NULL DEFAULT 'unverified',
    provisioning_status varchar(30) NOT NULL DEFAULT 'pending',
    subscription_status varchar(30) NOT NULL DEFAULT 'trialing',
    service_status varchar(30) NOT NULL DEFAULT 'operational',
    offboarding_status varchar(30) NOT NULL DEFAULT 'none',
    release_channel varchar(30) NOT NULL DEFAULT 'stable',
    data_region varchar(50) NOT NULL DEFAULT 'tz-primary',
    desired_configuration_version bigint NOT NULL DEFAULT 1,
    actual_configuration_version bigint NOT NULL DEFAULT 1,
    version bigint NOT NULL DEFAULT 1,
    last_reconciled_at timestamptz,
    updated_by_platform_user_id uuid REFERENCES platform_users(id) DEFERRABLE,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_tenant_control_lifecycle CHECK (lifecycle_status IN (
        'trial', 'active', 'restricted', 'frozen', 'suspended', 'archived',
        'offboarding', 'terminated', 'cancelled'
    )),
    CONSTRAINT chk_tenant_control_verification CHECK (verification_status IN (
        'unverified', 'pending', 'under_review', 'needs_information',
        'verified', 'rejected', 'suspended', 'expired'
    )),
    CONSTRAINT chk_tenant_control_provisioning CHECK (provisioning_status IN (
        'pending', 'provisioning', 'ready', 'failed', 'deprovisioning',
        'deprovisioned'
    )),
    CONSTRAINT chk_tenant_control_subscription CHECK (subscription_status IN (
        'trialing', 'active', 'past_due', 'paused', 'cancelled', 'expired'
    )),
    CONSTRAINT chk_tenant_control_service CHECK (service_status IN (
        'operational', 'degraded', 'maintenance', 'disrupted'
    )),
    CONSTRAINT chk_tenant_control_offboarding CHECK (offboarding_status IN (
        'none', 'requested', 'exporting', 'retention_hold', 'scheduled',
        'completed', 'cancelled'
    )),
    CONSTRAINT chk_tenant_control_release_channel CHECK (release_channel IN (
        'canary', 'early_access', 'stable', 'long_term_support'
    )),
    CONSTRAINT chk_tenant_control_versions CHECK (
        desired_configuration_version > 0
        AND actual_configuration_version > 0
        AND version > 0
    )
);

CREATE TABLE tenant_workflows (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES tenants(id) DEFERRABLE,
    workflow_type varchar(40) NOT NULL,
    status varchar(30) NOT NULL DEFAULT 'queued',
    requested_by_platform_user_id uuid REFERENCES platform_users(id) DEFERRABLE,
    requested_by_tenant_user_id uuid,
    reason text,
    current_step text,
    total_steps integer NOT NULL DEFAULT 0,
    completed_steps integer NOT NULL DEFAULT 0,
    version bigint NOT NULL DEFAULT 1,
    started_at timestamptz,
    completed_at timestamptz,
    failed_at timestamptz,
    error_code text,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_tenant_workflows_tenant_id_id UNIQUE (tenant_id, id),
    CONSTRAINT chk_tenant_workflow_type CHECK (workflow_type IN (
        'onboarding', 'plan_change', 'trial_extension', 'reactivation',
        'freeze', 'archive', 'offboarding', 'restore', 'data_export',
        'configuration_rollout'
    )),
    CONSTRAINT chk_tenant_workflow_status CHECK (status IN (
        'queued', 'running', 'waiting_for_approval', 'waiting_for_customer',
        'retrying', 'succeeded', 'failed', 'cancelled'
    )),
    CONSTRAINT chk_tenant_workflow_progress CHECK (
        total_steps >= 0 AND completed_steps >= 0 AND completed_steps <= total_steps
    )
);

CREATE TABLE tenant_workflow_steps (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    workflow_id uuid NOT NULL,
    step_key text NOT NULL,
    sequence integer NOT NULL,
    status varchar(30) NOT NULL DEFAULT 'pending',
    attempt_count integer NOT NULL DEFAULT 0,
    started_at timestamptz,
    completed_at timestamptz,
    next_attempt_at timestamptz,
    error_code text,
    error_detail text,
    evidence jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_tenant_workflow_step_workflow FOREIGN KEY (tenant_id, workflow_id)
        REFERENCES tenant_workflows(tenant_id, id) DEFERRABLE,
    CONSTRAINT uq_tenant_workflow_step UNIQUE (workflow_id, step_key),
    CONSTRAINT uq_tenant_workflow_step_sequence UNIQUE (workflow_id, sequence),
    CONSTRAINT chk_tenant_workflow_step_sequence CHECK (sequence > 0),
    CONSTRAINT chk_tenant_workflow_step_attempts CHECK (attempt_count >= 0),
    CONSTRAINT chk_tenant_workflow_step_status CHECK (status IN (
        'pending', 'running', 'waiting', 'succeeded', 'failed', 'skipped', 'cancelled'
    ))
);

CREATE TABLE tenant_subscriptions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES tenants(id) DEFERRABLE,
    plan_id uuid NOT NULL REFERENCES plans(id) DEFERRABLE,
    status varchar(30) NOT NULL DEFAULT 'trialing',
    billing_cycle varchar(20) NOT NULL DEFAULT 'monthly',
    billing_currency char(3) NOT NULL DEFAULT 'TZS',
    provider varchar(30) NOT NULL DEFAULT 'manual',
    provider_customer_id text,
    provider_subscription_id text,
    current_period_starts_at timestamptz NOT NULL DEFAULT now(),
    current_period_ends_at timestamptz,
    trial_ends_at timestamptz,
    cancel_at_period_end boolean NOT NULL DEFAULT false,
    grace_period_ends_at timestamptz,
    version bigint NOT NULL DEFAULT 1,
    created_by_platform_user_id uuid REFERENCES platform_users(id) DEFERRABLE,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_tenant_active_subscription UNIQUE NULLS NOT DISTINCT (
        tenant_id, status
    ) DEFERRABLE INITIALLY IMMEDIATE,
    CONSTRAINT uq_tenant_subscriptions_tenant_id_id UNIQUE (tenant_id, id),
    CONSTRAINT chk_tenant_subscription_status CHECK (status IN (
        'trialing', 'active', 'past_due', 'paused', 'cancelled', 'expired'
    )),
    CONSTRAINT chk_tenant_subscription_cycle CHECK (billing_cycle IN (
        'monthly', 'annually', 'contract'
    )),
    CONSTRAINT chk_tenant_subscription_period CHECK (
        current_period_ends_at IS NULL OR current_period_ends_at > current_period_starts_at
    ),
    CONSTRAINT chk_tenant_subscription_version CHECK (version > 0)
);

-- The uniqueness above must allow history while preventing more than one service-
-- granting subscription. Replace it with a partial index that expresses that rule.
ALTER TABLE tenant_subscriptions DROP CONSTRAINT uq_tenant_active_subscription;
CREATE UNIQUE INDEX uq_tenant_service_granting_subscription
    ON tenant_subscriptions (tenant_id)
    WHERE status IN ('trialing', 'active', 'past_due', 'paused');

CREATE TABLE tenant_entitlement_overrides (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES tenants(id) DEFERRABLE,
    entitlement_code text NOT NULL,
    entitlement_value jsonb NOT NULL DEFAULT '{}'::jsonb,
    is_enabled boolean NOT NULL,
    reason text NOT NULL,
    starts_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz,
    approved_by_platform_user_id uuid NOT NULL REFERENCES platform_users(id) DEFERRABLE,
    revoked_at timestamptz,
    revoked_by_platform_user_id uuid REFERENCES platform_users(id) DEFERRABLE,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_tenant_entitlement_override_dates CHECK (
        expires_at IS NULL OR expires_at > starts_at
    ),
    CONSTRAINT chk_tenant_entitlement_override_revocation CHECK (
        revoked_at IS NULL OR revoked_by_platform_user_id IS NOT NULL
    )
);

CREATE TABLE tenant_privacy_requests (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES tenants(id) DEFERRABLE,
    request_type varchar(30) NOT NULL,
    subject_reference text NOT NULL,
    status varchar(30) NOT NULL DEFAULT 'submitted',
    requested_by_user_id uuid,
    assigned_platform_user_id uuid REFERENCES platform_users(id) DEFERRABLE,
    due_at timestamptz NOT NULL,
    verified_at timestamptz,
    completed_at timestamptz,
    rejection_reason text,
    export_object_key text,
    export_content_hash text,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_tenant_privacy_requests_tenant_id_id UNIQUE (tenant_id, id),
    CONSTRAINT chk_tenant_privacy_request_type CHECK (request_type IN (
        'access', 'rectification', 'erasure', 'restriction', 'portability',
        'objection', 'consent_withdrawal'
    )),
    CONSTRAINT chk_tenant_privacy_request_status CHECK (status IN (
        'submitted', 'identity_verification', 'in_progress', 'blocked_by_legal_hold',
        'ready', 'completed', 'rejected', 'cancelled'
    )),
    CONSTRAINT chk_tenant_privacy_request_hash CHECK (
        export_content_hash IS NULL OR export_content_hash ~ '^[0-9a-f]{64}$'
    )
);

CREATE TABLE tenant_legal_holds (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES tenants(id) DEFERRABLE,
    hold_scope varchar(30) NOT NULL,
    subject_reference text,
    reason text NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'active',
    starts_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz,
    released_at timestamptz,
    created_by_platform_user_id uuid NOT NULL REFERENCES platform_users(id) DEFERRABLE,
    released_by_platform_user_id uuid REFERENCES platform_users(id) DEFERRABLE,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_tenant_legal_hold_scope CHECK (hold_scope IN (
        'tenant', 'guest', 'user', 'financial', 'document'
    )),
    CONSTRAINT chk_tenant_legal_hold_status CHECK (status IN ('active', 'released', 'expired')),
    CONSTRAINT chk_tenant_legal_hold_dates CHECK (expires_at IS NULL OR expires_at > starts_at),
    CONSTRAINT chk_tenant_legal_hold_release CHECK (
        status <> 'released' OR (released_at IS NOT NULL AND released_by_platform_user_id IS NOT NULL)
    )
);

CREATE TABLE tenant_identity_connections (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES tenants(id) DEFERRABLE,
    connection_name text NOT NULL,
    protocol varchar(20) NOT NULL,
    issuer text,
    verified_domain text,
    discovery_url text,
    client_id text,
    client_secret_ref text,
    scim_enabled boolean NOT NULL DEFAULT false,
    status varchar(20) NOT NULL DEFAULT 'draft',
    version bigint NOT NULL DEFAULT 1,
    created_by_user_id uuid,
    verified_by_platform_user_id uuid REFERENCES platform_users(id) DEFERRABLE,
    verified_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_tenant_identity_connection_name UNIQUE (tenant_id, connection_name),
    CONSTRAINT uq_tenant_identity_verified_domain UNIQUE NULLS NOT DISTINCT (verified_domain),
    CONSTRAINT chk_tenant_identity_protocol CHECK (protocol IN ('oidc', 'saml', 'ldap', 'scim')),
    CONSTRAINT chk_tenant_identity_status CHECK (status IN (
        'draft', 'pending_verification', 'active', 'disabled', 'failed'
    )),
    CONSTRAINT chk_tenant_identity_secret_reference CHECK (
        client_secret_ref IS NULL OR client_secret_ref LIKE 'secret://%'
    )
);

CREATE TABLE support_ticket_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    ticket_id uuid NOT NULL REFERENCES support_tickets(id) DEFERRABLE,
    event_type varchar(30) NOT NULL,
    actor_platform_user_id uuid REFERENCES platform_users(id) DEFERRABLE,
    actor_tenant_user_id uuid,
    before_state jsonb,
    after_state jsonb,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_support_ticket_event_type CHECK (event_type IN (
        'opened', 'assigned', 'status_changed', 'priority_changed', 'note_added',
        'access_requested', 'access_approved', 'access_activated', 'access_revoked',
        'resolved', 'reopened'
    ))
);

ALTER TABLE platform_break_glass_access
    ADD COLUMN IF NOT EXISTS support_ticket_id uuid REFERENCES support_tickets(id) DEFERRABLE,
    ADD COLUMN IF NOT EXISTS activated_by uuid REFERENCES platform_users(id) DEFERRABLE,
    ADD COLUMN IF NOT EXISTS max_uses integer NOT NULL DEFAULT 100,
    ADD COLUMN IF NOT EXISTS use_count integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_used_at timestamptz,
    ADD COLUMN IF NOT EXISTS requested_ip inet,
    ADD COLUMN IF NOT EXISTS assurance_level varchar(20) NOT NULL DEFAULT 'mfa';

ALTER TABLE platform_break_glass_access
    ADD CONSTRAINT chk_platform_break_glass_ticket_required
        CHECK (support_ticket_id IS NOT NULL) NOT VALID,
    ADD CONSTRAINT chk_platform_break_glass_max_uses
        CHECK (max_uses BETWEEN 1 AND 1000 AND use_count BETWEEN 0 AND max_uses),
    ADD CONSTRAINT chk_platform_break_glass_assurance
        CHECK (assurance_level IN ('mfa', 'phishing_resistant')),
    ADD CONSTRAINT chk_platform_break_glass_max_duration
        CHECK (expires_at <= starts_at + interval '4 hours') NOT VALID;

CREATE TABLE platform_releases (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    version text NOT NULL UNIQUE,
    image_digest text NOT NULL,
    schema_version integer NOT NULL,
    status varchar(30) NOT NULL DEFAULT 'draft',
    release_notes text,
    created_by_platform_user_id uuid NOT NULL REFERENCES platform_users(id) DEFERRABLE,
    approved_by_platform_user_id uuid REFERENCES platform_users(id) DEFERRABLE,
    created_at timestamptz NOT NULL DEFAULT now(),
    approved_at timestamptz,
    CONSTRAINT chk_platform_release_digest CHECK (image_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT chk_platform_release_schema CHECK (schema_version > 0),
    CONSTRAINT chk_platform_release_status CHECK (status IN (
        'draft', 'approved', 'canary', 'rolling_out', 'stable', 'paused', 'recalled'
    )),
    CONSTRAINT chk_platform_release_approval CHECK (
        status = 'draft' OR (approved_by_platform_user_id IS NOT NULL AND approved_at IS NOT NULL)
    )
);

CREATE TABLE platform_release_assignments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    release_id uuid NOT NULL REFERENCES platform_releases(id) DEFERRABLE,
    tenant_id uuid REFERENCES tenants(id) DEFERRABLE,
    release_channel varchar(30) NOT NULL,
    status varchar(30) NOT NULL DEFAULT 'scheduled',
    desired_version text NOT NULL,
    actual_version text,
    scheduled_at timestamptz NOT NULL DEFAULT now(),
    started_at timestamptz,
    completed_at timestamptz,
    rollback_release_id uuid REFERENCES platform_releases(id) DEFERRABLE,
    error_detail text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_platform_release_assignment UNIQUE NULLS NOT DISTINCT (
        release_id, tenant_id, release_channel
    ),
    CONSTRAINT chk_platform_release_assignment_channel CHECK (release_channel IN (
        'canary', 'early_access', 'stable', 'long_term_support'
    )),
    CONSTRAINT chk_platform_release_assignment_status CHECK (status IN (
        'scheduled', 'running', 'verified', 'failed', 'paused', 'rolled_back'
    ))
);

CREATE TABLE organization_units (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES tenants(id) DEFERRABLE,
    parent_id uuid,
    unit_type varchar(30) NOT NULL,
    code varchar(60) NOT NULL,
    name text NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'active',
    path text NOT NULL,
    version bigint NOT NULL DEFAULT 1,
    created_by_user_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_organization_units_tenant_id_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_organization_unit_code UNIQUE (tenant_id, code),
    CONSTRAINT uq_organization_unit_path UNIQUE (tenant_id, path),
    CONSTRAINT fk_organization_unit_parent FOREIGN KEY (tenant_id, parent_id)
        REFERENCES organization_units(tenant_id, id) DEFERRABLE,
    CONSTRAINT chk_organization_unit_type CHECK (unit_type IN (
        'portfolio', 'brand', 'region', 'hub', 'management_group'
    )),
    CONSTRAINT chk_organization_unit_status CHECK (status IN ('active', 'inactive', 'archived')),
    CONSTRAINT chk_organization_unit_path CHECK (
        length(path) <= 500 AND path ~ '^/[a-z0-9][a-z0-9/-]*$'
    )
);

CREATE TABLE organization_unit_properties (
    tenant_id uuid NOT NULL,
    organization_unit_id uuid NOT NULL,
    property_id uuid NOT NULL,
    is_primary boolean NOT NULL DEFAULT false,
    assigned_by_user_id uuid NOT NULL,
    assigned_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_unit_id, property_id),
    CONSTRAINT fk_organization_unit_property_unit FOREIGN KEY (
        tenant_id, organization_unit_id
    ) REFERENCES organization_units(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_organization_unit_property_property FOREIGN KEY (
        tenant_id, property_id
    ) REFERENCES properties(tenant_id, id) DEFERRABLE
);

CREATE TABLE portfolio_config_templates (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES tenants(id) DEFERRABLE,
    name text NOT NULL,
    config_domain varchar(40) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'draft',
    current_revision integer NOT NULL DEFAULT 0,
    created_by_user_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_portfolio_config_template_name UNIQUE (tenant_id, config_domain, name),
    CONSTRAINT uq_portfolio_config_template_tenant_id_id UNIQUE (tenant_id, id),
    CONSTRAINT chk_portfolio_config_domain CHECK (config_domain IN (
        'operations', 'security', 'finance', 'payments', 'fiscal', 'reporting',
        'communications', 'integrations'
    )),
    CONSTRAINT chk_portfolio_config_template_status CHECK (status IN (
        'draft', 'active', 'retired'
    )),
    CONSTRAINT chk_portfolio_config_template_revision CHECK (current_revision >= 0)
);

CREATE TABLE portfolio_config_revisions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    template_id uuid NOT NULL,
    revision integer NOT NULL,
    config_json jsonb NOT NULL,
    content_hash text NOT NULL,
    change_summary text NOT NULL,
    created_by_user_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_portfolio_config_revision_template FOREIGN KEY (tenant_id, template_id)
        REFERENCES portfolio_config_templates(tenant_id, id) DEFERRABLE,
    CONSTRAINT uq_portfolio_config_revision UNIQUE (template_id, revision),
    CONSTRAINT chk_portfolio_config_revision_positive CHECK (revision > 0),
    CONSTRAINT chk_portfolio_config_revision_hash CHECK (content_hash ~ '^[0-9a-f]{64}$')
);

CREATE TABLE portfolio_config_assignments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    template_id uuid NOT NULL,
    revision integer NOT NULL,
    organization_unit_id uuid,
    property_id uuid,
    status varchar(20) NOT NULL DEFAULT 'scheduled',
    override_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    previous_revision integer,
    scheduled_at timestamptz NOT NULL DEFAULT now(),
    applied_at timestamptz,
    applied_by_user_id uuid,
    error_detail text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_portfolio_config_assignment_template FOREIGN KEY (tenant_id, template_id)
        REFERENCES portfolio_config_templates(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_portfolio_config_assignment_unit FOREIGN KEY (tenant_id, organization_unit_id)
        REFERENCES organization_units(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_portfolio_config_assignment_property FOREIGN KEY (tenant_id, property_id)
        REFERENCES properties(tenant_id, id) DEFERRABLE,
    CONSTRAINT chk_portfolio_config_assignment_target CHECK (
        (organization_unit_id IS NOT NULL AND property_id IS NULL)
        OR (organization_unit_id IS NULL AND property_id IS NOT NULL)
    ),
    CONSTRAINT chk_portfolio_config_assignment_status CHECK (status IN (
        'scheduled', 'canary', 'applying', 'applied', 'failed', 'rolled_back'
    )),
    CONSTRAINT chk_portfolio_config_assignment_revision CHECK (
        revision > 0 AND (previous_revision IS NULL OR previous_revision > 0)
    )
);

CREATE INDEX idx_tenant_control_states_fleet
    ON tenant_control_states (lifecycle_status, service_status, subscription_status, updated_at DESC);
CREATE INDEX idx_tenant_workflows_tenant_created
    ON tenant_workflows (tenant_id, created_at DESC, id DESC);
CREATE INDEX idx_tenant_workflow_steps_workflow
    ON tenant_workflow_steps (workflow_id, sequence);
CREATE INDEX idx_tenant_subscriptions_tenant_period
    ON tenant_subscriptions (tenant_id, current_period_starts_at DESC);
CREATE INDEX idx_tenant_entitlement_overrides_effective
    ON tenant_entitlement_overrides (tenant_id, entitlement_code, starts_at DESC)
    WHERE revoked_at IS NULL;
CREATE INDEX idx_tenant_privacy_requests_due
    ON tenant_privacy_requests (status, due_at, tenant_id)
    WHERE status NOT IN ('completed', 'rejected', 'cancelled');
CREATE INDEX idx_tenant_legal_holds_active
    ON tenant_legal_holds (tenant_id, hold_scope, subject_reference)
    WHERE status = 'active';
CREATE INDEX idx_support_ticket_events_ticket
    ON support_ticket_events (ticket_id, occurred_at, id);
CREATE INDEX idx_platform_release_assignments_status
    ON platform_release_assignments (status, scheduled_at, tenant_id);
CREATE INDEX idx_organization_units_parent
    ON organization_units (tenant_id, parent_id, unit_type, name);
CREATE UNIQUE INDEX uq_organization_unit_properties_primary
    ON organization_unit_properties (tenant_id, property_id)
    WHERE is_primary;
CREATE INDEX idx_portfolio_config_assignments_target
    ON portfolio_config_assignments (tenant_id, organization_unit_id, property_id, status);

CREATE TRIGGER trg_tenant_control_states_updated_at
    BEFORE UPDATE ON tenant_control_states FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_tenant_workflows_updated_at
    BEFORE UPDATE ON tenant_workflows FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_tenant_workflow_steps_updated_at
    BEFORE UPDATE ON tenant_workflow_steps FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_tenant_subscriptions_updated_at
    BEFORE UPDATE ON tenant_subscriptions FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_tenant_privacy_requests_updated_at
    BEFORE UPDATE ON tenant_privacy_requests FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_tenant_identity_connections_updated_at
    BEFORE UPDATE ON tenant_identity_connections FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_platform_release_assignments_updated_at
    BEFORE UPDATE ON platform_release_assignments FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_organization_units_updated_at
    BEFORE UPDATE ON organization_units FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_portfolio_config_templates_updated_at
    BEFORE UPDATE ON portfolio_config_templates FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_portfolio_config_assignments_updated_at
    BEFORE UPDATE ON portfolio_config_assignments FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE OR REPLACE FUNCTION guard_platform_control_append_only()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% records are append-only', TG_TABLE_NAME;
END;
$$;

CREATE TRIGGER trg_tenant_workflow_steps_no_delete
    BEFORE DELETE ON tenant_workflow_steps
    FOR EACH ROW EXECUTE FUNCTION guard_platform_control_append_only();
CREATE TRIGGER trg_support_ticket_events_append_only
    BEFORE UPDATE OR DELETE ON support_ticket_events
    FOR EACH ROW EXECUTE FUNCTION guard_platform_control_append_only();
CREATE TRIGGER trg_portfolio_config_revisions_append_only
    BEFORE UPDATE OR DELETE ON portfolio_config_revisions
    FOR EACH ROW EXECUTE FUNCTION guard_platform_control_append_only();

INSERT INTO tenant_control_states (
    tenant_id, lifecycle_status, verification_status, provisioning_status,
    subscription_status, service_status, offboarding_status
)
SELECT
    tenant.id,
    CASE tenant.status
        WHEN 'active' THEN 'active'
        WHEN 'trial' THEN 'trial'
        WHEN 'suspended' THEN 'suspended'
        WHEN 'frozen' THEN 'frozen'
        WHEN 'archived' THEN 'archived'
        WHEN 'terminated' THEN 'terminated'
        ELSE 'cancelled'
    END,
    CASE
        WHEN profile.verification_status IN ('approved', 'verified') THEN 'verified'
        WHEN profile.verification_status = 'pending' THEN 'pending'
        WHEN profile.verification_status = 'rejected' THEN 'rejected'
        WHEN profile.verification_status = 'suspended' THEN 'suspended'
        WHEN profile.verification_status = 'expired' THEN 'expired'
        ELSE 'unverified'
    END,
    CASE WHEN tenant.status IN ('active', 'trial') THEN 'ready' ELSE 'pending' END,
    CASE WHEN tenant.status = 'trial' THEN 'trialing'
         WHEN tenant.status = 'active' THEN 'active'
         WHEN tenant.status IN ('cancelled', 'terminated') THEN 'cancelled'
         ELSE 'paused' END,
    'operational',
    CASE WHEN tenant.status IN ('archived', 'terminated', 'cancelled') THEN 'completed'
         ELSE 'none' END
FROM tenants tenant
LEFT JOIN tenant_profiles profile ON profile.tenant_id = tenant.id
WHERE tenant.deleted_at IS NULL
ON CONFLICT (tenant_id) DO NOTHING;

INSERT INTO tenant_subscriptions (
    tenant_id, plan_id, status, billing_cycle, billing_currency,
    current_period_starts_at, current_period_ends_at, trial_ends_at
)
SELECT
    tenant.id,
    tenant.plan_id,
    CASE WHEN tenant.status = 'trial' THEN 'trialing' ELSE 'active' END,
    CASE WHEN tenant.billing_cycle = 'annually' THEN 'annually' ELSE 'monthly' END,
    COALESCE(tenant.currency_code, 'TZS'),
    COALESCE(tenant.subscription_starts_at, tenant.created_at),
    tenant.subscription_ends_at,
    tenant.trial_ends_at
FROM tenants tenant
WHERE tenant.deleted_at IS NULL
  AND tenant.status IN ('trial', 'active')
ON CONFLICT DO NOTHING;

-- Seed meaningful plan entitlements. Missing entitlement rows remain denied by the
-- effective-entitlement resolver; every currently active tenant module is added so
-- this migration preserves existing access.
INSERT INTO plan_entitlements (plan_id, entitlement_code, entitlement_value, is_enabled)
SELECT DISTINCT
    plan.id,
    'module.' || module.module_id,
    jsonb_build_object('moduleId', module.module_id),
    true
FROM plans plan
CROSS JOIN module_catalog module
WHERE plan.is_active = true
  AND module.launch_status = 'active'
  AND module.access_scope IN ('tenant', 'property', 'both')
ON CONFLICT (plan_id, entitlement_code) DO NOTHING;

INSERT INTO plan_entitlements (plan_id, entitlement_code, entitlement_value, is_enabled)
SELECT plan.id, entitlement.code, entitlement.value, true
FROM plans plan
CROSS JOIN LATERAL (
    VALUES
        ('limit.properties', jsonb_build_object('limit', plan.max_properties)),
        ('limit.rooms', jsonb_build_object('limit', plan.max_rooms)),
        ('limit.users', jsonb_build_object('limit', plan.max_users)),
        ('limit.outlets', jsonb_build_object('limit', plan.max_outlets))
) AS entitlement(code, value)
WHERE plan.is_active = true
ON CONFLICT (plan_id, entitlement_code) DO UPDATE SET
    entitlement_value = EXCLUDED.entitlement_value,
    is_enabled = true,
    updated_at = now();

CREATE OR REPLACE FUNCTION effective_tenant_entitlement(
    p_tenant_id uuid,
    p_entitlement_code text
) RETURNS TABLE (
    is_enabled boolean,
    entitlement_value jsonb,
    source text
)
LANGUAGE plpgsql STABLE SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    IF p_tenant_id IS NULL OR NULLIF(btrim(p_entitlement_code), '') IS NULL THEN
        RAISE EXCEPTION 'Tenant id and entitlement code are required';
    END IF;
    IF p_tenant_id IS DISTINCT FROM current_tenant_id()
       AND NOT platform_user_has_permission(current_platform_user_id(), 'platform.billing.manage')
       AND NOT platform_user_has_permission(current_platform_user_id(), 'platform.tenants.manage') THEN
        RAISE EXCEPTION 'Tenant entitlement access is not authorized';
    END IF;

    RETURN QUERY
    WITH current_subscription AS (
        SELECT COALESCE(
            (
                SELECT subscription.plan_id
                FROM tenant_subscriptions subscription
                WHERE subscription.tenant_id = p_tenant_id
                  AND subscription.status IN ('trialing', 'active', 'past_due', 'paused')
                ORDER BY subscription.created_at DESC
                LIMIT 1
            ),
            tenant.plan_id
        ) AS plan_id
        FROM tenants tenant
        WHERE tenant.id = p_tenant_id AND tenant.deleted_at IS NULL
    ), effective_override AS (
        SELECT override.is_enabled, override.entitlement_value,
               'override'::text AS source
        FROM tenant_entitlement_overrides override
        WHERE override.tenant_id = p_tenant_id
          AND override.entitlement_code = lower(btrim(p_entitlement_code))
          AND override.revoked_at IS NULL
          AND override.starts_at <= now()
          AND (override.expires_at IS NULL OR override.expires_at > now())
        ORDER BY override.starts_at DESC, override.created_at DESC
        LIMIT 1
    ), plan_value AS (
        SELECT COALESCE(entitlement.is_enabled, true) AS is_enabled,
               COALESCE(
                   entitlement.entitlement_value,
                   CASE lower(btrim(p_entitlement_code))
                       WHEN 'limit.properties' THEN jsonb_build_object('limit', plan.max_properties)
                       WHEN 'limit.rooms' THEN jsonb_build_object('limit', plan.max_rooms)
                       WHEN 'limit.users' THEN jsonb_build_object('limit', plan.max_users)
                       WHEN 'limit.outlets' THEN jsonb_build_object('limit', plan.max_outlets)
                   END
               ) AS entitlement_value,
               'plan'::text AS source
        FROM current_subscription subscription
        JOIN plans plan ON plan.id = subscription.plan_id AND plan.is_active
        LEFT JOIN plan_entitlements entitlement
          ON entitlement.plan_id = subscription.plan_id
         AND entitlement.entitlement_code = lower(btrim(p_entitlement_code))
        WHERE entitlement.id IS NOT NULL
           OR lower(btrim(p_entitlement_code)) IN (
               'limit.properties', 'limit.rooms', 'limit.users', 'limit.outlets'
           )
    )
    SELECT * FROM effective_override
    UNION ALL
    SELECT * FROM plan_value
    WHERE NOT EXISTS (SELECT 1 FROM effective_override)
    LIMIT 1;
END;
$$;

CREATE OR REPLACE FUNCTION assert_tenant_entitlement_enabled(
    p_tenant_id uuid,
    p_entitlement_code text
) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_entitlement record;
BEGIN
    SELECT * INTO v_entitlement
    FROM effective_tenant_entitlement(p_tenant_id, p_entitlement_code);
    IF NOT FOUND OR v_entitlement.is_enabled IS DISTINCT FROM true THEN
        RAISE EXCEPTION 'Tenant is not entitled to %', p_entitlement_code;
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION assert_tenant_capacity(
    p_tenant_id uuid,
    p_entitlement_code text
) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_entitlement record;
    v_limit bigint;
    v_usage bigint;
BEGIN
    PERFORM pg_advisory_xact_lock(
        hashtextextended(p_tenant_id::text || ':' || lower(btrim(p_entitlement_code)), 0)
    );
    SELECT * INTO v_entitlement
    FROM effective_tenant_entitlement(p_tenant_id, p_entitlement_code);
    IF NOT FOUND OR v_entitlement.is_enabled IS DISTINCT FROM true THEN
        RAISE EXCEPTION 'Tenant capacity % is not enabled', p_entitlement_code;
    END IF;
    v_limit := NULLIF(v_entitlement.entitlement_value ->> 'limit', '')::bigint;
    IF v_limit IS NULL OR v_limit < 0 THEN
        RAISE EXCEPTION 'Tenant capacity % has no valid limit', p_entitlement_code;
    END IF;

    CASE lower(btrim(p_entitlement_code))
        WHEN 'limit.properties' THEN
            SELECT count(*) INTO v_usage FROM properties
            WHERE tenant_id = p_tenant_id AND deleted_at IS NULL;
        WHEN 'limit.rooms' THEN
            SELECT count(*) INTO v_usage FROM rooms
            WHERE tenant_id = p_tenant_id AND deleted_at IS NULL;
        WHEN 'limit.users' THEN
            SELECT
                (SELECT count(*) FROM users
                 WHERE tenant_id = p_tenant_id AND deleted_at IS NULL)
                +
                (SELECT count(*) FROM tenant_user_invitations
                 WHERE tenant_id = p_tenant_id
                   AND accepted_at IS NULL AND revoked_at IS NULL AND expires_at > now())
            INTO v_usage;
        WHEN 'limit.outlets' THEN
            SELECT count(*) INTO v_usage FROM outlets
            WHERE tenant_id = p_tenant_id AND deleted_at IS NULL;
        ELSE
            RAISE EXCEPTION 'Unsupported tenant capacity %', p_entitlement_code;
    END CASE;

    IF v_usage >= v_limit THEN
        RAISE EXCEPTION 'Tenant capacity % is exhausted (% of %)',
            p_entitlement_code, v_usage, v_limit;
    END IF;
END;
$$;

REVOKE EXECUTE ON FUNCTION effective_tenant_entitlement(uuid, text) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION assert_tenant_entitlement_enabled(uuid, text) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION assert_tenant_capacity(uuid, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION effective_tenant_entitlement(uuid, text)
    TO pms_app, pms_platform, pms_worker, pms_readonly_support;
GRANT EXECUTE ON FUNCTION assert_tenant_entitlement_enabled(uuid, text)
    TO pms_app, pms_platform;
GRANT EXECUTE ON FUNCTION assert_tenant_capacity(uuid, text)
    TO pms_app, pms_platform;

-- RLS -------------------------------------------------------------------------

DROP POLICY IF EXISTS outbox_platform_request ON outbox_events;
CREATE POLICY outbox_platform_request ON outbox_events
    FOR ALL TO pms_platform
    USING (
        current_platform_user_id() IS NOT NULL
        AND (
            destination = 'platform'
            OR (destination = 'email' AND aggregate_type = 'tenant_user_invitations')
        )
    )
    WITH CHECK (
        current_platform_user_id() IS NOT NULL
        AND (
            destination = 'platform'
            OR (destination = 'email' AND aggregate_type = 'tenant_user_invitations')
        )
    );

ALTER TABLE tenant_control_states ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_control_states FORCE ROW LEVEL SECURITY;
ALTER TABLE tenant_workflows ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_workflows FORCE ROW LEVEL SECURITY;
ALTER TABLE tenant_workflow_steps ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_workflow_steps FORCE ROW LEVEL SECURITY;
ALTER TABLE tenant_subscriptions ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_subscriptions FORCE ROW LEVEL SECURITY;
ALTER TABLE tenant_entitlement_overrides ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_entitlement_overrides FORCE ROW LEVEL SECURITY;
ALTER TABLE tenant_privacy_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_privacy_requests FORCE ROW LEVEL SECURITY;
ALTER TABLE tenant_legal_holds ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_legal_holds FORCE ROW LEVEL SECURITY;
ALTER TABLE tenant_identity_connections ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_identity_connections FORCE ROW LEVEL SECURITY;
ALTER TABLE support_ticket_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_releases ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_release_assignments ENABLE ROW LEVEL SECURITY;
ALTER TABLE organization_units ENABLE ROW LEVEL SECURITY;
ALTER TABLE organization_units FORCE ROW LEVEL SECURITY;
ALTER TABLE organization_unit_properties ENABLE ROW LEVEL SECURITY;
ALTER TABLE organization_unit_properties FORCE ROW LEVEL SECURITY;
ALTER TABLE portfolio_config_templates ENABLE ROW LEVEL SECURITY;
ALTER TABLE portfolio_config_templates FORCE ROW LEVEL SECURITY;
ALTER TABLE portfolio_config_revisions ENABLE ROW LEVEL SECURITY;
ALTER TABLE portfolio_config_revisions FORCE ROW LEVEL SECURITY;
ALTER TABLE portfolio_config_assignments ENABLE ROW LEVEL SECURITY;
ALTER TABLE portfolio_config_assignments FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_or_platform_control ON tenant_control_states
    USING (tenant_id = current_tenant_id()
        OR platform_user_has_permission(current_platform_user_id(), 'platform.tenants.view'))
    WITH CHECK (tenant_id = current_tenant_id()
        OR platform_user_has_permission(current_platform_user_id(), 'platform.tenants.manage'));
CREATE POLICY tenant_or_platform_workflows ON tenant_workflows
    USING (tenant_id = current_tenant_id()
        OR platform_user_has_permission(current_platform_user_id(), 'platform.tenants.view'))
    WITH CHECK (tenant_id = current_tenant_id()
        OR platform_user_has_permission(current_platform_user_id(), 'platform.tenants.manage'));
CREATE POLICY tenant_or_platform_workflow_steps ON tenant_workflow_steps
    USING (tenant_id = current_tenant_id()
        OR platform_user_has_permission(current_platform_user_id(), 'platform.tenants.view'))
    WITH CHECK (tenant_id = current_tenant_id()
        OR platform_user_has_permission(current_platform_user_id(), 'platform.tenants.manage'));
CREATE POLICY tenant_or_platform_subscriptions ON tenant_subscriptions
    USING (tenant_id = current_tenant_id()
        OR platform_user_has_permission(current_platform_user_id(), 'platform.billing.manage')
        OR platform_user_has_permission(current_platform_user_id(), 'platform.tenants.manage'))
    WITH CHECK (
        platform_user_has_permission(current_platform_user_id(), 'platform.billing.manage')
        OR platform_user_has_permission(current_platform_user_id(), 'platform.tenants.manage')
    );
CREATE POLICY tenant_or_platform_entitlement_overrides ON tenant_entitlement_overrides
    USING (tenant_id = current_tenant_id()
        OR platform_user_has_permission(current_platform_user_id(), 'platform.billing.manage'))
    WITH CHECK (platform_user_has_permission(current_platform_user_id(), 'platform.billing.manage'));
CREATE POLICY tenant_or_platform_privacy ON tenant_privacy_requests
    USING (tenant_id = current_tenant_id()
        OR platform_user_has_permission(current_platform_user_id(), 'platform.privacy.view'))
    WITH CHECK (tenant_id = current_tenant_id()
        OR platform_user_has_permission(current_platform_user_id(), 'platform.privacy.manage'));
CREATE POLICY platform_legal_holds ON tenant_legal_holds
    USING (platform_user_has_permission(current_platform_user_id(), 'platform.privacy.view'))
    WITH CHECK (platform_user_has_permission(current_platform_user_id(), 'platform.privacy.manage'));
CREATE POLICY tenant_or_platform_identity ON tenant_identity_connections
    USING (tenant_id = current_tenant_id()
        OR platform_user_has_permission(current_platform_user_id(), 'platform.identity.manage'))
    WITH CHECK (tenant_id = current_tenant_id()
        OR platform_user_has_permission(current_platform_user_id(), 'platform.identity.manage'));
CREATE POLICY tenant_or_platform_support_ticket_events ON support_ticket_events
    USING (
        platform_user_has_permission(current_platform_user_id(), 'platform.support.view')
        OR EXISTS (
            SELECT 1 FROM support_tickets ticket
            WHERE ticket.id = support_ticket_events.ticket_id
              AND ticket.tenant_id = current_tenant_id()
        )
    )
    WITH CHECK (
        platform_user_has_permission(current_platform_user_id(), 'platform.support.manage')
        OR EXISTS (
            SELECT 1 FROM support_tickets ticket
            WHERE ticket.id = support_ticket_events.ticket_id
              AND ticket.tenant_id = current_tenant_id()
        )
    );
CREATE POLICY platform_releases_access ON platform_releases
    USING (platform_user_has_permission(current_platform_user_id(), 'platform.releases.view'))
    WITH CHECK (platform_user_has_permission(current_platform_user_id(), 'platform.releases.manage'));
CREATE POLICY platform_release_assignments_access ON platform_release_assignments
    USING (platform_user_has_permission(current_platform_user_id(), 'platform.releases.view'))
    WITH CHECK (platform_user_has_permission(current_platform_user_id(), 'platform.releases.manage'));
DROP POLICY IF EXISTS tenant_or_platform ON feature_flags;
CREATE POLICY tenant_or_platform_features ON feature_flags
    USING (
        tenant_id IS NULL
        OR tenant_id = current_tenant_id()
        OR platform_user_has_permission(current_platform_user_id(), 'platform.features.view')
        OR platform_user_has_permission(current_platform_user_id(), 'platform.features.manage')
    )
    WITH CHECK (
        (tenant_id IS NULL AND current_tenant_id() IS NOT NULL)
        OR tenant_id = current_tenant_id()
        OR platform_user_has_permission(current_platform_user_id(), 'platform.features.manage')
    );
CREATE POLICY platform_control_read ON properties
    FOR SELECT TO pms_platform
    USING (
        platform_user_has_permission(current_platform_user_id(), 'platform.tenants.view')
        OR platform_user_has_permission(current_platform_user_id(), 'platform.billing.manage')
        OR platform_user_has_permission(current_platform_user_id(), 'platform.features.view')
        OR platform_user_has_permission(current_platform_user_id(), 'platform.features.manage')
        OR platform_user_has_permission(current_platform_user_id(), 'platform.support.view')
    );
CREATE POLICY platform_control_read ON rooms
    FOR SELECT TO pms_platform
    USING (platform_user_has_permission(current_platform_user_id(), 'platform.billing.manage'));
CREATE POLICY platform_control_read ON outlets
    FOR SELECT TO pms_platform
    USING (platform_user_has_permission(current_platform_user_id(), 'platform.billing.manage'));
CREATE POLICY platform_control_read ON users
    FOR SELECT TO pms_platform
    USING (
        platform_user_has_permission(current_platform_user_id(), 'platform.billing.manage')
        OR platform_user_has_permission(current_platform_user_id(), 'platform.privacy.manage')
        OR platform_user_has_permission(current_platform_user_id(), 'platform.security.manage')
    );
CREATE POLICY platform_control_read ON tenant_roles
    FOR SELECT TO pms_platform
    USING (platform_user_has_permission(current_platform_user_id(), 'platform.security.manage'));
CREATE POLICY platform_control_read ON stays
    FOR SELECT TO pms_platform
    USING (platform_user_has_permission(current_platform_user_id(), 'platform.tenants.manage'));
CREATE POLICY platform_control_read ON guests
    FOR SELECT TO pms_platform
    USING (platform_user_has_permission(current_platform_user_id(), 'platform.privacy.manage'));
CREATE POLICY platform_control_read ON audit_logs
    FOR SELECT TO pms_platform
    USING (platform_user_has_permission(current_platform_user_id(), 'platform.billing.manage'));
CREATE POLICY tenant_portfolio_units ON organization_units
    USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());
CREATE POLICY tenant_portfolio_properties ON organization_unit_properties
    USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());
CREATE POLICY tenant_portfolio_templates ON portfolio_config_templates
    USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());
CREATE POLICY tenant_portfolio_revisions ON portfolio_config_revisions
    USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());
CREATE POLICY tenant_portfolio_assignments ON portfolio_config_assignments
    USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());

-- Runtime grants ----------------------------------------------------------------

GRANT SELECT, INSERT, UPDATE ON tenant_control_states, tenant_workflows,
    tenant_workflow_steps, tenant_subscriptions, tenant_entitlement_overrides,
    tenant_privacy_requests, tenant_legal_holds, tenant_identity_connections
    TO pms_platform;
GRANT SELECT ON tenant_control_states, tenant_workflows, tenant_workflow_steps,
    tenant_subscriptions, tenant_entitlement_overrides, tenant_privacy_requests,
    tenant_identity_connections TO pms_app;
GRANT INSERT, UPDATE ON tenant_privacy_requests, tenant_identity_connections TO pms_app;
GRANT SELECT ON tenant_control_states, tenant_workflows, tenant_workflow_steps,
    tenant_subscriptions, tenant_entitlement_overrides, tenant_privacy_requests,
    tenant_legal_holds, tenant_identity_connections TO pms_worker, pms_readonly_support;

GRANT SELECT, INSERT ON support_ticket_events TO pms_app, pms_platform;
GRANT SELECT, INSERT, UPDATE ON platform_releases, platform_release_assignments TO pms_platform;
GRANT SELECT, INSERT, UPDATE ON feature_flags, platform_services, platform_jobs,
    platform_job_runs, platform_alerts, platform_incidents TO pms_platform;
GRANT SELECT, INSERT ON service_health_checks TO pms_platform;
GRANT SELECT ON worker_runtime_heartbeats TO pms_platform;
GRANT SELECT ON properties, rooms, outlets, users, tenant_roles, stays, guests,
    audit_logs TO pms_platform;
GRANT SELECT ON support_ticket_events, platform_releases, platform_release_assignments
    TO pms_worker, pms_readonly_support;

GRANT SELECT, INSERT, UPDATE ON organization_units, organization_unit_properties,
    portfolio_config_templates, portfolio_config_assignments TO pms_app;
GRANT SELECT, INSERT ON portfolio_config_revisions TO pms_app;
GRANT SELECT ON organization_units, organization_unit_properties,
    portfolio_config_templates, portfolio_config_revisions, portfolio_config_assignments
    TO pms_worker, pms_readonly_support;

GRANT SELECT, INSERT, UPDATE ON platform_break_glass_access, support_tickets,
    support_ticket_notes TO pms_platform;
GRANT SELECT ON platform_break_glass_access, support_tickets, support_ticket_notes
    TO pms_readonly_support;
GRANT SELECT, INSERT, UPDATE ON plans, plan_entitlements, tenant_usage_snapshots TO pms_platform;
GRANT SELECT ON plans, plan_entitlements, tenant_usage_snapshots TO pms_app, pms_worker,
    pms_readonly_support;
GRANT SELECT, INSERT, UPDATE ON tenant_verification_cases,
    tenant_verification_documents TO pms_app, pms_platform;
GRANT SELECT ON tenant_verification_cases, tenant_verification_documents
    TO pms_worker, pms_readonly_support;

REVOKE DELETE ON tenant_control_states, tenant_workflows, tenant_workflow_steps,
    tenant_subscriptions, tenant_entitlement_overrides, tenant_privacy_requests,
    tenant_legal_holds, tenant_identity_connections, support_ticket_events,
    platform_releases, platform_release_assignments, organization_units,
    organization_unit_properties, portfolio_config_templates,
    portfolio_config_revisions, portfolio_config_assignments
    FROM pms_app, pms_platform, pms_worker;

-- Permissions -------------------------------------------------------------------

INSERT INTO permission_catalog (
    code, namespace, access_scope, description,
    is_platform_permission, is_tenant_permission
) VALUES
    ('platform.privacy.view', 'platform', 'platform',
     'View tenant privacy requests and legal holds', true, false),
    ('platform.privacy.manage', 'platform', 'platform',
     'Manage tenant privacy requests, exports and legal holds', true, false),
    ('platform.identity.manage', 'platform', 'platform',
     'Verify and manage tenant enterprise identity connections', true, false),
    ('platform.releases.view', 'platform', 'platform',
     'View platform releases and tenant rollout state', true, false),
    ('platform.releases.manage', 'platform', 'platform',
     'Approve, roll out, pause and recall platform releases', true, false),
    ('platform.features.view', 'platform', 'platform',
     'View effective feature flags and rollout rules', true, false),
    ('platform.features.manage', 'platform', 'platform',
     'Manage tenant and property feature rollout rules', true, false),
    ('tenant.subscription.view', 'tenant', 'tenant',
     'View subscription, entitlements and usage', false, true),
    ('tenant.privacy.manage', 'tenant', 'tenant',
     'Submit and track privacy rights requests', false, true),
    ('tenant.identity.manage', 'tenant', 'tenant',
     'Configure enterprise identity and SCIM connections', false, true),
    ('tenant.support.manage', 'tenant', 'tenant',
     'Open and manage tenant support requests', false, true),
    ('tenant.portfolio.view', 'tenant', 'tenant',
     'View hospitality portfolio hierarchy and inherited configuration', false, true),
    ('tenant.portfolio.manage', 'tenant', 'tenant',
     'Manage portfolio groups, templates and controlled rollouts', false, true)
ON CONFLICT (code) DO UPDATE SET
    namespace = EXCLUDED.namespace,
    access_scope = EXCLUDED.access_scope,
    description = EXCLUDED.description,
    is_platform_permission = EXCLUDED.is_platform_permission,
    is_tenant_permission = EXCLUDED.is_tenant_permission,
    updated_at = now();

INSERT INTO platform_permissions (code, namespace, description) VALUES
    ('platform.privacy.view', 'security', 'View tenant privacy requests and legal holds'),
    ('platform.privacy.manage', 'security', 'Manage tenant privacy requests and legal holds'),
    ('platform.identity.manage', 'security', 'Manage tenant identity connections'),
    ('platform.releases.view', 'monitoring', 'View platform release state'),
    ('platform.releases.manage', 'monitoring', 'Manage platform releases'),
    ('platform.features.view', 'monitoring', 'View feature rollout state'),
    ('platform.features.manage', 'monitoring', 'Manage feature rollouts')
ON CONFLICT (code) DO UPDATE SET
    namespace = EXCLUDED.namespace,
    description = EXCLUDED.description,
    updated_at = now();

INSERT INTO platform_role_permissions (platform_role_id, platform_permission_id)
SELECT role.id, permission.id
FROM platform_roles role
CROSS JOIN platform_permissions permission
WHERE role.code = 'platform_root'
  AND role.is_system = true
  AND role.is_active = true
  AND permission.code IN (
      'platform.privacy.view', 'platform.privacy.manage', 'platform.identity.manage',
      'platform.releases.view', 'platform.releases.manage',
      'platform.features.view', 'platform.features.manage'
  )
ON CONFLICT ON CONSTRAINT platform_role_permissions_pkey DO NOTHING;

INSERT INTO permissions (id, tenant_id, code, description)
SELECT gen_random_uuid(), tenant.id, catalog.code, catalog.description
FROM tenants tenant
JOIN permission_catalog catalog ON catalog.code IN (
    'tenant.subscription.view', 'tenant.privacy.manage', 'tenant.identity.manage',
    'tenant.support.manage', 'tenant.portfolio.view', 'tenant.portfolio.manage'
)
WHERE tenant.deleted_at IS NULL
ON CONFLICT (tenant_id, code) DO UPDATE SET
    description = EXCLUDED.description,
    updated_at = now();

INSERT INTO tenant_role_permissions (tenant_role_id, permission_id)
SELECT role.id, permission.id
FROM tenant_roles role
JOIN permissions permission ON permission.tenant_id = role.tenant_id
WHERE role.code = 'tenant_admin'
  AND role.is_system = true
  AND role.is_active = true
  AND permission.code IN (
      'tenant.subscription.view', 'tenant.privacy.manage', 'tenant.identity.manage',
      'tenant.support.manage', 'tenant.portfolio.view', 'tenant.portfolio.manage'
  )
ON CONFLICT ON CONSTRAINT tenant_role_permissions_pkey DO NOTHING;

-- API route contracts -----------------------------------------------------------

DELETE FROM module_access_matrix
WHERE module_id = 'platform_admin'
  AND screen_key = 'platform.monitoring'
  AND http_method = 'ANY'
  AND api_pattern = '/api/platform/monitoring*';

INSERT INTO module_access_matrix (
    module_id, screen_key, screen_label, http_method, api_pattern,
    permission_code, route_scope, guard_mode, access_scope,
    is_tanzania_v1, is_enabled_by_default, notes
) VALUES
    ('platform_admin', 'platform.tenants.catalog', 'Tenant Catalog', 'GET',
     '/api/platform/tenants', 'platform.tenants.view', 'platform',
     'platform_permission', 'platform', true, true,
     'Cursor-paginated tenant catalog'),
    ('platform_admin', 'platform.tenants.control', 'Tenant Control', 'ANY',
     '/api/platform/tenants/:tenantId/control*', 'platform.tenants.manage', 'platform',
     'platform_permission', 'platform', true, true,
     'Tenant 360, lifecycle workflows and offboarding'),
    ('platform_admin', 'platform.plans', 'Plans and Entitlements', 'ANY',
     '/api/platform/plans*', 'platform.billing.manage', 'platform',
     'platform_permission', 'platform', true, true,
     'Product plans and plan entitlement definitions'),
    ('platform_admin', 'platform.tenant_commercial', 'Tenant Commercial Control', 'ANY',
     '/api/platform/tenants/:tenantId/commercial*', 'platform.billing.manage', 'platform',
     'platform_permission', 'platform', true, true,
     'Subscription, entitlement overrides and usage'),
    ('platform_admin', 'platform.tenant_admin_invitation', 'Tenant Administrator Invitation', 'POST',
     '/api/platform/tenants/:tenantId/administrator-invitations', 'platform.security.manage', 'platform',
     'platform_permission', 'platform', true, true,
     'Invite the initial tenant administrator without pre-provisioned OIDC subject'),
    ('platform_admin', 'platform.tenant_privacy', 'Tenant Privacy Control', 'ANY',
     '/api/platform/tenants/:tenantId/privacy*', 'platform.privacy.manage', 'platform',
     'platform_permission', 'platform', true, true,
     'Privacy requests, exports and legal holds'),
    ('platform_admin', 'platform.tenant_identity', 'Tenant Identity Control', 'ANY',
     '/api/platform/tenants/:tenantId/identity-connections*', 'platform.identity.manage', 'platform',
     'platform_permission', 'platform', true, true,
     'Enterprise SSO, domain and SCIM verification'),
    ('platform_admin', 'platform.tenant_verification', 'Tenant Verification', 'ANY',
     '/api/platform/tenants/:tenantId/verification-cases*', 'platform.tenants.verification.manage',
     'platform', 'platform_permission', 'platform', true, true,
     'Business verification case review and evidence decisions'),
    ('platform_admin', 'platform.tenant_legal_holds', 'Tenant Legal Holds', 'ANY',
     '/api/platform/tenants/:tenantId/legal-holds*', 'platform.privacy.manage', 'platform',
     'platform_permission', 'platform', true, true,
     'Create and release retention-preserving legal holds'),
    ('platform_admin', 'platform.releases.view', 'View Platform Releases', 'GET',
     '/api/platform/releases*', 'platform.releases.view', 'platform',
     'platform_permission', 'platform', true, true,
     'View immutable releases and rollout assignments'),
    ('platform_admin', 'platform.releases', 'Platform Releases', 'ANY',
     '/api/platform/releases*', 'platform.releases.manage', 'platform',
     'platform_permission', 'platform', true, true,
     'Approve and roll out immutable releases'),
    ('platform_admin', 'platform.features.view', 'View Feature Rollouts', 'GET',
     '/api/platform/features*', 'platform.features.view', 'platform',
     'platform_permission', 'platform', true, true,
     'View effective feature flags and rollout rules'),
    ('platform_admin', 'platform.features', 'Feature Rollouts', 'ANY',
     '/api/platform/features*', 'platform.features.manage', 'platform',
     'platform_permission', 'platform', true, true,
     'Versioned tenant and property feature rollout control'),
    ('platform_admin', 'platform.monitoring.view', 'View Fleet Operations', 'GET',
     '/api/platform/monitoring*', 'platform.monitoring.view', 'platform',
     'platform_permission', 'platform', true, true,
     'View services, health, jobs, alerts and incidents'),
    ('platform_admin', 'platform.monitoring', 'Fleet Operations', 'ANY',
     '/api/platform/monitoring*', 'platform.monitoring.manage', 'platform',
     'platform_permission', 'platform', true, true,
     'Services, health, jobs, alerts, incidents and maintenance'),
    ('platform_admin', 'platform.support', 'Platform Support', 'ANY',
     '/api/platform/support*', 'platform.support.manage', 'platform',
     'platform_permission', 'platform', true, true,
     'Support tickets and approved just-in-time access'),
    ('tenant_admin', 'tenant.subscription', 'Subscription and Usage', 'GET',
     '/api/tenants/:tenantId/commercial*', 'tenant.subscription.view', 'tenant',
     'staff_permission', 'tenant', true, true,
     'Tenant subscription, effective entitlements and usage'),
    ('tenant_admin', 'tenant.privacy', 'Privacy Requests', 'ANY',
     '/api/tenants/:tenantId/privacy*', 'tenant.privacy.manage', 'tenant',
     'staff_permission', 'tenant', true, true,
     'Submit and track privacy requests'),
    ('tenant_admin', 'tenant.identity', 'Enterprise Identity', 'ANY',
     '/api/tenants/:tenantId/identity-connections*', 'tenant.identity.manage', 'tenant',
     'staff_permission', 'tenant', true, true,
     'Configure enterprise identity connections'),
    ('tenant_admin', 'tenant.verification', 'Business Verification', 'ANY',
     '/api/tenants/:tenantId/verification-cases*', 'tenant.profile.manage', 'tenant',
     'staff_permission', 'tenant', true, true,
     'Submit business verification cases and evidence'),
    ('tenant_admin', 'tenant.support', 'Support', 'ANY',
     '/api/tenants/:tenantId/support*', 'tenant.support.manage', 'tenant',
     'staff_permission', 'tenant', true, true,
     'Open and manage support requests'),
    ('tenant_admin', 'tenant.portfolio', 'Portfolio Control', 'ANY',
     '/api/tenants/:tenantId/portfolio*', 'tenant.portfolio.manage', 'tenant',
     'staff_permission', 'tenant', true, true,
     'Portfolio hierarchy, templates, bulk rollout and rollback')
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

COMMENT ON TABLE tenant_control_states IS
    'Desired and actual SaaS control state kept separate from the compatible tenants.status field.';
COMMENT ON TABLE tenant_workflows IS
    'Durable tenant lifecycle workflows with explicit human waits and recovery state.';
COMMENT ON TABLE tenant_subscriptions IS
    'Commercial subscription state; never used to abruptly stop in-house hotel continuity workflows.';
COMMENT ON TABLE tenant_entitlement_overrides IS
    'Time-bound, reasoned and approved exceptions to plan entitlements.';
COMMENT ON TABLE support_ticket_events IS
    'Immutable support and privileged-access evidence timeline.';
COMMENT ON TABLE platform_releases IS
    'Immutable image/schema release approved before channel or tenant rollout.';
COMMENT ON TABLE organization_units IS
    'Tenant-owned portfolio, brand, region, hub and management-group hierarchy.';
