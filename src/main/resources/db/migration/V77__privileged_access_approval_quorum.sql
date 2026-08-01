-- =============================================================================
-- General N-of-M approval quorum for privileged access
--
-- Closes the verified defect that request, approve, activate, revoke and list
-- all shared one permission, so two operators with identical authority could
-- approve one another, and that a single `approved_by` column cannot express a
-- policy such as "security plus finance".
--
-- Model:
--   * An approval policy owns one or more seats.
--   * A seat names the permission an approver must hold and how many distinct
--     approvers it needs.
--   * Approvals are append-only and bind to the exact request version and hash.
--   * Any material change to the request bumps its version, which invalidates
--     every prior approval because the recorded hash no longer matches.
--
-- Enforcement rules encoded here:
--   * One person occupies at most one seat, regardless of how many roles they
--     hold (unique approver per request).
--   * The requester can never approve their own request.
--   * An approver must actually hold the seat permission at decision time and
--     still hold it, and still be effective, at activation time.
--   * Insufficient qualified personnel leaves the operation unavailable; the
--     quorum is never silently reduced.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. Approval policies and their seats
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS public.approval_policies (
    approval_policy_code text PRIMARY KEY,
    description text NOT NULL,
    approval_ttl_minutes integer NOT NULL DEFAULT 60,
    created_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT chk_approval_policy_ttl CHECK (
        approval_ttl_minutes BETWEEN 5 AND 1440
    )
);

COMMENT ON TABLE public.approval_policies IS
    'Named approval quorum. Seats are defined in approval_policy_seats; a policy with no seats can never be satisfied.';

CREATE TABLE IF NOT EXISTS public.approval_policy_seats (
    approval_policy_code text NOT NULL
        REFERENCES public.approval_policies(approval_policy_code),
    seat_code text NOT NULL,
    required_permission text NOT NULL,
    required_approvers integer NOT NULL DEFAULT 1,
    description text,

    PRIMARY KEY (approval_policy_code, seat_code),
    CONSTRAINT chk_approval_seat_count CHECK (
        required_approvers BETWEEN 1 AND 5
    )
);

COMMENT ON TABLE public.approval_policy_seats IS
    'A seat requires a number of distinct approvers who each hold the named permission. Seats are independent; one person cannot fill two.';

-- -----------------------------------------------------------------------------
-- 2. Request versioning and canonical binding
-- -----------------------------------------------------------------------------

ALTER TABLE public.platform_break_glass_access
    ADD COLUMN IF NOT EXISTS request_version integer NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS request_hash text,
    ADD COLUMN IF NOT EXISTS operation_code text,
    ADD COLUMN IF NOT EXISTS approval_policy_code text;

COMMENT ON COLUMN public.platform_break_glass_access.request_hash IS
    'Canonical hash of the material request fields. Approvals record the hash they approved, so any material change invalidates them.';

CREATE OR REPLACE FUNCTION public.privileged_access_request_hash(
    p_platform_user_id pg_catalog.uuid,
    p_tenant_id pg_catalog.uuid,
    p_action_code pg_catalog.text,
    p_operation_code pg_catalog.text,
    p_reason pg_catalog.text,
    p_starts_at pg_catalog.timestamptz,
    p_expires_at pg_catalog.timestamptz,
    p_max_uses pg_catalog.int4,
    p_assurance_level pg_catalog.text,
    p_support_ticket_id pg_catalog.uuid
) RETURNS pg_catalog.text
LANGUAGE sql
IMMUTABLE
SET search_path = pg_catalog, pg_temp
AS $function$
    SELECT encode(
        sha256(
            convert_to(
                concat_ws(
                    '|',
                    p_platform_user_id::text,
                    p_tenant_id::text,
                    coalesce(p_action_code, ''),
                    coalesce(p_operation_code, ''),
                    coalesce(p_reason, ''),
                    p_starts_at::text,
                    p_expires_at::text,
                    p_max_uses::text,
                    coalesce(p_assurance_level, ''),
                    coalesce(p_support_ticket_id::text, '')
                ),
                'UTF8'
            )
        ),
        'hex'
    );
$function$;

