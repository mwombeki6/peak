-- Reserved administrator wildcards belong only to their immutable system roles.
-- Dynamic tenant roles may contain tenant-scoped permissions only; dynamic
-- property roles may contain property-scoped permissions only.

DELETE FROM platform_role_permissions prp
USING platform_roles pr, platform_permissions pp
WHERE pr.id = prp.platform_role_id
  AND pp.id = prp.platform_permission_id
  AND pr.is_system = false
  AND pp.code = 'platform.admin.all';

DELETE FROM tenant_role_permissions trp
USING tenant_roles tr, permissions p
WHERE tr.id = trp.tenant_role_id
  AND p.id = trp.permission_id
  AND p.tenant_id = tr.tenant_id
  AND tr.is_system = false
  AND (
      p.code = 'tenant.admin.all'
      OR NOT EXISTS (
          SELECT 1
          FROM permission_catalog pc
          WHERE pc.code = p.code
            AND pc.is_tenant_permission = true
            AND pc.access_scope IN ('tenant', 'both')
      )
  );

DELETE FROM role_permissions rp
USING roles r, permissions p
WHERE r.id = rp.role_id
  AND p.id = rp.permission_id
  AND p.tenant_id = r.tenant_id
  AND r.is_system = false
  AND (
      p.code = 'admin.all'
      OR NOT EXISTS (
          SELECT 1
          FROM permission_catalog pc
          WHERE pc.code = p.code
            AND pc.is_tenant_permission = true
            AND pc.access_scope IN ('property', 'both')
      )
  );

CREATE OR REPLACE FUNCTION enforce_platform_role_permission_policy()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = public
AS $$
DECLARE
    v_is_system boolean;
    v_permission_code text;
BEGIN
    SELECT pr.is_system, pp.code
    INTO v_is_system, v_permission_code
    FROM platform_roles pr
    JOIN platform_permissions pp
      ON pp.id = NEW.platform_permission_id
    WHERE pr.id = NEW.platform_role_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Platform role permission references an unknown role or permission';
    END IF;

    IF v_is_system = false AND v_permission_code = 'platform.admin.all' THEN
        RAISE EXCEPTION 'platform.admin.all is reserved for system platform roles';
    END IF;

    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION enforce_tenant_role_permission_policy()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = public
AS $$
DECLARE
    v_is_system boolean;
    v_permission_code text;
    v_is_tenant_permission boolean;
    v_access_scope text;
BEGIN
    SELECT tr.is_system, p.code, pc.is_tenant_permission, pc.access_scope
    INTO v_is_system, v_permission_code, v_is_tenant_permission, v_access_scope
    FROM tenant_roles tr
    JOIN permissions p
      ON p.id = NEW.permission_id
     AND p.tenant_id = tr.tenant_id
    LEFT JOIN permission_catalog pc
      ON pc.code = p.code
    WHERE tr.id = NEW.tenant_role_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Tenant role permission references an unknown or cross-tenant role or permission';
    END IF;

    IF v_is_system = false AND v_permission_code = 'tenant.admin.all' THEN
        RAISE EXCEPTION 'tenant.admin.all is reserved for system tenant roles';
    END IF;

    IF v_is_system = false AND (
        v_is_tenant_permission IS DISTINCT FROM true
        OR v_access_scope NOT IN ('tenant', 'both')
    ) THEN
        RAISE EXCEPTION 'Dynamic tenant roles may contain only tenant-scoped permissions';
    END IF;

    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION enforce_property_role_permission_policy()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = public
AS $$
DECLARE
    v_is_system boolean;
    v_permission_code text;
    v_is_tenant_permission boolean;
    v_access_scope text;
BEGIN
    SELECT r.is_system, p.code, pc.is_tenant_permission, pc.access_scope
    INTO v_is_system, v_permission_code, v_is_tenant_permission, v_access_scope
    FROM roles r
    JOIN permissions p
      ON p.id = NEW.permission_id
     AND p.tenant_id = r.tenant_id
    LEFT JOIN permission_catalog pc
      ON pc.code = p.code
    WHERE r.id = NEW.role_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Property role permission references an unknown or cross-tenant role or permission';
    END IF;

    IF v_is_system = false AND v_permission_code = 'admin.all' THEN
        RAISE EXCEPTION 'admin.all is reserved for system property roles';
    END IF;

    IF v_is_system = false AND (
        v_is_tenant_permission IS DISTINCT FROM true
        OR v_access_scope NOT IN ('property', 'both')
    ) THEN
        RAISE EXCEPTION 'Dynamic property roles may contain only property-scoped permissions';
    END IF;

    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION prevent_platform_role_system_demotion()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = public
