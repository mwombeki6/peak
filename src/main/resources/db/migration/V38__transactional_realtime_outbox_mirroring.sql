CREATE OR REPLACE FUNCTION mirror_property_outbox_to_realtime_journal()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    IF NEW.destination = 'platform' AND NEW.property_id IS NOT NULL THEN
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
    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION mirror_property_outbox_to_realtime_journal() FROM PUBLIC;

DROP TRIGGER IF EXISTS trg_outbox_realtime_journal ON outbox_events;
CREATE TRIGGER trg_outbox_realtime_journal
    AFTER INSERT ON outbox_events
    FOR EACH ROW
    EXECUTE FUNCTION mirror_property_outbox_to_realtime_journal();

COMMENT ON FUNCTION mirror_property_outbox_to_realtime_journal() IS
    'Atomically mirrors property-scoped platform events into durable realtime replay.';
