-- Phase 5: reporting access matrix, runtime grants, operational views and alerts.

ALTER TABLE outbox_events
    DROP CONSTRAINT IF EXISTS chk_outbox_events_destination,
    ADD CONSTRAINT chk_outbox_events_destination CHECK (
        destination IN (
            'fiscal', 'payment', 'notification', 'analytics', 'audit',
            'edge_sync', 'webhook', 'email', 'sms', 'whatsapp', 'pos',
            'housekeeping', 'platform', 'reports'
        )
    );

INSERT INTO permission_catalog (
    code, namespace, access_scope, description,
    is_platform_permission, is_tenant_permission
) VALUES
    ('reports.catalog.view', 'reports', 'tenant', 'View available report catalog', false, true),
    ('reports.subscriptions.view', 'reports', 'both', 'View report subscriptions and masked recipients', false, true),
    ('reports.subscriptions.manage', 'reports', 'both', 'Manage report subscriptions and recipients', false, true),
    ('reports.generate', 'reports', 'property', 'Generate a report from an immutable close snapshot', false, true),
    ('reports.artifact.download', 'reports', 'tenant', 'Create an expiring authenticated artifact link', false, true),
    ('reports.deliveries.view', 'reports', 'tenant', 'View report delivery history', false, true),
    ('reports.deliveries.retry', 'reports', 'tenant', 'Retry failed report delivery', false, true),
    ('reports.retention.manage', 'reports', 'both', 'Manage report artifact retention policy', false, true),
    ('night_audit.close_snapshot.view', 'finance', 'property', 'View immutable night-audit close snapshot', false, true)
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
JOIN permission_catalog catalog ON catalog.code IN (
    'reports.catalog.view',
    'reports.subscriptions.view',
    'reports.subscriptions.manage',
    'reports.generate',
    'reports.artifact.download',
    'reports.deliveries.view',
    'reports.deliveries.retry',
    'reports.retention.manage',
    'night_audit.close_snapshot.view'
)
WHERE tenant.deleted_at IS NULL
ON CONFLICT ON CONSTRAINT permissions_tenant_id_code_key
DO UPDATE SET
    description = EXCLUDED.description,
    updated_at = now();

INSERT INTO tenant_role_permissions (tenant_role_id, permission_id)
SELECT role.id, permission.id
FROM tenant_roles role
JOIN permissions permission ON permission.tenant_id = role.tenant_id
WHERE role.code = 'tenant_admin'
  AND role.is_system
  AND permission.code IN (
      'reports.catalog.view',
      'reports.subscriptions.view',
      'reports.subscriptions.manage',
      'reports.generate',
      'reports.artifact.download',
      'reports.deliveries.view',
      'reports.deliveries.retry',
      'reports.retention.manage',
      'night_audit.close_snapshot.view'
  )
ON CONFLICT DO NOTHING;

