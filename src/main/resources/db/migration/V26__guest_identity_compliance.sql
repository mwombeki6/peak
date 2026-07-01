-- Phase 3 guest identity verification and check-in compliance.

ALTER TABLE guests
    ADD COLUMN IF NOT EXISTS origin_property_id uuid;

UPDATE guests g
SET origin_property_id = source.property_id
FROM (
    SELECT DISTINCT ON (r.tenant_id, rg.guest_id)
           r.tenant_id, rg.guest_id, r.property_id
    FROM reservation_guests rg
    JOIN reservations r
      ON r.tenant_id = rg.tenant_id
     AND r.id = rg.reservation_id
    ORDER BY r.tenant_id, rg.guest_id, r.created_at, r.id
) source
WHERE g.tenant_id = source.tenant_id
  AND g.id = source.guest_id
  AND g.origin_property_id IS NULL;

UPDATE guests g
SET origin_property_id = source.property_id
FROM (
    SELECT tenant_id, min(id::text)::uuid AS property_id
    FROM properties
    WHERE deleted_at IS NULL
    GROUP BY tenant_id
    HAVING count(*) = 1
) source
WHERE g.tenant_id = source.tenant_id
  AND g.origin_property_id IS NULL;

ALTER TABLE guests
    DROP CONSTRAINT IF EXISTS fk_guests_origin_property,
    ADD CONSTRAINT fk_guests_origin_property
        FOREIGN KEY (tenant_id, origin_property_id)
        REFERENCES properties(tenant_id, id)
        DEFERRABLE;

CREATE INDEX IF NOT EXISTS idx_guests_origin_property
    ON guests (tenant_id, origin_property_id, created_at DESC)
    WHERE deleted_at IS NULL;

ALTER TABLE guest_documents
    DROP CONSTRAINT IF EXISTS guest_documents_guest_document_key;

ALTER TABLE guest_documents
    ADD COLUMN IF NOT EXISTS document_number_hmac character varying(64),
    ADD COLUMN IF NOT EXISTS document_number_hmac_key_version character varying(32),
    ADD COLUMN IF NOT EXISTS document_number_last4 character varying(4),
    ADD COLUMN IF NOT EXISTS verification_status text DEFAULT 'unverified' NOT NULL,
    ADD COLUMN IF NOT EXISTS verification_method text,
    ADD COLUMN IF NOT EXISTS verification_provider text,
    ADD COLUMN IF NOT EXISTS provider_reference text,
    ADD COLUMN IF NOT EXISTS verification_expires_at timestamp with time zone,
    ADD COLUMN IF NOT EXISTS revoked_at timestamp with time zone,
    ADD COLUMN IF NOT EXISTS revoked_by uuid,
    ADD COLUMN IF NOT EXISTS revocation_reason text;

UPDATE guest_documents
SET document_number_last4 = right(regexp_replace(document_number, '[^[:alnum:]]', '', 'g'), 4),
    document_number = '***' || right(regexp_replace(document_number, '[^[:alnum:]]', '', 'g'), 4),
    verified = false,
    verified_by = NULL,
    verified_at = NULL,
    verification_status = 'legacy_unverified',
    verification_method = NULL,
    verification_provider = NULL,
    provider_reference = NULL
WHERE document_number_hmac IS NULL;

ALTER TABLE guest_documents
    DROP CONSTRAINT IF EXISTS chk_guest_documents_verification_status,
    ADD CONSTRAINT chk_guest_documents_verification_status
        CHECK (verification_status IN (
            'unverified',
            'pending',
            'verified',
            'failed',
            'expired',
            'revoked',
            'legacy_unverified'
        )),
    DROP CONSTRAINT IF EXISTS chk_guest_documents_verification_method,
    ADD CONSTRAINT chk_guest_documents_verification_method
        CHECK (
            verification_method IS NULL
            OR verification_method IN ('nida_cig', 'physical_document')
        ),
    DROP CONSTRAINT IF EXISTS chk_guest_documents_verified_state,
    ADD CONSTRAINT chk_guest_documents_verified_state
        CHECK (
            (verification_status = 'verified' AND verified = true AND verified_at IS NOT NULL)
            OR (verification_status <> 'verified' AND verified = false)
        ),
    DROP CONSTRAINT IF EXISTS fk_guest_documents_revoked_by,
    ADD CONSTRAINT fk_guest_documents_revoked_by
        FOREIGN KEY (tenant_id, revoked_by) REFERENCES users(tenant_id, id) DEFERRABLE;

CREATE UNIQUE INDEX IF NOT EXISTS uq_guest_documents_tenant_type_hmac
    ON guest_documents (tenant_id, document_type, document_number_hmac)
    WHERE document_number_hmac IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_guest_documents_guest_verification
    ON guest_documents (tenant_id, guest_id, verification_status, expires_at);

