-- V100 — letting an operator resolve a payment Peak could not.
--
-- Two things. The second only works because of the first.

-- -----------------------------------------------------------------------------
-- 1. The admin views were unreadable by the people they were built for.
--
-- V99 added peak_payments_requiring_reconciliation and
-- peak_tenant_commercial_standing and granted SELECT to pms_platform. But the
-- underlying tables carry only tenant_isolation, keyed on current_tenant_id(),
-- and a platform session has no tenant. A view runs with the invoker's
-- permissions, so every one of those queries would have returned zero rows in
-- production while looking perfectly healthy.
--
-- The tests did not catch it because the test connection is a superuser and
-- bypasses row-level security entirely. Same trap as the worker sweeps in V94,
-- walked into again from the read side.
--
-- The convention already exists in V71: tenant_or_platform_* policies gated on a
-- platform permission, so a platform user sees across tenants only if they hold
-- the right to.
-- -----------------------------------------------------------------------------
CREATE POLICY tenant_or_platform_purchases ON peak_purchases
    FOR SELECT
    USING (
        tenant_id = current_tenant_id()
        OR platform_user_has_permission(current_platform_user_id(), 'platform.billing.view')
    );

CREATE POLICY tenant_or_platform_payment_attempts ON peak_payment_attempts
    FOR SELECT
    USING (
        tenant_id = current_tenant_id()
        OR platform_user_has_permission(current_platform_user_id(), 'platform.billing.view')
    );

CREATE POLICY tenant_or_platform_receipts ON peak_receipts
    FOR SELECT
    USING (
        tenant_id = current_tenant_id()
        OR platform_user_has_permission(current_platform_user_id(), 'platform.billing.view')
    );

CREATE POLICY tenant_or_platform_renewal_offers ON peak_renewal_offers
    FOR SELECT
    USING (
        tenant_id = current_tenant_id()
        OR platform_user_has_permission(current_platform_user_id(), 'platform.billing.view')
    );

CREATE POLICY tenant_or_platform_product_grants ON peak_product_grants
    FOR SELECT
    USING (
        tenant_id = current_tenant_id()
        OR platform_user_has_permission(current_platform_user_id(), 'platform.billing.view')
    );

-- -----------------------------------------------------------------------------
-- 2. Operator resolutions.
--
-- An operator records an *observation* about a payment. They do not set a
-- purchase to paid. The distinction is the whole design: a resolution enters the
-- same settlement path a callback or a status query enters, so everything already
-- built around idempotency, grants, receipts and convergence continues to apply,
-- and there is exactly one implementation of what settling means.
--
-- The ordinary action is not a resolution at all. It is 'ask the provider again'
-- — the authoritative system answering for itself. Manual resolution is the
-- exception, and it requires evidence.
-- -----------------------------------------------------------------------------
CREATE TABLE peak_reconciliation_resolutions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_attempt_id uuid NOT NULL REFERENCES peak_payment_attempts(id),
    tenant_id uuid NOT NULL REFERENCES tenants(id) DEFERRABLE,
    resolution varchar(20) NOT NULL,
    -- Where the operator's belief came from. A resolution without a source is an
    -- opinion, and an opinion must not move money.
    evidence_type varchar(30),
    evidence_reference text,
    provider_reference text,
    observed_amount numeric(15,2),
    observed_currency char(3),
    reason text NOT NULL,
    resolved_by_platform_user_id uuid NOT NULL REFERENCES platform_users(id) DEFERRABLE,
    resolved_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_peak_reconciliation_resolution CHECK (
        resolution IN ('requeried', 'confirmed_paid', 'confirmed_failed', 'abandoned')
    ),
    -- Declaring that a customer paid requires evidence and a figure to check it
    -- against. Declaring a failure or abandoning only requires a reason, because
    -- neither grants anything.
    CONSTRAINT chk_peak_reconciliation_evidence CHECK (
        resolution <> 'confirmed_paid'
        OR (
            evidence_type IS NOT NULL
            AND evidence_reference IS NOT NULL
            AND observed_amount IS NOT NULL
            AND observed_currency IS NOT NULL
        )
    ),
    CONSTRAINT chk_peak_reconciliation_reason CHECK (length(btrim(reason)) >= 10)
);

