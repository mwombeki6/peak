-- V113 — a staff member is a person, not an email address.
--
-- users.email was NOT NULL, so onboarding a restaurant meant inventing an
-- address for every waiter, cashier and housekeeper. Identity data that is
-- garbage by construction, and then has to be trusted never to receive
-- anything, forever, by everyone who touches it.
--
-- A staff member with no email and no phone is an ordinary hotel employee. What
-- they have instead is a number they type on a terminal.

ALTER TABLE users ALTER COLUMN email DROP NOT NULL;

-- idx_users_tenant_email (V1:9191) is already partial on deleted_at IS NULL, and
-- a btree treats NULLs as distinct, so any number of staff without an address
-- coexist while a present address stays unique per tenant.

ALTER TABLE users ADD COLUMN staff_number varchar(10);

COMMENT ON COLUMN users.staff_number IS
    'Short number a staff member types on a POS or handheld. Unique per tenant rather than '
    'per property, so a group can move someone between hotels without reissuing it. Stable '
    'across role changes: a waiter promoted to supervisor keeps the same number, because a '
    'role is an assignment and this is identity.';

CREATE UNIQUE INDEX uq_users_tenant_staff_number
    ON users (tenant_id, staff_number)
    WHERE staff_number IS NOT NULL AND deleted_at IS NULL;

-- -----------------------------------------------------------------------------
-- users.status was nullable with no CHECK, and three security functions compare
-- it to 'active': resolve_oidc_identity_link (V7), user_has_tenant_permission
-- (V1:556) and user_has_property_permission (V1:450).
--
-- A NULL therefore read as "not active" in all three at once. The user could not
-- authenticate, held no permission anywhere, and nothing in the system said why
-- — the row looked complete and every gate silently declined it.
-- -----------------------------------------------------------------------------

UPDATE users SET status = 'active' WHERE status IS NULL AND is_active;
UPDATE users SET status = 'disabled' WHERE status IS NULL;

ALTER TABLE users ALTER COLUMN status SET DEFAULT 'active';
ALTER TABLE users ALTER COLUMN status SET NOT NULL;

-- The three values TenantUserLifecycleService writes, and no others.
ALTER TABLE users ADD CONSTRAINT chk_users_status
    CHECK (status IN ('active', 'locked', 'disabled'));

-- -----------------------------------------------------------------------------

CREATE FUNCTION allocate_staff_number(p_tenant_id uuid)
RETURNS varchar
LANGUAGE plpgsql
SET search_path = pg_catalog, public, pg_temp
AS $function$
DECLARE
    v_next integer;
BEGIN
    -- Serialised per tenant so two managers adding staff at the same moment
    -- cannot be handed the same number. The lock is on the tenant row, which is
    -- the smallest thing that orders allocation without blocking other hotels.
    PERFORM 1 FROM tenants WHERE id = p_tenant_id FOR SHARE;

    SELECT coalesce(max(staff_number::integer), 0) + 1
    INTO v_next
    FROM users
    WHERE tenant_id = p_tenant_id
      AND staff_number ~ '^[0-9]+$';

    -- Gaps are fine and expected: a staff number identifies a person, it does
    -- not count them. Reusing a departed employee's number would attach their
    -- history to someone else.
    RETURN lpad(v_next::text, 4, '0');
END;
$function$;

REVOKE ALL ON FUNCTION allocate_staff_number(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION allocate_staff_number(uuid) TO pms_app;

DO $migration$
DECLARE
    v_null_status integer;
BEGIN
    SELECT count(*) INTO v_null_status FROM users WHERE status IS NULL;
    IF v_null_status > 0 THEN
        RAISE EXCEPTION
            '% users still have no status. Three security functions read that as inactive, '
            'so those accounts cannot authenticate and nothing reports why.',
            v_null_status;
    END IF;

    -- The backfill above is only correct if the constraint now forbids what it
    -- repaired. Without this the migration could pass having fixed the rows and
    -- left the door open behind it.
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'chk_users_status'
          AND conrelid = 'users'::regclass
    ) THEN
        RAISE EXCEPTION 'chk_users_status is missing; users.status can go NULL again';
    END IF;
END;
$migration$;