ALTER TABLE reservation_guests
    ADD COLUMN IF NOT EXISTS relationship_type text DEFAULT 'adult' NOT NULL,
    ADD COLUMN IF NOT EXISTS guardian_guest_id uuid,
    ADD COLUMN IF NOT EXISTS guardian_attested_at timestamp with time zone,
    ADD COLUMN IF NOT EXISTS guardian_attested_by uuid;

ALTER TABLE reservation_guests
    DROP CONSTRAINT IF EXISTS chk_reservation_guests_relationship,
    ADD CONSTRAINT chk_reservation_guests_relationship
        CHECK (relationship_type IN ('adult', 'child', 'dependent')),
    DROP CONSTRAINT IF EXISTS chk_reservation_guests_guardian,
    ADD CONSTRAINT chk_reservation_guests_guardian
        CHECK (
            (relationship_type = 'adult'
                AND guardian_guest_id IS NULL
                AND guardian_attested_at IS NULL
                AND guardian_attested_by IS NULL)
            OR (relationship_type IN ('child', 'dependent')
                AND guardian_guest_id IS NOT NULL
                AND guardian_guest_id <> guest_id
                AND guardian_attested_at IS NOT NULL
                AND guardian_attested_by IS NOT NULL)
        );

CREATE UNIQUE INDEX IF NOT EXISTS uq_reservation_guests_occupant
    ON reservation_guests (tenant_id, reservation_id, guest_id);

ALTER TABLE reservation_guests
    DROP CONSTRAINT IF EXISTS fk_reservation_guests_guardian,
    ADD CONSTRAINT fk_reservation_guests_guardian
        FOREIGN KEY (tenant_id, reservation_id, guardian_guest_id)
        REFERENCES reservation_guests(tenant_id, reservation_id, guest_id)
        DEFERRABLE,
    DROP CONSTRAINT IF EXISTS fk_reservation_guests_guardian_attested_by,
    ADD CONSTRAINT fk_reservation_guests_guardian_attested_by
        FOREIGN KEY (tenant_id, guardian_attested_by)
        REFERENCES users(tenant_id, id)
        DEFERRABLE;

