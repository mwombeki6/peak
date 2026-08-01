-- =============================================================================
-- Privileged operation policy, append-only usage ledger, atomic consumption
--
-- Closes the attestation-enforcement drift in privileged support access:
-- `max_uses`, `use_count` and `last_used_at` existed but no decision path read
-- or updated them, and `can_support_session_access_tenant` is STABLE and
-- therefore structurally incapable of consuming a use.
--
-- This migration adds the authoritative policy model, an append-only usage
-- ledger, and a narrow VOLATILE check-and-consume function owned by a
-- dedicated NOLOGIN role. The existing STABLE predicate is retained for
-- non-consuming authorization checks.
--
-- Enforcement rules encoded here:
--   * Access binds to an exact operation code, never to a permission alone.
--   * Policy ceilings are database constraints, not conventions.
--   * Destructive operations can never be break-glass eligible.
--   * A denied authorization records evidence but consumes nothing.
--   * One server request consumes at most one use, regardless of how many
--     internal authorization layers run.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. Dedicated function owner
-- -----------------------------------------------------------------------------

DO $migration$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_catalog.pg_roles
        WHERE rolname = 'pms_privileged_access_owner'
    ) THEN
        CREATE ROLE pms_privileged_access_owner
            NOLOGIN
            NOSUPERUSER
            NOCREATEDB
            NOCREATEROLE
            NOINHERIT
            NOBYPASSRLS;
    ELSE
        ALTER ROLE pms_privileged_access_owner
            NOLOGIN
            NOSUPERUSER
            NOCREATEDB
            NOCREATEROLE
            NOINHERIT
            NOBYPASSRLS;
    END IF;
END;
$migration$;

-- -----------------------------------------------------------------------------
-- 2. Privileged operation policy catalog
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS public.privileged_operation_policies (
    operation_code text PRIMARY KEY,
    permission_code text NOT NULL,
    access_class text NOT NULL,
    risk_level smallint NOT NULL,
    break_glass_eligible boolean NOT NULL,
    required_assurance text NOT NULL,
    max_auth_age_seconds integer NOT NULL,
    max_duration_minutes integer NOT NULL,
    max_uses integer NOT NULL,
    approval_policy_code text NOT NULL,
    tenant_notification_required boolean NOT NULL DEFAULT true,
    description text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT chk_privileged_operation_access_class CHECK (
        access_class IN (
            'metadata_read',
            'sensitive_read',
            'ordinary_mutation',
            'financial_mutation',
            'identity_mutation',
            'destructive'
        )
    ),
    CONSTRAINT chk_privileged_operation_assurance CHECK (
        required_assurance IN ('mfa', 'phishing_resistant')
    ),
    CONSTRAINT chk_privileged_operation_risk_level CHECK (
        risk_level BETWEEN 1 AND 5
    ),
    CONSTRAINT chk_privileged_operation_auth_age CHECK (
        max_auth_age_seconds BETWEEN 30 AND 3600
    ),
    CONSTRAINT chk_privileged_operation_duration CHECK (
        max_duration_minutes BETWEEN 1 AND 240
    ),

    -- Policy ceilings are enforced by the database. A catalog row can never
    -- declare a limit looser than its access class permits.
    CONSTRAINT chk_privileged_operation_class_ceiling CHECK (
        (access_class = 'metadata_read'      AND max_uses BETWEEN 1 AND 20)
     OR (access_class = 'sensitive_read'     AND max_uses BETWEEN 1 AND 10)
     OR (access_class = 'ordinary_mutation'  AND max_uses BETWEEN 1 AND 3)
     OR (access_class = 'financial_mutation' AND max_uses = 1)
     OR (access_class = 'identity_mutation'  AND max_uses = 1)
     OR (access_class = 'destructive')
    ),

    -- Destructive operations are never reachable through ordinary break-glass.
    CONSTRAINT chk_privileged_operation_destructive CHECK (
        access_class <> 'destructive' OR break_glass_eligible = false
    ),

    -- Mutation classes always require phishing-resistant authentication.
    CONSTRAINT chk_privileged_operation_mutation_assurance CHECK (
        access_class NOT IN ('financial_mutation', 'identity_mutation')
     OR required_assurance = 'phishing_resistant'
    )
);

COMMENT ON TABLE public.privileged_operation_policies IS
    'Authoritative privileged-operation policy. Binds an exact operation code to its permission, access class, assurance, freshness, duration, use ceiling, approval quorum and tenant-notification requirement.';

