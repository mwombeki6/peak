-- =============================================================================
-- What Peak sells, and the routes a tenant buys it through
--
-- Three base tiers and four add-ons. The tiers map onto the plans that already
-- exist, rather than introducing a parallel set: a tenant's plan_id keeps
-- meaning what it meant, and the limit machinery built on plans.max_* keeps
-- working untouched.
--
-- Add-ons carry no plan because a tenant may hold only one service-granting
-- subscription. They grant their entitlements directly, which is also what makes
-- them composable: a lodge can take Peak Core plus POS without being pushed onto
-- a tier it does not otherwise need.
--
-- POS and Inventory are priced per property. A three-property group running
-- restaurants gets three times the value and pays three times; a single
-- guesthouse is not asked to subsidise them. The tenant chooses which properties
-- are covered at quote time, and the reconciler grants only those.
--
-- Longer terms are stored as the price of the term, not as a discount rate. The
-- twelve-month price is three months' saving, which is the number the customer
-- is actually comparing, and it means no rounding argument later.
--
-- Peak Group carries no price rows. It is sold by contract, so the catalog marks
-- it unsellable rather than inventing a number the sales conversation will
-- contradict.
-- =============================================================================

INSERT INTO peak_products (
    code, name, description, kind, plan_code, tier_rank,
    requires_product_code, is_per_property, is_sellable, display_order
) VALUES
    ('peak_core', 'Peak Core',
     'The hotel operating loop: reservations, front office, folios, payments, fiscal and the daily close.',
     'base', 'starter', 1, NULL, false, true, 10),
    ('peak_pro', 'Peak Pro',
     'Everything in Core, plus deeper operations: maintenance, analytics and higher capacity.',
     'base', 'pro', 2, NULL, false, true, 20),
    ('peak_group', 'Peak Group',
     'Multi-property operation with portfolio reporting and central configuration. Sold by contract.',
     'base', 'enterprise', 3, NULL, false, false, 30)
ON CONFLICT (code) DO NOTHING;

INSERT INTO peak_products (
    code, name, description, kind, plan_code, tier_rank,
    requires_product_code, is_per_property, is_sellable, display_order
) VALUES
    ('peak_pos', 'Peak POS',
     'Restaurant, bar and outlet trading with cashier sessions and order workflow.',
     'addon', NULL, NULL, NULL, true, true, 100),
    ('peak_inventory', 'Peak Inventory & Procurement',
     'Stock, suppliers, purchasing, recipes and consumption variance.',
     'addon', NULL, NULL, NULL, true, true, 110),
    ('peak_direct', 'Peak Direct',
     'Booking engine and direct reservations taken on the property''s own terms.',
     'addon', NULL, NULL, NULL, false, true, 120),
    ('peak_revenue_assurance', 'Peak Revenue Assurance',
     'Payment mismatches, unpaid folios, abnormal voids and revenue leakage intelligence.',
     'addon', NULL, NULL, NULL, false, true, 130)
ON CONFLICT (code) DO NOTHING;

-- Inventory without outlets to consume from is not a product anyone wants.
UPDATE peak_products SET requires_product_code = 'peak_pos' WHERE code = 'peak_inventory';

-- -----------------------------------------------------------------------------
-- What each product grants.
--
-- auto_activate marks the entitlements whose arrival should switch a module on.
-- A capacity change raises a limit and activates nothing, and conflating the two
-- would have the reconciler enabling modules nobody asked for.
-- -----------------------------------------------------------------------------

