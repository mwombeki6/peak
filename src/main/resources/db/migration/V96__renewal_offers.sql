-- V96 — renewal offers, which are deliberately not purchases.
--
-- The obvious implementation of "remind the customer at T-14" is to create a
-- quoted peak_purchase and notify them about it. That would have been a product
-- bug, not merely an implementation detail.
--
-- peak_purchases carries a partial unique index allowing one open order per
-- tenant, so that two concurrent USSD pushes cannot fight over one customer's
-- handset. A renewal quote sitting in 'quoted' for the whole fortnight would
-- occupy that slot:
--
--   1 September   worker creates renewal quote        -> tenant's slot taken
--   2 September   owner tries to add POS to Property B -> refused
--   ...           for fourteen days
--
-- The reminder would have locked the tenant out of buying anything else. An
-- unattended background job must not create checkout objects that contend with
-- what a real customer is doing.
--
-- So an offer is a *notification with an expiry*, not a commitment. It holds no
-- price at all. Nothing is priced until the customer says yes, at which point a
-- real purchase is created through the ordinary path, at the ordinary price,
-- competing for the open-order slot exactly like any other purchase -- because
-- at that moment it is one.
--
-- Holding no price is also what stops accidental grandfathering. A stored 2026
-- amount, re-presented every year, would quietly renew a customer at a price
-- that no longer exists. Grandfathering is a commercial decision and should look
-- like one, not like a stale column.

CREATE TABLE peak_renewal_offers (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES tenants(id) DEFERRABLE,
    -- What is coming up for renewal, so the offer can reconstruct the selection.
    source_purchase_id uuid REFERENCES peak_purchases(id),
    -- When the cover being renewed runs out. The offer is about this date.
    cover_ends_at timestamptz NOT NULL,
    term_months integer NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'offered',
    notified_at timestamptz,
    -- Set when the customer accepts and a real purchase is created.
    accepted_purchase_id uuid REFERENCES peak_purchases(id),
    accepted_at timestamptz,
    declined_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_peak_renewal_offers_status CHECK (
        status IN ('offered', 'accepted', 'declined', 'expired', 'superseded')
    ),
    CONSTRAINT chk_peak_renewal_offers_term CHECK (term_months IN (1, 3, 6, 12)),
    CONSTRAINT chk_peak_renewal_offers_acceptance CHECK (
        (status <> 'accepted')
        OR (accepted_purchase_id IS NOT NULL AND accepted_at IS NOT NULL)
    )
);

-- One live offer per tenant per expiring cover. The worker runs every fifteen
-- minutes and must not accumulate a reminder each time; this makes repetition a
-- no-op at the storage layer rather than a matter of the worker remembering.
CREATE UNIQUE INDEX uq_peak_renewal_offers_open
    ON peak_renewal_offers (tenant_id, cover_ends_at)
    WHERE status = 'offered';

CREATE INDEX idx_peak_renewal_offers_tenant
    ON peak_renewal_offers (tenant_id, status, cover_ends_at DESC);

ALTER TABLE peak_renewal_offers ENABLE ROW LEVEL SECURITY;
ALTER TABLE peak_renewal_offers FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON peak_renewal_offers
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

GRANT SELECT, INSERT, UPDATE ON peak_renewal_offers
    TO pms_app, pms_platform, pms_worker;
GRANT SELECT ON peak_renewal_offers TO pms_readonly_support;

-- Append-and-amend only. An offer that was made is a fact about what the
-- customer was told, and deleting one would erase the record of a notification
-- they may have acted on.
REVOKE DELETE ON peak_renewal_offers
    FROM pms_app, pms_platform, pms_worker, pms_readonly_support;

-- The lifecycle worker creates offers on an unbound sweep, so it needs the same
-- cross-tenant reach V94 gave the reconcile and attempt sweeps, and for the same
-- reason: an unbound session sees nothing and the sweep would silently create
-- nothing at all while looking perfectly healthy.
GRANT SELECT, INSERT ON peak_renewal_offers TO pms_platform_billing_sweep_owner;

CREATE POLICY sweep_owner_manages_offers ON peak_renewal_offers
    FOR ALL TO pms_platform_billing_sweep_owner
    USING (true) WITH CHECK (true);