INSERT INTO module_access_matrix (
    module_id, screen_key, screen_label, http_method, api_pattern,
    permission_code, route_scope, guard_mode, access_scope,
    is_tanzania_v1, is_enabled_by_default, notes
) VALUES
    ('reports', 'reporting.tenant_settings.view', 'Tenant Reporting Settings', 'GET', '/api/tenants/:tenantId/reporting/settings', 'reports.catalog.view', 'tenant', 'staff_permission', 'tenant', true, true, 'View resolved tenant retention settings'),
    ('reports', 'reporting.tenant_settings.update', 'Update Tenant Reporting Settings', 'PUT', '/api/tenants/:tenantId/reporting/settings', 'reports.retention.manage', 'tenant', 'staff_permission', 'tenant', true, true, 'Set tenant artifact retention'),
    ('reports', 'reporting.property_settings.view', 'Property Reporting Settings', 'GET', '/api/properties/:propertyId/reporting/settings', 'reports.catalog.view', 'property', 'staff_permission', 'property', true, true, 'View resolved property retention settings'),
    ('reports', 'reporting.property_settings.update', 'Update Property Reporting Settings', 'PUT', '/api/properties/:propertyId/reporting/settings', 'reports.retention.manage', 'property', 'staff_permission', 'property', true, true, 'Set property artifact retention'),
    ('reports', 'reporting.catalog', 'Report Catalog', 'GET', '/api/tenants/:tenantId/reports/catalog', 'reports.catalog.view', 'tenant', 'staff_permission', 'tenant', true, true, 'List catalog including unavailable generators'),
    ('reports', 'reporting.tenant_subscriptions', 'Tenant Report Subscriptions', 'ANY', '/api/tenants/:tenantId/report-subscriptions*', 'reports.subscriptions.manage', 'tenant', 'staff_permission', 'tenant', true, true, 'Manage tenant subscriptions'),
    ('reports', 'reporting.property_subscriptions', 'Property Report Subscriptions', 'ANY', '/api/properties/:propertyId/report-subscriptions*', 'reports.subscriptions.manage', 'property', 'staff_permission', 'property', true, true, 'Manage property subscriptions'),
    ('reports', 'reporting.generate', 'Generate Report', 'POST', '/api/properties/:propertyId/reports/:reportCode/runs', 'reports.generate', 'property', 'staff_permission', 'property', true, true, 'Generate from a completed close snapshot'),
    ('reports', 'reporting.runs.list', 'Report Runs', 'GET', '/api/tenants/:tenantId/report-runs', 'reports.deliveries.view', 'tenant', 'staff_permission', 'tenant', true, true, 'List report runs'),
    ('reports', 'reporting.runs.view', 'Report Run', 'GET', '/api/tenants/:tenantId/report-runs/:runId', 'reports.deliveries.view', 'tenant', 'staff_permission', 'tenant', true, true, 'View report run'),
    ('reports', 'reporting.runs.download', 'Report Download Link', 'POST', '/api/tenants/:tenantId/report-runs/:runId/download-link', 'reports.artifact.download', 'tenant', 'staff_permission', 'tenant', true, true, 'Create fifteen-minute link'),
    ('reports', 'reporting.deliveries.list', 'Report Deliveries', 'GET', '/api/tenants/:tenantId/report-runs/:runId/deliveries', 'reports.deliveries.view', 'tenant', 'staff_permission', 'tenant', true, true, 'View deliveries and attempts'),
    ('reports', 'reporting.deliveries.retry', 'Retry Report Delivery', 'POST', '/api/tenants/:tenantId/report-deliveries/:deliveryId/retry', 'reports.deliveries.retry', 'tenant', 'staff_permission', 'tenant', true, true, 'Retry failed or dead-letter delivery'),
    ('night_audit', 'night_audit.close_snapshot', 'Night Audit Close Snapshot', 'GET', '/api/properties/:propertyId/night-audit/:runId/close-snapshot', 'night_audit.close_snapshot.view', 'property', 'staff_permission', 'property', true, true, 'View immutable close evidence')
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

CREATE OR REPLACE VIEW reporting_operational_summary
WITH (security_invoker = true)
AS
SELECT run.tenant_id,
       run.property_id,
       run.report_code,
       run.status,
       count(*) AS run_count,
       max(run.created_at) AS latest_created_at,
       max(run.generated_at) AS latest_generated_at,
       count(*) FILTER (
           WHERE run.status = 'queued'
             AND run.created_at < now() - interval '15 minutes'
       ) AS lagging_count,
       count(*) FILTER (WHERE run.status = 'failed') AS failed_count
FROM report_runs run
GROUP BY run.tenant_id, run.property_id, run.report_code, run.status;

CREATE OR REPLACE VIEW report_delivery_operational_summary
WITH (security_invoker = true)
AS
SELECT delivery.tenant_id,
       delivery.property_id,
       delivery.report_code,
       delivery.status,
       count(*) AS delivery_count,
       sum(delivery.attempt_count) AS retry_count,
       max(delivery.updated_at) AS latest_updated_at
FROM report_deliveries delivery
GROUP BY
    delivery.tenant_id,
    delivery.property_id,
    delivery.report_code,
    delivery.status;

CREATE OR REPLACE VIEW report_artifact_retention_summary
WITH (security_invoker = true)
AS
SELECT artifact.tenant_id,
       artifact.property_id,
       count(*) FILTER (
           WHERE artifact.expires_at <= now()
             AND artifact.object_deleted_at IS NULL
       ) AS cleanup_due_count,
       count(*) FILTER (
           WHERE artifact.object_deleted_at IS NOT NULL
       ) AS expired_object_count,
       sum(artifact.content_length) FILTER (
           WHERE artifact.object_deleted_at IS NULL
       ) AS retained_bytes
FROM report_artifacts artifact
GROUP BY artifact.tenant_id, artifact.property_id;

