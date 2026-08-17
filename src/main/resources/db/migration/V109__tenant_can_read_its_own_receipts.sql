-- V109 — the receipt Peak issues to a tenant was readable only by Peak.
--
-- V99 built the receipt properly: peak_receipts, one per purchase enforced by
-- uq_peak_receipts_purchase, with a sequential number from
-- allocate_peak_receipt_number(). Then it registered exactly one route to read
-- them, /api/platform/billing/receipts, behind platform.billing.view — a Peak
-- staff permission. TenantBillingController exposed catalog, quotes, purchases
-- and renewal offers, and no receipts at all.
--
-- So Peak took a hotel's money, allocated a numbered receipt for it, and filed
-- that receipt where the hotel could not reach it. For a Tanzanian business the
-- receipt is what their bookkeeping and their own TRA filing rest on. This is the
-- same shape as the suspended-checkout gap in V108: the artifact existed, was
-- correct, and was unreachable by the person who needed it.
--
-- Reuses tenant.subscription.view rather than minting a permission. That is not
-- laziness — it is the same read authority as the purchase history the receipt
-- corresponds to, and it matches 'tenant.subscription.%', which is allowed under
-- suspension. A suspended tenant pays to end the suspension; being handed service
-- back but not the receipt for it would be a strange place to draw a line.

INSERT INTO module_access_matrix (
    module_id, screen_key, screen_label, http_method, api_pattern,
    permission_code, route_scope, guard_mode, access_scope,
    is_tanzania_v1, is_enabled_by_default, notes
) VALUES
    ('tenant_admin', 'subscription.receipts', 'Subscription Receipts', 'GET',
     '/api/tenants/:tenantId/billing/receipts', 'tenant.subscription.view',
     'tenant', 'staff_permission', 'tenant', true, true,
     'The tenant''s own receipts for what it has paid Peak. Previously issued and readable '
     'only by Peak staff, which withheld the document the customer''s own bookkeeping '
     'depends on.')
ON CONFLICT DO NOTHING;

DO $migration$
DECLARE
    unreachable text;
    missing text;
BEGIN
    -- A tenant must be able to reach its own commercial documents while suspended,
    -- because suspension is ended by paying and the receipt is the evidence of it.
    SELECT string_agg(route.api_pattern, ', ')
    INTO unreachable
    FROM module_access_matrix route
    WHERE route.api_pattern LIKE '/api/tenants/:tenantId/billing/%'
      AND route.permission_code IS NOT NULL
      AND NOT EXISTS (
          SELECT 1 FROM peak_restriction_allowances allowance
          WHERE allowance.restriction_state = 'suspended'
            AND route.permission_code LIKE allowance.permission_pattern
      );

    IF unreachable IS NOT NULL THEN
        RAISE EXCEPTION
            'A suspended tenant cannot reach its own billing routes: %. Suspension is ended '
            'by paying, so the route to paying and the evidence of having paid must both '
            'survive it.',
            unreachable;
    END IF;

    -- V108 asserted four permissions on the departure path and stopped short of the
    -- fiscal receipt, which checkout also refuses without, and of the override that
    -- exists for when the fiscal provider is unreachable. Both are currently covered
    -- by the 'fiscal.%' and 'checkout.%' patterns; neither was actually asserted, so
    -- narrowing either pattern would have gone unnoticed until a guest was standing
    -- at the desk.
    SELECT string_agg(required.code, ', ')
    INTO missing
    FROM (VALUES
        ('checkout.process'),
        ('checkout.fiscal_override'),
        ('checkout.unpaid_override'),
        ('billing.invoice'),
        ('folio.view'),
        ('payments.collect'),
        ('fiscal.receipts.view'),
        ('fiscal.receipts.retry')
    ) AS required(code)
    WHERE NOT EXISTS (
        SELECT 1 FROM peak_restriction_allowances allowance
        WHERE allowance.restriction_state = 'suspended'
          AND required.code LIKE allowance.permission_pattern
    );

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION
            'A suspended tenant cannot complete a lawful checkout without: %. Issuing the '
            'fiscal receipt is as much a part of departure as the invoice, and the override '
            'is what lets a guest leave when the fiscal provider cannot be reached.',
            missing;
    END IF;
END;
$migration$;
