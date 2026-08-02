-- =============================================================================
-- Correct a comment that claims a control the data does not implement
--
-- V82 gave contact_channel_can_receive this description:
--
--   'Delivery eligibility honouring the purpose delivery basis. Security and
--    critical operational notices require a verified active channel but not
--    consent; every other purpose still requires an active consent decision.'
--
-- The function is data-driven: it returns early only when the purpose row says
-- delivery_basis = 'legitimate_interest'. Exactly one purpose is seeded that
-- way, security_notifications. critical_operational_alerts is seeded as
-- 'consent', and PrivilegedAccessEnforcementIntegrationTests asserts it stays
-- that way, so a tenant can still suppress an incident notice by withholding
-- consent.
--
-- The comment is residue from a reclassification that was proposed, caught by
-- that test, and reverted as a product decision rather than a defect fix. The
-- behaviour was correctly left alone; the description was not.
--
-- This changes no behaviour. It is worth a migration anyway, because a comment
-- that describes a control more strongly than it is enforced is the failure
-- this schema has spent V76 to V86 removing, and someone reading the catalog
-- would reasonably believe incident alerts cannot be suppressed.
--
-- Whether they should be suppressible remains open. When it is decided, the
-- fix is one row of communication_purpose_policies and the test that pins the
-- current answer, not this text.
-- =============================================================================

COMMENT ON FUNCTION public.contact_channel_can_receive(
    pg_catalog.uuid, pg_catalog.uuid, pg_catalog.uuid, pg_catalog.text
) IS
    'Delivery eligibility honouring the purpose delivery basis. Every purpose requires a verified active channel. Only purposes whose delivery_basis is legitimate_interest skip the consent check, which today means security_notifications alone; critical_operational_alerts remains consent-based and is therefore suppressible by the recipient.';