CREATE OR REPLACE FUNCTION claim_expired_report_artifacts(p_limit integer)
RETURNS TABLE (
    artifact_id uuid,
    tenant_id uuid,
    property_id uuid,
    object_key text
)
LANGUAGE sql
SECURITY DEFINER
SET search_path = public
AS $$
    SELECT artifact.id,
           artifact.tenant_id,
           artifact.property_id,
           artifact.object_key
    FROM report_artifacts artifact
    WHERE artifact.expires_at <= now()
      AND artifact.object_deleted_at IS NULL
    ORDER BY artifact.expires_at, artifact.id
    LIMIT LEAST(GREATEST(p_limit, 1), 500);
$$;
REVOKE ALL ON FUNCTION claim_expired_report_artifacts(integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION claim_expired_report_artifacts(integer)
TO pms_worker;

CREATE OR REPLACE FUNCTION enqueue_report_delivery_outbox_event(
    p_event_id uuid,
    p_tenant_id uuid,
    p_property_id uuid,
    p_delivery_id uuid,
    p_headers jsonb,
    p_correlation_id text
) RETURNS uuid
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_event_id uuid := COALESCE(p_event_id, gen_random_uuid());
BEGIN
    IF p_tenant_id IS NULL THEN
        RAISE EXCEPTION 'Report delivery outbox tenant is required';
    END IF;

    IF p_delivery_id IS NULL THEN
        RAISE EXCEPTION 'Report delivery id is required';
    END IF;

    IF current_tenant_id() IS DISTINCT FROM p_tenant_id THEN
        RAISE EXCEPTION 'Report delivery outbox tenant context does not match event tenant';
    END IF;

    INSERT INTO outbox_events (
        id,
        tenant_id,
        property_id,
        aggregate_type,
        aggregate_id,
        event_type,
        destination,
        payload,
        headers,
        correlation_id,
        priority,
        max_attempts
    )
    VALUES (
        v_event_id,
        p_tenant_id,
        p_property_id,
        'report_deliveries',
        p_delivery_id,
        'report.delivery.requested',
        'reports',
        jsonb_build_object('reportDeliveryId', p_delivery_id),
        COALESCE(p_headers, '{}'::jsonb),
        p_correlation_id,
        3,
        10
    );

    RETURN v_event_id;
END;
$$;
REVOKE ALL ON FUNCTION enqueue_report_delivery_outbox_event(
    uuid, uuid, uuid, uuid, jsonb, text
) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION enqueue_report_delivery_outbox_event(
    uuid, uuid, uuid, uuid, jsonb, text
) TO pms_worker;

GRANT SELECT, INSERT, UPDATE ON TABLE
    report_subscriptions,
    report_subscription_recipients,
    report_runs,
    report_deliveries,
    reporting_retention_policies
TO pms_app;
GRANT SELECT, INSERT ON TABLE
    report_artifacts,
    report_delivery_attempts
TO pms_app;
GRANT SELECT ON TABLE report_catalog TO pms_app;
GRANT SELECT, INSERT, UPDATE ON TABLE
    accounting_accounts,
    journal_entries,
    journal_entry_lines
TO pms_app;

GRANT SELECT, INSERT, UPDATE ON TABLE
    report_runs,
    report_deliveries,
    report_artifacts,
    reporting_retention_policies
TO pms_worker;
GRANT SELECT, INSERT ON TABLE report_delivery_attempts TO pms_worker;
REVOKE SELECT, INSERT, UPDATE, DELETE ON TABLE outbox_events FROM pms_worker;
GRANT SELECT ON TABLE
    report_catalog,
    report_subscriptions,
    report_subscription_recipients,
    night_audit_close_snapshots
TO pms_worker;

GRANT SELECT ON
    reporting_operational_summary,
    report_delivery_operational_summary,
    report_artifact_retention_summary
TO pms_app, pms_worker, pms_readonly_support;

REVOKE DELETE ON TABLE
    report_runs,
    report_artifacts,
    report_deliveries,
    report_delivery_attempts,
    night_audit_close_snapshots
FROM pms_app, pms_worker;

INSERT INTO schema_version_history (
    version_key, description, applied_by, metadata
) VALUES (
    'phase5_control_close_reporting',
    'Authoritative close snapshots, deterministic reports, private artifacts and consent-aware delivery',
    'V53',
    '{"schema_version":53,"system_retention_days":400,"core_reports":["daily_management_summary","night_audit_close"]}'::jsonb
)
ON CONFLICT (version_key) DO UPDATE SET
    description = EXCLUDED.description,
    applied_at = now(),
    applied_by = EXCLUDED.applied_by,
    metadata = EXCLUDED.metadata;
