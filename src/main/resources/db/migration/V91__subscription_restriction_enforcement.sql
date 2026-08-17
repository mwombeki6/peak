-- =============================================================================
-- Make a lapsed subscription restrict something, without stranding a guest
--
-- lifecycle_status already records that a tenant is restricted or suspended, and
-- nothing reads it to decide anything. A tenant moved to 'restricted' keeps full
-- access to every route. Billing therefore has no consequence, which is a poor
-- foundation for selling subscriptions.
--
-- The blunt fix is to disable the tenant's modules, and it is the wrong one. A
-- hotel that is late paying still has guests in rooms. Turning off the frontdesk
-- module strands them at checkout, and turning off payments means the hotel
-- cannot take the money it needs in order to pay us. Restriction has to be
-- selective: deny growth and administration, permit operations.
--
-- That selection is expressed as data rather than as branches in code. A row
-- says which permissions survive which restriction state, and the enforcement is
-- one extra conjunct in can_access_module — the single function every tenant
-- staff route already passes through, via RouteGuardInterceptor and
-- JdbcAuthorizationPort.
--
-- Doing it here rather than in Kotlin is not a preference. usermanagement owns
-- that authorization path and platformbilling depends on tenantmanagement, which
-- depends on usermanagement; a Kotlin check would need the arrow to point back
-- and ModulithArchitectureTests would reject the cycle. Driving it from a column
-- tenantmanagement already owns is cycle-free by construction.
--
-- Allowances match by pattern rather than by exact code. An exact list denies
-- anything added later by omission, so the first new frontdesk permission would
-- silently become unavailable to restricted tenants and nobody would connect the
-- two. A pattern keeps whole operational namespaces working as they grow, while
-- still naming each exception deliberately.
-- =============================================================================

CREATE TABLE peak_restriction_allowances (
    restriction_state varchar(20) NOT NULL,
    permission_pattern text NOT NULL,
    rationale text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (restriction_state, permission_pattern),
    CONSTRAINT chk_peak_restriction_allowances_state
        CHECK (restriction_state IN ('restricted', 'suspended'))
);

GRANT SELECT ON peak_restriction_allowances
    TO pms_app, pms_platform, pms_worker, pms_readonly_support;
GRANT INSERT, UPDATE ON peak_restriction_allowances TO pms_platform;
REVOKE DELETE ON peak_restriction_allowances
    FROM pms_app, pms_platform, pms_worker, pms_readonly_support;

-- RESTRICTED: the hotel keeps running; it just cannot grow or reconfigure.
INSERT INTO peak_restriction_allowances (restriction_state, permission_pattern, rationale) VALUES
    ('restricted', 'frontdesk.%',        'Arrivals and departures must not stop because an invoice is late'),
    ('restricted', 'checkout.%',         'A guest in the building must always be able to leave'),
    ('restricted', 'folio.%',            'A stay already open has to be settleable'),
    ('restricted', 'billing.%',          'Charges accrued must still be billable'),
    ('restricted', 'payments.%',         'A hotel that cannot take money cannot pay us either'),
    ('restricted', 'fiscal.%',           'Fiscal submission is a legal obligation, not a feature'),
    ('restricted', 'night_audit.%',      'The daily close protects the integrity of the ledger'),
    ('restricted', 'housekeeping.%',     'Rooms still have to be turned over'),
    ('restricted', 'maintenance.%',      'Faults still have to be recorded'),
    ('restricted', 'reservations.%',     'Existing bookings must remain serviceable'),
    ('restricted', 'guests.%',           'Guest records support the operations above'),
    ('restricted', 'lost_found.%',       'Incidental to normal front-of-house operation'),
    ('restricted', 'pos.order%',         'Outlets already trading keep trading; pos.configure does not'),
    ('restricted', 'reports.%.view',     'Reading numbers is not growth'),
    ('restricted', 'reports.view%',      'Reading numbers is not growth'),
    ('restricted', 'tenant.data.export', 'Data portability is a right, not a lever'),
    ('restricted', 'tenant.subscription.%', 'The route to paying must never be the route that is blocked');

-- SUSPENDED: read-only, plus the handful of things that would be indefensible to
-- withhold. A guest must never be trapped, and a customer must never be locked
-- out of the page where they settle up.
INSERT INTO peak_restriction_allowances (restriction_state, permission_pattern, rationale) VALUES
    ('suspended', 'checkout.%',          'A guest in the building must always be able to leave'),
    ('suspended', 'frontdesk.checkout%', 'Departure is not negotiable'),
    ('suspended', 'payments.%',          'Settling an outstanding folio must remain possible'),
    ('suspended', 'folio.view%',         'The bill being settled has to be readable'),
    ('suspended', 'fiscal.%',            'Fiscal obligations outlive the commercial relationship'),
    ('suspended', 'tenant.data.export',  'Data portability is a right, not a lever'),
    ('suspended', 'tenant.subscription.%', 'The route to paying must never be the route that is blocked');

