-- V99 — receipts for what Peak sold, and the views an operator needs.
--
-- Two things that have been missing since V89 created their tables.

-- -----------------------------------------------------------------------------
-- Receipt numbering.
--
-- Deliberately NOT allocate_document_number(). That allocator is tenant-scoped
-- and refuses to run outside a tenant PMS context, because it numbers a
-- *property's* documents -- its guest invoices and folio receipts. A Peak receipt
-- is Peak's document, issued by Peak to a tenant, and numbering it from the
-- tenant's own sequence would interleave Peak's revenue with the hotel's.
--
-- These are also not fiscal receipts. A guest-facing fiscal receipt is the
-- fiscal module's business and answers to TRA rules including gapless
-- numbering; this is a commercial receipt for a SaaS subscription. A plain
-- sequence is therefore fine, and its gaps -- sequences do not roll back -- are
-- not a problem here. The UNIQUE constraint on receipt_number is what actually
-- matters.
-- -----------------------------------------------------------------------------
CREATE SEQUENCE peak_receipt_number_seq START WITH 1 INCREMENT BY 1;

GRANT USAGE, SELECT ON SEQUENCE peak_receipt_number_seq TO pms_worker, pms_platform;

CREATE OR REPLACE FUNCTION allocate_peak_receipt_number()
RETURNS text
LANGUAGE sql
VOLATILE
AS $$
    SELECT 'PEAK-' || to_char(now(), 'YYYY') || '-' ||
           lpad(nextval('peak_receipt_number_seq')::text, 6, '0');
$$;

REVOKE ALL ON FUNCTION allocate_peak_receipt_number() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION allocate_peak_receipt_number() TO pms_worker, pms_platform;

-- -----------------------------------------------------------------------------
-- Commercial standing, stated plainly.
--
-- During SUSPENDED, tenant_subscriptions.status reads 'past_due' and the row
-- stays service-granting on purpose -- that is what keeps the restriction
-- allowances reachable so a suspended hotel can still check a guest out. But a
-- support engineer reading that column alone will conclude the subscription is
-- broadly fine, or worse, "fix" it.
--
-- So the operator-facing model states the three facts separately rather than
-- collapsing them into one ambiguous status.
-- -----------------------------------------------------------------------------
CREATE OR REPLACE VIEW peak_tenant_commercial_standing AS
SELECT tenant.id AS tenant_id,
       tenant.name AS tenant_name,
       control.lifecycle_status AS commercial_standing,
       subscription.status AS subscription_row_status,
       -- Spelled out so nobody has to infer it from the two columns above.
       CASE
           WHEN control.lifecycle_status IN ('restricted', 'suspended')
               THEN 'retained deliberately so safety-critical access still resolves'
           ELSE 'normal'
       END AS service_relationship,
       CASE
           WHEN control.lifecycle_status = 'suspended'
               THEN 'read-only plus checkout, payment collection, data export and subscription purchase'
           WHEN control.lifecycle_status = 'restricted'
               THEN 'operations continue; growth and administration denied'
           ELSE 'unrestricted'
       END AS operational_policy,
       cover.paid_through,
       CASE
           WHEN cover.paid_through IS NULL THEN 'never purchased'
           WHEN cover.paid_through > now() THEN 'current'
           ELSE 'overdue since ' || to_char(cover.paid_through, 'YYYY-MM-DD')
       END AS payment_status,
       offer.status AS renewal_offer_status,
       outstanding.amount AS outstanding_amount,
       outstanding.currency AS outstanding_currency
FROM tenants tenant
JOIN tenant_control_states control ON control.tenant_id = tenant.id
LEFT JOIN tenant_subscriptions subscription
  ON subscription.tenant_id = tenant.id
 AND subscription.status IN ('trialing', 'active', 'past_due', 'paused')
LEFT JOIN LATERAL (
    SELECT max(grant_row.ends_at) AS paid_through
    FROM peak_product_grants grant_row
    WHERE grant_row.tenant_id = tenant.id
      AND grant_row.revoked_at IS NULL
      AND grant_row.status = 'active'
) cover ON true
LEFT JOIN LATERAL (
    SELECT renewal.status
    FROM peak_renewal_offers renewal
    WHERE renewal.tenant_id = tenant.id
    ORDER BY renewal.cover_ends_at DESC
    LIMIT 1
) offer ON true
LEFT JOIN LATERAL (
    SELECT sum(purchase.total_amount) AS amount, min(purchase.currency) AS currency
    FROM peak_purchases purchase
    WHERE purchase.tenant_id = tenant.id
      AND purchase.status IN ('quoted', 'awaiting_payment')
) outstanding ON true
WHERE tenant.deleted_at IS NULL;

GRANT SELECT ON peak_tenant_commercial_standing TO pms_platform, pms_readonly_support;

-- -----------------------------------------------------------------------------
-- Platform operator routes.
-- -----------------------------------------------------------------------------
INSERT INTO permission_catalog (code, namespace, access_scope, description,
                                is_platform_permission, is_tenant_permission)
VALUES
    ('platform.billing.view', 'platform', 'platform',
     'View Peak subscription revenue, purchases and stuck payments', true, false)
ON CONFLICT (code) DO UPDATE SET
    description = EXCLUDED.description,
    is_platform_permission = EXCLUDED.is_platform_permission,
    is_tenant_permission = EXCLUDED.is_tenant_permission,
    updated_at = now();

INSERT INTO module_access_matrix (
    module_id, screen_key, screen_label, http_method, api_pattern,
    permission_code, route_scope, guard_mode, access_scope,
    is_tanzania_v1, is_enabled_by_default, notes
) VALUES
    ('platform_admin', 'platform.billing.standing', 'Tenant Commercial Standing', 'GET',
     '/api/platform/billing/standing', 'platform.billing.view',
     'platform', 'platform_permission', 'platform', true, true,
     'Paid-through, commercial standing and service relationship stated separately, so '
     'nobody reads past_due during suspension as a broken subscription'),
    ('platform_admin', 'platform.billing.reconciliation', 'Payments Needing Reconciliation', 'GET',
     '/api/platform/billing/reconciliation', 'platform.billing.view',
     'platform', 'platform_permission', 'platform', true, true,
     'Payments whose outcome Peak could not determine'),
    ('platform_admin', 'platform.billing.receipts', 'Subscription Receipts', 'GET',
     '/api/platform/billing/receipts', 'platform.billing.view',
     'platform', 'platform_permission', 'platform', true, true,
     'Receipts issued for settled Peak purchases')
ON CONFLICT DO NOTHING;

DO $migration$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_proc WHERE proname = 'allocate_peak_receipt_number'
    ) THEN
        RAISE EXCEPTION 'V99 did not create allocate_peak_receipt_number';
    END IF;

    -- The receipt number allocator must never be reachable by a tenant runtime:
    -- a receipt is Peak's document and its numbering is Peak's business.
    IF EXISTS (
        SELECT 1
        FROM information_schema.role_routine_grants
        WHERE routine_name = 'allocate_peak_receipt_number'
          AND grantee = 'pms_app'
    ) THEN
        RAISE EXCEPTION 'pms_app must not be able to allocate Peak receipt numbers';
    END IF;
END;
$migration$;
