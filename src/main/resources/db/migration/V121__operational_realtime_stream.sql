-- V121 — a till's PIN session must be able to watch the stream it trades on.
--
-- realtime.stream was left at the column default 'strong' because V114 classified
-- the work a waiter does, not the delivery of events about that work. After
-- SessionClass is enforced, a device-bound operational session would be refused
-- REST backfill and would only reach STOMP if the subscription authorizer forgot
-- to check the class — a side door, not a design. The stream is how a till learns
-- that an order was sent or a payment settled; it is operational.

UPDATE permission_catalog
SET minimum_session_class = 'operational',
    updated_at = now()
WHERE code = 'realtime.stream';

DO $migration$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM permission_catalog
        WHERE code = 'realtime.stream'
          AND minimum_session_class = 'operational'
    ) THEN
        RAISE EXCEPTION
            'realtime.stream must be operational so a PIN session can backfill and subscribe';
    END IF;
END;
$migration$;
