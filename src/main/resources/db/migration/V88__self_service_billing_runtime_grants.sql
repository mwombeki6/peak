-- =============================================================================
-- Let the worker runtime apply a paid subscription
--
-- Self-service billing settles in the worker: a provider confirms a payment, the
-- worker verifies it, activates the subscription and converges the tenant's
-- modules. None of that is currently possible.
--
-- tenant_subscriptions and tenant_control_states grant SELECT to pms_app and
-- pms_worker but INSERT/UPDATE only to pms_platform (V71), and their row
-- policies additionally demand a platform permission in WITH CHECK. That is
-- correct for a platform operator changing a tenant's commercial terms by hand,
-- and it is exactly wrong for a tenant paying for themselves: there is no
-- platform user in the loop at all.
--
-- tenant_modules and property_modules grant INSERT/UPDATE to pms_app only
-- (V22), so the reconciler that turns a purchased module on — and, more
-- importantly, turns a lapsed one off — cannot write from the worker either.
--
-- This migration opens the narrowest path that makes settlement work:
--
--   * pms_worker gains write access to the four tables.
--   * pms_worker gains a row policy on the two V71 tables permitting writes
--     only within the tenant its session is bound to.
--
-- pms_app is deliberately left read-only on subscriptions and control states.
-- A tenant user must never be able to move their own subscription by calling an
-- API; the only path to an active subscription is a provider-verified payment
-- applied by the worker. The API runtime writes billing's own tables and
-- enqueues; everything privileged happens after the money is confirmed.
--
-- Row policies are permissive and therefore OR-ed, so the new policy widens
-- pms_worker without altering what any other role may do. The existing
-- tenant_isolation policies on tenant_modules and property_modules already
-- scope writes to current_tenant_id(), so those tables need a grant only.
-- =============================================================================

GRANT SELECT, INSERT, UPDATE ON TABLE
    tenant_modules,
    property_modules
TO pms_worker;

GRANT SELECT, INSERT, UPDATE ON TABLE
    tenant_subscriptions,
    tenant_control_states
TO pms_worker;

-- The pre-existing tenant_or_platform_subscriptions policy carries no TO clause,
-- so it applies to pms_worker too, and its WITH CHECK calls
-- platform_user_has_permission. PostgreSQL evaluates every applicable permissive
-- policy rather than short-circuiting the OR once one passes, so a worker insert
-- fails with "permission denied for function platform_user_has_permission"
-- before the policy added below is ever reached.
--
-- Granting EXECUTE confers no privilege by itself: a worker session binds no
-- platform user, so current_platform_user_id() is NULL and the function returns
-- false. It only lets the existing expression evaluate to false instead of
-- raising, which is what allows the OR to fall through to the tenant-scoped
-- policy below.
GRANT EXECUTE ON FUNCTION platform_user_has_permission(uuid, text) TO pms_worker;

-- Bound to the worker role and to the session's tenant. A worker that has not
-- bound a tenant context has current_tenant_id() NULL, so this grants nothing on
-- its own; DatabaseSessionContext.bind must have run inside the transaction.
CREATE POLICY worker_tenant_subscription_projection ON tenant_subscriptions
    FOR ALL
    TO pms_worker
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

CREATE POLICY worker_tenant_control_state_projection ON tenant_control_states
    FOR ALL
    TO pms_worker
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- Deletion is never part of settlement. Subscriptions are superseded by status,
-- not removed, and the lifecycle history has to survive.
REVOKE DELETE ON TABLE
    tenant_subscriptions,
    tenant_control_states,
    tenant_modules,
    property_modules
FROM pms_worker;

-- platform_billing needs its own outbox destination. Reusing 'platform' would
-- put a second handler on that destination, and OutboxEventDispatcher.handlerFor
-- throws AmbiguousOutboxEventHandlerException on more than one match — which
-- would dead-letter every existing platform event, not just billing's.
ALTER TABLE outbox_events
    DROP CONSTRAINT IF EXISTS chk_outbox_events_destination,
    ADD CONSTRAINT chk_outbox_events_destination CHECK (
        destination IN (
            'fiscal', 'payment', 'notification', 'analytics', 'audit',
            'edge_sync', 'webhook', 'email', 'sms', 'whatsapp', 'pos',
            'housekeeping', 'platform', 'reports', 'platform_billing'
        )
    );

DO $migration$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_catalog.pg_policy p
        JOIN pg_catalog.pg_class c ON c.oid = p.polrelid
        WHERE c.relname = 'tenant_subscriptions'
          AND p.polname = 'worker_tenant_subscription_projection'
    ) THEN
        RAISE EXCEPTION 'worker subscription projection policy was not created';
    END IF;

    IF NOT pg_catalog.has_table_privilege('pms_worker', 'public.tenant_modules', 'UPDATE') THEN
        RAISE EXCEPTION 'pms_worker cannot update tenant_modules; the reconciler would fail';
    END IF;
END;
$migration$;