CREATE TABLE guest_identity_verification_attempts (
    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    guest_id uuid NOT NULL,
    guest_document_id uuid NOT NULL,
    verification_method text NOT NULL,
    provider text NOT NULL,
    status text NOT NULL,
    provider_reference text,
    failure_code text,
    attestation_reason text,
    requested_by uuid NOT NULL,
    completed_by uuid,
    idempotency_key_id uuid NOT NULL,
    started_at timestamp with time zone DEFAULT now() NOT NULL,
    completed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_guest_identity_attempt_method
        CHECK (verification_method IN ('nida_cig', 'physical_document')),
    CONSTRAINT chk_guest_identity_attempt_status
        CHECK (status IN ('pending', 'verified', 'rejected', 'unavailable', 'failed')),
    CONSTRAINT chk_guest_identity_attempt_completion
        CHECK (
            (status = 'pending' AND completed_at IS NULL)
            OR (status <> 'pending' AND completed_at IS NOT NULL)
        ),
    CONSTRAINT fk_guest_identity_attempt_property
        FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_guest_identity_attempt_guest
        FOREIGN KEY (tenant_id, guest_id) REFERENCES guests(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_guest_identity_attempt_document
        FOREIGN KEY (tenant_id, guest_document_id) REFERENCES guest_documents(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_guest_identity_attempt_requested_by
        FOREIGN KEY (tenant_id, requested_by) REFERENCES users(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_guest_identity_attempt_completed_by
        FOREIGN KEY (tenant_id, completed_by) REFERENCES users(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_guest_identity_attempt_idempotency
        FOREIGN KEY (tenant_id, idempotency_key_id) REFERENCES idempotency_keys(tenant_id, id) DEFERRABLE,
    CONSTRAINT uq_guest_identity_attempt_idempotency UNIQUE (tenant_id, idempotency_key_id)
);

CREATE INDEX idx_guest_identity_attempt_guest
    ON guest_identity_verification_attempts (tenant_id, guest_id, started_at DESC);

CREATE INDEX idx_guest_identity_attempt_provider_status
    ON guest_identity_verification_attempts (provider, status, started_at DESC);

ALTER TABLE guest_identity_verification_attempts ENABLE ROW LEVEL SECURITY;
ALTER TABLE ONLY guest_identity_verification_attempts FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON guest_identity_verification_attempts
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

INSERT INTO permission_catalog (
    code,
    namespace,
    access_scope,
    description,
    is_platform_permission,
    is_tenant_permission
) VALUES
    ('guests.identity.view', 'reservations', 'property', 'View masked guest identity and verification status', false, true),
    ('guests.identity.manage', 'reservations', 'property', 'Submit and revoke guest identity documents', false, true),
    ('guests.identity.verify', 'reservations', 'property', 'Verify guest identity through an approved provider', false, true),
    ('guests.identity.manual_verify', 'reservations', 'property', 'Attest physical guest identity during controlled fallback', false, true),
    ('reservations.guests.manage', 'reservations', 'property', 'Manage reservation occupants and guardian links', false, true)
ON CONFLICT (code) DO UPDATE SET
    namespace = EXCLUDED.namespace,
    access_scope = EXCLUDED.access_scope,
    description = EXCLUDED.description,
    is_platform_permission = EXCLUDED.is_platform_permission,
    is_tenant_permission = EXCLUDED.is_tenant_permission,
    updated_at = now();

INSERT INTO permissions (id, tenant_id, code, description)
SELECT gen_random_uuid(), t.id, pc.code, pc.description
FROM tenants t
JOIN permission_catalog pc ON pc.code IN (
    'guests.identity.view',
    'guests.identity.manage',
    'guests.identity.verify',
    'guests.identity.manual_verify',
    'reservations.guests.manage'
)
WHERE t.deleted_at IS NULL
ON CONFLICT ON CONSTRAINT permissions_tenant_id_code_key
DO UPDATE SET
    description = EXCLUDED.description,
    updated_at = now();

INSERT INTO tenant_role_permissions (tenant_role_id, permission_id)
SELECT tr.id, p.id
FROM tenant_roles tr
JOIN permissions p ON p.tenant_id = tr.tenant_id
WHERE tr.code = 'tenant_admin'
  AND tr.is_system = true
  AND p.code IN (
      'guests.identity.view',
      'guests.identity.manage',
      'guests.identity.verify',
      'guests.identity.manual_verify',
      'reservations.guests.manage'
  )
ON CONFLICT DO NOTHING;

INSERT INTO module_access_matrix (
    module_id,
    screen_key,
    screen_label,
    http_method,
    api_pattern,
    permission_code,
    route_scope,
    guard_mode,
    access_scope,
    is_tanzania_v1,
    is_enabled_by_default,
    notes
) VALUES
    ('reservations', 'guests.update', 'Update Guest', 'PATCH', '/api/properties/:propertyId/guests/:guestId', 'guests.manage', 'property', 'staff_permission', 'property', true, true, 'Update identity-policy guest attributes'),
    ('reservations', 'guest_identity.list', 'Guest Identity', 'GET', '/api/properties/:propertyId/guests/:guestId/identity-documents', 'guests.identity.view', 'property', 'staff_permission', 'property', true, true, 'View masked identity verification records'),
    ('reservations', 'guest_identity.verify', 'Verify Guest Identity', 'POST', '/api/properties/:propertyId/guests/:guestId/identity-documents/verify', 'guests.identity.verify', 'property', 'staff_permission', 'property', true, true, 'Verify a NIDA identity through the configured provider'),
    ('reservations', 'guest_identity.manual_verify', 'Manually Verify Guest Identity', 'POST', '/api/properties/:propertyId/guests/:guestId/identity-documents/manual-verification', 'guests.identity.manual_verify', 'property', 'staff_permission', 'property', true, true, 'Attest inspection of a recognised physical identity document'),
    ('reservations', 'guest_identity.revoke', 'Revoke Guest Identity', 'POST', '/api/properties/:propertyId/guests/:guestId/identity-documents/:documentId/revoke', 'guests.identity.manage', 'property', 'staff_permission', 'property', true, true, 'Revoke a guest identity verification with an audited reason'),
    ('reservations', 'reservation_guests.add', 'Add Reservation Guest', 'POST', '/api/properties/:propertyId/reservations/:reservationId/guests', 'reservations.guests.manage', 'property', 'staff_permission', 'property', true, true, 'Attach an occupant and optional guardian attestation'),
    ('reservations', 'reservation_guests.list', 'Reservation Guests', 'GET', '/api/properties/:propertyId/reservations/:reservationId/guests', 'reservations.view', 'property', 'staff_permission', 'property', true, true, 'List every attached reservation occupant and guardian relationship'),
    ('reservations', 'reservation_guests.remove', 'Remove Reservation Guest', 'DELETE', '/api/properties/:propertyId/reservations/:reservationId/guests/:guestId', 'reservations.guests.manage', 'property', 'staff_permission', 'property', true, true, 'Remove a non-primary occupant before check-in'),
    ('reservations', 'reservation_identity.readiness', 'Reservation Identity Readiness', 'GET', '/api/properties/:propertyId/reservations/:reservationId/identity-readiness', 'guests.identity.view', 'property', 'staff_permission', 'property', true, true, 'Evaluate identity compliance for every reservation occupant')
ON CONFLICT (module_id, screen_key, http_method, api_pattern, permission_code)
DO UPDATE SET
    screen_label = EXCLUDED.screen_label,
    route_scope = EXCLUDED.route_scope,
    guard_mode = EXCLUDED.guard_mode,
    access_scope = EXCLUDED.access_scope,
    is_tanzania_v1 = EXCLUDED.is_tanzania_v1,
    is_enabled_by_default = EXCLUDED.is_enabled_by_default,
    notes = EXCLUDED.notes,
    updated_at = now();

GRANT SELECT, INSERT, UPDATE ON TABLE
    guest_documents,
    guest_identity_verification_attempts,
    reservation_guests
TO pms_app;
