-- =============================================================================
-- Peak's own subscription revenue
--
-- Tenants buy Peak from inside Peak: a base tier, plus add-ons priced per
-- property, on a 1/3/6/12 month term, paid by mobile money push and activated
-- automatically once the provider confirms. This is Peak's money, collected into
-- Peak's own merchant account, and it is unrelated to how a property collects
-- from its guests.
--
-- The organising rule is that a commercial product is not a technical module.
-- Selling "Peak POS" must not mean putting a price on the `pos` module, because
-- a product is a bundle whose contents change over time while an already-sold
-- purchase must not. Three planes, one direction of travel:
--
--   peak_products  ──►  entitlement codes  ──►  tenant_modules.is_enabled
--   (sold)              (module.pos, limit.rooms)  (enforced by can_access_module)
--
-- Price therefore lives only on (product, term). Base tiers map one-to-one onto
-- a plans row so tenant_subscriptions.plan_id keeps working unchanged; add-ons
-- carry no plan and grant entitlements directly. That split is forced by the
-- existing uq_tenant_service_granting_subscription index, which permits a tenant
-- exactly one service-granting subscription — an add-on cannot be a second one.
--
-- Nothing here writes tenant_subscriptions, tenant_modules or property_modules.
-- Those belong to tenantmanagement and property; billing records what was bought
-- and what was paid, and a reconciler projects the consequences.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Catalog. Global, not tenant scoped: the same products are sold to everyone.
-- -----------------------------------------------------------------------------

CREATE TABLE peak_products (
    code text PRIMARY KEY,
    name text NOT NULL,
    description text,
    kind varchar(20) NOT NULL,
    plan_code text REFERENCES plans(code),
    tier_rank integer,
    requires_product_code text REFERENCES peak_products(code),
    is_per_property boolean NOT NULL DEFAULT false,
    is_sellable boolean NOT NULL DEFAULT true,
    display_order integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_peak_products_kind CHECK (kind IN ('base', 'addon')),
    -- A base tier is a plan; an add-on must not be, or it would need a second
    -- service-granting subscription row, which the unique index forbids.
    CONSTRAINT chk_peak_products_plan_binding CHECK (
        (kind = 'base' AND plan_code IS NOT NULL AND tier_rank IS NOT NULL)
        OR (kind = 'addon' AND plan_code IS NULL AND tier_rank IS NULL)
    ),
    -- Tiers are ordered so "is this an upgrade?" is a data question.
    CONSTRAINT uq_peak_products_tier_rank UNIQUE (tier_rank),
    CONSTRAINT chk_peak_products_no_self_requirement CHECK (
        requires_product_code IS NULL OR requires_product_code <> code
    )
);

CREATE TABLE peak_product_entitlements (
    product_code text NOT NULL REFERENCES peak_products(code) ON DELETE CASCADE,
    entitlement_code text NOT NULL,
    entitlement_value jsonb NOT NULL DEFAULT '{}'::jsonb,
    is_enabled boolean NOT NULL DEFAULT true,
    -- Distinguishes "buying Peak POS should switch the pos module on" from
    -- "buying Peak Pro raises limit.rooms", which activates nothing. Without it
    -- the reconciler cannot tell an activation from a capacity change.
    auto_activate boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (product_code, entitlement_code),
    CONSTRAINT chk_peak_product_entitlements_code
        CHECK (entitlement_code ~ '^[a-z][a-z0-9_.-]{1,99}$')
);

