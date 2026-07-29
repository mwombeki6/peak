-- =============================================================================
-- Dual-controlled Platform Emergency Administrator appointment
--
-- Closes the verified defect that root appointment was immediate and
-- single-actor: one holder of platform.administrators.manage could insert the
-- platform_root role for another user with no request record, no second
-- approver, no step-up, no expiry and no cooling period. Guards existed against
-- self-assignment and against removing the final effective root, but a single
-- compromised operator account could still mint a permanent second root.
--
-- The approval_policies and approval_policy_seats tables from V77 are already
-- subject-agnostic, keyed by policy code rather than by what is being approved,
-- so the identity_mutation quorum of two distinct security custodians is reused
-- here rather than redefined.
--
-- Appointment is deliberately modelled as a request that must be approved and
-- then applied, not as a mutation that happens to be logged. The role grant
-- cannot occur until an independent quorum has approved the exact request.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. Appointment requests
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS public.platform_root_appointment_requests (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    action text NOT NULL,
    target_platform_user_id uuid NOT NULL REFERENCES public.platform_users(id),
    requested_by_platform_user_id uuid NOT NULL REFERENCES public.platform_users(id),
    reason text NOT NULL,
    status text NOT NULL DEFAULT 'requested',
    approval_policy_code text NOT NULL
        REFERENCES public.approval_policies(approval_policy_code),
    request_version integer NOT NULL DEFAULT 1,
    request_hash text,
    requested_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    applied_at timestamptz,
    applied_by_platform_user_id uuid REFERENCES public.platform_users(id),
    cancelled_at timestamptz,

    CONSTRAINT chk_root_appointment_action CHECK (
        action IN ('appoint', 'revoke')
    ),
    CONSTRAINT chk_root_appointment_status CHECK (
        status IN ('requested', 'approved', 'applied', 'denied', 'expired', 'cancelled')
    ),
    CONSTRAINT chk_root_appointment_reason CHECK (
        length(btrim(reason)) BETWEEN 10 AND 1000
    ),
    CONSTRAINT chk_root_appointment_window CHECK (
        expires_at > requested_at
    ),
    -- The requester can never be the target. Appointing yourself, or revoking
    -- another custodian to leave yourself sole root, is the attack this
    -- prevents.
    CONSTRAINT chk_root_appointment_not_self CHECK (
        target_platform_user_id <> requested_by_platform_user_id
    ),
    CONSTRAINT chk_root_appointment_applied CHECK (
        status <> 'applied'
     OR (applied_at IS NOT NULL AND applied_by_platform_user_id IS NOT NULL)
    )
);

CREATE INDEX IF NOT EXISTS idx_root_appointment_target
    ON public.platform_root_appointment_requests (target_platform_user_id, requested_at DESC);

COMMENT ON TABLE public.platform_root_appointment_requests IS
    'Requests to appoint or revoke a Platform Emergency Administrator. The role grant cannot occur until an independent quorum approves the exact request version.';

-- Only one open request per target and action, so concurrent requests cannot
-- accumulate approvals separately and then both apply.
CREATE UNIQUE INDEX IF NOT EXISTS uq_root_appointment_open
    ON public.platform_root_appointment_requests (target_platform_user_id, action)
    WHERE status IN ('requested', 'approved');

-- -----------------------------------------------------------------------------
-- 2. Canonical request binding
-- -----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.root_appointment_request_hash(
    p_action pg_catalog.text,
    p_target pg_catalog.uuid,
    p_requested_by pg_catalog.uuid,
    p_reason pg_catalog.text,
    p_expires_at pg_catalog.timestamptz
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
                    coalesce(p_action, ''),
                    p_target::text,
                    p_requested_by::text,
                    coalesce(btrim(p_reason), ''),
                    p_expires_at::text
                ),
                'UTF8'
            )
        ),
        'hex'
    );
$function$;

CREATE OR REPLACE FUNCTION public.maintain_root_appointment_version()
RETURNS pg_catalog.trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $function$
DECLARE
    v_hash text;
