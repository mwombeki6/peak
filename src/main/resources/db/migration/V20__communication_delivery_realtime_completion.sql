-- ================================================================================
-- Peak phase 2 communication delivery completion and realtime route hardening
-- ================================================================================

CREATE TABLE communication_delivery_requests (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    property_id uuid,
    original_outbox_event_id uuid NOT NULL,
    current_outbox_event_id uuid NOT NULL,
    channel_type character varying(20) NOT NULL,
    recipient text NOT NULL,
    recipient_fingerprint text NOT NULL,
    subject text,
    content_fingerprint text NOT NULL,
    status character varying(20) DEFAULT 'queued' NOT NULL,
    attempt_count integer DEFAULT 0 NOT NULL,
    max_attempts integer DEFAULT 10 NOT NULL,
    requested_at timestamp with time zone DEFAULT now() NOT NULL,
    delivered_at timestamp with time zone,
    failed_at timestamp with time zone,
    last_error text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone,
    CONSTRAINT communication_delivery_requests_pkey PRIMARY KEY (id),
    CONSTRAINT communication_delivery_requests_original_outbox_key UNIQUE (original_outbox_event_id),
    CONSTRAINT communication_delivery_requests_current_outbox_key UNIQUE (current_outbox_event_id),
    CONSTRAINT fk_communication_delivery_requests_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE,
    CONSTRAINT fk_communication_delivery_requests_property
        FOREIGN KEY (property_id) REFERENCES properties(id) DEFERRABLE,
    CONSTRAINT chk_communication_delivery_requests_channel
        CHECK ((channel_type)::text = ANY ((ARRAY['email', 'sms', 'whatsapp', 'voice_phone'])::text[])),
    CONSTRAINT chk_communication_delivery_requests_status
        CHECK ((status)::text = ANY ((ARRAY['queued', 'sending', 'delivered', 'failed', 'dead_letter', 'cancelled'])::text[])),
    CONSTRAINT chk_communication_delivery_requests_attempts
        CHECK (attempt_count >= 0 AND max_attempts > 0)
);

CREATE TABLE communication_delivery_attempts (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    delivery_request_id uuid NOT NULL,
    outbox_event_id uuid NOT NULL,
    attempt_number integer NOT NULL,
    provider character varying(80) NOT NULL,
    status character varying(20) NOT NULL,
    provider_message_id text,
    error_message text,
    started_at timestamp with time zone DEFAULT now() NOT NULL,
    completed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT communication_delivery_attempts_pkey PRIMARY KEY (id),
    CONSTRAINT communication_delivery_attempts_unique_attempt
        UNIQUE (delivery_request_id, outbox_event_id, attempt_number),
    CONSTRAINT fk_communication_delivery_attempts_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE,
    CONSTRAINT fk_communication_delivery_attempts_delivery_request
        FOREIGN KEY (delivery_request_id) REFERENCES communication_delivery_requests(id) DEFERRABLE,
    CONSTRAINT chk_communication_delivery_attempts_status
        CHECK ((status)::text = ANY ((ARRAY['sending', 'delivered', 'failed', 'dead_letter'])::text[])),
    CONSTRAINT chk_communication_delivery_attempts_attempt
        CHECK (attempt_number > 0)
);

CREATE INDEX idx_communication_delivery_requests_tenant_status
    ON communication_delivery_requests (tenant_id, status, requested_at DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_communication_delivery_requests_property
    ON communication_delivery_requests (tenant_id, property_id, requested_at DESC)
    WHERE property_id IS NOT NULL AND deleted_at IS NULL;

CREATE INDEX idx_communication_delivery_attempts_request
    ON communication_delivery_attempts (tenant_id, delivery_request_id, attempt_number DESC);

ALTER TABLE communication_delivery_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE ONLY communication_delivery_requests FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON communication_delivery_requests
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

ALTER TABLE communication_delivery_attempts ENABLE ROW LEVEL SECURITY;
ALTER TABLE ONLY communication_delivery_attempts FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON communication_delivery_attempts
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

CREATE TRIGGER trg_communication_delivery_requests_updated_at
    BEFORE UPDATE ON communication_delivery_requests
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

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
)
VALUES
    ('communications', 'communications.delivery_requests.list', 'Communication Delivery Requests', 'GET', '/api/communication/delivery-requests', 'communications.view', 'tenant', 'staff_permission', 'tenant', true, true, 'List tenant communication delivery requests'),
    ('communications', 'communications.delivery_requests.get', 'Communication Delivery Request', 'GET', '/api/communication/delivery-requests/:deliveryRequestId', 'communications.view', 'tenant', 'staff_permission', 'tenant', true, true, 'Get communication delivery request status'),
    ('communications', 'communications.delivery_attempts.list', 'Communication Delivery Attempts', 'GET', '/api/communication/delivery-requests/:deliveryRequestId/attempts', 'communications.view', 'tenant', 'staff_permission', 'tenant', true, true, 'List provider attempts for a delivery request'),
    ('communications', 'communications.delivery_requests.retry', 'Retry Communication Delivery', 'POST', '/api/communication/delivery-requests/:deliveryRequestId/retry', 'communications.send', 'tenant', 'staff_permission', 'tenant', true, true, 'Retry a failed or dead-lettered communication delivery request')
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

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pms_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE communication_delivery_requests TO pms_app;
        GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE communication_delivery_attempts TO pms_app;
    END IF;

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pms_worker') THEN
        GRANT SELECT, INSERT, UPDATE ON TABLE communication_delivery_requests TO pms_worker;
        GRANT SELECT, INSERT, UPDATE ON TABLE communication_delivery_attempts TO pms_worker;
    END IF;
END $$;