CREATE TABLE peak_product_prices (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    product_code text NOT NULL REFERENCES peak_products(code) ON DELETE CASCADE,
    term_months integer NOT NULL,
    currency char(3) NOT NULL DEFAULT 'TZS',
    -- The actual price of the longer term, never a discount percentage. Storing
    -- the rate invites rounding drift and an argument about what it applies to.
    amount numeric(15,2) NOT NULL,
    effective_from timestamptz NOT NULL DEFAULT now(),
    effective_to timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_peak_product_prices_term CHECK (term_months IN (1, 3, 6, 12)),
    CONSTRAINT chk_peak_product_prices_amount CHECK (amount >= 0),
    CONSTRAINT chk_peak_product_prices_window CHECK (
        effective_to IS NULL OR effective_to > effective_from
    ),
    -- Two overlapping prices for the same product and term would make a quote
    -- ambiguous, and the ambiguity would surface as a customer dispute rather
    -- than an error. Make it unrepresentable.
    CONSTRAINT excl_peak_product_prices_overlap EXCLUDE USING gist (
        product_code WITH =,
        term_months WITH =,
        currency WITH =,
        tstzrange(effective_from, effective_to) WITH &&
    )
);

-- -----------------------------------------------------------------------------
-- Purchases. Tenant scoped and immutable once terminal.
-- -----------------------------------------------------------------------------

CREATE TABLE peak_purchases (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES tenants(id) DEFERRABLE,
    status varchar(20) NOT NULL DEFAULT 'quoted',
    currency char(3) NOT NULL DEFAULT 'TZS',
    term_months integer NOT NULL,
    total_amount numeric(15,2) NOT NULL,
    period_starts_at timestamptz NOT NULL,
    period_ends_at timestamptz NOT NULL,
    quote_expires_at timestamptz NOT NULL,
    renewal_of_purchase_id uuid REFERENCES peak_purchases(id),
    created_by_user_id uuid,
    version bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_peak_purchases_status CHECK (
        status IN ('quoted', 'awaiting_payment', 'paid', 'failed', 'expired', 'cancelled')
    ),
    CONSTRAINT chk_peak_purchases_term CHECK (term_months IN (1, 3, 6, 12)),
    CONSTRAINT chk_peak_purchases_total CHECK (total_amount >= 0),
    CONSTRAINT chk_peak_purchases_period CHECK (period_ends_at > period_starts_at)
);

-- One open order per tenant. Two concurrent USSD pushes for two different carts
-- is the fastest route to a customer paying for something they did not choose.
CREATE UNIQUE INDEX uq_peak_purchases_open_per_tenant
    ON peak_purchases (tenant_id)
    WHERE status IN ('quoted', 'awaiting_payment');

CREATE INDEX idx_peak_purchases_tenant_status ON peak_purchases (tenant_id, status);

CREATE TABLE peak_purchase_lines (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    purchase_id uuid NOT NULL REFERENCES peak_purchases(id) ON DELETE CASCADE,
    tenant_id uuid NOT NULL REFERENCES tenants(id) DEFERRABLE,
    product_code text NOT NULL REFERENCES peak_products(code),
    term_months integer NOT NULL,
    -- Property count for a per-property add-on; 1 for tenant-scoped products.
    quantity integer NOT NULL DEFAULT 1,
    -- Which properties the tenant chose to cover. Empty for tenant-scoped
    -- products. The reconciler grants per property from this, so it is the
    -- record of what was actually bought, not a derived count.
    covered_property_ids jsonb NOT NULL DEFAULT '[]'::jsonb,
    unit_amount numeric(15,2) NOT NULL,
    amount numeric(15,2) NOT NULL,
    price_source_id uuid REFERENCES peak_product_prices(id),
    -- Frozen at quote time. If Peak later adds a module to Peak Pro, a purchase
    -- already in flight must grant what was sold, not what the product now means.
    entitlement_snapshot jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_peak_purchase_lines_product UNIQUE (purchase_id, product_code),
    CONSTRAINT chk_peak_purchase_lines_quantity CHECK (quantity >= 1),
    CONSTRAINT chk_peak_purchase_lines_amounts CHECK (unit_amount >= 0 AND amount >= 0)
);

CREATE INDEX idx_peak_purchase_lines_purchase ON peak_purchase_lines (purchase_id);

