-- Realtime envelope single-delivery doctrine.
--
-- V38 mirrors every platform-scoped outbox event into the realtime journal as a
-- safety net so no committed business event is ever lost to realtime consumers.
-- Since then, the POS and payment modules broadcast their events through the
-- realtime port (RealTimeStreamService.broadcastRealtimeEvent), which appends a
-- canonical envelope with aggregate metadata in the SAME business transaction.
--
-- Events from those modules were therefore journaled twice: once by this
-- trigger in the legacy mirror shape (aggregate fields null, payload wrapped in
-- data/eventType/timestamp/correlationId) and once canonically. Subscribers on
-- the routed destinations received two different envelopes for one committed
-- event, which violates the doctrine that every committed event is delivered
-- exactly once in the canonical shape.
--
-- This migration narrows the mirror to event families that are not yet
-- broadcast by application code. The skip list must be kept in sync with the
-- canonical broadcasters (see RealtimeEventTypes in realtime/api/RealTimePort
-- and the broadcastRealtimeEvent call sites in the pos and payment modules).
CREATE OR REPLACE FUNCTION mirror_property_outbox_to_realtime_journal()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
DECLARE
    v_is_canonical boolean;
BEGIN
    IF NEW.destination = 'platform' AND NEW.property_id IS NOT NULL THEN
        SELECT NEW.event_type LIKE 'pos.order.%'
            OR NEW.event_type LIKE 'pos.kitchen_ticket.%'
            OR NEW.event_type LIKE 'pos.session.%'
            OR NEW.event_type LIKE 'payment.%'
        INTO v_is_canonical;

        IF NOT v_is_canonical THEN
            INSERT INTO realtime_event_journal (
                tenant_id,
                property_id,
                event_type,
                payload,
                created_at
            )
            VALUES (
                NEW.tenant_id,
                NEW.property_id,
                NEW.event_type,
                jsonb_build_object(
                    'eventType', NEW.event_type,
                    'timestamp', NEW.created_at,
                    'correlationId', NEW.correlation_id,
                    'data', NEW.payload
                ),
                NEW.created_at
            );
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION mirror_property_outbox_to_realtime_journal() FROM PUBLIC;

COMMENT ON FUNCTION mirror_property_outbox_to_realtime_journal() IS
    'Mirrors non-canonical platform outbox events into durable realtime replay. '
    'Events broadcast canonically by application code (pos.order.*, '
    'pos.kitchen_ticket.*, pos.session.*, payment.*) are skipped so every '
    'committed event reaches subscribers exactly once in the canonical envelope.';