-- Route evidence: bind access-matrix rows to their business operation so route,
-- method, permission and grant agree rather than relying on naming conventions.
ALTER TABLE public.module_access_matrix
    ADD COLUMN IF NOT EXISTS operation_code text;

COMMENT ON COLUMN public.module_access_matrix.operation_code IS
    'Privileged operation this route performs, joining to privileged_operation_policies.';

-- -----------------------------------------------------------------------------
-- 3. Exhausted grant state
-- -----------------------------------------------------------------------------
-- Use exhaustion is distinct evidence from time expiry; conflating them would
-- lose the reason a grant ended.

ALTER TABLE public.platform_break_glass_access
    DROP CONSTRAINT IF EXISTS chk_platform_break_glass_access_status;

ALTER TABLE public.platform_break_glass_access
    ADD CONSTRAINT chk_platform_break_glass_access_status CHECK (
        (status)::text = ANY (ARRAY[
            'requested', 'approved', 'active', 'denied',
            'revoked', 'expired', 'exhausted'
        ]::text[])
    );

-- -----------------------------------------------------------------------------
-- 4. Append-only usage ledger
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS public.platform_privileged_access_usage (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    access_id uuid NOT NULL REFERENCES public.platform_break_glass_access(id),
    request_execution_id uuid NOT NULL,
    platform_user_id uuid NOT NULL REFERENCES public.platform_users(id),
    tenant_id uuid NOT NULL,
    operation_code text NOT NULL,
    decision text NOT NULL,
    denial_reason text,
    achieved_assurance text,
    auth_time timestamptz,
    use_index integer,
    correlation_id text,
    occurred_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT chk_privileged_usage_decision CHECK (
        decision IN ('consumed', 'denied')
    ),
    CONSTRAINT chk_privileged_usage_denial_reason CHECK (
        (decision = 'denied' AND denial_reason IS NOT NULL)
     OR (decision = 'consumed' AND denial_reason IS NULL AND use_index IS NOT NULL)
    )
);

-- One server request consumes at most one use. The uniqueness boundary is the
-- server-generated execution id; a caller-supplied correlation id must never be
-- used here because clients can pin it across requests.
CREATE UNIQUE INDEX IF NOT EXISTS uq_privileged_usage_execution
    ON public.platform_privileged_access_usage (access_id, request_execution_id)
    WHERE decision = 'consumed';

CREATE INDEX IF NOT EXISTS idx_privileged_usage_access
    ON public.platform_privileged_access_usage (access_id, occurred_at DESC);

CREATE INDEX IF NOT EXISTS idx_privileged_usage_tenant
    ON public.platform_privileged_access_usage (tenant_id, occurred_at DESC);

COMMENT ON TABLE public.platform_privileged_access_usage IS
    'Append-only evidence of every privileged access attempt, consumed or denied. Never updated or deleted.';

-- Outcomes are recorded separately so the usage ledger stays immutable.
CREATE TABLE IF NOT EXISTS public.platform_privileged_access_outcomes (
    usage_id uuid PRIMARY KEY
        REFERENCES public.platform_privileged_access_usage(id),
    outcome text NOT NULL,
    detail text,
    recorded_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT chk_privileged_outcome CHECK (
        outcome IN ('succeeded', 'failed', 'unknown')
    )
);

COMMENT ON TABLE public.platform_privileged_access_outcomes IS
    'Business outcome of a consumed privileged use. A crash after consumption leaves the outcome unknown; the use is never refunded.';

CREATE OR REPLACE FUNCTION public.prevent_privileged_usage_mutation()
RETURNS pg_catalog.trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $function$
BEGIN
    RAISE EXCEPTION USING
        ERRCODE = '42501',
        MESSAGE = 'Privileged access usage records are append-only';
END;
$function$;

DROP TRIGGER IF EXISTS privileged_usage_append_only
    ON public.platform_privileged_access_usage;
CREATE TRIGGER privileged_usage_append_only
    BEFORE UPDATE OR DELETE ON public.platform_privileged_access_usage
    FOR EACH ROW EXECUTE FUNCTION public.prevent_privileged_usage_mutation();

-- -----------------------------------------------------------------------------
-- 5. Consumption result type
-- -----------------------------------------------------------------------------

