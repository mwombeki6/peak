-- =============================================================================
-- Tenant-visible privileged access evidence and a non-consent notification basis
--
-- Two problems are addressed together because they serve the same promise: a
-- tenant must be able to see that Peak entered their operational world, and
-- must not be able to be kept from being told.
--
-- 1. Notification eligibility.
--    contact_channel_has_active_consent returns false unless an explicit active
--    consent row exists for the exact purpose. Security notices routed through
--    it therefore default to silence: a tenant who never opted in to
--    'security_notifications', or who opted out, would never learn that
--    privileged access occurred. Consent is the correct basis for marketing and
--    for optional operational mail. It is the wrong basis for telling someone
--    their data was accessed, which is a security obligation rather than an
--    offer.
--
--    Purposes now carry an explicit delivery basis. Consent-based purposes
--    behave exactly as before. Legitimate-interest purposes require a verified,
--    active channel but cannot be suppressed by withholding consent.
--
-- 2. Evidence.
--    A tenant-readable timeline of privileged access, assembled from the grant
--    lifecycle and the append-only usage ledger, with internal decision notes
--    excluded.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. Delivery basis per communication purpose
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS public.communication_purpose_policies (
    purpose text PRIMARY KEY,
    delivery_basis text NOT NULL,
    requires_verified_channel boolean NOT NULL DEFAULT true,
    description text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT chk_communication_purpose_basis CHECK (
        delivery_basis IN ('consent', 'legitimate_interest')
    )
);

COMMENT ON TABLE public.communication_purpose_policies IS
    'Delivery basis for each communication purpose. Consent-based purposes require an active consent decision; legitimate-interest purposes are security and safety obligations that a recipient cannot switch off.';

INSERT INTO public.communication_purpose_policies (
    purpose, delivery_basis, requires_verified_channel, description
) VALUES
    ('marketing', 'consent', true,
     'Promotional communication. Always requires explicit consent.'),
    ('operational_reports', 'consent', true,
     'Scheduled operational reporting. Opt-in.'),
    ('billing_communications', 'consent', true,
     'Billing and commercial correspondence. Opt-in.'),
    ('service_notifications', 'consent', true,
     'Product and service notices. Opt-in.'),
    -- Deliberately left consent-based. Peak today lets a tenant revoke consent
    -- for critical operational alerts, and that behaviour is asserted by an
    -- existing test. Whether an incident notice should be suppressible is a
    -- product decision about tenant-facing behaviour, not a consequence of
    -- fixing privileged-access notification, so it is raised for review rather
    -- than changed here.
    ('critical_operational_alerts', 'consent', true,
     'Incidents materially affecting the tenant operation. Currently opt-in.'),
    ('security_notifications', 'legitimate_interest', true,
     'Security events including privileged Peak staff access to tenant data. Cannot be suppressed by withholding consent.')
ON CONFLICT (purpose) DO UPDATE SET
    delivery_basis = EXCLUDED.delivery_basis,
    requires_verified_channel = EXCLUDED.requires_verified_channel,
    description = EXCLUDED.description;

-- -----------------------------------------------------------------------------
-- 2. Eligibility that respects the basis
-- -----------------------------------------------------------------------------
-- The existing consent predicate is retained unchanged for consent purposes.
-- This function decides eligibility, delegating to it only where consent is
-- actually the basis.

CREATE OR REPLACE FUNCTION public.contact_channel_can_receive(
    p_tenant_id pg_catalog.uuid,
    p_contact_id pg_catalog.uuid,
    p_contact_channel_id pg_catalog.uuid,
    p_purpose pg_catalog.text
) RETURNS pg_catalog.bool
LANGUAGE plpgsql
STABLE
SET search_path = pg_catalog, public, pg_temp
AS $function$
DECLARE
    v_policy public.communication_purpose_policies%ROWTYPE;
    v_channel_ok boolean;