CREATE TABLE peak_payment_attempts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    purchase_id uuid NOT NULL REFERENCES peak_purchases(id) ON DELETE CASCADE,
    tenant_id uuid NOT NULL REFERENCES tenants(id) DEFERRABLE,
    attempt_no integer NOT NULL,
    provider varchar(30) NOT NULL,
    provider_channel varchar(30),
    payer_msisdn text NOT NULL,
    amount numeric(15,2) NOT NULL,
    currency char(3) NOT NULL DEFAULT 'TZS',
    internal_reference text NOT NULL,
    provider_reference text,
    redirect_url text,
    status varchar(20) NOT NULL DEFAULT 'created',
    initiated_by_user_id uuid,
    expires_at timestamptz,
    failure_code text,
    failure_detail text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_peak_payment_attempts_reference UNIQUE (internal_reference),
    CONSTRAINT uq_peak_payment_attempts_sequence UNIQUE (purchase_id, attempt_no),
    CONSTRAINT chk_peak_payment_attempts_status CHECK (
        status IN ('created', 'initiated', 'pending', 'confirmed', 'failed', 'expired')
    ),
    CONSTRAINT chk_peak_payment_attempts_amount CHECK (amount > 0)
);

-- USSD fails often, so a purchase accumulates attempts; but only one may be in
-- flight, or the owner gets two PIN prompts for the same order.
CREATE UNIQUE INDEX uq_peak_payment_attempts_open
    ON peak_payment_attempts (purchase_id)
    WHERE status IN ('created', 'initiated', 'pending');

CREATE INDEX idx_peak_payment_attempts_tenant ON peak_payment_attempts (tenant_id, status);

CREATE TABLE peak_provider_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    provider varchar(30) NOT NULL,
    provider_event_id text NOT NULL,
    tenant_id uuid REFERENCES tenants(id) DEFERRABLE,
    attempt_id uuid REFERENCES peak_payment_attempts(id),
    payload jsonb NOT NULL,
    signature_method text,
    -- Recorded rather than only logged: a run of rejected callbacks is evidence
    -- of either a rotated key or an attack, and both need to be queryable.
    signature_verified boolean NOT NULL DEFAULT false,
    outcome text,
    received_at timestamptz NOT NULL DEFAULT now(),
    processed_at timestamptz,
    -- Replay defence at the storage layer, independent of IdempotencyPort, so a
    -- duplicate cannot be applied even if the application layer is bypassed.
    CONSTRAINT uq_peak_provider_events_identity UNIQUE (provider, provider_event_id)
);

CREATE INDEX idx_peak_provider_events_attempt ON peak_provider_events (attempt_id);

-- -----------------------------------------------------------------------------
-- Grants and projection. What a tenant currently owns, and what the reconciler
-- has already done about it.
-- -----------------------------------------------------------------------------

CREATE TABLE peak_product_grants (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES tenants(id) DEFERRABLE,
    -- NULL for a tenant-scoped product; one row per covered property for a
    -- per-property add-on, because property_modules is per property and the
    -- reconciler needs to know which ones were paid for.
    property_id uuid,
    product_code text NOT NULL REFERENCES peak_products(code),
    source varchar(20) NOT NULL DEFAULT 'purchase',
    source_purchase_id uuid REFERENCES peak_purchases(id),
    status varchar(20) NOT NULL DEFAULT 'active',
    starts_at timestamptz NOT NULL DEFAULT now(),
    ends_at timestamptz,
    granted_entitlements jsonb NOT NULL,
    revoked_at timestamptz,
    revoked_reason text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_peak_product_grants_source CHECK (
        source IN ('purchase', 'trial', 'complimentary', 'migration')
    ),
    CONSTRAINT chk_peak_product_grants_status CHECK (
        status IN ('active', 'expired', 'revoked')
    ),
    CONSTRAINT chk_peak_product_grants_window CHECK (
        ends_at IS NULL OR ends_at > starts_at
    )
);

CREATE INDEX idx_peak_product_grants_live
    ON peak_product_grants (tenant_id, status, ends_at);
