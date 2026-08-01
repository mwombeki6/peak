-- =============================================================================
-- Row-level security for the privileged access ledger, and indexes for the
-- per-entity trail tables
--
-- Two unrelated findings from a full-system review, grouped because both are
-- narrow schema corrections with no behavioural change.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. The usage ledger was the only tenant-bearing table without row-level
--    security
-- -----------------------------------------------------------------------------
-- Of 212 tables carrying a tenant_id, this was the single one relying on table
-- grants alone. It is not currently reachable by a tenant runtime: peak_app has
-- no grant and is refused outright. The gap is that grants are the only layer,
-- so a later migration that hands SELECT to another role would silently expose
-- every tenant's access history with nothing behind it.
--
-- Enabling row-level security inverts that default. A role with no policy reads
-- nothing regardless of its grants, so a future grant fails closed and has to be
-- paired with a deliberate policy before it does anything.
--
-- The policies below are permissive for the three roles that already hold
-- grants, because each is meant to see the whole ledger: platform operators and
-- read-only support review privileged access across tenants, which is the point
-- of the record. This does not narrow what those roles can read today. It
-- narrows what any other role can read tomorrow.
--
-- Note what this deliberately does NOT protect. Tenants read their own history
-- through tenant_privileged_access_evidence, a view owned by the migration role
-- and created without security_invoker, so it executes with the owner's rights
-- and is not constrained by these policies. The control there is the view's own
-- `WHERE tenant_id = current_tenant_id()` filter from V83, proven over HTTP by
-- TenantPrivilegedAccessEvidenceControllerIntegrationTests. Row-level security
-- on this table is a second layer for direct readers, not the tenant control.

ALTER TABLE public.platform_privileged_access_usage ENABLE ROW LEVEL SECURITY;

-- consume_privileged_access runs as pms_privileged_access_owner, which is
-- NOBYPASSRLS, and both reads this table for execution-id deduplication and
-- appends to it. Without these two policies privileged access consumption stops
-- working entirely, so they are the load-bearing pair.
--
-- SELECT and INSERT are granted separately rather than as FOR ALL. The table
-- rejects UPDATE and DELETE through an append-only trigger, so a broader policy
-- would advertise permissions that cannot be exercised.
DROP POLICY IF EXISTS privileged_access_usage_consumer_reader
    ON public.platform_privileged_access_usage;
CREATE POLICY privileged_access_usage_consumer_reader
    ON public.platform_privileged_access_usage
    FOR SELECT
    TO pms_privileged_access_owner
    USING (true);

DROP POLICY IF EXISTS privileged_access_usage_consumer_writer
    ON public.platform_privileged_access_usage;
CREATE POLICY privileged_access_usage_consumer_writer
    ON public.platform_privileged_access_usage
    FOR INSERT
    TO pms_privileged_access_owner
    WITH CHECK (true);

DROP POLICY IF EXISTS privileged_access_usage_platform_reader
    ON public.platform_privileged_access_usage;
CREATE POLICY privileged_access_usage_platform_reader
    ON public.platform_privileged_access_usage
    FOR SELECT
    TO pms_platform
    USING (true);

DROP POLICY IF EXISTS privileged_access_usage_support_reader
    ON public.platform_privileged_access_usage;
CREATE POLICY privileged_access_usage_support_reader
    ON public.platform_privileged_access_usage
    FOR SELECT
    TO pms_readonly_support
    USING (true);

-- -----------------------------------------------------------------------------
-- 2. Per-entity trail tables had no index beyond their primary key
-- -----------------------------------------------------------------------------
-- These four tables are append-only histories that the application writes and
-- never reads: no SELECT exists against any of them. That is why the missing
-- indexes have not hurt yet, and why adding them is cheap to get wrong in the
-- other direction, since every index is a cost on a write-only table.
--
-- One index each, therefore, shaped for the read that will actually arrive
-- first. These are per-entity histories, so the question asked of them is "what
-- happened to this room / task / item / purchase order", and the answer wants
-- the most recent rows first.
--
-- tenant_id leads each index rather than the entity column. Row-level security
-- appends `tenant_id = current_tenant_id()` to every statement, so a leading
-- tenant_id lets that mandatory predicate be satisfied by the index instead of
-- rechecked per row. Without it the planner can still use an entity index, but
-- the isolation predicate never contributes to the scan.
--
-- Growth remains unbounded. room_state_transitions in particular gains a row
-- per room status change, so it outgrows the others by an order of magnitude.
-- Retention and partitioning are deliberately not decided here, because the
-- retention period is a compliance question rather than a schema one.

CREATE INDEX IF NOT EXISTS idx_room_state_transitions_tenant_room_created
    ON public.room_state_transitions (tenant_id, room_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_housekeeping_inspections_tenant_task_created
    ON public.housekeeping_inspections (tenant_id, task_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_lost_and_found_custody_tenant_item_created
    ON public.lost_and_found_custody_events (tenant_id, item_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_purchase_order_approvals_tenant_order_created
    ON public.purchase_order_approvals (tenant_id, purchase_order_id, created_at DESC);
