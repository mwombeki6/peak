-- V110 — three corrections to the commercial-document model.
--
-- 1. A peak_receipt is FBC's commercial evidence, not a TRA fiscal receipt.
-- 2. Suspension survival is a property of a route, not of the controller it
--    happens to live in.
-- 3. The two fiscal contexts must be visibly, permanently separate.

-- -----------------------------------------------------------------------------
-- 1. peak_receipts is commercial evidence. It is not fiscalized.
-- -----------------------------------------------------------------------------
-- A sequential number that reads PEAK-RCP-2026-000123 looks official, and that
-- resemblance is the whole danger. A TRA fiscal receipt carries the seller's TIN
-- and VRN, EFD/UIN identifiers, a receipt/Z number, a tax breakdown and a
-- verification code that TRA's own service can be queried with. peak_receipts
-- carries none of those, because FBC's fiscalization of its own SaaS sales is a
-- separate workflow under FBC's taxpayer identity that does not exist yet.
--
-- The column exists so the distinction is a value the UI must render rather than
-- a convention someone has to already know. 'not_applicable' is the honest
-- default: nothing has been fiscalized, and claiming 'pending' would imply a
-- workflow is running.

ALTER TABLE peak_receipts
    ADD COLUMN fiscal_status varchar(20) NOT NULL DEFAULT 'not_applicable',
    ADD COLUMN fiscal_reference text,
    ADD CONSTRAINT chk_peak_receipts_fiscal_status
        CHECK (fiscal_status IN ('not_applicable', 'pending', 'issued', 'failed')),
    -- A receipt cannot claim to be fiscalized without saying which document it is.
    ADD CONSTRAINT chk_peak_receipts_fiscal_reference
        CHECK (fiscal_status <> 'issued' OR fiscal_reference IS NOT NULL);

COMMENT ON TABLE peak_receipts IS
    'FBC''s commercial receipt to a tenant for a Peak subscription purchase. NOT a TRA '
    'fiscal receipt: it carries no TIN/VRN, EFD identifiers, tax breakdown or TRA '
    'verification code. Fiscalizing FBC''s own SaaS sales is a separate workflow under '
    'FBC''s taxpayer identity. Never present this document as fiscal evidence.';

COMMENT ON COLUMN peak_receipts.fiscal_status IS
    'Whether a TRA fiscal document exists for this sale under FBC''s taxpayer identity. '
    'not_applicable until that workflow exists. Rendered to the tenant so a commercial '
    'receipt is never mistaken for fiscal evidence.';

-- -----------------------------------------------------------------------------
-- 2. Suspension survival is per-route, not per-controller.
-- -----------------------------------------------------------------------------
-- V109 asserted that every route under /api/tenants/:tenantId/billing/ must
-- resolve against the suspended allowances. That was right for the routes that
-- exist and wrong as a rule: it makes membership of TenantBillingController the
-- safety classification. A later /billing/refunds, /billing/write-offs or
-- /billing/contracts/terminate would inherit suspension access by living in the
-- same place, which is precisely backwards — those are the routes a suspended
-- tenant should not have.
--
-- The actual rule is narrower: a route must survive suspension when it is needed
-- to understand, pay, recover or obtain evidence for an outstanding obligation to
-- Peak. That is a judgement about the route, so it is recorded on the route.

ALTER TABLE module_access_matrix
    ADD COLUMN suspension_recovery_safe boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN module_access_matrix.suspension_recovery_safe IS
    'True when a suspended tenant must still reach this route to understand, pay, recover '
    'or obtain evidence for what it owes Peak. Suspension is ended by paying, so the route '
    'to paying and the evidence of having paid must both outlive it. Not a synonym for '
    '"billing route": a refund, write-off or termination is not recovery.';

