-- ================================================================================
-- Phase 2 tenant, property, and communication API runtime privileges
-- ================================================================================

GRANT SELECT, INSERT, UPDATE ON TABLE
    tenant_modules,
    tenant_roles,
    properties,
    property_modules,
    buildings,
    floors,
    room_types,
    rooms,
    revenue_centers,
    departments,
    tax_rates,
    roles,
    permissions,
    tenant_contacts,
    tenant_contact_roles,
    contact_channels
TO pms_app;

GRANT SELECT, INSERT ON TABLE
    room_status_log
TO pms_app;

GRANT SELECT, INSERT, DELETE ON TABLE
    tenant_role_permissions,
    role_permissions,
    user_property_roles
TO pms_app;

GRANT SELECT ON TABLE
    report_subscriptions,
    report_subscription_recipients
TO pms_app;