COMMENT ON FUNCTION public.privileged_access_request_hash(
    pg_catalog.uuid, pg_catalog.uuid, pg_catalog.text, pg_catalog.text,
    pg_catalog.text, pg_catalog.timestamptz, pg_catalog.timestamptz,
    pg_catalog.int4, pg_catalog.text, pg_catalog.uuid
) IS
    'Canonical hash over the material fields of a privileged access request.';

-- Maintain hash and version automatically so no caller can forget.
CREATE OR REPLACE FUNCTION public.maintain_privileged_request_version()
RETURNS pg_catalog.trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $function$
DECLARE
    v_hash text;
BEGIN
    v_hash := public.privileged_access_request_hash(
        NEW.platform_user_id, NEW.tenant_id, NEW.action_code, NEW.operation_code,
        NEW.reason, NEW.starts_at, NEW.expires_at, NEW.max_uses,
        NEW.assurance_level, NEW.support_ticket_id
    );

    IF TG_OP = 'INSERT' THEN
        NEW.request_hash := v_hash;
        NEW.request_version := 1;
        RETURN NEW;
    END IF;

    IF v_hash IS DISTINCT FROM OLD.request_hash THEN
        NEW.request_hash := v_hash;
        NEW.request_version := OLD.request_version + 1;
    END IF;

    RETURN NEW;
END;
$function$;

DROP TRIGGER IF EXISTS privileged_request_version
    ON public.platform_break_glass_access;
CREATE TRIGGER privileged_request_version
    BEFORE INSERT OR UPDATE ON public.platform_break_glass_access
    FOR EACH ROW EXECUTE FUNCTION public.maintain_privileged_request_version();

UPDATE public.platform_break_glass_access AS access
SET request_hash = public.privileged_access_request_hash(
        access.platform_user_id, access.tenant_id, access.action_code,
        access.operation_code, access.reason, access.starts_at,
        access.expires_at, access.max_uses, access.assurance_level,
        access.support_ticket_id
    )
WHERE access.request_hash IS NULL;

-- -----------------------------------------------------------------------------
-- 3. Append-only approval decisions
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS public.platform_break_glass_approvals (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    access_id uuid NOT NULL
        REFERENCES public.platform_break_glass_access(id),
    seat_code text NOT NULL,
    approver_platform_user_id uuid NOT NULL
        REFERENCES public.platform_users(id),
    decision text NOT NULL,
    decision_reason text,
    approved_request_version integer NOT NULL,
    approved_request_hash text NOT NULL,
    approver_assurance text,
    decided_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,

    CONSTRAINT chk_break_glass_approval_decision CHECK (
        decision IN ('approved', 'denied')
    ),
    CONSTRAINT chk_break_glass_approval_window CHECK (
        expires_at > decided_at
    ),

    -- One person occupies at most one seat per request, and cannot approve
    -- twice to inflate a quorum.
    CONSTRAINT uq_break_glass_approval_person
        UNIQUE (access_id, approver_platform_user_id)
);

CREATE INDEX IF NOT EXISTS idx_break_glass_approvals_access
    ON public.platform_break_glass_approvals (access_id, seat_code);

COMMENT ON TABLE public.platform_break_glass_approvals IS
    'Append-only approval decisions bound to an exact request version and hash. One approver, one seat, one decision.';

CREATE OR REPLACE FUNCTION public.prevent_break_glass_approval_mutation()
RETURNS pg_catalog.trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $function$
BEGIN
    RAISE EXCEPTION USING
        ERRCODE = '42501',
        MESSAGE = 'Privileged access approvals are append-only';
END;
$function$;

DROP TRIGGER IF EXISTS break_glass_approvals_append_only
    ON public.platform_break_glass_approvals;
CREATE TRIGGER break_glass_approvals_append_only
    BEFORE UPDATE OR DELETE ON public.platform_break_glass_approvals
    FOR EACH ROW EXECUTE FUNCTION public.prevent_break_glass_approval_mutation();

-- -----------------------------------------------------------------------------
-- 4. Approval admissibility
-- -----------------------------------------------------------------------------
-- Rejected at write time rather than filtered at evaluation time, so an
-- inadmissible approval never becomes evidence.

