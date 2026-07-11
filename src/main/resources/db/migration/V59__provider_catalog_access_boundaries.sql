-- Provider account/configuration is property-scoped, but provider catalogs are
-- broader control-plane data. Payment provider catalog entries are tenant-owned;
-- fiscal provider catalog entries are shared platform catalog rows.

INSERT INTO permission_catalog (
    code,
    namespace,
    access_scope,
    description,
    is_platform_permission,
    is_tenant_permission
) VALUES
    (
        'payments.provider_catalog.manage',
        'payments',
        'tenant',
        'Manage tenant payment provider catalog entries',
        false,
        true
    )
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
JOIN permission_catalog pc
  ON pc.code = 'payments.provider_catalog.manage'
WHERE t.deleted_at IS NULL
ON CONFLICT (tenant_id, code) DO UPDATE SET
    description = EXCLUDED.description,
    updated_at = now();

INSERT INTO tenant_role_permissions (tenant_role_id, permission_id)
SELECT tr.id, p.id
FROM tenant_roles tr
JOIN permissions p
  ON p.tenant_id = tr.tenant_id
 AND p.code = 'payments.provider_catalog.manage'
WHERE tr.is_active = true
  AND (
      tr.code = 'tenant_admin'
      OR EXISTS (
          SELECT 1
          FROM tenant_role_permissions trp
          JOIN permissions admin_permission
            ON admin_permission.id = trp.permission_id
           AND admin_permission.tenant_id = tr.tenant_id
          WHERE trp.tenant_role_id = tr.id
            AND admin_permission.code = 'tenant.admin.all'
      )
  )
ON CONFLICT ON CONSTRAINT tenant_role_permissions_pkey DO NOTHING;

INSERT INTO fiscal_providers (
    provider_code,
    country_code,
    name,
    authority_name,
    fiscal_mode,
    supports_realtime,
    supports_batch,
    is_active
) VALUES
    (
        'contract_mock',
        'TZ',
        'Contract Mock Fiscal Provider',
        'Tanzania Revenue Authority',
        'EFD_VFD',
        true,
        false,
        true
    ),
    (
        'signed_simulator',
        'TZ',
        'Signed Fiscal Simulator',
        'Tanzania Revenue Authority',
        'EFD_VFD',
        true,
        false,
        true
    ),
    (
        'http_gateway',
        'TZ',
        'HTTP Fiscal Gateway',
        'Tanzania Revenue Authority',
        'EFD_VFD',
        true,
        false,
        true
    )
ON CONFLICT (provider_code) DO UPDATE SET
    country_code = EXCLUDED.country_code,
    name = EXCLUDED.name,
    authority_name = EXCLUDED.authority_name,
    fiscal_mode = EXCLUDED.fiscal_mode,
    supports_realtime = EXCLUDED.supports_realtime,
    supports_batch = EXCLUDED.supports_batch,
    is_active = true,
    updated_at = now();