UPDATE module_access_matrix
SET suspension_recovery_safe = true
WHERE api_pattern IN (
    '/api/tenants/:tenantId/billing/catalog',
    '/api/tenants/:tenantId/billing/quotes',
    '/api/tenants/:tenantId/billing/purchases',
    '/api/tenants/:tenantId/billing/purchases*',
    '/api/tenants/:tenantId/billing/purchases/:purchaseId/payments',
    '/api/tenants/:tenantId/billing/renewal-offers',
    '/api/tenants/:tenantId/billing/renewal-offers/:offerId/accept',
    '/api/tenants/:tenantId/billing/renewal-offers/:offerId/decline',
    '/api/tenants/:tenantId/billing/receipts',
    -- Predates platformbilling and was reachable under suspension without anyone
    -- classifying it. It is a GET of the tenant's own subscription, entitlements
    -- and usage — how a suspended customer sees what they owe — so it is recovery.
    -- The inverse assertion below is what surfaced it.
    '/api/tenants/:tenantId/commercial*'
);

DO $migration$
DECLARE
    lying text;
    overreaching text;
BEGIN
    -- A route that claims to survive suspension must actually survive it.
    SELECT string_agg(route.api_pattern, ', ')
    INTO lying
    FROM module_access_matrix route
    WHERE route.suspension_recovery_safe
      AND route.permission_code IS NOT NULL
      AND NOT EXISTS (
          SELECT 1 FROM peak_restriction_allowances allowance
          WHERE allowance.restriction_state = 'suspended'
            AND route.permission_code LIKE allowance.permission_pattern
      );

    IF lying IS NOT NULL THEN
        RAISE EXCEPTION
            'These routes are marked suspension_recovery_safe but their permission does not '
            'survive suspension, so the flag is a comment rather than a fact: %',
            lying;
    END IF;

    -- The direction that actually protects the future. A route nobody classified as
    -- recovery is reachable anyway when its permission pattern is wider than the route
    -- it guards. A refund endpoint reusing tenant.subscription.view would land here.
    SELECT string_agg(route.api_pattern || ' (' || route.permission_code || ')', ', ')
    INTO overreaching
    FROM module_access_matrix route
    WHERE NOT route.suspension_recovery_safe
      AND route.permission_code IS NOT NULL
      AND route.route_scope = 'tenant'
      AND route.api_pattern LIKE '/api/tenants/%'
      AND EXISTS (
          SELECT 1 FROM peak_restriction_allowances allowance
          WHERE allowance.restriction_state = 'suspended'
            AND route.permission_code LIKE allowance.permission_pattern
      );

    IF overreaching IS NOT NULL THEN
        RAISE EXCEPTION
            'These routes are reachable while suspended without being classified as '
            'recovery. Either mark them suspension_recovery_safe deliberately, or give '
            'them a permission narrow enough to exclude them: %',
            overreaching;
    END IF;
END;
$migration$;

-- -----------------------------------------------------------------------------
-- 3. Two taxpayers, two allocators, permanently.
-- -----------------------------------------------------------------------------
-- The hotel sells to a guest under the hotel's TRA identity. FBC sells to the
-- hotel under FBC's. These are different taxpayers, and their document numbering
-- must never meet: a shared sequence would put FBC's sales into a hotel's
-- numbering and make both sets of books unauditable.
--
-- V99 already chose the right thing by not reaching for allocate_document_number.
-- This asserts it rather than leaving it as a decision someone has to rediscover.

DO $migration$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_proc
        WHERE proname = 'allocate_peak_receipt_number'
          AND pg_get_functiondef(oid) ILIKE '%document_sequences%'
    ) THEN
        RAISE EXCEPTION
            'FBC receipt numbering reads the tenant document allocator. Those are two '
            'taxpayers; sharing a sequence makes both sets of books unauditable.';
    END IF;

    IF EXISTS (
        SELECT 1 FROM pg_proc
        WHERE proname = 'allocate_document_number'
          AND pg_get_functiondef(oid) ILIKE '%peak_receipt_number_seq%'
    ) THEN
        RAISE EXCEPTION
            'The tenant document allocator reads FBC''s receipt sequence, which would put '
            'FBC''s own sales into a hotel''s invoice numbering.';
    END IF;
END;
$migration$;
