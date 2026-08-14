-- V114 — every permission states the weakest session that may exercise it.
--
-- Deny by default. 'strong' is the default, so a permission nobody has
-- classified is unreachable from a device PIN session. The failure mode of
-- forgetting to classify is a waiter being refused something they should have
-- had; the inverse would be a waiter reaching a settlement account.
--
-- This is deliberately NOT the assurance ladder. That one is
-- NONE < MFA < PHISHING_RESISTANT read from the token's acr/amr, so a manager
-- signing in with a password and no second factor sits at NONE. Ranking a
-- six-digit PIN above that manager would be false, and defaulting to it would
-- refuse every existing user on the day this shipped.

ALTER TABLE permission_catalog
    ADD COLUMN minimum_session_class varchar(20) NOT NULL DEFAULT 'strong',
    ADD CONSTRAINT chk_permission_catalog_session_class
        CHECK (minimum_session_class IN ('operational', 'strong'));

COMMENT ON COLUMN permission_catalog.minimum_session_class IS
    'The weakest session class that may exercise this permission. Defaults to strong, so an '
    'unclassified permission is refused to a device-bound PIN session rather than allowed. '
    'Independent of authentication assurance, which asks whether an MFA ceremony happened.';

-- The work a waiter, cashier, housekeeper or technician does on a device.
-- Nothing here changes a price, a role, a provider, a subscription, or a
-- payment that has already settled.
UPDATE permission_catalog SET minimum_session_class = 'operational'
WHERE code IN (
    'pos.view',
    'pos.order.manage',
    'pos.order.settle',
    'pos.kitchen.view',
    'pos.kitchen.manage',
    'pos.item.void',
    'pos.session.manage',
    'housekeeping.view',
    'housekeeping.manage',
    'maintenance.view',
    'maintenance.manage',
    'folio.view',
    'payments.collect',
    'payments.view',
    'frontdesk.stays.view'
);

DO $migration$
DECLARE
    v_classified integer;
    v_missing text;
    v_dangerous text;
BEGIN
    -- An UPDATE whose WHERE matches nothing succeeds silently. If these codes
    -- are not actually rows in permission_catalog, every one of them stays
    -- 'strong' and a device session can do nothing at all — a failure that would
    -- surface as "the POS is broken" long after this migration passed.
    SELECT count(*) INTO v_classified
    FROM permission_catalog
    WHERE minimum_session_class = 'operational';

    SELECT string_agg(expected.code, ', ')
    INTO v_missing
    FROM (VALUES
        ('pos.view'), ('pos.order.manage'), ('pos.order.settle'),
        ('pos.kitchen.view'), ('pos.kitchen.manage'), ('pos.item.void'),
        ('pos.session.manage'), ('housekeeping.view'), ('housekeeping.manage'),
        ('maintenance.view'), ('maintenance.manage'), ('folio.view'),
        ('payments.collect'), ('payments.view'), ('frontdesk.stays.view')
    ) AS expected(code)
    WHERE NOT EXISTS (
        SELECT 1 FROM permission_catalog pc WHERE pc.code = expected.code
    );

    IF v_missing IS NOT NULL THEN
        RAISE EXCEPTION
            'These permissions were classified as operational but are not in '
            'permission_catalog, so the update matched nothing and they remain strong: %',
            v_missing;
    END IF;

    IF v_classified = 0 THEN
        RAISE EXCEPTION
            'No permission was classified as operational. A device session would be able to '
            'do nothing, which is not what deny-by-default is supposed to mean.';
    END IF;

    -- The direction that protects the future. Whatever anyone lists above, a
    -- permission that moves money, changes identity or alters configuration must
    -- never be reachable from six digits typed on a terminal.
    SELECT string_agg(code, ', ')
    INTO v_dangerous
    FROM permission_catalog
    WHERE minimum_session_class = 'operational'
      AND (
          code LIKE 'platform.%'
       OR code LIKE 'tenant.subscription.%'
       OR code LIKE 'tenant.users.%'
       OR code LIKE 'tenant.roles.%'
       OR code LIKE 'tenant.properties.%'
       OR code LIKE '%.configure'
       OR code LIKE 'payments.refund%'
       OR code LIKE 'payments.reconcile%'
       OR code LIKE '%write_off%'
       OR code IN ('tenant.admin.all', 'admin.all')
      );

    IF v_dangerous IS NOT NULL THEN
        RAISE EXCEPTION
            'These permissions are reachable from a PIN session and must not be: %',
            v_dangerous;
    END IF;
END;
$migration$;
