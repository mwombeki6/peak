-- =============================================================================
-- Validate constraints that were added NOT VALID
--
-- Thirty constraints across the finance, fiscal, POS, reporting and privileged
-- access schemas were added with NOT VALID, and nothing ever validated them.
-- PostgreSQL enforces such a constraint on new and updated rows but never
-- checks the rows that already existed, and `pg_constraint.convalidated`
-- remains false permanently.
--
-- The practical effect is a guarantee that reads as enforced and is only
-- partly so. Several of these are security or money relevant: that every
-- privileged access grant cites a support ticket, that production payment and
-- fiscal providers carry certification metadata, that payment amounts are
-- positive, that webhook payloads are hashed.
--
-- NOT VALID is the correct tool when adding a constraint to a large live table,
-- because it avoids a long exclusive lock. It is meant to be followed by
-- VALIDATE CONSTRAINT once the backlog is cleaned. That second step never
-- happened here. This migration performs it.
--
-- VALIDATE CONSTRAINT takes only SHARE UPDATE EXCLUSIVE, so it does not block
-- reads or writes. If any row violates a constraint the migration fails loudly,
-- which is the desired outcome: it means the guarantee was already false and
-- the data needs attention before the claim can be made.
-- =============================================================================


-- folio_payments
ALTER TABLE public.folio_payments VALIDATE CONSTRAINT chk_phase3_folio_payments_method_cash_mobile;
ALTER TABLE public.folio_payments VALIDATE CONSTRAINT chk_phase3_folio_payments_amount_positive;
ALTER TABLE public.folio_payments VALIDATE CONSTRAINT fk_phase3_folio_payments_tenant_property;
ALTER TABLE public.folio_payments VALIDATE CONSTRAINT fk_folio_payments_transaction;
ALTER TABLE public.folio_payments VALIDATE CONSTRAINT fk_folio_payments_cash_session;

-- payment_transactions
ALTER TABLE public.payment_transactions VALIDATE CONSTRAINT fk_payment_transactions_tenant_folio;
ALTER TABLE public.payment_transactions VALIDATE CONSTRAINT fk_payment_transactions_initiated_by;
ALTER TABLE public.payment_transactions VALIDATE CONSTRAINT fk_payment_transactions_idempotency;
ALTER TABLE public.payment_transactions VALIDATE CONSTRAINT fk_payment_transactions_reversal_of;
ALTER TABLE public.payment_transactions VALIDATE CONSTRAINT fk_payment_transactions_tenant_pos_order;
ALTER TABLE public.payment_transactions VALIDATE CONSTRAINT chk_payment_transactions_single_sales_target;
ALTER TABLE public.payment_transactions VALIDATE CONSTRAINT fk_payment_transactions_refund_of;

-- fiscal_receipts
ALTER TABLE public.fiscal_receipts VALIDATE CONSTRAINT fk_fiscal_receipts_tenant_property;
ALTER TABLE public.fiscal_receipts VALIDATE CONSTRAINT fk_fiscal_receipts_idempotency;

-- properties
ALTER TABLE public.properties VALIDATE CONSTRAINT chk_properties_business_date_offset;

-- night_audit_runs
ALTER TABLE public.night_audit_runs VALIDATE CONSTRAINT chk_night_audit_runs_attempt_no;

-- pos_sessions
ALTER TABLE public.pos_sessions VALIDATE CONSTRAINT fk_pos_sessions_closed_by;
ALTER TABLE public.pos_sessions VALIDATE CONSTRAINT fk_pos_sessions_variance_approved_by;

-- pos_orders
ALTER TABLE public.pos_orders VALIDATE CONSTRAINT fk_pos_orders_tenant_payment_transaction;

-- payment_webhook_events
ALTER TABLE public.payment_webhook_events VALIDATE CONSTRAINT chk_payment_webhook_payload_hash;

-- invoices
ALTER TABLE public.invoices VALIDATE CONSTRAINT fk_invoices_voided_by;

-- credit_notes
ALTER TABLE public.credit_notes VALIDATE CONSTRAINT fk_credit_notes_property;
ALTER TABLE public.credit_notes VALIDATE CONSTRAINT fk_credit_notes_idempotency;

-- payment_provider_accounts
ALTER TABLE public.payment_provider_accounts VALIDATE CONSTRAINT chk_payment_provider_accounts_production_certification;

-- fiscal_provider_configs
ALTER TABLE public.fiscal_provider_configs VALIDATE CONSTRAINT chk_fiscal_provider_production_certification;

-- report_runs
ALTER TABLE public.report_runs VALIDATE CONSTRAINT fk_report_runs_close_snapshot;

-- report_deliveries
ALTER TABLE public.report_deliveries VALIDATE CONSTRAINT chk_report_delivery_link_expiry;
ALTER TABLE public.report_deliveries VALIDATE CONSTRAINT fk_report_delivery_catalog;

-- platform_break_glass_access
ALTER TABLE public.platform_break_glass_access VALIDATE CONSTRAINT chk_platform_break_glass_ticket_required;
ALTER TABLE public.platform_break_glass_access VALIDATE CONSTRAINT chk_platform_break_glass_assurance;