INSERT INTO peak_product_entitlements (product_code, entitlement_code, entitlement_value, is_enabled, auto_activate) VALUES
    ('peak_core', 'module.billing',      '{}'::jsonb, true, true),
    ('peak_core', 'module.payments',     '{}'::jsonb, true, true),
    ('peak_core', 'module.fiscal',       '{}'::jsonb, true, true),
    ('peak_core', 'module.night_audit',  '{}'::jsonb, true, true),
    ('peak_core', 'module.reports',      '{}'::jsonb, true, true),
    ('peak_core', 'module.housekeeping', '{}'::jsonb, true, true),
    ('peak_core', 'module.tenant_admin', '{}'::jsonb, true, true),

    ('peak_pro', 'module.billing',      '{}'::jsonb, true, true),
    ('peak_pro', 'module.payments',     '{}'::jsonb, true, true),
    ('peak_pro', 'module.fiscal',       '{}'::jsonb, true, true),
    ('peak_pro', 'module.night_audit',  '{}'::jsonb, true, true),
    ('peak_pro', 'module.reports',      '{}'::jsonb, true, true),
    ('peak_pro', 'module.housekeeping', '{}'::jsonb, true, true),
    ('peak_pro', 'module.tenant_admin', '{}'::jsonb, true, true),
    ('peak_pro', 'module.maintenance',  '{}'::jsonb, true, true),
    ('peak_pro', 'module.analytics',    '{}'::jsonb, true, true),

    ('peak_group', 'module.billing',      '{}'::jsonb, true, true),
    ('peak_group', 'module.payments',     '{}'::jsonb, true, true),
    ('peak_group', 'module.fiscal',       '{}'::jsonb, true, true),
    ('peak_group', 'module.night_audit',  '{}'::jsonb, true, true),
    ('peak_group', 'module.reports',      '{}'::jsonb, true, true),
    ('peak_group', 'module.housekeeping', '{}'::jsonb, true, true),
    ('peak_group', 'module.tenant_admin', '{}'::jsonb, true, true),
    ('peak_group', 'module.maintenance',  '{}'::jsonb, true, true),
    ('peak_group', 'module.analytics',    '{}'::jsonb, true, true),

    ('peak_pos', 'module.pos', '{}'::jsonb, true, true),

    ('peak_inventory', 'module.inventory',   '{}'::jsonb, true, true),
    ('peak_inventory', 'module.procurement', '{}'::jsonb, true, true),

    ('peak_direct', 'module.booking_engine', '{}'::jsonb, true, true),

    ('peak_revenue_assurance', 'module.analytics', '{}'::jsonb, true, true)
ON CONFLICT (product_code, entitlement_code) DO NOTHING;

-- -----------------------------------------------------------------------------
-- Prices, in TZS. One row per product and term; the exclusion constraint on the
-- table makes an overlapping second row impossible.
-- -----------------------------------------------------------------------------

INSERT INTO peak_product_prices (product_code, term_months, currency, amount) VALUES
    ('peak_core', 1,  'TZS',    30000.00),
    ('peak_core', 3,  'TZS',    85500.00),
    ('peak_core', 6,  'TZS',   162000.00),
    ('peak_core', 12, 'TZS',   270000.00),

    ('peak_pro', 1,  'TZS',   120000.00),
    ('peak_pro', 3,  'TZS',   342000.00),
    ('peak_pro', 6,  'TZS',   648000.00),
    ('peak_pro', 12, 'TZS',  1080000.00),

    ('peak_pos', 1,  'TZS',    35000.00),
    ('peak_pos', 3,  'TZS',    99750.00),
    ('peak_pos', 6,  'TZS',   189000.00),
    ('peak_pos', 12, 'TZS',   315000.00),

    ('peak_inventory', 1,  'TZS',   25000.00),
    ('peak_inventory', 3,  'TZS',   71250.00),
    ('peak_inventory', 6,  'TZS',  135000.00),
    ('peak_inventory', 12, 'TZS',  225000.00),

    ('peak_direct', 1,  'TZS',    40000.00),
    ('peak_direct', 3,  'TZS',   114000.00),
    ('peak_direct', 6,  'TZS',   216000.00),
    ('peak_direct', 12, 'TZS',   360000.00),

    ('peak_revenue_assurance', 1,  'TZS',    50000.00),
    ('peak_revenue_assurance', 3,  'TZS',   142500.00),
    ('peak_revenue_assurance', 6,  'TZS',   270000.00),
    ('peak_revenue_assurance', 12, 'TZS',   450000.00);

-- -----------------------------------------------------------------------------
-- Permissions and routes
-- -----------------------------------------------------------------------------

INSERT INTO permission_catalog (
    code, namespace, access_scope, description, is_platform_permission, is_tenant_permission
) VALUES
    ('tenant.subscription.view', 'tenant', 'tenant',
     'View the product catalog, current subscription and purchase history', false, true),
    ('tenant.subscription.purchase', 'tenant', 'tenant',
     'Buy or renew a Peak subscription and add-ons', false, true)
