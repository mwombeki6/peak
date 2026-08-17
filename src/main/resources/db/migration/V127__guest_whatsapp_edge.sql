-- V127 — guests of a property can be reached on WhatsApp through the existing
-- communication outbox. This is not a second WhatsApp product.
--
-- Slice 4 shipped Beem send-only. WhatsApp stayed unrouted until a from-number
-- exists, and there was no delivery receipt. Guests already have phone numbers;
-- tenant_contacts already have WhatsApp channels, consent, templates, and the
-- notification outbox. This migration:
--   1. lets a property guest own a contact channel without appearing in the
--      tenant ops contact list (origin_guest_id)
--   2. names the guest-facing consent purposes (reservation, folio, payment
--      prompt, check-in)
--   3. registers the public Beem delivery-receipt route — inbound chat is
--      not a product; the webhook only updates delivery state
--
-- Beem Moja session text is still a 24-hour window. Peak does not invent a
-- template-broadcast host; Beem's public WhatsApp template sample has no URL.

ALTER TABLE public.tenant_contacts
    ADD COLUMN IF NOT EXISTS origin_guest_id uuid;

ALTER TABLE public.tenant_contacts
    ADD CONSTRAINT fk_tenant_contacts_origin_guest
        FOREIGN KEY (tenant_id, origin_guest_id)
        REFERENCES public.guests (tenant_id, id)
        DEFERRABLE;

CREATE UNIQUE INDEX IF NOT EXISTS idx_tenant_contacts_origin_guest_active
    ON public.tenant_contacts (tenant_id, origin_guest_id)
    WHERE origin_guest_id IS NOT NULL AND deleted_at IS NULL;

COMMENT ON COLUMN public.tenant_contacts.origin_guest_id IS
    'When set, this contact is a property guest''s WhatsApp/SMS edge, not a tenant '
    'ops contact. listContacts excludes these rows. Consent and channels stay on '
    'the existing communication tables.';

INSERT INTO public.communication_purpose_policies (
    purpose, delivery_basis, requires_verified_channel, description
) VALUES
    ('guest_reservation', 'consent', true,
     'Reservation confirmation and amendment notices to a property guest.'),
    ('guest_folio', 'consent', true,
     'Folio and stay-account notices to a property guest.'),
    ('guest_payment_prompt', 'consent', true,
     'Payment request notices to a property guest. Peak never holds the money.'),
    ('guest_check_in', 'consent', true,
     'Check-in welcome notices to a property guest.')
ON CONFLICT (purpose) DO UPDATE SET
    delivery_basis = EXCLUDED.delivery_basis,
    requires_verified_channel = EXCLUDED.requires_verified_channel,
    description = EXCLUDED.description;

ALTER TABLE public.communication_consents
    DROP CONSTRAINT IF EXISTS chk_communication_consents_purpose;

ALTER TABLE public.communication_consents
    ADD CONSTRAINT chk_communication_consents_purpose CHECK (
        (purpose)::text = ANY ((ARRAY[
            'operational_reports',
            'critical_operational_alerts',
            'billing_communications',
            'security_notifications',
            'service_notifications',
            'marketing',
            'guest_reservation',
            'guest_folio',
            'guest_payment_prompt',
            'guest_check_in'
        ])::text[])
    );

INSERT INTO public.module_access_matrix (
    module_id, screen_key, screen_label, http_method, api_pattern,
    permission_code, route_scope, guard_mode, access_scope,
    is_tanzania_v1, is_enabled_by_default, notes
) VALUES
    ('communications', 'communications.guest_whatsapp.register',
     'Guest WhatsApp Channel', 'POST',
     '/api/properties/:propertyId/guests/:guestId/whatsapp-channel',
     'guests.manage', 'property', 'staff_permission', 'property', true, true,
     'Front desk records a property guest WhatsApp number and stay-notice consent. '
     'Uses existing contact channels and consents; not a tenant ops contact.'),
    ('communications', 'communications.webhooks.beem.whatsapp',
     'Beem WhatsApp Delivery Receipt', 'POST',
     '/api/communication/webhooks/beem/whatsapp/:transactionId/:signature',
     NULL, 'public', 'public_token', 'tenant', true, true,
     'Beem Moja callback_url. Signature is HMAC of transaction_id with the Beem '
     'secret. Updates delivery state only; inbound chat bodies are discarded.')
ON CONFLICT DO NOTHING;

DO $migration$
BEGIN
    IF EXISTS (
        SELECT 1 FROM public.module_access_matrix
        WHERE api_pattern LIKE '/api/communication/webhooks/%'
          AND (route_scope <> 'public' OR guard_mode <> 'public_token')
    ) THEN
        RAISE EXCEPTION
            'Beem WhatsApp webhook routes must be public/public_token, or receipts '
            'are denied at runtime while the migration looks fine';
    END IF;
END;
$migration$;

-- Beem posts to a public route with no tenant. Delivery rows sit behind FORCE
-- RLS keyed on current_tenant_id(), which is null until Peak knows whose
-- receipt this is. Same chicken-and-egg as resolve_payment_webhook_scope:
-- a SECURITY DEFINER owned by a NOBYPASSRLS role with an explicit read policy.
DO $migration$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pms_communication_webhook_scope_owner') THEN
        CREATE ROLE pms_communication_webhook_scope_owner
            NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
    ELSE
        ALTER ROLE pms_communication_webhook_scope_owner
            NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
    END IF;
END;
$migration$;

GRANT SELECT ON communication_delivery_requests TO pms_communication_webhook_scope_owner;
GRANT SELECT ON communication_delivery_attempts TO pms_communication_webhook_scope_owner;

CREATE POLICY communication_webhook_scope_owner_reads_requests
    ON communication_delivery_requests
    FOR SELECT
    TO pms_communication_webhook_scope_owner
    USING (true);

CREATE POLICY communication_webhook_scope_owner_reads_attempts
    ON communication_delivery_attempts
    FOR SELECT
    TO pms_communication_webhook_scope_owner
    USING (true);

CREATE OR REPLACE FUNCTION resolve_beem_whatsapp_delivery_scope(
    p_outbox_event_id uuid
) RETURNS TABLE (
    tenant_id uuid,
    delivery_request_id uuid
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
    SELECT dr.tenant_id, dr.id
    FROM communication_delivery_attempts da
    JOIN communication_delivery_requests dr
      ON dr.tenant_id = da.tenant_id
     AND dr.id = da.delivery_request_id
    WHERE da.outbox_event_id = p_outbox_event_id
      AND da.provider = 'beem'
    ORDER BY da.attempt_number DESC
    LIMIT 1;
$$;

ALTER FUNCTION resolve_beem_whatsapp_delivery_scope(uuid)
    OWNER TO pms_communication_webhook_scope_owner;

REVOKE ALL ON FUNCTION resolve_beem_whatsapp_delivery_scope(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION resolve_beem_whatsapp_delivery_scope(uuid) TO pms_app;

DO $migration$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_proc proc
        JOIN pg_roles owner ON owner.oid = proc.proowner
        WHERE proc.proname = 'resolve_beem_whatsapp_delivery_scope'
          AND (owner.rolsuper OR owner.rolbypassrls)
    ) THEN
        RAISE EXCEPTION
            'resolve_beem_whatsapp_delivery_scope must not be owned by a superuser or a BYPASSRLS role';
    END IF;
END;
$migration$;
