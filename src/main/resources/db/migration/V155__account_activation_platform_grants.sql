-- Account activation and recovery run in the platform runtime as well as the
-- app runtime: peak-platform serves the same /api/v1/invitations/* endpoints
-- that the platform console's accept flow calls. V154 granted the backing
-- functions and table to pms_app only, so the platform runtime failed with
-- "permission denied for function lookup_invitation_by_token_hash".
-- The worker does not execute this flow; it only dispatches the outbox events
-- the flow enqueues, which it already owns.
GRANT EXECUTE ON FUNCTION lookup_invitation_by_token_hash(text) TO pms_platform;
GRANT EXECUTE ON FUNCTION insert_account_setup_grant(text, uuid, uuid, text, text, integer) TO pms_platform;
GRANT EXECUTE ON FUNCTION consume_account_setup_grant(text) TO pms_platform;
GRANT SELECT, INSERT, UPDATE ON account_setup_grants TO pms_platform;

-- The same activation path requests and confirms verification challenges, and
-- V144 granted those functions to pms_app only. The platform runtime hits the
-- identical failure the moment a code is dispatched.
GRANT EXECUTE ON FUNCTION request_verification_challenge(uuid, text, text, text, text, double precision, integer) TO pms_platform;
GRANT EXECUTE ON FUNCTION confirm_verification_challenge(text, text, text) TO pms_platform;

-- The realtime journal health indicator runs in every servlet runtime. V35
-- granted the sequence probe to pms_app only, so peak-platform reported the
-- journal DOWN and the aggregate /actuator/health went DOWN with it.
GRANT EXECUTE ON FUNCTION latest_realtime_event_sequence() TO pms_platform;