-- The function has to read tenant_control_states, whose row policy contains
-- `OR platform_user_has_permission(...)` — a function pms_app cannot execute. It
-- therefore runs as a definer.
--
-- A definer owned by the migrator would run as a superuser and bypass row-level
-- security entirely, which DefinerSearchPathIntegrationTests refuses: its ledger
-- exists so that a new definer cannot quietly acquire that reach. The convention
-- here, established by V72 for the continuity lock, is a dedicated NOBYPASSRLS
-- owner per privileged function, so the function gets exactly the reach it needs
-- and none of the reach a superuser would bring with it.
DO $migration$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'pms_billing_restriction_owner'
    ) THEN
        CREATE ROLE pms_billing_restriction_owner
            NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
    ELSE
        ALTER ROLE pms_billing_restriction_owner
            NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
    END IF;
END;
$migration$;

GRANT SELECT ON tenant_control_states TO pms_billing_restriction_owner;
GRANT SELECT ON peak_restriction_allowances TO pms_billing_restriction_owner;
GRANT EXECUTE ON FUNCTION platform_user_has_permission(uuid, text)
    TO pms_billing_restriction_owner;

-- The owner is NOBYPASSRLS, so without this it sees tenant_control_states only
-- through the tenant policy, and only when the calling session happens to be
-- tenant-bound. That would make the control fail *open*: no visible row means no
-- restriction found, and a suspended tenant would silently regain everything.
--
-- The function is passed the tenant it must judge, so it has to be able to read
-- that tenant's row on its own account. This policy is read-only, scoped to a
-- NOLOGIN role that nothing but the function runs as, and grants no write reach.
CREATE POLICY billing_restriction_owner_reads ON tenant_control_states
    FOR SELECT
    TO pms_billing_restriction_owner
    USING (true);

CREATE OR REPLACE FUNCTION tenant_restriction_permits(
    p_tenant_id uuid,
    p_permission_code text
) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public, pg_temp
    AS $$
  -- True unless the tenant is in a restricting state and the permission is not
  -- allowed under it. A tenant with no control-state row, or in any other state,
  -- is unaffected.
  SELECT NOT EXISTS (
      SELECT 1
      FROM tenant_control_states control
      WHERE control.tenant_id = p_tenant_id
        AND control.lifecycle_status IN ('restricted', 'suspended')
        AND NOT EXISTS (
            SELECT 1
            FROM peak_restriction_allowances allowance
            WHERE allowance.restriction_state = control.lifecycle_status
              AND p_permission_code LIKE allowance.permission_pattern
        )
  );
$$;

-- Owned by the dedicated NOBYPASSRLS role rather than the migrator, so the
-- definer runs with that role's reach: read tenant_control_states and the
-- allowance list, and nothing else.
ALTER FUNCTION tenant_restriction_permits(uuid, text)
    OWNER TO pms_billing_restriction_owner;

REVOKE EXECUTE ON FUNCTION tenant_restriction_permits(uuid, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tenant_restriction_permits(uuid, text)
    TO pms_app, pms_platform, pms_worker;

-- The body is unchanged apart from the final conjunct. can_access_module is not
-- SECURITY DEFINER and carries no pinned search_path, so there is no proconfig to
-- restate here.
CREATE OR REPLACE FUNCTION can_access_module(
    p_user_id uuid,
    p_tenant_id uuid,
    p_property_id uuid,
    p_module_id text,
    p_permission_code text DEFAULT NULL
) RETURNS boolean
    LANGUAGE sql STABLE
    AS $$
  SELECT
    p_tenant_id = current_tenant_id()
    AND current_platform_user_id() IS NULL
    AND p_user_id = current_tenant_user_id()
    AND p_permission_code IS NOT NULL
    AND is_tenant_module_enabled(p_tenant_id, p_module_id)
    AND (
      (
        p_property_id IS NULL
        AND user_has_tenant_permission(p_user_id, p_tenant_id, p_permission_code)
      )
      OR (
        p_property_id IS NOT NULL
        AND is_property_module_enabled(p_tenant_id, p_property_id, p_module_id)
        AND user_has_property_permission(p_user_id, p_tenant_id, p_property_id, p_permission_code)
      )
    )
    AND tenant_restriction_permits(p_tenant_id, p_permission_code);
$$;

DO $migration$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_catalog.pg_proc
        WHERE proname = 'tenant_restriction_permits'
          AND 'search_path=pg_catalog, public, pg_temp' = ANY(proconfig)
    ) THEN
        RAISE EXCEPTION 'tenant_restriction_permits must pin its search_path';
    END IF;

    IF pg_catalog.pg_get_functiondef(
        'public.can_access_module(uuid, uuid, uuid, text, text)'::regprocedure
    ) NOT LIKE '%tenant_restriction_permits%' THEN
        RAISE EXCEPTION 'can_access_module was not rewritten to consult the restriction state';
    END IF;
END;
$migration$;