CREATE OR REPLACE FUNCTION public.validate_break_glass_approval()
RETURNS pg_catalog.trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $function$
DECLARE
    v_access public.platform_break_glass_access%ROWTYPE;
    v_seat public.approval_policy_seats%ROWTYPE;
BEGIN
    SELECT *
    INTO v_access
    FROM public.platform_break_glass_access AS access
    WHERE access.id = NEW.access_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = '23503',
            MESSAGE = 'Privileged access request was not found';
    END IF;

    -- The requester can never approve their own request.
    IF NEW.approver_platform_user_id = v_access.platform_user_id THEN
        RAISE EXCEPTION USING
            ERRCODE = '42501',
            MESSAGE = 'Requester cannot approve their own privileged access request';
    END IF;

    -- Approvals bind to the exact current request.
    IF NEW.approved_request_hash IS DISTINCT FROM v_access.request_hash
       OR NEW.approved_request_version IS DISTINCT FROM v_access.request_version THEN
        RAISE EXCEPTION USING
            ERRCODE = '42501',
            MESSAGE = 'Approval does not match the current privileged access request version';
    END IF;

    -- Only seats declared by the request policy may be filled.
    SELECT *
    INTO v_seat
    FROM public.approval_policy_seats AS seat
    WHERE seat.approval_policy_code = v_access.approval_policy_code
      AND seat.seat_code = NEW.seat_code;

    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = '42501',
            MESSAGE = 'Approval seat is not part of the request approval policy';
    END IF;

    -- The approver must actually hold the seat permission.
    IF NOT public.platform_user_has_permission(
        NEW.approver_platform_user_id, v_seat.required_permission
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '42501',
            MESSAGE = 'Approver does not hold the permission required by this seat';
    END IF;

    -- The approver must be an effective platform user.
    IF NOT EXISTS (
        SELECT 1
        FROM public.platform_users AS approver
        WHERE approver.id = NEW.approver_platform_user_id
          AND approver.status = 'active'
          AND approver.deleted_at IS NULL
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '42501',
            MESSAGE = 'Approver is not an effective platform user';
    END IF;

    RETURN NEW;
END;
$function$;

DROP TRIGGER IF EXISTS break_glass_approval_admissible
    ON public.platform_break_glass_approvals;
CREATE TRIGGER break_glass_approval_admissible
    BEFORE INSERT ON public.platform_break_glass_approvals
    FOR EACH ROW EXECUTE FUNCTION public.validate_break_glass_approval();

-- -----------------------------------------------------------------------------
-- 5. Quorum evaluation
-- -----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.break_glass_quorum_satisfied(
    p_access_id pg_catalog.uuid
) RETURNS pg_catalog.bool
LANGUAGE plpgsql
STABLE
SET search_path = pg_catalog, pg_temp
AS $function$
DECLARE
    v_access public.platform_break_glass_access%ROWTYPE;
    v_seat_total integer;
    v_unmet integer;
BEGIN
    SELECT *
    INTO v_access
    FROM public.platform_break_glass_access AS access
    WHERE access.id = p_access_id;

    IF NOT FOUND OR v_access.approval_policy_code IS NULL THEN
        RETURN false;
    END IF;

    -- A denial anywhere blocks the request outright.
    IF EXISTS (
        SELECT 1
        FROM public.platform_break_glass_approvals AS decision
        WHERE decision.access_id = p_access_id
          AND decision.decision = 'denied'
          AND decision.approved_request_hash = v_access.request_hash
    ) THEN
        RETURN false;
    END IF;

    SELECT count(*)
    INTO v_seat_total
    FROM public.approval_policy_seats AS seat
    WHERE seat.approval_policy_code = v_access.approval_policy_code;

    -- A policy with no seats can never be satisfied.
    IF v_seat_total = 0 THEN
        RETURN false;
    END IF;

    -- Count seats that are still short of their required distinct approvers.
    SELECT count(*)
    INTO v_unmet
    FROM public.approval_policy_seats AS seat
    WHERE seat.approval_policy_code = v_access.approval_policy_code
      AND (
          SELECT count(DISTINCT decision.approver_platform_user_id)
          FROM public.platform_break_glass_approvals AS decision
          JOIN public.platform_users AS approver
            ON approver.id = decision.approver_platform_user_id
          WHERE decision.access_id = p_access_id
            AND decision.seat_code = seat.seat_code
            AND decision.decision = 'approved'
            AND decision.approved_request_hash = v_access.request_hash
            AND decision.approved_request_version = v_access.request_version
            AND decision.expires_at > now()
            AND approver.status = 'active'
            AND approver.deleted_at IS NULL
            AND public.platform_user_has_permission(
                    decision.approver_platform_user_id, seat.required_permission
                )
      ) < seat.required_approvers;

    RETURN v_unmet = 0;
END;
$function$;

REVOKE ALL ON FUNCTION public.break_glass_quorum_satisfied(pg_catalog.uuid)
    FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.break_glass_quorum_satisfied(pg_catalog.uuid)
    TO pms_platform;

COMMENT ON FUNCTION public.break_glass_quorum_satisfied(pg_catalog.uuid) IS
    'True only when every seat of the request approval policy has its required distinct, still-effective, still-permitted approvers for the current request version.';

-- -----------------------------------------------------------------------------
-- 6. Runtime privileges
-- -----------------------------------------------------------------------------

GRANT SELECT ON TABLE public.approval_policies TO pms_platform;
GRANT SELECT ON TABLE public.approval_policy_seats TO pms_platform;
GRANT SELECT, INSERT ON TABLE public.platform_break_glass_approvals TO pms_platform;

GRANT SELECT ON TABLE public.approval_policies TO pms_readonly_support;
GRANT SELECT ON TABLE public.approval_policy_seats TO pms_readonly_support;
GRANT SELECT ON TABLE public.platform_break_glass_approvals TO pms_readonly_support;

-- -----------------------------------------------------------------------------
-- 7. Initial quorum policies
-- -----------------------------------------------------------------------------

INSERT INTO public.approval_policies (approval_policy_code, description, approval_ttl_minutes)
VALUES
    ('read_investigation',
     'One qualified approver for read-only investigation access.', 120),
    ('ordinary_mutation',
     'One security approver for ordinary tenant mutations.', 60),
    ('financial_mutation',
     'Two distinct seats, security and finance, for financial mutations.', 30),
    ('identity_mutation',
     'Two distinct security custodians for identity and root mutations.', 30),
    ('ineligible',
     'Operations that are never reachable through privileged access.', 5)
ON CONFLICT (approval_policy_code) DO NOTHING;

INSERT INTO public.approval_policy_seats (
    approval_policy_code, seat_code, required_permission, required_approvers, description
) VALUES
    ('read_investigation', 'approver',
     'platform.support.access.approve', 1,
     'Any qualified privileged-access approver.'),
    ('ordinary_mutation', 'security',
     'platform.support.access.approve', 1,
     'Security approver.'),
    ('financial_mutation', 'security',
     'platform.support.access.approve', 1,
     'Security seat.'),
    ('financial_mutation', 'finance',
     'platform.billing.manage', 1,
     'Finance seat, distinct person from the security seat.'),
    ('identity_mutation', 'security_custodian_a',
     'platform.security.manage', 1,
     'First security custodian.'),
    ('identity_mutation', 'security_custodian_b',
     'platform.security.manage', 1,
     'Second security custodian, distinct person.')
ON CONFLICT (approval_policy_code, seat_code) DO NOTHING;

-- 'ineligible' intentionally has no seats: it can never be satisfied.

-- Bind existing policy rows to their quorum.
UPDATE public.privileged_operation_policies AS policy
SET approval_policy_code = policy.approval_policy_code
WHERE EXISTS (
    SELECT 1
    FROM public.approval_policies AS quorum
    WHERE quorum.approval_policy_code = policy.approval_policy_code
);

ALTER TABLE public.privileged_operation_policies
    ADD CONSTRAINT fk_privileged_policy_approval
        FOREIGN KEY (approval_policy_code)
        REFERENCES public.approval_policies(approval_policy_code);