-- -----------------------------------------------------------------------------
-- Tenants whose cover runs out within the notice period and who have not already
-- been told. Returns the expiring purchase so the offer can name what it renews.
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION platform_billing_renewals_due(
    p_notice_days integer,
    p_limit integer
) RETURNS TABLE (
    tenant_id uuid,
    source_purchase_id uuid,
    cover_ends_at timestamptz,
    term_months integer
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
    SELECT DISTINCT ON (grant_row.tenant_id)
           grant_row.tenant_id,
           grant_row.source_purchase_id,
           grant_row.ends_at,
           coalesce(purchase.term_months, 1)
    FROM peak_product_grants grant_row
    LEFT JOIN peak_purchases purchase ON purchase.id = grant_row.source_purchase_id
    WHERE grant_row.revoked_at IS NULL
      AND grant_row.status = 'active'
      AND grant_row.ends_at IS NOT NULL
      -- Cover that runs out within the notice period, or that has already run out.
      --
      -- There is deliberately no lower bound. An earlier version required
      -- ends_at > now(), which meant a tenant whose cover had already lapsed was
      -- never offered a renewal -- exactly the suspended tenant, the one who most
      -- needs a route back. Restriction is only defensible if recovery is reachable,
      -- so the offer has to survive the lapse it is warning about.
      AND grant_row.ends_at <= now() + make_interval(days => greatest(p_notice_days, 0))
      -- Any offer at all for this expiring cover, whatever became of it. The unique
      -- index above is a concurrency guard -- it stops two live offers racing -- and
      -- this is the policy: one reminder per cover period. Excluding only 'offered'
      -- and 'accepted' would make declining mean "remind me again in fifteen
      -- minutes", which is the opposite of what the customer said.
      AND NOT EXISTS (
          SELECT 1
          FROM peak_renewal_offers offer
          WHERE offer.tenant_id = grant_row.tenant_id
            AND offer.cover_ends_at = grant_row.ends_at
      )
    ORDER BY grant_row.tenant_id, grant_row.ends_at DESC
    LIMIT greatest(p_limit, 0);
$$;

ALTER FUNCTION platform_billing_renewals_due(integer, integer)
    OWNER TO pms_platform_billing_sweep_owner;
REVOKE ALL ON FUNCTION platform_billing_renewals_due(integer, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_billing_renewals_due(integer, integer) TO pms_worker;

-- Routes. Under tenant_admin like the rest of billing, and permitted by the
-- 'tenant.subscription.%' allowance in both restricting states -- accepting a
-- renewal is precisely how a suspended tenant gets back to normal, so it must
-- remain reachable from inside suspension.
INSERT INTO module_access_matrix (
    module_id, screen_key, screen_label, http_method, api_pattern,
    permission_code, route_scope, guard_mode, access_scope,
    is_tanzania_v1, is_enabled_by_default, notes
) VALUES
    ('tenant_admin', 'subscription.renewals', 'Renewal Offers', 'GET',
     '/api/tenants/:tenantId/billing/renewal-offers', 'tenant.subscription.view',
     'tenant', 'staff_permission', 'tenant', true, true,
     'Reminders that cover is running out; these carry no price'),
    ('tenant_admin', 'subscription.renewal.accept', 'Accept a Renewal', 'POST',
     '/api/tenants/:tenantId/billing/renewal-offers/:offerId/accept',
     'tenant.subscription.purchase',
     'tenant', 'staff_permission', 'tenant', true, true,
     'Prices the renewal at today''s catalog and creates an ordinary purchase'),
    ('tenant_admin', 'subscription.renewal.decline', 'Decline a Renewal', 'POST',
     '/api/tenants/:tenantId/billing/renewal-offers/:offerId/decline',
     'tenant.subscription.purchase',
     'tenant', 'staff_permission', 'tenant', true, true,
     'Records that the customer does not intend to renew')
ON CONFLICT DO NOTHING;

DO $migration$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_proc proc
        JOIN pg_roles owner ON owner.oid = proc.proowner
        WHERE proc.proname = 'platform_billing_renewals_due'
          AND (owner.rolsuper OR owner.rolbypassrls)
    ) THEN
        RAISE EXCEPTION
            'platform_billing_renewals_due must not be owned by a superuser or a BYPASSRLS role';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM information_schema.role_table_grants
        WHERE grantee IN ('pms_app', 'pms_worker', 'pms_platform')
          AND table_name = 'peak_renewal_offers'
          AND privilege_type = 'DELETE'
    ) THEN
        RAISE EXCEPTION
            'peak_renewal_offers must be append-and-amend only; an offer that was made is a '
            'record of what the customer was told';
    END IF;
END;
$migration$;
