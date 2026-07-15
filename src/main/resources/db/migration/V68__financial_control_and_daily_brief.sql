-- Peak Daily Control: turn night-audit exceptions into owned, evidenced actions.

CREATE UNIQUE INDEX idx_night_audit_issues_tenant_id_id
    ON night_audit_issues (tenant_id, id);

CREATE TABLE financial_control_cases (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    business_date date NOT NULL,
    source_run_id uuid NOT NULL,
    source_issue_id uuid NOT NULL,
    issue_code text NOT NULL,
    category text NOT NULL,
    severity text NOT NULL,
    title text NOT NULL,
    description text NOT NULL,
    status text NOT NULL DEFAULT 'open',
    currency character(3) NOT NULL,
    quantity integer NOT NULL DEFAULT 1,
    amount_at_risk numeric(15,2),
    assigned_to uuid,
    assigned_by uuid,
    assigned_at timestamptz,
    due_at timestamptz,
    resolution_type text,
    resolution_note text,
    value_recovered numeric(15,2) NOT NULL DEFAULT 0,
    value_protected numeric(15,2) NOT NULL DEFAULT 0,
    resolved_by uuid,
    resolved_at timestamptz,
    first_detected_at timestamptz NOT NULL DEFAULT now(),
    last_detected_at timestamptz NOT NULL DEFAULT now(),
    occurrence_count integer NOT NULL DEFAULT 1,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_financial_control_case_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_financial_control_case_daily_issue
        UNIQUE (tenant_id, property_id, business_date, issue_code),
    CONSTRAINT chk_financial_control_case_category CHECK (
        category IN ('revenue', 'payment', 'fiscal', 'pos', 'operations')
    ),
    CONSTRAINT chk_financial_control_case_severity CHECK (
        severity IN ('warning', 'blocking')
    ),
    CONSTRAINT chk_financial_control_case_status CHECK (
        status IN ('open', 'assigned', 'resolved', 'accepted')
    ),
    CONSTRAINT chk_financial_control_case_currency CHECK (
        currency ~ '^[A-Z]{3}$'
    ),
    CONSTRAINT chk_financial_control_case_values CHECK (
        quantity > 0
        AND (amount_at_risk IS NULL OR amount_at_risk >= 0)
        AND value_recovered >= 0
        AND value_protected >= 0
        AND occurrence_count > 0
        AND version > 0
    ),
    CONSTRAINT chk_financial_control_case_assignment CHECK (
        (status <> 'assigned')
        OR (assigned_to IS NOT NULL AND assigned_by IS NOT NULL AND assigned_at IS NOT NULL)
    ),
    CONSTRAINT chk_financial_control_case_resolution CHECK (
        (status IN ('open', 'assigned')
            AND resolved_at IS NULL
            AND resolved_by IS NULL
            AND resolution_type IS NULL
            AND resolution_note IS NULL)
        OR
        (status IN ('resolved', 'accepted')
            AND resolved_at IS NOT NULL
            AND resolution_type IS NOT NULL
            AND resolution_note IS NOT NULL)
    ),
    CONSTRAINT fk_financial_control_case_property
        FOREIGN KEY (tenant_id, property_id)
        REFERENCES properties(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_financial_control_case_run
        FOREIGN KEY (tenant_id, source_run_id)
        REFERENCES night_audit_runs(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_financial_control_case_issue
        FOREIGN KEY (tenant_id, source_issue_id)
        REFERENCES night_audit_issues(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_financial_control_case_assigned_to
        FOREIGN KEY (tenant_id, assigned_to)
        REFERENCES users(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_financial_control_case_assigned_by
        FOREIGN KEY (tenant_id, assigned_by)
        REFERENCES users(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_financial_control_case_resolved_by
        FOREIGN KEY (tenant_id, resolved_by)
        REFERENCES users(tenant_id, id) DEFERRABLE
);

CREATE TABLE financial_control_evidence (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    case_id uuid NOT NULL,
    source_run_id uuid NOT NULL,
    source_issue_id uuid NOT NULL,
    evidence_type text NOT NULL,
    resource_type text,
    resource_id uuid,
    amount numeric(15,2),
    payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    recorded_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_financial_control_evidence_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT chk_financial_control_evidence_amount CHECK (
        amount IS NULL OR amount >= 0
    ),
    CONSTRAINT chk_financial_control_evidence_resource CHECK (
        (resource_type IS NULL) = (resource_id IS NULL)
    ),
    CONSTRAINT fk_financial_control_evidence_property
        FOREIGN KEY (tenant_id, property_id)
        REFERENCES properties(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_financial_control_evidence_case
        FOREIGN KEY (tenant_id, case_id)
        REFERENCES financial_control_cases(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_financial_control_evidence_run
        FOREIGN KEY (tenant_id, source_run_id)
        REFERENCES night_audit_runs(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_financial_control_evidence_issue
        FOREIGN KEY (tenant_id, source_issue_id)
        REFERENCES night_audit_issues(tenant_id, id) DEFERRABLE
);

CREATE TABLE financial_control_case_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    case_id uuid NOT NULL,
    event_type text NOT NULL,
    actor_id uuid,
    payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_financial_control_case_event_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_financial_control_case_event_property
        FOREIGN KEY (tenant_id, property_id)
        REFERENCES properties(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_financial_control_case_event_case
        FOREIGN KEY (tenant_id, case_id)
        REFERENCES financial_control_cases(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_financial_control_case_event_actor
        FOREIGN KEY (tenant_id, actor_id)
        REFERENCES users(tenant_id, id) DEFERRABLE
);

CREATE INDEX idx_financial_control_cases_open
    ON financial_control_cases (
        tenant_id, property_id, status, due_at, last_detected_at DESC
    )
    WHERE status IN ('open', 'assigned');
CREATE INDEX idx_financial_control_cases_business_date
    ON financial_control_cases (
        tenant_id, property_id, business_date DESC, issue_code
    );
CREATE INDEX idx_financial_control_evidence_case
    ON financial_control_evidence (tenant_id, case_id, recorded_at, id);
CREATE INDEX idx_financial_control_case_events_case
    ON financial_control_case_events (tenant_id, case_id, occurred_at, id);

CREATE OR REPLACE FUNCTION guard_financial_control_append_only()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION '% records are append-only', TG_TABLE_NAME;
END;
$$;

CREATE TRIGGER trg_financial_control_evidence_append_only
    BEFORE UPDATE OR DELETE ON financial_control_evidence
    FOR EACH ROW EXECUTE FUNCTION guard_financial_control_append_only();
CREATE TRIGGER trg_financial_control_case_events_append_only
    BEFORE UPDATE OR DELETE ON financial_control_case_events
    FOR EACH ROW EXECUTE FUNCTION guard_financial_control_append_only();
CREATE TRIGGER trg_financial_control_cases_updated_at
    BEFORE UPDATE ON financial_control_cases
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE financial_control_cases ENABLE ROW LEVEL SECURITY;
ALTER TABLE financial_control_cases FORCE ROW LEVEL SECURITY;
ALTER TABLE financial_control_evidence ENABLE ROW LEVEL SECURITY;
ALTER TABLE financial_control_evidence FORCE ROW LEVEL SECURITY;
ALTER TABLE financial_control_case_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE financial_control_case_events FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON financial_control_cases
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());
CREATE POLICY tenant_isolation ON financial_control_evidence
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());
CREATE POLICY tenant_isolation ON financial_control_case_events
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

GRANT SELECT, INSERT, UPDATE ON financial_control_cases TO pms_app;
GRANT SELECT, INSERT ON financial_control_evidence, financial_control_case_events TO pms_app;
GRANT SELECT ON financial_control_cases, financial_control_evidence,
    financial_control_case_events TO pms_worker, pms_readonly_support;
REVOKE DELETE ON financial_control_cases, financial_control_evidence,
    financial_control_case_events FROM pms_app, pms_worker;
REVOKE UPDATE ON financial_control_evidence, financial_control_case_events
    FROM pms_app, pms_worker;

INSERT INTO permission_catalog (
    code, namespace, access_scope, description,
    is_platform_permission, is_tenant_permission
) VALUES
    ('financial_control.view', 'finance', 'property',
     'View certified daily financial truth and revenue-assurance cases', false, true),
    ('financial_control.manage', 'finance', 'property',
     'Assign and resolve accountable revenue-assurance cases', false, true)
ON CONFLICT (code) DO UPDATE SET
    namespace = EXCLUDED.namespace,
    access_scope = EXCLUDED.access_scope,
    description = EXCLUDED.description,
    is_platform_permission = EXCLUDED.is_platform_permission,
    is_tenant_permission = EXCLUDED.is_tenant_permission,
    updated_at = now();

INSERT INTO permissions (id, tenant_id, code, description)
SELECT gen_random_uuid(), tenant.id, catalog.code, catalog.description
FROM tenants tenant
JOIN permission_catalog catalog
  ON catalog.code IN ('financial_control.view', 'financial_control.manage')
WHERE tenant.deleted_at IS NULL
ON CONFLICT (tenant_id, code) DO UPDATE SET
    description = EXCLUDED.description,
    updated_at = now();

INSERT INTO tenant_role_permissions (tenant_role_id, permission_id)
SELECT role.id, permission.id
FROM tenant_roles role
JOIN permissions permission ON permission.tenant_id = role.tenant_id
WHERE role.is_active
  AND permission.code IN ('financial_control.view', 'financial_control.manage')
  AND (
      role.code = 'tenant_admin'
      OR EXISTS (
          SELECT 1
          FROM tenant_role_permissions assignment
          JOIN permissions admin_permission
            ON admin_permission.id = assignment.permission_id
           AND admin_permission.tenant_id = role.tenant_id
          WHERE assignment.tenant_role_id = role.id
            AND admin_permission.code = 'tenant.admin.all'
      )
  )
ON CONFLICT ON CONSTRAINT tenant_role_permissions_pkey DO NOTHING;

WITH inherited_permission(new_code, source_code) AS (
    VALUES
        ('financial_control.view', 'night_audit.view'),
        ('financial_control.manage', 'night_audit.override')
)
INSERT INTO role_permissions (role_id, permission_id)
SELECT DISTINCT role.id, new_permission.id
FROM roles role
JOIN role_permissions existing_assignment
  ON existing_assignment.role_id = role.id
JOIN permissions existing_permission
  ON existing_permission.id = existing_assignment.permission_id
 AND existing_permission.tenant_id = role.tenant_id
JOIN inherited_permission inherited
  ON inherited.source_code = existing_permission.code
JOIN permissions new_permission
  ON new_permission.tenant_id = role.tenant_id
 AND new_permission.code = inherited.new_code
WHERE role.is_active
ON CONFLICT ON CONSTRAINT role_permissions_pkey DO NOTHING;

INSERT INTO module_access_matrix (
    module_id, screen_key, screen_label, http_method, api_pattern,
    permission_code, route_scope, guard_mode, access_scope,
    is_tanzania_v1, is_enabled_by_default, notes
) VALUES
    ('night_audit', 'financial_control.brief', 'Daily Control Brief', 'GET',
     '/api/properties/:propertyId/financial-control/briefs/:businessDate',
     'financial_control.view', 'property', 'staff_permission', 'property', true, true,
     'View certified money, close evidence and accountable actions'),
    ('night_audit', 'financial_control.cases.list', 'Financial Control Cases', 'GET',
     '/api/properties/:propertyId/financial-control/cases',
     'financial_control.view', 'property', 'staff_permission', 'property', true, true,
     'List bounded property revenue-assurance cases'),
    ('night_audit', 'financial_control.cases.view', 'Financial Control Case', 'GET',
     '/api/properties/:propertyId/financial-control/cases/:caseId',
     'financial_control.view', 'property', 'staff_permission', 'property', true, true,
     'View a case with immutable evidence and event history'),
    ('night_audit', 'financial_control.cases.assign', 'Assign Financial Control Case', 'POST',
     '/api/properties/:propertyId/financial-control/cases/:caseId/assign',
     'financial_control.manage', 'property', 'staff_permission', 'property', true, true,
     'Assign an open case to active property staff'),
    ('night_audit', 'financial_control.cases.resolve', 'Resolve Financial Control Case', 'POST',
     '/api/properties/:propertyId/financial-control/cases/:caseId/resolve',
     'financial_control.manage', 'property', 'staff_permission', 'property', true, true,
     'Record an evidenced resolution and verified value outcome')
ON CONFLICT (
    module_id, screen_key, http_method, api_pattern, permission_code
) DO UPDATE SET
    screen_label = EXCLUDED.screen_label,
    route_scope = EXCLUDED.route_scope,
    guard_mode = EXCLUDED.guard_mode,
    access_scope = EXCLUDED.access_scope,
    is_tanzania_v1 = EXCLUDED.is_tanzania_v1,
    is_enabled_by_default = EXCLUDED.is_enabled_by_default,
    notes = EXCLUDED.notes,
    updated_at = now();

COMMENT ON TABLE financial_control_cases IS
    'Owned revenue-assurance actions derived from deterministic close controls.';
COMMENT ON TABLE financial_control_evidence IS
    'Immutable economic and operational evidence supporting a financial-control case.';
COMMENT ON TABLE financial_control_case_events IS
    'Immutable assignment and resolution history for a financial-control case.';
