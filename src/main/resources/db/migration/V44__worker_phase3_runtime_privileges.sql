-- ================================================================================
-- Least-privilege worker reads for Phase 3 background processing
-- ================================================================================

GRANT SELECT ON TABLE
    tenants,
    properties,
    tenant_contacts,
    contact_channels,
    communication_consents
TO pms_worker;

REVOKE INSERT, UPDATE, DELETE ON TABLE
    tenants,
    properties,
    tenant_contacts,
    contact_channels,
    communication_consents
FROM pms_worker;

REVOKE EXECUTE ON FUNCTION contact_channel_has_active_consent(uuid, uuid, uuid, text)
FROM PUBLIC;

GRANT EXECUTE ON FUNCTION contact_channel_has_active_consent(uuid, uuid, uuid, text)
TO pms_app, pms_worker;

-- Invoice triggers maintain the optional corporate receivable projection. Run
-- that cross-module projection under its migration-owned function instead of
-- granting the API role direct access to corporate account tables.
ALTER FUNCTION sync_corporate_account_balance_from_ar()
    SECURITY DEFINER
    SET search_path = public, pg_temp;

REVOKE EXECUTE ON FUNCTION sync_corporate_account_balance_from_ar()
FROM PUBLIC;