CREATE INDEX idx_peak_reconciliation_resolutions_attempt
    ON peak_reconciliation_resolutions (payment_attempt_id, resolved_at DESC);

ALTER TABLE peak_reconciliation_resolutions ENABLE ROW LEVEL SECURITY;
ALTER TABLE peak_reconciliation_resolutions FORCE ROW LEVEL SECURITY;

CREATE POLICY platform_reconciliation_resolutions ON peak_reconciliation_resolutions
    FOR ALL
    USING (platform_user_has_permission(current_platform_user_id(), 'platform.billing.view'))
    WITH CHECK (
        platform_user_has_permission(current_platform_user_id(), 'platform.billing.reconcile')
    );

GRANT SELECT, INSERT ON peak_reconciliation_resolutions TO pms_platform;
GRANT SELECT ON peak_reconciliation_resolutions TO pms_readonly_support;

-- Append-only. What an operator declared, and why, is the audit trail for a
-- financial decision; editing it later would defeat the point.
REVOKE UPDATE, DELETE ON peak_reconciliation_resolutions
    FROM pms_platform, pms_readonly_support;

-- The settlement path runs as the worker after the operator's observation is
-- recorded, so it needs to read the resolution it is acting on.
GRANT SELECT ON peak_reconciliation_resolutions TO pms_worker;
CREATE POLICY worker_reads_reconciliation_resolutions ON peak_reconciliation_resolutions
    FOR SELECT TO pms_worker USING (true);

-- -----------------------------------------------------------------------------
-- Permissions and routes.
--
-- Viewing the queue and acting on it are separate rights. Most support staff
-- should be able to see why a tenant is stuck without being able to declare that
-- money arrived.
-- -----------------------------------------------------------------------------
INSERT INTO permission_catalog (code, namespace, access_scope, description,
                                is_platform_permission, is_tenant_permission)
VALUES
    ('platform.billing.reconcile', 'platform', 'platform',
     'Re-query a provider and record a resolution for a payment Peak could not determine',
     true, false)
ON CONFLICT (code) DO UPDATE SET
    description = EXCLUDED.description,
    is_platform_permission = EXCLUDED.is_platform_permission,
    is_tenant_permission = EXCLUDED.is_tenant_permission,
    updated_at = now();

INSERT INTO module_access_matrix (
    module_id, screen_key, screen_label, http_method, api_pattern,
    permission_code, route_scope, guard_mode, access_scope,
    is_tanzania_v1, is_enabled_by_default, notes
) VALUES
    ('platform_admin', 'platform.billing.requery', 'Ask the Provider Again', 'POST',
     '/api/platform/billing/reconciliation/:attemptId/requery',
     'platform.billing.reconcile',
     'platform', 'platform_permission', 'platform', true, true,
     'The ordinary action. Decides nothing; asks the authoritative system to answer again'),
    ('platform_admin', 'platform.billing.resolve', 'Record a Resolution', 'POST',
     '/api/platform/billing/reconciliation/:attemptId/resolutions',
     'platform.billing.reconcile',
     'platform', 'platform_permission', 'platform', true, true,
     'The exception. Records an operator observation with evidence, which then enters '
     'the same settlement path a callback would')
ON CONFLICT DO NOTHING;

DO $migration$
BEGIN
    -- Viewing must not confer acting. If these ever collapse into one permission,
    -- everyone who can read the queue can declare that money arrived.
    IF NOT EXISTS (
        SELECT 1 FROM permission_catalog WHERE code = 'platform.billing.reconcile'
    ) OR NOT EXISTS (
        SELECT 1 FROM permission_catalog WHERE code = 'platform.billing.view'
    ) THEN
        RAISE EXCEPTION 'platform billing view and reconcile permissions must both exist';
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.role_table_grants
        WHERE grantee IN ('pms_platform', 'pms_readonly_support')
          AND table_name = 'peak_reconciliation_resolutions'
          AND privilege_type IN ('UPDATE', 'DELETE')
    ) THEN
        RAISE EXCEPTION
            'reconciliation resolutions must be append-only; they are the audit trail for a '
            'financial decision';
    END IF;
END;
$migration$;
