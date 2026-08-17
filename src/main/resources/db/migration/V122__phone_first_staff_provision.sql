-- V122 — a waiter is hired with a phone, not an email address.
--
-- Keycloak invitations stay email-NOT-NULL: a manager still accepts with a verified
-- OIDC identity. Frontline staff never go through that path. They are provisioned
-- here, given a staff number, and (when a phone is present) sent an activation
-- secret over SMS. Email is optional. A staff member with no phone is still valid;
-- the manager hands them the one-time secret in person.
--
-- The property role assigned at provision must be entirely operational. A PIN
-- session cannot exercise a strong permission, so hiring a tenant administrator
-- through this route would create an account that cannot actually administer.

ALTER TABLE users
    ADD CONSTRAINT chk_users_phone_e164
    CHECK (phone_number IS NULL OR phone_number ~ '^\+[1-9][0-9]{7,14}$');

COMMENT ON COLUMN users.phone_number IS
    'E.164 mobile number used to deliver an activation SMS. Optional: a staff member with '
    'neither email nor phone is an ordinary hotel employee identified by staff_number.';

CREATE UNIQUE INDEX uq_users_tenant_phone_number
    ON users (tenant_id, phone_number)
    WHERE phone_number IS NOT NULL AND deleted_at IS NULL;

INSERT INTO module_access_matrix (
    module_id, screen_key, screen_label, http_method, api_pattern,
    permission_code, route_scope, guard_mode, access_scope,
    is_tanzania_v1, is_enabled_by_default, notes
) VALUES
    (
        'tenant_admin', 'staff.provision', 'Provision Operational Staff',
        'POST', '/api/tenants/:tenantId/staff',
        'tenant.users.manage', 'tenant', 'staff_permission', 'tenant',
        true, true,
        'Creates a staff member without email, allocates a staff number, assigns an operational property role, and issues a PIN activation. SMS when a phone is present. Strong session required.'
    )
ON CONFLICT (module_id, screen_key, http_method, api_pattern, permission_code)
DO UPDATE SET
    screen_label = EXCLUDED.screen_label,
    route_scope = EXCLUDED.route_scope,
    guard_mode = EXCLUDED.guard_mode,
    access_scope = EXCLUDED.access_scope,
    is_tanzania_v1 = EXCLUDED.is_tanzania_v1,
    is_enabled_by_default = EXCLUDED.is_enabled_by_default,
    notes = EXCLUDED.notes,
    updated_at = now();

INSERT INTO module_access_matrix (
    module_id, screen_key, screen_label, http_method, api_pattern,
    permission_code, route_scope, guard_mode, access_scope,
    is_tanzania_v1, is_enabled_by_default, notes
) VALUES
    (
        'tenant_admin', 'staff.credentials.activate', 'Activate Staff PIN',
        'POST', '/api/staff/credentials/activate',
        NULL, 'public', 'public_token', 'tenant',
        true, true,
        'Public: staff number + one-time secret + chosen PIN. Never returns the PIN. Not a Keycloak flow.'
    )
ON CONFLICT (module_id, screen_key, http_method, api_pattern)
WHERE permission_code IS NULL
DO UPDATE SET
    screen_label = EXCLUDED.screen_label,
    route_scope = EXCLUDED.route_scope,
    guard_mode = EXCLUDED.guard_mode,
    access_scope = EXCLUDED.access_scope,
    notes = EXCLUDED.notes,
    updated_at = now();

DO $migration$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'chk_users_phone_e164'
          AND conrelid = 'users'::regclass
    ) THEN
        RAISE EXCEPTION 'chk_users_phone_e164 is missing; staff phones can be free-form again';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_indexes
        WHERE indexname = 'uq_users_tenant_phone_number'
    ) THEN
        RAISE EXCEPTION 'uq_users_tenant_phone_number is missing; two staff could share a phone';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM module_access_matrix
        WHERE api_pattern = '/api/tenants/:tenantId/staff'
          AND http_method = 'POST'
          AND permission_code = 'tenant.users.manage'
          AND guard_mode = 'staff_permission'
    ) THEN
        RAISE EXCEPTION 'staff provision route is missing from module_access_matrix';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM module_access_matrix
        WHERE api_pattern = '/api/staff/credentials/activate'
          AND http_method = 'POST'
          AND permission_code IS NULL
          AND guard_mode = 'public_token'
          AND route_scope = 'public'
    ) THEN
        RAISE EXCEPTION 'staff PIN activation route is missing from module_access_matrix';
    END IF;
END;
$migration$;
