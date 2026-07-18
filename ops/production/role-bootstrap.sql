-- Create production login roles and attach them to the no-login grant roles that
-- are managed by Flyway. Run this as the PostgreSQL administrator after the
-- database is created and before starting the API or worker.
--
-- Example:
--   podman compose --env-file ops/production/.env -f ops/production/compose.yaml exec postgres \
--     psql -v peak_app_password='...' \
--          -v peak_worker_password='...' \
--          -v peak_platform_password='...' \
--          -v peak_platform_support_password='...' \
--          -U "$POSTGRES_MIGRATOR_USER" -d "$POSTGRES_DB" \
--          -f /path/to/role-bootstrap.sql

\set ON_ERROR_STOP on

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pms_app') THEN
    CREATE ROLE pms_app NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE INHERIT NOBYPASSRLS;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pms_platform') THEN
    CREATE ROLE pms_platform NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE INHERIT NOBYPASSRLS;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pms_worker') THEN
    CREATE ROLE pms_worker NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE INHERIT NOBYPASSRLS;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pms_readonly_support') THEN
    CREATE ROLE pms_readonly_support NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE INHERIT NOBYPASSRLS;
  END IF;

  IF NOT EXISTS (
    SELECT 1
    FROM pg_catalog.pg_roles
    WHERE rolname = 'pms_tenant_continuity_owner'
  ) THEN
    CREATE ROLE pms_tenant_continuity_owner
      NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
  ELSE
    ALTER ROLE pms_tenant_continuity_owner
      NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
  END IF;
END;
$$;

SELECT format(
  'CREATE ROLE peak_app LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE INHERIT NOBYPASSRLS',
  :'peak_app_password'
)
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'peak_app')
\gexec

SELECT format(
  'CREATE ROLE peak_worker LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE INHERIT NOBYPASSRLS',
  :'peak_worker_password'
)
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'peak_worker')
\gexec

SELECT format(
  'CREATE ROLE peak_platform LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE INHERIT NOBYPASSRLS',
  :'peak_platform_password'
)
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'peak_platform')
\gexec

SELECT format(
  'CREATE ROLE peak_platform_support LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE INHERIT NOBYPASSRLS',
  :'peak_platform_support_password'
)
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'peak_platform_support')
\gexec

ALTER ROLE peak_app PASSWORD :'peak_app_password';
ALTER ROLE peak_platform PASSWORD :'peak_platform_password';
ALTER ROLE peak_worker PASSWORD :'peak_worker_password';
ALTER ROLE peak_platform_support PASSWORD :'peak_platform_support_password';

REVOKE pms_platform FROM peak_app;
GRANT pms_app TO peak_app;
GRANT pms_platform TO peak_platform;
GRANT pms_worker TO peak_worker;
GRANT pms_readonly_support TO peak_platform_support;