CREATE INDEX idx_peak_product_grants_property
    ON peak_product_grants (tenant_id, property_id)
    WHERE property_id IS NOT NULL;

CREATE TABLE peak_module_activations (
    tenant_id uuid NOT NULL REFERENCES tenants(id) DEFERRABLE,
    module_id varchar(50) NOT NULL,
    first_activated_at timestamptz NOT NULL DEFAULT now(),
    activated_by_purchase_id uuid REFERENCES peak_purchases(id),
    last_deactivated_at timestamptz,
    last_deactivation_reason text,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, module_id)
);

CREATE TABLE peak_reconciliation_state (
    tenant_id uuid PRIMARY KEY REFERENCES tenants(id) DEFERRABLE,
    desired_hash text,
    applied_hash text,
    last_run_at timestamptz,
    next_run_at timestamptz NOT NULL DEFAULT now(),
    consecutive_failures integer NOT NULL DEFAULT 0,
    last_error text,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_peak_reconciliation_failures CHECK (consecutive_failures >= 0)
);

-- Claimed by the reconciler loop; the partial index is what makes convergence
-- incremental rather than a full scan of every tenant every minute.
CREATE INDEX idx_peak_reconciliation_pending
    ON peak_reconciliation_state (next_run_at)
    WHERE desired_hash IS DISTINCT FROM applied_hash;

CREATE TABLE peak_billing_lifecycle_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES tenants(id) DEFERRABLE,
    from_state varchar(20),
    to_state varchar(20) NOT NULL,
    reason text,
    actor text NOT NULL DEFAULT 'system',
    purchase_id uuid REFERENCES peak_purchases(id),
    occurred_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_peak_billing_lifecycle_tenant
    ON peak_billing_lifecycle_events (tenant_id, occurred_at DESC);

CREATE TABLE peak_receipts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES tenants(id) DEFERRABLE,
    purchase_id uuid NOT NULL REFERENCES peak_purchases(id),
    receipt_number text NOT NULL,
    issued_at timestamptz NOT NULL DEFAULT now(),
    total_amount numeric(15,2) NOT NULL,
    currency char(3) NOT NULL DEFAULT 'TZS',
    tenant_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb,
    object_key text,
    CONSTRAINT uq_peak_receipts_purchase UNIQUE (purchase_id),
    CONSTRAINT uq_peak_receipts_number UNIQUE (receipt_number)
);

-- -----------------------------------------------------------------------------
-- Row level security. Catalog is world-readable within the platform; everything
-- carrying a tenant_id is isolated the same way the rest of the schema is.
-- -----------------------------------------------------------------------------

ALTER TABLE peak_purchases ENABLE ROW LEVEL SECURITY;
ALTER TABLE peak_purchases FORCE ROW LEVEL SECURITY;
ALTER TABLE peak_purchase_lines ENABLE ROW LEVEL SECURITY;
ALTER TABLE peak_purchase_lines FORCE ROW LEVEL SECURITY;
ALTER TABLE peak_payment_attempts ENABLE ROW LEVEL SECURITY;
ALTER TABLE peak_payment_attempts FORCE ROW LEVEL SECURITY;
ALTER TABLE peak_product_grants ENABLE ROW LEVEL SECURITY;
ALTER TABLE peak_product_grants FORCE ROW LEVEL SECURITY;
ALTER TABLE peak_module_activations ENABLE ROW LEVEL SECURITY;
ALTER TABLE peak_module_activations FORCE ROW LEVEL SECURITY;
ALTER TABLE peak_reconciliation_state ENABLE ROW LEVEL SECURITY;
ALTER TABLE peak_reconciliation_state FORCE ROW LEVEL SECURITY;
ALTER TABLE peak_billing_lifecycle_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE peak_billing_lifecycle_events FORCE ROW LEVEL SECURITY;
ALTER TABLE peak_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE peak_receipts FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON peak_purchases
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());
CREATE POLICY tenant_isolation ON peak_purchase_lines
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());
CREATE POLICY tenant_isolation ON peak_payment_attempts
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());
CREATE POLICY tenant_isolation ON peak_product_grants
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());
CREATE POLICY tenant_isolation ON peak_module_activations
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());
CREATE POLICY tenant_isolation ON peak_reconciliation_state
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());
CREATE POLICY tenant_isolation ON peak_billing_lifecycle_events
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());
CREATE POLICY tenant_isolation ON peak_receipts
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- peak_provider_events is written before a tenant is known: a callback arrives
-- carrying only a provider reference, and resolving it to a tenant is the work
-- the record exists to make safe. It is therefore not tenant-isolated, and is
-- readable only by the runtimes that process callbacks.
ALTER TABLE peak_provider_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE peak_provider_events FORCE ROW LEVEL SECURITY;
CREATE POLICY provider_events_runtime ON peak_provider_events
    USING (true)
    WITH CHECK (true);