AS $$
BEGIN
    IF OLD.is_system = true AND NEW.is_system = false AND EXISTS (
        SELECT 1
        FROM platform_role_permissions prp
        JOIN platform_permissions pp
          ON pp.id = prp.platform_permission_id
        WHERE prp.platform_role_id = OLD.id
          AND pp.code = 'platform.admin.all'
    ) THEN
        RAISE EXCEPTION 'Platform role holding platform.admin.all cannot become dynamic';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION prevent_tenant_role_system_demotion()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = public
AS $$
BEGIN
    IF OLD.is_system = true AND NEW.is_system = false AND EXISTS (
        SELECT 1
        FROM tenant_role_permissions trp
        JOIN permissions p
          ON p.id = trp.permission_id
         AND p.tenant_id = OLD.tenant_id
        LEFT JOIN permission_catalog pc
          ON pc.code = p.code
        WHERE trp.tenant_role_id = OLD.id
          AND (
              p.code = 'tenant.admin.all'
              OR pc.is_tenant_permission IS DISTINCT FROM true
              OR pc.access_scope NOT IN ('tenant', 'both')
          )
    ) THEN
        RAISE EXCEPTION 'Tenant role holding reserved or non-tenant authority cannot become dynamic';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION prevent_property_role_system_demotion()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = public
AS $$
BEGIN
    IF OLD.is_system = true AND NEW.is_system = false AND EXISTS (
        SELECT 1
        FROM role_permissions rp
        JOIN permissions p
          ON p.id = rp.permission_id
         AND p.tenant_id = OLD.tenant_id
        LEFT JOIN permission_catalog pc
          ON pc.code = p.code
        WHERE rp.role_id = OLD.id
          AND (
              p.code = 'admin.all'
              OR pc.is_tenant_permission IS DISTINCT FROM true
              OR pc.access_scope NOT IN ('property', 'both')
          )
    ) THEN
        RAISE EXCEPTION 'Property role holding reserved or non-property authority cannot become dynamic';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_platform_role_permission_policy ON platform_role_permissions;
CREATE TRIGGER trg_platform_role_permission_policy
BEFORE INSERT OR UPDATE ON platform_role_permissions
FOR EACH ROW EXECUTE FUNCTION enforce_platform_role_permission_policy();

DROP TRIGGER IF EXISTS trg_tenant_role_permission_policy ON tenant_role_permissions;
CREATE TRIGGER trg_tenant_role_permission_policy
BEFORE INSERT OR UPDATE ON tenant_role_permissions
FOR EACH ROW EXECUTE FUNCTION enforce_tenant_role_permission_policy();

DROP TRIGGER IF EXISTS trg_property_role_permission_policy ON role_permissions;
CREATE TRIGGER trg_property_role_permission_policy
BEFORE INSERT OR UPDATE ON role_permissions
FOR EACH ROW EXECUTE FUNCTION enforce_property_role_permission_policy();

DROP TRIGGER IF EXISTS trg_platform_role_system_demotion ON platform_roles;
CREATE TRIGGER trg_platform_role_system_demotion
BEFORE UPDATE OF is_system ON platform_roles
FOR EACH ROW EXECUTE FUNCTION prevent_platform_role_system_demotion();

DROP TRIGGER IF EXISTS trg_tenant_role_system_demotion ON tenant_roles;
CREATE TRIGGER trg_tenant_role_system_demotion
BEFORE UPDATE OF is_system ON tenant_roles
FOR EACH ROW EXECUTE FUNCTION prevent_tenant_role_system_demotion();

DROP TRIGGER IF EXISTS trg_property_role_system_demotion ON roles;
CREATE TRIGGER trg_property_role_system_demotion
BEFORE UPDATE OF is_system ON roles
FOR EACH ROW EXECUTE FUNCTION prevent_property_role_system_demotion();

REVOKE ALL ON FUNCTION enforce_platform_role_permission_policy() FROM PUBLIC;
REVOKE ALL ON FUNCTION enforce_tenant_role_permission_policy() FROM PUBLIC;
REVOKE ALL ON FUNCTION enforce_property_role_permission_policy() FROM PUBLIC;
REVOKE ALL ON FUNCTION prevent_platform_role_system_demotion() FROM PUBLIC;
REVOKE ALL ON FUNCTION prevent_tenant_role_system_demotion() FROM PUBLIC;
REVOKE ALL ON FUNCTION prevent_property_role_system_demotion() FROM PUBLIC;