DO $migration$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_catalog.pg_type
        WHERE typname = 'privileged_access_consumption'
    ) THEN
        CREATE TYPE public.privileged_access_consumption AS (
            allowed boolean,
            usage_id uuid,
            uses_remaining integer,
            denial_reason text
        );
    END IF;
END;
$migration$;

-- -----------------------------------------------------------------------------
-- 6. Atomic check-and-consume
-- -----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.consume_privileged_access(
    p_platform_user_id pg_catalog.uuid,
    p_access_id pg_catalog.uuid,
    p_tenant_id pg_catalog.uuid,
    p_operation_code pg_catalog.text,
    p_request_execution_id pg_catalog.uuid,
    p_achieved_assurance pg_catalog.text,
    p_auth_time pg_catalog.timestamptz,
    p_correlation_id pg_catalog.text DEFAULT NULL
) RETURNS public.privileged_access_consumption
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $function$
DECLARE
    v_access public.platform_break_glass_access%ROWTYPE;
    v_policy public.privileged_operation_policies%ROWTYPE;
    v_existing public.platform_privileged_access_usage%ROWTYPE;
    v_result public.privileged_access_consumption;
    v_usage_id uuid;
    v_denial text;
    v_ticket_open boolean;
BEGIN
    IF p_platform_user_id IS NULL
       OR p_access_id IS NULL
       OR p_tenant_id IS NULL
       OR p_operation_code IS NULL
       OR p_request_execution_id IS NULL THEN
        RAISE EXCEPTION USING
            ERRCODE = '22023',
            MESSAGE = 'Privileged access consumption requires all identifying arguments';
    END IF;

    -- Idempotent per server request: a second authorization layer in the same
    -- request returns the first decision without consuming again.
    SELECT *
    INTO v_existing
    FROM public.platform_privileged_access_usage AS usage
    WHERE usage.access_id = p_access_id
      AND usage.request_execution_id = p_request_execution_id
      AND usage.decision = 'consumed'
    LIMIT 1;

    IF FOUND THEN
        SELECT access.max_uses - access.use_count
        INTO v_result.uses_remaining
        FROM public.platform_break_glass_access AS access
        WHERE access.id = p_access_id;

        v_result.allowed := true;
        v_result.usage_id := v_existing.id;
        v_result.denial_reason := NULL;
        RETURN v_result;
    END IF;

    SELECT *
    INTO v_policy
    FROM public.privileged_operation_policies AS policy
    WHERE policy.operation_code = p_operation_code;

    IF NOT FOUND THEN
        v_denial := 'Operation has no privileged policy';
    ELSIF v_policy.break_glass_eligible IS NOT TRUE THEN
        v_denial := 'Operation is not eligible for privileged access';
    END IF;

    -- Serialize concurrent consumption of the same grant.
    SELECT *
    INTO v_access
    FROM public.platform_break_glass_access AS access
    WHERE access.id = p_access_id
    FOR UPDATE;

    -- Evidence rows reference the grant, so a missing grant cannot be recorded
    -- as a denial. Treat it as a caller error rather than silently returning.
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = '42501',
            MESSAGE = 'Privileged access grant was not found';
    END IF;

    -- Re-check under the row lock: a concurrent call with the same execution id
    -- may have consumed between the first check and acquiring the lock.
    SELECT *
    INTO v_existing
    FROM public.platform_privileged_access_usage AS usage
    WHERE usage.access_id = p_access_id
      AND usage.request_execution_id = p_request_execution_id
      AND usage.decision = 'consumed'
    LIMIT 1;

    IF FOUND THEN
        v_result.allowed := true;
        v_result.usage_id := v_existing.id;
        v_result.uses_remaining := v_access.max_uses - v_access.use_count;
        v_result.denial_reason := NULL;
        RETURN v_result;
    END IF;

    IF v_denial IS NULL THEN
        IF v_access.platform_user_id IS DISTINCT FROM p_platform_user_id THEN
            v_denial := 'Privileged access grant belongs to another operator';
        ELSIF v_access.tenant_id IS DISTINCT FROM p_tenant_id THEN
            v_denial := 'Privileged access grant is scoped to another tenant';
        ELSIF v_access.action_code IS DISTINCT FROM v_policy.permission_code THEN
            v_denial := 'Privileged access grant does not permit this operation';
        ELSIF (v_access.status)::text <> 'active' THEN
            v_denial := 'Privileged access grant is not active';
        ELSIF v_access.approved_by IS NULL OR v_access.approved_at IS NULL THEN
            v_denial := 'Privileged access grant is not approved';
        ELSIF v_access.activated_at IS NULL OR v_access.activated_at > now() THEN
            v_denial := 'Privileged access grant is not activated';
        ELSIF v_access.revoked_at IS NOT NULL OR v_access.denied_at IS NOT NULL THEN
            v_denial := 'Privileged access grant is revoked or denied';
        ELSIF v_access.starts_at > now() OR v_access.expires_at <= now() THEN
            v_denial := 'Privileged access grant is outside its time window';
        ELSIF v_access.use_count >= v_access.max_uses THEN
            v_denial := 'Privileged access grant has no remaining uses';
        ELSIF p_achieved_assurance IS NULL THEN
            v_denial := 'Authentication assurance evidence is missing';
        ELSIF v_policy.required_assurance = 'phishing_resistant'
              AND p_achieved_assurance <> 'phishing_resistant' THEN
            v_denial := 'Operation requires phishing-resistant authentication';
        ELSIF p_auth_time IS NULL THEN
            v_denial := 'Authentication time evidence is missing';
        ELSIF p_auth_time
              < now() - make_interval(secs => v_policy.max_auth_age_seconds) THEN
            v_denial := 'Authentication is not fresh enough for this operation';
        END IF;
    END IF;

    -- An authorizing ticket must still be open.
    IF v_denial IS NULL THEN
        SELECT EXISTS (
            SELECT 1
            FROM public.support_tickets AS ticket
            WHERE ticket.id = v_access.support_ticket_id
              AND (ticket.status)::text NOT IN ('resolved', 'closed')
        )
        INTO v_ticket_open;

        IF v_access.support_ticket_id IS NULL OR v_ticket_open IS NOT TRUE THEN
            v_denial := 'Authorizing support ticket is not open';
        END IF;
    END IF;

    -- The operator must still be an effective platform user.
    IF v_denial IS NULL THEN
        IF NOT EXISTS (
            SELECT 1
            FROM public.platform_users AS operator
            WHERE operator.id = p_platform_user_id
              AND operator.status = 'active'
              AND operator.deleted_at IS NULL
        ) THEN
            v_denial := 'Operator is no longer an effective platform user';
        END IF;
    END IF;

    -- Denied attempts are evidence but never consume a use.
    IF v_denial IS NOT NULL THEN
        INSERT INTO public.platform_privileged_access_usage (
            access_id, request_execution_id, platform_user_id, tenant_id,
            operation_code, decision, denial_reason, achieved_assurance,
            auth_time, correlation_id
        ) VALUES (
            p_access_id, p_request_execution_id, p_platform_user_id, p_tenant_id,
            p_operation_code, 'denied', v_denial, p_achieved_assurance,
            p_auth_time, p_correlation_id
        )
        RETURNING id INTO v_usage_id;

        v_result.allowed := false;
        v_result.usage_id := v_usage_id;
        v_result.uses_remaining := COALESCE(v_access.max_uses - v_access.use_count, 0);
        v_result.denial_reason := v_denial;
        RETURN v_result;
    END IF;

    INSERT INTO public.platform_privileged_access_usage (
        access_id, request_execution_id, platform_user_id, tenant_id,
        operation_code, decision, achieved_assurance, auth_time,
        use_index, correlation_id
    ) VALUES (
        p_access_id, p_request_execution_id, p_platform_user_id, p_tenant_id,
        p_operation_code, 'consumed', p_achieved_assurance, p_auth_time,
        v_access.use_count + 1, p_correlation_id
    )
    RETURNING id INTO v_usage_id;

    UPDATE public.platform_break_glass_access AS access
    SET use_count = access.use_count + 1,
        last_used_at = now(),
        status = CASE
            WHEN access.use_count + 1 >= access.max_uses THEN 'exhausted'
            ELSE access.status
        END
    WHERE access.id = p_access_id;

    v_result.allowed := true;
    v_result.usage_id := v_usage_id;
    v_result.uses_remaining := v_access.max_uses - (v_access.use_count + 1);
    v_result.denial_reason := NULL;
    RETURN v_result;