-- -----------------------------------------------------------------------------
-- Privileges
-- -----------------------------------------------------------------------------

GRANT SELECT ON peak_products, peak_product_entitlements, peak_product_prices
    TO pms_app, pms_platform, pms_worker, pms_readonly_support;
GRANT INSERT, UPDATE ON peak_products, peak_product_entitlements, peak_product_prices
    TO pms_platform;

-- The API runtime quotes, creates a purchase and records an attempt. It never
-- marks anything paid: that follows a verified provider event, in the worker.
GRANT SELECT, INSERT, UPDATE ON
    peak_purchases, peak_purchase_lines, peak_payment_attempts, peak_provider_events
    TO pms_app;
GRANT SELECT ON
    peak_product_grants, peak_module_activations, peak_receipts,
    peak_billing_lifecycle_events
    TO pms_app;

GRANT SELECT, INSERT, UPDATE ON
    peak_purchases, peak_purchase_lines, peak_payment_attempts, peak_provider_events,
    peak_product_grants, peak_module_activations, peak_reconciliation_state,
    peak_billing_lifecycle_events, peak_receipts
    TO pms_worker;

GRANT SELECT ON
    peak_purchases, peak_purchase_lines, peak_payment_attempts, peak_provider_events,
    peak_product_grants, peak_module_activations, peak_reconciliation_state,
    peak_billing_lifecycle_events, peak_receipts
    TO pms_platform, pms_readonly_support;

-- Nothing in billing is deleted. A purchase is superseded by status and a grant
-- by revocation, because the history is the evidence of what was sold.
REVOKE DELETE ON
    peak_products, peak_product_entitlements, peak_product_prices,
    peak_purchases, peak_purchase_lines, peak_payment_attempts, peak_provider_events,
    peak_product_grants, peak_module_activations, peak_reconciliation_state,
    peak_billing_lifecycle_events, peak_receipts
    FROM pms_app, pms_platform, pms_worker, pms_readonly_support;

-- Append-only: a received callback and a lifecycle transition are facts about
-- the past and must not be editable after the fact.
REVOKE UPDATE ON peak_billing_lifecycle_events, peak_receipts
    FROM pms_app, pms_platform, pms_worker;

DO $migration$
DECLARE
    missing text;
BEGIN
    SELECT string_agg(t, ', ') INTO missing
    FROM unnest(ARRAY[
        'peak_products', 'peak_product_entitlements', 'peak_product_prices',
        'peak_purchases', 'peak_purchase_lines', 'peak_payment_attempts',
        'peak_provider_events', 'peak_product_grants', 'peak_module_activations',
        'peak_reconciliation_state', 'peak_billing_lifecycle_events', 'peak_receipts'
    ]) AS t
    WHERE NOT EXISTS (
        SELECT 1 FROM pg_catalog.pg_class c
        JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'public' AND c.relname = t AND c.relkind = 'r'
    );

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'platform billing tables missing: %', missing;
    END IF;
END;
$migration$;
