-- V125 — a property walks a step machine; go-live is not a checkbox.
--
-- Tenant onboarding (V71) is the account. This is the hotel. Peak already had
-- scattered property readiness (buildings, rooms, rates) and a later pile of
-- identity, rail, and SMS facts that never joined that list. Activate would
-- flip status=active while a PIN-only property with no guest rail and no
-- Keycloak manager looked "ready". Collection was a separate lie (V123); this
-- one is launching the property itself.
--
-- Evidence is recomputed on every read. Persisted step rows are a snapshot of
-- that evaluation, not a manager ticking boxes. ENABLED on a payment account
-- is still the collection gate and is not written here.
--
-- WhatsApp is optional. Inbound WhatsApp does not exist. ClickPesa is not
-- required. Fiscal/NIDA stay out unless some other domain already blocks on
-- them — property activation does not. Frontline staff do not need email.

ALTER TABLE properties
    ADD CONSTRAINT chk_properties_distinct_from_tenant
        CHECK (id <> tenant_id);

COMMENT ON CONSTRAINT chk_properties_distinct_from_tenant ON properties IS
    'A property is not the tenant. The same UUID in both columns would collapse '
    'the hotel into the account and make every later property-scoped check a lie.';

CREATE TABLE property_onboarding_states (
    tenant_id uuid NOT NULL REFERENCES tenants(id) DEFERRABLE,
    property_id uuid NOT NULL,
    workflow_status varchar(20) NOT NULL DEFAULT 'running',
    current_step text,
    last_evaluated_at timestamptz,
    activated_at timestamptz,
    version bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, property_id),
    CONSTRAINT fk_property_onboarding_states_property
        FOREIGN KEY (tenant_id, property_id)
        REFERENCES properties(tenant_id, id) DEFERRABLE,
    CONSTRAINT chk_property_onboarding_workflow_status CHECK (
        workflow_status IN ('running', 'blocked', 'ready', 'activated')
    ),
    CONSTRAINT chk_property_onboarding_version CHECK (version > 0)
);

COMMENT ON TABLE property_onboarding_states IS
    'Where this hotel is in go-live. Owned by property. Status is reconciled from '
    'step evidence; it is not a launch checkbox.';

CREATE TABLE property_onboarding_steps (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    step_key text NOT NULL,
    sequence integer NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'pending',
    required boolean NOT NULL DEFAULT true,
    blocker_code text,
    blocker_detail text,
    evidence jsonb NOT NULL DEFAULT '{}'::jsonb,
    satisfied_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_property_onboarding_steps_state
        FOREIGN KEY (tenant_id, property_id)
        REFERENCES property_onboarding_states(tenant_id, property_id) DEFERRABLE,
    CONSTRAINT uq_property_onboarding_step
        UNIQUE (tenant_id, property_id, step_key),
    CONSTRAINT uq_property_onboarding_step_sequence
        UNIQUE (tenant_id, property_id, sequence),
    CONSTRAINT chk_property_onboarding_step_sequence CHECK (sequence > 0),
    CONSTRAINT chk_property_onboarding_step_status CHECK (
        status IN ('pending', 'satisfied', 'blocked', 'skipped')
    ),
    CONSTRAINT chk_property_onboarding_step_key CHECK (
        step_key IN (
            'property_distinct',
            'strong_manager',
            'inventory_ready',
            'frontline_path',
            'guest_rail_configured',
            'sms_routable',
            'go_live'
        )
    )
);

COMMENT ON TABLE property_onboarding_steps IS
    'Canonical go-live steps for one property. frontline_path and sms_routable '
    'are skipped when POS/front desk are not in scope. guest_rail_configured is '
    'CONFIGURED, not ENABLED.';

CREATE INDEX idx_property_onboarding_steps_property
    ON property_onboarding_steps (tenant_id, property_id, sequence);