BEGIN
    v_hash := public.root_appointment_request_hash(
        NEW.action, NEW.target_platform_user_id,
        NEW.requested_by_platform_user_id, NEW.reason, NEW.expires_at
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

DROP TRIGGER IF EXISTS root_appointment_version
    ON public.platform_root_appointment_requests;
CREATE TRIGGER root_appointment_version
    BEFORE INSERT OR UPDATE ON public.platform_root_appointment_requests
    FOR EACH ROW EXECUTE FUNCTION public.maintain_root_appointment_version();

-- -----------------------------------------------------------------------------
-- 3. Append-only approvals
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS public.platform_root_appointment_approvals (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    request_id uuid NOT NULL
        REFERENCES public.platform_root_appointment_requests(id),
    seat_code text NOT NULL,
    approver_platform_user_id uuid NOT NULL REFERENCES public.platform_users(id),
    decision text NOT NULL,
    decision_reason text,
    approved_request_version integer NOT NULL,
    approved_request_hash text NOT NULL,
    approver_assurance text,
    decided_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,

    CONSTRAINT chk_root_approval_decision CHECK (
        decision IN ('approved', 'denied')
    ),
    CONSTRAINT chk_root_approval_window CHECK (expires_at > decided_at),
    CONSTRAINT uq_root_approval_person UNIQUE (request_id, approver_platform_user_id)
);

COMMENT ON TABLE public.platform_root_appointment_approvals IS
    'Append-only approval decisions for root appointment, bound to an exact request version and hash. One approver, one seat.';

CREATE OR REPLACE FUNCTION public.prevent_root_approval_mutation()
RETURNS pg_catalog.trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $function$
BEGIN
    RAISE EXCEPTION USING
        ERRCODE = '42501',
        MESSAGE = 'Root appointment approvals are append-only';
END;
$function$;

DROP TRIGGER IF EXISTS root_approvals_append_only
    ON public.platform_root_appointment_approvals;
CREATE TRIGGER root_approvals_append_only
    BEFORE UPDATE OR DELETE ON public.platform_root_appointment_approvals
    FOR EACH ROW EXECUTE FUNCTION public.prevent_root_approval_mutation();

-- -----------------------------------------------------------------------------
-- 4. Approval admissibility
-- -----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.validate_root_appointment_approval()
RETURNS pg_catalog.trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $function$
DECLARE
    v_request public.platform_root_appointment_requests%ROWTYPE;
    v_seat public.approval_policy_seats%ROWTYPE;
BEGIN
    SELECT *
    INTO v_request
    FROM public.platform_root_appointment_requests AS request
    WHERE request.id = NEW.request_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = '23503',
            MESSAGE = 'Root appointment request was not found';
    END IF;

    IF NEW.approver_platform_user_id = v_request.requested_by_platform_user_id THEN
        RAISE EXCEPTION USING
            ERRCODE = '42501',
            MESSAGE = 'Requester cannot approve their own root appointment request';
    END IF;

    -- The subject of the appointment must not approve it. Otherwise a candidate
    -- root approves their own elevation.
    IF NEW.approver_platform_user_id = v_request.target_platform_user_id THEN
        RAISE EXCEPTION USING
            ERRCODE = '42501',
            MESSAGE = 'Target cannot approve their own root appointment';
    END IF;

    IF NEW.approved_request_hash IS DISTINCT FROM v_request.request_hash
       OR NEW.approved_request_version IS DISTINCT FROM v_request.request_version THEN
        RAISE EXCEPTION USING
            ERRCODE = '42501',
            MESSAGE = 'Approval does not match the current root appointment request version';
    END IF;

    SELECT *
    INTO v_seat
    FROM public.approval_policy_seats AS seat
    WHERE seat.approval_policy_code = v_request.approval_policy_code
      AND seat.seat_code = NEW.seat_code;

    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = '42501',
            MESSAGE = 'Approval seat is not part of the request approval policy';
    END IF;

    IF public.platform_user_holds_permission(
        NEW.approver_platform_user_id, v_seat.required_permission
    ) IS NOT TRUE THEN
        RAISE EXCEPTION USING
            ERRCODE = '42501',
            MESSAGE = 'Approver does not hold the permission required by this seat';
    END IF;

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

DROP TRIGGER IF EXISTS root_approval_admissible
    ON public.platform_root_appointment_approvals;
CREATE TRIGGER root_approval_admissible
    BEFORE INSERT ON public.platform_root_appointment_approvals
    FOR EACH ROW EXECUTE FUNCTION public.validate_root_appointment_approval();

-- -----------------------------------------------------------------------------
-- 5. Quorum evaluation
-- -----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.root_appointment_quorum_satisfied(
    p_request_id pg_catalog.uuid
) RETURNS pg_catalog.bool
LANGUAGE plpgsql
STABLE
SET search_path = pg_catalog, pg_temp
AS $function$
DECLARE
    v_request public.platform_root_appointment_requests%ROWTYPE;
    v_seat_total integer;
    v_unmet integer;
BEGIN
    SELECT *
    INTO v_request
    FROM public.platform_root_appointment_requests AS request
    WHERE request.id = p_request_id;

    IF NOT FOUND THEN
        RETURN false;
    END IF;

    IF v_request.expires_at <= now()
       OR v_request.cancelled_at IS NOT NULL
       OR v_request.status NOT IN ('requested', 'approved') THEN
        RETURN false;
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.platform_root_appointment_approvals AS decision
        WHERE decision.request_id = p_request_id
          AND decision.decision = 'denied'
          AND decision.approved_request_hash = v_request.request_hash
    ) THEN
        RETURN false;
    END IF;

    SELECT count(*)
    INTO v_seat_total
    FROM public.approval_policy_seats AS seat
    WHERE seat.approval_policy_code = v_request.approval_policy_code;

    IF v_seat_total = 0 THEN
        RETURN false;
    END IF;

    SELECT count(*)
    INTO v_unmet
    FROM public.approval_policy_seats AS seat
    WHERE seat.approval_policy_code = v_request.approval_policy_code
      AND (
          SELECT count(DISTINCT decision.approver_platform_user_id)
          FROM public.platform_root_appointment_approvals AS decision
          JOIN public.platform_users AS approver
            ON approver.id = decision.approver_platform_user_id
          WHERE decision.request_id = p_request_id
            AND decision.seat_code = seat.seat_code
            AND decision.decision = 'approved'
            AND decision.approved_request_hash = v_request.request_hash
            AND decision.approved_request_version = v_request.request_version
            AND decision.expires_at > now()
            AND approver.status = 'active'
            AND approver.deleted_at IS NULL
            AND public.platform_user_holds_permission(
                    decision.approver_platform_user_id, seat.required_permission
                ) IS TRUE
      ) < seat.required_approvers;

    RETURN v_unmet = 0;
END;
$function$;

REVOKE ALL ON FUNCTION public.root_appointment_quorum_satisfied(pg_catalog.uuid)
    FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.root_appointment_quorum_satisfied(pg_catalog.uuid)
    TO pms_platform;

COMMENT ON FUNCTION public.root_appointment_quorum_satisfied(pg_catalog.uuid) IS
    'True only when every seat of the request approval policy has its required distinct, unexpired, still-effective and still-permitted approvers for the current request version.';

-- -----------------------------------------------------------------------------
-- 6. Runtime privileges
-- -----------------------------------------------------------------------------

GRANT SELECT, INSERT, UPDATE ON TABLE public.platform_root_appointment_requests TO pms_platform;
GRANT SELECT, INSERT ON TABLE public.platform_root_appointment_approvals TO pms_platform;
GRANT SELECT ON TABLE public.platform_root_appointment_requests TO pms_readonly_support;
GRANT SELECT ON TABLE public.platform_root_appointment_approvals TO pms_readonly_support;
