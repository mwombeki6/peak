-- V95 — letting a newly activated module actually become visible.
--
-- Buying Peak POS enables the module, and until now that was the end of it:
-- can_access_module also requires the user to hold a permission in that module,
-- and nobody did. The tenant would have paid, the flag would have flipped, and
-- the screens would have stayed exactly as absent as before.
--
-- The reconciler runs as pms_worker, which has no reach into permissions or
-- tenant_role_permissions at all -- those belong to usermanagement and were
-- only ever written by migrations and by the API role. This opens the narrowest
-- path that makes activation mean something.
--
-- Reads are needed too: the permission set for a module is derived from
-- module_access_matrix rather than hardcoded, so the same table the route guard
-- consults decides what a module grants. A hardcoded list would drift the first
-- time someone added a route.

GRANT SELECT ON module_access_matrix TO pms_worker;
GRANT SELECT ON permission_catalog TO pms_worker;
GRANT SELECT ON tenant_roles TO pms_worker;

GRANT SELECT, INSERT ON permissions TO pms_worker;
GRANT SELECT, INSERT ON tenant_role_permissions TO pms_worker;

-- No UPDATE and no DELETE. The bootstrap only ever adds: it must never be able
-- to rewrite a permission's meaning or strip a role of something an operator
-- granted deliberately. Revocation happens by disabling the module, which
-- can_access_module already honours, not by deleting grants.
REVOKE UPDATE, DELETE ON permissions FROM pms_worker;
REVOKE UPDATE, DELETE ON tenant_role_permissions FROM pms_worker;

CREATE POLICY worker_module_permission_bootstrap ON permissions
    FOR ALL
    TO pms_worker
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

CREATE POLICY worker_module_role_permission_bootstrap ON tenant_role_permissions
    FOR ALL
    TO pms_worker
    USING (
        EXISTS (
            SELECT 1 FROM tenant_roles role
            WHERE role.id = tenant_role_permissions.tenant_role_id
              AND role.tenant_id = current_tenant_id()
        )
    )
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM tenant_roles role
            WHERE role.id = tenant_role_permissions.tenant_role_id
              AND role.tenant_id = current_tenant_id()
        )
    );

-- The worker reads tenant_roles through the policy above, so it needs to be able
-- to see the tenant's own roles on a tenant-bound session.
CREATE POLICY worker_reads_tenant_roles ON tenant_roles
    FOR SELECT
    TO pms_worker
    USING (tenant_id = current_tenant_id());

DO $migration$
DECLARE
    leaked text;
BEGIN
    SELECT string_agg(table_name || '.' || privilege_type, ', ')
    INTO leaked
    FROM information_schema.role_table_grants
    WHERE grantee = 'pms_worker'
      AND table_name IN ('permissions', 'tenant_role_permissions')
      AND privilege_type IN ('UPDATE', 'DELETE');

    IF leaked IS NOT NULL THEN
        RAISE EXCEPTION
            'The module permission bootstrap must be additive only, but pms_worker holds: %',
            leaked;
    END IF;
END;
$migration$;