CREATE TRIGGER trg_property_onboarding_states_updated_at
    BEFORE UPDATE ON property_onboarding_states
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_property_onboarding_steps_updated_at
    BEFORE UPDATE ON property_onboarding_steps
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE property_onboarding_states ENABLE ROW LEVEL SECURITY;
ALTER TABLE property_onboarding_states FORCE ROW LEVEL SECURITY;
ALTER TABLE property_onboarding_steps ENABLE ROW LEVEL SECURITY;
ALTER TABLE property_onboarding_steps FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON property_onboarding_states
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

CREATE POLICY tenant_isolation ON property_onboarding_steps
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

GRANT SELECT, INSERT, UPDATE ON property_onboarding_states TO pms_app;
GRANT SELECT, INSERT, UPDATE ON property_onboarding_steps TO pms_app;
GRANT SELECT ON property_onboarding_states TO pms_worker, pms_readonly_support;
GRANT SELECT ON property_onboarding_steps TO pms_worker, pms_readonly_support;
REVOKE DELETE ON property_onboarding_states FROM pms_app, pms_worker;
REVOKE DELETE ON property_onboarding_steps FROM pms_app, pms_worker;

INSERT INTO property_onboarding_states (
    tenant_id, property_id, workflow_status
)
SELECT tenant_id,
       id,
       CASE WHEN status = 'active' THEN 'activated' ELSE 'running' END
FROM properties
WHERE deleted_at IS NULL
ON CONFLICT DO NOTHING;

INSERT INTO property_onboarding_steps (
    tenant_id, property_id, step_key, sequence, required, status
)
SELECT s.tenant_id, s.property_id, k.step_key, k.sequence, true,
       CASE WHEN s.workflow_status = 'activated' AND k.step_key = 'go_live'
            THEN 'satisfied'
            ELSE 'pending'
       END
FROM property_onboarding_states s
CROSS JOIN (
    VALUES
        ('property_distinct', 1),
        ('strong_manager', 2),
        ('inventory_ready', 3),
        ('frontline_path', 4),
        ('guest_rail_configured', 5),
        ('sms_routable', 6),
        ('go_live', 7)
) AS k(step_key, sequence)
ON CONFLICT DO NOTHING;

INSERT INTO module_access_matrix (
    module_id, screen_key, screen_label, http_method, api_pattern,
    permission_code, route_scope, guard_mode, access_scope,
    is_tanzania_v1, is_enabled_by_default, notes
) VALUES
    (
        'property', 'property.onboarding', 'Property Go-Live',
        'GET', '/api/properties/:propertyId/onboarding',
        'property.view', 'property', 'staff_permission', 'property',
        true, true,
        'Read the property step machine and remaining go-live blockers. Evidence, not checkboxes. Does not enable collection.'
    )
ON CONFLICT (module_id, screen_key, http_method, api_pattern, permission_code)
DO UPDATE SET
    screen_label = EXCLUDED.screen_label,
    route_scope = EXCLUDED.route_scope,
    guard_mode = EXCLUDED.guard_mode,
    access_scope = EXCLUDED.access_scope,
    notes = EXCLUDED.notes,
    updated_at = now();

DO $migration$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'chk_properties_distinct_from_tenant'
          AND conrelid = 'properties'::regclass
    ) THEN
        RAISE EXCEPTION 'properties must refuse an id that equals tenant_id';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_class
        WHERE relname = 'property_onboarding_states' AND relkind = 'r'
    ) THEN
        RAISE EXCEPTION 'property_onboarding_states was not created';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_class
        WHERE relname = 'property_onboarding_steps' AND relkind = 'r'
    ) THEN
        RAISE EXCEPTION 'property_onboarding_steps was not created';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE tablename = 'property_onboarding_states'
          AND policyname = 'tenant_isolation'
    ) THEN
        RAISE EXCEPTION 'property_onboarding_states has no tenant RLS policy';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM module_access_matrix
        WHERE module_id = 'property'
          AND screen_key = 'property.onboarding'
          AND http_method = 'GET'
          AND api_pattern = '/api/properties/:propertyId/onboarding'
    ) THEN
        RAISE EXCEPTION 'property onboarding read route is missing from the access matrix';
    END IF;
END;
$migration$;