ON CONFLICT (code) DO NOTHING;

-- Billing routes belong to tenant_admin, never to a module of their own. A
-- module can be switched off by the reconciler, and a tenant locked out of the
-- page where they pay could never end the restriction that locked them out.
INSERT INTO module_access_matrix (
    module_id, screen_key, screen_label, http_method, api_pattern,
    permission_code, route_scope, guard_mode, access_scope,
    is_tanzania_v1, is_enabled_by_default, notes
) VALUES
    ('tenant_admin', 'subscription.catalog', 'Peak Product Catalog', 'GET',
     '/api/tenants/:tenantId/billing/catalog', 'tenant.subscription.view',
     'tenant', 'staff_permission', 'tenant', true, true,
     'Sellable products with prices for each term'),
    ('tenant_admin', 'subscription.quote', 'Price a Selection', 'POST',
     '/api/tenants/:tenantId/billing/quotes', 'tenant.subscription.view',
     'tenant', 'staff_permission', 'tenant', true, true,
     'Prices a selection without committing to it'),
    ('tenant_admin', 'subscription.purchases', 'Subscription Purchases', 'GET',
     '/api/tenants/:tenantId/billing/purchases*', 'tenant.subscription.view',
     'tenant', 'staff_permission', 'tenant', true, true,
     'Purchase history and a single purchase'),
    ('tenant_admin', 'subscription.purchase.create', 'Buy a Subscription', 'POST',
     '/api/tenants/:tenantId/billing/purchases', 'tenant.subscription.purchase',
     'tenant', 'staff_permission', 'tenant', true, true,
     'Records an immutable order with price and entitlements frozen'),
    ('tenant_admin', 'subscription.purchase.pay', 'Pay for a Subscription', 'POST',
     '/api/tenants/:tenantId/billing/purchases/:purchaseId/payments',
     'tenant.subscription.purchase',
     'tenant', 'staff_permission', 'tenant', true, true,
     'Pushes a PIN prompt to the payer; never completes the purchase itself')
ON CONFLICT DO NOTHING;

-- The provider callback authenticates by signature, not by a bearer token, so it
-- carries no permission and is served on the public token guard like the payment
-- webhooks it sits beside.
INSERT INTO module_access_matrix (
    module_id, screen_key, screen_label, http_method, api_pattern,
    permission_code, route_scope, guard_mode, access_scope,
    is_tanzania_v1, is_enabled_by_default, notes
) VALUES
    -- route_scope must be 'public', not 'public_property'. Both satisfy the check
    -- constraint, but authorizePublicToken requires RouteScope.PUBLIC exactly and
    -- also refuses a route carrying tenant or property variables. The wrong one
    -- passes the migration and denies every callback at runtime.
    ('tenant_admin', 'subscription.webhook', 'Platform Billing Callback', 'POST',
     '/api/platform-billing/webhooks/:providerCode', NULL,
     'public', 'public_token', 'tenant', true, true,
     'Signature-verified provider confirmation for Peak''s own collections')
ON CONFLICT DO NOTHING;

DO $migration$
DECLARE
    product_count bigint;
    price_count bigint;
    unpriced text;
BEGIN
    SELECT count(*) INTO product_count FROM peak_products;
    SELECT count(*) INTO price_count FROM peak_product_prices;

    IF product_count < 7 THEN
        RAISE EXCEPTION 'expected the full product catalog, found % product(s)', product_count;
    END IF;

    -- A sellable product with no price is a button that cannot be pressed.
    SELECT string_agg(product.code, ', ') INTO unpriced
    FROM peak_products product
    WHERE product.is_sellable
      AND NOT EXISTS (
          SELECT 1 FROM peak_product_prices price WHERE price.product_code = product.code
      );

    IF unpriced IS NOT NULL THEN
        RAISE EXCEPTION 'sellable products without a price: %', unpriced;
    END IF;

    IF price_count < 24 THEN
        RAISE EXCEPTION 'expected a price for every sellable product and term, found %', price_count;
    END IF;
END;
$migration$;
