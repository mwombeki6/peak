-- V117: Realtime event envelope evolution + POS aggregate versions
--
-- 1. The realtime journal gains the canonical event envelope routing fields:
--      schema_version, aggregate_type, aggregate_id, aggregate_version, outlet_id
--    The envelope is the contract between backend and realtime subscribers:
--      { eventId, type, schemaVersion, aggregateType, aggregateId,
--        aggregateVersion, occurredAt, tenantId, propertyId, outletId, payload }
--    Tenant/property/outlet routing context lets fanout target scoped
--    destinations (/topic/outlets/{o}/orders, /topic/orders/{id}, ...).
--
-- 2. pos_orders and kitchen_tickets gain a DB-managed `version` column.
--    Neither aggregate had any existing versioning (plain JDBC, no JPA @Version),
--    so the database trigger is the single coherent mechanism, mirroring the
--    payment_transactions.status_version precedent (V41). aggregateVersion in
--    realtime events is read from this column after commit.

ALTER TABLE realtime_event_journal
    ADD COLUMN IF NOT EXISTS schema_version integer NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS aggregate_type text,
    ADD COLUMN IF NOT EXISTS aggregate_id uuid,
    ADD COLUMN IF NOT EXISTS aggregate_version bigint,
    ADD COLUMN IF NOT EXISTS outlet_id uuid;

ALTER TABLE realtime_event_journal
    ADD CONSTRAINT chk_realtime_event_journal_schema_version
        CHECK (schema_version >= 1),
    ADD CONSTRAINT chk_realtime_event_journal_aggregate_version
        CHECK (aggregate_version IS NULL OR aggregate_version >= 0),
    ADD CONSTRAINT chk_realtime_event_journal_aggregate_pair
        CHECK (
            (aggregate_type IS NULL AND aggregate_id IS NULL)
            OR (aggregate_type IS NOT NULL AND aggregate_id IS NOT NULL)
        );

CREATE INDEX IF NOT EXISTS idx_realtime_event_journal_aggregate
    ON realtime_event_journal (aggregate_type, aggregate_id, sequence_id);
CREATE INDEX IF NOT EXISTS idx_realtime_event_journal_outlet
    ON realtime_event_journal (tenant_id, outlet_id, sequence_id);

-- Envelope-aware append. Overloads the V35 function so existing 4-argument
-- callers keep working; the old signature resolves to its own overload.
CREATE OR REPLACE FUNCTION append_realtime_event(
    p_tenant_id uuid,
    p_property_id uuid,
    p_event_type text,
    p_payload jsonb,
    p_outlet_id uuid DEFAULT NULL,
    p_schema_version integer DEFAULT 1,
    p_aggregate_type text DEFAULT NULL,
    p_aggregate_id uuid DEFAULT NULL,
    p_aggregate_version bigint DEFAULT NULL
) RETURNS TABLE (
    sequence_id bigint,
    event_id uuid,
    tenant_id uuid,
    property_id uuid,
    event_type text,
    payload jsonb,
    created_at timestamptz,
    schema_version integer,
    aggregate_type text,
    aggregate_id uuid,
    aggregate_version bigint,
    outlet_id uuid
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM properties p
        JOIN tenants t ON t.id = p.tenant_id
        WHERE p.tenant_id = p_tenant_id
          AND p.id = p_property_id
          AND p.deleted_at IS NULL
          AND t.deleted_at IS NULL
    ) THEN
        RAISE EXCEPTION 'Realtime property scope does not exist';
    END IF;

    IF p_outlet_id IS NOT NULL AND NOT EXISTS (
        SELECT 1
        FROM outlets o
        WHERE o.tenant_id = p_tenant_id
          AND o.property_id = p_property_id
          AND o.id = p_outlet_id
          AND o.deleted_at IS NULL
    ) THEN
        RAISE EXCEPTION 'Realtime outlet scope does not exist';
    END IF;

    IF (p_aggregate_type IS NULL) != (p_aggregate_id IS NULL) THEN
        RAISE EXCEPTION 'Realtime aggregate type and id must be provided together';
    END IF;

    IF p_aggregate_version IS NOT NULL AND p_aggregate_version < 0 THEN
        RAISE EXCEPTION 'Realtime aggregate version must be non-negative';
    END IF;

    RETURN QUERY
    INSERT INTO realtime_event_journal (
        tenant_id,
        property_id,
        event_type,
        payload,
        outlet_id,
        schema_version,
        aggregate_type,
        aggregate_id,
        aggregate_version
    )
    VALUES (
        p_tenant_id,
        p_property_id,
        p_event_type,
        p_payload,
        p_outlet_id,
        p_schema_version,
        p_aggregate_type,
        p_aggregate_id,
        p_aggregate_version
    )
    RETURNING
        realtime_event_journal.sequence_id,
        realtime_event_journal.event_id,
        realtime_event_journal.tenant_id,
        realtime_event_journal.property_id,
        realtime_event_journal.event_type,
        realtime_event_journal.payload,
        realtime_event_journal.created_at,
        realtime_event_journal.schema_version,
        realtime_event_journal.aggregate_type,
        realtime_event_journal.aggregate_id,
        realtime_event_journal.aggregate_version,
        realtime_event_journal.outlet_id;
END;
$$;

REVOKE ALL ON FUNCTION append_realtime_event(uuid, uuid, text, jsonb, uuid, integer, text, uuid, bigint) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION append_realtime_event(uuid, uuid, text, jsonb, uuid, integer, text, uuid, bigint) TO pms_app;

-- ─────────────────────────────────────────────────────────────────────
-- POS aggregate versions
-- ─────────────────────────────────────────────────────────────────────

CREATE OR REPLACE FUNCTION bump_committed_aggregate_version() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.version := OLD.version + 1;
    RETURN NEW;
END;
$$;

ALTER TABLE pos_orders
    ADD COLUMN IF NOT EXISTS version bigint NOT NULL DEFAULT 0;
ALTER TABLE pos_orders
    ADD CONSTRAINT chk_pos_orders_version CHECK (version >= 0);

DROP TRIGGER IF EXISTS trg_pos_orders_version ON pos_orders;
CREATE TRIGGER trg_pos_orders_version
    BEFORE UPDATE ON pos_orders
    FOR EACH ROW
    EXECUTE FUNCTION bump_committed_aggregate_version();

ALTER TABLE kitchen_tickets
    ADD COLUMN IF NOT EXISTS version bigint NOT NULL DEFAULT 0;
ALTER TABLE kitchen_tickets
    ADD CONSTRAINT chk_kitchen_tickets_version CHECK (version >= 0);

DROP TRIGGER IF EXISTS trg_kitchen_tickets_version ON kitchen_tickets;
CREATE TRIGGER trg_kitchen_tickets_version
    BEFORE UPDATE ON kitchen_tickets
    FOR EACH ROW
    EXECUTE FUNCTION bump_committed_aggregate_version();

GRANT SELECT, UPDATE (version) ON TABLE pos_orders TO pms_app;
GRANT SELECT, UPDATE (version) ON TABLE kitchen_tickets TO pms_app;