BEGIN
    SELECT *
    INTO v_policy
    FROM public.communication_purpose_policies AS policy
    WHERE policy.purpose = p_purpose;

    -- An unknown purpose is never deliverable. A typo must not silently widen
    -- eligibility, and it must not silently suppress a security notice either;
    -- it fails loudly at the call site instead.
    IF NOT FOUND THEN
        RETURN false;
    END IF;

    SELECT channel.is_active
       AND channel.verification_status = 'verified'
    INTO v_channel_ok
    FROM public.contact_channels AS channel
    WHERE channel.id = p_contact_channel_id
      AND channel.tenant_id = p_tenant_id
      AND channel.contact_id = p_contact_id;

    IF v_channel_ok IS NOT TRUE THEN
        RETURN false;
    END IF;

    IF v_policy.delivery_basis = 'legitimate_interest' THEN
        RETURN true;
    END IF;

    RETURN public.contact_channel_has_active_consent(
        p_tenant_id, p_contact_id, p_contact_channel_id, p_purpose
    );
END;
$function$;

REVOKE ALL ON FUNCTION public.contact_channel_can_receive(
    pg_catalog.uuid, pg_catalog.uuid, pg_catalog.uuid, pg_catalog.text
) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.contact_channel_can_receive(
    pg_catalog.uuid, pg_catalog.uuid, pg_catalog.uuid, pg_catalog.text
) TO pms_app, pms_worker, pms_platform;

COMMENT ON FUNCTION public.contact_channel_can_receive(
    pg_catalog.uuid, pg_catalog.uuid, pg_catalog.uuid, pg_catalog.text
) IS
    'Delivery eligibility honouring the purpose delivery basis. Security and critical operational notices require a verified active channel but not consent; every other purpose still requires an active consent decision.';

GRANT SELECT ON TABLE public.communication_purpose_policies
    TO pms_app, pms_worker, pms_platform, pms_readonly_support;

-- -----------------------------------------------------------------------------
-- 3. Tenant-readable privileged access evidence
-- -----------------------------------------------------------------------------
-- One timeline per tenant covering the grant lifecycle and every use, denied or
-- consumed. Internal decision notes are deliberately excluded: the tenant is
-- entitled to know that access happened, by whom, under which ticket and for
-- which operation, not to read Peak's internal case commentary.

CREATE OR REPLACE VIEW public.tenant_privileged_access_evidence AS
SELECT
    access.tenant_id,
    access.id AS access_id,
    access.support_ticket_id,
    access.platform_user_id,
    operator.full_name AS operator_name,
    access.action_code,
    access.operation_code,
    access.reason,
    'grant_' || access.status AS event_type,
    access.requested_at AS occurred_at,
    access.starts_at,
    access.expires_at,
    access.max_uses,
    access.use_count,
    NULL::text AS denial_reason
FROM public.platform_break_glass_access AS access
JOIN public.platform_users AS operator
  ON operator.id = access.platform_user_id

UNION ALL

SELECT
    usage.tenant_id,
    usage.access_id,
    access.support_ticket_id,
    usage.platform_user_id,
    operator.full_name AS operator_name,
    access.action_code,
    usage.operation_code,
    access.reason,
    'use_' || usage.decision AS event_type,
    usage.occurred_at,
    access.starts_at,
    access.expires_at,
    access.max_uses,
    access.use_count,
    usage.denial_reason
FROM public.platform_privileged_access_usage AS usage
JOIN public.platform_break_glass_access AS access
  ON access.id = usage.access_id
JOIN public.platform_users AS operator
  ON operator.id = usage.platform_user_id;

COMMENT ON VIEW public.tenant_privileged_access_evidence IS
    'Tenant-readable timeline of privileged Peak staff access: grant lifecycle and every consumed or denied use. Internal decision notes are excluded.';

GRANT SELECT ON public.tenant_privileged_access_evidence
    TO pms_app, pms_platform, pms_readonly_support;