END;
$function$;

ALTER FUNCTION public.consume_privileged_access(
    pg_catalog.uuid, pg_catalog.uuid, pg_catalog.uuid, pg_catalog.text,
    pg_catalog.uuid, pg_catalog.text, pg_catalog.timestamptz, pg_catalog.text
) OWNER TO pms_privileged_access_owner;

REVOKE ALL ON FUNCTION public.consume_privileged_access(
    pg_catalog.uuid, pg_catalog.uuid, pg_catalog.uuid, pg_catalog.text,
    pg_catalog.uuid, pg_catalog.text, pg_catalog.timestamptz, pg_catalog.text
) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION public.consume_privileged_access(
    pg_catalog.uuid, pg_catalog.uuid, pg_catalog.uuid, pg_catalog.text,
    pg_catalog.uuid, pg_catalog.text, pg_catalog.timestamptz, pg_catalog.text
) TO pms_platform;

COMMENT ON FUNCTION public.consume_privileged_access(
    pg_catalog.uuid, pg_catalog.uuid, pg_catalog.uuid, pg_catalog.text,
    pg_catalog.uuid, pg_catalog.text, pg_catalog.timestamptz, pg_catalog.text
) IS
    'Atomically authorizes and consumes one privileged access use. Denials record evidence without consuming. Repeat calls with the same server execution id return the first decision.';

