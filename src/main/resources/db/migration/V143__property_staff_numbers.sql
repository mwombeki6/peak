-- V143 — the staff number is a property membership, not a human attribute. The prior scheme
-- (V113's users.staff_number) is a single per-tenant identifier shared across every property a
-- person is assigned to, which is exactly the anti-pattern this replaces: the same waiter
-- covering two hotels under one tenant had one number, so it named the person, not the job. A
-- property staff number belongs to the (tenant, user, property) membership. Retiring a
-- membership retires the number; a later re-assignment gets a new one — the counter never goes
-- backwards, so a retired number is never handed to someone else.

CREATE TABLE property_staff_number_sequences (
    property_id uuid PRIMARY KEY REFERENCES properties(id) DEFERRABLE,
    tenant_id uuid NOT NULL REFERENCES tenants(id) DEFERRABLE,
    next_value integer NOT NULL DEFAULT 1
);

ALTER TABLE property_staff_number_sequences ENABLE ROW LEVEL SECURITY;
ALTER TABLE property_staff_number_sequences FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON property_staff_number_sequences
    USING (tenant_id = current_tenant_id());

CREATE TABLE property_staff_numbers (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES tenants(id) DEFERRABLE,
    property_id uuid NOT NULL REFERENCES properties(id) DEFERRABLE,
    user_id uuid NOT NULL REFERENCES users(id) DEFERRABLE,
    staff_number text NOT NULL,
    local_sequence integer NOT NULL,
    status varchar(10) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'RETIRED')),
    assigned_at timestamptz NOT NULL DEFAULT now(),
    retired_at timestamptz,
    CONSTRAINT chk_property_staff_numbers_retired_consistency
        CHECK ((status = 'RETIRED') = (retired_at IS NOT NULL)),
    CONSTRAINT fk_property_staff_numbers_tenant_property
        FOREIGN KEY (tenant_id, property_id) REFERENCES properties (tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_property_staff_numbers_tenant_user
        FOREIGN KEY (tenant_id, user_id) REFERENCES users (tenant_id, id) DEFERRABLE
);

-- One local sequence value is used exactly once per property, forever — the POS local-suffix
-- login (server resolves trusted device property + typed suffix) depends on this being unique
-- even across retired rows, so a retired number can never be confused with an active one at the
-- same property.
CREATE UNIQUE INDEX uq_property_staff_numbers_sequence
    ON property_staff_numbers (property_id, local_sequence);

-- At most one ACTIVE row per (tenant, user, property) — this is what makes "transfer" fall out
-- of revoke + assign for free, and what StaffProvisionService/TenantPropertyRoleManagementService
-- check before allocating a new number.
CREATE UNIQUE INDEX uq_property_staff_numbers_active_membership
    ON property_staff_numbers (tenant_id, user_id, property_id)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_property_staff_numbers_tenant_user
    ON property_staff_numbers (tenant_id, user_id);

ALTER TABLE property_staff_numbers ENABLE ROW LEVEL SECURITY;
ALTER TABLE property_staff_numbers FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON property_staff_numbers
    USING (tenant_id = current_tenant_id());

GRANT SELECT, INSERT, UPDATE ON property_staff_number_sequences TO pms_app;
GRANT SELECT, INSERT, UPDATE ON property_staff_numbers TO pms_app;

CREATE FUNCTION allocate_property_staff_number(
    p_tenant_id uuid,
    p_property_id uuid,
    p_user_id uuid
) RETURNS text
    LANGUAGE plpgsql
    AS $$
DECLARE
    v_next integer;
    v_property_number text;
    v_staff_number text;
BEGIN
    -- Idempotent by design: both call sites (hiring staff, assigning an additional role at a
    -- property the person is already staffed at) may legitimately ask for a number that already
    -- exists. Returning the existing one avoids a check-then-insert race with the caller and
    -- avoids burning a sequence value on a no-op.
    SELECT staff_number INTO v_staff_number
    FROM property_staff_numbers
    WHERE tenant_id = p_tenant_id
      AND property_id = p_property_id
      AND user_id = p_user_id
      AND status = 'ACTIVE';

    IF FOUND THEN
        RETURN v_staff_number;
    END IF;

    INSERT INTO property_staff_number_sequences (property_id, tenant_id)
    VALUES (p_property_id, p_tenant_id)
    ON CONFLICT (property_id) DO NOTHING;

    UPDATE property_staff_number_sequences
    SET next_value = next_value + 1
    WHERE property_id = p_property_id
      AND tenant_id = p_tenant_id
    RETURNING next_value - 1 INTO v_next;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Property % not found for tenant %', p_property_id, p_tenant_id;
    END IF;

    SELECT property_number INTO v_property_number
    FROM properties
    WHERE id = p_property_id
      AND tenant_id = p_tenant_id;

    IF v_property_number IS NULL THEN
        RAISE EXCEPTION 'Property % not found for tenant %', p_property_id, p_tenant_id;
    END IF;

    v_staff_number := 'ST-' || substring(v_property_number FROM 4) || '-' || lpad(v_next::text, 5, '0');

    INSERT INTO property_staff_numbers (tenant_id, property_id, user_id, staff_number, local_sequence)
    VALUES (p_tenant_id, p_property_id, p_user_id, v_staff_number, v_next);

    RETURN v_staff_number;
END;
$$;

GRANT EXECUTE ON FUNCTION allocate_property_staff_number(uuid, uuid, uuid) TO pms_app;
