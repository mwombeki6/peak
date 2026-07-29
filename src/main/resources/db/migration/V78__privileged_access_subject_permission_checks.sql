-- =============================================================================
-- Subject-oriented permission checks for approval seats
--
-- `platform_user_has_permission` answers "does the CURRENT session user hold
-- this permission": its first predicate is
-- `p_platform_user_id = current_platform_user_id()`. Evaluating a third party
-- with it yields NULL rather than false whenever no platform session is bound,
-- because `NULL AND true AND true` is NULL.
--
-- That is unsafe in two directions:
--   * In a WHERE clause the approver silently stops counting, so a legitimate
--     quorum can never be satisfied.
--   * In `IF NOT <expr>` a NULL is not true, so the guard fails OPEN and admits
--     an approval from someone who does not hold the seat permission.
--
-- Approval evaluation is inherently about other people, so it needs a
-- subject-oriented check. This migration adds one and rewires the approval
-- trigger and quorum evaluation onto it, using NULL-safe comparisons.
--
-- The wildcard `platform.admin.all` deliberately does NOT satisfy an approval
-- seat. Emergency authority must not silently fill a security or finance seat;
-- if too few qualified people exist the operation stays unavailable rather than
-- quietly reducing the quorum.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Row-level security accommodation for the consuming definer
-- -----------------------------------------------------------------------------
-- `pms_privileged_access_owner` is deliberately NOBYPASSRLS, so it is subject to
-- row-level security on every table `consume_privileged_access` reads. Those
-- policies call `platform_user_has_permission` and the session accessors, which
-- the role could not execute, and they evaluate against a bound platform
-- session that a consuming call does not have.
--
-- Two accommodations are required, and both are deliberately narrow:
--   * EXECUTE on the functions the existing policies invoke, so policy
--     evaluation does not fail with permission denied.
--   * Role-scoped permissive policies so the definer can see exactly the rows
--     its body already validates. Combined with the column-level grants from
--     V76, the role can read only id/status/deleted_at style columns and can
--     update only the use counters.
--
-- The role cannot log in, and every authorization decision remains inside the
-- function body, so this does not widen any runtime principal's reach.

GRANT EXECUTE ON FUNCTION public.platform_user_has_permission(uuid, text)
    TO pms_privileged_access_owner;
GRANT EXECUTE ON FUNCTION public.current_platform_user_id()
    TO pms_privileged_access_owner;
GRANT EXECUTE ON FUNCTION public.current_tenant_id()
    TO pms_privileged_access_owner;

DROP POLICY IF EXISTS privileged_access_consumer
    ON public.platform_break_glass_access;
CREATE POLICY privileged_access_consumer
    ON public.platform_break_glass_access
    FOR ALL
    TO pms_privileged_access_owner
    USING (true)
    WITH CHECK (true);

DROP POLICY IF EXISTS privileged_access_operator_reader
    ON public.platform_users;
CREATE POLICY privileged_access_operator_reader
    ON public.platform_users
    FOR SELECT
    TO pms_privileged_access_owner
    USING (true);

DROP POLICY IF EXISTS privileged_access_ticket_reader
    ON public.support_tickets;
CREATE POLICY privileged_access_ticket_reader
    ON public.support_tickets
    FOR SELECT
    TO pms_privileged_access_owner
    USING (true);

CREATE OR REPLACE FUNCTION public.platform_user_holds_permission(
    p_platform_user_id pg_catalog.uuid,
    p_permission_code pg_catalog.text
) RETURNS pg_catalog.bool
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $function$
    SELECT EXISTS (
        SELECT 1
        FROM public.platform_users AS operator
        JOIN public.platform_user_roles AS assignment
          ON assignment.platform_user_id = operator.id
        JOIN public.platform_roles AS role
          ON role.id = assignment.platform_role_id
        JOIN public.platform_role_permissions AS grant_row
          ON grant_row.platform_role_id = role.id
        JOIN public.platform_permissions AS permission
          ON permission.id = grant_row.platform_permission_id
        WHERE operator.id = p_platform_user_id
          AND operator.status = 'active'
          AND operator.deleted_at IS NULL
          AND role.is_active = true
          AND permission.code = p_permission_code
    );
$function$;

REVOKE ALL ON FUNCTION public.platform_user_holds_permission(
    pg_catalog.uuid, pg_catalog.text
) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.platform_user_holds_permission(
    pg_catalog.uuid, pg_catalog.text
) TO pms_platform;
GRANT EXECUTE ON FUNCTION public.platform_user_holds_permission(
    pg_catalog.uuid, pg_catalog.text
) TO pms_privileged_access_owner;

COMMENT ON FUNCTION public.platform_user_holds_permission(
    pg_catalog.uuid, pg_catalog.text
) IS
    'Subject-oriented permission check for an arbitrary platform user. Returns a strict boolean and never depends on the bound session identity. The platform.admin.all wildcard does not satisfy it, so emergency authority cannot silently occupy an approval seat.';

-- -----------------------------------------------------------------------------
-- Rewire approval admissibility onto the subject check
-- -----------------------------------------------------------------------------

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

    IF NEW.approver_platform_user_id = v_access.platform_user_id THEN
        RAISE EXCEPTION USING
            ERRCODE = '42501',
            MESSAGE = 'Requester cannot approve their own privileged access request';
    END IF;

    IF NEW.approved_request_hash IS DISTINCT FROM v_access.request_hash
       OR NEW.approved_request_version IS DISTINCT FROM v_access.request_version THEN
        RAISE EXCEPTION USING
            ERRCODE = '42501',
            MESSAGE = 'Approval does not match the current privileged access request version';
    END IF;

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

    -- NULL-safe: anything other than an explicit true rejects the approval.
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

-- -----------------------------------------------------------------------------
-- Rewire quorum evaluation onto the subject check
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

    IF v_seat_total = 0 THEN
        RETURN false;
    END IF;

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
            AND public.platform_user_holds_permission(
                    decision.approver_platform_user_id, seat.required_permission
                ) IS TRUE
      ) < seat.required_approvers;

    RETURN v_unmet = 0;
END;
$function$;