-- -----------------------------------------------------------------------------
-- 7. Owner privileges
-- -----------------------------------------------------------------------------
-- The definer owns only what the function body touches.

GRANT USAGE ON SCHEMA public TO pms_privileged_access_owner;

GRANT SELECT, INSERT
    ON TABLE public.platform_privileged_access_usage
    TO pms_privileged_access_owner;
GRANT SELECT
    ON TABLE public.privileged_operation_policies
    TO pms_privileged_access_owner;
GRANT SELECT (id, status)
    ON TABLE public.support_tickets
    TO pms_privileged_access_owner;
GRANT SELECT (id, status, deleted_at)
    ON TABLE public.platform_users
    TO pms_privileged_access_owner;
GRANT SELECT, UPDATE (use_count, last_used_at, status)
    ON TABLE public.platform_break_glass_access
    TO pms_privileged_access_owner;

-- Runtime roles read evidence but never mutate the ledger directly.
GRANT SELECT ON TABLE public.privileged_operation_policies TO pms_platform;
GRANT SELECT ON TABLE public.platform_privileged_access_usage TO pms_platform;
GRANT SELECT, INSERT ON TABLE public.platform_privileged_access_outcomes TO pms_platform;
GRANT SELECT ON TABLE public.privileged_operation_policies TO pms_readonly_support;
GRANT SELECT ON TABLE public.platform_privileged_access_usage TO pms_readonly_support;

-- -----------------------------------------------------------------------------
-- 8. Initial policy seed
-- -----------------------------------------------------------------------------
-- One row per access class so every ceiling is exercised by real policy.

INSERT INTO public.privileged_operation_policies (
    operation_code, permission_code, access_class, risk_level,
    break_glass_eligible, required_assurance, max_auth_age_seconds,
    max_duration_minutes, max_uses, approval_policy_code,
    tenant_notification_required, description
) VALUES
    (
        'platform.tenants.catalog.read', 'platform.tenants.view',
        'metadata_read', 1, true, 'mfa', 900, 60, 20,
        'read_investigation', true,
        'Read tenant catalog metadata during an investigation.'
    ),
    (
        'platform.tenants.profile.read', 'platform.tenants.view',
        'sensitive_read', 2, true, 'phishing_resistant', 600, 60, 10,
        'read_investigation', true,
        'Read a tenant business profile including contact details.'
    ),
    (
        'platform.tenants.control.suspend', 'platform.tenants.manage',
        'ordinary_mutation', 3, true, 'phishing_resistant', 300, 30, 3,
        'ordinary_mutation', true,
        'Suspend a tenant account during an incident.'
    ),
    (
        'platform.payments.reconcile', 'platform.billing.manage',
        'financial_mutation', 4, true, 'phishing_resistant', 300, 15, 1,
        'financial_mutation', true,
        'Reconcile a tenant payment record.'
    ),
    (
        'platform.tenant.administrator.provision',
        'platform.tenant_administrator.provision',
        'identity_mutation', 5, true, 'phishing_resistant', 300, 15, 1,
        'identity_mutation', true,
        'Provision or replace a tenant administrator identity.'
    ),
    (
        'platform.tenant.delete', 'platform.tenants.manage',
        'destructive', 5, false, 'phishing_resistant', 300, 15, 1,
        'ineligible', true,
        'Delete a tenant. Never reachable through privileged support access.'
    )
ON CONFLICT (operation_code) DO NOTHING;
