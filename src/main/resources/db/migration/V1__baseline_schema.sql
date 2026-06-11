-- ================================================================================
-- Peak PMS — Master Schema
-- PostgreSQL 16+
--
-- Single source of truth. Run this on a fresh database to get the complete
-- enterprise schema including all RLS policies, indexes, triggers, and FK
-- constraints.
--
-- Usage:
--   psql -U pms -d pms_dev -f schema.sql
--
-- Sections:
--   1.  Preamble
--   2.  Functions
--   3–20. Tables by domain
--   21. Primary Key & Unique Constraints
--   22. Foreign Key Constraints
--   23. Indexes
--   24. Row Level Security
--   25. Triggers
-- ================================================================================

-- ================================================================================
-- 1. PREAMBLE
-- ================================================================================

SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;

CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS btree_gist;

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pms_owner') THEN
    CREATE ROLE pms_owner NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pms_migrator') THEN
    CREATE ROLE pms_migrator NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE INHERIT NOBYPASSRLS;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pms_app') THEN
    CREATE ROLE pms_app NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE INHERIT NOBYPASSRLS;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pms_platform') THEN
    CREATE ROLE pms_platform NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE INHERIT NOBYPASSRLS;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pms_worker') THEN
    CREATE ROLE pms_worker NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE INHERIT NOBYPASSRLS;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pms_readonly_support') THEN
    CREATE ROLE pms_readonly_support NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE INHERIT NOBYPASSRLS;
  END IF;
END;
$$;


-- ================================================================================
-- 2. FUNCTIONS
-- ================================================================================

CREATE FUNCTION current_tenant_id() RETURNS uuid
    LANGUAGE sql STABLE
    AS $$
  SELECT nullif(current_setting('app.current_tenant_id', true), '')::uuid;
$$;

CREATE FUNCTION current_tenant_user_id() RETURNS uuid
    LANGUAGE sql STABLE
    AS $$
  SELECT nullif(current_setting('app.current_tenant_user_id', true), '')::uuid;
$$;

CREATE FUNCTION set_updated_at() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
  NEW.updated_at = now();
  RETURN NEW;
END;
$$;

CREATE FUNCTION normalize_contact_channel_address(
    p_channel_type text,
    p_address text
) RETURNS text
    LANGUAGE plpgsql IMMUTABLE
    AS $$
DECLARE
  v_address text;
BEGIN
  v_address := NULLIF(btrim(p_address), '');
  IF v_address IS NULL THEN
    RETURN NULL;
  END IF;

  IF p_channel_type = 'email' THEN
    RETURN lower(v_address);
  END IF;

  IF p_channel_type IN ('sms', 'whatsapp', 'voice_phone') THEN
    v_address := regexp_replace(v_address, '[^0-9+]', '', 'g');
    IF left(v_address, 1) <> '+' THEN
      v_address := '+' || v_address;
    END IF;
    RETURN v_address;
  END IF;

  RETURN v_address;
END;
$$;

CREATE FUNCTION mask_contact_channel_address(
    p_channel_type text,
    p_address text
) RETURNS text
    LANGUAGE plpgsql IMMUTABLE
    AS $$
DECLARE
  v_address text;
  v_local text;
  v_domain text;
BEGIN
  v_address := normalize_contact_channel_address(p_channel_type, p_address);
  IF v_address IS NULL THEN
    RETURN NULL;
  END IF;

  IF p_channel_type = 'email' THEN
    v_local := split_part(v_address, '@', 1);
    v_domain := split_part(v_address, '@', 2);
    IF v_domain = '' THEN
      RETURN '***';
    END IF;
    RETURN left(v_local, 1) || '***@' || v_domain;
  END IF;

  IF p_channel_type IN ('sms', 'whatsapp', 'voice_phone') THEN
    RETURN '***' || right(v_address, 4);
  END IF;

  RETURN '***';
END;
$$;

CREATE FUNCTION contact_channel_has_active_consent(
    p_tenant_id uuid,
    p_contact_id uuid,
    p_contact_channel_id uuid,
    p_purpose text
) RETURNS boolean
    LANGUAGE sql STABLE
    AS $$
  SELECT COALESCE((
    SELECT cc.status = 'active'
       AND (cc.expires_at IS NULL OR cc.expires_at > now())
    FROM communication_consents cc
    WHERE cc.tenant_id = p_tenant_id
      AND cc.contact_id = p_contact_id
      AND cc.contact_channel_id = p_contact_channel_id
      AND cc.purpose = p_purpose
    ORDER BY cc.captured_at DESC, cc.created_at DESC, cc.id DESC
    LIMIT 1
  ), false);
$$;

CREATE FUNCTION guard_contact_channel_state() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
  NEW.address := NULLIF(btrim(NEW.address), '');
  NEW.normalized_address := normalize_contact_channel_address(
    NEW.channel_type,
    COALESCE(NEW.normalized_address, NEW.address)
  );

  IF NEW.address IS NULL OR NEW.normalized_address IS NULL THEN
    RAISE EXCEPTION 'Contact channel address is required';
  END IF;

  IF NEW.channel_type = 'email'
     AND NEW.normalized_address !~* '^[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}$' THEN
    RAISE EXCEPTION 'Invalid email contact channel address: %', NEW.address;
  END IF;

  IF NEW.channel_type IN ('sms', 'whatsapp', 'voice_phone')
     AND NEW.normalized_address !~ '^\+[1-9][0-9]{7,14}$' THEN
    RAISE EXCEPTION 'Invalid E.164 contact channel address: %', NEW.address;
  END IF;

  IF NEW.verification_status = 'verified' AND NEW.verified_at IS NULL THEN
    NEW.verified_at := now();
  END IF;

  IF NEW.verification_status <> 'verified' AND NEW.verified_at IS NOT NULL THEN
    RAISE EXCEPTION 'verified_at can only be set when verification_status is verified';
  END IF;

  RETURN NEW;
END;
$$;

CREATE FUNCTION guard_communication_consents_append_only() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
  v_channel record;
BEGIN
  IF TG_OP = 'UPDATE' OR TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'Communication consents are append-only; insert a new consent state instead of mutating history';
  END IF;

  SELECT ch.channel_type, ch.verification_status, ch.is_active, ch.deleted_at
  INTO v_channel
  FROM contact_channels ch
  WHERE ch.tenant_id = NEW.tenant_id
    AND ch.contact_id = NEW.contact_id
    AND ch.id = NEW.contact_channel_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'Consent channel % does not belong to tenant/contact', NEW.contact_channel_id;
  END IF;

  IF NEW.status = 'active'
     AND (v_channel.deleted_at IS NOT NULL OR v_channel.is_active = false OR v_channel.verification_status <> 'verified') THEN
    RAISE EXCEPTION 'Active consent requires an active verified contact channel';
  END IF;

  IF NEW.status = 'revoked' AND NEW.revoked_at IS NULL THEN
    RAISE EXCEPTION 'Revoked consent requires revoked_at';
  END IF;

  IF NEW.status <> 'revoked' AND NEW.revoked_at IS NOT NULL THEN
    RAISE EXCEPTION 'revoked_at can only be set for revoked consent records';
  END IF;

  RETURN NEW;
END;
$$;

CREATE FUNCTION guard_report_subscription_recipient() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
  v_subscription record;
  v_channel record;
  v_supported boolean;
BEGIN
  SELECT rs.report_code, rs.status, rc.supports_email, rc.supports_sms,
         rc.supports_whatsapp, rc.supports_in_app
  INTO v_subscription
  FROM report_subscriptions rs
  JOIN report_catalog rc ON rc.report_code = rs.report_code
  WHERE rs.tenant_id = NEW.tenant_id
    AND rs.id = NEW.subscription_id
    AND rs.deleted_at IS NULL;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'Report subscription % does not exist for tenant %', NEW.subscription_id, NEW.tenant_id;
  END IF;

  SELECT ch.channel_type, ch.verification_status, ch.is_active, ch.deleted_at
  INTO v_channel
  FROM contact_channels ch
  WHERE ch.tenant_id = NEW.tenant_id
    AND ch.contact_id = NEW.contact_id
    AND ch.id = NEW.contact_channel_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'Report recipient channel % does not belong to the selected tenant contact', NEW.contact_channel_id;
  END IF;

  v_supported := CASE v_channel.channel_type
    WHEN 'email' THEN v_subscription.supports_email
    WHEN 'sms' THEN v_subscription.supports_sms
    WHEN 'whatsapp' THEN v_subscription.supports_whatsapp
    ELSE false
  END;

  IF NOT v_supported THEN
    RAISE EXCEPTION 'Report % does not support % delivery', v_subscription.report_code, v_channel.channel_type;
  END IF;

  IF NEW.is_enabled THEN
    IF v_channel.deleted_at IS NOT NULL OR v_channel.is_active = false OR v_channel.verification_status <> 'verified' THEN
      RAISE EXCEPTION 'Enabled report recipient requires an active verified contact channel';
    END IF;

    IF NOT contact_channel_has_active_consent(
      NEW.tenant_id,
      NEW.contact_id,
      NEW.contact_channel_id,
      'operational_reports'
    ) THEN
      RAISE EXCEPTION 'Enabled report recipient requires active operational_reports consent';
    END IF;
  END IF;

  RETURN NEW;
END;
$$;

CREATE FUNCTION guard_report_delivery_state() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
  v_run record;
  v_channel record;
  v_recipient_id uuid;
BEGIN
  SELECT rr.property_id, rr.report_code
  INTO v_run
  FROM report_runs rr
  WHERE rr.tenant_id = NEW.tenant_id
    AND rr.id = NEW.report_run_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'Report run % does not exist for tenant %', NEW.report_run_id, NEW.tenant_id;
  END IF;

  IF NEW.property_id IS NULL THEN
    NEW.property_id := v_run.property_id;
  END IF;

  IF NEW.subscription_recipient_id IS NOT NULL THEN
    SELECT rsr.id
    INTO v_recipient_id
    FROM report_subscription_recipients rsr
    WHERE rsr.tenant_id = NEW.tenant_id
      AND rsr.id = NEW.subscription_recipient_id
      AND rsr.contact_id = NEW.contact_id
      AND rsr.contact_channel_id = NEW.contact_channel_id;

    IF v_recipient_id IS NULL THEN
      RAISE EXCEPTION 'Report delivery recipient linkage is not tenant/contact/channel safe';
    END IF;
  END IF;

  SELECT ch.channel_type, ch.address
  INTO v_channel
  FROM contact_channels ch
  WHERE ch.tenant_id = NEW.tenant_id
    AND ch.contact_id = NEW.contact_id
    AND ch.id = NEW.contact_channel_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'Report delivery channel % does not belong to tenant/contact', NEW.contact_channel_id;
  END IF;

  NEW.channel_type := v_channel.channel_type;
  NEW.destination_masked := COALESCE(
    NEW.destination_masked,
    mask_contact_channel_address(v_channel.channel_type, v_channel.address)
  );

  IF NEW.status IN ('sent', 'delivered') AND NEW.sent_at IS NULL THEN
    NEW.sent_at := now();
  END IF;

  IF NEW.status = 'delivered' AND NEW.delivered_at IS NULL THEN
    NEW.delivered_at := now();
  END IF;

  IF NEW.status = 'failed' AND NEW.failed_at IS NULL THEN
    NEW.failed_at := now();
  END IF;

  RETURN NEW;
END;
$$;

CREATE FUNCTION tenant_onboarding_is_ready(p_tenant_id uuid) RETURNS boolean
    LANGUAGE sql STABLE
    AS $$
  SELECT EXISTS (
      SELECT 1
      FROM tenant_profiles tp
      WHERE tp.tenant_id = p_tenant_id
        AND tp.verification_status IN ('approved', 'verified')
        AND tp.business_phone IS NOT NULL
        AND tp.business_email IS NOT NULL
    )
    AND EXISTS (
      SELECT 1
      FROM tenant_contact_roles tcr
      JOIN tenant_contacts tc
        ON tc.tenant_id = tcr.tenant_id
       AND tc.id = tcr.contact_id
       AND tc.deleted_at IS NULL
       AND tc.status = 'active'
      WHERE tcr.tenant_id = p_tenant_id
        AND tcr.role_code IN ('owner_managing_director', 'authorized_signatory', 'primary_contact')
        AND (tcr.effective_to IS NULL OR tcr.effective_to > now())
    )
    AND EXISTS (
      SELECT 1
      FROM report_subscription_recipients rsr
      JOIN report_subscriptions rs
        ON rs.tenant_id = rsr.tenant_id
       AND rs.id = rsr.subscription_id
       AND rs.status = 'active'
       AND rs.deleted_at IS NULL
      WHERE rsr.tenant_id = p_tenant_id
        AND rsr.is_enabled = true
        AND contact_channel_has_active_consent(
          rsr.tenant_id,
          rsr.contact_id,
          rsr.contact_channel_id,
          'operational_reports'
        )
    );
$$;

CREATE FUNCTION is_tenant_module_enabled(p_tenant_id uuid, p_module_id text) RETURNS boolean
    LANGUAGE sql STABLE
    AS $$
  SELECT
    p_tenant_id = current_tenant_id()
    AND current_platform_user_id() IS NULL
    AND EXISTS (
      SELECT 1
      FROM tenant_modules tm
      WHERE tm.tenant_id = p_tenant_id
        AND tm.module_id = p_module_id
        AND tm.is_enabled = true
    );
$$;

CREATE FUNCTION is_property_module_enabled(p_tenant_id uuid, p_property_id uuid, p_module_id text) RETURNS boolean
    LANGUAGE sql STABLE
    AS $$
  SELECT
    is_tenant_module_enabled(p_tenant_id, p_module_id)
    AND EXISTS (
      SELECT 1
      FROM property_modules pm
      JOIN properties p
        ON p.id = pm.property_id
       AND p.tenant_id = pm.tenant_id
      WHERE pm.tenant_id = p_tenant_id
        AND pm.property_id = p_property_id
        AND pm.module_id = p_module_id
        AND pm.is_enabled = true
        AND p.deleted_at IS NULL
    );
$$;

CREATE FUNCTION user_has_property_permission(
    p_user_id uuid,
    p_tenant_id uuid,
    p_property_id uuid,
    p_permission_code text
) RETURNS boolean
    LANGUAGE sql STABLE
    AS $$
  SELECT
    p_tenant_id = current_tenant_id()
    AND current_platform_user_id() IS NULL
    AND p_user_id = current_tenant_user_id()
    AND p_permission_code IS NOT NULL
    AND EXISTS (
      SELECT 1
      FROM users u
      JOIN user_property_roles upr
        ON upr.user_id = u.id
       AND upr.tenant_id = u.tenant_id
      JOIN roles r
        ON r.id = upr.role_id
       AND r.tenant_id = upr.tenant_id
      JOIN role_permissions rp
        ON rp.role_id = r.id
      JOIN permissions perm
        ON perm.id = rp.permission_id
       AND perm.tenant_id = upr.tenant_id
      WHERE u.id = p_user_id
        AND u.tenant_id = p_tenant_id
        AND u.status = 'active'
        AND u.is_active = true
        AND u.deleted_at IS NULL
        AND (u.locked_until IS NULL OR u.locked_until <= now())
        AND upr.tenant_id = p_tenant_id
        AND (p_property_id IS NULL OR upr.property_id = p_property_id)
        AND r.is_active = true
        AND (perm.code = p_permission_code OR perm.code = 'admin.all')
    );
$$;

CREATE FUNCTION current_platform_user_id() RETURNS uuid
    LANGUAGE sql STABLE
    AS $$
  SELECT nullif(current_setting('app.current_platform_user_id', true), '')::uuid;
$$;

CREATE FUNCTION assert_no_mixed_context() RETURNS void
    LANGUAGE plpgsql STABLE
    AS $$
BEGIN
  IF current_platform_user_id() IS NOT NULL
     AND (current_tenant_id() IS NOT NULL OR current_tenant_user_id() IS NOT NULL) THEN
    RAISE EXCEPTION 'Mixed tenant and platform context is not allowed';
  END IF;

  IF current_tenant_user_id() IS NOT NULL AND current_tenant_id() IS NULL THEN
    RAISE EXCEPTION 'Tenant user context requires tenant context';
  END IF;
END;
$$;

CREATE FUNCTION assert_tenant_context() RETURNS uuid
    LANGUAGE plpgsql STABLE
    AS $$
DECLARE
  v_tenant_id uuid;
BEGIN
  PERFORM assert_no_mixed_context();
  v_tenant_id := current_tenant_id();
  IF v_tenant_id IS NULL THEN
    RAISE EXCEPTION 'Tenant context is required but app.current_tenant_id is not set';
  END IF;
  RETURN v_tenant_id;
END;
$$;

CREATE FUNCTION assert_tenant_user_context() RETURNS uuid
    LANGUAGE plpgsql STABLE
    AS $$
DECLARE
  v_user_id uuid;
BEGIN
  PERFORM assert_tenant_context();
  v_user_id := current_tenant_user_id();
  IF v_user_id IS NULL THEN
    RAISE EXCEPTION 'Tenant user context is required but app.current_tenant_user_id is not set';
  END IF;
  RETURN v_user_id;
END;
$$;

CREATE FUNCTION assert_platform_context() RETURNS uuid
    LANGUAGE plpgsql STABLE
    AS $$
DECLARE
  v_platform_user_id uuid;
BEGIN
  PERFORM assert_no_mixed_context();
  v_platform_user_id := current_platform_user_id();
  IF v_platform_user_id IS NULL THEN
    RAISE EXCEPTION 'Platform context is required but app.current_platform_user_id is not set';
  END IF;
  RETURN v_platform_user_id;
END;
$$;

CREATE FUNCTION user_has_tenant_permission(
    p_user_id uuid,
    p_tenant_id uuid,
    p_permission_code text
) RETURNS boolean
    LANGUAGE sql STABLE
    AS $$
  SELECT
    p_tenant_id = current_tenant_id()
    AND current_platform_user_id() IS NULL
    AND p_user_id = current_tenant_user_id()
    AND p_permission_code IS NOT NULL
    AND EXISTS (
      SELECT 1
      FROM users u
      JOIN user_tenant_roles utr
        ON utr.user_id = u.id
       AND utr.tenant_id = u.tenant_id
      JOIN tenant_roles tr
        ON tr.id = utr.tenant_role_id
       AND tr.tenant_id = utr.tenant_id
      JOIN tenant_role_permissions trp
        ON trp.tenant_role_id = tr.id
      JOIN permissions perm
        ON perm.id = trp.permission_id
       AND perm.tenant_id = utr.tenant_id
      WHERE u.id = p_user_id
        AND u.tenant_id = p_tenant_id
        AND u.status = 'active'
        AND u.is_active = true
        AND u.deleted_at IS NULL
        AND (u.locked_until IS NULL OR u.locked_until <= now())
        AND utr.tenant_id = p_tenant_id
        AND tr.is_active = true
        AND (perm.code = p_permission_code OR perm.code = 'tenant.admin.all')
    );
$$;

CREATE OR REPLACE FUNCTION can_access_module(
    p_user_id uuid,
    p_tenant_id uuid,
    p_property_id uuid,
    p_module_id text,
    p_permission_code text DEFAULT NULL
) RETURNS boolean
    LANGUAGE sql STABLE
    AS $$
  SELECT
    p_tenant_id = current_tenant_id()
    AND current_platform_user_id() IS NULL
    AND p_user_id = current_tenant_user_id()
    AND p_permission_code IS NOT NULL
    AND is_tenant_module_enabled(p_tenant_id, p_module_id)
    AND (
      (
        p_property_id IS NULL
        AND user_has_tenant_permission(p_user_id, p_tenant_id, p_permission_code)
      )
      OR (
        p_property_id IS NOT NULL
        AND is_property_module_enabled(p_tenant_id, p_property_id, p_module_id)
        AND user_has_property_permission(p_user_id, p_tenant_id, p_property_id, p_permission_code)
      )
    );
$$;

CREATE FUNCTION can_access_public_module(
    p_tenant_id uuid,
    p_property_id uuid,
    p_module_id text
) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = public
    AS $$
  SELECT
    current_tenant_id() IS NULL
    AND current_tenant_user_id() IS NULL
    AND current_platform_user_id() IS NULL
    AND p_tenant_id IS NOT NULL
    AND p_property_id IS NOT NULL
    AND EXISTS (
      SELECT 1
      FROM tenants t
      WHERE t.id = p_tenant_id
        AND t.status IN ('trial', 'active')
        AND t.deleted_at IS NULL
    )
    AND EXISTS (
      SELECT 1
      FROM properties p
      WHERE p.tenant_id = p_tenant_id
        AND p.id = p_property_id
        AND p.status = 'active'
        AND p.is_active = true
        AND p.deleted_at IS NULL
    )
    AND EXISTS (
      SELECT 1
      FROM tenant_modules tm
      WHERE tm.tenant_id = p_tenant_id
        AND tm.module_id = p_module_id
        AND tm.is_enabled = true
    )
    AND EXISTS (
      SELECT 1
      FROM property_modules pm
      WHERE pm.tenant_id = p_tenant_id
        AND pm.property_id = p_property_id
        AND pm.module_id = p_module_id
        AND pm.is_enabled = true
    );
$$;

CREATE FUNCTION platform_user_has_permission(
    p_platform_user_id uuid,
    p_permission_code text
) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = public
    AS $$
  SELECT
    p_platform_user_id = current_platform_user_id()
    AND current_tenant_id() IS NULL
    AND EXISTS (
      SELECT 1
      FROM platform_users pu
      JOIN platform_user_roles pur
        ON pur.platform_user_id = pu.id
      JOIN platform_roles pr
        ON pr.id = pur.platform_role_id
      JOIN platform_role_permissions prp
        ON prp.platform_role_id = pr.id
      JOIN platform_permissions pp
        ON pp.id = prp.platform_permission_id
      WHERE pu.id = p_platform_user_id
        AND pu.status = 'active'
        AND pr.is_active = true
        AND (pp.code = p_permission_code OR pp.code = 'platform.admin.all')
    );
$$;

CREATE FUNCTION can_platform_admin_access_tenant(
    p_platform_user_id uuid,
    p_tenant_id uuid,
    p_action_code text
) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = public
    AS $$
  SELECT
    current_tenant_id() IS NULL
    AND platform_user_has_permission(p_platform_user_id, p_action_code)
    AND EXISTS (
      SELECT 1
      FROM tenants t
      WHERE t.id = p_tenant_id
        AND t.deleted_at IS NULL
    )
    AND EXISTS (
      SELECT 1
      FROM platform_break_glass_access bga
      WHERE bga.platform_user_id = p_platform_user_id
        AND bga.tenant_id = p_tenant_id
        AND bga.action_code = p_action_code
        AND bga.status = 'active'
        AND bga.approved_by IS NOT NULL
        AND bga.approved_at IS NOT NULL
        AND bga.activated_at IS NOT NULL
        AND bga.activated_at <= now()
        AND bga.revoked_at IS NULL
        AND bga.starts_at <= now()
        AND bga.expires_at > now()
    );
$$;

CREATE FUNCTION allocate_document_number(
    p_tenant_id uuid,
    p_document_type text,
    p_year smallint DEFAULT (EXTRACT(year FROM CURRENT_DATE))::smallint
) RETURNS TABLE (
    sequence_id uuid,
    sequence_value bigint,
    formatted_document_number text
)
    LANGUAGE plpgsql
    AS $$
DECLARE
  v_sequence record;
BEGIN
  PERFORM assert_no_mixed_context();

  IF current_platform_user_id() IS NOT NULL THEN
    RAISE EXCEPTION 'Document sequence allocation requires tenant PMS context';
  END IF;

  IF current_tenant_id() IS DISTINCT FROM p_tenant_id THEN
    RAISE EXCEPTION 'Document sequence tenant % does not match current tenant context', p_tenant_id;
  END IF;

  SELECT ds.id, ds.prefix, ds.year, ds.next_value, ds.padding
  INTO v_sequence
  FROM document_sequences ds
  WHERE ds.tenant_id = p_tenant_id
    AND ds.document_type = p_document_type
    AND ds.year = p_year
  FOR UPDATE;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'No document sequence exists for tenant %, document type %, year %', p_tenant_id, p_document_type, p_year;
  END IF;

  UPDATE document_sequences
  SET next_value = next_value + 1,
      updated_at = now()
  WHERE id = v_sequence.id;

  sequence_id := v_sequence.id;
  sequence_value := v_sequence.next_value;
  formatted_document_number := v_sequence.prefix || '-' || v_sequence.year::text || '-' || lpad(v_sequence.next_value::text, v_sequence.padding, '0');
  RETURN NEXT;
END;
$$;

CREATE FUNCTION claim_outbox_events(
    p_worker_id text,
    p_destination text DEFAULT NULL,
    p_limit integer DEFAULT 50
) RETURNS TABLE (
    id uuid,
    tenant_id uuid,
    property_id uuid,
    aggregate_type text,
    aggregate_id uuid,
    event_type text,
    destination character varying(50),
    payload jsonb,
    headers jsonb,
    correlation_id uuid,
    idempotency_key_id uuid,
    status character varying(20),
    priority smallint,
    attempt_count integer,
    max_attempts integer,
    next_attempt_at timestamp with time zone,
    locked_by text,
    locked_at timestamp with time zone,
    delivered_at timestamp with time zone,
    failed_at timestamp with time zone,
    error_message text,
    created_at timestamp with time zone,
    updated_at timestamp with time zone
)
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path = public
    AS $$
BEGIN
  IF p_worker_id IS NULL OR btrim(p_worker_id) = '' THEN
    RAISE EXCEPTION 'Outbox worker id is required';
  END IF;

  IF p_limit IS NULL OR p_limit < 1 OR p_limit > 500 THEN
    RAISE EXCEPTION 'Outbox claim limit must be between 1 and 500';
  END IF;

  RETURN QUERY
  WITH claimable AS (
    SELECT oe.id
    FROM outbox_events oe
    WHERE oe.status IN ('pending', 'failed')
      AND oe.next_attempt_at <= now()
      AND oe.attempt_count < oe.max_attempts
      AND (p_destination IS NULL OR oe.destination = p_destination)
    ORDER BY oe.priority ASC, oe.created_at ASC
    FOR UPDATE SKIP LOCKED
    LIMIT p_limit
  )
  UPDATE outbox_events oe
  SET status = 'locked',
      locked_by = p_worker_id,
      locked_at = now(),
      attempt_count = oe.attempt_count + 1,
      updated_at = now()
  FROM claimable c
  WHERE oe.id = c.id
  RETURNING
    oe.id,
    oe.tenant_id,
    oe.property_id,
    oe.aggregate_type,
    oe.aggregate_id,
    oe.event_type,
    oe.destination,
    oe.payload,
    oe.headers,
    oe.correlation_id,
    oe.idempotency_key_id,
    oe.status,
    oe.priority,
    oe.attempt_count,
    oe.max_attempts,
    oe.next_attempt_at,
    oe.locked_by,
    oe.locked_at,
    oe.delivered_at,
    oe.failed_at,
    oe.error_message,
    oe.created_at,
    oe.updated_at;
END;
$$;

CREATE FUNCTION complete_outbox_event(
    p_event_id uuid,
    p_worker_id text
) RETURNS void
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path = public
    AS $$
BEGIN
  IF p_worker_id IS NULL OR btrim(p_worker_id) = '' THEN
    RAISE EXCEPTION 'Outbox worker id is required';
  END IF;

  UPDATE outbox_events
  SET status = 'delivered',
      delivered_at = now(),
      locked_by = NULL,
      locked_at = NULL,
      error_message = NULL,
      updated_at = now()
  WHERE id = p_event_id
    AND status = 'locked'
    AND locked_by = p_worker_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'Outbox event % is not locked by worker %', p_event_id, p_worker_id;
  END IF;
END;
$$;

CREATE FUNCTION fail_outbox_event(
    p_event_id uuid,
    p_worker_id text,
    p_error_message text,
    p_retry_delay interval DEFAULT interval '5 minutes'
) RETURNS void
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path = public
    AS $$
DECLARE
  v_attempt_count integer;
  v_max_attempts integer;
BEGIN
  IF p_worker_id IS NULL OR btrim(p_worker_id) = '' THEN
    RAISE EXCEPTION 'Outbox worker id is required';
  END IF;

  SELECT attempt_count, max_attempts
  INTO v_attempt_count, v_max_attempts
  FROM outbox_events
  WHERE id = p_event_id
    AND status = 'locked'
    AND locked_by = p_worker_id
  FOR UPDATE;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'Outbox event % is not locked by worker %', p_event_id, p_worker_id;
  END IF;

  UPDATE outbox_events
  SET status = CASE WHEN v_attempt_count >= v_max_attempts THEN 'dead_letter' ELSE 'failed' END,
      failed_at = now(),
      locked_by = NULL,
      locked_at = NULL,
      next_attempt_at = CASE WHEN v_attempt_count >= v_max_attempts THEN next_attempt_at ELSE now() + COALESCE(p_retry_delay, interval '5 minutes') END,
      error_message = p_error_message,
      updated_at = now()
  WHERE id = p_event_id;
END;
$$;

CREATE FUNCTION reclaim_stale_outbox_events(
    p_locked_before timestamp with time zone DEFAULT now() - interval '15 minutes',
    p_limit integer DEFAULT 500
) RETURNS integer
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path = public
    AS $$
DECLARE
  v_count integer;
BEGIN
  IF p_limit IS NULL OR p_limit < 1 OR p_limit > 5000 THEN
    RAISE EXCEPTION 'Outbox stale-lock reclaim limit must be between 1 and 5000';
  END IF;

  WITH stale AS (
    SELECT id
    FROM outbox_events
    WHERE status = 'locked'
      AND locked_at < p_locked_before
      AND attempt_count < max_attempts
    ORDER BY locked_at ASC
    FOR UPDATE SKIP LOCKED
    LIMIT p_limit
  )
  UPDATE outbox_events oe
  SET status = 'failed',
      locked_by = NULL,
      locked_at = NULL,
      next_attempt_at = now(),
      error_message = COALESCE(error_message, 'Reclaimed after stale worker lock'),
      updated_at = now()
  FROM stale
  WHERE oe.id = stale.id;

  GET DIAGNOSTICS v_count = ROW_COUNT;
  RETURN v_count;
END;
$$;

CREATE FUNCTION dead_letter_outbox_event(
    p_event_id uuid,
    p_worker_id text,
    p_error_message text
) RETURNS void
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path = public
    AS $$
BEGIN
  IF p_worker_id IS NULL OR btrim(p_worker_id) = '' THEN
    RAISE EXCEPTION 'Outbox worker id is required';
  END IF;

  UPDATE outbox_events
  SET status = 'dead_letter',
      failed_at = now(),
      locked_by = NULL,
      locked_at = NULL,
      error_message = p_error_message,
      updated_at = now()
  WHERE id = p_event_id
    AND status = 'locked'
    AND locked_by = p_worker_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'Outbox event % is not locked by worker %', p_event_id, p_worker_id;
  END IF;
END;
$$;

REVOKE EXECUTE ON FUNCTION claim_outbox_events(text, text, integer) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION complete_outbox_event(uuid, text) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION fail_outbox_event(uuid, text, text, interval) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION reclaim_stale_outbox_events(timestamp with time zone, integer) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION dead_letter_outbox_event(uuid, text, text) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION can_access_public_module(uuid, uuid, text) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION platform_user_has_permission(uuid, text) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION can_platform_admin_access_tenant(uuid, uuid, text) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION claim_outbox_events(text, text, integer) TO pms_worker;
GRANT EXECUTE ON FUNCTION complete_outbox_event(uuid, text) TO pms_worker;
GRANT EXECUTE ON FUNCTION fail_outbox_event(uuid, text, text, interval) TO pms_worker;
GRANT EXECUTE ON FUNCTION reclaim_stale_outbox_events(timestamp with time zone, integer) TO pms_worker;
GRANT EXECUTE ON FUNCTION dead_letter_outbox_event(uuid, text, text) TO pms_worker;
GRANT EXECUTE ON FUNCTION can_access_public_module(uuid, uuid, text) TO pms_app;
GRANT EXECUTE ON FUNCTION platform_user_has_permission(uuid, text) TO pms_platform;
GRANT EXECUTE ON FUNCTION can_platform_admin_access_tenant(uuid, uuid, text) TO pms_platform;

CREATE FUNCTION tenant_allows_operational_writes(p_tenant_id uuid) RETURNS boolean
    LANGUAGE sql STABLE
    AS $$
  SELECT EXISTS (
    SELECT 1
    FROM tenants t
    WHERE t.id = p_tenant_id
      AND t.deleted_at IS NULL
      AND t.status IN ('trial', 'active')
  );
$$;

CREATE FUNCTION property_allows_operational_writes(p_tenant_id uuid, p_property_id uuid) RETURNS boolean
    LANGUAGE sql STABLE
    AS $$
  SELECT
    p_property_id IS NULL
    OR EXISTS (
      SELECT 1
      FROM properties p
      WHERE p.tenant_id = p_tenant_id
        AND p.id = p_property_id
        AND p.deleted_at IS NULL
        AND p.is_active = true
        AND p.status = 'active'
    );
$$;

CREATE FUNCTION guard_tenant_operational_write() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
  v_row jsonb;
  v_tenant_id uuid;
  v_property_id uuid;
BEGIN
  IF current_platform_user_id() IS NOT NULL AND current_tenant_id() IS NULL THEN
    IF TG_OP = 'DELETE' THEN
      RETURN OLD;
    END IF;
    RETURN NEW;
  END IF;

  PERFORM assert_no_mixed_context();

  v_row := CASE WHEN TG_OP = 'DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
  v_tenant_id := NULLIF(v_row->>'tenant_id', '')::uuid;
  v_property_id := NULLIF(v_row->>'property_id', '')::uuid;

  IF v_tenant_id IS NULL THEN
    IF TG_OP = 'DELETE' THEN
      RETURN OLD;
    END IF;
    RETURN NEW;
  END IF;

  IF NOT tenant_allows_operational_writes(v_tenant_id) THEN
    RAISE EXCEPTION 'Tenant % is not writable in its current lifecycle state', v_tenant_id;
  END IF;

  IF NOT property_allows_operational_writes(v_tenant_id, v_property_id) THEN
    RAISE EXCEPTION 'Property % for tenant % is not writable in its current lifecycle state', v_property_id, v_tenant_id;
  END IF;

  IF TG_OP = 'DELETE' THEN
    RETURN OLD;
  END IF;
  RETURN NEW;
END;
$$;

CREATE FUNCTION normalize_guest_name() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
  NEW.first_name := NULLIF(btrim(NEW.first_name), '');
  NEW.last_name := NULLIF(btrim(NEW.last_name), '');
  NEW.full_name := NULLIF(btrim(NEW.full_name), '');

  IF NEW.first_name IS NOT NULL OR NEW.last_name IS NOT NULL THEN
    NEW.full_name := NULLIF(btrim(concat_ws(' ', NEW.first_name, NEW.last_name)), '');
  END IF;

  RETURN NEW;
END;
$$;

CREATE FUNCTION recalculate_guest_rollups(
    p_tenant_id uuid,
    p_guest_id uuid
) RETURNS void
    LANGUAGE plpgsql
    AS $$
BEGIN
  IF p_tenant_id IS NULL OR p_guest_id IS NULL THEN
    RETURN;
  END IF;

  PERFORM 1
  FROM guests g
  WHERE g.tenant_id = p_tenant_id
    AND g.id = p_guest_id
  FOR UPDATE;

  IF NOT FOUND THEN
    RETURN;
  END IF;

  UPDATE guests g
  SET total_stays = COALESCE((
        SELECT count(DISTINCT r.id)::integer
        FROM reservations r
        WHERE r.tenant_id = p_tenant_id
          AND r.primary_guest_id = p_guest_id
          AND r.status = 'checked_out'
          AND r.deleted_at IS NULL
      ), 0),
      total_revenue = COALESCE((
        SELECT round(sum(fc.amount), 2)
        FROM folio_charges fc
        JOIN folios f
          ON f.tenant_id = fc.tenant_id
         AND f.id = fc.folio_id
        JOIN reservations r
          ON r.tenant_id = f.tenant_id
         AND r.id = f.reservation_id
        WHERE r.tenant_id = p_tenant_id
          AND r.primary_guest_id = p_guest_id
          AND r.deleted_at IS NULL
          AND f.deleted_at IS NULL
          AND fc.deleted_at IS NULL
          AND fc.status = 'POSTED'
      ), 0),
      updated_at = now()
  WHERE g.tenant_id = p_tenant_id
    AND g.id = p_guest_id;
END;
$$;

CREATE FUNCTION recalculate_guest_rollups_for_reservation(
    p_tenant_id uuid,
    p_reservation_id uuid
) RETURNS void
    LANGUAGE plpgsql
    AS $$
DECLARE
  v_guest_id uuid;
BEGIN
  IF p_tenant_id IS NULL OR p_reservation_id IS NULL THEN
    RETURN;
  END IF;

  SELECT r.primary_guest_id
  INTO v_guest_id
  FROM reservations r
  WHERE r.tenant_id = p_tenant_id
    AND r.id = p_reservation_id;

  PERFORM recalculate_guest_rollups(p_tenant_id, v_guest_id);
END;
$$;

CREATE FUNCTION recalculate_guest_rollups_for_folio(
    p_tenant_id uuid,
    p_folio_id uuid
) RETURNS void
    LANGUAGE plpgsql
    AS $$
DECLARE
  v_reservation_id uuid;
BEGIN
  IF p_tenant_id IS NULL OR p_folio_id IS NULL THEN
    RETURN;
  END IF;

  SELECT f.reservation_id
  INTO v_reservation_id
  FROM folios f
  WHERE f.tenant_id = p_tenant_id
    AND f.id = p_folio_id;

  PERFORM recalculate_guest_rollups_for_reservation(p_tenant_id, v_reservation_id);
END;
$$;

CREATE FUNCTION sync_guest_rollups_from_reservation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    PERFORM recalculate_guest_rollups(OLD.tenant_id, OLD.primary_guest_id);
    RETURN OLD;
  END IF;

  PERFORM recalculate_guest_rollups(NEW.tenant_id, NEW.primary_guest_id);

  IF TG_OP = 'UPDATE'
     AND (OLD.tenant_id, OLD.primary_guest_id) IS DISTINCT FROM (NEW.tenant_id, NEW.primary_guest_id) THEN
    PERFORM recalculate_guest_rollups(OLD.tenant_id, OLD.primary_guest_id);
  END IF;

  RETURN NEW;
END;
$$;

CREATE FUNCTION sync_guest_rollups_from_stay() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    PERFORM recalculate_guest_rollups_for_reservation(OLD.tenant_id, OLD.reservation_id);
    RETURN OLD;
  END IF;

  PERFORM recalculate_guest_rollups_for_reservation(NEW.tenant_id, NEW.reservation_id);

  IF TG_OP = 'UPDATE'
     AND (OLD.tenant_id, OLD.reservation_id) IS DISTINCT FROM (NEW.tenant_id, NEW.reservation_id) THEN
    PERFORM recalculate_guest_rollups_for_reservation(OLD.tenant_id, OLD.reservation_id);
  END IF;

  RETURN NEW;
END;
$$;

CREATE FUNCTION sync_guest_rollups_from_folio() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    PERFORM recalculate_guest_rollups_for_folio(OLD.tenant_id, OLD.id);
    RETURN OLD;
  END IF;

  PERFORM recalculate_guest_rollups_for_folio(NEW.tenant_id, NEW.id);

  IF TG_OP = 'UPDATE'
     AND (OLD.tenant_id, OLD.reservation_id) IS DISTINCT FROM (NEW.tenant_id, NEW.reservation_id) THEN
    PERFORM recalculate_guest_rollups_for_folio(OLD.tenant_id, OLD.id);
  END IF;

  RETURN NEW;
END;
$$;

CREATE FUNCTION sync_guest_rollups_from_folio_charge() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    PERFORM recalculate_guest_rollups_for_folio(OLD.tenant_id, OLD.folio_id);
    RETURN OLD;
  END IF;

  PERFORM recalculate_guest_rollups_for_folio(NEW.tenant_id, NEW.folio_id);

  IF TG_OP = 'UPDATE'
     AND (OLD.tenant_id, OLD.folio_id) IS DISTINCT FROM (NEW.tenant_id, NEW.folio_id) THEN
    PERFORM recalculate_guest_rollups_for_folio(OLD.tenant_id, OLD.folio_id);
  END IF;

  RETURN NEW;
END;
$$;

CREATE FUNCTION sync_menu_item_tax_snapshot() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
  v_rate numeric(8,6);
BEGIN
  IF NEW.tax_rate_id IS NULL THEN
    RETURN NEW;
  END IF;

  SELECT tr.rate
  INTO v_rate
  FROM tax_rates tr
  WHERE tr.tenant_id = NEW.tenant_id
    AND tr.id = NEW.tax_rate_id
    AND tr.is_active = true;

  IF v_rate IS NULL THEN
    RAISE EXCEPTION 'Active tax rate % for tenant % was not found', NEW.tax_rate_id, NEW.tenant_id;
  END IF;

  NEW.vat_rate := round(v_rate * 100, 2);
  RETURN NEW;
END;
$$;

CREATE FUNCTION apply_availability_lock_capacity() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
  v_expected_days integer;
  v_changed_days integer;
BEGIN
  IF TG_OP = 'INSERT' THEN
    IF NEW.released_at IS NULL THEN
      v_expected_days := NEW.check_out_date - NEW.check_in_date;

      UPDATE availability_calendar ac
      SET rooms_held = ac.rooms_held + NEW.rooms_held,
          updated_at = now()
      WHERE ac.tenant_id = NEW.tenant_id
        AND ac.property_id = NEW.property_id
        AND ac.room_type_id = NEW.room_type_id
        AND ac.stay_date >= NEW.check_in_date
        AND ac.stay_date < NEW.check_out_date
        AND ac.stop_sell = false;

      GET DIAGNOSTICS v_changed_days = ROW_COUNT;
      IF v_changed_days <> v_expected_days THEN
        RAISE EXCEPTION 'Availability calendar is incomplete or closed for room type % from % to %',
          NEW.room_type_id, NEW.check_in_date, NEW.check_out_date;
      END IF;
    END IF;

    RETURN NEW;
  END IF;

  IF TG_OP = 'UPDATE' THEN
    IF (OLD.tenant_id, OLD.property_id, OLD.room_type_id, OLD.check_in_date, OLD.check_out_date, OLD.rooms_held)
       IS DISTINCT FROM
       (NEW.tenant_id, NEW.property_id, NEW.room_type_id, NEW.check_in_date, NEW.check_out_date, NEW.rooms_held) THEN
      RAISE EXCEPTION 'Availability lock scope and quantity are immutable after creation';
    END IF;

    IF OLD.released_at IS NULL AND NEW.released_at IS NOT NULL THEN
      v_expected_days := OLD.check_out_date - OLD.check_in_date;

      UPDATE availability_calendar ac
      SET rooms_held = ac.rooms_held - OLD.rooms_held,
          updated_at = now()
      WHERE ac.tenant_id = OLD.tenant_id
        AND ac.property_id = OLD.property_id
        AND ac.room_type_id = OLD.room_type_id
        AND ac.stay_date >= OLD.check_in_date
        AND ac.stay_date < OLD.check_out_date;

      GET DIAGNOSTICS v_changed_days = ROW_COUNT;
      IF v_changed_days <> v_expected_days THEN
        RAISE EXCEPTION 'Availability calendar is incomplete while releasing lock %', OLD.id;
      END IF;
    ELSIF OLD.released_at IS NOT NULL AND NEW.released_at IS NULL THEN
      RAISE EXCEPTION 'Released availability locks cannot be reactivated';
    END IF;

    RETURN NEW;
  END IF;

  IF TG_OP = 'DELETE' THEN
    IF OLD.released_at IS NULL THEN
      UPDATE availability_calendar ac
      SET rooms_held = ac.rooms_held - OLD.rooms_held,
          updated_at = now()
      WHERE ac.tenant_id = OLD.tenant_id
        AND ac.property_id = OLD.property_id
        AND ac.room_type_id = OLD.room_type_id
        AND ac.stay_date >= OLD.check_in_date
        AND ac.stay_date < OLD.check_out_date;
    END IF;

    RETURN OLD;
  END IF;

  RETURN NULL;
END;
$$;

CREATE FUNCTION recalculate_corporate_account_balance(p_corporate_account_id uuid) RETURNS void
    LANGUAGE plpgsql
    AS $$
DECLARE
  v_account record;
  v_invoice_total numeric(15,2);
  v_allocation_total numeric(15,2);
BEGIN
  SELECT id, tenant_id, company_id
  INTO v_account
  FROM corporate_accounts
  WHERE id = p_corporate_account_id;

  IF NOT FOUND THEN
    RETURN;
  END IF;

  SELECT COALESCE(SUM(i.total), 0)
  INTO v_invoice_total
  FROM invoices i
  WHERE i.tenant_id = v_account.tenant_id
    AND i.company_id = v_account.company_id
    AND i.status IN ('issued', 'sent', 'paid', 'overdue')
    AND i.deleted_at IS NULL;

  SELECT COALESCE(SUM(a.amount), 0)
  INTO v_allocation_total
  FROM ar_allocations a
  WHERE a.tenant_id = v_account.tenant_id
    AND a.corporate_account_id = v_account.id
    AND a.allocation_type IN ('payment_to_invoice', 'credit_to_invoice', 'writeoff');

  PERFORM set_config('app.allow_corporate_balance_update', 'on', true);

  UPDATE corporate_accounts
  SET current_balance = GREATEST(round(v_invoice_total - v_allocation_total, 2), 0),
      updated_at = now()
  WHERE id = v_account.id;

  PERFORM set_config('app.allow_corporate_balance_update', '', true);
END;
$$;

CREATE FUNCTION guard_corporate_account_balance_write() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
  IF TG_OP = 'UPDATE'
     AND NEW.current_balance IS DISTINCT FROM OLD.current_balance
     AND current_setting('app.allow_corporate_balance_update', true) IS DISTINCT FROM 'on' THEN
    RAISE EXCEPTION 'corporate_accounts.current_balance is system-derived; update source invoices or AR allocations instead';
  END IF;
  RETURN NEW;
END;
$$;

CREATE FUNCTION sync_corporate_account_balance_from_ar() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
  v_old_account_id uuid;
  v_new_account_id uuid;
  v_account_id uuid;
BEGIN
  IF TG_TABLE_NAME = 'invoices' THEN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
      SELECT ca.id
      INTO v_old_account_id
      FROM corporate_accounts ca
      WHERE ca.tenant_id = OLD.tenant_id
        AND ca.company_id = OLD.company_id
      LIMIT 1;
    END IF;

    IF TG_OP IN ('INSERT', 'UPDATE') THEN
      SELECT ca.id
      INTO v_new_account_id
      FROM corporate_accounts ca
      WHERE ca.tenant_id = NEW.tenant_id
        AND ca.company_id = NEW.company_id
      LIMIT 1;
    END IF;
  ELSE
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
      v_old_account_id := OLD.corporate_account_id;
    END IF;

    IF TG_OP IN ('INSERT', 'UPDATE') THEN
      v_new_account_id := NEW.corporate_account_id;
    END IF;
  END IF;

  FOR v_account_id IN
    SELECT DISTINCT account_id
    FROM (VALUES (v_old_account_id), (v_new_account_id)) AS account_ids(account_id)
    WHERE account_id IS NOT NULL
    ORDER BY account_id
  LOOP
    PERFORM recalculate_corporate_account_balance(v_account_id);
  END LOOP;

  IF TG_OP = 'DELETE' THEN
    RETURN OLD;
  END IF;
  RETURN NEW;
END;
$$;

CREATE FUNCTION recalculate_journal_entry_totals(p_journal_entry_id uuid) RETURNS void
    LANGUAGE plpgsql
    AS $$
DECLARE
  v_debit numeric(15,2);
  v_credit numeric(15,2);
  v_status text;
BEGIN
  SELECT status INTO v_status
  FROM journal_entries
  WHERE id = p_journal_entry_id
  FOR UPDATE;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'Journal entry % does not exist', p_journal_entry_id;
  END IF;

  IF v_status <> 'draft' THEN
    RAISE EXCEPTION 'Cannot recalculate totals for % journal entry %', v_status, p_journal_entry_id;
  END IF;

  SELECT
    COALESCE(round(sum(debit), 2), 0),
    COALESCE(round(sum(credit), 2), 0)
  INTO v_debit, v_credit
  FROM journal_entry_lines
  WHERE journal_entry_id = p_journal_entry_id;

  PERFORM set_config('app.allow_journal_total_update', 'on', true);

  UPDATE journal_entries
  SET total_debit = v_debit,
      total_credit = v_credit,
      updated_at = now()
  WHERE id = p_journal_entry_id;

  PERFORM set_config('app.allow_journal_total_update', 'off', true);
END;
$$;

CREATE FUNCTION guard_journal_entry_line_integrity() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
  v_entry record;
  v_old_entry record;
  v_account record;
  v_journal_entry_id uuid;
BEGIN
  v_journal_entry_id := CASE WHEN TG_OP = 'DELETE' THEN OLD.journal_entry_id ELSE NEW.journal_entry_id END;

  SELECT tenant_id, property_id, status, currency
  INTO v_entry
  FROM journal_entries
  WHERE id = v_journal_entry_id
  FOR UPDATE;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'Journal entry % does not exist', v_journal_entry_id;
  END IF;

  IF v_entry.status <> 'draft' THEN
    RAISE EXCEPTION 'Journal lines cannot be changed after entry is %', v_entry.status;
  END IF;

  IF TG_OP = 'UPDATE' AND OLD.journal_entry_id <> NEW.journal_entry_id THEN
    SELECT status
    INTO v_old_entry
    FROM journal_entries
    WHERE id = OLD.journal_entry_id
    FOR UPDATE;

    IF v_old_entry.status <> 'draft' THEN
      RAISE EXCEPTION 'Journal lines cannot be moved from a % journal entry', v_old_entry.status;
    END IF;
  END IF;

  IF TG_OP <> 'DELETE' THEN
    SELECT tenant_id, currency, is_active
    INTO v_account
    FROM accounting_accounts
    WHERE id = NEW.account_id;

    IF NOT FOUND THEN
      RAISE EXCEPTION 'Accounting account % does not exist', NEW.account_id;
    END IF;

    IF NEW.tenant_id <> v_entry.tenant_id THEN
      RAISE EXCEPTION 'Journal line tenant does not match journal entry tenant';
    END IF;

    IF NEW.property_id IS NOT NULL AND NEW.property_id IS DISTINCT FROM v_entry.property_id THEN
      RAISE EXCEPTION 'Journal line property does not match journal entry property';
    END IF;

    IF v_account.tenant_id <> NEW.tenant_id THEN
      RAISE EXCEPTION 'Journal line account tenant does not match line tenant';
    END IF;

    IF v_account.currency <> v_entry.currency THEN
      RAISE EXCEPTION 'Journal line account currency does not match journal entry currency';
    END IF;

    IF NOT v_account.is_active THEN
      RAISE EXCEPTION 'Journal line account % is inactive', NEW.account_id;
    END IF;
  END IF;

  IF TG_OP = 'DELETE' THEN
    RETURN OLD;
  END IF;

  RETURN NEW;
END;
$$;

CREATE FUNCTION sync_journal_entry_totals_from_lines() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
  IF TG_OP = 'INSERT' THEN
    PERFORM recalculate_journal_entry_totals(NEW.journal_entry_id);
  ELSIF TG_OP = 'UPDATE' THEN
    IF OLD.journal_entry_id <> NEW.journal_entry_id THEN
      PERFORM recalculate_journal_entry_totals(OLD.journal_entry_id);
    END IF;
    PERFORM recalculate_journal_entry_totals(NEW.journal_entry_id);
  ELSE
    PERFORM recalculate_journal_entry_totals(OLD.journal_entry_id);
  END IF;

  RETURN NULL;
END;
$$;

CREATE FUNCTION guard_journal_entry_status() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
  v_debit numeric(15,2);
  v_credit numeric(15,2);
  v_line_count integer;
  v_allow_total_update boolean;
BEGIN
  IF TG_OP = 'INSERT' THEN
    IF NEW.status <> 'draft' THEN
      RAISE EXCEPTION 'Journal entries must be created as draft and posted after lines are balanced';
    END IF;
    RETURN NEW;
  END IF;

  v_allow_total_update := COALESCE(current_setting('app.allow_journal_total_update', true) = 'on', false);

  IF OLD.status = 'voided' THEN
    IF (NEW.tenant_id, NEW.property_id, NEW.entry_number, NEW.entry_date, NEW.source_type, NEW.source_id,
        NEW.memo, NEW.currency, NEW.total_debit, NEW.total_credit, NEW.status, NEW.posted_by,
        NEW.posted_at, NEW.voided_by, NEW.voided_at, NEW.created_at)
       IS DISTINCT FROM
       (OLD.tenant_id, OLD.property_id, OLD.entry_number, OLD.entry_date, OLD.source_type, OLD.source_id,
        OLD.memo, OLD.currency, OLD.total_debit, OLD.total_credit, OLD.status, OLD.posted_by,
        OLD.posted_at, OLD.voided_by, OLD.voided_at, OLD.created_at) THEN
      RAISE EXCEPTION 'Voided journal entries are immutable';
    END IF;
    RETURN NEW;
  END IF;

  IF OLD.status = 'posted' THEN
    IF NEW.status = 'voided' THEN
      IF NEW.voided_by IS NULL THEN
        RAISE EXCEPTION 'Voiding a posted journal entry requires voided_by';
      END IF;
      NEW.voided_at := COALESCE(NEW.voided_at, now());

      IF (NEW.tenant_id, NEW.property_id, NEW.entry_number, NEW.entry_date, NEW.source_type, NEW.source_id,
          NEW.memo, NEW.currency, NEW.total_debit, NEW.total_credit, NEW.posted_by, NEW.posted_at, NEW.created_at)
         IS DISTINCT FROM
         (OLD.tenant_id, OLD.property_id, OLD.entry_number, OLD.entry_date, OLD.source_type, OLD.source_id,
          OLD.memo, OLD.currency, OLD.total_debit, OLD.total_credit, OLD.posted_by, OLD.posted_at, OLD.created_at) THEN
        RAISE EXCEPTION 'Posted journal entries can only transition to voided';
      END IF;
    ELSIF NEW.status = 'posted' THEN
      IF (NEW.tenant_id, NEW.property_id, NEW.entry_number, NEW.entry_date, NEW.source_type, NEW.source_id,
          NEW.memo, NEW.currency, NEW.total_debit, NEW.total_credit, NEW.posted_by, NEW.posted_at,
          NEW.voided_by, NEW.voided_at, NEW.created_at)
         IS DISTINCT FROM
         (OLD.tenant_id, OLD.property_id, OLD.entry_number, OLD.entry_date, OLD.source_type, OLD.source_id,
          OLD.memo, OLD.currency, OLD.total_debit, OLD.total_credit, OLD.posted_by, OLD.posted_at,
          OLD.voided_by, OLD.voided_at, OLD.created_at) THEN
        RAISE EXCEPTION 'Posted journal entries are immutable';
      END IF;
    ELSE
      RAISE EXCEPTION 'Posted journal entries can only be voided';
    END IF;

    RETURN NEW;
  END IF;

  IF NOT v_allow_total_update
     AND (NEW.total_debit IS DISTINCT FROM OLD.total_debit
          OR NEW.total_credit IS DISTINCT FROM OLD.total_credit) THEN
    RAISE EXCEPTION 'Journal entry totals are maintained from journal_entry_lines';
  END IF;

  IF OLD.status = 'draft' AND NEW.status = 'posted' THEN
    SELECT
      COALESCE(round(sum(debit), 2), 0),
      COALESCE(round(sum(credit), 2), 0),
      count(*)
    INTO v_debit, v_credit, v_line_count
    FROM journal_entry_lines
    WHERE journal_entry_id = NEW.id;

    IF v_line_count < 2 OR v_debit = 0 OR v_debit <> v_credit THEN
      RAISE EXCEPTION 'Cannot post unbalanced journal entry %: debit %, credit %, lines %',
        NEW.id, v_debit, v_credit, v_line_count;
    END IF;

    IF NEW.posted_by IS NULL THEN
      RAISE EXCEPTION 'Posting a journal entry requires posted_by';
    END IF;

    NEW.total_debit := v_debit;
    NEW.total_credit := v_credit;
    NEW.posted_at := COALESCE(NEW.posted_at, now());
  ELSIF OLD.status = 'draft' AND NEW.status = 'voided' THEN
    IF NEW.voided_by IS NULL THEN
      RAISE EXCEPTION 'Voiding a draft journal entry requires voided_by';
    END IF;
    NEW.voided_at := COALESCE(NEW.voided_at, now());
  ELSIF OLD.status = 'draft' AND NEW.status <> 'draft' THEN
    RAISE EXCEPTION 'Invalid journal status transition from % to %', OLD.status, NEW.status;
  END IF;

  RETURN NEW;
END;
$$;

CREATE FUNCTION prevent_financial_document_delete() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
  RAISE EXCEPTION 'Financial document rows cannot be deleted; use reversal or void status instead';
END;
$$;

CREATE FUNCTION assert_charge_tax_snapshot(p_folio_charge_id uuid) RETURNS void
    LANGUAGE plpgsql
    AS $$
DECLARE
  v_charge_tax numeric(15,2);
  v_child_tax numeric(15,2);
  v_child_count integer;
BEGIN
  SELECT fc.tax_amount
  INTO v_charge_tax
  FROM folio_charges fc
  WHERE fc.id = p_folio_charge_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'Folio charge % does not exist', p_folio_charge_id;
  END IF;

  SELECT count(*), COALESCE(round(sum(fct.tax_amount), 2), 0)
  INTO v_child_count, v_child_tax
  FROM folio_charge_taxes fct
  WHERE fct.folio_charge_id = p_folio_charge_id;

  IF v_child_count > 0 AND v_child_tax <> v_charge_tax THEN
    RAISE EXCEPTION 'Folio charge % tax snapshot % does not match child tax total %',
      p_folio_charge_id, v_charge_tax, v_child_tax;
  END IF;
END;
$$;

CREATE FUNCTION recalculate_folio_totals(p_folio_id uuid) RETURNS void
    LANGUAGE plpgsql
    AS $$
DECLARE
  v_folio record;
  v_subtotal numeric(15,2);
  v_tax numeric(15,2);
  v_charge_total numeric(15,2);
  v_paid numeric(15,2);
BEGIN
  SELECT *
  INTO v_folio
  FROM folios
  WHERE id = p_folio_id
  FOR UPDATE;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'Folio % does not exist', p_folio_id;
  END IF;

  IF v_folio.status <> 'open' THEN
    RAISE EXCEPTION 'Cannot recalculate totals for % folio %', v_folio.status, p_folio_id;
  END IF;

  SELECT
    COALESCE(round(sum(fc.subtotal), 2), 0),
    COALESCE(round(sum(fc.tax_amount), 2), 0),
    COALESCE(round(sum(fc.amount), 2), 0)
  INTO v_subtotal, v_tax, v_charge_total
  FROM folio_charges fc
  WHERE fc.tenant_id = v_folio.tenant_id
    AND fc.folio_id = v_folio.id
    AND fc.status = 'POSTED'
    AND fc.deleted_at IS NULL;

  SELECT COALESCE(round(sum(fp.amount), 2), 0)
  INTO v_paid
  FROM folio_payments fp
  WHERE fp.tenant_id = v_folio.tenant_id
    AND fp.folio_id = v_folio.id
    AND fp.status = 'POSTED'
    AND fp.deleted_at IS NULL;

  UPDATE folios
  SET subtotal = v_subtotal,
      tax_amount = v_tax,
      total_amount = round(v_charge_total + service_charge + tourism_levy, 2),
      total_paid = v_paid,
      updated_at = now()
  WHERE id = v_folio.id;
END;
$$;

CREATE FUNCTION assert_folio_can_close(p_folio_id uuid) RETURNS void
    LANGUAGE plpgsql
    AS $$
DECLARE
  v_folio record;
  v_subtotal numeric(15,2);
  v_tax numeric(15,2);
  v_charge_total numeric(15,2);
  v_paid numeric(15,2);
  v_bad_tax_count integer;
BEGIN
  SELECT *
  INTO v_folio
  FROM folios
  WHERE id = p_folio_id
  FOR UPDATE;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'Folio % does not exist', p_folio_id;
  END IF;

  SELECT
    COALESCE(round(sum(fc.subtotal), 2), 0),
    COALESCE(round(sum(fc.tax_amount), 2), 0),
    COALESCE(round(sum(fc.amount), 2), 0)
  INTO v_subtotal, v_tax, v_charge_total
  FROM folio_charges fc
  WHERE fc.tenant_id = v_folio.tenant_id
    AND fc.folio_id = v_folio.id
    AND fc.status = 'POSTED'
    AND fc.deleted_at IS NULL;

  SELECT COALESCE(round(sum(fp.amount), 2), 0)
  INTO v_paid
  FROM folio_payments fp
  WHERE fp.tenant_id = v_folio.tenant_id
    AND fp.folio_id = v_folio.id
    AND fp.status = 'POSTED'
    AND fp.deleted_at IS NULL;

  IF (v_folio.subtotal, v_folio.tax_amount, v_folio.total_paid, v_folio.total_amount)
     IS DISTINCT FROM
     (v_subtotal, v_tax, v_paid, round(v_charge_total + v_folio.service_charge + v_folio.tourism_levy, 2)) THEN
    RAISE EXCEPTION 'Folio % totals do not reconcile to charges/payments', p_folio_id;
  END IF;

  SELECT count(*)
  INTO v_bad_tax_count
  FROM folio_charges fc
  JOIN (
    SELECT folio_charge_id, round(sum(tax_amount), 2) AS tax_total
    FROM folio_charge_taxes
    GROUP BY folio_charge_id
  ) fct ON fct.folio_charge_id = fc.id
  WHERE fc.tenant_id = v_folio.tenant_id
    AND fc.folio_id = v_folio.id
    AND fc.status = 'POSTED'
    AND fc.tax_amount <> fct.tax_total;

  IF v_bad_tax_count > 0 THEN
    RAISE EXCEPTION 'Folio % has % charge tax snapshots that do not match child tax rows',
      p_folio_id, v_bad_tax_count;
  END IF;
END;
$$;

CREATE FUNCTION recalculate_invoice_totals(p_invoice_id uuid) RETURNS void
    LANGUAGE plpgsql
    AS $$
DECLARE
  v_invoice record;
  v_subtotal numeric(15,2);
  v_vat numeric(15,2);
  v_service numeric(15,2);
  v_tourism numeric(15,2);
  v_tax_rows integer;
BEGIN
  SELECT *
  INTO v_invoice
  FROM invoices
  WHERE id = p_invoice_id
  FOR UPDATE;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'Invoice % does not exist', p_invoice_id;
  END IF;

  IF v_invoice.status <> 'draft' THEN
    RAISE EXCEPTION 'Cannot recalculate totals for % invoice %', v_invoice.status, p_invoice_id;
  END IF;

  SELECT
    COALESCE(round(sum(ii.amount), 2), 0),
    COALESCE(round(sum(ii.vat_amount), 2), 0)
  INTO v_subtotal, v_vat
  FROM invoice_items ii
  WHERE ii.tenant_id = v_invoice.tenant_id
    AND ii.invoice_id = v_invoice.id
    AND ii.status = 'POSTED'
    AND ii.is_reversed = false;

  SELECT
    count(*),
    COALESCE(round(sum(iit.tax_amount) FILTER (WHERE iit.tax_type = 'vat'), 2), v_vat),
    COALESCE(round(sum(iit.tax_amount) FILTER (WHERE iit.tax_type = 'service_charge'), 2), v_invoice.service_charge),
    COALESCE(round(sum(iit.tax_amount) FILTER (WHERE iit.tax_type = 'tourism_levy'), 2), v_invoice.tourism_levy)
  INTO v_tax_rows, v_vat, v_service, v_tourism
  FROM invoice_items ii
  JOIN invoice_item_taxes iit
    ON iit.tenant_id = ii.tenant_id
   AND iit.invoice_item_id = ii.id
  WHERE ii.tenant_id = v_invoice.tenant_id
    AND ii.invoice_id = v_invoice.id
    AND ii.status = 'POSTED'
    AND ii.is_reversed = false;

  UPDATE invoices
  SET subtotal = v_subtotal,
      vat_total = v_vat,
      service_charge = v_service,
      tourism_levy = v_tourism,
      total = round(v_subtotal + v_vat + v_service + v_tourism, 2),
      updated_at = now()
  WHERE id = v_invoice.id;
END;
$$;

CREATE FUNCTION assert_invoice_totals(p_invoice_id uuid) RETURNS void
    LANGUAGE plpgsql
    AS $$
DECLARE
  v_invoice record;
  v_subtotal numeric(15,2);
  v_vat numeric(15,2);
  v_service numeric(15,2);
  v_tourism numeric(15,2);
BEGIN
  SELECT *
  INTO v_invoice
  FROM invoices
  WHERE id = p_invoice_id
  FOR UPDATE;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'Invoice % does not exist', p_invoice_id;
  END IF;

  SELECT
    COALESCE(round(sum(ii.amount), 2), 0),
    COALESCE(round(sum(ii.vat_amount), 2), 0)
  INTO v_subtotal, v_vat
  FROM invoice_items ii
  WHERE ii.tenant_id = v_invoice.tenant_id
    AND ii.invoice_id = v_invoice.id
    AND ii.status = 'POSTED'
    AND ii.is_reversed = false;

  SELECT
    COALESCE(round(sum(iit.tax_amount) FILTER (WHERE iit.tax_type = 'vat'), 2), v_vat),
    COALESCE(round(sum(iit.tax_amount) FILTER (WHERE iit.tax_type = 'service_charge'), 2), v_invoice.service_charge),
    COALESCE(round(sum(iit.tax_amount) FILTER (WHERE iit.tax_type = 'tourism_levy'), 2), v_invoice.tourism_levy)
  INTO v_vat, v_service, v_tourism
  FROM invoice_items ii
  JOIN invoice_item_taxes iit
    ON iit.tenant_id = ii.tenant_id
   AND iit.invoice_item_id = ii.id
  WHERE ii.tenant_id = v_invoice.tenant_id
    AND ii.invoice_id = v_invoice.id
    AND ii.status = 'POSTED'
    AND ii.is_reversed = false;

  IF (v_invoice.subtotal, v_invoice.vat_total, v_invoice.service_charge, v_invoice.tourism_levy, v_invoice.total)
     IS DISTINCT FROM
     (v_subtotal, v_vat, v_service, v_tourism, round(v_subtotal + v_vat + v_service + v_tourism, 2)) THEN
    RAISE EXCEPTION 'Invoice % totals do not reconcile to invoice items/taxes', p_invoice_id;
  END IF;
END;
$$;

CREATE FUNCTION guard_folios_financial_state() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
  v_subtotal numeric(15,2);
  v_tax numeric(15,2);
  v_charge_total numeric(15,2);
  v_paid numeric(15,2);
BEGIN
  IF TG_OP = 'INSERT' THEN
    IF NEW.status = 'closed' THEN
      NEW.closed_at := COALESCE(NEW.closed_at, now());
    END IF;
    RETURN NEW;
  END IF;

  IF OLD.status = 'voided' THEN
    RAISE EXCEPTION 'Voided folios are immutable';
  END IF;

  IF OLD.status = 'closed' THEN
    IF NEW.status = 'voided' THEN
      NEW.closed_at := COALESCE(NEW.closed_at, OLD.closed_at, now());
    ELSIF NEW.status <> 'closed' THEN
      RAISE EXCEPTION 'Closed folios can only be voided';
    END IF;

    IF (NEW.tenant_id, NEW.property_id, NEW.reservation_id, NEW.parent_folio_id, NEW.folio_type,
        NEW.currency_code, NEW.subtotal, NEW.tax_amount, NEW.service_charge, NEW.tourism_levy,
        NEW.total_amount, NEW.total_paid, NEW.opened_at, NEW.created_at, NEW.deleted_at)
       IS DISTINCT FROM
       (OLD.tenant_id, OLD.property_id, OLD.reservation_id, OLD.parent_folio_id, OLD.folio_type,
        OLD.currency_code, OLD.subtotal, OLD.tax_amount, OLD.service_charge, OLD.tourism_levy,
        OLD.total_amount, OLD.total_paid, OLD.opened_at, OLD.created_at, OLD.deleted_at) THEN
      RAISE EXCEPTION 'Closed folios are financially immutable';
    END IF;
  ELSIF OLD.status = 'open' THEN
    IF NEW.status = 'closed' THEN
      SELECT
        COALESCE(round(sum(fc.subtotal), 2), 0),
        COALESCE(round(sum(fc.tax_amount), 2), 0),
        COALESCE(round(sum(fc.amount), 2), 0)
      INTO v_subtotal, v_tax, v_charge_total
      FROM folio_charges fc
      WHERE fc.tenant_id = OLD.tenant_id
        AND fc.folio_id = OLD.id
        AND fc.status = 'POSTED'
        AND fc.deleted_at IS NULL;

      SELECT COALESCE(round(sum(fp.amount), 2), 0)
      INTO v_paid
      FROM folio_payments fp
      WHERE fp.tenant_id = OLD.tenant_id
        AND fp.folio_id = OLD.id
        AND fp.status = 'POSTED'
        AND fp.deleted_at IS NULL;

      NEW.subtotal := v_subtotal;
      NEW.tax_amount := v_tax;
      NEW.total_paid := v_paid;
      NEW.total_amount := round(v_charge_total + NEW.service_charge + NEW.tourism_levy, 2);
      NEW.closed_at := COALESCE(NEW.closed_at, now());
    ELSIF NEW.status = 'voided' THEN
      NEW.closed_at := COALESCE(NEW.closed_at, now());
    ELSIF NEW.status <> 'open' THEN
      RAISE EXCEPTION 'Invalid folio status transition from % to %', OLD.status, NEW.status;
    END IF;
  END IF;

  RETURN NEW;
END;
$$;

CREATE FUNCTION guard_folio_charges_financial_state() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
  v_folio_status text;
BEGIN
  IF TG_OP = 'INSERT' THEN
    SELECT f.status
    INTO v_folio_status
    FROM folios f
    WHERE f.tenant_id = NEW.tenant_id
      AND f.id = NEW.folio_id
    FOR SHARE;

    IF v_folio_status IS NULL THEN
      RAISE EXCEPTION 'Folio % does not exist for tenant %', NEW.folio_id, NEW.tenant_id;
    END IF;

    IF v_folio_status <> 'open' THEN
      RAISE EXCEPTION 'Cannot post a charge to a % folio', v_folio_status;
    END IF;

    IF NEW.status = 'VOIDED' AND NEW.voided_by IS NULL THEN
      RAISE EXCEPTION 'Voided folio charges require voided_by';
    END IF;
    NEW.voided_at := CASE WHEN NEW.status = 'VOIDED' THEN COALESCE(NEW.voided_at, now()) ELSE NEW.voided_at END;
    NEW.is_reversed := NEW.status IN ('REVERSED', 'VOIDED') OR NEW.is_reversed;
    RETURN NEW;
  END IF;

  IF OLD.status IN ('REVERSED', 'VOIDED') THEN
    RAISE EXCEPTION '% folio charges are immutable', OLD.status;
  END IF;

  IF OLD.status = 'POSTED' THEN
    IF NEW.status = 'VOIDED' THEN
      IF NEW.voided_by IS NULL THEN
        RAISE EXCEPTION 'Voiding a folio charge requires voided_by';
      END IF;
      NEW.voided_at := COALESCE(NEW.voided_at, now());
      NEW.is_reversed := true;
    ELSIF NEW.status = 'REVERSED' THEN
      NEW.is_reversed := true;
    ELSIF NEW.status <> 'POSTED' THEN
      RAISE EXCEPTION 'Invalid folio charge status transition from % to %', OLD.status, NEW.status;
    END IF;

    IF (NEW.tenant_id, NEW.property_id, NEW.folio_id, NEW.revenue_center_id, NEW.charge_type, NEW.description, NEW.source_type, NEW.source_id,
        NEW.quantity, NEW.unit_price, NEW.subtotal, NEW.tax_rate, NEW.tax_amount, NEW.amount,
        NEW.posted_at, NEW.posted_by, NEW.created_at, NEW.deleted_at)
       IS DISTINCT FROM
       (OLD.tenant_id, OLD.property_id, OLD.folio_id, OLD.revenue_center_id, OLD.charge_type, OLD.description, OLD.source_type, OLD.source_id,
        OLD.quantity, OLD.unit_price, OLD.subtotal, OLD.tax_rate, OLD.tax_amount, OLD.amount,
        OLD.posted_at, OLD.posted_by, OLD.created_at, OLD.deleted_at) THEN
      RAISE EXCEPTION 'Posted folio charges are financially immutable';
    END IF;
  END IF;

  RETURN NEW;
END;
$$;

CREATE FUNCTION guard_folio_payments_financial_state() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
  v_folio_status text;
BEGIN
  IF TG_OP = 'INSERT' THEN
    SELECT f.status
    INTO v_folio_status
    FROM folios f
    WHERE f.tenant_id = NEW.tenant_id
      AND f.id = NEW.folio_id
    FOR SHARE;

    IF v_folio_status IS NULL THEN
      RAISE EXCEPTION 'Folio % does not exist for tenant %', NEW.folio_id, NEW.tenant_id;
    END IF;

    IF v_folio_status <> 'open' THEN
      RAISE EXCEPTION 'Cannot post a payment to a % folio', v_folio_status;
    END IF;

    NEW.is_reversed := NEW.status = 'REVERSED' OR NEW.is_reversed;
    RETURN NEW;
  END IF;

  IF OLD.status IN ('REVERSED', 'FAILED') THEN
    RAISE EXCEPTION '% folio payments are immutable', OLD.status;
  END IF;

  IF OLD.status = 'POSTED' THEN
    IF NEW.status = 'REVERSED' THEN
      NEW.is_reversed := true;
    ELSIF NEW.status <> 'POSTED' THEN
      RAISE EXCEPTION 'Posted folio payments can only be reversed';
    END IF;

    IF (NEW.tenant_id, NEW.folio_id, NEW.payment_method, NEW.amount, NEW.currency_code,
        NEW.exchange_rate, NEW.reference_number, NEW.idempotency_key, NEW.paid_at,
        NEW.processed_by, NEW.created_by, NEW.created_at, NEW.deleted_at)
       IS DISTINCT FROM
       (OLD.tenant_id, OLD.folio_id, OLD.payment_method, OLD.amount, OLD.currency_code,
        OLD.exchange_rate, OLD.reference_number, OLD.idempotency_key, OLD.paid_at,
        OLD.processed_by, OLD.created_by, OLD.created_at, OLD.deleted_at) THEN
      RAISE EXCEPTION 'Posted folio payments are financially immutable';
    END IF;
  ELSIF OLD.status = 'PENDING' THEN
    IF NEW.status NOT IN ('PENDING', 'POSTED', 'FAILED') THEN
      RAISE EXCEPTION 'Invalid pending payment transition from % to %', OLD.status, NEW.status;
    END IF;
  END IF;

  RETURN NEW;
END;
$$;

CREATE FUNCTION guard_pos_order_state() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
  v_folio_status text;
BEGIN
  IF TG_OP = 'INSERT' AND NEW.folio_id IS NOT NULL THEN
    SELECT f.status
    INTO v_folio_status
    FROM folios f
    WHERE f.tenant_id = NEW.tenant_id
      AND f.id = NEW.folio_id
    FOR SHARE;

    IF v_folio_status IS NULL THEN
      RAISE EXCEPTION 'POS order folio % does not exist for tenant %', NEW.folio_id, NEW.tenant_id;
    END IF;

    IF v_folio_status <> 'open' THEN
      RAISE EXCEPTION 'Cannot link a POS order to a % folio', v_folio_status;
    END IF;

    IF NEW.status = 'closed' THEN
      NEW.settled_at := COALESCE(NEW.settled_at, now());
    END IF;

    RETURN NEW;
  END IF;

  IF TG_OP = 'INSERT' THEN
    IF NEW.status = 'closed' THEN
      NEW.settled_at := COALESCE(NEW.settled_at, now());
    END IF;
    RETURN NEW;
  END IF;

  IF OLD.status IN ('closed', 'voided', 'cancelled') THEN
    IF (NEW.tenant_id, NEW.property_id, NEW.outlet_id, NEW.revenue_center_id, NEW.folio_id,
        NEW.order_number, NEW.table_number, NEW.order_type, NEW.subtotal, NEW.tax_amount,
        NEW.total_amount, NEW.served_by, NEW.settled_at, NEW.edge_created, NEW.edge_sync_id,
        NEW.created_at, NEW.deleted_at)
       IS DISTINCT FROM
       (OLD.tenant_id, OLD.property_id, OLD.outlet_id, OLD.revenue_center_id, OLD.folio_id,
        OLD.order_number, OLD.table_number, OLD.order_type, OLD.subtotal, OLD.tax_amount,
        OLD.total_amount, OLD.served_by, OLD.settled_at, OLD.edge_created, OLD.edge_sync_id,
        OLD.created_at, OLD.deleted_at) THEN
      RAISE EXCEPTION '% POS orders are financially immutable', OLD.status;
    END IF;

    IF NEW.status <> OLD.status THEN
      RAISE EXCEPTION 'Cannot transition a % POS order to %', OLD.status, NEW.status;
    END IF;
  ELSIF OLD.status = 'open' THEN
    IF NEW.status = 'closed' THEN
      NEW.settled_at := COALESCE(NEW.settled_at, now());
    ELSIF NEW.status NOT IN ('open', 'voided', 'cancelled') THEN
      RAISE EXCEPTION 'Invalid POS order status transition from % to %', OLD.status, NEW.status;
    END IF;
  END IF;

  RETURN NEW;
END;
$$;

CREATE FUNCTION apply_stock_movement_to_levels() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
  v_delta numeric(15,3);
  v_new_quantity numeric(15,3);
  v_lock_key bigint;
BEGIN
  IF TG_OP <> 'INSERT' THEN
    RAISE EXCEPTION 'Stock movements are append-only; create a reversing movement instead';
  END IF;

  v_delta := CASE
    WHEN NEW.type IN ('purchase', 'transfer_in', 'return', 'opening_balance') THEN NEW.quantity
    WHEN NEW.type IN ('consumption', 'waste', 'transfer_out') THEN -NEW.quantity
    WHEN NEW.type = 'adjustment' THEN NEW.quantity
    ELSE NEW.quantity
  END;

  v_lock_key := hashtextextended(
    NEW.tenant_id::text || ':' || NEW.item_id::text || ':' || COALESCE(NEW.location_id::text, 'global'),
    0
  );
  PERFORM pg_advisory_xact_lock(v_lock_key);

  UPDATE stock_levels
  SET quantity = quantity + v_delta,
      last_updated_at = now(),
      updated_at = now()
  WHERE tenant_id = NEW.tenant_id
    AND item_id = NEW.item_id
    AND location_id IS NOT DISTINCT FROM NEW.location_id
  RETURNING quantity INTO v_new_quantity;

  IF NOT FOUND THEN
    INSERT INTO stock_levels (tenant_id, item_id, location_id, quantity, reorder_level)
    VALUES (NEW.tenant_id, NEW.item_id, NEW.location_id, v_delta, 0)
    RETURNING quantity INTO v_new_quantity;
  END IF;

  IF v_new_quantity < 0 THEN
    RAISE EXCEPTION 'Stock movement would make item % negative at location %', NEW.item_id, NEW.location_id;
  END IF;

  UPDATE inventory_items ii
  SET current_stock = COALESCE((
        SELECT SUM(sl.quantity)
        FROM stock_levels sl
        WHERE sl.tenant_id = NEW.tenant_id
          AND sl.item_id = NEW.item_id
      ), 0),
      updated_at = now()
  WHERE ii.tenant_id = NEW.tenant_id
    AND ii.id = NEW.item_id;

  RETURN NEW;
END;
$$;

CREATE FUNCTION guard_invoices_financial_state() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
  v_subtotal numeric(15,2);
  v_vat numeric(15,2);
  v_service numeric(15,2);
  v_tourism numeric(15,2);
BEGIN
  IF TG_OP = 'INSERT' THEN
    IF NEW.status <> 'draft' AND NEW.invoice_number_formatted IS NULL THEN
      RAISE EXCEPTION 'Issued invoices require invoice_number_formatted';
    END IF;
    IF NEW.status IN ('issued', 'sent', 'paid', 'overdue') THEN
      NEW.issued_at := COALESCE(NEW.issued_at, now());
    END IF;
    RETURN NEW;
  END IF;

  IF OLD.status = 'voided' THEN
    RAISE EXCEPTION 'Voided invoices are immutable';
  END IF;

  IF OLD.status <> 'draft' THEN
    IF NOT (
      (OLD.status = NEW.status)
      OR (OLD.status = 'issued' AND NEW.status IN ('sent', 'paid', 'overdue', 'voided'))
      OR (OLD.status = 'sent' AND NEW.status IN ('paid', 'overdue', 'voided'))
      OR (OLD.status = 'overdue' AND NEW.status IN ('paid', 'voided'))
      OR (OLD.status = 'paid' AND NEW.status = 'voided')
    ) THEN
      RAISE EXCEPTION 'Invalid invoice status transition from % to %', OLD.status, NEW.status;
    END IF;

    IF (NEW.tenant_id, NEW.property_id, NEW.folio_id, NEW.invoice_number_formatted,
        NEW.subtotal, NEW.vat_total, NEW.service_charge, NEW.tourism_levy, NEW.total,
        NEW.currency_code, NEW.due_date, NEW.created_by, NEW.created_at, NEW.deleted_at,
        NEW.company_id)
       IS DISTINCT FROM
       (OLD.tenant_id, OLD.property_id, OLD.folio_id, OLD.invoice_number_formatted,
        OLD.subtotal, OLD.vat_total, OLD.service_charge, OLD.tourism_levy, OLD.total,
        OLD.currency_code, OLD.due_date, OLD.created_by, OLD.created_at, OLD.deleted_at,
        OLD.company_id) THEN
      RAISE EXCEPTION 'Issued invoices are financially immutable';
    END IF;
  ELSE
    IF NEW.status NOT IN ('draft', 'issued', 'voided') THEN
      RAISE EXCEPTION 'Draft invoices can only be issued or voided';
    END IF;

    IF NEW.status = 'issued' THEN
      SELECT
        COALESCE(round(sum(ii.amount), 2), 0),
        COALESCE(round(sum(ii.vat_amount), 2), 0)
      INTO v_subtotal, v_vat
      FROM invoice_items ii
      WHERE ii.tenant_id = OLD.tenant_id
        AND ii.invoice_id = OLD.id
        AND ii.status = 'POSTED'
        AND ii.is_reversed = false;

      SELECT
        COALESCE(round(sum(iit.tax_amount) FILTER (WHERE iit.tax_type = 'vat'), 2), v_vat),
        COALESCE(round(sum(iit.tax_amount) FILTER (WHERE iit.tax_type = 'service_charge'), 2), NEW.service_charge),
        COALESCE(round(sum(iit.tax_amount) FILTER (WHERE iit.tax_type = 'tourism_levy'), 2), NEW.tourism_levy)
      INTO v_vat, v_service, v_tourism
      FROM invoice_items ii
      JOIN invoice_item_taxes iit
        ON iit.tenant_id = ii.tenant_id
       AND iit.invoice_item_id = ii.id
      WHERE ii.tenant_id = OLD.tenant_id
        AND ii.invoice_id = OLD.id
        AND ii.status = 'POSTED'
        AND ii.is_reversed = false;

      NEW.subtotal := v_subtotal;
      NEW.vat_total := v_vat;
      NEW.service_charge := v_service;
      NEW.tourism_levy := v_tourism;
      NEW.total := round(v_subtotal + v_vat + v_service + v_tourism, 2);
    END IF;
  END IF;

  IF NEW.status IN ('issued', 'sent', 'paid', 'overdue') THEN
    IF NEW.invoice_number_formatted IS NULL THEN
      RAISE EXCEPTION 'Issued invoices require invoice_number_formatted';
    END IF;
    NEW.issued_at := COALESCE(NEW.issued_at, OLD.issued_at, now());
  END IF;

  RETURN NEW;
END;
$$;

CREATE FUNCTION guard_invoice_items_financial_state() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
  v_status text;
  v_old_status text;
BEGIN
  IF TG_OP IN ('UPDATE', 'DELETE') THEN
    SELECT status INTO v_old_status FROM invoices WHERE id = OLD.invoice_id;
    IF v_old_status <> 'draft' THEN
      RAISE EXCEPTION 'Invoice items cannot be changed after invoice is %', v_old_status;
    END IF;
  END IF;

  IF TG_OP IN ('INSERT', 'UPDATE') THEN
    SELECT status INTO v_status FROM invoices WHERE id = NEW.invoice_id;
    IF v_status <> 'draft' THEN
      RAISE EXCEPTION 'Invoice items cannot be added to a % invoice', v_status;
    END IF;
    RETURN NEW;
  END IF;

  RETURN OLD;
END;
$$;

CREATE FUNCTION guard_invoice_item_taxes_financial_state() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
  v_status text;
  v_old_status text;
BEGIN
  IF TG_OP IN ('UPDATE', 'DELETE') THEN
    SELECT i.status INTO v_old_status
    FROM invoices i
    JOIN invoice_items ii ON ii.invoice_id = i.id
    WHERE ii.id = OLD.invoice_item_id;

    IF v_old_status <> 'draft' THEN
      RAISE EXCEPTION 'Invoice item taxes cannot be changed after invoice is %', v_old_status;
    END IF;
  END IF;

  IF TG_OP IN ('INSERT', 'UPDATE') THEN
    SELECT i.status INTO v_status
    FROM invoices i
    JOIN invoice_items ii ON ii.invoice_id = i.id
    WHERE ii.id = NEW.invoice_item_id;

    IF v_status <> 'draft' THEN
      RAISE EXCEPTION 'Invoice item taxes cannot be added to a % invoice', v_status;
    END IF;
    RETURN NEW;
  END IF;

  RETURN OLD;
END;
$$;

CREATE FUNCTION guard_folio_charge_taxes_financial_state() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
  v_status text;
BEGIN
  SELECT status INTO v_status
  FROM folio_charges
  WHERE id = CASE WHEN TG_OP = 'DELETE' THEN OLD.folio_charge_id ELSE NEW.folio_charge_id END;

  IF TG_OP <> 'INSERT' THEN
    RAISE EXCEPTION 'Folio charge tax rows are immutable';
  END IF;

  IF v_status <> 'POSTED' THEN
    RAISE EXCEPTION 'Folio charge taxes cannot be added after charge is %', v_status;
  END IF;

  RETURN NEW;
END;
$$;

CREATE FUNCTION guard_fiscal_receipts_financial_state() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
  IF TG_OP = 'INSERT' THEN
    RETURN NEW;
  END IF;

  IF OLD.status IN ('accepted', 'rejected') THEN
    RAISE EXCEPTION '% fiscal receipts are immutable', OLD.status;
  END IF;

  IF NEW.status NOT IN ('pending', 'submitted', 'accepted', 'rejected') THEN
    RAISE EXCEPTION 'Invalid fiscal receipt status %', NEW.status;
  END IF;

  IF (NEW.tenant_id, NEW.invoice_id, NEW.fiscal_mode, NEW.receipt_number, NEW.submitted_at, NEW.created_at)
     IS DISTINCT FROM
     (OLD.tenant_id, OLD.invoice_id, OLD.fiscal_mode, OLD.receipt_number, OLD.submitted_at, OLD.created_at) THEN
    RAISE EXCEPTION 'Fiscal receipt identity fields are immutable after submission';
  END IF;

  RETURN NEW;
END;
$$;


-- ================================================================================
-- 3. PLATFORM — Subscription Plans
-- ================================================================================

-- plans
CREATE TABLE plans (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    name text NOT NULL,
    code text NOT NULL,
    max_properties integer DEFAULT 1 NOT NULL,
    max_rooms integer DEFAULT 50 NOT NULL,
    max_users integer DEFAULT 10 NOT NULL,
    max_outlets integer DEFAULT 2 NOT NULL,
    features jsonb DEFAULT '{}'::jsonb NOT NULL,
    monthly_usd numeric(10,2) DEFAULT 0 NOT NULL,
    annual_usd numeric(10,2) DEFAULT 0 NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


-- platform_users
CREATE TABLE platform_users (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    full_name text NOT NULL,
    email text NOT NULL,
    password_hash text,
    status character varying(20) DEFAULT 'active' NOT NULL,
    last_login_at timestamp with time zone,
    failed_attempts integer DEFAULT 0 NOT NULL,
    locked_until timestamp with time zone,
    must_change_pw boolean DEFAULT false NOT NULL,
    mfa_enabled boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone,
    CONSTRAINT chk_platform_users_status CHECK (((status)::text = ANY ((ARRAY['active', 'invited', 'locked', 'disabled'])::text[])))
);


-- platform_roles
CREATE TABLE platform_roles (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    name text NOT NULL,
    code text NOT NULL,
    description text,
    is_system boolean DEFAULT false NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


-- platform_permissions
CREATE TABLE platform_permissions (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    code text NOT NULL,
    description text,
    namespace text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_platform_permissions_namespace CHECK ((namespace = ANY (ARRAY['platform', 'tenant', 'support', 'monitoring', 'billing', 'security'])))
);


-- platform_role_permissions
CREATE TABLE platform_role_permissions (
    platform_role_id uuid NOT NULL,
    platform_permission_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


-- platform_user_roles
CREATE TABLE platform_user_roles (
    platform_user_id uuid NOT NULL,
    platform_role_id uuid NOT NULL,
    assigned_by uuid,
    assigned_at timestamp with time zone DEFAULT now() NOT NULL
);


-- platform_sessions
CREATE TABLE platform_sessions (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    platform_user_id uuid NOT NULL,
    token_hash text NOT NULL,
    device_info jsonb DEFAULT '{}'::jsonb NOT NULL,
    ip_address inet,
    expires_at timestamp with time zone NOT NULL,
    revoked_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


-- platform_audit_logs
CREATE TABLE platform_audit_logs (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    platform_user_id uuid,
    action text NOT NULL,
    entity_type text NOT NULL,
    entity_id uuid,
    tenant_id uuid,
    old_values jsonb,
    new_values jsonb,
    ip_address inet,
    user_agent text,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


-- plan_entitlements
CREATE TABLE plan_entitlements (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    plan_id uuid NOT NULL,
    entitlement_code text NOT NULL,
    entitlement_value jsonb DEFAULT '{}'::jsonb NOT NULL,
    is_enabled boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


-- tenant_lifecycle_events
CREATE TABLE tenant_lifecycle_events (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    event_type character varying(40) NOT NULL,
    status character varying(30) DEFAULT 'completed' NOT NULL,
    reason text,
    metadata jsonb DEFAULT '{}'::jsonb NOT NULL,
    performed_by_platform_user_id uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_tenant_lifecycle_events_status CHECK (((status)::text = ANY ((ARRAY['pending', 'completed', 'failed', 'cancelled'])::text[]))),
    CONSTRAINT chk_tenant_lifecycle_events_type CHECK (((event_type)::text = ANY ((ARRAY['created', 'activated', 'suspended', 'frozen', 'unfrozen', 'reactivated', 'archived', 'restored', 'terminated', 'cancelled', 'plan_changed', 'trial_extended', 'data_exported'])::text[])))
);


-- tenant_usage_snapshots
CREATE TABLE tenant_usage_snapshots (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    snapshot_date date DEFAULT CURRENT_DATE NOT NULL,
    property_count integer DEFAULT 0 NOT NULL,
    room_count integer DEFAULT 0 NOT NULL,
    user_count integer DEFAULT 0 NOT NULL,
    outlet_count integer DEFAULT 0 NOT NULL,
    storage_bytes bigint DEFAULT 0 NOT NULL,
    api_calls integer DEFAULT 0 NOT NULL,
    metrics jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_tenant_usage_snapshots_counts CHECK (((property_count >= 0) AND (room_count >= 0) AND (user_count >= 0) AND (outlet_count >= 0) AND (storage_bytes >= 0) AND (api_calls >= 0)))
);


-- feature_flags
CREATE TABLE feature_flags (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    flag_key text NOT NULL,
    description text,
    scope character varying(20) DEFAULT 'platform' NOT NULL,
    tenant_id uuid,
    property_id uuid,
    is_enabled boolean DEFAULT false NOT NULL,
    rollout_rules jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_by uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_feature_flags_scope CHECK (((scope)::text = ANY ((ARRAY['platform', 'tenant', 'property'])::text[]))),
    CONSTRAINT chk_feature_flags_scope_target CHECK (((((scope)::text = 'platform'::text) AND (tenant_id IS NULL) AND (property_id IS NULL)) OR (((scope)::text = 'tenant'::text) AND (tenant_id IS NOT NULL) AND (property_id IS NULL)) OR (((scope)::text = 'property'::text) AND (tenant_id IS NOT NULL) AND (property_id IS NOT NULL))))
);


-- maintenance_windows
CREATE TABLE maintenance_windows (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    title text NOT NULL,
    scope character varying(20) DEFAULT 'platform' NOT NULL,
    tenant_id uuid,
    property_id uuid,
    starts_at timestamp with time zone NOT NULL,
    ends_at timestamp with time zone NOT NULL,
    status character varying(20) DEFAULT 'scheduled' NOT NULL,
    impact text,
    created_by uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_maintenance_windows_dates CHECK ((ends_at > starts_at)),
    CONSTRAINT chk_maintenance_windows_scope CHECK (((scope)::text = ANY ((ARRAY['platform', 'tenant', 'property'])::text[]))),
    CONSTRAINT chk_maintenance_windows_scope_target CHECK (((((scope)::text = 'platform'::text) AND (tenant_id IS NULL) AND (property_id IS NULL)) OR (((scope)::text = 'tenant'::text) AND (tenant_id IS NOT NULL) AND (property_id IS NULL)) OR (((scope)::text = 'property'::text) AND (tenant_id IS NOT NULL) AND (property_id IS NOT NULL)))),
    CONSTRAINT chk_maintenance_windows_status CHECK (((status)::text = ANY ((ARRAY['scheduled', 'in_progress', 'completed', 'cancelled'])::text[])))
);


-- support_tickets
CREATE TABLE support_tickets (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid,
    property_id uuid,
    opened_by_user_id uuid,
    assigned_platform_user_id uuid,
    ticket_number text NOT NULL,
    subject text NOT NULL,
    description text,
    priority character varying(20) DEFAULT 'normal' NOT NULL,
    status character varying(20) DEFAULT 'open' NOT NULL,
    category character varying(40) DEFAULT 'general' NOT NULL,
    metadata jsonb DEFAULT '{}'::jsonb NOT NULL,
    opened_at timestamp with time zone DEFAULT now() NOT NULL,
    resolved_at timestamp with time zone,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_support_tickets_priority CHECK (((priority)::text = ANY ((ARRAY['low', 'normal', 'high', 'urgent'])::text[]))),
    CONSTRAINT chk_support_tickets_status CHECK (((status)::text = ANY ((ARRAY['open', 'triaged', 'in_progress', 'waiting_customer', 'resolved', 'closed'])::text[])))
);


-- support_ticket_notes
CREATE TABLE support_ticket_notes (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    ticket_id uuid NOT NULL,
    platform_user_id uuid,
    tenant_user_id uuid,
    note text NOT NULL,
    visibility character varying(20) DEFAULT 'internal' NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_support_ticket_notes_visibility CHECK (((visibility)::text = ANY ((ARRAY['internal', 'customer'])::text[])))
);


-- platform_break_glass_access
CREATE TABLE platform_break_glass_access (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    platform_user_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    action_code text NOT NULL,
    reason text NOT NULL,
    status character varying(20) DEFAULT 'requested' NOT NULL,
    requested_at timestamp with time zone DEFAULT now() NOT NULL,
    approved_by uuid,
    approved_at timestamp with time zone,
    activated_at timestamp with time zone,
    denied_at timestamp with time zone,
    starts_at timestamp with time zone DEFAULT now() NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    revoked_at timestamp with time zone,
    decision_reason text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_platform_break_glass_access_dates CHECK ((expires_at > starts_at)),
    CONSTRAINT chk_platform_break_glass_access_approval CHECK ((((status)::text <> ALL (ARRAY['approved'::text, 'active'::text])) OR ((approved_by IS NOT NULL) AND (approved_at IS NOT NULL)))),
    CONSTRAINT chk_platform_break_glass_access_no_self_approval CHECK (((approved_by IS NULL) OR (approved_by <> platform_user_id))),
    CONSTRAINT chk_platform_break_glass_access_active CHECK ((((status)::text <> 'active'::text) OR ((approved_by IS NOT NULL) AND (approved_at IS NOT NULL) AND (activated_at IS NOT NULL) AND (revoked_at IS NULL) AND (denied_at IS NULL)))),
    CONSTRAINT chk_platform_break_glass_access_denied CHECK ((((status)::text <> 'denied'::text) OR (denied_at IS NOT NULL))),
    CONSTRAINT chk_platform_break_glass_access_revoked CHECK ((((status)::text <> 'revoked'::text) OR (revoked_at IS NOT NULL))),
    CONSTRAINT chk_platform_break_glass_access_status CHECK (((status)::text = ANY ((ARRAY['requested', 'approved', 'active', 'denied', 'revoked', 'expired'])::text[])))
);


-- platform_services
CREATE TABLE platform_services (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    service_key text NOT NULL,
    name text NOT NULL,
    service_type character varying(30) NOT NULL,
    owner_team text,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_platform_services_type CHECK (((service_type)::text = ANY ((ARRAY['api', 'worker', 'database', 'integration', 'frontend', 'edge', 'other'])::text[])))
);


-- service_health_checks
CREATE TABLE service_health_checks (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    service_id uuid NOT NULL,
    checked_at timestamp with time zone DEFAULT now() NOT NULL,
    status character varying(20) NOT NULL,
    latency_ms integer,
    details jsonb DEFAULT '{}'::jsonb NOT NULL,
    CONSTRAINT chk_service_health_checks_latency CHECK (((latency_ms IS NULL) OR (latency_ms >= 0))),
    CONSTRAINT chk_service_health_checks_status CHECK (((status)::text = ANY ((ARRAY['healthy', 'degraded', 'down', 'unknown'])::text[])))
);


-- platform_jobs
CREATE TABLE platform_jobs (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    job_key text NOT NULL,
    service_id uuid,
    description text,
    schedule_cron text,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


-- platform_job_runs
CREATE TABLE platform_job_runs (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    job_id uuid NOT NULL,
    tenant_id uuid,
    status character varying(20) DEFAULT 'queued' NOT NULL,
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    duration_ms integer,
    error_message text,
    metadata jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_platform_job_runs_duration CHECK (((duration_ms IS NULL) OR (duration_ms >= 0))),
    CONSTRAINT chk_platform_job_runs_status CHECK (((status)::text = ANY ((ARRAY['queued', 'running', 'succeeded', 'failed', 'cancelled'])::text[])))
);


-- platform_alerts
CREATE TABLE platform_alerts (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    service_id uuid,
    tenant_id uuid,
    alert_key text NOT NULL,
    severity character varying(20) DEFAULT 'warning' NOT NULL,
    status character varying(20) DEFAULT 'open' NOT NULL,
    title text NOT NULL,
    body text,
    opened_at timestamp with time zone DEFAULT now() NOT NULL,
    acknowledged_by uuid,
    acknowledged_at timestamp with time zone,
    resolved_at timestamp with time zone,
    metadata jsonb DEFAULT '{}'::jsonb NOT NULL,
    CONSTRAINT chk_platform_alerts_severity CHECK (((severity)::text = ANY ((ARRAY['info', 'warning', 'critical'])::text[]))),
    CONSTRAINT chk_platform_alerts_status CHECK (((status)::text = ANY ((ARRAY['open', 'acknowledged', 'resolved', 'suppressed'])::text[])))
);


-- platform_incidents
CREATE TABLE platform_incidents (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    incident_number text NOT NULL,
    title text NOT NULL,
    severity character varying(20) DEFAULT 'sev3' NOT NULL,
    status character varying(20) DEFAULT 'open' NOT NULL,
    started_at timestamp with time zone DEFAULT now() NOT NULL,
    resolved_at timestamp with time zone,
    summary text,
    owner_platform_user_id uuid,
    metadata jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_platform_incidents_severity CHECK (((severity)::text = ANY ((ARRAY['sev1', 'sev2', 'sev3', 'sev4'])::text[]))),
    CONSTRAINT chk_platform_incidents_status CHECK (((status)::text = ANY ((ARRAY['open', 'investigating', 'monitoring', 'resolved', 'closed'])::text[])))
);


-- schema_version_history
CREATE TABLE schema_version_history (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    version_key text NOT NULL,
    description text,
    checksum text,
    applied_at timestamp with time zone DEFAULT now() NOT NULL,
    applied_by text,
    metadata jsonb DEFAULT '{}'::jsonb NOT NULL
);


-- ================================================================================
-- 4. TENANT MANAGEMENT
-- ================================================================================

-- tenants
CREATE TABLE tenants (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    name text NOT NULL,
    slug character varying(100) NOT NULL,
    tin_number text,
    status character varying(20) DEFAULT 'trial' NOT NULL,
    schema_name character varying(100) NOT NULL,
    country_code character(2),
    currency_code character(3),
    fiscal_mode character varying(20) DEFAULT 'NONE' NOT NULL,
    property_count_estimate integer DEFAULT 1 NOT NULL,
    billing_cycle character varying(20) DEFAULT 'monthly' NOT NULL,
    db_mode character varying(20) DEFAULT 'standard' NOT NULL,
    has_dedicated_db boolean DEFAULT false NOT NULL,
    subscription_starts_at timestamp with time zone,
    subscription_ends_at timestamp with time zone,
    trial_ends_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone,
    plan_id uuid NOT NULL,
    CONSTRAINT chk_tenants_billing_cycle CHECK (((billing_cycle)::text = ANY ((ARRAY['monthly', 'annually'])::text[]))),
    CONSTRAINT chk_tenants_db_mode CHECK (((db_mode)::text = ANY ((ARRAY['standard', 'dedicated'])::text[]))),
    CONSTRAINT chk_tenants_dedicated_db_consistency CHECK ((has_dedicated_db = ((db_mode)::text = 'dedicated'::text))),
    CONSTRAINT chk_tenants_fiscal_mode CHECK (((fiscal_mode)::text = ANY ((ARRAY['NONE', 'TRA_EFD', 'KRA_ETR', 'ZIMRA_FDMS', 'MANUAL'])::text[]))),
    CONSTRAINT chk_tenants_property_count_estimate_positive CHECK ((property_count_estimate > 0)),
    CONSTRAINT chk_tenants_status CHECK (((status)::text = ANY ((ARRAY['trial', 'active', 'suspended', 'frozen', 'archived', 'terminated', 'cancelled'])::text[])))
);


-- tenant_configs
CREATE TABLE tenant_configs (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    timezone character varying(50) DEFAULT 'Africa/Dar_es_Salaam' NOT NULL,
    vat_rate numeric(5,2) DEFAULT 18.00 NOT NULL,
    tourism_levy_rate numeric(5,2) DEFAULT 0.00 NOT NULL,
    service_charge_rate numeric(5,2) DEFAULT 0.00 NOT NULL,
    check_in_time time without time zone DEFAULT '14:00:00'::time without time zone NOT NULL,
    check_out_time time without time zone DEFAULT '11:00:00'::time without time zone NOT NULL,
    date_format character varying(20) DEFAULT 'DD/MM/YYYY' NOT NULL,
    default_language character varying(10) DEFAULT 'en' NOT NULL,
    fiscal_mode character varying(20) DEFAULT 'NONE' NOT NULL,
    vfd_secret_ref text,
    vfd_routing_key text,
    timezone_sync_mode character varying(30) DEFAULT 'automatic_ntp' NOT NULL,
    integration_mode character varying(30) DEFAULT 'vfd_api' NOT NULL,
    receipt_footer text,
    settings jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_tenant_configs_fiscal_mode CHECK (((fiscal_mode)::text = ANY ((ARRAY['NONE', 'TRA_EFD', 'KRA_ETR', 'ZIMRA_FDMS', 'MANUAL'])::text[]))),
    CONSTRAINT chk_tenant_configs_vfd_secret_ref CHECK ((((fiscal_mode)::text = 'NONE'::text) OR (vfd_secret_ref IS NOT NULL))),
    CONSTRAINT chk_tenant_configs_integration_mode CHECK (((integration_mode)::text = ANY ((ARRAY['vfd_api', 'physical_efd'])::text[]))),
    CONSTRAINT chk_tenant_configs_timezone_sync_mode CHECK (((timezone_sync_mode)::text = ANY ((ARRAY['automatic_ntp', 'manual_system_time'])::text[])))
);


-- tenant_profiles
CREATE TABLE tenant_profiles (
    tenant_id uuid NOT NULL,
    legal_name text NOT NULL,
    trading_name text,
    entity_type character varying(40) NOT NULL,
    registration_country_code character(2) DEFAULT 'TZ' NOT NULL,
    business_registration_number text,
    vrn_number text,
    business_license_number text,
    hospitality_license_number text,
    website_url text,
    business_phone text NOT NULL,
    business_email text NOT NULL,
    registered_address jsonb DEFAULT '{}'::jsonb NOT NULL,
    billing_address jsonb DEFAULT '{}'::jsonb NOT NULL,
    verification_level character varying(30) DEFAULT 'basic' NOT NULL,
    verification_status character varying(30) DEFAULT 'unverified' NOT NULL,
    verified_at timestamp with time zone,
    verified_by_platform_user_id uuid,
    verification_expires_at timestamp with time zone,
    rejection_reason text,
    metadata jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT tenant_profiles_pkey PRIMARY KEY (tenant_id),
    CONSTRAINT chk_tenant_profiles_email CHECK ((business_email ~* '^[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}$')),
    CONSTRAINT chk_tenant_profiles_entity_type CHECK (((entity_type)::text = ANY ((ARRAY['sole_proprietor', 'partnership', 'limited_company', 'public_company', 'ngo', 'government', 'other'])::text[]))),
    CONSTRAINT chk_tenant_profiles_phone CHECK ((business_phone ~ '^\+[1-9][0-9]{7,14}$')),
    CONSTRAINT chk_tenant_profiles_verification_level CHECK (((verification_level)::text = ANY ((ARRAY['basic', 'standard', 'enhanced'])::text[]))),
    CONSTRAINT chk_tenant_profiles_verification_status CHECK (((verification_status)::text = ANY ((ARRAY['unverified', 'pending', 'approved', 'verified', 'rejected', 'suspended', 'expired'])::text[]))),
    CONSTRAINT chk_tenant_profiles_verified_state CHECK ((((verification_status)::text <> ALL (ARRAY['approved'::text, 'verified'::text])) OR ((verified_at IS NOT NULL) AND (verified_by_platform_user_id IS NOT NULL)))),
    CONSTRAINT chk_tenant_profiles_rejection_reason CHECK ((((verification_status)::text <> 'rejected'::text) OR (rejection_reason IS NOT NULL)))
);


-- contact_role_catalog
CREATE TABLE contact_role_catalog (
    role_code text NOT NULL,
    name text NOT NULL,
    description text,
    scope character varying(20) DEFAULT 'tenant' NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    display_order integer DEFAULT 100 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT contact_role_catalog_pkey PRIMARY KEY (role_code),
    CONSTRAINT chk_contact_role_catalog_scope CHECK (((scope)::text = ANY ((ARRAY['tenant', 'property', 'both'])::text[])))
);


-- tenant_contacts
CREATE TABLE tenant_contacts (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    user_id uuid,
    full_name text NOT NULL,
    job_title text,
    department_name text,
    preferred_language character varying(10) DEFAULT 'en' NOT NULL,
    status character varying(20) DEFAULT 'active' NOT NULL,
    is_primary_contact boolean DEFAULT false NOT NULL,
    notes text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone,
    CONSTRAINT tenant_contacts_pkey PRIMARY KEY (id),
    CONSTRAINT tenant_contacts_tenant_id_id_key UNIQUE (tenant_id, id),
    CONSTRAINT chk_tenant_contacts_status CHECK (((status)::text = ANY ((ARRAY['active', 'inactive', 'invited', 'archived'])::text[])))
);


-- tenant_contact_roles
CREATE TABLE tenant_contact_roles (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    contact_id uuid NOT NULL,
    property_id uuid,
    role_code text NOT NULL,
    is_primary_for_role boolean DEFAULT false NOT NULL,
    effective_from timestamp with time zone DEFAULT now() NOT NULL,
    effective_to timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    CONSTRAINT tenant_contact_roles_pkey PRIMARY KEY (id),
    CONSTRAINT tenant_contact_roles_tenant_id_id_key UNIQUE (tenant_id, id),
    CONSTRAINT chk_tenant_contact_roles_dates CHECK (((effective_to IS NULL) OR (effective_to > effective_from)))
);


-- contact_channels
CREATE TABLE contact_channels (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    contact_id uuid NOT NULL,
    channel_type character varying(20) NOT NULL,
    address text NOT NULL,
    normalized_address text NOT NULL,
    label text,
    is_primary boolean DEFAULT false NOT NULL,
    verification_status character varying(20) DEFAULT 'unverified' NOT NULL,
    verified_at timestamp with time zone,
    verification_method character varying(40),
    verification_token_hash text,
    verification_expires_at timestamp with time zone,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone,
    CONSTRAINT contact_channels_pkey PRIMARY KEY (id),
    CONSTRAINT contact_channels_tenant_id_id_key UNIQUE (tenant_id, id),
    CONSTRAINT contact_channels_tenant_contact_id_key UNIQUE (tenant_id, contact_id, id),
    CONSTRAINT chk_contact_channels_type CHECK (((channel_type)::text = ANY ((ARRAY['email', 'sms', 'whatsapp', 'voice_phone'])::text[]))),
    CONSTRAINT chk_contact_channels_verification_status CHECK (((verification_status)::text = ANY ((ARRAY['unverified', 'pending', 'verified', 'failed', 'expired', 'revoked'])::text[]))),
    CONSTRAINT chk_contact_channels_token_hash CHECK (((verification_token_hash IS NULL) OR (length(verification_token_hash) >= 32)))
);


-- communication_consents
CREATE TABLE communication_consents (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    contact_id uuid NOT NULL,
    contact_channel_id uuid NOT NULL,
    purpose character varying(50) NOT NULL,
    status character varying(20) NOT NULL,
    policy_version text NOT NULL,
    capture_source character varying(40) NOT NULL,
    captured_at timestamp with time zone DEFAULT now() NOT NULL,
    captured_by uuid,
    revoked_at timestamp with time zone,
    revoked_by uuid,
    expires_at timestamp with time zone,
    evidence_metadata jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT communication_consents_pkey PRIMARY KEY (id),
    CONSTRAINT communication_consents_tenant_id_id_key UNIQUE (tenant_id, id),
    CONSTRAINT chk_communication_consents_capture_source CHECK (((capture_source)::text = ANY ((ARRAY['onboarding', 'settings', 'support', 'import', 'api'])::text[]))),
    CONSTRAINT chk_communication_consents_purpose CHECK (((purpose)::text = ANY ((ARRAY['operational_reports', 'critical_operational_alerts', 'billing_communications', 'security_notifications', 'service_notifications', 'marketing'])::text[]))),
    CONSTRAINT chk_communication_consents_status CHECK (((status)::text = ANY ((ARRAY['active', 'declined', 'revoked', 'expired'])::text[]))),
    CONSTRAINT chk_communication_consents_expiry CHECK (((expires_at IS NULL) OR (expires_at > captured_at)))
);


-- tenant_verification_cases
CREATE TABLE tenant_verification_cases (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    case_type character varying(40) NOT NULL,
    required_level character varying(30) DEFAULT 'standard' NOT NULL,
    status character varying(30) DEFAULT 'draft' NOT NULL,
    risk_rating character varying(20) DEFAULT 'low' NOT NULL,
    submitted_at timestamp with time zone,
    submitted_by_user_id uuid,
    assigned_platform_user_id uuid,
    review_started_at timestamp with time zone,
    reviewed_at timestamp with time zone,
    approved_at timestamp with time zone,
    approved_by_platform_user_id uuid,
    rejected_at timestamp with time zone,
    rejected_by_platform_user_id uuid,
    rejection_reason text,
    expires_at timestamp with time zone,
    metadata jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT tenant_verification_cases_pkey PRIMARY KEY (id),
    CONSTRAINT tenant_verification_cases_tenant_id_id_key UNIQUE (tenant_id, id),
    CONSTRAINT chk_tenant_verification_cases_case_type CHECK (((case_type)::text = ANY ((ARRAY['initial_onboarding', 'annual_review', 'license_refresh', 'risk_review', 'reactivation'])::text[]))),
    CONSTRAINT chk_tenant_verification_cases_required_level CHECK (((required_level)::text = ANY ((ARRAY['basic', 'standard', 'enhanced'])::text[]))),
    CONSTRAINT chk_tenant_verification_cases_risk CHECK (((risk_rating)::text = ANY ((ARRAY['low', 'medium', 'high', 'critical'])::text[]))),
    CONSTRAINT chk_tenant_verification_cases_status CHECK (((status)::text = ANY ((ARRAY['draft', 'submitted', 'under_review', 'needs_information', 'approved', 'rejected', 'suspended', 'expired'])::text[]))),
    CONSTRAINT chk_tenant_verification_cases_submitted CHECK ((((status)::text = 'draft'::text) OR (submitted_at IS NOT NULL))),
    CONSTRAINT chk_tenant_verification_cases_approved CHECK ((((status)::text <> 'approved'::text) OR ((assigned_platform_user_id IS NOT NULL) AND (approved_at IS NOT NULL) AND (approved_by_platform_user_id IS NOT NULL) AND (reviewed_at IS NOT NULL)))),
    CONSTRAINT chk_tenant_verification_cases_rejected CHECK ((((status)::text <> 'rejected'::text) OR ((rejected_at IS NOT NULL) AND (rejected_by_platform_user_id IS NOT NULL) AND (rejection_reason IS NOT NULL))))
);


-- tenant_verification_documents
CREATE TABLE tenant_verification_documents (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    verification_case_id uuid NOT NULL,
    document_type character varying(60) NOT NULL,
    document_number_masked text,
    storage_object_key text NOT NULL,
    content_hash text NOT NULL,
    mime_type text NOT NULL,
    issued_at date,
    expires_at date,
    status character varying(30) DEFAULT 'submitted' NOT NULL,
    verified_at timestamp with time zone,
    verified_by_platform_user_id uuid,
    rejection_reason text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT tenant_verification_documents_pkey PRIMARY KEY (id),
    CONSTRAINT tenant_verification_documents_tenant_id_id_key UNIQUE (tenant_id, id),
    CONSTRAINT chk_tenant_verification_documents_dates CHECK (((expires_at IS NULL) OR (issued_at IS NULL) OR (expires_at > issued_at))),
    CONSTRAINT chk_tenant_verification_documents_hash CHECK ((content_hash ~ '^[0-9a-f]{64}$')),
    CONSTRAINT chk_tenant_verification_documents_status CHECK (((status)::text = ANY ((ARRAY['submitted', 'approved', 'rejected', 'expired', 'superseded'])::text[]))),
    CONSTRAINT chk_tenant_verification_documents_type CHECK (((document_type)::text = ANY ((ARRAY['business_registration', 'tax_identification', 'vat_registration', 'business_license', 'hospitality_license', 'authorized_signatory_id', 'bank_letter', 'other'])::text[]))),
    CONSTRAINT chk_tenant_verification_documents_verified CHECK ((((status)::text <> 'approved'::text) OR ((verified_at IS NOT NULL) AND (verified_by_platform_user_id IS NOT NULL))))
);


-- report_catalog
CREATE TABLE report_catalog (
    report_code text NOT NULL,
    module_id character varying(50) NOT NULL,
    name text NOT NULL,
    description text,
    scope character varying(20) NOT NULL,
    sensitivity_level character varying(20) DEFAULT 'internal' NOT NULL,
    supports_email boolean DEFAULT true NOT NULL,
    supports_sms boolean DEFAULT false NOT NULL,
    supports_whatsapp boolean DEFAULT false NOT NULL,
    supports_in_app boolean DEFAULT true NOT NULL,
    default_format character varying(20) DEFAULT 'pdf' NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    display_order integer DEFAULT 100 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT report_catalog_pkey PRIMARY KEY (report_code),
    CONSTRAINT chk_report_catalog_format CHECK (((default_format)::text = ANY ((ARRAY['pdf', 'csv', 'xlsx', 'json', 'html'])::text[]))),
    CONSTRAINT chk_report_catalog_scope CHECK (((scope)::text = ANY ((ARRAY['tenant', 'property', 'both', 'platform'])::text[]))),
    CONSTRAINT chk_report_catalog_sensitivity CHECK (((sensitivity_level)::text = ANY ((ARRAY['public', 'internal', 'confidential', 'regulated'])::text[])))
);


-- report_subscriptions
CREATE TABLE report_subscriptions (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    property_id uuid,
    report_code text NOT NULL,
    subscription_name text NOT NULL,
    scope character varying(20) NOT NULL,
    frequency character varying(30) NOT NULL,
    schedule_time time without time zone,
    timezone character varying(50) DEFAULT 'Africa/Dar_es_Salaam' NOT NULL,
    days_of_week smallint[] DEFAULT '{}'::smallint[] NOT NULL,
    day_of_month smallint,
    business_date_offset integer DEFAULT 0 NOT NULL,
    language_code character varying(10) DEFAULT 'en' NOT NULL,
    default_format character varying(20) DEFAULT 'pdf' NOT NULL,
    status character varying(20) DEFAULT 'active' NOT NULL,
    next_run_at timestamp with time zone,
    last_run_at timestamp with time zone,
    created_by uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone,
    CONSTRAINT report_subscriptions_pkey PRIMARY KEY (id),
    CONSTRAINT report_subscriptions_tenant_id_id_key UNIQUE (tenant_id, id),
    CONSTRAINT chk_report_subscriptions_day_of_month CHECK (((day_of_month IS NULL) OR ((day_of_month >= 1) AND (day_of_month <= 31)))),
    CONSTRAINT chk_report_subscriptions_format CHECK (((default_format)::text = ANY ((ARRAY['pdf', 'csv', 'xlsx', 'json', 'html'])::text[]))),
    CONSTRAINT chk_report_subscriptions_frequency CHECK (((frequency)::text = ANY ((ARRAY['daily', 'weekly', 'monthly', 'after_night_audit', 'event_driven'])::text[]))),
    CONSTRAINT chk_report_subscriptions_scope CHECK (((scope)::text = ANY ((ARRAY['tenant', 'property'])::text[]))),
    CONSTRAINT chk_report_subscriptions_scope_property CHECK ((((scope)::text = 'tenant'::text AND property_id IS NULL) OR ((scope)::text = 'property'::text AND property_id IS NOT NULL))),
    CONSTRAINT chk_report_subscriptions_status CHECK (((status)::text = ANY ((ARRAY['active', 'paused', 'disabled', 'archived'])::text[])))
);


-- report_subscription_recipients
CREATE TABLE report_subscription_recipients (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    subscription_id uuid NOT NULL,
    contact_id uuid NOT NULL,
    contact_channel_id uuid NOT NULL,
    delivery_format character varying(20) DEFAULT 'pdf' NOT NULL,
    is_enabled boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT report_subscription_recipients_pkey PRIMARY KEY (id),
    CONSTRAINT report_subscription_recipients_tenant_id_id_key UNIQUE (tenant_id, id),
    CONSTRAINT chk_report_subscription_recipients_format CHECK (((delivery_format)::text = ANY ((ARRAY['pdf', 'csv', 'xlsx', 'json', 'html'])::text[])))
);


-- report_runs
CREATE TABLE report_runs (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    property_id uuid,
    subscription_id uuid,
    report_code text NOT NULL,
    business_date date,
    period_start date NOT NULL,
    period_end date NOT NULL,
    status character varying(30) DEFAULT 'queued' NOT NULL,
    generation_key text NOT NULL,
    parameters jsonb DEFAULT '{}'::jsonb NOT NULL,
    summary_payload jsonb DEFAULT '{}'::jsonb NOT NULL,
    storage_object_key text,
    content_hash text,
    generated_at timestamp with time zone,
    failed_at timestamp with time zone,
    failure_reason text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT report_runs_pkey PRIMARY KEY (id),
    CONSTRAINT report_runs_generation_key_key UNIQUE (generation_key),
    CONSTRAINT report_runs_tenant_id_id_key UNIQUE (tenant_id, id),
    CONSTRAINT chk_report_runs_dates CHECK ((period_end >= period_start)),
    CONSTRAINT chk_report_runs_hash CHECK (((content_hash IS NULL) OR (content_hash ~ '^[0-9a-f]{64}$'))),
    CONSTRAINT chk_report_runs_status CHECK (((status)::text = ANY ((ARRAY['queued', 'running', 'generated', 'failed', 'cancelled', 'superseded'])::text[]))),
    CONSTRAINT chk_report_runs_generated CHECK ((((status)::text <> 'generated'::text) OR ((generated_at IS NOT NULL) AND (storage_object_key IS NOT NULL))))
);


-- report_deliveries
CREATE TABLE report_deliveries (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    property_id uuid,
    report_run_id uuid NOT NULL,
    subscription_recipient_id uuid,
    contact_id uuid NOT NULL,
    contact_channel_id uuid NOT NULL,
    channel_type character varying(20) NOT NULL,
    destination_masked text NOT NULL,
    status character varying(30) DEFAULT 'queued' NOT NULL,
    provider_code text,
    provider_message_id text,
    deduplication_key text NOT NULL,
    attempt_count integer DEFAULT 0 NOT NULL,
    max_attempts integer DEFAULT 3 NOT NULL,
    next_attempt_at timestamp with time zone DEFAULT now() NOT NULL,
    queued_at timestamp with time zone DEFAULT now() NOT NULL,
    sent_at timestamp with time zone,
    delivered_at timestamp with time zone,
    failed_at timestamp with time zone,
    last_error_code text,
    last_error_message text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT report_deliveries_pkey PRIMARY KEY (id),
    CONSTRAINT report_deliveries_deduplication_key_key UNIQUE (deduplication_key),
    CONSTRAINT report_deliveries_tenant_id_id_key UNIQUE (tenant_id, id),
    CONSTRAINT chk_report_deliveries_attempts CHECK (((attempt_count >= 0) AND (max_attempts > 0) AND (attempt_count <= max_attempts))),
    CONSTRAINT chk_report_deliveries_channel CHECK (((channel_type)::text = ANY ((ARRAY['email', 'sms', 'whatsapp', 'voice_phone'])::text[]))),
    CONSTRAINT chk_report_deliveries_status CHECK (((status)::text = ANY ((ARRAY['queued', 'sending', 'sent', 'delivered', 'failed', 'retry_scheduled', 'dead_letter', 'cancelled'])::text[])))
);

COMMENT ON TABLE tenant_profiles IS 'One business profile per tenant. tenants owns subscription/lifecycle/TIN; tenant_profiles owns onboarding business details, addresses, verification state, and hospitality registration metadata.';
COMMENT ON COLUMN tenants.tin_number IS 'Canonical tenant tax identification number. tenant_profiles intentionally does not duplicate TIN to avoid competing business identity values.';
COMMENT ON TABLE tenant_contacts IS 'Tenant business contact directory. Contacts may link to users, but contacts are not login identities and can represent executives or finance/legal stakeholders without PMS access.';
COMMENT ON TABLE contact_role_catalog IS 'Canonical tenant contact roles. owner_managing_director is a hospitality leadership role only; it is not a real-estate owner, landlord, lease, or payout concept.';
COMMENT ON TABLE communication_consents IS 'Append-only communication consent history. Delivery workers must evaluate the latest consent state for each purpose and channel.';
COMMENT ON TABLE report_deliveries IS 'Historical report delivery attempts and outcomes for executive, operational, finance, and compliance reporting.';


-- properties
CREATE TABLE properties (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    name text NOT NULL,
    location text,
    code character varying(20),
    type character varying(30) DEFAULT 'HOTEL',
    star_rating smallint,
    vrn character varying(50),
    address_line1 text,
    city character varying(100),
    country_code character(2),
    phone character varying(20),
    email character varying(254),
    total_rooms smallint DEFAULT 0 NOT NULL,
    status character varying(20) DEFAULT 'active' NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now(),
    updated_at timestamp with time zone DEFAULT now(),
    deleted_at timestamp with time zone,
    CONSTRAINT chk_properties_status CHECK (((status)::text = ANY ((ARRAY['active', 'suspended', 'frozen', 'archived', 'terminated'])::text[]))),
    CONSTRAINT chk_properties_total_rooms_non_negative CHECK ((total_rooms >= 0)),
    CONSTRAINT properties_star_rating_check CHECK (((star_rating >= 1) AND (star_rating <= 5)))
);


-- buildings
CREATE TABLE buildings (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    name text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


-- floors
CREATE TABLE floors (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    building_id uuid,
    floor_number smallint NOT NULL,
    capacity integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_floors_capacity_non_negative CHECK ((capacity >= 0))
);


-- departments
CREATE TABLE departments (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    property_id uuid,
    name text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


-- revenue_centers
CREATE TABLE revenue_centers (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    code character varying(50) NOT NULL,
    name text NOT NULL,
    center_type character varying(30) DEFAULT 'other' NOT NULL,
    is_rooms_revenue boolean DEFAULT false NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    display_order integer DEFAULT 100 NOT NULL,
    metadata jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone,
    CONSTRAINT chk_revenue_centers_display_order CHECK ((display_order >= 0)),
    CONSTRAINT chk_revenue_centers_type CHECK (((center_type)::text = ANY ((ARRAY['rooms', 'restaurant', 'bar', 'spa', 'banquet', 'events', 'other'])::text[])))
);


-- module_catalog
CREATE TABLE module_catalog (
    module_id character varying(50) NOT NULL,
    name text NOT NULL,
    category character varying(40) NOT NULL,
    access_scope character varying(20) NOT NULL,
    launch_status character varying(20) DEFAULT 'active' NOT NULL,
    is_platform_visible boolean DEFAULT false NOT NULL,
    is_tenant_visible boolean DEFAULT true NOT NULL,
    is_property_scoped boolean DEFAULT true NOT NULL,
    required_plan_code text,
    display_order integer DEFAULT 1000 NOT NULL,
    description text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_module_catalog_access_scope CHECK (((access_scope)::text = ANY ((ARRAY['tenant', 'property', 'both', 'platform'])::text[]))),
    CONSTRAINT chk_module_catalog_category CHECK (((category)::text = ANY ((ARRAY['platform', 'core_pms', 'finance', 'operations', 'revenue', 'guest', 'workforce', 'integration', 'future'])::text[]))),
    CONSTRAINT chk_module_catalog_launch_status CHECK (((launch_status)::text = ANY ((ARRAY['active', 'deferred', 'internal', 'deprecated'])::text[])))
);


-- module_dependencies
CREATE TABLE module_dependencies (
    module_id character varying(50) NOT NULL,
    depends_on_module_id character varying(50) NOT NULL,
    dependency_type character varying(20) DEFAULT 'required' NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_module_dependencies_type CHECK (((dependency_type)::text = ANY ((ARRAY['required', 'optional', 'runtime'])::text[]))),
    CONSTRAINT chk_module_dependencies_not_self CHECK (((module_id)::text <> (depends_on_module_id)::text))
);


-- property_modules
CREATE TABLE property_modules (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    module_id character varying(50) NOT NULL,
    is_enabled boolean DEFAULT true NOT NULL,
    is_configured boolean DEFAULT false NOT NULL,
    configured_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


-- property_module_configs
CREATE TABLE property_module_configs (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    module_id character varying(50) NOT NULL,
    config_json jsonb DEFAULT '{}'::jsonb NOT NULL,
    version integer DEFAULT 1 NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_property_module_configs_version_positive CHECK ((version > 0))
);


-- tenant_modules
CREATE TABLE tenant_modules (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    module_id character varying(50) NOT NULL,
    is_enabled boolean DEFAULT true NOT NULL,
    is_configured boolean DEFAULT false NOT NULL,
    source character varying(30) DEFAULT 'manual' NOT NULL,
    configured_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_tenant_modules_source CHECK (((source)::text = ANY ((ARRAY['plan', 'manual', 'trial', 'system', 'seed'])::text[])))
);


-- tenant_module_configs
CREATE TABLE tenant_module_configs (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    module_id character varying(50) NOT NULL,
    config_json jsonb DEFAULT '{}'::jsonb NOT NULL,
    version integer DEFAULT 1 NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_tenant_module_configs_version_positive CHECK ((version > 0))
);


-- ================================================================================
-- 5. USERS, ROLES & AUTH
-- ================================================================================

-- users
CREATE TABLE users (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    full_name text,
    email text NOT NULL,
    password_hash text,
    status text,
    last_login_at timestamp with time zone,
    failed_attempts integer DEFAULT 0 NOT NULL,
    locked_until timestamp with time zone,
    must_change_pw boolean DEFAULT false NOT NULL,
    avatar_url text,
    phone_number text,
    is_active boolean DEFAULT true NOT NULL,
    department_id uuid,
    language_preference text,
    created_by uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now(),
    deleted_at timestamp with time zone
);


-- roles
CREATE TABLE roles (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    name text NOT NULL,
    is_system boolean DEFAULT false NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


-- permissions
CREATE TABLE permissions (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    code text NOT NULL,
    description text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


-- permission_catalog
CREATE TABLE permission_catalog (
    code text NOT NULL,
    namespace text NOT NULL,
    access_scope character varying(20) NOT NULL,
    description text,
    is_platform_permission boolean DEFAULT false NOT NULL,
    is_tenant_permission boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_permission_catalog_scope CHECK (((access_scope)::text = ANY ((ARRAY['tenant', 'property', 'both', 'platform'])::text[]))),
    CONSTRAINT chk_permission_catalog_namespace CHECK ((namespace = ANY (ARRAY['platform', 'tenant', 'property', 'module', 'reservation', 'reservations', 'frontdesk', 'housekeeping', 'maintenance', 'finance', 'folio', 'billing', 'fiscal', 'payments', 'pos', 'inventory', 'procurement', 'events', 'corporate_accounts', 'staffing', 'hr', 'reports', 'analytics', 'edge_sync', 'admin'])))
);


-- role_permissions
CREATE TABLE role_permissions (
    role_id uuid NOT NULL,
    permission_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now()
);


-- user_property_roles
CREATE TABLE user_property_roles (
    user_id uuid NOT NULL,
    property_id uuid NOT NULL,
    role_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


-- tenant_roles
CREATE TABLE tenant_roles (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    name text NOT NULL,
    code text NOT NULL,
    description text,
    is_system boolean DEFAULT false NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


-- tenant_role_permissions
CREATE TABLE tenant_role_permissions (
    tenant_role_id uuid NOT NULL,
    permission_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


-- user_tenant_roles
CREATE TABLE user_tenant_roles (
    user_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    tenant_role_id uuid NOT NULL,
    assigned_by uuid,
    assigned_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


-- module_access_matrix
CREATE TABLE module_access_matrix (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    module_id character varying(50) NOT NULL,
    screen_key text NOT NULL,
    screen_label text NOT NULL,
    http_method character varying(10) DEFAULT 'ANY' NOT NULL,
    api_pattern text NOT NULL,
    permission_code text,
    route_scope character varying(30) DEFAULT 'property' NOT NULL,
    guard_mode character varying(30) DEFAULT 'staff_permission' NOT NULL,
    access_scope character varying(20) NOT NULL,
    is_tanzania_v1 boolean DEFAULT false NOT NULL,
    is_enabled_by_default boolean DEFAULT true NOT NULL,
    notes text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_module_access_matrix_access_scope CHECK (((access_scope)::text = ANY ((ARRAY['tenant', 'property', 'both', 'platform'])::text[]))),
    CONSTRAINT chk_module_access_matrix_guard_mode CHECK (((guard_mode)::text = ANY ((ARRAY['staff_permission', 'module_only', 'platform_permission'])::text[]))),
    CONSTRAINT chk_module_access_matrix_http_method CHECK (((http_method)::text = ANY ((ARRAY['ANY', 'GET', 'POST', 'PUT', 'PATCH', 'DELETE'])::text[]))),
    CONSTRAINT chk_module_access_matrix_route_scope CHECK (((route_scope)::text = ANY ((ARRAY['tenant', 'property', 'public_property', 'platform'])::text[])))
);


-- business_profiles
CREATE TABLE business_profiles (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    code text NOT NULL,
    name text NOT NULL,
    description text,
    is_active boolean DEFAULT true NOT NULL,
    display_order integer DEFAULT 100 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


-- business_profile_modules
CREATE TABLE business_profile_modules (
    business_profile_id uuid NOT NULL,
    module_id character varying(50) NOT NULL,
    is_default boolean DEFAULT true NOT NULL,
    is_optional boolean DEFAULT false NOT NULL,
    display_order integer DEFAULT 100 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_business_profile_modules_default_optional CHECK ((NOT ((is_default = true) AND (is_optional = true))))
);


-- refresh_tokens
CREATE TABLE refresh_tokens (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    token_hash text NOT NULL,
    device_info jsonb,
    expires_at timestamp with time zone NOT NULL,
    revoked_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


-- workflow_catalog
CREATE TABLE workflow_catalog (
    workflow_code text NOT NULL,
    module_id character varying(50) NOT NULL,
    name text NOT NULL,
    description text,
    actor_scope character varying(20) NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_workflow_catalog_actor_scope CHECK (((actor_scope)::text = ANY ((ARRAY['platform', 'tenant', 'property', 'guest', 'system'])::text[])))
);


-- workflow_steps
CREATE TABLE workflow_steps (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    workflow_code text NOT NULL,
    step_order integer NOT NULL,
    step_key text NOT NULL,
    step_label text NOT NULL,
    table_name text,
    permission_code text,
    api_pattern text,
    is_required boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_workflow_steps_order CHECK ((step_order > 0))
);


-- ================================================================================
-- 6. EMPLOYEES & HR
-- ================================================================================

-- employees
CREATE TABLE employees (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    user_id uuid,
    department_id uuid,
    employee_number character varying(20),
    full_name text NOT NULL,
    national_id character varying(50),
    nssf_number character varying(50),
    date_of_birth date,
    gender character varying(20),
    employment_type character varying(20) DEFAULT 'full_time' NOT NULL,
    hire_date date,
    basic_salary numeric(15,2) DEFAULT 0 NOT NULL,
    bank_account jsonb,
    mobile_money jsonb,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone,
    CONSTRAINT chk_employees_employment_type CHECK (((employment_type)::text = ANY ((ARRAY['full_time', 'part_time', 'contract', 'casual', 'intern'])::text[]))),
    CONSTRAINT chk_employees_gender CHECK ((((gender)::text = ANY ((ARRAY['male', 'female', 'other'])::text[])) OR (gender IS NULL)))
);


-- attendance
CREATE TABLE attendance (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    employee_id uuid NOT NULL,
    check_in timestamp with time zone,
    check_out timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


-- payroll_runs
CREATE TABLE payroll_runs (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    period_start date NOT NULL,
    period_end date NOT NULL,
    status character varying(20) DEFAULT 'draft' NOT NULL,
    total_gross numeric(15,2) DEFAULT 0 NOT NULL,
    total_paye numeric(15,2) DEFAULT 0 NOT NULL,
    total_nssf numeric(15,2) DEFAULT 0 NOT NULL,
    total_net numeric(15,2) DEFAULT 0 NOT NULL,
    run_by uuid,
    approved_by uuid,
    approved_at timestamp with time zone,
    paid_at timestamp with time zone,
    notes text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_payroll_runs_status CHECK (((status)::text = ANY ((ARRAY['draft', 'approved', 'paid', 'cancelled'])::text[])))
);


-- payroll_records
CREATE TABLE payroll_records (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    payroll_run_id uuid NOT NULL,
    employee_id uuid NOT NULL,
    period_start date NOT NULL,
    period_end date NOT NULL,
    basic_salary numeric(15,2) DEFAULT 0 NOT NULL,
    allowances numeric(15,2) DEFAULT 0 NOT NULL,
    overtime numeric(15,2) DEFAULT 0 NOT NULL,
    gross_pay numeric(15,2) DEFAULT 0 NOT NULL,
    paye_tax numeric(15,2) DEFAULT 0 NOT NULL,
    nssf_employee numeric(15,2) DEFAULT 0 NOT NULL,
    nssf_employer numeric(15,2) DEFAULT 0 NOT NULL,
    other_deductions numeric(15,2) DEFAULT 0 NOT NULL,
    net_pay numeric(15,2) DEFAULT 0 NOT NULL,
    deductions_detail jsonb DEFAULT '[]'::jsonb NOT NULL,
    pay_date date,
    pdf_url text,
    paid_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


-- leave_requests
CREATE TABLE leave_requests (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    employee_id uuid NOT NULL,
    leave_type character varying(30) NOT NULL,
    start_date date NOT NULL,
    end_date date NOT NULL,
    status character varying(20) DEFAULT 'pending' NOT NULL,
    requested_by uuid,
    approved_by uuid,
    approved_at timestamp with time zone,
    reason text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_leave_requests_dates CHECK ((end_date >= start_date)),
    CONSTRAINT chk_leave_requests_leave_type CHECK (((leave_type)::text = ANY ((ARRAY['annual', 'sick', 'maternity', 'paternity', 'unpaid', 'compassionate', 'other'])::text[]))),
    CONSTRAINT chk_leave_requests_status CHECK (((status)::text = ANY ((ARRAY['pending', 'approved', 'rejected', 'cancelled'])::text[])))
);


-- staff_rosters
CREATE TABLE staff_rosters (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    employee_id uuid NOT NULL,
    shift_template_id uuid,
    roster_date date NOT NULL,
    starts_at timestamp with time zone NOT NULL,
    ends_at timestamp with time zone NOT NULL,
    status character varying(20) DEFAULT 'scheduled' NOT NULL,
    notes text,
    created_by uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_staff_rosters_dates CHECK ((ends_at > starts_at)),
    CONSTRAINT chk_staff_rosters_status CHECK (((status)::text = ANY ((ARRAY['scheduled', 'confirmed', 'completed', 'missed', 'cancelled'])::text[])))
);


-- labor_forecasts
CREATE TABLE labor_forecasts (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    department_id uuid,
    forecast_date date NOT NULL,
    expected_occupancy_pct numeric(5,2) DEFAULT 0 NOT NULL,
    expected_arrivals integer DEFAULT 0 NOT NULL,
    expected_departures integer DEFAULT 0 NOT NULL,
    required_staff numeric(8,2) DEFAULT 0 NOT NULL,
    notes text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_labor_forecasts_counts CHECK (((expected_arrivals >= 0) AND (expected_departures >= 0) AND (required_staff >= (0)::numeric))),
    CONSTRAINT chk_labor_forecasts_occupancy CHECK (((expected_occupancy_pct >= (0)::numeric) AND (expected_occupancy_pct <= (100)::numeric)))
);


-- ================================================================================
-- 7. GUESTS & LOYALTY
-- ================================================================================

-- companies
CREATE TABLE companies (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    name text NOT NULL,
    trade_name text,
    tin_number character varying(50),
    contact_name character varying(200),
    contact_email character varying(254),
    contact_phone character varying(20),
    billing_address jsonb,
    credit_limit numeric(15,2) DEFAULT 0 NOT NULL,
    payment_terms smallint DEFAULT 30 NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone
);


-- guests
CREATE TABLE guests (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    guest_number character varying(20),
    full_name text,
    first_name character varying(100),
    last_name character varying(100),
    title character varying(20),
    date_of_birth date,
    gender text,
    nationality text,
    email character varying(254),
    phone_primary character varying(20),
    language character varying(10) DEFAULT 'en' NOT NULL,
    vip_level character varying(20) DEFAULT 'NONE' NOT NULL,
    company_id uuid,
    blacklisted boolean DEFAULT false NOT NULL,
    blacklist_reason text,
    total_stays integer DEFAULT 0 NOT NULL,
    total_revenue numeric(15,2) DEFAULT 0 NOT NULL,
    notes text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone,
    CONSTRAINT chk_guests_has_name CHECK (((NULLIF(btrim(full_name), ''::text) IS NOT NULL) OR (NULLIF(btrim((first_name)::text), ''::text) IS NOT NULL) OR (NULLIF(btrim((last_name)::text), ''::text) IS NOT NULL))),
    CONSTRAINT chk_guests_vip_level CHECK (((vip_level)::text = ANY ((ARRAY['NONE', 'BRONZE', 'SILVER', 'GOLD', 'PLATINUM', 'VIP', 'BLACKLISTED'])::text[])))
);


-- guest_contacts
CREATE TABLE guest_contacts (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    guest_id uuid NOT NULL,
    phone text,
    email text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


-- guest_documents
CREATE TABLE guest_documents (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    guest_id uuid NOT NULL,
    document_type text NOT NULL,
    document_number text NOT NULL,
    issuing_country character(2),
    issuing_authority character varying(200),
    issued_at date,
    expires_at date,
    scan_url text,
    verified boolean DEFAULT false NOT NULL,
    verified_by uuid,
    verified_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


-- guest_preferences
CREATE TABLE guest_preferences (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    guest_id uuid NOT NULL,
    room_preferences jsonb DEFAULT '{}'::jsonb NOT NULL,
    dietary jsonb DEFAULT '[]'::jsonb NOT NULL,
    amenities jsonb DEFAULT '[]'::jsonb NOT NULL,
    special_requests text,
    newsletter_opt_in boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


-- loyalty_accounts
CREATE TABLE loyalty_accounts (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    guest_id uuid NOT NULL,
    card_number character varying(50) NOT NULL,
    tier character varying(20) DEFAULT 'BRONZE' NOT NULL,
    points_balance integer DEFAULT 0 NOT NULL,
    lifetime_points integer DEFAULT 0 NOT NULL,
    tier_review_date date,
    enrolled_at timestamp with time zone DEFAULT now() NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_loyalty_accounts_tier CHECK (((tier)::text = ANY ((ARRAY['BRONZE', 'SILVER', 'GOLD', 'PLATINUM'])::text[])))
);


-- loyalty_transactions
CREATE TABLE loyalty_transactions (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    account_id uuid NOT NULL,
    type character varying(20) NOT NULL,
    points integer NOT NULL,
    reference_id uuid,
    reference_type character varying(50),
    notes text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_loyalty_transactions_type CHECK (((type)::text = ANY ((ARRAY['earn', 'redeem', 'expire', 'adjust', 'bonus', 'transfer'])::text[])))
);


-- sales_leads
CREATE TABLE sales_leads (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    property_id uuid,
    company_id uuid,
    guest_id uuid,
    lead_source character varying(40) DEFAULT 'direct' NOT NULL,
    lead_type character varying(40) DEFAULT 'corporate' NOT NULL,
    title text NOT NULL,
    contact_name text,
    contact_email character varying(254),
    contact_phone character varying(30),
    estimated_value numeric(15,2) DEFAULT 0 NOT NULL,
    expected_close_date date,
    status character varying(30) DEFAULT 'new' NOT NULL,
    assigned_to uuid,
    notes text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_sales_leads_estimated_value CHECK ((estimated_value >= (0)::numeric)),
    CONSTRAINT chk_sales_leads_source CHECK (((lead_source)::text = ANY ((ARRAY['direct', 'website', 'referral', 'ota', 'walkin', 'event', 'corporate', 'other'])::text[]))),
    CONSTRAINT chk_sales_leads_status CHECK (((status)::text = ANY ((ARRAY['new', 'contacted', 'qualified', 'proposal', 'won', 'lost', 'archived'])::text[]))),
    CONSTRAINT chk_sales_leads_type CHECK (((lead_type)::text = ANY ((ARRAY['corporate', 'group', 'event', 'long_stay', 'travel_agent', 'other'])::text[])))
);


-- sales_activities
CREATE TABLE sales_activities (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    lead_id uuid,
    company_id uuid,
    guest_id uuid,
    activity_type character varying(30) NOT NULL,
    subject text NOT NULL,
    activity_at timestamp with time zone DEFAULT now() NOT NULL,
    due_at timestamp with time zone,
    completed_at timestamp with time zone,
    assigned_to uuid,
    notes text,
    outcome text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_sales_activities_type CHECK (((activity_type)::text = ANY ((ARRAY['call', 'email', 'meeting', 'site_visit', 'proposal', 'follow_up', 'note'])::text[])))
);


-- corporate_rate_agreements
CREATE TABLE corporate_rate_agreements (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    company_id uuid NOT NULL,
    rate_plan_id uuid,
    agreement_code character varying(50) NOT NULL,
    name text NOT NULL,
    valid_from date NOT NULL,
    valid_to date,
    discount_pct numeric(5,2) DEFAULT 0 NOT NULL,
    contracted_rate numeric(12,2),
    min_room_nights integer DEFAULT 0 NOT NULL,
    status character varying(20) DEFAULT 'draft' NOT NULL,
    terms jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_by uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_corporate_rate_agreements_dates CHECK (((valid_to IS NULL) OR (valid_to >= valid_from))),
    CONSTRAINT chk_corporate_rate_agreements_discount CHECK (((discount_pct >= (0)::numeric) AND (discount_pct <= (100)::numeric))),
    CONSTRAINT chk_corporate_rate_agreements_min_nights CHECK ((min_room_nights >= 0)),
    CONSTRAINT chk_corporate_rate_agreements_status CHECK (((status)::text = ANY ((ARRAY['draft', 'active', 'expired', 'suspended', 'cancelled'])::text[])))
);


-- corporate_accounts
CREATE TABLE corporate_accounts (
    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id uuid NOT NULL,
    company_id uuid NOT NULL,
    account_number character varying(50) NOT NULL,
    account_name text NOT NULL,
    account_type character varying(30) DEFAULT 'corporate' NOT NULL,
    credit_limit numeric(15,2) DEFAULT 0 NOT NULL,
    current_balance numeric(15,2) DEFAULT 0 NOT NULL,
    available_credit numeric(15,2) GENERATED ALWAYS AS (credit_limit - current_balance) STORED,
    currency character(3) DEFAULT 'TZS'::bpchar NOT NULL,
    payment_terms_days smallint DEFAULT 30 NOT NULL,
    billing_cycle character varying(20) DEFAULT 'monthly' NOT NULL,
    statement_day smallint DEFAULT 1 NOT NULL,
    credit_status character varying(20) DEFAULT 'active' NOT NULL,
    account_manager_id uuid,
    default_billing_contact_id uuid,
    tax_profile jsonb DEFAULT '{}'::jsonb NOT NULL,
    notes text,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone,
    CONSTRAINT chk_corporate_accounts_amounts CHECK (((credit_limit >= (0)::numeric) AND (current_balance >= (0)::numeric))),
    CONSTRAINT chk_corporate_accounts_billing_cycle CHECK (((billing_cycle)::text = ANY ((ARRAY['weekly', 'monthly', 'quarterly', 'custom'])::text[]))),
    CONSTRAINT chk_corporate_accounts_credit_status CHECK (((credit_status)::text = ANY ((ARRAY['active', 'watch', 'hold', 'suspended', 'closed'])::text[]))),
    CONSTRAINT chk_corporate_accounts_statement_day CHECK (((statement_day >= 1) AND (statement_day <= 31))),
    CONSTRAINT chk_corporate_accounts_type CHECK (((account_type)::text = ANY ((ARRAY['corporate', 'travel_agent', 'government', 'ngo', 'event_client', 'other'])::text[]))),
    UNIQUE (tenant_id, account_number),
    UNIQUE (tenant_id, company_id)
);


-- corporate_account_contacts
CREATE TABLE corporate_account_contacts (
    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id uuid NOT NULL,
    corporate_account_id uuid NOT NULL,
    contact_name text NOT NULL,
    role character varying(30) NOT NULL,
    email character varying(254),
    phone character varying(30),
    is_primary boolean DEFAULT false NOT NULL,
    receives_invoices boolean DEFAULT false NOT NULL,
    receives_statements boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_corporate_account_contacts_role CHECK (((role)::text = ANY ((ARRAY['billing', 'booking', 'approver', 'finance', 'escalation', 'manager', 'other'])::text[])))
);


-- corporate_account_limits
CREATE TABLE corporate_account_limits (
    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id uuid NOT NULL,
    corporate_account_id uuid NOT NULL,
    property_id uuid,
    limit_type character varying(30) DEFAULT 'credit' NOT NULL,
    limit_amount numeric(15,2) NOT NULL,
    currency character(3) DEFAULT 'TZS'::bpchar NOT NULL,
    effective_from date DEFAULT CURRENT_DATE NOT NULL,
    effective_to date,
    approved_by uuid,
    approved_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_corporate_account_limits_amount CHECK ((limit_amount >= (0)::numeric)),
    CONSTRAINT chk_corporate_account_limits_dates CHECK (((effective_to IS NULL) OR (effective_to >= effective_from))),
    CONSTRAINT chk_corporate_account_limits_type CHECK (((limit_type)::text = ANY ((ARRAY['credit', 'monthly_spend', 'event_spend', 'room_nights'])::text[])))
);


-- corporate_account_holds
CREATE TABLE corporate_account_holds (
    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id uuid NOT NULL,
    corporate_account_id uuid NOT NULL,
    hold_type character varying(30) NOT NULL,
    reason text NOT NULL,
    starts_at timestamp with time zone DEFAULT now() NOT NULL,
    ends_at timestamp with time zone,
    status character varying(20) DEFAULT 'active' NOT NULL,
    placed_by uuid,
    released_by uuid,
    released_at timestamp with time zone,
    release_reason text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_corporate_account_holds_dates CHECK (((ends_at IS NULL) OR (ends_at >= starts_at))),
    CONSTRAINT chk_corporate_account_holds_status CHECK (((status)::text = ANY ((ARRAY['active', 'released', 'expired'])::text[]))),
    CONSTRAINT chk_corporate_account_holds_type CHECK (((hold_type)::text = ANY ((ARRAY['credit', 'legal', 'collections', 'manual', 'fraud', 'other'])::text[])))
);


-- corporate_statements
CREATE TABLE corporate_statements (
    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id uuid NOT NULL,
    corporate_account_id uuid NOT NULL,
    statement_number character varying(50) NOT NULL,
    period_start date NOT NULL,
    period_end date NOT NULL,
    opening_balance numeric(15,2) DEFAULT 0 NOT NULL,
    charges_total numeric(15,2) DEFAULT 0 NOT NULL,
    payments_total numeric(15,2) DEFAULT 0 NOT NULL,
    credits_total numeric(15,2) DEFAULT 0 NOT NULL,
    closing_balance numeric(15,2) DEFAULT 0 NOT NULL,
    currency character(3) DEFAULT 'TZS'::bpchar NOT NULL,
    status character varying(20) DEFAULT 'draft' NOT NULL,
    issued_at timestamp with time zone,
    due_date date,
    pdf_url text,
    created_by uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_corporate_statements_dates CHECK ((period_end >= period_start)),
    CONSTRAINT chk_corporate_statements_status CHECK (((status)::text = ANY ((ARRAY['draft', 'issued', 'sent', 'paid', 'voided'])::text[]))),
    UNIQUE (tenant_id, statement_number)
);


-- corporate_statement_items
CREATE TABLE corporate_statement_items (
    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id uuid NOT NULL,
    statement_id uuid NOT NULL,
    item_date date NOT NULL,
    item_type character varying(30) NOT NULL,
    invoice_id uuid,
    folio_payment_id uuid,
    credit_note_id uuid,
    description text NOT NULL,
    debit numeric(15,2) DEFAULT 0 NOT NULL,
    credit numeric(15,2) DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_corporate_statement_items_amount CHECK (((debit >= (0)::numeric) AND (credit >= (0)::numeric) AND ((debit > (0)::numeric) <> (credit > (0)::numeric)))),
    CONSTRAINT chk_corporate_statement_items_type CHECK (((item_type)::text = ANY ((ARRAY['invoice', 'payment', 'credit_note', 'adjustment', 'opening_balance'])::text[])))
);


-- credit_notes
CREATE TABLE credit_notes (
    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id uuid NOT NULL,
    corporate_account_id uuid,
    company_id uuid,
    invoice_id uuid,
    credit_note_number character varying(50) NOT NULL,
    reason text NOT NULL,
    subtotal numeric(15,2) DEFAULT 0 NOT NULL,
    tax_amount numeric(15,2) DEFAULT 0 NOT NULL,
    total_amount numeric(15,2) DEFAULT 0 NOT NULL,
    currency character(3) DEFAULT 'TZS'::bpchar NOT NULL,
    status character varying(20) DEFAULT 'draft' NOT NULL,
    issued_at timestamp with time zone,
    approved_by uuid,
    created_by uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_credit_notes_amounts CHECK (((subtotal >= (0)::numeric) AND (tax_amount >= (0)::numeric) AND (total_amount = round((subtotal + tax_amount), 2)))),
    CONSTRAINT chk_credit_notes_status CHECK (((status)::text = ANY ((ARRAY['draft', 'issued', 'applied', 'voided'])::text[]))),
    UNIQUE (tenant_id, credit_note_number)
);


-- ar_allocations
CREATE TABLE ar_allocations (
    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id uuid NOT NULL,
    corporate_account_id uuid NOT NULL,
    invoice_id uuid,
    folio_payment_id uuid,
    credit_note_id uuid,
    allocation_type character varying(30) NOT NULL,
    amount numeric(15,2) NOT NULL,
    currency character(3) DEFAULT 'TZS'::bpchar NOT NULL,
    allocated_at timestamp with time zone DEFAULT now() NOT NULL,
    allocated_by uuid,
    notes text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_ar_allocations_amount CHECK ((amount > (0)::numeric)),
    CONSTRAINT chk_ar_allocations_target CHECK (((invoice_id IS NOT NULL)::integer + (credit_note_id IS NOT NULL)::integer >= 1)),
    CONSTRAINT chk_ar_allocations_type CHECK (((allocation_type)::text = ANY ((ARRAY['payment_to_invoice', 'credit_to_invoice', 'writeoff', 'adjustment'])::text[])))
);


-- ================================================================================
-- 8. ROOMS & RATE MANAGEMENT
-- ================================================================================

-- room_types
CREATE TABLE room_types (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    name text NOT NULL,
    code character varying(20),
    description text,
    base_price numeric(15,2) DEFAULT 0 NOT NULL,
    max_adults smallint DEFAULT 2 NOT NULL,
    max_children smallint DEFAULT 0 NOT NULL,
    max_occupancy smallint DEFAULT 2 NOT NULL,
    size_sqm numeric(6,2),
    bed_type character varying(20),
    amenities jsonb DEFAULT '[]'::jsonb NOT NULL,
    images jsonb DEFAULT '[]'::jsonb NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone,
    CONSTRAINT chk_room_types_base_price_non_negative CHECK ((base_price >= (0)::numeric)),
    CONSTRAINT chk_room_types_occupancy_valid CHECK ((max_occupancy >= max_adults))
);


-- rooms
CREATE TABLE rooms (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    room_type_id uuid NOT NULL,
    room_number text NOT NULL,
    floor smallint,
    status text DEFAULT 'vacant_clean' NOT NULL,
    is_smoking boolean DEFAULT false NOT NULL,
    is_accessible boolean DEFAULT false NOT NULL,
    connecting_room_id uuid,
    notes text,
    last_status_changed_at timestamp with time zone DEFAULT now() NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone,
    CONSTRAINT chk_rooms_status CHECK ((status = ANY (ARRAY['vacant_clean', 'vacant_dirty', 'occupied', 'maintenance', 'out_of_order', 'blocked'])))
);


-- room_status_log
CREATE TABLE room_status_log (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    room_id uuid NOT NULL,
    status text NOT NULL,
    changed_at timestamp with time zone DEFAULT now() NOT NULL,
    changed_by uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_room_status_log_status CHECK ((status = ANY (ARRAY['vacant_clean', 'vacant_dirty', 'occupied', 'maintenance', 'out_of_order', 'blocked'])))
);


-- rate_plans
CREATE TABLE rate_plans (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    name text NOT NULL,
    description text,
    is_active boolean DEFAULT true,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


-- rate_plan_prices
CREATE TABLE rate_plan_prices (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    rate_plan_id uuid NOT NULL,
    room_type_id uuid,
    date date NOT NULL,
    price numeric(15,2) NOT NULL,
    occupancy integer DEFAULT 1 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_rate_plan_prices_positive CHECK ((price > (0)::numeric))
);


-- rate_restrictions
CREATE TABLE rate_restrictions (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    rate_plan_id uuid NOT NULL,
    room_type_id uuid,
    restriction_type text NOT NULL,
    value integer,
    days_of_week integer[],
    date_from date,
    date_to date,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT rate_restrictions_check CHECK (((date_to IS NULL) OR (date_to >= date_from))),
    CONSTRAINT rate_restrictions_restriction_type_check CHECK ((restriction_type = ANY (ARRAY['min_stay', 'max_stay', 'closed_to_arrival', 'closed_to_departure', 'advance_booking_min', 'advance_booking_max', 'blackout', 'day_of_week'])))
);


-- pricing_rules
CREATE TABLE pricing_rules (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    rate_plan_id uuid NOT NULL,
    room_type_id uuid,
    valid_from date NOT NULL,
    valid_to date NOT NULL,
    price_modifier numeric(15,2) NOT NULL,
    modifier_type text DEFAULT 'absolute' NOT NULL,
    created_at timestamp with time zone DEFAULT now(),
    updated_at timestamp with time zone DEFAULT now(),
    CONSTRAINT chk_pricing_rules_modifier_type CHECK ((modifier_type = ANY (ARRAY['absolute', 'percentage'])))
);


-- availability_calendar
CREATE TABLE availability_calendar (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    room_type_id uuid NOT NULL,
    stay_date date NOT NULL,
    total_rooms integer DEFAULT 0 NOT NULL,
    rooms_sold integer DEFAULT 0 NOT NULL,
    rooms_blocked integer DEFAULT 0 NOT NULL,
    rooms_held integer DEFAULT 0 NOT NULL,
    rooms_available integer GENERATED ALWAYS AS (GREATEST(0, (((total_rooms - rooms_sold) - rooms_blocked) - rooms_held))) STORED,
    stop_sell boolean DEFAULT false NOT NULL,
    closed_to_arrival boolean DEFAULT false NOT NULL,
    closed_to_departure boolean DEFAULT false NOT NULL,
    override_price numeric(12,2),
    last_pushed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT availability_calendar_capacity_check CHECK (((rooms_sold + rooms_blocked + rooms_held) <= total_rooms)),
    CONSTRAINT availability_calendar_rooms_blocked_check CHECK ((rooms_blocked >= 0)),
    CONSTRAINT availability_calendar_rooms_held_check CHECK ((rooms_held >= 0)),
    CONSTRAINT availability_calendar_rooms_sold_check CHECK ((rooms_sold >= 0)),
    CONSTRAINT availability_calendar_total_rooms_check CHECK ((total_rooms >= 0))
);


-- ================================================================================
-- 9. RESERVATIONS, STAYS & CHANNELS
-- ================================================================================

-- channels
CREATE TABLE channels (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    name text NOT NULL,
    code text NOT NULL,
    type text NOT NULL,
    commission_pct numeric(5,2) DEFAULT 0 NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    deleted_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT channels_commission_pct_check CHECK (((commission_pct >= (0)::numeric) AND (commission_pct <= (100)::numeric))),
    CONSTRAINT channels_type_check CHECK ((type = ANY (ARRAY['direct', 'ota', 'gds', 'corporate', 'group', 'travel_agent', 'other'])))
);


-- groups
CREATE TABLE groups (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    name text NOT NULL,
    group_code text NOT NULL,
    type text DEFAULT 'tour' NOT NULL,
    account_manager uuid,
    company_id uuid,
    allotment_rooms integer DEFAULT 0 NOT NULL,
    contracted_rate numeric(12,2),
    arrival_date date NOT NULL,
    departure_date date NOT NULL,
    status text DEFAULT 'tentative' NOT NULL,
    notes text,
    deleted_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT groups_check CHECK ((departure_date > arrival_date)),
    CONSTRAINT groups_status_check CHECK ((status = ANY (ARRAY['tentative', 'confirmed', 'in_house', 'checked_out', 'cancelled']))),
    CONSTRAINT groups_type_check CHECK ((type = ANY (ARRAY['tour', 'corporate', 'wedding', 'conference', 'government', 'other'])))
);


-- reservations
CREATE TABLE reservations (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    primary_guest_id uuid NOT NULL,
    rate_plan_id uuid,
    confirmation_number character varying(20),
    external_reservation_id text,
    status text DEFAULT 'confirmed' NOT NULL,
    check_in_date date NOT NULL,
    check_out_date date NOT NULL,
    actual_check_in_at timestamp with time zone,
    actual_check_out_at timestamp with time zone,
    adults smallint DEFAULT 1 NOT NULL,
    children smallint DEFAULT 0 NOT NULL,
    total_amount numeric(15,2) DEFAULT 0 NOT NULL,
    total_paid numeric(15,2) DEFAULT 0 NOT NULL,
    special_requests text,
    internal_notes text,
    group_id uuid,
    cancelled_at timestamp with time zone,
    cancellation_reason text,
    cancellation_fee numeric(15,2),
    created_by uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone,
    channel_id uuid,
    CONSTRAINT chk_reservations_adults_positive CHECK ((adults >= 1)),
    CONSTRAINT chk_reservations_dates_valid CHECK ((check_out_date > check_in_date)),
    CONSTRAINT chk_reservations_status CHECK ((status = ANY (ARRAY['pending', 'confirmed', 'checked_in', 'checked_out', 'cancelled', 'no_show'])))
);


-- reservation_guests
CREATE TABLE reservation_guests (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    reservation_id uuid NOT NULL,
    guest_id uuid NOT NULL,
    is_primary boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


-- reservation_notes
CREATE TABLE reservation_notes (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    reservation_id uuid NOT NULL,
    note_type character varying(30) DEFAULT 'general' NOT NULL,
    note text NOT NULL,
    created_by uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


-- reservation_rooms
CREATE TABLE reservation_rooms (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    reservation_id uuid NOT NULL,
    room_type_id uuid NOT NULL,
    room_id uuid,
    folio_id uuid,
    check_in_date date NOT NULL,
    check_out_date date NOT NULL,
    rate_per_night numeric(15,2) DEFAULT 0 NOT NULL,
    status character varying(30) DEFAULT 'reserved' NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_reservation_rooms_dates_valid CHECK ((check_out_date > check_in_date)),
    CONSTRAINT chk_reservation_rooms_rate_non_negative CHECK ((rate_per_night >= (0)::numeric)),
    CONSTRAINT chk_reservation_rooms_room_required_for_stay CHECK (((room_id IS NOT NULL) OR ((status)::text = ANY ((ARRAY['reserved', 'cancelled', 'no_show'])::text[])))),
    CONSTRAINT chk_reservation_rooms_status CHECK (((status)::text = ANY ((ARRAY['reserved', 'checked_in', 'checked_out', 'cancelled', 'no_show'])::text[])))
);


-- reservation_room_nights
CREATE TABLE reservation_room_nights (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    reservation_id uuid NOT NULL,
    reservation_room_id uuid NOT NULL,
    room_id uuid,
    room_type_id uuid NOT NULL,
    rate_plan_id uuid,
    stay_date date NOT NULL,
    base_rate numeric(12,2) NOT NULL,
    tax_amount numeric(12,2) DEFAULT 0 NOT NULL,
    fee_amount numeric(12,2) DEFAULT 0 NOT NULL,
    discount_amount numeric(12,2) DEFAULT 0 NOT NULL,
    final_amount numeric(12,2) NOT NULL,
    currency character(3) DEFAULT 'TZS'::bpchar NOT NULL,
    rate_source character varying(30) DEFAULT 'manual' NOT NULL,
    rate_snapshot jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_reservation_room_nights_amounts CHECK (((base_rate >= (0)::numeric) AND (tax_amount >= (0)::numeric) AND (fee_amount >= (0)::numeric) AND (discount_amount >= (0)::numeric) AND (final_amount >= (0)::numeric))),
    CONSTRAINT chk_reservation_room_nights_final_amount CHECK ((final_amount = round((((base_rate + tax_amount) + fee_amount) - discount_amount), 2))),
    CONSTRAINT chk_reservation_room_nights_rate_source CHECK (((rate_source)::text = ANY ((ARRAY['rate_plan', 'manual', 'corporate', 'group', 'package', 'comp', 'booking_engine'])::text[])))
);


-- stays
CREATE TABLE stays (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    reservation_id uuid NOT NULL,
    room_id uuid NOT NULL,
    status text DEFAULT 'checked_in' NOT NULL,
    check_in_time timestamp with time zone,
    check_out_time timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_stays_status CHECK ((status = ANY (ARRAY['checked_in', 'checked_out', 'early_departure'])))
);


-- room_moves
CREATE TABLE room_moves (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    reservation_id uuid NOT NULL,
    stay_id uuid,
    from_room_id uuid NOT NULL,
    to_room_id uuid NOT NULL,
    moved_at timestamp with time zone DEFAULT now() NOT NULL,
    reason text NOT NULL,
    move_fee numeric(12,2) DEFAULT 0 NOT NULL,
    approved_by uuid,
    moved_by uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_room_moves_different_rooms CHECK ((from_room_id <> to_room_id)),
    CONSTRAINT chk_room_moves_fee CHECK ((move_fee >= (0)::numeric))
);


-- allotments
CREATE TABLE allotments (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    room_type_id uuid NOT NULL,
    channel_id uuid,
    group_id uuid,
    start_date date NOT NULL,
    end_date date NOT NULL,
    total_rooms integer NOT NULL,
    rooms_sold integer DEFAULT 0 NOT NULL,
    cut_off_date date,
    release_type text DEFAULT 'auto' NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT allotments_check CHECK ((end_date > start_date)),
    CONSTRAINT allotments_check1 CHECK ((rooms_sold <= total_rooms)),
    CONSTRAINT allotments_check2 CHECK (((cut_off_date IS NULL) OR (cut_off_date <= start_date))),
    CONSTRAINT allotments_release_type_check CHECK ((release_type = ANY (ARRAY['auto', 'manual']))),
    CONSTRAINT allotments_rooms_sold_check CHECK ((rooms_sold >= 0)),
    CONSTRAINT allotments_total_rooms_check CHECK ((total_rooms > 0))
);


-- availability_locks
CREATE TABLE availability_locks (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    room_type_id uuid NOT NULL,
    check_in_date date NOT NULL,
    check_out_date date NOT NULL,
    rooms_held integer DEFAULT 1 NOT NULL,
    locked_by text NOT NULL,
    expires_at timestamp with time zone DEFAULT (now() + '00:15:00'::interval) NOT NULL,
    released_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT availability_locks_check CHECK ((check_out_date > check_in_date)),
    CONSTRAINT availability_locks_rooms_held_check CHECK ((rooms_held > 0))
);


-- property_reservation_settings
CREATE TABLE property_reservation_settings (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    overbooking_policy character varying(20) DEFAULT 'strict' NOT NULL,
    folio_posting boolean DEFAULT true NOT NULL,
    min_nights integer DEFAULT 1 NOT NULL,
    ota_channel_sync boolean DEFAULT true NOT NULL,
    slot_duration_mins integer DEFAULT 60 NOT NULL,
    default_party_size integer DEFAULT 2 NOT NULL,
    noshow_cutoff time without time zone DEFAULT '18:00:00'::time without time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_property_reservation_settings_default_party_size CHECK ((default_party_size > 0)),
    CONSTRAINT chk_property_reservation_settings_min_nights CHECK ((min_nights > 0)),
    CONSTRAINT chk_property_reservation_settings_overbooking_policy CHECK (((overbooking_policy)::text = ANY ((ARRAY['strict', 'warning', 'override'])::text[]))),
    CONSTRAINT chk_property_reservation_settings_slot_duration CHECK ((slot_duration_mins > 0))
);


-- property_frontdesk_settings
CREATE TABLE property_frontdesk_settings (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    reception_mode_24hr boolean DEFAULT true NOT NULL,
    open_time time without time zone DEFAULT '00:00:00'::time without time zone NOT NULL,
    close_time time without time zone DEFAULT '23:59:00'::time without time zone NOT NULL,
    early_checkin_fee_enabled boolean DEFAULT false NOT NULL,
    early_fee_amount numeric(12,2) DEFAULT 0 NOT NULL,
    late_checkout_fee_enabled boolean DEFAULT false NOT NULL,
    late_fee_amount numeric(12,2) DEFAULT 0 NOT NULL,
    room_assignment_mode character varying(30) DEFAULT 'drag_drop' NOT NULL,
    walkin_payment_policy character varying(30) DEFAULT 'deposit' NOT NULL,
    rfid_key_enabled boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_property_frontdesk_settings_early_fee CHECK ((early_fee_amount >= (0)::numeric)),
    CONSTRAINT chk_property_frontdesk_settings_late_fee CHECK ((late_fee_amount >= (0)::numeric)),
    CONSTRAINT chk_property_frontdesk_settings_room_assignment_mode CHECK (((room_assignment_mode)::text = ANY ((ARRAY['drag_drop', 'click_to_assign', 'auto_assign'])::text[]))),
    CONSTRAINT chk_property_frontdesk_settings_walkin_payment_policy CHECK (((walkin_payment_policy)::text = ANY ((ARRAY['deposit', 'full_prepay', 'post_billing'])::text[])))
);


-- shift_templates
CREATE TABLE shift_templates (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    department_id uuid,
    name text NOT NULL,
    code character varying(30) NOT NULL,
    starts_at time without time zone NOT NULL,
    ends_at time without time zone NOT NULL,
    grace_minutes integer DEFAULT 0 NOT NULL,
    is_overnight boolean DEFAULT false NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_shift_templates_grace_minutes CHECK ((grace_minutes >= 0))
);


-- property_housekeeping_settings
CREATE TABLE property_housekeeping_settings (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    checkout_state_transition character varying(20) DEFAULT 'dirty' NOT NULL,
    supervisor_inspection_lock character varying(20) DEFAULT 'required' NOT NULL,
    cleaning_cycle character varying(20) DEFAULT 'daily' NOT NULL,
    turnover_timer_mins integer DEFAULT 45 NOT NULL,
    midstay_interval_days integer DEFAULT 3 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_property_housekeeping_settings_checkout_state CHECK (((checkout_state_transition)::text = ANY ((ARRAY['dirty', 'clean'])::text[]))),
    CONSTRAINT chk_property_housekeeping_settings_cleaning_cycle CHECK (((cleaning_cycle)::text = ANY ((ARRAY['daily', 'on_request', 'checkout_only', 'custom'])::text[]))),
    CONSTRAINT chk_property_housekeeping_settings_midstay_interval CHECK ((midstay_interval_days > 0)),
    CONSTRAINT chk_property_housekeeping_settings_supervisor_lock CHECK (((supervisor_inspection_lock)::text = ANY ((ARRAY['required', 'auto_release'])::text[]))),
    CONSTRAINT chk_property_housekeeping_settings_turnover_timer CHECK ((turnover_timer_mins > 0))
);


-- property_billing_settings
CREATE TABLE property_billing_settings (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    checkout_document_mode character varying(20) DEFAULT 'single_invoice' NOT NULL,
    default_payment_terms character varying(20) DEFAULT 'due_on_receipt' NOT NULL,
    billing_frequency character varying(20) DEFAULT 'monthly' NOT NULL,
    invoice_generation_day smallint DEFAULT 1 NOT NULL,
    penalty_overdue_enabled boolean DEFAULT false NOT NULL,
    penalty_pct numeric(6,4) DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_property_billing_settings_billing_frequency CHECK (((billing_frequency)::text = ANY ((ARRAY['weekly', 'monthly', 'quarterly', 'annually'])::text[]))),
    CONSTRAINT chk_property_billing_settings_checkout_document_mode CHECK (((checkout_document_mode)::text = ANY ((ARRAY['folio', 'single_invoice', 'immediate_receipt'])::text[]))),
    CONSTRAINT chk_property_billing_settings_default_payment_terms CHECK (((default_payment_terms)::text = ANY ((ARRAY['due_on_receipt', 'net_7', 'net_14', 'net_30'])::text[]))),
    CONSTRAINT chk_property_billing_settings_invoice_day CHECK (((invoice_generation_day >= 1) AND (invoice_generation_day <= 31))),
    CONSTRAINT chk_property_billing_settings_penalty_pct CHECK (((penalty_pct >= (0)::numeric) AND (penalty_pct <= (1)::numeric)))
);


-- ================================================================================
-- 10. BILLING, PAYMENTS & FISCAL
-- ================================================================================

-- folios
CREATE TABLE folios (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    property_id uuid,
    reservation_id uuid,
    parent_folio_id uuid,
    folio_type text DEFAULT 'guest' NOT NULL,
    status text DEFAULT 'open' NOT NULL,
    currency_code character(3) DEFAULT 'TZS'::bpchar NOT NULL,
    subtotal numeric(15,2) DEFAULT 0 NOT NULL,
    tax_amount numeric(15,2) DEFAULT 0 NOT NULL,
    service_charge numeric(15,2) DEFAULT 0 NOT NULL,
    tourism_levy numeric(15,2) DEFAULT 0 NOT NULL,
    total_amount numeric(15,2) DEFAULT 0 NOT NULL,
    total_paid numeric(15,2) DEFAULT 0 NOT NULL,
    opened_at timestamp with time zone DEFAULT now() NOT NULL,
    closed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now(),
    deleted_at timestamp with time zone,
    CONSTRAINT chk_folios_folio_type CHECK ((folio_type = ANY (ARRAY['guest', 'master', 'company', 'event', 'misc']))),
    CONSTRAINT chk_folios_status CHECK ((status = ANY (ARRAY['open', 'closed', 'voided'])))
);


-- folio_charges
CREATE TABLE folio_charges (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    folio_id uuid NOT NULL,
    revenue_center_id uuid,
    charge_type character varying(50) DEFAULT 'MISC' NOT NULL,
    description text NOT NULL,
    source_type character varying(50),
    source_id uuid,
    quantity numeric(10,3) DEFAULT 1 NOT NULL,
    unit_price numeric(15,2) DEFAULT 0 NOT NULL,
    subtotal numeric(15,2) DEFAULT 0 NOT NULL,
    tax_rate numeric(5,2) DEFAULT 0 NOT NULL,
    tax_amount numeric(15,2) DEFAULT 0 NOT NULL,
    amount numeric(15,2) DEFAULT 0 NOT NULL,
    posted_at timestamp with time zone DEFAULT now() NOT NULL,
    posted_by uuid,
    status text DEFAULT 'POSTED' NOT NULL,
    is_reversed boolean DEFAULT false NOT NULL,
    reversal_of uuid,
    voided_at timestamp with time zone,
    voided_by uuid,
    void_reason text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone,
    CONSTRAINT chk_folio_charges_amount_eq CHECK ((amount = round((subtotal + tax_amount), 2))),
    CONSTRAINT chk_folio_charges_charge_type CHECK (((charge_type)::text = ANY ((ARRAY['ROOM', 'F&B', 'LAUNDRY', 'MINIBAR', 'SPA', 'PARKING', 'TELEPHONE', 'TRANSFER', 'TAX', 'FEE', 'MISC'])::text[]))),
    CONSTRAINT chk_folio_charges_status CHECK ((status = ANY (ARRAY['POSTED', 'REVERSED', 'VOIDED']))),
    CONSTRAINT chk_folio_charges_subtotal_eq CHECK ((subtotal = round((quantity * unit_price), 2)))
);


-- folio_payments
CREATE TABLE folio_payments (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    folio_id uuid NOT NULL,
    payment_method text NOT NULL,
    amount numeric(15,2) DEFAULT 0 NOT NULL,
    currency_code character(3) DEFAULT 'TZS'::bpchar NOT NULL,
    exchange_rate numeric(10,6) DEFAULT 1 NOT NULL,
    reference_number text,
    idempotency_key text,
    status text DEFAULT 'POSTED' NOT NULL,
    is_reversed boolean DEFAULT false NOT NULL,
    reversal_of uuid,
    paid_at timestamp with time zone,
    processed_by uuid,
    created_by uuid,
    notes text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone,
    CONSTRAINT chk_folio_payments_method CHECK ((payment_method = ANY (ARRAY['cash', 'visa_card', 'mastercard', 'amex', 'mpesa', 'tigo_pesa', 'airtel_money', 'halotel', 'bank_transfer', 'cheque', 'credit_note', 'room_charge', 'mobile_money']))),
    CONSTRAINT chk_folio_payments_status CHECK ((status = ANY (ARRAY['POSTED', 'REVERSED', 'FAILED', 'PENDING'])))
);


-- payment_providers
CREATE TABLE payment_providers (
    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id uuid NOT NULL,
    provider_code character varying(50) NOT NULL,
    name text NOT NULL,
    provider_type character varying(30) NOT NULL,
    country_code character(2),
    supported_currencies text[] DEFAULT '{}'::text[] NOT NULL,
    supports_collections boolean DEFAULT true NOT NULL,
    supports_disbursements boolean DEFAULT false NOT NULL,
    supports_reversals boolean DEFAULT false NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    metadata jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_payment_providers_type CHECK (((provider_type)::text = ANY ((ARRAY['mobile_money', 'card', 'bank', 'cash', 'wallet', 'aggregator', 'other'])::text[]))),
    UNIQUE (tenant_id, provider_code)
);


-- payment_provider_accounts
CREATE TABLE payment_provider_accounts (
    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id uuid NOT NULL,
    property_id uuid,
    provider_id uuid NOT NULL,
    account_name text NOT NULL,
    merchant_id text,
    till_number text,
    paybill_number text,
    wallet_number text,
    settlement_bank_account jsonb DEFAULT '{}'::jsonb NOT NULL,
    secret_ref text,
    webhook_secret_ref text,
    callback_url text,
    is_default boolean DEFAULT false NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_payment_provider_accounts_secret_ref CHECK ((is_active = false OR secret_ref IS NOT NULL)),
    UNIQUE (tenant_id, provider_id, account_name)
);


-- payment_webhook_events
CREATE TABLE payment_webhook_events (
    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id uuid NOT NULL,
    provider_account_id uuid NOT NULL,
    provider_event_id text NOT NULL,
    event_type text NOT NULL,
    payload jsonb NOT NULL,
    received_at timestamp with time zone DEFAULT now() NOT NULL,
    processed_at timestamp with time zone,
    status character varying(20) DEFAULT 'received' NOT NULL,
    error_message text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_payment_webhook_events_status CHECK (((status)::text = ANY ((ARRAY['received', 'processed', 'ignored', 'failed'])::text[]))),
    UNIQUE (tenant_id, provider_account_id, provider_event_id)
);


-- payment_transactions
CREATE TABLE payment_transactions (
    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id uuid NOT NULL,
    property_id uuid,
    provider_account_id uuid,
    webhook_event_id uuid,
    folio_payment_id uuid,
    reservation_deposit_id uuid,
    transaction_direction character varying(20) NOT NULL,
    transaction_type character varying(30) NOT NULL,
    provider_reference text,
    internal_reference text,
    payer_identifier text,
    payee_identifier text,
    amount numeric(15,2) NOT NULL,
    fee_amount numeric(15,2) DEFAULT 0 NOT NULL,
    net_amount numeric(15,2) GENERATED ALWAYS AS (amount - fee_amount) STORED,
    currency character(3) DEFAULT 'TZS'::bpchar NOT NULL,
    status character varying(30) DEFAULT 'pending' NOT NULL,
    initiated_at timestamp with time zone DEFAULT now() NOT NULL,
    confirmed_at timestamp with time zone,
    failed_at timestamp with time zone,
    reversed_at timestamp with time zone,
    failure_reason text,
    metadata jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_payment_transactions_amounts CHECK (((amount > (0)::numeric) AND (fee_amount >= (0)::numeric) AND (fee_amount <= amount))),
    CONSTRAINT chk_payment_transactions_direction CHECK (((transaction_direction)::text = ANY ((ARRAY['inbound', 'outbound'])::text[]))),
    CONSTRAINT chk_payment_transactions_status CHECK (((status)::text = ANY ((ARRAY['initiated', 'pending', 'confirmed', 'failed', 'reversed', 'cancelled'])::text[]))),
    CONSTRAINT chk_payment_transactions_type CHECK (((transaction_type)::text = ANY ((ARRAY['collection', 'refund', 'disbursement', 'reversal', 'settlement_adjustment'])::text[]))),
    UNIQUE (tenant_id, provider_account_id, provider_reference)
);


-- payment_reconciliations
CREATE TABLE payment_reconciliations (
    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id uuid NOT NULL,
    property_id uuid,
    provider_account_id uuid NOT NULL,
    reconciliation_date date NOT NULL,
    statement_reference text,
    opening_balance numeric(15,2) DEFAULT 0 NOT NULL,
    provider_total numeric(15,2) DEFAULT 0 NOT NULL,
    system_total numeric(15,2) DEFAULT 0 NOT NULL,
    variance numeric(15,2) GENERATED ALWAYS AS (provider_total - system_total) STORED,
    currency character(3) DEFAULT 'TZS'::bpchar NOT NULL,
    status character varying(20) DEFAULT 'draft' NOT NULL,
    reconciled_by uuid,
    reconciled_at timestamp with time zone,
    notes text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_payment_reconciliations_status CHECK (((status)::text = ANY ((ARRAY['draft', 'matched', 'variance', 'approved', 'voided'])::text[]))),
    UNIQUE (tenant_id, provider_account_id, reconciliation_date, statement_reference)
);


-- payment_reconciliation_items
CREATE TABLE payment_reconciliation_items (
    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id uuid NOT NULL,
    reconciliation_id uuid NOT NULL,
    payment_transaction_id uuid,
    folio_payment_id uuid,
    provider_reference text,
    item_date timestamp with time zone NOT NULL,
    provider_amount numeric(15,2) NOT NULL,
    system_amount numeric(15,2) DEFAULT 0 NOT NULL,
    variance numeric(15,2) GENERATED ALWAYS AS (provider_amount - system_amount) STORED,
    match_status character varying(20) DEFAULT 'unmatched' NOT NULL,
    notes text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_payment_reconciliation_items_amounts CHECK (((provider_amount >= (0)::numeric) AND (system_amount >= (0)::numeric))),
    CONSTRAINT chk_payment_reconciliation_items_match_status CHECK (((match_status)::text = ANY ((ARRAY['unmatched', 'matched', 'variance', 'ignored'])::text[])))
);


-- mobile_money_disbursements
CREATE TABLE mobile_money_disbursements (
    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id uuid NOT NULL,
    provider_account_id uuid NOT NULL,
    payment_transaction_id uuid,
    supplier_id uuid,
    payroll_record_id uuid,
    recipient_name text NOT NULL,
    recipient_phone character varying(30) NOT NULL,
    recipient_reference text,
    purpose character varying(30) NOT NULL,
    amount numeric(15,2) NOT NULL,
    fee_amount numeric(15,2) DEFAULT 0 NOT NULL,
    currency character(3) DEFAULT 'TZS'::bpchar NOT NULL,
    status character varying(30) DEFAULT 'draft' NOT NULL,
    approved_by uuid,
    approved_at timestamp with time zone,
    sent_at timestamp with time zone,
    confirmed_at timestamp with time zone,
    failure_reason text,
    created_by uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_mobile_money_disbursements_amounts CHECK (((amount > (0)::numeric) AND (fee_amount >= (0)::numeric))),
    CONSTRAINT chk_mobile_money_disbursements_purpose CHECK (((purpose)::text = ANY ((ARRAY['supplier_payment', 'payroll', 'guest_refund', 'petty_cash', 'other'])::text[]))),
    CONSTRAINT chk_mobile_money_disbursements_status CHECK (((status)::text = ANY ((ARRAY['draft', 'approved', 'sent', 'confirmed', 'failed', 'cancelled', 'reversed'])::text[])))
);


-- invoices
CREATE TABLE invoices (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    property_id uuid,
    folio_id uuid NOT NULL,
    invoice_number_formatted character varying(30),
    subtotal numeric(15,2) DEFAULT 0 NOT NULL,
    vat_total numeric(15,2) DEFAULT 0 NOT NULL,
    service_charge numeric(15,2) DEFAULT 0 NOT NULL,
    tourism_levy numeric(15,2) DEFAULT 0 NOT NULL,
    total numeric(15,2) DEFAULT 0 NOT NULL,
    currency_code character(3) DEFAULT 'TZS'::bpchar NOT NULL,
    status character varying(20) DEFAULT 'draft' NOT NULL,
    due_date date,
    issued_at timestamp with time zone,
    pdf_url text,
    created_by uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone,
    company_id uuid,
    CONSTRAINT chk_invoices_status CHECK (((status)::text = ANY ((ARRAY['draft', 'issued', 'sent', 'paid', 'voided', 'overdue'])::text[])))
);


-- invoice_items
CREATE TABLE invoice_items (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    property_id uuid,
    invoice_id uuid NOT NULL,
    revenue_center_id uuid,
    description text NOT NULL,
    amount numeric(15,2) DEFAULT 0 NOT NULL,
    vat_amount numeric(15,2) DEFAULT 0 NOT NULL,
    status text DEFAULT 'POSTED' NOT NULL,
    is_reversed boolean DEFAULT false NOT NULL,
    reversal_of uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


-- document_sequences
CREATE TABLE document_sequences (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    document_type character varying(50) NOT NULL,
    prefix character varying(20) NOT NULL,
    year smallint NOT NULL,
    next_value bigint DEFAULT 1 NOT NULL,
    padding smallint DEFAULT 5 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


-- fiscal_receipts
CREATE TABLE fiscal_receipts (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    invoice_id uuid NOT NULL,
    fiscal_mode character varying(20) DEFAULT 'NONE' NOT NULL,
    receipt_number text NOT NULL,
    fiscal_code text,
    verification_code character varying(100),
    qr_code_url text,
    submitted_at timestamp with time zone DEFAULT now() NOT NULL,
    response_payload jsonb,
    status character varying(20) DEFAULT 'submitted' NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_fiscal_receipts_status CHECK (((status)::text = ANY ((ARRAY['submitted', 'accepted', 'rejected', 'pending'])::text[])))
);


-- fiscal_providers
CREATE TABLE fiscal_providers (
    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    provider_code character varying(50) NOT NULL UNIQUE,
    country_code character(2) NOT NULL,
    name text NOT NULL,
    authority_name text NOT NULL,
    fiscal_mode character varying(30) NOT NULL,
    supports_realtime boolean DEFAULT true NOT NULL,
    supports_batch boolean DEFAULT false NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    metadata jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


-- fiscal_provider_configs
CREATE TABLE fiscal_provider_configs (
    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id uuid NOT NULL,
    property_id uuid,
    provider_id uuid NOT NULL,
    environment character varying(20) DEFAULT 'production' NOT NULL,
    device_serial text,
    branch_code text,
    taxpayer_identifier text,
    endpoint_url text,
    secret_ref text,
    routing_config jsonb DEFAULT '{}'::jsonb NOT NULL,
    is_default boolean DEFAULT false NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_fiscal_provider_configs_environment CHECK (((environment)::text = ANY ((ARRAY['sandbox', 'production'])::text[]))),
    CONSTRAINT chk_fiscal_provider_configs_secret_ref CHECK ((is_active = false OR secret_ref IS NOT NULL)),
    UNIQUE (tenant_id, property_id, provider_id, environment)
);


-- fiscal_submission_batches
CREATE TABLE fiscal_submission_batches (
    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id uuid NOT NULL,
    property_id uuid,
    provider_config_id uuid NOT NULL,
    batch_reference text NOT NULL,
    period_start timestamp with time zone,
    period_end timestamp with time zone,
    document_count integer DEFAULT 0 NOT NULL,
    status character varying(20) DEFAULT 'pending' NOT NULL,
    submitted_at timestamp with time zone,
    accepted_at timestamp with time zone,
    rejected_at timestamp with time zone,
    response_payload jsonb,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_fiscal_submission_batches_count CHECK ((document_count >= 0)),
    CONSTRAINT chk_fiscal_submission_batches_dates CHECK (((period_end IS NULL) OR (period_start IS NULL) OR (period_end >= period_start))),
    CONSTRAINT chk_fiscal_submission_batches_status CHECK (((status)::text = ANY ((ARRAY['pending', 'submitted', 'accepted', 'rejected', 'partial', 'voided'])::text[]))),
    UNIQUE (tenant_id, provider_config_id, batch_reference)
);


-- fiscal_submission_attempts
CREATE TABLE fiscal_submission_attempts (
    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id uuid NOT NULL,
    provider_config_id uuid NOT NULL,
    batch_id uuid,
    fiscal_receipt_id uuid,
    attempt_no integer NOT NULL,
    request_payload jsonb NOT NULL,
    response_payload jsonb,
    status character varying(20) DEFAULT 'pending' NOT NULL,
    error_code text,
    error_message text,
    attempted_at timestamp with time zone DEFAULT now() NOT NULL,
    completed_at timestamp with time zone,
    CONSTRAINT chk_fiscal_submission_attempts_attempt CHECK ((attempt_no > 0)),
    CONSTRAINT chk_fiscal_submission_attempts_status CHECK (((status)::text = ANY ((ARRAY['pending', 'success', 'failed', 'timeout', 'ignored'])::text[])))
);


-- fiscal_document_mappings
CREATE TABLE fiscal_document_mappings (
    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id uuid NOT NULL,
    provider_config_id uuid NOT NULL,
    invoice_id uuid,
    credit_note_id uuid,
    fiscal_receipt_id uuid,
    provider_document_id text NOT NULL,
    provider_document_number text,
    fiscal_signature text,
    qr_code_url text,
    status character varying(20) DEFAULT 'active' NOT NULL,
    mapped_at timestamp with time zone DEFAULT now() NOT NULL,
    metadata jsonb DEFAULT '{}'::jsonb NOT NULL,
    CONSTRAINT chk_fiscal_document_mappings_status CHECK (((status)::text = ANY ((ARRAY['active', 'voided', 'replaced'])::text[]))),
    UNIQUE (tenant_id, provider_config_id, provider_document_id)
);


-- tax_jurisdictions
CREATE TABLE tax_jurisdictions (
    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id uuid,
    country_code character(2) NOT NULL,
    region_code character varying(20),
    name text NOT NULL,
    authority_name text,
    currency character(3) NOT NULL,
    fiscal_provider_id uuid,
    rules jsonb DEFAULT '{}'::jsonb NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    effective_from date DEFAULT CURRENT_DATE NOT NULL,
    effective_to date,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_tax_jurisdictions_dates CHECK (((effective_to IS NULL) OR (effective_to >= effective_from))),
    UNIQUE (tenant_id, country_code, region_code, name)
);


-- tax_report_runs
CREATE TABLE tax_report_runs (
    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id uuid NOT NULL,
    property_id uuid,
    jurisdiction_id uuid,
    report_type character varying(30) NOT NULL,
    period_start date NOT NULL,
    period_end date NOT NULL,
    currency character(3) NOT NULL,
    taxable_sales numeric(15,2) DEFAULT 0 NOT NULL,
    exempt_sales numeric(15,2) DEFAULT 0 NOT NULL,
    tax_collected numeric(15,2) DEFAULT 0 NOT NULL,
    adjustments numeric(15,2) DEFAULT 0 NOT NULL,
    net_tax_due numeric(15,2) GENERATED ALWAYS AS (tax_collected + adjustments) STORED,
    status character varying(20) DEFAULT 'draft' NOT NULL,
    generated_by uuid,
    generated_at timestamp with time zone DEFAULT now() NOT NULL,
    submitted_at timestamp with time zone,
    accepted_at timestamp with time zone,
    response_payload jsonb,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_tax_report_runs_amounts CHECK (((taxable_sales >= (0)::numeric) AND (exempt_sales >= (0)::numeric) AND (tax_collected >= (0)::numeric))),
    CONSTRAINT chk_tax_report_runs_dates CHECK ((period_end >= period_start)),
    CONSTRAINT chk_tax_report_runs_status CHECK (((status)::text = ANY ((ARRAY['draft', 'generated', 'submitted', 'accepted', 'rejected', 'amended', 'voided'])::text[]))),
    CONSTRAINT chk_tax_report_runs_type CHECK (((report_type)::text = ANY ((ARRAY['vat', 'levy', 'withholding', 'sales_tax', 'tourism_levy', 'fiscal_summary'])::text[]))),
    UNIQUE (tenant_id, property_id, report_type, period_start, period_end)
);


-- tax_report_lines
CREATE TABLE tax_report_lines (
    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id uuid NOT NULL,
    report_run_id uuid NOT NULL,
    tax_rate_id uuid,
    invoice_id uuid,
    credit_note_id uuid,
    line_type character varying(30) NOT NULL,
    taxable_amount numeric(15,2) DEFAULT 0 NOT NULL,
    exempt_amount numeric(15,2) DEFAULT 0 NOT NULL,
    tax_amount numeric(15,2) DEFAULT 0 NOT NULL,
    document_count integer DEFAULT 0 NOT NULL,
    metadata jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_tax_report_lines_amounts CHECK (((taxable_amount >= (0)::numeric) AND (exempt_amount >= (0)::numeric) AND (tax_amount >= (0)::numeric) AND (document_count >= 0))),
    CONSTRAINT chk_tax_report_lines_type CHECK (((line_type)::text = ANY ((ARRAY['sale', 'exempt_sale', 'credit_note', 'adjustment', 'rounding', 'summary'])::text[])))
);


-- booking_policies
CREATE TABLE booking_policies (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    property_id uuid,
    name text NOT NULL,
    policy_type character varying(30) NOT NULL,
    applies_to character varying(30) DEFAULT 'reservation' NOT NULL,
    rules jsonb DEFAULT '{}'::jsonb NOT NULL,
    is_default boolean DEFAULT false NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    effective_from date DEFAULT CURRENT_DATE NOT NULL,
    effective_to date,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_booking_policies_applies_to CHECK (((applies_to)::text = ANY ((ARRAY['reservation', 'room_type', 'rate_plan', 'channel', 'group', 'event'])::text[]))),
    CONSTRAINT chk_booking_policies_dates CHECK (((effective_to IS NULL) OR (effective_to >= effective_from))),
    CONSTRAINT chk_booking_policies_type CHECK (((policy_type)::text = ANY ((ARRAY['cancellation', 'deposit', 'refund', 'no_show', 'modification'])::text[])))
);


-- reservation_policy_snapshots
CREATE TABLE reservation_policy_snapshots (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    reservation_id uuid NOT NULL,
    policy_id uuid,
    policy_type character varying(30) NOT NULL,
    snapshot jsonb DEFAULT '{}'::jsonb NOT NULL,
    accepted_by_guest boolean DEFAULT false NOT NULL,
    accepted_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_reservation_policy_snapshots_type CHECK (((policy_type)::text = ANY ((ARRAY['cancellation', 'deposit', 'refund', 'no_show', 'modification'])::text[])))
);


-- reservation_deposits
CREATE TABLE reservation_deposits (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    reservation_id uuid NOT NULL,
    folio_id uuid,
    policy_snapshot_id uuid,
    required_amount numeric(12,2) DEFAULT 0 NOT NULL,
    paid_amount numeric(12,2) DEFAULT 0 NOT NULL,
    refunded_amount numeric(12,2) DEFAULT 0 NOT NULL,
    forfeited_amount numeric(12,2) DEFAULT 0 NOT NULL,
    currency character(3) DEFAULT 'TZS'::bpchar NOT NULL,
    status character varying(30) DEFAULT 'required' NOT NULL,
    due_at timestamp with time zone,
    paid_at timestamp with time zone,
    refunded_at timestamp with time zone,
    notes text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_reservation_deposits_amounts CHECK (((required_amount >= (0)::numeric) AND (paid_amount >= (0)::numeric) AND (refunded_amount >= (0)::numeric) AND (forfeited_amount >= (0)::numeric))),
    CONSTRAINT chk_reservation_deposits_status CHECK (((status)::text = ANY ((ARRAY['required', 'waived', 'partially_paid', 'paid', 'refunded', 'forfeited', 'cancelled'])::text[])))
);


-- folio_charge_taxes
CREATE TABLE folio_charge_taxes (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    folio_charge_id uuid NOT NULL,
    tax_rate_id uuid,
    tax_type character varying(30) NOT NULL,
    taxable_amount numeric(15,2) NOT NULL,
    rate numeric(8,6) NOT NULL,
    tax_amount numeric(15,2) NOT NULL,
    is_inclusive boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_folio_charge_taxes_amounts CHECK (((taxable_amount >= (0)::numeric) AND (rate >= (0)::numeric) AND (tax_amount >= (0)::numeric)))
);


-- invoice_item_taxes
CREATE TABLE invoice_item_taxes (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    invoice_item_id uuid NOT NULL,
    tax_rate_id uuid,
    tax_type character varying(30) NOT NULL,
    taxable_amount numeric(15,2) NOT NULL,
    rate numeric(8,6) NOT NULL,
    tax_amount numeric(15,2) NOT NULL,
    is_inclusive boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_invoice_item_taxes_amounts CHECK (((taxable_amount >= (0)::numeric) AND (rate >= (0)::numeric) AND (tax_amount >= (0)::numeric)))
);


-- currency_rates
CREATE TABLE currency_rates (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    from_currency character(3) NOT NULL,
    to_currency character(3) NOT NULL,
    rate numeric(16,6) NOT NULL,
    rate_date date DEFAULT CURRENT_DATE NOT NULL,
    source text DEFAULT 'manual' NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_by uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT currency_rates_rate_check CHECK ((rate > (0)::numeric)),
    CONSTRAINT currency_rates_source_check CHECK ((source = ANY (ARRAY['manual', 'nbt', 'cbk', 'api', 'xe'])))
);


-- tax_rates
CREATE TABLE tax_rates (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    name text NOT NULL,
    code text NOT NULL,
    rate numeric(6,4) NOT NULL,
    tax_type text NOT NULL,
    applies_to text[] DEFAULT '{}'::text[] NOT NULL,
    is_compound boolean DEFAULT false NOT NULL,
    is_inclusive boolean DEFAULT false NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    effective_from date DEFAULT CURRENT_DATE NOT NULL,
    effective_to date,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT tax_rates_check CHECK (((effective_to IS NULL) OR (effective_to > effective_from))),
    CONSTRAINT tax_rates_rate_check CHECK (((rate >= (0)::numeric) AND (rate <= (1)::numeric))),
    CONSTRAINT tax_rates_tax_type_check CHECK ((tax_type = ANY (ARRAY['vat', 'levy', 'service_charge', 'tourism_levy', 'exempt', 'other'])))
);


-- accounting_accounts
CREATE TABLE accounting_accounts (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    property_id uuid,
    code character varying(30) NOT NULL,
    name text NOT NULL,
    account_type character varying(20) NOT NULL,
    parent_account_id uuid,
    currency character(3) DEFAULT 'TZS'::bpchar NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_accounting_accounts_type CHECK (((account_type)::text = ANY ((ARRAY['asset', 'liability', 'equity', 'revenue', 'expense', 'contra_asset', 'contra_revenue'])::text[])))
);


-- journal_entries
CREATE TABLE journal_entries (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    property_id uuid,
    entry_number character varying(50) NOT NULL,
    entry_date date NOT NULL,
    source_type character varying(50),
    source_id uuid,
    memo text,
    currency character(3) DEFAULT 'TZS'::bpchar NOT NULL,
    total_debit numeric(15,2) DEFAULT 0 NOT NULL,
    total_credit numeric(15,2) DEFAULT 0 NOT NULL,
    status character varying(20) DEFAULT 'draft' NOT NULL,
    posted_by uuid,
    posted_at timestamp with time zone,
    voided_by uuid,
    voided_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_journal_entries_balanced CHECK ((((status)::text = 'draft'::text) OR (total_debit = total_credit))),
    CONSTRAINT chk_journal_entries_non_negative CHECK (((total_debit >= (0)::numeric) AND (total_credit >= (0)::numeric))),
    CONSTRAINT chk_journal_entries_status_metadata CHECK ((((status)::text <> 'posted'::text) OR ((posted_by IS NOT NULL) AND (posted_at IS NOT NULL))) AND (((status)::text <> 'voided'::text) OR ((voided_by IS NOT NULL) AND (voided_at IS NOT NULL)))),
    CONSTRAINT chk_journal_entries_status CHECK (((status)::text = ANY ((ARRAY['draft', 'posted', 'voided'])::text[])))
);


-- journal_entry_lines
CREATE TABLE journal_entry_lines (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    property_id uuid,
    journal_entry_id uuid NOT NULL,
    account_id uuid NOT NULL,
    revenue_center_id uuid,
    line_no integer NOT NULL,
    description text,
    debit numeric(15,2) DEFAULT 0 NOT NULL,
    credit numeric(15,2) DEFAULT 0 NOT NULL,
    source_type character varying(50),
    source_id uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_journal_entry_lines_amount CHECK (((debit >= (0)::numeric) AND (credit >= (0)::numeric) AND ((debit > (0)::numeric) <> (credit > (0)::numeric)))),
    CONSTRAINT chk_journal_entry_lines_line_no CHECK ((line_no > 0))
);


-- ================================================================================
-- 11. HOUSEKEEPING & MAINTENANCE
-- ================================================================================

-- housekeeping_tasks
CREATE TABLE housekeeping_tasks (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    room_id uuid NOT NULL,
    type character varying(30),
    status text DEFAULT 'pending' NOT NULL,
    priority smallint DEFAULT 1 NOT NULL,
    scheduled_date date NOT NULL,
    started_at timestamp with time zone,
    completed_at timestamp with time zone,
    notes text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_housekeeping_tasks_status CHECK ((status = ANY (ARRAY['pending', 'in_progress', 'completed', 'cancelled', 'skipped']))),
    CONSTRAINT chk_housekeeping_tasks_type CHECK (((type)::text = ANY ((ARRAY['stayover_clean', 'departure_clean', 'deep_clean', 'turndown', 'inspection', 'linen_change'])::text[])))
);


-- housekeeping_assignments
CREATE TABLE housekeeping_assignments (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    task_id uuid NOT NULL,
    user_id uuid NOT NULL,
    assigned_at timestamp with time zone DEFAULT now() NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


-- maintenance_requests
CREATE TABLE maintenance_requests (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    room_id uuid NOT NULL,
    reported_by uuid,
    category character varying(100),
    description text NOT NULL,
    priority character varying(20) DEFAULT 'medium' NOT NULL,
    status text DEFAULT 'open' NOT NULL,
    assigned_to uuid,
    resolved_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_maintenance_requests_priority CHECK (((priority)::text = ANY ((ARRAY['low', 'medium', 'high', 'critical'])::text[]))),
    CONSTRAINT chk_maintenance_requests_status CHECK ((status = ANY (ARRAY['open', 'in_progress', 'completed', 'cancelled', 'deferred'])))
);


-- work_orders
CREATE TABLE work_orders (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    request_id uuid,
    assigned_to uuid,
    title text NOT NULL,
    description text,
    priority text DEFAULT 'normal' NOT NULL,
    category text DEFAULT 'general' NOT NULL,
    status text DEFAULT 'open' NOT NULL,
    estimated_hours numeric(5,2),
    actual_hours numeric(5,2),
    parts_cost numeric(12,2) DEFAULT 0 NOT NULL,
    labour_cost numeric(12,2) DEFAULT 0 NOT NULL,
    scheduled_date date,
    started_at timestamp with time zone,
    completed_at timestamp with time zone,
    verified_by uuid,
    verified_at timestamp with time zone,
    notes text,
    deleted_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT work_orders_category_check CHECK ((category = ANY (ARRAY['plumbing', 'electrical', 'hvac', 'carpentry', 'painting', 'cleaning', 'pest_control', 'it', 'general', 'preventive']))),
    CONSTRAINT work_orders_priority_check CHECK ((priority = ANY (ARRAY['low', 'normal', 'high', 'emergency']))),
    CONSTRAINT work_orders_status_check CHECK ((status = ANY (ARRAY['open', 'in_progress', 'on_hold', 'completed', 'cancelled'])))
);


-- lost_and_found
CREATE TABLE lost_and_found (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    room_id uuid,
    description text NOT NULL,
    found_at timestamp with time zone DEFAULT now() NOT NULL,
    found_by uuid,
    status character varying(20) DEFAULT 'held' NOT NULL,
    claimed_by uuid,
    claimed_at timestamp with time zone,
    storage_location character varying(100),
    image_url text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_lost_and_found_status CHECK (((status)::text = ANY ((ARRAY['held', 'claimed', 'disposed', 'returned', 'donated'])::text[])))
);


-- ================================================================================
-- 12. FOOD & BEVERAGE — POS & KITCHEN
-- ================================================================================

-- outlets
CREATE TABLE outlets (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    revenue_center_id uuid,
    name text NOT NULL,
    type character varying(30) DEFAULT 'RESTAURANT' NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone,
    CONSTRAINT chk_outlets_type CHECK (((type)::text = ANY ((ARRAY['RESTAURANT', 'BAR', 'ROOM_SERVICE', 'POOL_BAR', 'SPA', 'BANQUET', 'CAFE', 'SHOP'])::text[])))
);


-- property_pos_settings
CREATE TABLE property_pos_settings (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    scanner_active boolean DEFAULT false NOT NULL,
    drawer_active boolean DEFAULT false NOT NULL,
    customer_display_active boolean DEFAULT false NOT NULL,
    receipt_header text,
    receipt_footer text,
    receipt_vfd_footnote text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


-- menu_categories
CREATE TABLE menu_categories (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    outlet_id uuid NOT NULL,
    name text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


-- menu_items
CREATE TABLE menu_items (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    category_id uuid NOT NULL,
    name text NOT NULL,
    price numeric(15,2) DEFAULT 0 NOT NULL,
    cost numeric(15,2),
    vat_rate numeric(5,2) DEFAULT 18 NOT NULL,
    is_available boolean DEFAULT true NOT NULL,
    inventory_item_id uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone,
    tax_rate_id uuid,
    CONSTRAINT chk_menu_items_price_positive CHECK ((price > (0)::numeric)),
    CONSTRAINT chk_menu_items_vat_rate_valid CHECK (((vat_rate >= (0)::numeric) AND (vat_rate <= (100)::numeric)))
);

COMMENT ON COLUMN folio_charges.tax_rate IS 'Deprecated compatibility snapshot. Canonical tax detail lives in folio_charge_taxes and tax_rates.';
COMMENT ON COLUMN menu_items.vat_rate IS 'Deprecated compatibility snapshot. Canonical menu-item tax configuration is menu_items.tax_rate_id -> tax_rates.';


-- menu_item_recipes
CREATE TABLE menu_item_recipes (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    menu_item_id uuid NOT NULL,
    inventory_item_id uuid NOT NULL,
    quantity numeric(15,3) NOT NULL,
    unit character varying(20) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


-- pos_sessions
CREATE TABLE pos_sessions (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    outlet_id uuid NOT NULL,
    cashier_id uuid NOT NULL,
    opened_at timestamp with time zone DEFAULT now() NOT NULL,
    closed_at timestamp with time zone,
    opening_float numeric(15,2) DEFAULT 0 NOT NULL,
    closing_cash numeric(15,2),
    expected_cash numeric(15,2),
    variance numeric(15,2),
    notes text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


-- pos_terminals
CREATE TABLE pos_terminals (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    outlet_id uuid,
    terminal_name text NOT NULL,
    activation_code character varying(20) NOT NULL,
    status character varying(20) DEFAULT 'pending' NOT NULL,
    device_metadata jsonb DEFAULT '{}'::jsonb NOT NULL,
    activated_at timestamp with time zone,
    expires_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_pos_terminals_status CHECK (((status)::text = ANY ((ARRAY['pending', 'active', 'expired', 'revoked'])::text[])))
);


-- pos_printer_routes
CREATE TABLE pos_printer_routes (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    outlet_id uuid NOT NULL,
    printer_name text NOT NULL,
    connection_type character varying(20) NOT NULL,
    ip_address character varying(45),
    printer_category character varying(30) NOT NULL,
    device_metadata jsonb DEFAULT '{}'::jsonb NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_pos_printer_routes_category CHECK (((printer_category)::text = ANY ((ARRAY['kitchen', 'receipt', 'bar', 'pass'])::text[]))),
    CONSTRAINT chk_pos_printer_routes_connection_type CHECK (((connection_type)::text = ANY ((ARRAY['TCP_IP', 'Bluetooth', 'USB'])::text[]))),
    CONSTRAINT chk_pos_printer_routes_ip_required CHECK ((((connection_type)::text <> 'TCP_IP'::text) OR (ip_address IS NOT NULL)))
);


-- pos_orders
CREATE TABLE pos_orders (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    outlet_id uuid NOT NULL,
    revenue_center_id uuid,
    folio_id uuid,
    order_number character varying(20),
    table_number character varying(20),
    order_type text DEFAULT 'dine_in' NOT NULL,
    status text DEFAULT 'open' NOT NULL,
    subtotal numeric(15,2) DEFAULT 0 NOT NULL,
    tax_amount numeric(15,2) DEFAULT 0 NOT NULL,
    total_amount numeric(15,2) DEFAULT 0 NOT NULL,
    served_by uuid,
    settled_at timestamp with time zone,
    edge_created boolean DEFAULT false NOT NULL,
    edge_sync_id uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone,
    session_id uuid,
    CONSTRAINT chk_pos_orders_order_type CHECK ((order_type = ANY (ARRAY['dine_in', 'takeaway', 'room_service', 'delivery', 'bar']))),
    CONSTRAINT chk_pos_orders_status CHECK ((status = ANY (ARRAY['open', 'closed', 'voided', 'cancelled'])))
);


-- pos_order_items
CREATE TABLE pos_order_items (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    order_id uuid NOT NULL,
    menu_item_id uuid NOT NULL,
    quantity numeric(10,3) DEFAULT 1 NOT NULL,
    unit_price numeric(15,2) DEFAULT 0 NOT NULL,
    subtotal numeric(15,2) DEFAULT 0 NOT NULL,
    tax_amount numeric(15,2) DEFAULT 0 NOT NULL,
    total_price numeric(15,2) DEFAULT 0 NOT NULL,
    modifiers jsonb DEFAULT '[]'::jsonb NOT NULL,
    special_request text,
    voided boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


-- kitchen_tickets
CREATE TABLE kitchen_tickets (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    order_id uuid NOT NULL,
    outlet_id uuid NOT NULL,
    status character varying(20) DEFAULT 'pending' NOT NULL,
    printed_at timestamp with time zone,
    ready_at timestamp with time zone,
    delivered_at timestamp with time zone,
    notes text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_kitchen_tickets_status CHECK (((status)::text = ANY ((ARRAY['pending', 'preparing', 'ready', 'delivered', 'voided'])::text[])))
);


-- cash_float_movements
CREATE TABLE cash_float_movements (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    session_id uuid NOT NULL,
    movement_type text NOT NULL,
    amount numeric(12,2) NOT NULL,
    currency character(3) DEFAULT 'TZS'::bpchar NOT NULL,
    declared_amount numeric(12,2),
    system_amount numeric(12,2),
    variance numeric(12,2) GENERATED ALWAYS AS (
CASE
    WHEN ((declared_amount IS NOT NULL) AND (system_amount IS NOT NULL)) THEN (declared_amount - system_amount)
    ELSE NULL::numeric
END) STORED,
    reason text,
    authorised_by uuid,
    created_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT cash_float_movements_movement_type_check CHECK ((movement_type = ANY (ARRAY['opening_float', 'top_up', 'withdrawal', 'petty_cash', 'end_count', 'variance'])))
);


-- shift_handovers
CREATE TABLE shift_handovers (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    department_id uuid,
    pos_session_id uuid,
    outgoing_user_id uuid NOT NULL,
    incoming_user_id uuid,
    shift_type text NOT NULL,
    handover_at timestamp with time zone DEFAULT now() NOT NULL,
    accepted_at timestamp with time zone,
    cash_float_tzs numeric(12,2) DEFAULT 0 NOT NULL,
    cash_float_usd numeric(12,2) DEFAULT 0 NOT NULL,
    open_folios integer DEFAULT 0 NOT NULL,
    pending_checkouts integer DEFAULT 0 NOT NULL,
    pending_checkins integer DEFAULT 0 NOT NULL,
    outstanding_tasks text,
    notes text,
    status text DEFAULT 'pending' NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT shift_handovers_shift_type_check CHECK ((shift_type = ANY (ARRAY['morning', 'afternoon', 'night', 'split', 'handover']))),
    CONSTRAINT shift_handovers_status_check CHECK ((status = ANY (ARRAY['pending', 'accepted', 'disputed'])))
);


-- ================================================================================
-- 13. INVENTORY & PURCHASING
-- ================================================================================

-- inventory_items
CREATE TABLE inventory_items (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    name text NOT NULL,
    category text,
    unit text NOT NULL,
    reorder_level numeric(15,3) DEFAULT 0 NOT NULL,
    cost_per_unit numeric(15,2) DEFAULT 0 NOT NULL,
    current_stock numeric(15,3) DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


-- inventory_locations
CREATE TABLE inventory_locations (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    outlet_id uuid,
    type text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


-- stock_levels
CREATE TABLE stock_levels (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    item_id uuid NOT NULL,
    location_id uuid,
    quantity numeric(15,3) DEFAULT 0 NOT NULL,
    reorder_level numeric(15,3) DEFAULT 0 NOT NULL,
    last_updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


-- stock_movements
CREATE TABLE stock_movements (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    item_id uuid NOT NULL,
    location_id uuid,
    quantity numeric(15,3) NOT NULL,
    type text NOT NULL,
    unit_cost numeric(15,2) DEFAULT 0 NOT NULL,
    total_cost numeric(15,2) DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now(),
    CONSTRAINT chk_stock_movements_costs CHECK (((unit_cost >= (0)::numeric) AND (total_cost >= (0)::numeric))),
    CONSTRAINT chk_stock_movements_quantity_direction CHECK ((((type = 'adjustment'::text) AND (quantity <> (0)::numeric)) OR ((type <> 'adjustment'::text) AND (quantity > (0)::numeric)))),
    CONSTRAINT chk_stock_movements_total_cost CHECK ((total_cost = round((abs(quantity) * unit_cost), 2))),
    CONSTRAINT chk_stock_movements_type CHECK ((type = ANY (ARRAY['purchase', 'consumption', 'waste', 'adjustment', 'transfer_in', 'transfer_out', 'return', 'opening_balance'])))
);


-- suppliers
CREATE TABLE suppliers (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    name text NOT NULL,
    contact_name character varying(200),
    contact_email character varying(254),
    contact_phone character varying(20),
    address jsonb,
    payment_terms smallint DEFAULT 30 NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone
);


-- purchase_orders
CREATE TABLE purchase_orders (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    supplier_id uuid NOT NULL,
    order_date date DEFAULT CURRENT_DATE NOT NULL,
    expected_delivery_date date,
    actual_delivery_date date,
    total_amount numeric(15,2) NOT NULL,
    status text DEFAULT 'draft' NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    approval_request_id uuid,
    CONSTRAINT chk_purchase_orders_status CHECK ((status = ANY (ARRAY['draft', 'submitted', 'approved', 'partially_delivered', 'delivered', 'cancelled'])))
);


-- purchase_order_items
CREATE TABLE purchase_order_items (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    purchase_order_id uuid NOT NULL,
    inventory_item_id uuid NOT NULL,
    quantity numeric(15,3) NOT NULL,
    unit_price numeric(15,2) NOT NULL,
    total_price numeric(15,2) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

-- ================================================================================
-- 14. EVENTS & BANQUET
-- ================================================================================

-- event_spaces
CREATE TABLE event_spaces (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    name text NOT NULL,
    code text NOT NULL,
    type text NOT NULL,
    floor_id uuid,
    capacity_theatre integer,
    capacity_classroom integer,
    capacity_banquet integer,
    capacity_cocktail integer,
    capacity_boardroom integer,
    area_sqm numeric(8,2),
    half_day_rate numeric(12,2),
    full_day_rate numeric(12,2),
    hourly_rate numeric(12,2),
    currency character(3) DEFAULT 'TZS'::bpchar NOT NULL,
    amenities text[] DEFAULT '{}'::text[] NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    deleted_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT event_spaces_type_check CHECK ((type = ANY (ARRAY['ballroom', 'conference', 'boardroom', 'garden', 'rooftop', 'restaurant_private', 'outdoor', 'chapel', 'other'])))
);


-- event_packages
CREATE TABLE event_packages (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    name text NOT NULL,
    code text NOT NULL,
    type text NOT NULL,
    duration_type text DEFAULT 'full_day' NOT NULL,
    base_price_per_pax numeric(12,2) DEFAULT 0 NOT NULL,
    min_pax integer DEFAULT 1 NOT NULL,
    includes text[] DEFAULT '{}'::text[] NOT NULL,
    description text,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT event_packages_duration_type_check CHECK ((duration_type = ANY (ARRAY['hourly', 'half_day', 'full_day', 'multi_day']))),
    CONSTRAINT event_packages_type_check CHECK ((type = ANY (ARRAY['conference', 'wedding', 'gala', 'product_launch', 'birthday', 'government', 'other'])))
);


-- event_bookings
CREATE TABLE event_bookings (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    event_space_id uuid NOT NULL,
    package_id uuid,
    booking_reference text NOT NULL,
    event_name text NOT NULL,
    event_type text NOT NULL,
    organiser_name text NOT NULL,
    organiser_phone text,
    organiser_email text,
    company_id uuid,
    guest_id uuid,
    group_id uuid,
    event_date date NOT NULL,
    start_time time without time zone NOT NULL,
    end_time time without time zone NOT NULL,
    setup_time time without time zone,
    teardown_time time without time zone,
    expected_pax integer NOT NULL,
    actual_pax integer,
    layout text,
    status text DEFAULT 'tentative' NOT NULL,
    folio_id uuid,
    deposit_amount numeric(12,2) DEFAULT 0 NOT NULL,
    deposit_paid_at timestamp with time zone,
    total_amount numeric(12,2) DEFAULT 0 NOT NULL,
    cancellation_reason text,
    notes text,
    handled_by uuid,
    deleted_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT event_bookings_check CHECK ((end_time > start_time)),
    CONSTRAINT event_bookings_event_type_check CHECK ((event_type = ANY (ARRAY['conference', 'wedding', 'gala', 'product_launch', 'birthday', 'government', 'training', 'graduation', 'other']))),
    CONSTRAINT event_bookings_expected_pax_check CHECK ((expected_pax > 0)),
    CONSTRAINT event_bookings_layout_check CHECK ((layout = ANY (ARRAY['theatre', 'classroom', 'banquet', 'cocktail', 'boardroom', 'custom']))),
    CONSTRAINT event_bookings_status_check CHECK ((status = ANY (ARRAY['tentative', 'confirmed', 'deposit_paid', 'in_progress', 'completed', 'cancelled', 'no_show'])))
);


-- event_booking_items
CREATE TABLE event_booking_items (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    booking_id uuid NOT NULL,
    item_type text NOT NULL,
    description text NOT NULL,
    quantity numeric(10,3) DEFAULT 1 NOT NULL,
    unit text DEFAULT 'unit' NOT NULL,
    unit_price numeric(12,2) NOT NULL,
    vat_rate numeric(5,2) DEFAULT 18 NOT NULL,
    subtotal numeric(12,2) GENERATED ALWAYS AS ((quantity * unit_price)) STORED,
    notes text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT event_booking_items_item_type_check CHECK ((item_type = ANY (ARRAY['venue_hire', 'catering', 'av_equipment', 'decor', 'accommodation', 'transport', 'photography', 'entertainment', 'security', 'other']))),
    CONSTRAINT event_booking_items_quantity_check CHECK ((quantity > (0)::numeric)),
    CONSTRAINT event_booking_items_unit_price_check CHECK ((unit_price >= (0)::numeric))
);


-- ================================================================================
-- 15. CHANNEL MANAGER & DIRECT BOOKING ENGINE
-- ================================================================================

-- channel_connections
CREATE TABLE channel_connections (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    channel_id uuid NOT NULL,
    provider text NOT NULL,
    secret_ref text,
    hotel_code text,
    endpoint_url text,
    connection_type text DEFAULT 'push' NOT NULL,
    is_active boolean DEFAULT false NOT NULL,
    last_sync_at timestamp with time zone,
    last_sync_status text,
    last_error text,
    sync_interval_minutes integer DEFAULT 60 NOT NULL,
    metadata jsonb DEFAULT '{}'::jsonb NOT NULL,
    deleted_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT channel_connections_secret_ref_check CHECK ((is_active = false OR secret_ref IS NOT NULL)),
    CONSTRAINT channel_connections_connection_type_check CHECK ((connection_type = ANY (ARRAY['push', 'pull', 'two_way']))),
    CONSTRAINT channel_connections_last_sync_status_check CHECK ((last_sync_status = ANY (ARRAY['success', 'failed', 'partial']))),
    CONSTRAINT channel_connections_provider_check CHECK ((provider = ANY (ARRAY['booking_com', 'expedia', 'airbnb', 'agoda', 'hotels_com', 'trivago', 'google_hotel', 'direct_api', 'other'])))
);


-- ota_room_type_mappings
CREATE TABLE ota_room_type_mappings (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    channel_connection_id uuid NOT NULL,
    room_type_id uuid NOT NULL,
    ota_room_code text NOT NULL,
    ota_room_name text,
    ota_rate_plan_code text,
    rate_plan_id uuid,
    markup_pct numeric(5,2) DEFAULT 0 NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


-- booking_engine_configs
CREATE TABLE booking_engine_configs (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    is_active boolean DEFAULT false NOT NULL,
    custom_domain text,
    primary_color character(7),
    logo_url text,
    hero_image_url text,
    tagline text,
    cancellation_policy text,
    check_in_policy text,
    min_advance_days integer DEFAULT 0 NOT NULL,
    max_advance_days integer DEFAULT 365 NOT NULL,
    default_currency character(3) DEFAULT 'TZS'::bpchar NOT NULL,
    accept_currencies text[] DEFAULT ARRAY['TZS', 'USD'] NOT NULL,
    payment_methods text[] DEFAULT ARRAY['card', 'mobile_money'] NOT NULL,
    deposit_required_pct numeric(5,2) DEFAULT 0 NOT NULL,
    show_room_availability boolean DEFAULT true NOT NULL,
    show_rate_comparison boolean DEFAULT false NOT NULL,
    ga_tracking_id text,
    meta_pixel_id text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT booking_engine_configs_deposit_required_pct_check CHECK (((deposit_required_pct >= (0)::numeric) AND (deposit_required_pct <= (100)::numeric)))
);


-- booking_engine_sites
CREATE TABLE booking_engine_sites (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    site_slug character varying(100) NOT NULL,
    custom_domain text,
    is_published boolean DEFAULT false NOT NULL,
    default_locale character varying(10) DEFAULT 'en' NOT NULL,
    supported_locales text[] DEFAULT ARRAY['en'] NOT NULL,
    seo_title text,
    seo_description text,
    terms_version character varying(50),
    booking_terms_url text,
    privacy_policy_url text,
    published_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_booking_engine_sites_slug CHECK (((site_slug)::text ~ '^[a-z0-9][a-z0-9-]{1,98}[a-z0-9]$'::text)),
    CONSTRAINT chk_booking_engine_sites_supported_locales CHECK ((array_length(supported_locales, 1) > 0))
);


-- promo_codes
CREATE TABLE promo_codes (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    property_id uuid,
    code text NOT NULL,
    description text,
    discount_type text NOT NULL,
    discount_value numeric(12,2) NOT NULL,
    applies_to text DEFAULT 'room' NOT NULL,
    min_nights integer DEFAULT 1 NOT NULL,
    min_amount numeric(12,2),
    max_uses integer,
    uses_per_guest integer DEFAULT 1 NOT NULL,
    total_uses integer DEFAULT 0 NOT NULL,
    valid_from date DEFAULT CURRENT_DATE NOT NULL,
    valid_to date,
    channel_id uuid,
    rate_plan_id uuid,
    is_active boolean DEFAULT true NOT NULL,
    deleted_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT promo_codes_applies_to_check CHECK ((applies_to = ANY (ARRAY['room', 'total', 'fnb', 'event']))),
    CONSTRAINT promo_codes_check CHECK (((valid_to IS NULL) OR (valid_to >= valid_from))),
    CONSTRAINT promo_codes_check1 CHECK (((max_uses IS NULL) OR (total_uses <= max_uses))),
    CONSTRAINT promo_codes_discount_type_check CHECK ((discount_type = ANY (ARRAY['percent', 'fixed_amount', 'free_night', 'upgrade']))),
    CONSTRAINT promo_codes_discount_value_check CHECK ((discount_value > (0)::numeric))
);


-- booking_sessions
CREATE TABLE booking_sessions (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    session_token text DEFAULT (gen_random_uuid())::text NOT NULL,
    check_in_date date NOT NULL,
    check_out_date date NOT NULL,
    adults integer DEFAULT 1 NOT NULL,
    children integer DEFAULT 0 NOT NULL,
    guest_name text,
    guest_email text,
    guest_phone text,
    guest_country character(2),
    promo_code_id uuid,
    promo_discount numeric(12,2) DEFAULT 0 NOT NULL,
    channel_id uuid,
    utm_source text,
    utm_medium text,
    utm_campaign text,
    total_before_discount numeric(12,2) DEFAULT 0 NOT NULL,
    total_after_discount numeric(12,2) DEFAULT 0 NOT NULL,
    currency character(3) DEFAULT 'TZS'::bpchar NOT NULL,
    status text DEFAULT 'active' NOT NULL,
    payment_intent_id text,
    converted_reservation_id uuid,
    ip_address inet,
    user_agent text,
    expires_at timestamp with time zone DEFAULT (now() + '00:20:00'::interval) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT booking_sessions_adults_check CHECK ((adults > 0)),
    CONSTRAINT booking_sessions_check CHECK ((check_out_date > check_in_date)),
    CONSTRAINT booking_sessions_children_check CHECK ((children >= 0)),
    CONSTRAINT booking_sessions_status_check CHECK ((status = ANY (ARRAY['active', 'payment_pending', 'confirmed', 'abandoned', 'expired'])))
);


-- booking_payment_attempts
CREATE TABLE booking_payment_attempts (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    session_id uuid NOT NULL,
    provider character varying(40) NOT NULL,
    provider_payment_id text,
    idempotency_key text NOT NULL,
    amount numeric(12,2) NOT NULL,
    currency character(3) DEFAULT 'TZS'::bpchar NOT NULL,
    status character varying(30) DEFAULT 'pending' NOT NULL,
    failure_reason text,
    webhook_event_id text,
    provider_response jsonb DEFAULT '{}'::jsonb NOT NULL,
    authorised_at timestamp with time zone,
    captured_at timestamp with time zone,
    failed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_booking_payment_attempts_amount CHECK ((amount >= (0)::numeric)),
    CONSTRAINT chk_booking_payment_attempts_provider CHECK (((provider)::text = ANY ((ARRAY['stripe', 'flutterwave', 'dpo', 'pesapal', 'mpesa', 'airtel_money', 'manual', 'other'])::text[]))),
    CONSTRAINT chk_booking_payment_attempts_status CHECK (((status)::text = ANY ((ARRAY['pending', 'requires_action', 'authorised', 'captured', 'failed', 'cancelled', 'refunded'])::text[])))
);


-- booking_session_rooms
CREATE TABLE booking_session_rooms (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    session_id uuid NOT NULL,
    room_type_id uuid NOT NULL,
    rate_plan_id uuid,
    quantity integer DEFAULT 1 NOT NULL,
    nightly_rate numeric(12,2) NOT NULL,
    nights integer NOT NULL,
    subtotal numeric(12,2) GENERATED ALWAYS AS ((((quantity)::numeric * nightly_rate) * (nights)::numeric)) STORED,
    availability_lock_id uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT booking_session_rooms_nightly_rate_check CHECK ((nightly_rate >= (0)::numeric)),
    CONSTRAINT booking_session_rooms_nights_check CHECK ((nights > 0)),
    CONSTRAINT booking_session_rooms_quantity_check CHECK ((quantity > 0))
);


-- booking_session_room_nights
CREATE TABLE booking_session_room_nights (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    session_id uuid NOT NULL,
    session_room_id uuid NOT NULL,
    room_type_id uuid NOT NULL,
    rate_plan_id uuid,
    stay_date date NOT NULL,
    base_rate numeric(12,2) NOT NULL,
    tax_amount numeric(12,2) DEFAULT 0 NOT NULL,
    fee_amount numeric(12,2) DEFAULT 0 NOT NULL,
    promo_discount numeric(12,2) DEFAULT 0 NOT NULL,
    final_amount numeric(12,2) NOT NULL,
    currency character(3) DEFAULT 'TZS'::bpchar NOT NULL,
    rate_snapshot jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_booking_session_room_nights_amounts CHECK (((base_rate >= (0)::numeric) AND (tax_amount >= (0)::numeric) AND (fee_amount >= (0)::numeric) AND (promo_discount >= (0)::numeric) AND (final_amount >= (0)::numeric))),
    CONSTRAINT chk_booking_session_room_nights_final_amount CHECK ((final_amount = round((((base_rate + tax_amount) + fee_amount) - promo_discount), 2)))
);


-- ================================================================================
-- 16. APPROVAL WORKFLOWS
-- ================================================================================

-- approval_workflows
CREATE TABLE approval_workflows (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    name text NOT NULL,
    code text NOT NULL,
    entity_type text NOT NULL,
    description text,
    is_active boolean DEFAULT true NOT NULL,
    auto_approve_below_amount numeric(12,2),
    escalation_hours integer DEFAULT 24 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


-- approval_workflow_steps
CREATE TABLE approval_workflow_steps (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    workflow_id uuid NOT NULL,
    step_order integer NOT NULL,
    step_name text NOT NULL,
    approver_role text,
    approver_user_id uuid,
    is_optional boolean DEFAULT false NOT NULL,
    timeout_hours integer DEFAULT 48 NOT NULL,
    on_timeout text DEFAULT 'escalate' NOT NULL,
    CONSTRAINT approval_workflow_steps_on_timeout_check CHECK ((on_timeout = ANY (ARRAY['escalate', 'auto_approve', 'reject']))),
    CONSTRAINT approval_workflow_steps_step_order_check CHECK ((step_order > 0))
);


-- approval_requests
CREATE TABLE approval_requests (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    workflow_id uuid NOT NULL,
    entity_type text NOT NULL,
    entity_id uuid NOT NULL,
    reference_number text NOT NULL,
    requested_by uuid NOT NULL,
    current_step integer DEFAULT 1 NOT NULL,
    status text DEFAULT 'pending' NOT NULL,
    amount numeric(12,2),
    subject text NOT NULL,
    description text,
    approved_at timestamp with time zone,
    rejected_at timestamp with time zone,
    cancelled_by uuid,
    metadata jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT approval_requests_status_check CHECK ((status = ANY (ARRAY['pending', 'in_review', 'approved', 'rejected', 'cancelled', 'auto_approved'])))
);


-- approval_request_steps
CREATE TABLE approval_request_steps (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    request_id uuid NOT NULL,
    step_order integer NOT NULL,
    step_name text NOT NULL,
    assigned_to uuid,
    action text,
    actioned_by uuid,
    actioned_at timestamp with time zone,
    due_at timestamp with time zone,
    comments text,
    delegated_to uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT approval_request_steps_action_check CHECK ((action = ANY (ARRAY['approved', 'rejected', 'delegated', 'skipped', 'timed_out'])))
);


-- ================================================================================
-- 17. NIGHT AUDIT
-- ================================================================================

-- night_audit_runs
CREATE TABLE night_audit_runs (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    audit_date date NOT NULL,
    status character varying(20) DEFAULT 'pending' NOT NULL,
    started_at timestamp with time zone,
    completed_at timestamp with time zone,
    summary jsonb,
    run_by uuid,
    error_message text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_night_audit_runs_status CHECK (((status)::text = ANY ((ARRAY['pending', 'running', 'completed', 'failed'])::text[])))
);


-- ================================================================================
-- 18. NOTIFICATIONS & i18n
-- ================================================================================

-- notifications
CREATE TABLE notifications (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    property_id uuid,
    user_id uuid NOT NULL,
    type text NOT NULL,
    title text NOT NULL,
    body text NOT NULL,
    entity_type text,
    entity_id uuid,
    priority text DEFAULT 'normal' NOT NULL,
    channel text DEFAULT 'in_app' NOT NULL,
    read_at timestamp with time zone,
    sent_at timestamp with time zone,
    failed_at timestamp with time zone,
    failure_reason text,
    expires_at timestamp with time zone,
    metadata jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT notifications_channel_check CHECK ((channel = ANY (ARRAY['in_app', 'sms', 'email', 'push', 'whatsapp']))),
    CONSTRAINT notifications_priority_check CHECK ((priority = ANY (ARRAY['low', 'normal', 'high', 'urgent']))),
    CONSTRAINT notifications_type_check CHECK ((type = ANY (ARRAY['low_stock', 'maintenance_escalation', 'vip_checkin', 'night_audit_failed', 'payment_declined', 'room_ready', 'checkout_due', 'group_arrival', 'system_alert', 'task_assigned'])))
);


-- translations
CREATE TABLE translations (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid,
    namespace text NOT NULL,
    key text NOT NULL,
    locale character(5) NOT NULL,
    value text NOT NULL,
    is_approved boolean DEFAULT false NOT NULL,
    translated_by uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT translations_namespace_check CHECK ((namespace = ANY (ARRAY['ui', 'email', 'receipt', 'sms', 'error', 'report', 'notification'])))
);


-- ================================================================================
-- 19. GUEST FEEDBACK
-- ================================================================================

-- guest_feedback
CREATE TABLE guest_feedback (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    reservation_id uuid NOT NULL,
    guest_id uuid NOT NULL,
    overall_score smallint NOT NULL,
    cleanliness smallint,
    service smallint,
    food_beverage smallint,
    facilities smallint,
    value_for_money smallint,
    comments text,
    response text,
    responded_by uuid,
    responded_at timestamp with time zone,
    source text DEFAULT 'internal' NOT NULL,
    is_published boolean DEFAULT false NOT NULL,
    submitted_at timestamp with time zone DEFAULT now() NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT guest_feedback_cleanliness_check CHECK (((cleanliness >= 1) AND (cleanliness <= 5))),
    CONSTRAINT guest_feedback_facilities_check CHECK (((facilities >= 1) AND (facilities <= 5))),
    CONSTRAINT guest_feedback_food_beverage_check CHECK (((food_beverage >= 1) AND (food_beverage <= 5))),
    CONSTRAINT guest_feedback_overall_score_check CHECK (((overall_score >= 1) AND (overall_score <= 10))),
    CONSTRAINT guest_feedback_service_check CHECK (((service >= 1) AND (service <= 5))),
    CONSTRAINT guest_feedback_source_check CHECK ((source = ANY (ARRAY['internal', 'tripadvisor', 'google', 'booking_com', 'expedia', 'email']))),
    CONSTRAINT guest_feedback_value_for_money_check CHECK (((value_for_money >= 1) AND (value_for_money <= 5)))
);


-- ================================================================================
-- 20. SYSTEM — Audit, Events & Edge Sync
-- ================================================================================

-- idempotency_keys
CREATE TABLE idempotency_keys (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid,
    property_id uuid,
    idempotency_key text NOT NULL,
    request_method character varying(10),
    request_path text,
    request_hash text,
    actor_type character varying(20) DEFAULT 'tenant_user' NOT NULL,
    actor_id uuid,
    operation_type character varying(50) NOT NULL,
    resource_type text,
    resource_id uuid,
    status character varying(20) DEFAULT 'processing' NOT NULL,
    response_code integer,
    response_body jsonb,
    locked_at timestamp with time zone,
    expires_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_idempotency_keys_actor_type CHECK (((actor_type)::text = ANY ((ARRAY['platform_user', 'tenant_user', 'guest', 'system', 'integration'])::text[]))),
    CONSTRAINT chk_idempotency_keys_response_code CHECK (((response_code IS NULL) OR ((response_code >= 100) AND (response_code <= 599)))),
    CONSTRAINT chk_idempotency_keys_status CHECK (((status)::text = ANY ((ARRAY['processing', 'succeeded', 'failed', 'expired'])::text[])))
);


-- outbox_events
CREATE TABLE outbox_events (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid,
    property_id uuid,
    aggregate_type text NOT NULL,
    aggregate_id uuid,
    event_type text NOT NULL,
    destination character varying(50) NOT NULL,
    payload jsonb NOT NULL,
    headers jsonb DEFAULT '{}'::jsonb NOT NULL,
    correlation_id uuid,
    idempotency_key_id uuid,
    status character varying(20) DEFAULT 'pending' NOT NULL,
    priority smallint DEFAULT 5 NOT NULL,
    attempt_count integer DEFAULT 0 NOT NULL,
    max_attempts integer DEFAULT 10 NOT NULL,
    next_attempt_at timestamp with time zone DEFAULT now() NOT NULL,
    locked_by text,
    locked_at timestamp with time zone,
    delivered_at timestamp with time zone,
    failed_at timestamp with time zone,
    error_message text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_outbox_events_attempts CHECK (((attempt_count >= 0) AND (max_attempts > 0) AND (attempt_count <= max_attempts))),
    CONSTRAINT chk_outbox_events_destination CHECK (((destination)::text = ANY ((ARRAY['fiscal', 'payment', 'notification', 'analytics', 'audit', 'edge_sync', 'webhook', 'email', 'sms', 'whatsapp', 'platform'])::text[]))),
    CONSTRAINT chk_outbox_events_priority CHECK (((priority >= 1) AND (priority <= 10))),
    CONSTRAINT chk_outbox_events_status CHECK (((status)::text = ANY ((ARRAY['pending', 'locked', 'delivered', 'failed', 'dead_letter', 'cancelled'])::text[])))
);


-- audit_logs
CREATE TABLE audit_logs (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    user_id uuid,
    action text NOT NULL,
    entity_type text NOT NULL,
    entity_id uuid,
    old_values jsonb,
    new_values jsonb,
    ip_address text,
    user_agent text,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


-- events
CREATE TABLE events (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    event_type text NOT NULL,
    payload jsonb,
    created_at timestamp with time zone DEFAULT now()
);


-- edge_sync_events
CREATE TABLE edge_sync_events (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    property_id uuid,
    event_type character varying(100) NOT NULL,
    payload jsonb NOT NULL,
    originated_at timestamp with time zone,
    received_at timestamp with time zone DEFAULT now() NOT NULL,
    status character varying(20) DEFAULT 'pending' NOT NULL,
    applied_at timestamp with time zone,
    conflict_reason text,
    resolved_by uuid,
    resolved_at timestamp with time zone,
    retry_count smallint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_edge_sync_events_status CHECK (((status)::text = ANY ((ARRAY['pending', 'applied', 'conflict', 'failed', 'ignored'])::text[])))
);


-- ================================================================================
-- 21. PRIMARY KEY & UNIQUE CONSTRAINTS
-- ================================================================================

ALTER TABLE ONLY accounting_accounts
    ADD CONSTRAINT accounting_accounts_pkey PRIMARY KEY (id);

ALTER TABLE ONLY accounting_accounts
    ADD CONSTRAINT accounting_accounts_tenant_property_code_key UNIQUE (tenant_id, property_id, code);

ALTER TABLE ONLY allotments
    ADD CONSTRAINT allotments_pkey PRIMARY KEY (id);

ALTER TABLE ONLY approval_request_steps
    ADD CONSTRAINT approval_request_steps_pkey PRIMARY KEY (id);

ALTER TABLE ONLY approval_requests
    ADD CONSTRAINT approval_requests_pkey PRIMARY KEY (id);

ALTER TABLE ONLY approval_workflow_steps
    ADD CONSTRAINT approval_workflow_steps_pkey PRIMARY KEY (id);

ALTER TABLE ONLY approval_workflow_steps
    ADD CONSTRAINT approval_workflow_steps_workflow_id_step_order_key UNIQUE (workflow_id, step_order);

ALTER TABLE ONLY approval_workflows
    ADD CONSTRAINT approval_workflows_pkey PRIMARY KEY (id);

ALTER TABLE ONLY approval_workflows
    ADD CONSTRAINT approval_workflows_tenant_id_code_key UNIQUE (tenant_id, code);

ALTER TABLE ONLY attendance
    ADD CONSTRAINT attendance_pkey PRIMARY KEY (id);

ALTER TABLE ONLY audit_logs
    ADD CONSTRAINT audit_logs_pkey PRIMARY KEY (id);

ALTER TABLE ONLY feature_flags
    ADD CONSTRAINT feature_flags_pkey PRIMARY KEY (id);

ALTER TABLE ONLY idempotency_keys
    ADD CONSTRAINT idempotency_keys_pkey PRIMARY KEY (id);

ALTER TABLE ONLY availability_calendar
    ADD CONSTRAINT availability_calendar_pkey PRIMARY KEY (id);

ALTER TABLE ONLY availability_calendar
    ADD CONSTRAINT availability_calendar_tenant_id_property_id_room_type_id_st_key UNIQUE (tenant_id, property_id, room_type_id, stay_date);

ALTER TABLE ONLY availability_locks
    ADD CONSTRAINT availability_locks_pkey PRIMARY KEY (id);

ALTER TABLE ONLY booking_engine_configs
    ADD CONSTRAINT booking_engine_configs_pkey PRIMARY KEY (id);

ALTER TABLE ONLY booking_engine_configs
    ADD CONSTRAINT booking_engine_configs_tenant_id_property_id_key UNIQUE (tenant_id, property_id);

ALTER TABLE ONLY booking_engine_sites
    ADD CONSTRAINT booking_engine_sites_custom_domain_key UNIQUE (custom_domain);

ALTER TABLE ONLY booking_engine_sites
    ADD CONSTRAINT booking_engine_sites_pkey PRIMARY KEY (id);

ALTER TABLE ONLY booking_engine_sites
    ADD CONSTRAINT booking_engine_sites_tenant_id_site_slug_key UNIQUE (tenant_id, site_slug);

ALTER TABLE ONLY booking_policies
    ADD CONSTRAINT booking_policies_pkey PRIMARY KEY (id);

ALTER TABLE ONLY booking_policies
    ADD CONSTRAINT booking_policies_tenant_property_type_name_key UNIQUE (tenant_id, property_id, policy_type, name);

ALTER TABLE ONLY booking_payment_attempts
    ADD CONSTRAINT booking_payment_attempts_pkey PRIMARY KEY (id);

ALTER TABLE ONLY booking_payment_attempts
    ADD CONSTRAINT booking_payment_attempts_tenant_id_idempotency_key_key UNIQUE (tenant_id, idempotency_key);

ALTER TABLE ONLY booking_session_rooms
    ADD CONSTRAINT booking_session_rooms_pkey PRIMARY KEY (id);

ALTER TABLE ONLY booking_session_room_nights
    ADD CONSTRAINT booking_session_room_nights_pkey PRIMARY KEY (id);

ALTER TABLE ONLY booking_session_room_nights
    ADD CONSTRAINT booking_session_room_nights_session_room_id_stay_date_key UNIQUE (session_room_id, stay_date);

ALTER TABLE ONLY booking_sessions
    ADD CONSTRAINT booking_sessions_pkey PRIMARY KEY (id);

ALTER TABLE ONLY booking_sessions
    ADD CONSTRAINT booking_sessions_session_token_key UNIQUE (session_token);

ALTER TABLE ONLY buildings
    ADD CONSTRAINT buildings_pkey PRIMARY KEY (id);

ALTER TABLE ONLY property_billing_settings
    ADD CONSTRAINT property_billing_settings_pkey PRIMARY KEY (id);

ALTER TABLE ONLY property_billing_settings
    ADD CONSTRAINT property_billing_settings_tenant_id_property_id_key UNIQUE (tenant_id, property_id);

ALTER TABLE ONLY cash_float_movements
    ADD CONSTRAINT cash_float_movements_pkey PRIMARY KEY (id);

ALTER TABLE ONLY channel_connections
    ADD CONSTRAINT channel_connections_pkey PRIMARY KEY (id);

ALTER TABLE ONLY channels
    ADD CONSTRAINT channels_pkey PRIMARY KEY (id);

ALTER TABLE ONLY channels
    ADD CONSTRAINT channels_tenant_id_code_key UNIQUE (tenant_id, code);

ALTER TABLE ONLY companies
    ADD CONSTRAINT companies_pkey PRIMARY KEY (id);

ALTER TABLE ONLY corporate_rate_agreements
    ADD CONSTRAINT corporate_rate_agreements_pkey PRIMARY KEY (id);

ALTER TABLE ONLY corporate_rate_agreements
    ADD CONSTRAINT corporate_rate_agreements_tenant_property_code_key UNIQUE (tenant_id, property_id, agreement_code);

ALTER TABLE ONLY currency_rates
    ADD CONSTRAINT currency_rates_pkey PRIMARY KEY (id);

ALTER TABLE ONLY currency_rates
    ADD CONSTRAINT currency_rates_tenant_id_from_currency_to_currency_rate_dat_key UNIQUE (tenant_id, from_currency, to_currency, rate_date);

ALTER TABLE ONLY departments
    ADD CONSTRAINT departments_pkey PRIMARY KEY (id);

ALTER TABLE ONLY document_sequences
    ADD CONSTRAINT document_sequences_pkey PRIMARY KEY (id);

ALTER TABLE ONLY edge_sync_events
    ADD CONSTRAINT edge_sync_events_pkey PRIMARY KEY (id);

ALTER TABLE ONLY employees
    ADD CONSTRAINT employees_employee_number_key UNIQUE (tenant_id, employee_number);

ALTER TABLE ONLY employees
    ADD CONSTRAINT employees_pkey PRIMARY KEY (id);

ALTER TABLE ONLY employees
    ADD CONSTRAINT employees_user_id_key UNIQUE (user_id);

ALTER TABLE ONLY event_booking_items
    ADD CONSTRAINT event_booking_items_pkey PRIMARY KEY (id);

ALTER TABLE ONLY event_bookings
    ADD CONSTRAINT event_bookings_pkey PRIMARY KEY (id);

ALTER TABLE ONLY event_bookings
    ADD CONSTRAINT event_bookings_no_active_overlap EXCLUDE USING gist (event_space_id WITH =, event_date WITH =, int4range((EXTRACT(epoch FROM start_time))::integer, (EXTRACT(epoch FROM end_time))::integer, '[)'::text) WITH &&) WHERE ((status = ANY (ARRAY['tentative', 'confirmed', 'deposit_paid', 'in_progress'])));

ALTER TABLE ONLY event_packages
    ADD CONSTRAINT event_packages_pkey PRIMARY KEY (id);

ALTER TABLE ONLY event_packages
    ADD CONSTRAINT event_packages_tenant_id_code_key UNIQUE (tenant_id, code);

ALTER TABLE ONLY event_spaces
    ADD CONSTRAINT event_spaces_pkey PRIMARY KEY (id);

ALTER TABLE ONLY event_spaces
    ADD CONSTRAINT event_spaces_tenant_id_code_key UNIQUE (tenant_id, code);

ALTER TABLE ONLY events
    ADD CONSTRAINT events_pkey PRIMARY KEY (id);

ALTER TABLE ONLY fiscal_receipts
    ADD CONSTRAINT fiscal_receipts_pkey PRIMARY KEY (id);

ALTER TABLE ONLY floors
    ADD CONSTRAINT floors_pkey PRIMARY KEY (id);

ALTER TABLE ONLY folio_charges
    ADD CONSTRAINT folio_charges_pkey PRIMARY KEY (id);

ALTER TABLE ONLY folio_charge_taxes
    ADD CONSTRAINT folio_charge_taxes_pkey PRIMARY KEY (id);

ALTER TABLE ONLY folio_charge_taxes
    ADD CONSTRAINT folio_charge_taxes_charge_tax_key UNIQUE (folio_charge_id, tax_type, tax_rate_id);

ALTER TABLE ONLY folio_payments
    ADD CONSTRAINT folio_payments_idempotency_key_key UNIQUE (tenant_id, idempotency_key);

ALTER TABLE ONLY folio_payments
    ADD CONSTRAINT folio_payments_pkey PRIMARY KEY (id);

ALTER TABLE ONLY folios
    ADD CONSTRAINT folios_pkey PRIMARY KEY (id);

ALTER TABLE ONLY groups
    ADD CONSTRAINT groups_pkey PRIMARY KEY (id);

ALTER TABLE ONLY groups
    ADD CONSTRAINT groups_tenant_id_group_code_key UNIQUE (tenant_id, group_code);

ALTER TABLE ONLY guest_contacts
    ADD CONSTRAINT guest_contacts_pkey PRIMARY KEY (id);

ALTER TABLE ONLY guest_documents
    ADD CONSTRAINT guest_documents_pkey PRIMARY KEY (id);

ALTER TABLE ONLY guest_documents
    ADD CONSTRAINT guest_documents_guest_document_key UNIQUE (tenant_id, guest_id, document_type, document_number);

ALTER TABLE ONLY guest_feedback
    ADD CONSTRAINT guest_feedback_pkey PRIMARY KEY (id);

ALTER TABLE ONLY guest_preferences
    ADD CONSTRAINT guest_preferences_guest_id_key UNIQUE (guest_id);

ALTER TABLE ONLY guest_preferences
    ADD CONSTRAINT guest_preferences_pkey PRIMARY KEY (id);

ALTER TABLE ONLY guests
    ADD CONSTRAINT guests_pkey PRIMARY KEY (id);

ALTER TABLE ONLY housekeeping_assignments
    ADD CONSTRAINT housekeeping_assignments_pkey PRIMARY KEY (id);

ALTER TABLE ONLY housekeeping_tasks
    ADD CONSTRAINT housekeeping_tasks_pkey PRIMARY KEY (id);

ALTER TABLE ONLY inventory_items
    ADD CONSTRAINT inventory_items_pkey PRIMARY KEY (id);

ALTER TABLE ONLY inventory_locations
    ADD CONSTRAINT inventory_locations_pkey PRIMARY KEY (id);

ALTER TABLE ONLY labor_forecasts
    ADD CONSTRAINT labor_forecasts_pkey PRIMARY KEY (id);

ALTER TABLE ONLY labor_forecasts
    ADD CONSTRAINT labor_forecasts_tenant_property_department_date_key UNIQUE (tenant_id, property_id, department_id, forecast_date);

ALTER TABLE ONLY leave_requests
    ADD CONSTRAINT leave_requests_pkey PRIMARY KEY (id);

ALTER TABLE ONLY invoice_items
    ADD CONSTRAINT invoice_items_pkey PRIMARY KEY (id);

ALTER TABLE ONLY invoice_item_taxes
    ADD CONSTRAINT invoice_item_taxes_pkey PRIMARY KEY (id);

ALTER TABLE ONLY invoice_item_taxes
    ADD CONSTRAINT invoice_item_taxes_item_tax_key UNIQUE (invoice_item_id, tax_type, tax_rate_id);

ALTER TABLE ONLY invoices
    ADD CONSTRAINT invoices_invoice_number_formatted_key UNIQUE (tenant_id, invoice_number_formatted);

ALTER TABLE ONLY invoices
    ADD CONSTRAINT invoices_pkey PRIMARY KEY (id);

ALTER TABLE ONLY kitchen_tickets
    ADD CONSTRAINT kitchen_tickets_pkey PRIMARY KEY (id);

ALTER TABLE ONLY journal_entries
    ADD CONSTRAINT journal_entries_pkey PRIMARY KEY (id);

ALTER TABLE ONLY journal_entries
    ADD CONSTRAINT journal_entries_tenant_entry_number_key UNIQUE (tenant_id, entry_number);

ALTER TABLE ONLY journal_entry_lines
    ADD CONSTRAINT journal_entry_lines_pkey PRIMARY KEY (id);

ALTER TABLE ONLY journal_entry_lines
    ADD CONSTRAINT journal_entry_lines_entry_line_key UNIQUE (journal_entry_id, line_no);

ALTER TABLE ONLY lost_and_found
    ADD CONSTRAINT lost_and_found_pkey PRIMARY KEY (id);

ALTER TABLE ONLY loyalty_accounts
    ADD CONSTRAINT loyalty_accounts_guest_id_key UNIQUE (guest_id);

ALTER TABLE ONLY loyalty_accounts
    ADD CONSTRAINT loyalty_accounts_pkey PRIMARY KEY (id);

ALTER TABLE ONLY loyalty_transactions
    ADD CONSTRAINT loyalty_transactions_pkey PRIMARY KEY (id);

ALTER TABLE ONLY maintenance_requests
    ADD CONSTRAINT maintenance_requests_pkey PRIMARY KEY (id);

ALTER TABLE ONLY maintenance_windows
    ADD CONSTRAINT maintenance_windows_pkey PRIMARY KEY (id);

ALTER TABLE ONLY module_access_matrix
    ADD CONSTRAINT module_access_matrix_pkey PRIMARY KEY (id);

ALTER TABLE ONLY module_access_matrix
    ADD CONSTRAINT module_access_matrix_route_contract_key UNIQUE NULLS NOT DISTINCT (module_id, screen_key, http_method, api_pattern, permission_code);

ALTER TABLE ONLY module_catalog
    ADD CONSTRAINT module_catalog_pkey PRIMARY KEY (module_id);

ALTER TABLE ONLY module_dependencies
    ADD CONSTRAINT module_dependencies_pkey PRIMARY KEY (module_id, depends_on_module_id);

ALTER TABLE ONLY business_profiles
    ADD CONSTRAINT business_profiles_pkey PRIMARY KEY (id);

ALTER TABLE ONLY business_profiles
    ADD CONSTRAINT business_profiles_code_key UNIQUE (code);

ALTER TABLE ONLY business_profile_modules
    ADD CONSTRAINT business_profile_modules_pkey PRIMARY KEY (business_profile_id, module_id);

ALTER TABLE ONLY revenue_centers
    ADD CONSTRAINT revenue_centers_pkey PRIMARY KEY (id);

ALTER TABLE ONLY revenue_centers
    ADD CONSTRAINT revenue_centers_tenant_id_id_key UNIQUE (tenant_id, id);

ALTER TABLE ONLY revenue_centers
    ADD CONSTRAINT revenue_centers_tenant_property_code_key UNIQUE (tenant_id, property_id, code);

ALTER TABLE ONLY revenue_centers
    ADD CONSTRAINT revenue_centers_tenant_property_id_key UNIQUE (tenant_id, property_id, id);

ALTER TABLE ONLY menu_categories
    ADD CONSTRAINT menu_categories_pkey PRIMARY KEY (id);

ALTER TABLE ONLY menu_item_recipes
    ADD CONSTRAINT menu_item_recipes_pkey PRIMARY KEY (id);

ALTER TABLE ONLY menu_items
    ADD CONSTRAINT menu_items_pkey PRIMARY KEY (id);

ALTER TABLE ONLY night_audit_runs
    ADD CONSTRAINT night_audit_runs_pkey PRIMARY KEY (id);

ALTER TABLE ONLY notifications
    ADD CONSTRAINT notifications_pkey PRIMARY KEY (id);

ALTER TABLE ONLY ota_room_type_mappings
    ADD CONSTRAINT ota_room_type_mappings_channel_connection_id_ota_room_code_key UNIQUE (channel_connection_id, ota_room_code);

ALTER TABLE ONLY ota_room_type_mappings
    ADD CONSTRAINT ota_room_type_mappings_pkey PRIMARY KEY (id);

ALTER TABLE ONLY outlets
    ADD CONSTRAINT outlets_pkey PRIMARY KEY (id);

ALTER TABLE ONLY outlets
    ADD CONSTRAINT outlets_tenant_property_id_key UNIQUE (tenant_id, property_id, id);

ALTER TABLE ONLY outbox_events
    ADD CONSTRAINT outbox_events_pkey PRIMARY KEY (id);

ALTER TABLE ONLY property_frontdesk_settings
    ADD CONSTRAINT property_frontdesk_settings_pkey PRIMARY KEY (id);

ALTER TABLE ONLY property_frontdesk_settings
    ADD CONSTRAINT property_frontdesk_settings_tenant_id_property_id_key UNIQUE (tenant_id, property_id);

ALTER TABLE ONLY property_housekeeping_settings
    ADD CONSTRAINT property_housekeeping_settings_pkey PRIMARY KEY (id);

ALTER TABLE ONLY property_housekeeping_settings
    ADD CONSTRAINT property_housekeeping_settings_tenant_id_property_id_key UNIQUE (tenant_id, property_id);

ALTER TABLE ONLY property_module_configs
    ADD CONSTRAINT property_module_configs_pkey PRIMARY KEY (id);

ALTER TABLE ONLY property_module_configs
    ADD CONSTRAINT property_module_configs_tenant_id_property_id_module_id_key UNIQUE (tenant_id, property_id, module_id);

ALTER TABLE ONLY property_modules
    ADD CONSTRAINT property_modules_pkey PRIMARY KEY (id);

ALTER TABLE ONLY property_modules
    ADD CONSTRAINT property_modules_tenant_id_property_id_module_id_key UNIQUE (tenant_id, property_id, module_id);

ALTER TABLE ONLY property_pos_settings
    ADD CONSTRAINT property_pos_settings_pkey PRIMARY KEY (id);

ALTER TABLE ONLY property_pos_settings
    ADD CONSTRAINT property_pos_settings_tenant_id_property_id_key UNIQUE (tenant_id, property_id);

ALTER TABLE ONLY property_reservation_settings
    ADD CONSTRAINT property_reservation_settings_pkey PRIMARY KEY (id);

ALTER TABLE ONLY property_reservation_settings
    ADD CONSTRAINT property_reservation_settings_tenant_id_property_id_key UNIQUE (tenant_id, property_id);

ALTER TABLE ONLY payroll_records
    ADD CONSTRAINT payroll_records_pkey PRIMARY KEY (id);

ALTER TABLE ONLY payroll_runs
    ADD CONSTRAINT payroll_runs_pkey PRIMARY KEY (id);

ALTER TABLE ONLY permissions
    ADD CONSTRAINT permissions_pkey PRIMARY KEY (id);

ALTER TABLE ONLY permissions
    ADD CONSTRAINT permissions_tenant_id_code_key UNIQUE (tenant_id, code);

ALTER TABLE ONLY permission_catalog
    ADD CONSTRAINT permission_catalog_pkey PRIMARY KEY (code);

ALTER TABLE ONLY platform_alerts
    ADD CONSTRAINT platform_alerts_pkey PRIMARY KEY (id);

ALTER TABLE ONLY platform_audit_logs
    ADD CONSTRAINT platform_audit_logs_pkey PRIMARY KEY (id);

ALTER TABLE ONLY platform_break_glass_access
    ADD CONSTRAINT platform_break_glass_access_pkey PRIMARY KEY (id);

ALTER TABLE ONLY platform_break_glass_access
    ADD CONSTRAINT platform_break_glass_access_no_overlap EXCLUDE USING gist (platform_user_id WITH =, tenant_id WITH =, action_code WITH =, tstzrange(starts_at, expires_at, '[)'::text) WITH &&) WHERE (((status)::text = 'active'::text)) DEFERRABLE;

ALTER TABLE ONLY platform_incidents
    ADD CONSTRAINT platform_incidents_incident_number_key UNIQUE (incident_number);

ALTER TABLE ONLY platform_incidents
    ADD CONSTRAINT platform_incidents_pkey PRIMARY KEY (id);

ALTER TABLE ONLY platform_jobs
    ADD CONSTRAINT platform_jobs_job_key_key UNIQUE (job_key);

ALTER TABLE ONLY platform_jobs
    ADD CONSTRAINT platform_jobs_pkey PRIMARY KEY (id);

ALTER TABLE ONLY platform_job_runs
    ADD CONSTRAINT platform_job_runs_pkey PRIMARY KEY (id);

ALTER TABLE ONLY platform_permissions
    ADD CONSTRAINT platform_permissions_code_key UNIQUE (code);

ALTER TABLE ONLY platform_permissions
    ADD CONSTRAINT platform_permissions_pkey PRIMARY KEY (id);

ALTER TABLE ONLY platform_roles
    ADD CONSTRAINT platform_roles_code_key UNIQUE (code);

ALTER TABLE ONLY platform_roles
    ADD CONSTRAINT platform_roles_pkey PRIMARY KEY (id);

ALTER TABLE ONLY platform_role_permissions
    ADD CONSTRAINT platform_role_permissions_pkey PRIMARY KEY (platform_role_id, platform_permission_id);

ALTER TABLE ONLY platform_services
    ADD CONSTRAINT platform_services_pkey PRIMARY KEY (id);

ALTER TABLE ONLY platform_services
    ADD CONSTRAINT platform_services_service_key_key UNIQUE (service_key);

ALTER TABLE ONLY platform_sessions
    ADD CONSTRAINT platform_sessions_pkey PRIMARY KEY (id);

ALTER TABLE ONLY platform_sessions
    ADD CONSTRAINT platform_sessions_token_hash_key UNIQUE (token_hash);

ALTER TABLE ONLY platform_users
    ADD CONSTRAINT platform_users_email_key UNIQUE (email);

ALTER TABLE ONLY platform_users
    ADD CONSTRAINT platform_users_pkey PRIMARY KEY (id);

ALTER TABLE ONLY platform_user_roles
    ADD CONSTRAINT platform_user_roles_pkey PRIMARY KEY (platform_user_id, platform_role_id);

ALTER TABLE ONLY plan_entitlements
    ADD CONSTRAINT plan_entitlements_plan_code_key UNIQUE (plan_id, entitlement_code);

ALTER TABLE ONLY plan_entitlements
    ADD CONSTRAINT plan_entitlements_pkey PRIMARY KEY (id);

ALTER TABLE ONLY plans
    ADD CONSTRAINT plans_code_key UNIQUE (code);

ALTER TABLE ONLY plans
    ADD CONSTRAINT plans_pkey PRIMARY KEY (id);

ALTER TABLE ONLY pos_order_items
    ADD CONSTRAINT pos_order_items_pkey PRIMARY KEY (id);

ALTER TABLE ONLY pos_orders
    ADD CONSTRAINT pos_orders_order_number_key UNIQUE (tenant_id, outlet_id, order_number);

ALTER TABLE ONLY pos_orders
    ADD CONSTRAINT pos_orders_pkey PRIMARY KEY (id);

ALTER TABLE ONLY pos_sessions
    ADD CONSTRAINT pos_sessions_pkey PRIMARY KEY (id);

ALTER TABLE ONLY pos_printer_routes
    ADD CONSTRAINT pos_printer_routes_pkey PRIMARY KEY (id);

ALTER TABLE ONLY pos_printer_routes
    ADD CONSTRAINT pos_printer_routes_tenant_outlet_category_name_key UNIQUE (tenant_id, outlet_id, printer_category, printer_name);

ALTER TABLE ONLY pos_terminals
    ADD CONSTRAINT pos_terminals_activation_code_key UNIQUE (activation_code);

ALTER TABLE ONLY pos_terminals
    ADD CONSTRAINT pos_terminals_pkey PRIMARY KEY (id);

ALTER TABLE ONLY pricing_rules
    ADD CONSTRAINT pricing_rules_pkey PRIMARY KEY (id);

ALTER TABLE ONLY promo_codes
    ADD CONSTRAINT promo_codes_pkey PRIMARY KEY (id);

ALTER TABLE ONLY properties
    ADD CONSTRAINT properties_pkey PRIMARY KEY (id);

ALTER TABLE ONLY properties
    ADD CONSTRAINT properties_tenant_id_id_key UNIQUE (tenant_id, id);

ALTER TABLE ONLY purchase_order_items
    ADD CONSTRAINT purchase_order_items_pkey PRIMARY KEY (id);

ALTER TABLE ONLY purchase_orders
    ADD CONSTRAINT purchase_orders_pkey PRIMARY KEY (id);

ALTER TABLE ONLY rate_plan_prices
    ADD CONSTRAINT rate_plan_prices_pkey PRIMARY KEY (id);

ALTER TABLE ONLY rate_plans
    ADD CONSTRAINT rate_plans_pkey PRIMARY KEY (id);

ALTER TABLE ONLY rate_restrictions
    ADD CONSTRAINT rate_restrictions_pkey PRIMARY KEY (id);

ALTER TABLE ONLY refresh_tokens
    ADD CONSTRAINT refresh_tokens_pkey PRIMARY KEY (id);

ALTER TABLE ONLY refresh_tokens
    ADD CONSTRAINT refresh_tokens_token_hash_key UNIQUE (token_hash);

ALTER TABLE ONLY reservation_guests
    ADD CONSTRAINT reservation_guests_pkey PRIMARY KEY (id);

ALTER TABLE ONLY reservation_notes
    ADD CONSTRAINT reservation_notes_pkey PRIMARY KEY (id);

ALTER TABLE ONLY reservation_deposits
    ADD CONSTRAINT reservation_deposits_pkey PRIMARY KEY (id);

ALTER TABLE ONLY reservation_policy_snapshots
    ADD CONSTRAINT reservation_policy_snapshots_pkey PRIMARY KEY (id);

ALTER TABLE ONLY reservation_room_nights
    ADD CONSTRAINT reservation_room_nights_pkey PRIMARY KEY (id);

ALTER TABLE ONLY reservation_room_nights
    ADD CONSTRAINT reservation_room_nights_reservation_room_stay_date_key UNIQUE (reservation_room_id, stay_date);

ALTER TABLE ONLY reservation_rooms
    ADD CONSTRAINT reservation_rooms_pkey PRIMARY KEY (id);

ALTER TABLE ONLY reservation_rooms
    ADD CONSTRAINT reservation_rooms_no_active_overlap EXCLUDE USING gist (tenant_id WITH =, room_id WITH =, daterange(check_in_date, check_out_date, '[)'::text) WITH &&) WHERE (((room_id IS NOT NULL) AND ((status)::text = ANY ((ARRAY['reserved', 'checked_in'])::text[])))) DEFERRABLE;

ALTER TABLE ONLY reservations
    ADD CONSTRAINT reservations_confirmation_number_key UNIQUE (tenant_id, confirmation_number);

ALTER TABLE ONLY reservations
    ADD CONSTRAINT reservations_pkey PRIMARY KEY (id);

ALTER TABLE ONLY role_permissions
    ADD CONSTRAINT role_permissions_pkey PRIMARY KEY (role_id, permission_id);

ALTER TABLE ONLY roles
    ADD CONSTRAINT roles_pkey PRIMARY KEY (id);

ALTER TABLE ONLY room_moves
    ADD CONSTRAINT room_moves_pkey PRIMARY KEY (id);

ALTER TABLE ONLY room_status_log
    ADD CONSTRAINT room_status_log_pkey PRIMARY KEY (id);

ALTER TABLE ONLY room_types
    ADD CONSTRAINT room_types_pkey PRIMARY KEY (id);

ALTER TABLE ONLY rooms
    ADD CONSTRAINT rooms_pkey PRIMARY KEY (id);

ALTER TABLE ONLY schema_version_history
    ADD CONSTRAINT schema_version_history_pkey PRIMARY KEY (id);

ALTER TABLE ONLY schema_version_history
    ADD CONSTRAINT schema_version_history_version_key_key UNIQUE (version_key);

ALTER TABLE ONLY sales_activities
    ADD CONSTRAINT sales_activities_pkey PRIMARY KEY (id);

ALTER TABLE ONLY sales_leads
    ADD CONSTRAINT sales_leads_pkey PRIMARY KEY (id);

ALTER TABLE ONLY shift_handovers
    ADD CONSTRAINT shift_handovers_pkey PRIMARY KEY (id);

ALTER TABLE ONLY shift_templates
    ADD CONSTRAINT shift_templates_pkey PRIMARY KEY (id);

ALTER TABLE ONLY shift_templates
    ADD CONSTRAINT shift_templates_tenant_property_code_key UNIQUE (tenant_id, property_id, code);

ALTER TABLE ONLY service_health_checks
    ADD CONSTRAINT service_health_checks_pkey PRIMARY KEY (id);

ALTER TABLE ONLY staff_rosters
    ADD CONSTRAINT staff_rosters_pkey PRIMARY KEY (id);

ALTER TABLE ONLY staff_rosters
    ADD CONSTRAINT staff_rosters_employee_starts_at_key UNIQUE (employee_id, starts_at);

ALTER TABLE ONLY stays
    ADD CONSTRAINT stays_pkey PRIMARY KEY (id);

ALTER TABLE ONLY stock_levels
    ADD CONSTRAINT stock_levels_pkey PRIMARY KEY (id);

ALTER TABLE ONLY stock_movements
    ADD CONSTRAINT stock_movements_pkey PRIMARY KEY (id);

ALTER TABLE ONLY suppliers
    ADD CONSTRAINT suppliers_pkey PRIMARY KEY (id);

ALTER TABLE ONLY support_ticket_notes
    ADD CONSTRAINT support_ticket_notes_pkey PRIMARY KEY (id);

ALTER TABLE ONLY support_tickets
    ADD CONSTRAINT support_tickets_pkey PRIMARY KEY (id);

ALTER TABLE ONLY support_tickets
    ADD CONSTRAINT support_tickets_ticket_number_key UNIQUE (ticket_number);

ALTER TABLE ONLY tax_rates
    ADD CONSTRAINT tax_rates_pkey PRIMARY KEY (id);

ALTER TABLE ONLY tax_rates
    ADD CONSTRAINT tax_rates_tenant_id_code_key UNIQUE (tenant_id, code);

ALTER TABLE ONLY tenant_configs
    ADD CONSTRAINT tenant_configs_pkey PRIMARY KEY (id);

ALTER TABLE ONLY tenant_configs
    ADD CONSTRAINT tenant_configs_tenant_id_key UNIQUE (tenant_id);

ALTER TABLE ONLY tenant_lifecycle_events
    ADD CONSTRAINT tenant_lifecycle_events_pkey PRIMARY KEY (id);

ALTER TABLE ONLY tenant_module_configs
    ADD CONSTRAINT tenant_module_configs_pkey PRIMARY KEY (id);

ALTER TABLE ONLY tenant_module_configs
    ADD CONSTRAINT tenant_module_configs_tenant_id_module_id_key UNIQUE (tenant_id, module_id);

ALTER TABLE ONLY tenant_modules
    ADD CONSTRAINT tenant_modules_pkey PRIMARY KEY (id);

ALTER TABLE ONLY tenant_modules
    ADD CONSTRAINT tenant_modules_tenant_id_module_id_key UNIQUE (tenant_id, module_id);

ALTER TABLE ONLY tenant_roles
    ADD CONSTRAINT tenant_roles_pkey PRIMARY KEY (id);

ALTER TABLE ONLY tenant_roles
    ADD CONSTRAINT tenant_roles_tenant_code_key UNIQUE (tenant_id, code);

ALTER TABLE ONLY tenant_role_permissions
    ADD CONSTRAINT tenant_role_permissions_pkey PRIMARY KEY (tenant_role_id, permission_id);

ALTER TABLE ONLY tenant_usage_snapshots
    ADD CONSTRAINT tenant_usage_snapshots_pkey PRIMARY KEY (id);

ALTER TABLE ONLY tenant_usage_snapshots
    ADD CONSTRAINT tenant_usage_snapshots_tenant_date_key UNIQUE (tenant_id, snapshot_date);

ALTER TABLE ONLY tenants
    ADD CONSTRAINT tenants_pkey PRIMARY KEY (id);

ALTER TABLE ONLY tenants
    ADD CONSTRAINT tenants_schema_name_key UNIQUE (schema_name);

ALTER TABLE ONLY translations
    ADD CONSTRAINT translations_pkey PRIMARY KEY (id);

ALTER TABLE ONLY translations
    ADD CONSTRAINT translations_tenant_id_namespace_key_locale_key UNIQUE (tenant_id, namespace, key, locale);

ALTER TABLE ONLY user_property_roles
    ADD CONSTRAINT user_property_roles_pkey PRIMARY KEY (user_id, property_id, role_id);

ALTER TABLE ONLY user_tenant_roles
    ADD CONSTRAINT user_tenant_roles_pkey PRIMARY KEY (user_id, tenant_id, tenant_role_id);

ALTER TABLE ONLY users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);

ALTER TABLE ONLY workflow_catalog
    ADD CONSTRAINT workflow_catalog_pkey PRIMARY KEY (workflow_code);

ALTER TABLE ONLY workflow_steps
    ADD CONSTRAINT workflow_steps_pkey PRIMARY KEY (id);

ALTER TABLE ONLY workflow_steps
    ADD CONSTRAINT workflow_steps_workflow_step_key UNIQUE (workflow_code, step_order);

ALTER TABLE ONLY work_orders
    ADD CONSTRAINT work_orders_pkey PRIMARY KEY (id);


-- ================================================================================
-- 22. FOREIGN KEY CONSTRAINTS
-- ================================================================================

ALTER TABLE ONLY allotments
    ADD CONSTRAINT allotments_channel_id_fkey FOREIGN KEY (channel_id) REFERENCES channels(id);

ALTER TABLE ONLY allotments
    ADD CONSTRAINT allotments_group_id_fkey FOREIGN KEY (group_id) REFERENCES groups(id);

ALTER TABLE ONLY allotments
    ADD CONSTRAINT allotments_property_id_fkey FOREIGN KEY (property_id) REFERENCES properties(id);

ALTER TABLE ONLY allotments
    ADD CONSTRAINT allotments_room_type_id_fkey FOREIGN KEY (room_type_id) REFERENCES room_types(id);

ALTER TABLE ONLY allotments
    ADD CONSTRAINT allotments_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY approval_request_steps
    ADD CONSTRAINT approval_request_steps_actioned_by_fkey FOREIGN KEY (actioned_by) REFERENCES users(id);

ALTER TABLE ONLY approval_request_steps
    ADD CONSTRAINT approval_request_steps_assigned_to_fkey FOREIGN KEY (assigned_to) REFERENCES users(id);

ALTER TABLE ONLY approval_request_steps
    ADD CONSTRAINT approval_request_steps_delegated_to_fkey FOREIGN KEY (delegated_to) REFERENCES users(id);

ALTER TABLE ONLY approval_request_steps
    ADD CONSTRAINT approval_request_steps_request_id_fkey FOREIGN KEY (request_id) REFERENCES approval_requests(id);

ALTER TABLE ONLY approval_requests
    ADD CONSTRAINT approval_requests_cancelled_by_fkey FOREIGN KEY (cancelled_by) REFERENCES users(id);

ALTER TABLE ONLY approval_requests
    ADD CONSTRAINT approval_requests_requested_by_fkey FOREIGN KEY (requested_by) REFERENCES users(id);

ALTER TABLE ONLY approval_requests
    ADD CONSTRAINT approval_requests_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY approval_requests
    ADD CONSTRAINT approval_requests_workflow_id_fkey FOREIGN KEY (workflow_id) REFERENCES approval_workflows(id);

ALTER TABLE ONLY approval_workflow_steps
    ADD CONSTRAINT approval_workflow_steps_approver_user_id_fkey FOREIGN KEY (approver_user_id) REFERENCES users(id);

ALTER TABLE ONLY approval_workflow_steps
    ADD CONSTRAINT approval_workflow_steps_workflow_id_fkey FOREIGN KEY (workflow_id) REFERENCES approval_workflows(id);

ALTER TABLE ONLY approval_workflows
    ADD CONSTRAINT approval_workflows_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY attendance
    ADD CONSTRAINT fk_attendance_employee_id FOREIGN KEY (employee_id) REFERENCES employees(id) DEFERRABLE;

ALTER TABLE ONLY attendance
    ADD CONSTRAINT fk_attendance_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY audit_logs
    ADD CONSTRAINT fk_audit_logs_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY audit_logs
    ADD CONSTRAINT fk_audit_logs_user_id FOREIGN KEY (user_id) REFERENCES users(id) DEFERRABLE;

ALTER TABLE ONLY availability_calendar
    ADD CONSTRAINT availability_calendar_property_id_fkey FOREIGN KEY (property_id) REFERENCES properties(id);

ALTER TABLE ONLY availability_calendar
    ADD CONSTRAINT availability_calendar_room_type_id_fkey FOREIGN KEY (room_type_id) REFERENCES room_types(id);

ALTER TABLE ONLY availability_calendar
    ADD CONSTRAINT availability_calendar_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY availability_locks
    ADD CONSTRAINT availability_locks_room_type_id_fkey FOREIGN KEY (room_type_id) REFERENCES room_types(id);

ALTER TABLE ONLY availability_locks
    ADD CONSTRAINT availability_locks_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY property_reservation_settings
    ADD CONSTRAINT property_reservation_settings_property_id_fkey FOREIGN KEY (property_id) REFERENCES properties(id) ON DELETE CASCADE;

ALTER TABLE ONLY property_reservation_settings
    ADD CONSTRAINT property_reservation_settings_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY property_frontdesk_settings
    ADD CONSTRAINT property_frontdesk_settings_property_id_fkey FOREIGN KEY (property_id) REFERENCES properties(id) ON DELETE CASCADE;

ALTER TABLE ONLY property_frontdesk_settings
    ADD CONSTRAINT property_frontdesk_settings_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY property_housekeeping_settings
    ADD CONSTRAINT property_housekeeping_settings_property_id_fkey FOREIGN KEY (property_id) REFERENCES properties(id) ON DELETE CASCADE;

ALTER TABLE ONLY property_housekeeping_settings
    ADD CONSTRAINT property_housekeeping_settings_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY property_billing_settings
    ADD CONSTRAINT property_billing_settings_property_id_fkey FOREIGN KEY (property_id) REFERENCES properties(id) ON DELETE CASCADE;

ALTER TABLE ONLY property_billing_settings
    ADD CONSTRAINT property_billing_settings_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY booking_engine_configs
    ADD CONSTRAINT booking_engine_configs_property_id_fkey FOREIGN KEY (property_id) REFERENCES properties(id);

ALTER TABLE ONLY booking_engine_configs
    ADD CONSTRAINT booking_engine_configs_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY booking_engine_sites
    ADD CONSTRAINT booking_engine_sites_property_id_fkey FOREIGN KEY (property_id) REFERENCES properties(id);

ALTER TABLE ONLY booking_engine_sites
    ADD CONSTRAINT booking_engine_sites_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY booking_payment_attempts
    ADD CONSTRAINT booking_payment_attempts_property_id_fkey FOREIGN KEY (property_id) REFERENCES properties(id);

ALTER TABLE ONLY booking_payment_attempts
    ADD CONSTRAINT booking_payment_attempts_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY booking_session_room_nights
    ADD CONSTRAINT booking_session_room_nights_rate_plan_id_fkey FOREIGN KEY (rate_plan_id) REFERENCES rate_plans(id);

ALTER TABLE ONLY booking_session_room_nights
    ADD CONSTRAINT booking_session_room_nights_room_type_id_fkey FOREIGN KEY (room_type_id) REFERENCES room_types(id);

ALTER TABLE ONLY booking_session_room_nights
    ADD CONSTRAINT booking_session_room_nights_session_id_fkey FOREIGN KEY (session_id) REFERENCES booking_sessions(id);

ALTER TABLE ONLY booking_session_room_nights
    ADD CONSTRAINT booking_session_room_nights_session_room_id_fkey FOREIGN KEY (session_room_id) REFERENCES booking_session_rooms(id);

ALTER TABLE ONLY booking_session_room_nights
    ADD CONSTRAINT booking_session_room_nights_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY booking_session_rooms
    ADD CONSTRAINT booking_session_rooms_availability_lock_id_fkey FOREIGN KEY (availability_lock_id) REFERENCES availability_locks(id);

ALTER TABLE ONLY booking_session_rooms
    ADD CONSTRAINT booking_session_rooms_rate_plan_id_fkey FOREIGN KEY (rate_plan_id) REFERENCES rate_plans(id);

ALTER TABLE ONLY booking_session_rooms
    ADD CONSTRAINT booking_session_rooms_room_type_id_fkey FOREIGN KEY (room_type_id) REFERENCES room_types(id);

ALTER TABLE ONLY booking_session_rooms
    ADD CONSTRAINT booking_session_rooms_session_id_fkey FOREIGN KEY (session_id) REFERENCES booking_sessions(id);

ALTER TABLE ONLY booking_sessions
    ADD CONSTRAINT booking_sessions_channel_id_fkey FOREIGN KEY (channel_id) REFERENCES channels(id);

ALTER TABLE ONLY booking_sessions
    ADD CONSTRAINT booking_sessions_converted_reservation_id_fkey FOREIGN KEY (converted_reservation_id) REFERENCES reservations(id);

ALTER TABLE ONLY booking_sessions
    ADD CONSTRAINT booking_sessions_promo_code_id_fkey FOREIGN KEY (promo_code_id) REFERENCES promo_codes(id);

ALTER TABLE ONLY booking_sessions
    ADD CONSTRAINT booking_sessions_property_id_fkey FOREIGN KEY (property_id) REFERENCES properties(id);

ALTER TABLE ONLY booking_sessions
    ADD CONSTRAINT booking_sessions_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY buildings
    ADD CONSTRAINT fk_buildings_property_id FOREIGN KEY (property_id) REFERENCES properties(id) DEFERRABLE;

ALTER TABLE ONLY buildings
    ADD CONSTRAINT fk_buildings_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY cash_float_movements
    ADD CONSTRAINT cash_float_movements_authorised_by_fkey FOREIGN KEY (authorised_by) REFERENCES users(id);

ALTER TABLE ONLY cash_float_movements
    ADD CONSTRAINT cash_float_movements_created_by_fkey FOREIGN KEY (created_by) REFERENCES users(id);

ALTER TABLE ONLY cash_float_movements
    ADD CONSTRAINT cash_float_movements_session_id_fkey FOREIGN KEY (session_id) REFERENCES pos_sessions(id);

ALTER TABLE ONLY cash_float_movements
    ADD CONSTRAINT cash_float_movements_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY channel_connections
    ADD CONSTRAINT channel_connections_channel_id_fkey FOREIGN KEY (channel_id) REFERENCES channels(id);

ALTER TABLE ONLY channel_connections
    ADD CONSTRAINT channel_connections_property_id_fkey FOREIGN KEY (property_id) REFERENCES properties(id);

ALTER TABLE ONLY channel_connections
    ADD CONSTRAINT channel_connections_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY channels
    ADD CONSTRAINT channels_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY companies
    ADD CONSTRAINT fk_companies_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY currency_rates
    ADD CONSTRAINT currency_rates_created_by_fkey FOREIGN KEY (created_by) REFERENCES users(id);

ALTER TABLE ONLY currency_rates
    ADD CONSTRAINT currency_rates_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY departments
    ADD CONSTRAINT fk_departments_property_id FOREIGN KEY (property_id) REFERENCES properties(id) DEFERRABLE;

ALTER TABLE ONLY departments
    ADD CONSTRAINT fk_departments_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY property_modules
    ADD CONSTRAINT fk_property_modules_property_id FOREIGN KEY (property_id) REFERENCES properties(id) ON DELETE CASCADE DEFERRABLE;

ALTER TABLE ONLY property_modules
    ADD CONSTRAINT fk_property_modules_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY property_module_configs
    ADD CONSTRAINT fk_property_module_configs_property_id FOREIGN KEY (property_id) REFERENCES properties(id) ON DELETE CASCADE DEFERRABLE;

ALTER TABLE ONLY property_module_configs
    ADD CONSTRAINT fk_property_module_configs_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY document_sequences
    ADD CONSTRAINT fk_document_sequences_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY edge_sync_events
    ADD CONSTRAINT fk_edge_sync_events_property_id FOREIGN KEY (property_id) REFERENCES properties(id) DEFERRABLE;

ALTER TABLE ONLY edge_sync_events
    ADD CONSTRAINT fk_edge_sync_events_resolved_by FOREIGN KEY (resolved_by) REFERENCES users(id) DEFERRABLE;

ALTER TABLE ONLY edge_sync_events
    ADD CONSTRAINT fk_edge_sync_events_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY employees
    ADD CONSTRAINT fk_employees_department_id FOREIGN KEY (department_id) REFERENCES departments(id) DEFERRABLE;

ALTER TABLE ONLY employees
    ADD CONSTRAINT fk_employees_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY employees
    ADD CONSTRAINT fk_employees_user_id FOREIGN KEY (user_id) REFERENCES users(id) DEFERRABLE;

ALTER TABLE ONLY event_booking_items
    ADD CONSTRAINT event_booking_items_booking_id_fkey FOREIGN KEY (booking_id) REFERENCES event_bookings(id);

ALTER TABLE ONLY event_booking_items
    ADD CONSTRAINT event_booking_items_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY event_bookings
    ADD CONSTRAINT event_bookings_company_id_fkey FOREIGN KEY (company_id) REFERENCES companies(id);

ALTER TABLE ONLY event_bookings
    ADD CONSTRAINT event_bookings_event_space_id_fkey FOREIGN KEY (event_space_id) REFERENCES event_spaces(id);

ALTER TABLE ONLY event_bookings
    ADD CONSTRAINT event_bookings_folio_id_fkey FOREIGN KEY (folio_id) REFERENCES folios(id);

ALTER TABLE ONLY event_bookings
    ADD CONSTRAINT event_bookings_group_id_fkey FOREIGN KEY (group_id) REFERENCES groups(id);

ALTER TABLE ONLY event_bookings
    ADD CONSTRAINT event_bookings_guest_id_fkey FOREIGN KEY (guest_id) REFERENCES guests(id);

ALTER TABLE ONLY event_bookings
    ADD CONSTRAINT event_bookings_handled_by_fkey FOREIGN KEY (handled_by) REFERENCES users(id);

ALTER TABLE ONLY event_bookings
    ADD CONSTRAINT event_bookings_package_id_fkey FOREIGN KEY (package_id) REFERENCES event_packages(id);

ALTER TABLE ONLY event_bookings
    ADD CONSTRAINT event_bookings_property_id_fkey FOREIGN KEY (property_id) REFERENCES properties(id);

ALTER TABLE ONLY event_bookings
    ADD CONSTRAINT event_bookings_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY event_packages
    ADD CONSTRAINT event_packages_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY event_spaces
    ADD CONSTRAINT event_spaces_floor_id_fkey FOREIGN KEY (floor_id) REFERENCES floors(id);

ALTER TABLE ONLY event_spaces
    ADD CONSTRAINT event_spaces_property_id_fkey FOREIGN KEY (property_id) REFERENCES properties(id);

ALTER TABLE ONLY event_spaces
    ADD CONSTRAINT event_spaces_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY events
    ADD CONSTRAINT fk_events_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY fiscal_receipts
    ADD CONSTRAINT fk_fiscal_receipts_invoice_id FOREIGN KEY (invoice_id) REFERENCES invoices(id) DEFERRABLE;

ALTER TABLE ONLY fiscal_receipts
    ADD CONSTRAINT fk_fiscal_receipts_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY floors
    ADD CONSTRAINT fk_floors_building_id FOREIGN KEY (building_id) REFERENCES buildings(id) DEFERRABLE;

ALTER TABLE ONLY floors
    ADD CONSTRAINT fk_floors_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY revenue_centers
    ADD CONSTRAINT fk_revenue_centers_property_id FOREIGN KEY (property_id) REFERENCES properties(id) DEFERRABLE;

ALTER TABLE ONLY revenue_centers
    ADD CONSTRAINT fk_revenue_centers_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY folio_charges
    ADD CONSTRAINT fk_folio_charges_folio_id FOREIGN KEY (folio_id) REFERENCES folios(id) DEFERRABLE;

ALTER TABLE ONLY folio_charges
    ADD CONSTRAINT fk_folio_charges_posted_by FOREIGN KEY (posted_by) REFERENCES users(id) DEFERRABLE;

ALTER TABLE ONLY folio_charges
    ADD CONSTRAINT fk_folio_charges_property_id FOREIGN KEY (property_id) REFERENCES properties(id) DEFERRABLE;

ALTER TABLE ONLY folio_charges
    ADD CONSTRAINT fk_folio_charges_revenue_center_id FOREIGN KEY (revenue_center_id) REFERENCES revenue_centers(id) DEFERRABLE;

ALTER TABLE ONLY folio_charges
    ADD CONSTRAINT fk_folio_charges_reversal_of FOREIGN KEY (reversal_of) REFERENCES folio_charges(id) DEFERRABLE;

ALTER TABLE ONLY folio_charges
    ADD CONSTRAINT fk_folio_charges_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY folio_charges
    ADD CONSTRAINT fk_folio_charges_voided_by FOREIGN KEY (voided_by) REFERENCES users(id) DEFERRABLE;

ALTER TABLE ONLY folio_payments
    ADD CONSTRAINT fk_folio_payments_created_by FOREIGN KEY (created_by) REFERENCES users(id) DEFERRABLE;

ALTER TABLE ONLY folio_payments
    ADD CONSTRAINT fk_folio_payments_folio_id FOREIGN KEY (folio_id) REFERENCES folios(id) DEFERRABLE;

ALTER TABLE ONLY folio_payments
    ADD CONSTRAINT fk_folio_payments_processed_by FOREIGN KEY (processed_by) REFERENCES users(id) DEFERRABLE;

ALTER TABLE ONLY folio_payments
    ADD CONSTRAINT fk_folio_payments_reversal_of FOREIGN KEY (reversal_of) REFERENCES folio_payments(id) DEFERRABLE;

ALTER TABLE ONLY folio_payments
    ADD CONSTRAINT fk_folio_payments_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY folios
    ADD CONSTRAINT fk_folios_parent_folio_id FOREIGN KEY (parent_folio_id) REFERENCES folios(id) DEFERRABLE;

ALTER TABLE ONLY folios
    ADD CONSTRAINT fk_folios_property_id FOREIGN KEY (property_id) REFERENCES properties(id) DEFERRABLE;

ALTER TABLE ONLY folios
    ADD CONSTRAINT fk_folios_reservation_id FOREIGN KEY (reservation_id) REFERENCES reservations(id) DEFERRABLE;

ALTER TABLE ONLY folios
    ADD CONSTRAINT fk_folios_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY groups
    ADD CONSTRAINT groups_account_manager_fkey FOREIGN KEY (account_manager) REFERENCES users(id);

ALTER TABLE ONLY groups
    ADD CONSTRAINT groups_company_id_fkey FOREIGN KEY (company_id) REFERENCES companies(id);

ALTER TABLE ONLY groups
    ADD CONSTRAINT groups_property_id_fkey FOREIGN KEY (property_id) REFERENCES properties(id);

ALTER TABLE ONLY groups
    ADD CONSTRAINT groups_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY guest_contacts
    ADD CONSTRAINT fk_guest_contacts_guest_id FOREIGN KEY (guest_id) REFERENCES guests(id) DEFERRABLE;

ALTER TABLE ONLY guest_contacts
    ADD CONSTRAINT fk_guest_contacts_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY guest_documents
    ADD CONSTRAINT fk_guest_documents_guest_id FOREIGN KEY (guest_id) REFERENCES guests(id) DEFERRABLE;

ALTER TABLE ONLY guest_documents
    ADD CONSTRAINT fk_guest_documents_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY guest_documents
    ADD CONSTRAINT fk_guest_documents_verified_by FOREIGN KEY (verified_by) REFERENCES users(id) DEFERRABLE;

ALTER TABLE ONLY guest_feedback
    ADD CONSTRAINT guest_feedback_guest_id_fkey FOREIGN KEY (guest_id) REFERENCES guests(id);

ALTER TABLE ONLY guest_feedback
    ADD CONSTRAINT guest_feedback_property_id_fkey FOREIGN KEY (property_id) REFERENCES properties(id);

ALTER TABLE ONLY guest_feedback
    ADD CONSTRAINT guest_feedback_reservation_id_fkey FOREIGN KEY (reservation_id) REFERENCES reservations(id);

ALTER TABLE ONLY guest_feedback
    ADD CONSTRAINT guest_feedback_responded_by_fkey FOREIGN KEY (responded_by) REFERENCES users(id);

ALTER TABLE ONLY guest_feedback
    ADD CONSTRAINT guest_feedback_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY guest_preferences
    ADD CONSTRAINT fk_guest_preferences_guest_id FOREIGN KEY (guest_id) REFERENCES guests(id) DEFERRABLE;

ALTER TABLE ONLY guest_preferences
    ADD CONSTRAINT fk_guest_preferences_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY guests
    ADD CONSTRAINT fk_guests_company_id FOREIGN KEY (company_id) REFERENCES companies(id) DEFERRABLE;

ALTER TABLE ONLY guests
    ADD CONSTRAINT fk_guests_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY housekeeping_assignments
    ADD CONSTRAINT fk_housekeeping_assignments_task_id FOREIGN KEY (task_id) REFERENCES housekeeping_tasks(id) DEFERRABLE;

ALTER TABLE ONLY housekeeping_assignments
    ADD CONSTRAINT fk_housekeeping_assignments_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY housekeeping_assignments
    ADD CONSTRAINT fk_housekeeping_assignments_user_id FOREIGN KEY (user_id) REFERENCES users(id) DEFERRABLE;

ALTER TABLE ONLY housekeeping_tasks
    ADD CONSTRAINT fk_housekeeping_tasks_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY inventory_items
    ADD CONSTRAINT fk_inventory_items_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY inventory_locations
    ADD CONSTRAINT fk_inventory_locations_outlet_id FOREIGN KEY (outlet_id) REFERENCES outlets(id) DEFERRABLE;

ALTER TABLE ONLY inventory_locations
    ADD CONSTRAINT fk_inventory_locations_property_id FOREIGN KEY (property_id) REFERENCES properties(id) DEFERRABLE;

ALTER TABLE ONLY inventory_locations
    ADD CONSTRAINT fk_inventory_locations_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY invoice_items
    ADD CONSTRAINT fk_invoice_items_invoice_id FOREIGN KEY (invoice_id) REFERENCES invoices(id) DEFERRABLE;

ALTER TABLE ONLY invoice_items
    ADD CONSTRAINT fk_invoice_items_property_id FOREIGN KEY (property_id) REFERENCES properties(id) DEFERRABLE;

ALTER TABLE ONLY invoice_items
    ADD CONSTRAINT fk_invoice_items_revenue_center_id FOREIGN KEY (revenue_center_id) REFERENCES revenue_centers(id) DEFERRABLE;

ALTER TABLE ONLY invoice_items
    ADD CONSTRAINT fk_invoice_items_reversal_of FOREIGN KEY (reversal_of) REFERENCES invoice_items(id) DEFERRABLE;

ALTER TABLE ONLY invoice_items
    ADD CONSTRAINT fk_invoice_items_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY invoices
    ADD CONSTRAINT fk_invoices_company_id FOREIGN KEY (company_id) REFERENCES companies(id) DEFERRABLE;

ALTER TABLE ONLY invoices
    ADD CONSTRAINT fk_invoices_created_by FOREIGN KEY (created_by) REFERENCES users(id) DEFERRABLE;

ALTER TABLE ONLY invoices
    ADD CONSTRAINT fk_invoices_folio_id FOREIGN KEY (folio_id) REFERENCES folios(id) DEFERRABLE;

ALTER TABLE ONLY invoices
    ADD CONSTRAINT fk_invoices_property_id FOREIGN KEY (property_id) REFERENCES properties(id) DEFERRABLE;

ALTER TABLE ONLY invoices
    ADD CONSTRAINT fk_invoices_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY kitchen_tickets
    ADD CONSTRAINT fk_kitchen_tickets_order_id FOREIGN KEY (order_id) REFERENCES pos_orders(id) DEFERRABLE;

ALTER TABLE ONLY kitchen_tickets
    ADD CONSTRAINT fk_kitchen_tickets_outlet_id FOREIGN KEY (outlet_id) REFERENCES outlets(id) DEFERRABLE;

ALTER TABLE ONLY kitchen_tickets
    ADD CONSTRAINT fk_kitchen_tickets_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY lost_and_found
    ADD CONSTRAINT fk_lost_and_found_claimed_by FOREIGN KEY (claimed_by) REFERENCES guests(id) DEFERRABLE;

ALTER TABLE ONLY lost_and_found
    ADD CONSTRAINT fk_lost_and_found_found_by FOREIGN KEY (found_by) REFERENCES users(id) DEFERRABLE;

ALTER TABLE ONLY lost_and_found
    ADD CONSTRAINT fk_lost_and_found_property_id FOREIGN KEY (property_id) REFERENCES properties(id) DEFERRABLE;

ALTER TABLE ONLY lost_and_found
    ADD CONSTRAINT fk_lost_and_found_room_id FOREIGN KEY (room_id) REFERENCES rooms(id) DEFERRABLE;

ALTER TABLE ONLY lost_and_found
    ADD CONSTRAINT fk_lost_and_found_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY loyalty_accounts
    ADD CONSTRAINT fk_loyalty_accounts_guest_id FOREIGN KEY (guest_id) REFERENCES guests(id) DEFERRABLE;

ALTER TABLE ONLY loyalty_accounts
    ADD CONSTRAINT fk_loyalty_accounts_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY loyalty_transactions
    ADD CONSTRAINT fk_loyalty_transactions_account_id FOREIGN KEY (account_id) REFERENCES loyalty_accounts(id) DEFERRABLE;

ALTER TABLE ONLY loyalty_transactions
    ADD CONSTRAINT fk_loyalty_transactions_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY maintenance_requests
    ADD CONSTRAINT fk_maintenance_requests_assigned_to FOREIGN KEY (assigned_to) REFERENCES users(id) DEFERRABLE;

ALTER TABLE ONLY maintenance_requests
    ADD CONSTRAINT fk_maintenance_requests_reported_by FOREIGN KEY (reported_by) REFERENCES users(id) DEFERRABLE;

ALTER TABLE ONLY maintenance_requests
    ADD CONSTRAINT fk_maintenance_requests_room_id FOREIGN KEY (room_id) REFERENCES rooms(id) DEFERRABLE;

ALTER TABLE ONLY maintenance_requests
    ADD CONSTRAINT fk_maintenance_requests_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY menu_categories
    ADD CONSTRAINT fk_menu_categories_outlet_id FOREIGN KEY (outlet_id) REFERENCES outlets(id) DEFERRABLE;

ALTER TABLE ONLY menu_categories
    ADD CONSTRAINT fk_menu_categories_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY menu_item_recipes
    ADD CONSTRAINT fk_menu_item_recipes_inventory_item_id FOREIGN KEY (inventory_item_id) REFERENCES inventory_items(id) DEFERRABLE;

ALTER TABLE ONLY menu_item_recipes
    ADD CONSTRAINT fk_menu_item_recipes_menu_item_id FOREIGN KEY (menu_item_id) REFERENCES menu_items(id) DEFERRABLE;

ALTER TABLE ONLY menu_item_recipes
    ADD CONSTRAINT fk_menu_item_recipes_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY menu_items
    ADD CONSTRAINT fk_menu_items_category_id FOREIGN KEY (category_id) REFERENCES menu_categories(id) DEFERRABLE;

ALTER TABLE ONLY menu_items
    ADD CONSTRAINT fk_menu_items_inventory_item_id FOREIGN KEY (inventory_item_id) REFERENCES inventory_items(id) DEFERRABLE;

ALTER TABLE ONLY menu_items
    ADD CONSTRAINT fk_menu_items_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY menu_items
    ADD CONSTRAINT menu_items_tax_rate_id_fkey FOREIGN KEY (tax_rate_id) REFERENCES tax_rates(id);

ALTER TABLE ONLY night_audit_runs
    ADD CONSTRAINT fk_night_audit_runs_property_id FOREIGN KEY (property_id) REFERENCES properties(id) DEFERRABLE;

ALTER TABLE ONLY night_audit_runs
    ADD CONSTRAINT fk_night_audit_runs_run_by FOREIGN KEY (run_by) REFERENCES users(id) DEFERRABLE;

ALTER TABLE ONLY night_audit_runs
    ADD CONSTRAINT fk_night_audit_runs_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY notifications
    ADD CONSTRAINT notifications_property_id_fkey FOREIGN KEY (property_id) REFERENCES properties(id);

ALTER TABLE ONLY notifications
    ADD CONSTRAINT notifications_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY notifications
    ADD CONSTRAINT notifications_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id);

ALTER TABLE ONLY ota_room_type_mappings
    ADD CONSTRAINT ota_room_type_mappings_channel_connection_id_fkey FOREIGN KEY (channel_connection_id) REFERENCES channel_connections(id);

ALTER TABLE ONLY ota_room_type_mappings
    ADD CONSTRAINT ota_room_type_mappings_rate_plan_id_fkey FOREIGN KEY (rate_plan_id) REFERENCES rate_plans(id);

ALTER TABLE ONLY ota_room_type_mappings
    ADD CONSTRAINT ota_room_type_mappings_room_type_id_fkey FOREIGN KEY (room_type_id) REFERENCES room_types(id);

ALTER TABLE ONLY ota_room_type_mappings
    ADD CONSTRAINT ota_room_type_mappings_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY outlets
    ADD CONSTRAINT fk_outlets_property_id FOREIGN KEY (property_id) REFERENCES properties(id) DEFERRABLE;

ALTER TABLE ONLY outlets
    ADD CONSTRAINT fk_outlets_revenue_center_id FOREIGN KEY (revenue_center_id) REFERENCES revenue_centers(id) DEFERRABLE;

ALTER TABLE ONLY outlets
    ADD CONSTRAINT fk_outlets_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY property_pos_settings
    ADD CONSTRAINT property_pos_settings_property_id_fkey FOREIGN KEY (property_id) REFERENCES properties(id) ON DELETE CASCADE;

ALTER TABLE ONLY property_pos_settings
    ADD CONSTRAINT property_pos_settings_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY payroll_records
    ADD CONSTRAINT fk_payroll_records_employee_id FOREIGN KEY (employee_id) REFERENCES employees(id) DEFERRABLE;

ALTER TABLE ONLY payroll_records
    ADD CONSTRAINT fk_payroll_records_payroll_run_id FOREIGN KEY (payroll_run_id) REFERENCES payroll_runs(id) DEFERRABLE;

ALTER TABLE ONLY payroll_records
    ADD CONSTRAINT fk_payroll_records_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY payroll_runs
    ADD CONSTRAINT fk_payroll_runs_approved_by FOREIGN KEY (approved_by) REFERENCES users(id) DEFERRABLE;

ALTER TABLE ONLY payroll_runs
    ADD CONSTRAINT fk_payroll_runs_run_by FOREIGN KEY (run_by) REFERENCES users(id) DEFERRABLE;

ALTER TABLE ONLY payroll_runs
    ADD CONSTRAINT fk_payroll_runs_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY permissions
    ADD CONSTRAINT fk_permissions_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY pos_order_items
    ADD CONSTRAINT fk_pos_order_items_menu_item_id FOREIGN KEY (menu_item_id) REFERENCES menu_items(id) DEFERRABLE;

ALTER TABLE ONLY pos_order_items
    ADD CONSTRAINT fk_pos_order_items_order_id FOREIGN KEY (order_id) REFERENCES pos_orders(id) DEFERRABLE;

ALTER TABLE ONLY pos_order_items
    ADD CONSTRAINT fk_pos_order_items_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY pos_orders
    ADD CONSTRAINT fk_pos_orders_edge_sync_id FOREIGN KEY (edge_sync_id) REFERENCES edge_sync_events(id) DEFERRABLE;

ALTER TABLE ONLY pos_orders
    ADD CONSTRAINT fk_pos_orders_folio_id FOREIGN KEY (folio_id) REFERENCES folios(id) DEFERRABLE;

ALTER TABLE ONLY pos_orders
    ADD CONSTRAINT fk_pos_orders_outlet_id FOREIGN KEY (outlet_id) REFERENCES outlets(id) DEFERRABLE;

ALTER TABLE ONLY pos_orders
    ADD CONSTRAINT fk_pos_orders_property_id FOREIGN KEY (property_id) REFERENCES properties(id) DEFERRABLE;

ALTER TABLE ONLY pos_orders
    ADD CONSTRAINT fk_pos_orders_revenue_center_id FOREIGN KEY (revenue_center_id) REFERENCES revenue_centers(id) DEFERRABLE;

ALTER TABLE ONLY pos_orders
    ADD CONSTRAINT fk_pos_orders_served_by FOREIGN KEY (served_by) REFERENCES users(id) DEFERRABLE;

ALTER TABLE ONLY pos_orders
    ADD CONSTRAINT fk_pos_orders_session_id FOREIGN KEY (session_id) REFERENCES pos_sessions(id) DEFERRABLE;

ALTER TABLE ONLY pos_orders
    ADD CONSTRAINT fk_pos_orders_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY pos_sessions
    ADD CONSTRAINT fk_pos_sessions_cashier_id FOREIGN KEY (cashier_id) REFERENCES users(id) DEFERRABLE;

ALTER TABLE ONLY pos_sessions
    ADD CONSTRAINT fk_pos_sessions_outlet_id FOREIGN KEY (outlet_id) REFERENCES outlets(id) DEFERRABLE;

ALTER TABLE ONLY pos_sessions
    ADD CONSTRAINT fk_pos_sessions_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY pos_terminals
    ADD CONSTRAINT pos_terminals_outlet_id_fkey FOREIGN KEY (outlet_id) REFERENCES outlets(id);

ALTER TABLE ONLY pos_terminals
    ADD CONSTRAINT pos_terminals_property_id_fkey FOREIGN KEY (property_id) REFERENCES properties(id);

ALTER TABLE ONLY pos_terminals
    ADD CONSTRAINT pos_terminals_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY pos_printer_routes
    ADD CONSTRAINT pos_printer_routes_outlet_id_fkey FOREIGN KEY (outlet_id) REFERENCES outlets(id);

ALTER TABLE ONLY pos_printer_routes
    ADD CONSTRAINT pos_printer_routes_property_id_fkey FOREIGN KEY (property_id) REFERENCES properties(id);

ALTER TABLE ONLY pos_printer_routes
    ADD CONSTRAINT pos_printer_routes_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY pricing_rules
    ADD CONSTRAINT fk_pricing_rules_rate_plan_id FOREIGN KEY (rate_plan_id) REFERENCES rate_plans(id) DEFERRABLE;

ALTER TABLE ONLY pricing_rules
    ADD CONSTRAINT fk_pricing_rules_room_type_id FOREIGN KEY (room_type_id) REFERENCES room_types(id) DEFERRABLE;

ALTER TABLE ONLY pricing_rules
    ADD CONSTRAINT fk_pricing_rules_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY promo_codes
    ADD CONSTRAINT promo_codes_channel_id_fkey FOREIGN KEY (channel_id) REFERENCES channels(id);

ALTER TABLE ONLY promo_codes
    ADD CONSTRAINT promo_codes_property_id_fkey FOREIGN KEY (property_id) REFERENCES properties(id);

ALTER TABLE ONLY promo_codes
    ADD CONSTRAINT promo_codes_rate_plan_id_fkey FOREIGN KEY (rate_plan_id) REFERENCES rate_plans(id);

ALTER TABLE ONLY promo_codes
    ADD CONSTRAINT promo_codes_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY properties
    ADD CONSTRAINT fk_properties_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY purchase_order_items
    ADD CONSTRAINT fk_purchase_order_items_inventory_item_id FOREIGN KEY (inventory_item_id) REFERENCES inventory_items(id) DEFERRABLE;

ALTER TABLE ONLY purchase_order_items
    ADD CONSTRAINT fk_purchase_order_items_purchase_order_id FOREIGN KEY (purchase_order_id) REFERENCES purchase_orders(id) DEFERRABLE;

ALTER TABLE ONLY purchase_order_items
    ADD CONSTRAINT fk_purchase_order_items_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY purchase_orders
    ADD CONSTRAINT fk_purchase_orders_supplier_id FOREIGN KEY (supplier_id) REFERENCES suppliers(id) DEFERRABLE;

ALTER TABLE ONLY purchase_orders
    ADD CONSTRAINT fk_purchase_orders_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY purchase_orders
    ADD CONSTRAINT purchase_orders_approval_request_id_fkey FOREIGN KEY (approval_request_id) REFERENCES approval_requests(id);

ALTER TABLE ONLY rate_plan_prices
    ADD CONSTRAINT fk_rate_plan_prices_rate_plan_id FOREIGN KEY (rate_plan_id) REFERENCES rate_plans(id) DEFERRABLE;

ALTER TABLE ONLY rate_plan_prices
    ADD CONSTRAINT fk_rate_plan_prices_room_type_id FOREIGN KEY (room_type_id) REFERENCES room_types(id) DEFERRABLE;

ALTER TABLE ONLY rate_plan_prices
    ADD CONSTRAINT fk_rate_plan_prices_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY rate_plans
    ADD CONSTRAINT fk_rate_plans_property_id FOREIGN KEY (property_id) REFERENCES properties(id) DEFERRABLE;

ALTER TABLE ONLY rate_plans
    ADD CONSTRAINT fk_rate_plans_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY rate_restrictions
    ADD CONSTRAINT rate_restrictions_rate_plan_id_fkey FOREIGN KEY (rate_plan_id) REFERENCES rate_plans(id);

ALTER TABLE ONLY rate_restrictions
    ADD CONSTRAINT rate_restrictions_room_type_id_fkey FOREIGN KEY (room_type_id) REFERENCES room_types(id);

ALTER TABLE ONLY rate_restrictions
    ADD CONSTRAINT rate_restrictions_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY refresh_tokens
    ADD CONSTRAINT fk_refresh_tokens_user_id FOREIGN KEY (user_id) REFERENCES users(id) DEFERRABLE;

ALTER TABLE ONLY reservation_guests
    ADD CONSTRAINT fk_reservation_guests_guest_id FOREIGN KEY (guest_id) REFERENCES guests(id) DEFERRABLE;

ALTER TABLE ONLY reservation_guests
    ADD CONSTRAINT fk_reservation_guests_reservation_id FOREIGN KEY (reservation_id) REFERENCES reservations(id) DEFERRABLE;

ALTER TABLE ONLY reservation_guests
    ADD CONSTRAINT fk_reservation_guests_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY reservation_notes
    ADD CONSTRAINT fk_reservation_notes_created_by FOREIGN KEY (created_by) REFERENCES users(id) DEFERRABLE;

ALTER TABLE ONLY reservation_notes
    ADD CONSTRAINT fk_reservation_notes_reservation_id FOREIGN KEY (reservation_id) REFERENCES reservations(id) DEFERRABLE;

ALTER TABLE ONLY reservation_notes
    ADD CONSTRAINT fk_reservation_notes_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY reservation_rooms
    ADD CONSTRAINT fk_reservation_rooms_folio_id FOREIGN KEY (folio_id) REFERENCES folios(id) DEFERRABLE;

ALTER TABLE ONLY reservation_rooms
    ADD CONSTRAINT fk_reservation_rooms_reservation_id FOREIGN KEY (reservation_id) REFERENCES reservations(id) DEFERRABLE;

ALTER TABLE ONLY reservation_rooms
    ADD CONSTRAINT fk_reservation_rooms_room_id FOREIGN KEY (room_id) REFERENCES rooms(id) DEFERRABLE;

ALTER TABLE ONLY reservation_rooms
    ADD CONSTRAINT fk_reservation_rooms_room_type_id FOREIGN KEY (room_type_id) REFERENCES room_types(id) DEFERRABLE;

ALTER TABLE ONLY reservation_rooms
    ADD CONSTRAINT fk_reservation_rooms_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY reservations
    ADD CONSTRAINT fk_reservations_created_by FOREIGN KEY (created_by) REFERENCES users(id) DEFERRABLE;

ALTER TABLE ONLY reservations
    ADD CONSTRAINT fk_reservations_group FOREIGN KEY (group_id) REFERENCES groups(id) DEFERRABLE;

ALTER TABLE ONLY reservations
    ADD CONSTRAINT fk_reservations_primary_guest_id FOREIGN KEY (primary_guest_id) REFERENCES guests(id) DEFERRABLE;

ALTER TABLE ONLY reservations
    ADD CONSTRAINT fk_reservations_property_id FOREIGN KEY (property_id) REFERENCES properties(id) DEFERRABLE;

ALTER TABLE ONLY reservations
    ADD CONSTRAINT fk_reservations_rate_plan_id FOREIGN KEY (rate_plan_id) REFERENCES rate_plans(id) DEFERRABLE;

ALTER TABLE ONLY reservations
    ADD CONSTRAINT fk_reservations_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY reservations
    ADD CONSTRAINT reservations_channel_id_fkey FOREIGN KEY (channel_id) REFERENCES channels(id);

ALTER TABLE ONLY role_permissions
    ADD CONSTRAINT fk_role_permissions_permission_id FOREIGN KEY (permission_id) REFERENCES permissions(id) DEFERRABLE;

ALTER TABLE ONLY role_permissions
    ADD CONSTRAINT fk_role_permissions_role_id FOREIGN KEY (role_id) REFERENCES roles(id) DEFERRABLE;

ALTER TABLE ONLY roles
    ADD CONSTRAINT fk_roles_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY room_status_log
    ADD CONSTRAINT fk_room_status_log_changed_by FOREIGN KEY (changed_by) REFERENCES users(id) DEFERRABLE;

ALTER TABLE ONLY room_status_log
    ADD CONSTRAINT fk_room_status_log_room_id FOREIGN KEY (room_id) REFERENCES rooms(id) DEFERRABLE;

ALTER TABLE ONLY room_status_log
    ADD CONSTRAINT fk_room_status_log_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY room_types
    ADD CONSTRAINT fk_room_types_property_id FOREIGN KEY (property_id) REFERENCES properties(id) DEFERRABLE;

ALTER TABLE ONLY room_types
    ADD CONSTRAINT fk_room_types_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY rooms
    ADD CONSTRAINT fk_rooms_connecting_room_id FOREIGN KEY (connecting_room_id) REFERENCES rooms(id) DEFERRABLE;

ALTER TABLE ONLY rooms
    ADD CONSTRAINT fk_rooms_property_id FOREIGN KEY (property_id) REFERENCES properties(id) DEFERRABLE;

ALTER TABLE ONLY rooms
    ADD CONSTRAINT fk_rooms_room_type_id FOREIGN KEY (room_type_id) REFERENCES room_types(id) DEFERRABLE;

ALTER TABLE ONLY rooms
    ADD CONSTRAINT fk_rooms_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY shift_handovers
    ADD CONSTRAINT shift_handovers_department_id_fkey FOREIGN KEY (department_id) REFERENCES departments(id);

ALTER TABLE ONLY shift_handovers
    ADD CONSTRAINT shift_handovers_incoming_user_id_fkey FOREIGN KEY (incoming_user_id) REFERENCES users(id);

ALTER TABLE ONLY shift_handovers
    ADD CONSTRAINT shift_handovers_outgoing_user_id_fkey FOREIGN KEY (outgoing_user_id) REFERENCES users(id);

ALTER TABLE ONLY shift_handovers
    ADD CONSTRAINT shift_handovers_pos_session_id_fkey FOREIGN KEY (pos_session_id) REFERENCES pos_sessions(id);

ALTER TABLE ONLY shift_handovers
    ADD CONSTRAINT shift_handovers_property_id_fkey FOREIGN KEY (property_id) REFERENCES properties(id);

ALTER TABLE ONLY shift_handovers
    ADD CONSTRAINT shift_handovers_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY shift_templates
    ADD CONSTRAINT shift_templates_department_id_fkey FOREIGN KEY (department_id) REFERENCES departments(id);

ALTER TABLE ONLY shift_templates
    ADD CONSTRAINT shift_templates_property_id_fkey FOREIGN KEY (property_id) REFERENCES properties(id);

ALTER TABLE ONLY shift_templates
    ADD CONSTRAINT shift_templates_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY stays
    ADD CONSTRAINT fk_stays_reservation_id FOREIGN KEY (reservation_id) REFERENCES reservations(id) DEFERRABLE;

ALTER TABLE ONLY stays
    ADD CONSTRAINT fk_stays_room_id FOREIGN KEY (room_id) REFERENCES rooms(id) DEFERRABLE;

ALTER TABLE ONLY stays
    ADD CONSTRAINT fk_stays_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY stock_levels
    ADD CONSTRAINT fk_stock_levels_item_id FOREIGN KEY (item_id) REFERENCES inventory_items(id) DEFERRABLE;

ALTER TABLE ONLY stock_levels
    ADD CONSTRAINT fk_stock_levels_location_id FOREIGN KEY (location_id) REFERENCES inventory_locations(id) DEFERRABLE;

ALTER TABLE ONLY stock_levels
    ADD CONSTRAINT fk_stock_levels_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY stock_movements
    ADD CONSTRAINT fk_stock_movements_item_id FOREIGN KEY (item_id) REFERENCES inventory_items(id) DEFERRABLE;

ALTER TABLE ONLY stock_movements
    ADD CONSTRAINT fk_stock_movements_location_id FOREIGN KEY (location_id) REFERENCES inventory_locations(id) DEFERRABLE;

ALTER TABLE ONLY stock_movements
    ADD CONSTRAINT fk_stock_movements_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY suppliers
    ADD CONSTRAINT fk_suppliers_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY tax_rates
    ADD CONSTRAINT tax_rates_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY tenant_configs
    ADD CONSTRAINT fk_tenant_configs_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY tenant_module_configs
    ADD CONSTRAINT fk_tenant_module_configs_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY tenant_modules
    ADD CONSTRAINT fk_tenant_modules_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY tenants
    ADD CONSTRAINT tenants_plan_id_fkey FOREIGN KEY (plan_id) REFERENCES plans(id);

ALTER TABLE ONLY translations
    ADD CONSTRAINT translations_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY translations
    ADD CONSTRAINT translations_translated_by_fkey FOREIGN KEY (translated_by) REFERENCES users(id);

ALTER TABLE ONLY user_property_roles
    ADD CONSTRAINT fk_user_property_roles_property_id FOREIGN KEY (property_id) REFERENCES properties(id) DEFERRABLE;

ALTER TABLE ONLY user_property_roles
    ADD CONSTRAINT fk_user_property_roles_role_id FOREIGN KEY (role_id) REFERENCES roles(id) DEFERRABLE;

ALTER TABLE ONLY user_property_roles
    ADD CONSTRAINT fk_user_property_roles_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY user_property_roles
    ADD CONSTRAINT fk_user_property_roles_user_id FOREIGN KEY (user_id) REFERENCES users(id) DEFERRABLE;

ALTER TABLE ONLY users
    ADD CONSTRAINT fk_users_created_by FOREIGN KEY (created_by) REFERENCES users(id) DEFERRABLE;

ALTER TABLE ONLY users
    ADD CONSTRAINT fk_users_department_id FOREIGN KEY (department_id) REFERENCES departments(id) DEFERRABLE;

ALTER TABLE ONLY users
    ADD CONSTRAINT fk_users_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY work_orders
    ADD CONSTRAINT work_orders_assigned_to_fkey FOREIGN KEY (assigned_to) REFERENCES employees(id);

ALTER TABLE ONLY work_orders
    ADD CONSTRAINT work_orders_property_id_fkey FOREIGN KEY (property_id) REFERENCES properties(id);

ALTER TABLE ONLY work_orders
    ADD CONSTRAINT work_orders_request_id_fkey FOREIGN KEY (request_id) REFERENCES maintenance_requests(id);

ALTER TABLE ONLY work_orders
    ADD CONSTRAINT work_orders_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY work_orders
    ADD CONSTRAINT work_orders_verified_by_fkey FOREIGN KEY (verified_by) REFERENCES users(id);

ALTER TABLE ONLY accounting_accounts
    ADD CONSTRAINT accounting_accounts_parent_account_id_fkey FOREIGN KEY (parent_account_id) REFERENCES accounting_accounts(id);

ALTER TABLE ONLY accounting_accounts
    ADD CONSTRAINT accounting_accounts_property_id_fkey FOREIGN KEY (property_id) REFERENCES properties(id);

ALTER TABLE ONLY accounting_accounts
    ADD CONSTRAINT accounting_accounts_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY booking_policies
    ADD CONSTRAINT booking_policies_property_id_fkey FOREIGN KEY (property_id) REFERENCES properties(id);

ALTER TABLE ONLY booking_policies
    ADD CONSTRAINT booking_policies_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY corporate_rate_agreements
    ADD CONSTRAINT corporate_rate_agreements_company_id_fkey FOREIGN KEY (company_id) REFERENCES companies(id);

ALTER TABLE ONLY corporate_rate_agreements
    ADD CONSTRAINT corporate_rate_agreements_created_by_fkey FOREIGN KEY (created_by) REFERENCES users(id);

ALTER TABLE ONLY corporate_rate_agreements
    ADD CONSTRAINT corporate_rate_agreements_property_id_fkey FOREIGN KEY (property_id) REFERENCES properties(id);

ALTER TABLE ONLY corporate_rate_agreements
    ADD CONSTRAINT corporate_rate_agreements_rate_plan_id_fkey FOREIGN KEY (rate_plan_id) REFERENCES rate_plans(id);

ALTER TABLE ONLY corporate_rate_agreements
    ADD CONSTRAINT corporate_rate_agreements_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY folio_charge_taxes
    ADD CONSTRAINT folio_charge_taxes_folio_charge_id_fkey FOREIGN KEY (folio_charge_id) REFERENCES folio_charges(id);

ALTER TABLE ONLY folio_charge_taxes
    ADD CONSTRAINT folio_charge_taxes_tax_rate_id_fkey FOREIGN KEY (tax_rate_id) REFERENCES tax_rates(id);

ALTER TABLE ONLY folio_charge_taxes
    ADD CONSTRAINT folio_charge_taxes_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY invoice_item_taxes
    ADD CONSTRAINT invoice_item_taxes_invoice_item_id_fkey FOREIGN KEY (invoice_item_id) REFERENCES invoice_items(id);

ALTER TABLE ONLY invoice_item_taxes
    ADD CONSTRAINT invoice_item_taxes_tax_rate_id_fkey FOREIGN KEY (tax_rate_id) REFERENCES tax_rates(id);

ALTER TABLE ONLY invoice_item_taxes
    ADD CONSTRAINT invoice_item_taxes_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY journal_entries
    ADD CONSTRAINT journal_entries_posted_by_fkey FOREIGN KEY (posted_by) REFERENCES users(id);

ALTER TABLE ONLY journal_entries
    ADD CONSTRAINT journal_entries_property_id_fkey FOREIGN KEY (property_id) REFERENCES properties(id);

ALTER TABLE ONLY journal_entries
    ADD CONSTRAINT journal_entries_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY journal_entries
    ADD CONSTRAINT journal_entries_voided_by_fkey FOREIGN KEY (voided_by) REFERENCES users(id);

ALTER TABLE ONLY journal_entry_lines
    ADD CONSTRAINT journal_entry_lines_account_id_fkey FOREIGN KEY (account_id) REFERENCES accounting_accounts(id);

ALTER TABLE ONLY journal_entry_lines
    ADD CONSTRAINT journal_entry_lines_journal_entry_id_fkey FOREIGN KEY (journal_entry_id) REFERENCES journal_entries(id);

ALTER TABLE ONLY journal_entry_lines
    ADD CONSTRAINT journal_entry_lines_property_id_fkey FOREIGN KEY (property_id) REFERENCES properties(id);

ALTER TABLE ONLY journal_entry_lines
    ADD CONSTRAINT journal_entry_lines_revenue_center_id_fkey FOREIGN KEY (revenue_center_id) REFERENCES revenue_centers(id);

ALTER TABLE ONLY journal_entry_lines
    ADD CONSTRAINT journal_entry_lines_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY labor_forecasts
    ADD CONSTRAINT labor_forecasts_department_id_fkey FOREIGN KEY (department_id) REFERENCES departments(id);

ALTER TABLE ONLY labor_forecasts
    ADD CONSTRAINT labor_forecasts_property_id_fkey FOREIGN KEY (property_id) REFERENCES properties(id);

ALTER TABLE ONLY labor_forecasts
    ADD CONSTRAINT labor_forecasts_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY leave_requests
    ADD CONSTRAINT leave_requests_approved_by_fkey FOREIGN KEY (approved_by) REFERENCES users(id);

ALTER TABLE ONLY leave_requests
    ADD CONSTRAINT leave_requests_employee_id_fkey FOREIGN KEY (employee_id) REFERENCES employees(id);

ALTER TABLE ONLY leave_requests
    ADD CONSTRAINT leave_requests_requested_by_fkey FOREIGN KEY (requested_by) REFERENCES users(id);

ALTER TABLE ONLY leave_requests
    ADD CONSTRAINT leave_requests_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY reservation_deposits
    ADD CONSTRAINT reservation_deposits_folio_id_fkey FOREIGN KEY (folio_id) REFERENCES folios(id);

ALTER TABLE ONLY reservation_deposits
    ADD CONSTRAINT reservation_deposits_policy_snapshot_id_fkey FOREIGN KEY (policy_snapshot_id) REFERENCES reservation_policy_snapshots(id);

ALTER TABLE ONLY reservation_deposits
    ADD CONSTRAINT reservation_deposits_property_id_fkey FOREIGN KEY (property_id) REFERENCES properties(id);

ALTER TABLE ONLY reservation_deposits
    ADD CONSTRAINT reservation_deposits_reservation_id_fkey FOREIGN KEY (reservation_id) REFERENCES reservations(id);

ALTER TABLE ONLY reservation_deposits
    ADD CONSTRAINT reservation_deposits_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY reservation_policy_snapshots
    ADD CONSTRAINT reservation_policy_snapshots_policy_id_fkey FOREIGN KEY (policy_id) REFERENCES booking_policies(id);

ALTER TABLE ONLY reservation_policy_snapshots
    ADD CONSTRAINT reservation_policy_snapshots_reservation_id_fkey FOREIGN KEY (reservation_id) REFERENCES reservations(id);

ALTER TABLE ONLY reservation_policy_snapshots
    ADD CONSTRAINT reservation_policy_snapshots_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY reservation_room_nights
    ADD CONSTRAINT reservation_room_nights_rate_plan_id_fkey FOREIGN KEY (rate_plan_id) REFERENCES rate_plans(id);

ALTER TABLE ONLY reservation_room_nights
    ADD CONSTRAINT reservation_room_nights_reservation_id_fkey FOREIGN KEY (reservation_id) REFERENCES reservations(id);

ALTER TABLE ONLY reservation_room_nights
    ADD CONSTRAINT reservation_room_nights_reservation_room_id_fkey FOREIGN KEY (reservation_room_id) REFERENCES reservation_rooms(id);

ALTER TABLE ONLY reservation_room_nights
    ADD CONSTRAINT reservation_room_nights_room_id_fkey FOREIGN KEY (room_id) REFERENCES rooms(id);

ALTER TABLE ONLY reservation_room_nights
    ADD CONSTRAINT reservation_room_nights_room_type_id_fkey FOREIGN KEY (room_type_id) REFERENCES room_types(id);

ALTER TABLE ONLY reservation_room_nights
    ADD CONSTRAINT reservation_room_nights_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY room_moves
    ADD CONSTRAINT room_moves_approved_by_fkey FOREIGN KEY (approved_by) REFERENCES users(id);

ALTER TABLE ONLY room_moves
    ADD CONSTRAINT room_moves_from_room_id_fkey FOREIGN KEY (from_room_id) REFERENCES rooms(id);

ALTER TABLE ONLY room_moves
    ADD CONSTRAINT room_moves_moved_by_fkey FOREIGN KEY (moved_by) REFERENCES users(id);

ALTER TABLE ONLY room_moves
    ADD CONSTRAINT room_moves_reservation_id_fkey FOREIGN KEY (reservation_id) REFERENCES reservations(id);

ALTER TABLE ONLY room_moves
    ADD CONSTRAINT room_moves_stay_id_fkey FOREIGN KEY (stay_id) REFERENCES stays(id);

ALTER TABLE ONLY room_moves
    ADD CONSTRAINT room_moves_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY room_moves
    ADD CONSTRAINT room_moves_to_room_id_fkey FOREIGN KEY (to_room_id) REFERENCES rooms(id);

ALTER TABLE ONLY sales_activities
    ADD CONSTRAINT sales_activities_assigned_to_fkey FOREIGN KEY (assigned_to) REFERENCES users(id);

ALTER TABLE ONLY sales_activities
    ADD CONSTRAINT sales_activities_company_id_fkey FOREIGN KEY (company_id) REFERENCES companies(id);

ALTER TABLE ONLY sales_activities
    ADD CONSTRAINT sales_activities_guest_id_fkey FOREIGN KEY (guest_id) REFERENCES guests(id);

ALTER TABLE ONLY sales_activities
    ADD CONSTRAINT sales_activities_lead_id_fkey FOREIGN KEY (lead_id) REFERENCES sales_leads(id);

ALTER TABLE ONLY sales_activities
    ADD CONSTRAINT sales_activities_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY sales_leads
    ADD CONSTRAINT sales_leads_assigned_to_fkey FOREIGN KEY (assigned_to) REFERENCES users(id);

ALTER TABLE ONLY sales_leads
    ADD CONSTRAINT sales_leads_company_id_fkey FOREIGN KEY (company_id) REFERENCES companies(id);

ALTER TABLE ONLY sales_leads
    ADD CONSTRAINT sales_leads_guest_id_fkey FOREIGN KEY (guest_id) REFERENCES guests(id);

ALTER TABLE ONLY sales_leads
    ADD CONSTRAINT sales_leads_property_id_fkey FOREIGN KEY (property_id) REFERENCES properties(id);

ALTER TABLE ONLY sales_leads
    ADD CONSTRAINT sales_leads_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY staff_rosters
    ADD CONSTRAINT staff_rosters_created_by_fkey FOREIGN KEY (created_by) REFERENCES users(id);

ALTER TABLE ONLY staff_rosters
    ADD CONSTRAINT staff_rosters_employee_id_fkey FOREIGN KEY (employee_id) REFERENCES employees(id);

ALTER TABLE ONLY staff_rosters
    ADD CONSTRAINT staff_rosters_property_id_fkey FOREIGN KEY (property_id) REFERENCES properties(id);

ALTER TABLE ONLY staff_rosters
    ADD CONSTRAINT staff_rosters_shift_template_id_fkey FOREIGN KEY (shift_template_id) REFERENCES shift_templates(id);

ALTER TABLE ONLY staff_rosters
    ADD CONSTRAINT staff_rosters_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);


ALTER TABLE ONLY feature_flags
    ADD CONSTRAINT fk_feature_flags_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY feature_flags
    ADD CONSTRAINT fk_feature_flags_tenant_property FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY feature_flags
    ADD CONSTRAINT fk_feature_flags_created_by FOREIGN KEY (created_by) REFERENCES platform_users(id) DEFERRABLE;

ALTER TABLE ONLY idempotency_keys
    ADD CONSTRAINT fk_idempotency_keys_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY idempotency_keys
    ADD CONSTRAINT fk_idempotency_keys_property FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY maintenance_windows
    ADD CONSTRAINT fk_maintenance_windows_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY maintenance_windows
    ADD CONSTRAINT fk_maintenance_windows_tenant_property FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY maintenance_windows
    ADD CONSTRAINT fk_maintenance_windows_created_by FOREIGN KEY (created_by) REFERENCES platform_users(id) DEFERRABLE;

ALTER TABLE ONLY module_access_matrix
    ADD CONSTRAINT fk_module_access_matrix_module FOREIGN KEY (module_id) REFERENCES module_catalog(module_id) DEFERRABLE;

ALTER TABLE ONLY module_access_matrix
    ADD CONSTRAINT fk_module_access_matrix_permission_catalog FOREIGN KEY (permission_code) REFERENCES permission_catalog(code) DEFERRABLE;

ALTER TABLE ONLY module_dependencies
    ADD CONSTRAINT fk_module_dependencies_module FOREIGN KEY (module_id) REFERENCES module_catalog(module_id) DEFERRABLE;

ALTER TABLE ONLY module_dependencies
    ADD CONSTRAINT fk_module_dependencies_depends_on FOREIGN KEY (depends_on_module_id) REFERENCES module_catalog(module_id) DEFERRABLE;

ALTER TABLE ONLY business_profile_modules
    ADD CONSTRAINT fk_business_profile_modules_profile FOREIGN KEY (business_profile_id) REFERENCES business_profiles(id) DEFERRABLE;

ALTER TABLE ONLY business_profile_modules
    ADD CONSTRAINT fk_business_profile_modules_module FOREIGN KEY (module_id) REFERENCES module_catalog(module_id) DEFERRABLE;

ALTER TABLE ONLY outbox_events
    ADD CONSTRAINT fk_outbox_events_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY outbox_events
    ADD CONSTRAINT fk_outbox_events_property FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY outbox_events
    ADD CONSTRAINT fk_outbox_events_idempotency_key FOREIGN KEY (idempotency_key_id) REFERENCES idempotency_keys(id) DEFERRABLE;

ALTER TABLE ONLY permissions
    ADD CONSTRAINT fk_permissions_catalog FOREIGN KEY (code) REFERENCES permission_catalog(code) DEFERRABLE;

ALTER TABLE ONLY platform_alerts
    ADD CONSTRAINT fk_platform_alerts_service FOREIGN KEY (service_id) REFERENCES platform_services(id) DEFERRABLE;

ALTER TABLE ONLY platform_alerts
    ADD CONSTRAINT fk_platform_alerts_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY platform_alerts
    ADD CONSTRAINT fk_platform_alerts_acknowledged_by FOREIGN KEY (acknowledged_by) REFERENCES platform_users(id) DEFERRABLE;

ALTER TABLE ONLY platform_audit_logs
    ADD CONSTRAINT fk_platform_audit_logs_user FOREIGN KEY (platform_user_id) REFERENCES platform_users(id) DEFERRABLE;

ALTER TABLE ONLY platform_audit_logs
    ADD CONSTRAINT fk_platform_audit_logs_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY platform_break_glass_access
    ADD CONSTRAINT fk_platform_break_glass_access_user FOREIGN KEY (platform_user_id) REFERENCES platform_users(id) DEFERRABLE;

ALTER TABLE ONLY platform_break_glass_access
    ADD CONSTRAINT fk_platform_break_glass_access_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY platform_break_glass_access
    ADD CONSTRAINT fk_platform_break_glass_access_approved_by FOREIGN KEY (approved_by) REFERENCES platform_users(id) DEFERRABLE;

ALTER TABLE ONLY platform_incidents
    ADD CONSTRAINT fk_platform_incidents_owner FOREIGN KEY (owner_platform_user_id) REFERENCES platform_users(id) DEFERRABLE;

ALTER TABLE ONLY platform_jobs
    ADD CONSTRAINT fk_platform_jobs_service FOREIGN KEY (service_id) REFERENCES platform_services(id) DEFERRABLE;

ALTER TABLE ONLY platform_job_runs
    ADD CONSTRAINT fk_platform_job_runs_job FOREIGN KEY (job_id) REFERENCES platform_jobs(id) DEFERRABLE;

ALTER TABLE ONLY platform_job_runs
    ADD CONSTRAINT fk_platform_job_runs_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY platform_role_permissions
    ADD CONSTRAINT fk_platform_role_permissions_role FOREIGN KEY (platform_role_id) REFERENCES platform_roles(id) DEFERRABLE;

ALTER TABLE ONLY platform_role_permissions
    ADD CONSTRAINT fk_platform_role_permissions_permission FOREIGN KEY (platform_permission_id) REFERENCES platform_permissions(id) DEFERRABLE;

ALTER TABLE ONLY platform_sessions
    ADD CONSTRAINT fk_platform_sessions_user FOREIGN KEY (platform_user_id) REFERENCES platform_users(id) DEFERRABLE;

ALTER TABLE ONLY platform_user_roles
    ADD CONSTRAINT fk_platform_user_roles_user FOREIGN KEY (platform_user_id) REFERENCES platform_users(id) DEFERRABLE;

ALTER TABLE ONLY platform_user_roles
    ADD CONSTRAINT fk_platform_user_roles_role FOREIGN KEY (platform_role_id) REFERENCES platform_roles(id) DEFERRABLE;

ALTER TABLE ONLY platform_user_roles
    ADD CONSTRAINT fk_platform_user_roles_assigned_by FOREIGN KEY (assigned_by) REFERENCES platform_users(id) DEFERRABLE;

ALTER TABLE ONLY plan_entitlements
    ADD CONSTRAINT fk_plan_entitlements_plan FOREIGN KEY (plan_id) REFERENCES plans(id) DEFERRABLE;

ALTER TABLE ONLY property_module_configs
    ADD CONSTRAINT fk_property_module_configs_catalog FOREIGN KEY (module_id) REFERENCES module_catalog(module_id) DEFERRABLE;

ALTER TABLE ONLY property_modules
    ADD CONSTRAINT fk_property_modules_catalog FOREIGN KEY (module_id) REFERENCES module_catalog(module_id) DEFERRABLE;

ALTER TABLE ONLY service_health_checks
    ADD CONSTRAINT fk_service_health_checks_service FOREIGN KEY (service_id) REFERENCES platform_services(id) DEFERRABLE;

ALTER TABLE ONLY support_ticket_notes
    ADD CONSTRAINT fk_support_ticket_notes_ticket FOREIGN KEY (ticket_id) REFERENCES support_tickets(id) DEFERRABLE;

ALTER TABLE ONLY support_ticket_notes
    ADD CONSTRAINT fk_support_ticket_notes_platform_user FOREIGN KEY (platform_user_id) REFERENCES platform_users(id) DEFERRABLE;

ALTER TABLE ONLY support_ticket_notes
    ADD CONSTRAINT fk_support_ticket_notes_tenant_user FOREIGN KEY (tenant_user_id) REFERENCES users(id) DEFERRABLE;

ALTER TABLE ONLY support_tickets
    ADD CONSTRAINT fk_support_tickets_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY support_tickets
    ADD CONSTRAINT fk_support_tickets_property FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY support_tickets
    ADD CONSTRAINT fk_support_tickets_opened_by FOREIGN KEY (opened_by_user_id) REFERENCES users(id) DEFERRABLE;

ALTER TABLE ONLY support_tickets
    ADD CONSTRAINT fk_support_tickets_assigned_platform_user FOREIGN KEY (assigned_platform_user_id) REFERENCES platform_users(id) DEFERRABLE;

ALTER TABLE ONLY tenant_lifecycle_events
    ADD CONSTRAINT fk_tenant_lifecycle_events_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY tenant_lifecycle_events
    ADD CONSTRAINT fk_tenant_lifecycle_events_platform_user FOREIGN KEY (performed_by_platform_user_id) REFERENCES platform_users(id) DEFERRABLE;

ALTER TABLE ONLY tenant_module_configs
    ADD CONSTRAINT fk_tenant_module_configs_catalog FOREIGN KEY (module_id) REFERENCES module_catalog(module_id) DEFERRABLE;

ALTER TABLE ONLY tenant_modules
    ADD CONSTRAINT fk_tenant_modules_catalog FOREIGN KEY (module_id) REFERENCES module_catalog(module_id) DEFERRABLE;

ALTER TABLE ONLY tenant_roles
    ADD CONSTRAINT fk_tenant_roles_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY tenant_role_permissions
    ADD CONSTRAINT fk_tenant_role_permissions_role FOREIGN KEY (tenant_role_id) REFERENCES tenant_roles(id) DEFERRABLE;

ALTER TABLE ONLY tenant_role_permissions
    ADD CONSTRAINT fk_tenant_role_permissions_permission FOREIGN KEY (permission_id) REFERENCES permissions(id) DEFERRABLE;

ALTER TABLE ONLY tenant_usage_snapshots
    ADD CONSTRAINT fk_tenant_usage_snapshots_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY user_tenant_roles
    ADD CONSTRAINT fk_user_tenant_roles_user FOREIGN KEY (user_id) REFERENCES users(id) DEFERRABLE;

ALTER TABLE ONLY user_tenant_roles
    ADD CONSTRAINT fk_user_tenant_roles_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY user_tenant_roles
    ADD CONSTRAINT fk_user_tenant_roles_role FOREIGN KEY (tenant_role_id) REFERENCES tenant_roles(id) DEFERRABLE;

ALTER TABLE ONLY user_tenant_roles
    ADD CONSTRAINT fk_user_tenant_roles_assigned_by FOREIGN KEY (assigned_by) REFERENCES users(id) DEFERRABLE;

ALTER TABLE ONLY workflow_catalog
    ADD CONSTRAINT fk_workflow_catalog_module FOREIGN KEY (module_id) REFERENCES module_catalog(module_id) DEFERRABLE;

ALTER TABLE ONLY workflow_steps
    ADD CONSTRAINT fk_workflow_steps_workflow FOREIGN KEY (workflow_code) REFERENCES workflow_catalog(workflow_code) DEFERRABLE;

ALTER TABLE ONLY workflow_steps
    ADD CONSTRAINT fk_workflow_steps_permission_catalog FOREIGN KEY (permission_code) REFERENCES permission_catalog(code) DEFERRABLE;

-- ================================================================================
-- 23. INDEXES
-- ================================================================================

-- accounting
CREATE UNIQUE INDEX idx_accounting_accounts_tenant_id_id ON accounting_accounts USING btree (tenant_id, id);
CREATE INDEX idx_accounting_accounts_tenant ON accounting_accounts USING btree (tenant_id, account_type) WHERE (is_active = true);
CREATE UNIQUE INDEX idx_journal_entries_tenant_id_id ON journal_entries USING btree (tenant_id, id);
CREATE INDEX idx_journal_entries_source ON journal_entries USING btree (source_type, source_id) WHERE (source_id IS NOT NULL);
CREATE INDEX idx_journal_entries_tenant_date ON journal_entries USING btree (tenant_id, entry_date DESC);
CREATE INDEX idx_journal_entries_tenant_property_date ON journal_entries USING btree (tenant_id, property_id, entry_date DESC) WHERE (property_id IS NOT NULL);
CREATE INDEX idx_journal_entries_tenant_source ON journal_entries USING btree (tenant_id, source_type, source_id) WHERE (source_id IS NOT NULL);
CREATE INDEX idx_journal_entries_tenant_posted_by ON journal_entries USING btree (tenant_id, posted_by) WHERE (posted_by IS NOT NULL);
CREATE INDEX idx_journal_entries_tenant_voided_by ON journal_entries USING btree (tenant_id, voided_by) WHERE (voided_by IS NOT NULL);
CREATE INDEX idx_journal_entry_lines_account ON journal_entry_lines USING btree (account_id);
CREATE INDEX idx_journal_entry_lines_entry ON journal_entry_lines USING btree (journal_entry_id);
CREATE INDEX idx_journal_entry_lines_revenue_center ON journal_entry_lines USING btree (tenant_id, property_id, revenue_center_id) WHERE (revenue_center_id IS NOT NULL);

-- allotments
CREATE INDEX idx_allotments_property ON allotments USING btree (property_id, start_date, end_date) WHERE (is_active = true);

-- approval_request_steps
CREATE INDEX idx_approval_steps_assigned ON approval_request_steps USING btree (assigned_to) WHERE (action IS NULL);
CREATE INDEX idx_approval_steps_request ON approval_request_steps USING btree (request_id);

-- approval_requests
CREATE INDEX idx_approval_requests_entity ON approval_requests USING btree (entity_type, entity_id);
CREATE INDEX idx_approval_requests_pending ON approval_requests USING btree (tenant_id, status) WHERE (status = ANY (ARRAY['pending', 'in_review']));
CREATE INDEX idx_approval_requests_tenant ON approval_requests USING btree (tenant_id, created_at DESC);

-- approval_workflow_steps
CREATE INDEX idx_workflow_steps_workflow ON approval_workflow_steps USING btree (workflow_id);

-- approval_workflows
CREATE INDEX idx_approval_workflows_tenant ON approval_workflows USING btree (tenant_id) WHERE (is_active = true);

-- attendance
CREATE INDEX idx_attendance_employee_id ON attendance USING btree (employee_id);

-- audit_logs
CREATE INDEX idx_audit_logs_tenant_entity ON audit_logs USING btree (tenant_id, entity_type, entity_id);
CREATE INDEX idx_audit_logs_tenant_id ON audit_logs USING btree (tenant_id);

-- availability_calendar
CREATE INDEX idx_avail_cal_date ON availability_calendar USING btree (property_id, stay_date);
CREATE INDEX idx_avail_cal_sellable ON availability_calendar USING btree (property_id, stay_date) WHERE ((stop_sell = false) AND (rooms_available > 0));
CREATE INDEX idx_avail_cal_tenant_property_date ON availability_calendar USING btree (tenant_id, property_id, stay_date);

-- availability_locks
CREATE INDEX idx_avail_locks_expiry ON availability_locks USING btree (expires_at) WHERE (released_at IS NULL);
CREATE INDEX idx_avail_locks_room_date ON availability_locks USING btree (room_type_id, check_in_date, check_out_date) WHERE (released_at IS NULL);
CREATE INDEX idx_avail_locks_tenant_property_room_type_dates ON availability_locks USING btree (tenant_id, property_id, room_type_id, check_in_date, check_out_date, expires_at) WHERE (released_at IS NULL);

-- booking_engine_configs
CREATE INDEX idx_booking_engine_property ON booking_engine_configs USING btree (property_id) WHERE (is_active = true);
CREATE UNIQUE INDEX idx_booking_engine_configs_tenant_id_id ON booking_engine_configs USING btree (tenant_id, id);

-- booking_policies
CREATE UNIQUE INDEX idx_booking_policies_tenant_id_id ON booking_policies USING btree (tenant_id, id);
CREATE INDEX idx_booking_policies_property ON booking_policies USING btree (property_id, policy_type) WHERE (is_active = true);
CREATE INDEX idx_booking_policies_tenant ON booking_policies USING btree (tenant_id, policy_type) WHERE (is_active = true);

-- booking_engine_sites
CREATE INDEX idx_booking_engine_sites_domain ON booking_engine_sites USING btree (custom_domain) WHERE (custom_domain IS NOT NULL);
CREATE INDEX idx_booking_engine_sites_property ON booking_engine_sites USING btree (property_id) WHERE (is_published = true);
CREATE UNIQUE INDEX idx_booking_engine_sites_tenant_slug_active ON booking_engine_sites USING btree (tenant_id, lower((site_slug)::text)) WHERE (is_published = true);
CREATE UNIQUE INDEX idx_booking_engine_sites_tenant_id_id ON booking_engine_sites USING btree (tenant_id, id);

-- property_billing_settings
CREATE INDEX idx_property_billing_settings_tenant ON property_billing_settings USING btree (tenant_id, property_id);

-- booking_payment_attempts
CREATE INDEX idx_booking_payment_attempts_provider_id ON booking_payment_attempts USING btree (provider, provider_payment_id) WHERE (provider_payment_id IS NOT NULL);
CREATE INDEX idx_booking_payment_attempts_session ON booking_payment_attempts USING btree (session_id, created_at DESC);
CREATE INDEX idx_booking_payment_attempts_tenant_status ON booking_payment_attempts USING btree (tenant_id, status, created_at DESC);

-- booking_session_room_nights
CREATE INDEX idx_booking_session_room_nights_session ON booking_session_room_nights USING btree (session_id, stay_date);
CREATE INDEX idx_booking_session_room_nights_session_room ON booking_session_room_nights USING btree (session_room_id, stay_date);

-- booking_session_rooms
CREATE INDEX idx_session_rooms_session ON booking_session_rooms USING btree (session_id);
CREATE UNIQUE INDEX idx_booking_session_rooms_session_id_id ON booking_session_rooms USING btree (session_id, id);
CREATE INDEX idx_booking_session_rooms_tenant_session ON booking_session_rooms USING btree (tenant_id, session_id);
CREATE UNIQUE INDEX idx_booking_session_rooms_tenant_session_id ON booking_session_rooms USING btree (tenant_id, session_id, id);

-- booking_sessions
CREATE INDEX idx_booking_sessions_expiry ON booking_sessions USING btree (expires_at) WHERE (status = 'active');
CREATE INDEX idx_booking_sessions_tenant ON booking_sessions USING btree (tenant_id, created_at DESC);
CREATE UNIQUE INDEX idx_booking_sessions_tenant_id_id ON booking_sessions USING btree (tenant_id, id);
CREATE INDEX idx_booking_sessions_token ON booking_sessions USING btree (session_token) WHERE (status = 'active');

-- buildings
CREATE INDEX idx_buildings_property_id ON buildings USING btree (property_id);

-- cash_float_movements
CREATE INDEX idx_cash_float_session ON cash_float_movements USING btree (session_id);
CREATE INDEX idx_cash_float_tenant ON cash_float_movements USING btree (tenant_id, created_at);

-- channel_connections
CREATE INDEX idx_channel_connections_property ON channel_connections USING btree (property_id) WHERE ((deleted_at IS NULL) AND (is_active = true));
CREATE UNIQUE INDEX idx_channel_connections_provider_active ON channel_connections USING btree (tenant_id, property_id, provider) WHERE (deleted_at IS NULL);
CREATE INDEX idx_channel_connections_tenant ON channel_connections USING btree (tenant_id);
CREATE UNIQUE INDEX idx_channel_connections_tenant_id_id ON channel_connections USING btree (tenant_id, id);

-- channels
CREATE INDEX idx_channels_tenant ON channels USING btree (tenant_id) WHERE (deleted_at IS NULL);
CREATE UNIQUE INDEX idx_channels_tenant_id_id ON channels USING btree (tenant_id, id);

-- companies
CREATE INDEX idx_companies_tenant_id ON companies USING btree (tenant_id);
CREATE UNIQUE INDEX idx_companies_tenant_id_id ON companies USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_companies_tenant_name ON companies USING btree (tenant_id, name);

-- corporate AR
CREATE INDEX idx_ar_allocations_account ON ar_allocations USING btree (corporate_account_id, allocated_at DESC);
CREATE INDEX idx_ar_allocations_tenant_account ON ar_allocations USING btree (tenant_id, corporate_account_id, allocated_at DESC);
CREATE INDEX idx_ar_allocations_tenant_invoice ON ar_allocations USING btree (tenant_id, invoice_id) WHERE (invoice_id IS NOT NULL);
CREATE INDEX idx_ar_allocations_tenant_credit_note ON ar_allocations USING btree (tenant_id, credit_note_id) WHERE (credit_note_id IS NOT NULL);
CREATE INDEX idx_ar_allocations_tenant_folio_payment ON ar_allocations USING btree (tenant_id, folio_payment_id) WHERE (folio_payment_id IS NOT NULL);
CREATE INDEX idx_ar_allocations_account_created ON ar_allocations USING btree (tenant_id, corporate_account_id, created_at DESC);
CREATE INDEX idx_corporate_account_contacts_account ON corporate_account_contacts USING btree (corporate_account_id, role);
CREATE UNIQUE INDEX idx_corporate_account_contacts_tenant_id_id ON corporate_account_contacts USING btree (tenant_id, id);
CREATE INDEX idx_corporate_account_holds_active ON corporate_account_holds USING btree (corporate_account_id, starts_at DESC) WHERE ((status)::text = 'active'::text);
CREATE INDEX idx_corporate_account_limits_account ON corporate_account_limits USING btree (corporate_account_id, effective_from DESC);
CREATE INDEX idx_corporate_accounts_company ON corporate_accounts USING btree (company_id) WHERE (deleted_at IS NULL);
CREATE INDEX idx_corporate_accounts_status ON corporate_accounts USING btree (tenant_id, credit_status) WHERE (is_active = true);
CREATE UNIQUE INDEX idx_corporate_accounts_tenant_id_id ON corporate_accounts USING btree (tenant_id, id);
CREATE INDEX idx_corporate_statement_items_statement ON corporate_statement_items USING btree (statement_id, item_date);
CREATE INDEX idx_corporate_statement_items_statement_date ON corporate_statement_items USING btree (tenant_id, statement_id, item_date);
CREATE INDEX idx_corporate_statements_account_period ON corporate_statements USING btree (corporate_account_id, period_start, period_end);
CREATE UNIQUE INDEX idx_corporate_statements_tenant_id_id ON corporate_statements USING btree (tenant_id, id);
CREATE INDEX idx_credit_notes_account_status ON credit_notes USING btree (corporate_account_id, status);
CREATE INDEX idx_credit_notes_tenant_account_status ON credit_notes USING btree (tenant_id, corporate_account_id, status);
CREATE INDEX idx_credit_notes_tenant_invoice ON credit_notes USING btree (tenant_id, invoice_id) WHERE (invoice_id IS NOT NULL);
CREATE INDEX idx_credit_notes_tenant_company ON credit_notes USING btree (tenant_id, company_id) WHERE (company_id IS NOT NULL);
CREATE UNIQUE INDEX idx_credit_notes_tenant_id_id ON credit_notes USING btree (tenant_id, id);

-- currency_rates
CREATE INDEX idx_currency_rates_tenant ON currency_rates USING btree (tenant_id, rate_date DESC);

-- corporate_rate_agreements
CREATE INDEX idx_corporate_rate_agreements_company ON corporate_rate_agreements USING btree (company_id, status);
CREATE INDEX idx_corporate_rate_agreements_property ON corporate_rate_agreements USING btree (property_id, valid_from, valid_to);

-- departments
CREATE INDEX idx_departments_tenant_id ON departments USING btree (tenant_id);
CREATE UNIQUE INDEX idx_departments_tenant_property_name ON departments USING btree (tenant_id, property_id, name);
CREATE UNIQUE INDEX idx_departments_tenant_id_id ON departments USING btree (tenant_id, id);

-- document_sequences
CREATE UNIQUE INDEX idx_doc_sequences_tenant_type_year ON document_sequences USING btree (tenant_id, document_type, year);

-- edge_sync_events
CREATE INDEX idx_edge_sync_originated_at ON edge_sync_events USING btree (originated_at);
CREATE INDEX idx_edge_sync_tenant_status ON edge_sync_events USING btree (tenant_id, status, received_at);

-- employees
CREATE INDEX idx_employees_active ON employees USING btree (tenant_id, employment_type) WHERE (deleted_at IS NULL);
CREATE INDEX idx_employees_tenant_id ON employees USING btree (tenant_id);
CREATE UNIQUE INDEX idx_employees_tenant_id_id ON employees USING btree (tenant_id, id);

-- event_booking_items
CREATE INDEX idx_event_items_booking ON event_booking_items USING btree (booking_id);

-- event_bookings
CREATE INDEX idx_event_bookings_date ON event_bookings USING btree (property_id, event_date) WHERE (deleted_at IS NULL);
CREATE INDEX idx_event_bookings_space ON event_bookings USING btree (event_space_id, event_date) WHERE (status <> ALL (ARRAY['cancelled', 'no_show']));
CREATE INDEX idx_event_bookings_status ON event_bookings USING btree (tenant_id, status) WHERE (deleted_at IS NULL);

-- event_packages
CREATE INDEX idx_event_packages_tenant ON event_packages USING btree (tenant_id) WHERE (is_active = true);
CREATE UNIQUE INDEX idx_event_packages_tenant_id_id ON event_packages USING btree (tenant_id, id);

-- event_spaces
CREATE INDEX idx_event_spaces_property ON event_spaces USING btree (property_id) WHERE ((deleted_at IS NULL) AND (is_active = true));
CREATE UNIQUE INDEX idx_event_spaces_tenant_id_id ON event_spaces USING btree (tenant_id, id);

-- events
CREATE INDEX idx_events_tenant_type ON events USING btree (tenant_id, event_type);

-- fiscal_receipts
CREATE INDEX idx_fiscal_receipts_invoice_id ON fiscal_receipts USING btree (invoice_id);
CREATE UNIQUE INDEX idx_fiscal_receipts_tenant_id_id ON fiscal_receipts USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_fiscal_receipts_tenant_number ON fiscal_receipts USING btree (tenant_id, receipt_number);
CREATE INDEX idx_fiscal_receipts_tenant_receipt ON fiscal_receipts USING btree (tenant_id, receipt_number);
CREATE INDEX idx_fiscal_document_mappings_invoice ON fiscal_document_mappings USING btree (invoice_id) WHERE (invoice_id IS NOT NULL);
CREATE INDEX idx_fiscal_provider_configs_property ON fiscal_provider_configs USING btree (tenant_id, property_id, provider_id) WHERE (is_active = true);
CREATE UNIQUE INDEX idx_fiscal_provider_configs_tenant_id_id ON fiscal_provider_configs USING btree (tenant_id, id);
CREATE INDEX idx_fiscal_providers_country ON fiscal_providers USING btree (country_code, fiscal_mode) WHERE (is_active = true);
CREATE INDEX idx_fiscal_submission_attempts_receipt ON fiscal_submission_attempts USING btree (fiscal_receipt_id, attempted_at DESC) WHERE (fiscal_receipt_id IS NOT NULL);
CREATE INDEX idx_fiscal_submission_attempts_tenant_batch ON fiscal_submission_attempts USING btree (tenant_id, batch_id, attempt_no) WHERE (batch_id IS NOT NULL);
CREATE INDEX idx_fiscal_submission_attempts_tenant_provider ON fiscal_submission_attempts USING btree (tenant_id, provider_config_id, status, attempted_at DESC);
CREATE INDEX idx_fiscal_submission_attempts_tenant_receipt ON fiscal_submission_attempts USING btree (tenant_id, fiscal_receipt_id, attempted_at DESC) WHERE (fiscal_receipt_id IS NOT NULL);
CREATE INDEX idx_fiscal_submission_batches_status ON fiscal_submission_batches USING btree (tenant_id, status, submitted_at DESC);
CREATE UNIQUE INDEX idx_fiscal_submission_batches_tenant_id_id ON fiscal_submission_batches USING btree (tenant_id, id);
CREATE INDEX idx_tax_jurisdictions_country ON tax_jurisdictions USING btree (country_code, region_code) WHERE (is_active = true);
CREATE INDEX idx_tax_report_lines_run ON tax_report_lines USING btree (report_run_id);
CREATE INDEX idx_tax_report_runs_period ON tax_report_runs USING btree (tenant_id, report_type, period_start, period_end);
CREATE UNIQUE INDEX idx_tax_report_runs_tenant_id_id ON tax_report_runs USING btree (tenant_id, id);

-- floors
CREATE INDEX idx_floors_building_id ON floors USING btree (building_id);

-- folio_charges
CREATE INDEX idx_folio_charges_folio_active ON folio_charges USING btree (folio_id, charge_type) WHERE ((deleted_at IS NULL) AND (is_reversed = false));
CREATE INDEX idx_folio_charges_folio_id ON folio_charges USING btree (folio_id);
CREATE UNIQUE INDEX idx_folio_charges_tenant_id_id ON folio_charges USING btree (tenant_id, id);
CREATE INDEX idx_folio_charges_posted_at ON folio_charges USING btree (posted_at);
CREATE INDEX idx_folio_charges_tenant_folio_posted ON folio_charges USING btree (tenant_id, folio_id, posted_at DESC) WHERE ((deleted_at IS NULL) AND (is_reversed = false));
CREATE INDEX idx_folio_charges_revenue_center ON folio_charges USING btree (tenant_id, property_id, revenue_center_id, posted_at DESC) WHERE ((deleted_at IS NULL) AND (revenue_center_id IS NOT NULL));
CREATE INDEX idx_folio_charges_source_id ON folio_charges USING btree (source_id);
CREATE INDEX idx_folio_charges_source_type ON folio_charges USING btree (source_type);
CREATE INDEX idx_folio_charges_status ON folio_charges USING btree (status);

-- folio_charge_taxes
CREATE INDEX idx_folio_charge_taxes_charge ON folio_charge_taxes USING btree (folio_charge_id);

-- folio_payments
CREATE INDEX idx_folio_payments_folio_active ON folio_payments USING btree (folio_id) WHERE ((deleted_at IS NULL) AND (is_reversed = false));
CREATE INDEX idx_folio_payments_folio_id ON folio_payments USING btree (folio_id);
CREATE INDEX idx_folio_payments_tenant_folio_paid ON folio_payments USING btree (tenant_id, folio_id, paid_at DESC) WHERE ((deleted_at IS NULL) AND (is_reversed = false));
CREATE INDEX idx_folio_payments_status ON folio_payments USING btree (status);
CREATE INDEX idx_folio_payments_tenant_id ON folio_payments USING btree (tenant_id);
CREATE UNIQUE INDEX idx_folio_payments_tenant_id_id ON folio_payments USING btree (tenant_id, id);

-- folios
CREATE INDEX idx_folios_property_open ON folios USING btree (property_id, opened_at) WHERE ((status = 'open') AND (deleted_at IS NULL));
CREATE INDEX idx_folios_reservation_id ON folios USING btree (reservation_id);
CREATE INDEX idx_folios_status ON folios USING btree (status);
CREATE INDEX idx_folios_tenant_id ON folios USING btree (tenant_id);
CREATE UNIQUE INDEX idx_folios_tenant_id_id ON folios USING btree (tenant_id, id);
CREATE INDEX idx_folios_tenant_status ON folios USING btree (tenant_id, status);

-- groups
CREATE INDEX idx_groups_property ON groups USING btree (property_id, arrival_date);
CREATE INDEX idx_groups_tenant ON groups USING btree (tenant_id, status) WHERE (deleted_at IS NULL);
CREATE UNIQUE INDEX idx_groups_tenant_id_id ON groups USING btree (tenant_id, id);

-- guest_contacts
CREATE INDEX idx_guest_contacts_guest_id ON guest_contacts USING btree (guest_id);

-- guest_documents
CREATE INDEX idx_guest_documents_guest_id ON guest_documents USING btree (guest_id);

-- guest_feedback
CREATE INDEX idx_guest_feedback_guest ON guest_feedback USING btree (guest_id);
CREATE INDEX idx_guest_feedback_property ON guest_feedback USING btree (property_id, submitted_at DESC);

-- guests
CREATE INDEX idx_guests_active ON guests USING btree (tenant_id, full_name) WHERE (deleted_at IS NULL);
CREATE INDEX idx_guests_email ON guests USING btree (tenant_id, email) WHERE (deleted_at IS NULL);
CREATE UNIQUE INDEX idx_guests_tenant_email_active ON guests USING btree (tenant_id, lower((email)::text)) WHERE ((email IS NOT NULL) AND (deleted_at IS NULL));
CREATE INDEX idx_guests_full_name ON guests USING btree (full_name);
CREATE INDEX idx_guests_phone_primary ON guests USING btree (tenant_id, phone_primary) WHERE (deleted_at IS NULL);
CREATE INDEX idx_guests_tenant_id ON guests USING btree (tenant_id);
CREATE UNIQUE INDEX idx_guests_tenant_id_id ON guests USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_guests_tenant_number ON guests USING btree (tenant_id, guest_number);

-- housekeeping_assignments
CREATE UNIQUE INDEX idx_hk_assignments_task_user ON housekeeping_assignments USING btree (task_id, user_id);

-- housekeeping_tasks
CREATE INDEX idx_housekeeping_tasks_date_status ON housekeeping_tasks USING btree (tenant_id, scheduled_date, status);
CREATE INDEX idx_housekeeping_tasks_room_id ON housekeeping_tasks USING btree (room_id);
CREATE INDEX idx_housekeeping_tasks_scheduled_date ON housekeeping_tasks USING btree (scheduled_date);
CREATE INDEX idx_housekeeping_tasks_status ON housekeeping_tasks USING btree (status);
CREATE INDEX idx_housekeeping_tasks_tenant_status ON housekeeping_tasks USING btree (tenant_id, status);

-- inventory_items
CREATE INDEX idx_inventory_items_tenant_id ON inventory_items USING btree (tenant_id);
CREATE UNIQUE INDEX idx_inventory_items_tenant_name ON inventory_items USING btree (tenant_id, name);

-- inventory_locations
CREATE INDEX idx_inventory_locations_tenant_property ON inventory_locations USING btree (tenant_id, property_id);

-- labor / scheduling
CREATE INDEX idx_labor_forecasts_property_date ON labor_forecasts USING btree (property_id, forecast_date);
CREATE INDEX idx_leave_requests_employee_status ON leave_requests USING btree (employee_id, status, start_date);
CREATE INDEX idx_staff_rosters_property_date ON staff_rosters USING btree (property_id, roster_date);
CREATE INDEX idx_staff_rosters_employee_date ON staff_rosters USING btree (employee_id, roster_date);

-- invoice_items
CREATE INDEX idx_invoice_items_invoice_id ON invoice_items USING btree (invoice_id);
CREATE INDEX idx_invoice_items_revenue_center ON invoice_items USING btree (tenant_id, property_id, revenue_center_id) WHERE (revenue_center_id IS NOT NULL);
CREATE UNIQUE INDEX idx_invoice_items_tenant_id_id ON invoice_items USING btree (tenant_id, id);

-- invoice_item_taxes
CREATE INDEX idx_invoice_item_taxes_item ON invoice_item_taxes USING btree (invoice_item_id);

-- invoices
CREATE INDEX idx_invoices_folio_id ON invoices USING btree (folio_id);
CREATE INDEX idx_invoices_tenant_id ON invoices USING btree (tenant_id);
CREATE INDEX idx_invoices_tenant_folio ON invoices USING btree (tenant_id, folio_id);
CREATE INDEX idx_invoices_tenant_company ON invoices USING btree (tenant_id, company_id, status) WHERE (company_id IS NOT NULL);
CREATE INDEX idx_invoices_tenant_property_status ON invoices USING btree (tenant_id, property_id, status, issued_at DESC) WHERE (property_id IS NOT NULL);
CREATE UNIQUE INDEX idx_invoices_tenant_id_id ON invoices USING btree (tenant_id, id);

-- kitchen_tickets
CREATE INDEX idx_kitchen_tickets_order_id ON kitchen_tickets USING btree (order_id);
CREATE INDEX idx_kitchen_tickets_status ON kitchen_tickets USING btree (status);

-- lost_and_found
CREATE INDEX idx_lost_found_property_id ON lost_and_found USING btree (property_id);
CREATE INDEX idx_lost_found_status ON lost_and_found USING btree (status);

-- loyalty_accounts
CREATE UNIQUE INDEX idx_loyalty_accounts_tenant_card ON loyalty_accounts USING btree (tenant_id, card_number);

-- loyalty_transactions
CREATE INDEX idx_loyalty_tx_account_id ON loyalty_transactions USING btree (account_id);

-- maintenance_requests
CREATE INDEX idx_maintenance_room_id ON maintenance_requests USING btree (room_id);
CREATE INDEX idx_maintenance_status ON maintenance_requests USING btree (status);

-- module_access_matrix
CREATE INDEX idx_module_access_matrix_module ON module_access_matrix USING btree (module_id, access_scope);
CREATE INDEX idx_module_access_matrix_permission ON module_access_matrix USING btree (permission_code);
CREATE INDEX idx_module_access_matrix_route_contract ON module_access_matrix USING btree (route_scope, guard_mode, http_method);
CREATE UNIQUE INDEX idx_module_access_matrix_public_unique ON module_access_matrix USING btree (module_id, screen_key, http_method, api_pattern) WHERE (permission_code IS NULL);

-- business_profiles
CREATE INDEX idx_business_profiles_active ON business_profiles USING btree (display_order, code) WHERE (is_active = true);
CREATE INDEX idx_business_profile_modules_module ON business_profile_modules USING btree (module_id, display_order);

-- tenant onboarding, contacts, verification, and report delivery
CREATE INDEX idx_tenant_profiles_verification ON tenant_profiles USING btree (verification_status, verification_level, verification_expires_at);
CREATE INDEX idx_contact_role_catalog_active ON contact_role_catalog USING btree (display_order, role_code) WHERE (is_active = true);
CREATE INDEX idx_tenant_contacts_tenant_status ON tenant_contacts USING btree (tenant_id, status) WHERE (deleted_at IS NULL);
CREATE UNIQUE INDEX idx_tenant_contacts_primary_active ON tenant_contacts USING btree (tenant_id) WHERE ((is_primary_contact = true) AND (deleted_at IS NULL) AND ((status)::text = 'active'::text));
CREATE INDEX idx_tenant_contacts_user ON tenant_contacts USING btree (tenant_id, user_id) WHERE (user_id IS NOT NULL);
CREATE INDEX idx_tenant_contact_roles_contact ON tenant_contact_roles USING btree (tenant_id, contact_id, role_code);
CREATE INDEX idx_tenant_contact_roles_property ON tenant_contact_roles USING btree (tenant_id, property_id, role_code) WHERE (property_id IS NOT NULL);
CREATE UNIQUE INDEX idx_tenant_contact_roles_primary_active ON tenant_contact_roles USING btree (tenant_id, role_code, (COALESCE(property_id, '00000000-0000-0000-0000-000000000000'::uuid))) WHERE ((is_primary_for_role = true) AND (effective_to IS NULL));
CREATE INDEX idx_contact_channels_contact ON contact_channels USING btree (tenant_id, contact_id, channel_type) WHERE (deleted_at IS NULL);
CREATE UNIQUE INDEX idx_contact_channels_active_endpoint ON contact_channels USING btree (tenant_id, contact_id, channel_type, normalized_address) WHERE ((deleted_at IS NULL) AND (is_active = true));
CREATE UNIQUE INDEX idx_contact_channels_primary_active ON contact_channels USING btree (tenant_id, contact_id, channel_type) WHERE ((is_primary = true) AND (deleted_at IS NULL) AND (is_active = true));
CREATE INDEX idx_communication_consents_latest ON communication_consents USING btree (tenant_id, contact_id, contact_channel_id, purpose, captured_at DESC, created_at DESC);
CREATE INDEX idx_tenant_verification_cases_status ON tenant_verification_cases USING btree (tenant_id, status, created_at DESC);
CREATE INDEX idx_tenant_verification_cases_platform_queue ON tenant_verification_cases USING btree (status, risk_rating, submitted_at) WHERE ((status)::text = ANY ((ARRAY['submitted', 'under_review', 'needs_information'])::text[]));
CREATE INDEX idx_tenant_verification_documents_case ON tenant_verification_documents USING btree (tenant_id, verification_case_id, status);
CREATE INDEX idx_report_catalog_active ON report_catalog USING btree (module_id, display_order, report_code) WHERE (is_active = true);
CREATE INDEX idx_report_subscriptions_schedule ON report_subscriptions USING btree (tenant_id, status, next_run_at) WHERE ((deleted_at IS NULL) AND ((status)::text = 'active'::text));
CREATE INDEX idx_report_subscriptions_property ON report_subscriptions USING btree (tenant_id, property_id, report_code) WHERE ((property_id IS NOT NULL) AND (deleted_at IS NULL));
CREATE UNIQUE INDEX idx_report_subscriptions_active_unique ON report_subscriptions USING btree (tenant_id, (COALESCE(property_id, '00000000-0000-0000-0000-000000000000'::uuid)), report_code, lower(subscription_name)) WHERE ((deleted_at IS NULL) AND ((status)::text = 'active'::text));
CREATE INDEX idx_report_subscription_recipients_subscription ON report_subscription_recipients USING btree (tenant_id, subscription_id) WHERE (is_enabled = true);
CREATE INDEX idx_report_runs_subscription ON report_runs USING btree (tenant_id, subscription_id, business_date DESC) WHERE (subscription_id IS NOT NULL);
CREATE INDEX idx_report_runs_report_period ON report_runs USING btree (tenant_id, report_code, period_start, period_end);
CREATE INDEX idx_report_runs_property_date ON report_runs USING btree (tenant_id, property_id, business_date DESC) WHERE (property_id IS NOT NULL);
CREATE INDEX idx_report_deliveries_run ON report_deliveries USING btree (tenant_id, report_run_id, status);
CREATE INDEX idx_report_deliveries_dispatch ON report_deliveries USING btree (status, next_attempt_at, attempt_count, created_at) WHERE ((status)::text = ANY ((ARRAY['queued', 'failed', 'retry_scheduled'])::text[]));
CREATE INDEX idx_report_deliveries_provider_message ON report_deliveries USING btree (provider_code, provider_message_id) WHERE (provider_message_id IS NOT NULL);

-- menu_categories
CREATE UNIQUE INDEX idx_menu_categories_outlet_name ON menu_categories USING btree (outlet_id, name);
CREATE UNIQUE INDEX idx_menu_categories_tenant_id_id ON menu_categories USING btree (tenant_id, id);

-- menu_item_recipes
CREATE INDEX idx_menu_item_recipes_menu_item_id ON menu_item_recipes USING btree (menu_item_id);
CREATE UNIQUE INDEX idx_menu_item_recipes_unique ON menu_item_recipes USING btree (menu_item_id, inventory_item_id);

-- menu_items
CREATE INDEX idx_menu_items_active ON menu_items USING btree (category_id, price) WHERE ((deleted_at IS NULL) AND (is_available = true));
CREATE UNIQUE INDEX idx_menu_items_category_name ON menu_items USING btree (category_id, name);
CREATE UNIQUE INDEX idx_menu_items_tenant_id_id ON menu_items USING btree (tenant_id, id);

-- night_audit_runs
CREATE UNIQUE INDEX idx_night_audit_property_date ON night_audit_runs USING btree (property_id, audit_date);
CREATE INDEX idx_night_audit_tenant_date ON night_audit_runs USING btree (tenant_id, audit_date);

-- notifications
CREATE INDEX idx_notifications_entity ON notifications USING btree (entity_type, entity_id) WHERE (entity_id IS NOT NULL);
CREATE INDEX idx_notifications_tenant ON notifications USING btree (tenant_id, created_at DESC);
CREATE INDEX idx_notifications_user ON notifications USING btree (user_id, created_at DESC) WHERE (read_at IS NULL);

-- ota_room_type_mappings
CREATE INDEX idx_ota_mappings_connection ON ota_room_type_mappings USING btree (channel_connection_id) WHERE (is_active = true);
CREATE INDEX idx_ota_mappings_room_type ON ota_room_type_mappings USING btree (room_type_id);
CREATE INDEX idx_ota_mappings_tenant_connection ON ota_room_type_mappings USING btree (tenant_id, channel_connection_id) WHERE (is_active = true);
CREATE INDEX idx_ota_mappings_tenant_room_type ON ota_room_type_mappings USING btree (tenant_id, room_type_id);

-- outlets
CREATE INDEX idx_outlets_property_id ON outlets USING btree (property_id);
CREATE INDEX idx_outlets_revenue_center ON outlets USING btree (tenant_id, property_id, revenue_center_id) WHERE (revenue_center_id IS NOT NULL);
CREATE UNIQUE INDEX idx_outlets_tenant_property_name ON outlets USING btree (tenant_id, property_id, name);
CREATE UNIQUE INDEX idx_outlets_tenant_id_id ON outlets USING btree (tenant_id, id);

-- revenue_centers
CREATE INDEX idx_revenue_centers_active ON revenue_centers USING btree (tenant_id, property_id, display_order, name) WHERE ((deleted_at IS NULL) AND (is_active = true));
CREATE INDEX idx_revenue_centers_type ON revenue_centers USING btree (tenant_id, property_id, center_type) WHERE (deleted_at IS NULL);

-- property_* settings and module configuration
CREATE INDEX idx_property_frontdesk_settings_tenant ON property_frontdesk_settings USING btree (tenant_id, property_id);
CREATE INDEX idx_property_housekeeping_settings_tenant ON property_housekeeping_settings USING btree (tenant_id, property_id);
CREATE INDEX idx_property_module_configs_property ON property_module_configs USING btree (property_id, module_id);
CREATE INDEX idx_property_modules_property ON property_modules USING btree (property_id, module_id);
CREATE INDEX idx_property_pos_settings_tenant ON property_pos_settings USING btree (tenant_id, property_id);
CREATE INDEX idx_property_reservation_settings_tenant ON property_reservation_settings USING btree (tenant_id, property_id);

-- payroll_records
CREATE INDEX idx_payroll_records_employee_id ON payroll_records USING btree (employee_id);
CREATE UNIQUE INDEX idx_payroll_records_run_employee ON payroll_records USING btree (payroll_run_id, employee_id);
CREATE UNIQUE INDEX idx_payroll_records_tenant_id_id ON payroll_records USING btree (tenant_id, id);

-- payroll_runs
CREATE INDEX idx_payroll_runs_tenant_id ON payroll_runs USING btree (tenant_id);
CREATE UNIQUE INDEX idx_payroll_runs_tenant_period ON payroll_runs USING btree (tenant_id, period_start, period_end);

-- permissions
-- payment reconciliation
CREATE INDEX idx_mobile_money_disbursements_status ON mobile_money_disbursements USING btree (tenant_id, status, created_at DESC);
CREATE INDEX idx_mobile_money_disbursements_provider_status ON mobile_money_disbursements USING btree (tenant_id, provider_account_id, status, created_at DESC);
CREATE INDEX idx_mobile_money_disbursements_transaction ON mobile_money_disbursements USING btree (tenant_id, payment_transaction_id) WHERE (payment_transaction_id IS NOT NULL);
CREATE INDEX idx_mobile_money_disbursements_supplier ON mobile_money_disbursements USING btree (tenant_id, supplier_id) WHERE (supplier_id IS NOT NULL);
CREATE INDEX idx_mobile_money_disbursements_payroll_record ON mobile_money_disbursements USING btree (tenant_id, payroll_record_id) WHERE (payroll_record_id IS NOT NULL);
CREATE INDEX idx_payment_provider_accounts_provider ON payment_provider_accounts USING btree (provider_id) WHERE (is_active = true);
CREATE UNIQUE INDEX idx_payment_provider_accounts_tenant_id_id ON payment_provider_accounts USING btree (tenant_id, id);
CREATE INDEX idx_payment_providers_tenant_type ON payment_providers USING btree (tenant_id, provider_type) WHERE (is_active = true);
CREATE UNIQUE INDEX idx_payment_providers_tenant_id_id ON payment_providers USING btree (tenant_id, id);
CREATE INDEX idx_payment_reconciliation_items_reconciliation ON payment_reconciliation_items USING btree (reconciliation_id, match_status);
CREATE INDEX idx_payment_reconciliations_provider_date ON payment_reconciliations USING btree (provider_account_id, reconciliation_date DESC);
CREATE UNIQUE INDEX idx_payment_reconciliations_tenant_id_id ON payment_reconciliations USING btree (tenant_id, id);
CREATE INDEX idx_payment_transactions_provider_ref ON payment_transactions USING btree (provider_account_id, provider_reference) WHERE (provider_reference IS NOT NULL);
CREATE INDEX idx_payment_transactions_status ON payment_transactions USING btree (tenant_id, status, initiated_at DESC);
CREATE INDEX idx_payment_transactions_provider_status ON payment_transactions USING btree (tenant_id, provider_account_id, status, initiated_at DESC) WHERE (provider_account_id IS NOT NULL);
CREATE INDEX idx_payment_transactions_tenant_folio_payment ON payment_transactions USING btree (tenant_id, folio_payment_id) WHERE (folio_payment_id IS NOT NULL);
CREATE INDEX idx_payment_transactions_tenant_reservation_deposit ON payment_transactions USING btree (tenant_id, reservation_deposit_id) WHERE (reservation_deposit_id IS NOT NULL);
CREATE UNIQUE INDEX idx_payment_transactions_tenant_id_id ON payment_transactions USING btree (tenant_id, id);
CREATE INDEX idx_payment_webhook_events_status ON payment_webhook_events USING btree (tenant_id, status, received_at DESC);
CREATE UNIQUE INDEX idx_payment_webhook_events_tenant_id_id ON payment_webhook_events USING btree (tenant_id, id);

-- pos_order_items
CREATE INDEX idx_pos_order_items_order_id ON pos_order_items USING btree (order_id);

-- pos_orders
CREATE INDEX idx_pos_orders_outlet_id ON pos_orders USING btree (outlet_id);
CREATE INDEX idx_pos_orders_revenue_center ON pos_orders USING btree (tenant_id, property_id, revenue_center_id) WHERE (revenue_center_id IS NOT NULL);
CREATE INDEX idx_pos_orders_session ON pos_orders USING btree (session_id) WHERE (deleted_at IS NULL);
CREATE INDEX idx_pos_orders_session_id ON pos_orders USING btree (session_id);
CREATE INDEX idx_pos_orders_status ON pos_orders USING btree (status);
CREATE INDEX idx_pos_orders_tenant_id ON pos_orders USING btree (tenant_id);
CREATE UNIQUE INDEX idx_pos_orders_tenant_id_id ON pos_orders USING btree (tenant_id, id);

-- pos_terminals / pos_printer_routes
CREATE INDEX idx_pos_printer_routes_outlet ON pos_printer_routes USING btree (outlet_id) WHERE (is_active = true);
CREATE INDEX idx_pos_terminals_outlet ON pos_terminals USING btree (outlet_id, status);
CREATE INDEX idx_pos_terminals_tenant ON pos_terminals USING btree (tenant_id, status);

-- pos_sessions
CREATE UNIQUE INDEX idx_pos_sessions_tenant_id_id ON pos_sessions USING btree (tenant_id, id);

-- promo_codes
CREATE INDEX idx_promo_codes_tenant ON promo_codes USING btree (tenant_id, code) WHERE ((is_active = true) AND (deleted_at IS NULL));
CREATE UNIQUE INDEX idx_promo_codes_tenant_code_active ON promo_codes USING btree (tenant_id, lower(code)) WHERE (deleted_at IS NULL);
CREATE INDEX idx_promo_codes_valid ON promo_codes USING btree (valid_from, valid_to) WHERE (is_active = true);

-- properties
CREATE INDEX idx_properties_name ON properties USING btree (name);
CREATE UNIQUE INDEX idx_properties_tenant_code ON properties USING btree (tenant_id, code) WHERE ((code IS NOT NULL) AND (deleted_at IS NULL));
CREATE INDEX idx_properties_tenant_id ON properties USING btree (tenant_id);
-- purchase_order_items
CREATE UNIQUE INDEX idx_po_items_order_item ON purchase_order_items USING btree (purchase_order_id, inventory_item_id);

-- purchase_orders
CREATE INDEX idx_po_approval ON purchase_orders USING btree (approval_request_id) WHERE (approval_request_id IS NOT NULL);
CREATE INDEX idx_purchase_orders_supplier_id ON purchase_orders USING btree (supplier_id);
CREATE INDEX idx_purchase_orders_tenant_id ON purchase_orders USING btree (tenant_id);

-- rate_plan_prices
CREATE UNIQUE INDEX idx_rate_plan_prices_unique ON rate_plan_prices USING btree (rate_plan_id, room_type_id, date, occupancy);

-- rate_plans
CREATE UNIQUE INDEX idx_rate_plans_tenant_property_name ON rate_plans USING btree (tenant_id, property_id, name);
CREATE UNIQUE INDEX idx_rate_plans_tenant_id_id ON rate_plans USING btree (tenant_id, id);

-- rate_restrictions
CREATE INDEX idx_rate_restrictions_plan ON rate_restrictions USING btree (rate_plan_id) WHERE (is_active = true);

-- refresh_tokens
CREATE INDEX idx_refresh_tokens_expires_at ON refresh_tokens USING btree (expires_at);
CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens USING btree (user_id);

-- reservation_guests
CREATE UNIQUE INDEX idx_reservation_guests_unique ON reservation_guests USING btree (reservation_id, guest_id);

-- reservation_notes
CREATE INDEX idx_reservation_notes_reservation_id ON reservation_notes USING btree (reservation_id);
CREATE INDEX idx_reservation_notes_type ON reservation_notes USING btree (reservation_id, note_type);

-- reservation policies, deposits, and nightly rates
CREATE UNIQUE INDEX idx_reservation_policy_snapshots_tenant_id_id ON reservation_policy_snapshots USING btree (tenant_id, id);
CREATE INDEX idx_reservation_deposits_reservation ON reservation_deposits USING btree (reservation_id, status);
CREATE INDEX idx_reservation_deposits_tenant_reservation_status ON reservation_deposits USING btree (tenant_id, reservation_id, status);
CREATE UNIQUE INDEX idx_reservation_deposits_tenant_id_id ON reservation_deposits USING btree (tenant_id, id);
CREATE INDEX idx_reservation_policy_snapshots_reservation ON reservation_policy_snapshots USING btree (reservation_id, policy_type);
CREATE INDEX idx_reservation_room_nights_reservation ON reservation_room_nights USING btree (reservation_id, stay_date);
CREATE INDEX idx_reservation_room_nights_room ON reservation_room_nights USING btree (room_id, stay_date);
CREATE INDEX idx_reservation_room_nights_tenant_reservation ON reservation_room_nights USING btree (tenant_id, reservation_id, stay_date);
CREATE INDEX idx_reservation_room_nights_tenant_room ON reservation_room_nights USING btree (tenant_id, room_id, stay_date) WHERE (room_id IS NOT NULL);

-- reservation_rooms
CREATE INDEX idx_reservation_rooms_availability ON reservation_rooms USING btree (tenant_id, room_type_id, check_in_date, check_out_date) WHERE ((status)::text <> ALL ((ARRAY['cancelled', 'checked_out'])::text[]));
CREATE INDEX idx_reservation_rooms_reservation_id ON reservation_rooms USING btree (reservation_id);
CREATE INDEX idx_reservation_rooms_room_dates ON reservation_rooms USING btree (room_id, check_in_date, check_out_date);
CREATE INDEX idx_reservation_rooms_room_id ON reservation_rooms USING btree (room_id);
CREATE INDEX idx_reservation_rooms_status ON reservation_rooms USING btree (status);
CREATE UNIQUE INDEX idx_reservation_rooms_tenant_id_id ON reservation_rooms USING btree (tenant_id, id);
CREATE INDEX idx_reservation_rooms_tenant_room_dates ON reservation_rooms USING btree (tenant_id, room_id, check_in_date, check_out_date) WHERE (room_id IS NOT NULL);

-- reservations
CREATE INDEX idx_reservations_active ON reservations USING btree (tenant_id, status, check_in_date) WHERE (deleted_at IS NULL);
CREATE INDEX idx_reservations_channel ON reservations USING btree (channel_id) WHERE (channel_id IS NOT NULL);
CREATE INDEX idx_reservations_check_in_date ON reservations USING btree (check_in_date);
CREATE INDEX idx_reservations_check_out_date ON reservations USING btree (check_out_date);
CREATE INDEX idx_reservations_primary_guest_id ON reservations USING btree (primary_guest_id);
CREATE INDEX idx_reservations_property_check_in ON reservations USING btree (property_id, check_in_date);
CREATE INDEX idx_reservations_tenant_property_dates ON reservations USING btree (tenant_id, property_id, check_in_date, check_out_date) WHERE (deleted_at IS NULL);
CREATE INDEX idx_reservations_tenant_property_status_dates ON reservations USING btree (tenant_id, property_id, status, check_in_date, check_out_date) WHERE (deleted_at IS NULL);
CREATE INDEX idx_reservations_property_id ON reservations USING btree (property_id);
CREATE INDEX idx_reservations_status ON reservations USING btree (status);
CREATE INDEX idx_reservations_tenant_id ON reservations USING btree (tenant_id);
CREATE UNIQUE INDEX idx_reservations_tenant_id_id ON reservations USING btree (tenant_id, id);
CREATE INDEX idx_reservations_tenant_status ON reservations USING btree (tenant_id, status);

-- roles
CREATE INDEX idx_roles_tenant_id ON roles USING btree (tenant_id);
CREATE UNIQUE INDEX idx_roles_tenant_name ON roles USING btree (tenant_id, name);

-- room_moves
CREATE INDEX idx_room_moves_reservation ON room_moves USING btree (reservation_id, moved_at DESC);
CREATE INDEX idx_room_moves_rooms ON room_moves USING btree (from_room_id, to_room_id);
CREATE INDEX idx_room_moves_tenant_reservation ON room_moves USING btree (tenant_id, reservation_id, moved_at DESC);
CREATE INDEX idx_room_moves_tenant_from_room ON room_moves USING btree (tenant_id, from_room_id, moved_at DESC) WHERE (from_room_id IS NOT NULL);
CREATE INDEX idx_room_moves_tenant_to_room ON room_moves USING btree (tenant_id, to_room_id, moved_at DESC) WHERE (to_room_id IS NOT NULL);

-- room_status_log
CREATE INDEX idx_room_status_log_changed_at ON room_status_log USING btree (changed_at);
CREATE INDEX idx_room_status_log_room_id ON room_status_log USING btree (room_id);

-- room_types
CREATE INDEX idx_room_types_property_id ON room_types USING btree (property_id);
CREATE UNIQUE INDEX idx_room_types_tenant_id_id ON room_types USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_room_types_tenant_property_id ON room_types USING btree (tenant_id, property_id, id);
CREATE UNIQUE INDEX idx_room_types_tenant_property_code ON room_types USING btree (tenant_id, property_id, code);

-- rooms
CREATE INDEX idx_rooms_active ON rooms USING btree (property_id, status) WHERE (deleted_at IS NULL);
CREATE INDEX idx_rooms_property_id ON rooms USING btree (property_id);
CREATE UNIQUE INDEX idx_rooms_property_room_number ON rooms USING btree (property_id, room_number) WHERE (deleted_at IS NULL);
CREATE INDEX idx_rooms_status ON rooms USING btree (status);
CREATE UNIQUE INDEX idx_rooms_tenant_id_id ON rooms USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_rooms_tenant_id_room_type ON rooms USING btree (tenant_id, id, room_type_id);

-- shift_handovers
CREATE INDEX idx_shift_handovers_property ON shift_handovers USING btree (property_id, handover_at);
CREATE INDEX idx_shift_handovers_tenant ON shift_handovers USING btree (tenant_id);

-- shift_templates
CREATE INDEX idx_shift_templates_property ON shift_templates USING btree (property_id, starts_at) WHERE (is_active = true);
CREATE UNIQUE INDEX idx_shift_templates_tenant_id_id ON shift_templates USING btree (tenant_id, id);

-- sales
CREATE INDEX idx_sales_activities_lead ON sales_activities USING btree (lead_id, activity_at DESC) WHERE (lead_id IS NOT NULL);
CREATE INDEX idx_sales_activities_assigned ON sales_activities USING btree (assigned_to, due_at) WHERE (completed_at IS NULL);
CREATE INDEX idx_sales_leads_assigned ON sales_leads USING btree (assigned_to, status);
CREATE INDEX idx_sales_leads_property_status ON sales_leads USING btree (property_id, status);
CREATE UNIQUE INDEX idx_sales_leads_tenant_id_id ON sales_leads USING btree (tenant_id, id);

-- stays
CREATE UNIQUE INDEX idx_stays_tenant_id_id ON stays USING btree (tenant_id, id);

-- stock_levels
CREATE UNIQUE INDEX idx_stock_levels_item_location ON stock_levels USING btree (tenant_id, item_id, location_id) WHERE (location_id IS NOT NULL);
CREATE UNIQUE INDEX idx_stock_levels_item_no_location ON stock_levels USING btree (tenant_id, item_id) WHERE (location_id IS NULL);

-- stock_movements
CREATE INDEX idx_stock_movements_item_id ON stock_movements USING btree (item_id);
CREATE INDEX idx_stock_movements_tenant_item_created ON stock_movements USING btree (tenant_id, item_id, created_at);

-- suppliers
CREATE INDEX idx_suppliers_tenant_id ON suppliers USING btree (tenant_id);
CREATE UNIQUE INDEX idx_suppliers_tenant_id_id ON suppliers USING btree (tenant_id, id);

-- tax_rates
CREATE INDEX idx_tax_rates_tenant ON tax_rates USING btree (tenant_id) WHERE (is_active = true);
CREATE UNIQUE INDEX idx_tax_rates_tenant_id_id ON tax_rates USING btree (tenant_id, id);

-- tenants
CREATE INDEX idx_tenant_module_configs_tenant ON tenant_module_configs USING btree (tenant_id, module_id);
CREATE INDEX idx_tenant_modules_enabled ON tenant_modules USING btree (tenant_id, module_id) WHERE (is_enabled = true);
CREATE INDEX idx_tenants_name ON tenants USING btree (name);
CREATE INDEX idx_tenants_plan ON tenants USING btree (plan_id);
CREATE UNIQUE INDEX idx_tenants_slug_active ON tenants USING btree (lower((slug)::text)) WHERE (deleted_at IS NULL);
CREATE INDEX idx_tenants_status ON tenants USING btree (status);

-- translations
CREATE INDEX idx_translations_lookup ON translations USING btree (namespace, key, locale) WHERE (is_approved = true);
CREATE INDEX idx_translations_tenant ON translations USING btree (tenant_id, locale) WHERE (tenant_id IS NOT NULL);

-- utilities
-- users
CREATE INDEX idx_users_department_id ON users USING btree (department_id);
CREATE INDEX idx_users_status ON users USING btree (status);
CREATE UNIQUE INDEX idx_users_tenant_email ON users USING btree (tenant_id, lower((email)::text)) WHERE (deleted_at IS NULL);
CREATE UNIQUE INDEX idx_users_tenant_id_id ON users USING btree (tenant_id, id);
CREATE INDEX idx_users_tenant_id ON users USING btree (tenant_id);

-- enterprise foundation catalogs and platform operations
CREATE INDEX idx_feature_flags_scope ON feature_flags USING btree (scope, tenant_id) WHERE (is_enabled = true);
CREATE UNIQUE INDEX idx_feature_flags_platform_key ON feature_flags USING btree (flag_key) WHERE ((scope)::text = 'platform'::text);
CREATE UNIQUE INDEX idx_feature_flags_tenant_key ON feature_flags USING btree (tenant_id, flag_key) WHERE ((scope)::text = 'tenant'::text);
CREATE UNIQUE INDEX idx_feature_flags_property_key ON feature_flags USING btree (tenant_id, property_id, flag_key) WHERE ((scope)::text = 'property'::text);
CREATE INDEX idx_idempotency_keys_expiry ON idempotency_keys USING btree (expires_at) WHERE ((status)::text = 'processing');
CREATE INDEX idx_idempotency_keys_expiry_active ON idempotency_keys USING btree (expires_at) WHERE ((status)::text = ANY ((ARRAY['processing', 'succeeded', 'failed'])::text[]));
CREATE INDEX idx_idempotency_keys_resource ON idempotency_keys USING btree (tenant_id, resource_type, resource_id);
CREATE UNIQUE INDEX idx_idempotency_keys_global_key ON idempotency_keys USING btree (idempotency_key) WHERE (tenant_id IS NULL);
CREATE UNIQUE INDEX idx_idempotency_keys_tenant_key ON idempotency_keys USING btree (tenant_id, idempotency_key) WHERE (tenant_id IS NOT NULL);
CREATE INDEX idx_maintenance_windows_active ON maintenance_windows USING btree (scope, tenant_id, property_id, starts_at, ends_at) WHERE ((status)::text IN ('scheduled', 'in_progress'));
CREATE INDEX idx_module_catalog_status ON module_catalog USING btree (launch_status, category, display_order);
CREATE INDEX idx_outbox_events_dispatch ON outbox_events USING btree (destination, status, priority, next_attempt_at);
CREATE INDEX idx_outbox_events_worker_poll ON outbox_events USING btree (status, next_attempt_at, priority, created_at) WHERE ((status)::text = ANY ((ARRAY['pending', 'failed'])::text[]));
CREATE INDEX idx_outbox_events_correlation ON outbox_events USING btree (correlation_id) WHERE (correlation_id IS NOT NULL);
CREATE INDEX idx_permission_catalog_namespace ON permission_catalog USING btree (namespace, access_scope);
CREATE INDEX idx_platform_alerts_open ON platform_alerts USING btree (severity, opened_at) WHERE ((status)::text IN ('open', 'acknowledged'));
CREATE INDEX idx_platform_audit_logs_entity ON platform_audit_logs USING btree (entity_type, entity_id, created_at);
CREATE INDEX idx_platform_audit_logs_tenant ON platform_audit_logs USING btree (tenant_id, created_at) WHERE (tenant_id IS NOT NULL);
CREATE INDEX idx_platform_break_glass_active ON platform_break_glass_access USING btree (tenant_id, platform_user_id, action_code, starts_at, expires_at) WHERE (((status)::text = 'active') AND (approved_by IS NOT NULL) AND (revoked_at IS NULL));
CREATE INDEX idx_platform_job_runs_status ON platform_job_runs USING btree (status, created_at);
CREATE INDEX idx_platform_sessions_user ON platform_sessions USING btree (platform_user_id, expires_at);
CREATE INDEX idx_service_health_checks_service ON service_health_checks USING btree (service_id, checked_at DESC);
CREATE INDEX idx_support_tickets_tenant_status ON support_tickets USING btree (tenant_id, status, priority);
CREATE INDEX idx_tenant_lifecycle_events_tenant ON tenant_lifecycle_events USING btree (tenant_id, created_at);
CREATE INDEX idx_tenant_roles_tenant ON tenant_roles USING btree (tenant_id, is_active);
CREATE INDEX idx_tenant_usage_snapshots_tenant ON tenant_usage_snapshots USING btree (tenant_id, snapshot_date DESC);
CREATE INDEX idx_user_tenant_roles_tenant ON user_tenant_roles USING btree (tenant_id, user_id);
CREATE INDEX idx_canonical_workflow_steps_workflow ON workflow_steps USING btree (workflow_code, step_order);

-- work_orders
CREATE INDEX idx_work_orders_employee ON work_orders USING btree (assigned_to) WHERE (status = ANY (ARRAY['open', 'in_progress']));
CREATE INDEX idx_work_orders_property ON work_orders USING btree (property_id, status) WHERE (deleted_at IS NULL);


-- Explicit tenant-safe target support indexes.
CREATE UNIQUE INDEX idx_allotments_tenant_id_id ON allotments USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_approval_requests_tenant_id_id ON approval_requests USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_approval_workflows_tenant_id_id ON approval_workflows USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_ar_allocations_tenant_id_id ON ar_allocations USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_attendance_tenant_id_id ON attendance USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_audit_logs_tenant_id_id ON audit_logs USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_availability_calendar_tenant_id_id ON availability_calendar USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_availability_locks_tenant_id_id ON availability_locks USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_booking_payment_attempts_tenant_id_id ON booking_payment_attempts USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_booking_session_room_nights_tenant_id_id ON booking_session_room_nights USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_booking_session_rooms_tenant_id_id ON booking_session_rooms USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_buildings_tenant_id_id ON buildings USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_cash_float_movements_tenant_id_id ON cash_float_movements USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_corporate_account_holds_tenant_id_id ON corporate_account_holds USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_corporate_account_limits_tenant_id_id ON corporate_account_limits USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_corporate_rate_agreements_tenant_id_id ON corporate_rate_agreements USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_corporate_statement_items_tenant_id_id ON corporate_statement_items USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_currency_rates_tenant_id_id ON currency_rates USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_document_sequences_tenant_id_id ON document_sequences USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_edge_sync_events_tenant_id_id ON edge_sync_events USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_event_booking_items_tenant_id_id ON event_booking_items USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_event_bookings_tenant_id_id ON event_bookings USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_events_tenant_id_id ON events USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_feature_flags_tenant_id_id ON feature_flags USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_fiscal_document_mappings_tenant_id_id ON fiscal_document_mappings USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_fiscal_submission_attempts_tenant_id_id ON fiscal_submission_attempts USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_floors_tenant_id_id ON floors USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_folio_charge_taxes_tenant_id_id ON folio_charge_taxes USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_guest_contacts_tenant_id_id ON guest_contacts USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_guest_documents_tenant_id_id ON guest_documents USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_guest_feedback_tenant_id_id ON guest_feedback USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_guest_preferences_tenant_id_id ON guest_preferences USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_housekeeping_assignments_tenant_id_id ON housekeeping_assignments USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_housekeeping_tasks_tenant_id_id ON housekeeping_tasks USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_idempotency_keys_tenant_id_id ON idempotency_keys USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_inventory_items_tenant_id_id ON inventory_items USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_inventory_locations_tenant_id_id ON inventory_locations USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_invoice_item_taxes_tenant_id_id ON invoice_item_taxes USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_journal_entry_lines_tenant_id_id ON journal_entry_lines USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_kitchen_tickets_tenant_id_id ON kitchen_tickets USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_labor_forecasts_tenant_id_id ON labor_forecasts USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_leave_requests_tenant_id_id ON leave_requests USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_lost_and_found_tenant_id_id ON lost_and_found USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_loyalty_accounts_tenant_id_id ON loyalty_accounts USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_loyalty_transactions_tenant_id_id ON loyalty_transactions USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_maintenance_requests_tenant_id_id ON maintenance_requests USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_maintenance_windows_tenant_id_id ON maintenance_windows USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_menu_item_recipes_tenant_id_id ON menu_item_recipes USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_mobile_money_disbursements_tenant_id_id ON mobile_money_disbursements USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_night_audit_runs_tenant_id_id ON night_audit_runs USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_notifications_tenant_id_id ON notifications USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_ota_room_type_mappings_tenant_id_id ON ota_room_type_mappings USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_outbox_events_tenant_id_id ON outbox_events USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_payment_reconciliation_items_tenant_id_id ON payment_reconciliation_items USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_payroll_runs_tenant_id_id ON payroll_runs USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_permissions_tenant_id_id ON permissions USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_pos_order_items_tenant_id_id ON pos_order_items USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_pos_printer_routes_tenant_id_id ON pos_printer_routes USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_pos_terminals_tenant_id_id ON pos_terminals USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_pricing_rules_tenant_id_id ON pricing_rules USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_promo_codes_tenant_id_id ON promo_codes USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_property_billing_settings_tenant_id_id ON property_billing_settings USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_property_frontdesk_settings_tenant_id_id ON property_frontdesk_settings USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_property_housekeeping_settings_tenant_id_id ON property_housekeeping_settings USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_property_module_configs_tenant_id_id ON property_module_configs USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_property_modules_tenant_id_id ON property_modules USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_property_pos_settings_tenant_id_id ON property_pos_settings USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_property_reservation_settings_tenant_id_id ON property_reservation_settings USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_purchase_order_items_tenant_id_id ON purchase_order_items USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_purchase_orders_tenant_id_id ON purchase_orders USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_rate_plan_prices_tenant_id_id ON rate_plan_prices USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_rate_restrictions_tenant_id_id ON rate_restrictions USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_reservation_guests_tenant_id_id ON reservation_guests USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_reservation_notes_tenant_id_id ON reservation_notes USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_reservation_room_nights_tenant_id_id ON reservation_room_nights USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_roles_tenant_id_id ON roles USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_room_moves_tenant_id_id ON room_moves USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_room_status_log_tenant_id_id ON room_status_log USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_sales_activities_tenant_id_id ON sales_activities USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_shift_handovers_tenant_id_id ON shift_handovers USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_staff_rosters_tenant_id_id ON staff_rosters USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_stock_levels_tenant_id_id ON stock_levels USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_stock_movements_tenant_id_id ON stock_movements USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_support_tickets_tenant_id_id ON support_tickets USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_tax_jurisdictions_tenant_id_id ON tax_jurisdictions USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_tax_report_lines_tenant_id_id ON tax_report_lines USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_tenant_configs_tenant_id_id ON tenant_configs USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_tenant_lifecycle_events_tenant_id_id ON tenant_lifecycle_events USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_tenant_module_configs_tenant_id_id ON tenant_module_configs USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_tenant_modules_tenant_id_id ON tenant_modules USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_tenant_roles_tenant_id_id ON tenant_roles USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_tenant_usage_snapshots_tenant_id_id ON tenant_usage_snapshots USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_translations_tenant_id_id ON translations USING btree (tenant_id, id);
CREATE UNIQUE INDEX idx_work_orders_tenant_id_id ON work_orders USING btree (tenant_id, id);

-- Explicit tenant-safe relationship guards formerly generated by catalog introspection.
ALTER TABLE ONLY allotments
    ADD CONSTRAINT fk_tenant_guard_338ae07039bdd60b FOREIGN KEY (tenant_id, room_type_id) REFERENCES room_types(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY allotments
    ADD CONSTRAINT fk_tenant_guard_4bacc8f35ebbb648 FOREIGN KEY (tenant_id, group_id) REFERENCES groups(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY allotments
    ADD CONSTRAINT fk_tenant_guard_4caa7f8a1f1fdb40 FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY allotments
    ADD CONSTRAINT fk_tenant_guard_a19e4ec79765e575 FOREIGN KEY (tenant_id, channel_id) REFERENCES channels(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY approval_requests
    ADD CONSTRAINT fk_tenant_guard_3a34e543c6b10da5 FOREIGN KEY (tenant_id, workflow_id) REFERENCES approval_workflows(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY approval_requests
    ADD CONSTRAINT fk_tenant_guard_b8cb1bba0516a18e FOREIGN KEY (tenant_id, cancelled_by) REFERENCES users(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY approval_requests
    ADD CONSTRAINT fk_tenant_guard_fb945e87426686f1 FOREIGN KEY (tenant_id, requested_by) REFERENCES users(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY attendance
    ADD CONSTRAINT fk_tenant_guard_682158496c6e452b FOREIGN KEY (tenant_id, employee_id) REFERENCES employees(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY audit_logs
    ADD CONSTRAINT fk_tenant_guard_50100f805df8a79d FOREIGN KEY (tenant_id, user_id) REFERENCES users(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY availability_locks
    ADD CONSTRAINT fk_tenant_guard_42344d3e40fc6651 FOREIGN KEY (tenant_id, room_type_id) REFERENCES room_types(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY booking_engine_configs
    ADD CONSTRAINT fk_tenant_guard_c514a9d50247eb97 FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY booking_session_room_nights
    ADD CONSTRAINT fk_tenant_guard_d545dda066b731a6 FOREIGN KEY (tenant_id, session_room_id) REFERENCES booking_session_rooms(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY booking_sessions
    ADD CONSTRAINT fk_tenant_guard_2aea386baef94479 FOREIGN KEY (tenant_id, channel_id) REFERENCES channels(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY booking_sessions
    ADD CONSTRAINT fk_tenant_guard_450c75732205debb FOREIGN KEY (tenant_id, converted_reservation_id) REFERENCES reservations(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY booking_sessions
    ADD CONSTRAINT fk_tenant_guard_beab6b52adfcaf98 FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY booking_sessions
    ADD CONSTRAINT fk_tenant_guard_dc0054d48ffd6cd8 FOREIGN KEY (tenant_id, promo_code_id) REFERENCES promo_codes(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY buildings
    ADD CONSTRAINT fk_tenant_guard_7801f05090e6eedb FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY cash_float_movements
    ADD CONSTRAINT fk_tenant_guard_77e2dc5b31007404 FOREIGN KEY (tenant_id, session_id) REFERENCES pos_sessions(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY cash_float_movements
    ADD CONSTRAINT fk_tenant_guard_7c7795f5746bddfb FOREIGN KEY (tenant_id, created_by) REFERENCES users(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY cash_float_movements
    ADD CONSTRAINT fk_tenant_guard_b67306053d5d2572 FOREIGN KEY (tenant_id, authorised_by) REFERENCES users(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY channel_connections
    ADD CONSTRAINT fk_tenant_guard_67f3a847cbc6c60e FOREIGN KEY (tenant_id, channel_id) REFERENCES channels(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY channel_connections
    ADD CONSTRAINT fk_tenant_guard_e2363021403316ff FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY currency_rates
    ADD CONSTRAINT fk_tenant_guard_ccce17c43d4a6670 FOREIGN KEY (tenant_id, created_by) REFERENCES users(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY departments
    ADD CONSTRAINT fk_tenant_guard_e2fe8bdd74008386 FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY edge_sync_events
    ADD CONSTRAINT fk_tenant_guard_559ec0f4f8fac5c8 FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY edge_sync_events
    ADD CONSTRAINT fk_tenant_guard_8286711c4da813f7 FOREIGN KEY (tenant_id, resolved_by) REFERENCES users(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY employees
    ADD CONSTRAINT fk_tenant_guard_8d61210ef00e511b FOREIGN KEY (tenant_id, department_id) REFERENCES departments(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY employees
    ADD CONSTRAINT fk_tenant_guard_95f0e9573f89a289 FOREIGN KEY (tenant_id, user_id) REFERENCES users(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY event_booking_items
    ADD CONSTRAINT fk_tenant_guard_da650de4b7a3e322 FOREIGN KEY (tenant_id, booking_id) REFERENCES event_bookings(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY event_bookings
    ADD CONSTRAINT fk_tenant_guard_13d5f9cade4da3c2 FOREIGN KEY (tenant_id, package_id) REFERENCES event_packages(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY event_bookings
    ADD CONSTRAINT fk_tenant_guard_43f78672f5f1ff7a FOREIGN KEY (tenant_id, event_space_id) REFERENCES event_spaces(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY event_bookings
    ADD CONSTRAINT fk_tenant_guard_4fbc33e2550f2a6e FOREIGN KEY (tenant_id, guest_id) REFERENCES guests(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY event_bookings
    ADD CONSTRAINT fk_tenant_guard_5bde9836aee62ff4 FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY event_bookings
    ADD CONSTRAINT fk_tenant_guard_87aa03dc86275751 FOREIGN KEY (tenant_id, handled_by) REFERENCES users(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY event_bookings
    ADD CONSTRAINT fk_tenant_guard_9b66b6cfad911e0a FOREIGN KEY (tenant_id, company_id) REFERENCES companies(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY event_bookings
    ADD CONSTRAINT fk_tenant_guard_c004f7b5f41a3dbc FOREIGN KEY (tenant_id, folio_id) REFERENCES folios(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY event_bookings
    ADD CONSTRAINT fk_tenant_guard_d96b8cd401947567 FOREIGN KEY (tenant_id, group_id) REFERENCES groups(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY event_spaces
    ADD CONSTRAINT fk_tenant_guard_440c6bb203adc810 FOREIGN KEY (tenant_id, floor_id) REFERENCES floors(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY event_spaces
    ADD CONSTRAINT fk_tenant_guard_e21f20faa1de78c3 FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY fiscal_receipts
    ADD CONSTRAINT fk_tenant_guard_c230a035cfd2076e FOREIGN KEY (tenant_id, invoice_id) REFERENCES invoices(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY floors
    ADD CONSTRAINT fk_tenant_guard_bf70f34229a4b919 FOREIGN KEY (tenant_id, building_id) REFERENCES buildings(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY folio_charges
    ADD CONSTRAINT fk_tenant_guard_07773e184d66f09f FOREIGN KEY (tenant_id, voided_by) REFERENCES users(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY folio_charges
    ADD CONSTRAINT fk_tenant_guard_43820a42046a78c7 FOREIGN KEY (tenant_id, reversal_of) REFERENCES folio_charges(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY folio_charges
    ADD CONSTRAINT fk_tenant_guard_ce6adc479350232d FOREIGN KEY (tenant_id, revenue_center_id) REFERENCES revenue_centers(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY folio_charges
    ADD CONSTRAINT fk_tenant_guard_e76a05eb1a2daaa9 FOREIGN KEY (tenant_id, posted_by) REFERENCES users(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY folio_payments
    ADD CONSTRAINT fk_tenant_guard_5a254067bf6c76e2 FOREIGN KEY (tenant_id, created_by) REFERENCES users(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY folio_payments
    ADD CONSTRAINT fk_tenant_guard_ac42ff2125d65658 FOREIGN KEY (tenant_id, reversal_of) REFERENCES folio_payments(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY folio_payments
    ADD CONSTRAINT fk_tenant_guard_c747817586475400 FOREIGN KEY (tenant_id, processed_by) REFERENCES users(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY folios
    ADD CONSTRAINT fk_tenant_guard_31cfc97692a37eb5 FOREIGN KEY (tenant_id, parent_folio_id) REFERENCES folios(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY groups
    ADD CONSTRAINT fk_tenant_guard_07d5e5f663696bb0 FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY groups
    ADD CONSTRAINT fk_tenant_guard_132000171c9c11e1 FOREIGN KEY (tenant_id, company_id) REFERENCES companies(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY groups
    ADD CONSTRAINT fk_tenant_guard_18e077af424e4075 FOREIGN KEY (tenant_id, account_manager) REFERENCES users(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY guest_contacts
    ADD CONSTRAINT fk_tenant_guard_03d16fa80fa6f1f6 FOREIGN KEY (tenant_id, guest_id) REFERENCES guests(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY guest_documents
    ADD CONSTRAINT fk_tenant_guard_3d439ddf8c7014a0 FOREIGN KEY (tenant_id, verified_by) REFERENCES users(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY guest_documents
    ADD CONSTRAINT fk_tenant_guard_830c7e0c52c8a30a FOREIGN KEY (tenant_id, guest_id) REFERENCES guests(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY guest_feedback
    ADD CONSTRAINT fk_tenant_guard_2979cb9076389049 FOREIGN KEY (tenant_id, responded_by) REFERENCES users(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY guest_feedback
    ADD CONSTRAINT fk_tenant_guard_4970a77016ee873e FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY guest_feedback
    ADD CONSTRAINT fk_tenant_guard_a85795d1a6aa8419 FOREIGN KEY (tenant_id, reservation_id) REFERENCES reservations(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY guest_feedback
    ADD CONSTRAINT fk_tenant_guard_f50c0ed182f72bac FOREIGN KEY (tenant_id, guest_id) REFERENCES guests(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY guest_preferences
    ADD CONSTRAINT fk_tenant_guard_bb2697090170e5c8 FOREIGN KEY (tenant_id, guest_id) REFERENCES guests(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY guests
    ADD CONSTRAINT fk_tenant_guard_bb7742af79fd0092 FOREIGN KEY (tenant_id, company_id) REFERENCES companies(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY housekeeping_assignments
    ADD CONSTRAINT fk_tenant_guard_97fe29c21cf125b5 FOREIGN KEY (tenant_id, user_id) REFERENCES users(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY housekeeping_assignments
    ADD CONSTRAINT fk_tenant_guard_c9ba6d0d3580bf3b FOREIGN KEY (tenant_id, task_id) REFERENCES housekeeping_tasks(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY inventory_locations
    ADD CONSTRAINT fk_tenant_guard_1f7e88430526aee8 FOREIGN KEY (tenant_id, outlet_id) REFERENCES outlets(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY inventory_locations
    ADD CONSTRAINT fk_tenant_guard_4afd2c2421a50ca4 FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY invoice_items
    ADD CONSTRAINT fk_tenant_guard_16a1bfafc8751378 FOREIGN KEY (tenant_id, reversal_of) REFERENCES invoice_items(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY invoice_items
    ADD CONSTRAINT fk_tenant_guard_2529dba05f17be1a FOREIGN KEY (tenant_id, revenue_center_id) REFERENCES revenue_centers(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY invoices
    ADD CONSTRAINT fk_tenant_guard_680580eaa905239d FOREIGN KEY (tenant_id, created_by) REFERENCES users(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY journal_entry_lines
    ADD CONSTRAINT fk_tenant_guard_5f0e47c7824c9438 FOREIGN KEY (tenant_id, revenue_center_id) REFERENCES revenue_centers(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY kitchen_tickets
    ADD CONSTRAINT fk_tenant_guard_ea45208a8de67ecc FOREIGN KEY (tenant_id, outlet_id) REFERENCES outlets(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY kitchen_tickets
    ADD CONSTRAINT fk_tenant_guard_efaf05b02af2d5ae FOREIGN KEY (tenant_id, order_id) REFERENCES pos_orders(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY lost_and_found
    ADD CONSTRAINT fk_tenant_guard_0a4e75cd7c2d22e8 FOREIGN KEY (tenant_id, room_id) REFERENCES rooms(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY lost_and_found
    ADD CONSTRAINT fk_tenant_guard_0decb13431fd282f FOREIGN KEY (tenant_id, found_by) REFERENCES users(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY lost_and_found
    ADD CONSTRAINT fk_tenant_guard_5e93592438055537 FOREIGN KEY (tenant_id, claimed_by) REFERENCES guests(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY lost_and_found
    ADD CONSTRAINT fk_tenant_guard_ef3313af908795dd FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY loyalty_accounts
    ADD CONSTRAINT fk_tenant_guard_6f991cc641993d63 FOREIGN KEY (tenant_id, guest_id) REFERENCES guests(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY loyalty_transactions
    ADD CONSTRAINT fk_tenant_guard_8f982b3d567353fd FOREIGN KEY (tenant_id, account_id) REFERENCES loyalty_accounts(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY maintenance_requests
    ADD CONSTRAINT fk_tenant_guard_2f77a3321ca439d1 FOREIGN KEY (tenant_id, reported_by) REFERENCES users(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY maintenance_requests
    ADD CONSTRAINT fk_tenant_guard_5d680f85397694f2 FOREIGN KEY (tenant_id, assigned_to) REFERENCES users(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY maintenance_requests
    ADD CONSTRAINT fk_tenant_guard_8459abe84740016b FOREIGN KEY (tenant_id, room_id) REFERENCES rooms(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY menu_categories
    ADD CONSTRAINT fk_tenant_guard_3c43fb6605bf9ed2 FOREIGN KEY (tenant_id, outlet_id) REFERENCES outlets(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY menu_item_recipes
    ADD CONSTRAINT fk_tenant_guard_8916f8258375d09d FOREIGN KEY (tenant_id, menu_item_id) REFERENCES menu_items(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY menu_item_recipes
    ADD CONSTRAINT fk_tenant_guard_89cde8b54fa59554 FOREIGN KEY (tenant_id, inventory_item_id) REFERENCES inventory_items(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY menu_items
    ADD CONSTRAINT fk_tenant_guard_25465e6f79bd2812 FOREIGN KEY (tenant_id, tax_rate_id) REFERENCES tax_rates(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY menu_items
    ADD CONSTRAINT fk_tenant_guard_965a28d7286d9cec FOREIGN KEY (tenant_id, category_id) REFERENCES menu_categories(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY menu_items
    ADD CONSTRAINT fk_tenant_guard_9c62aa48c67673c2 FOREIGN KEY (tenant_id, inventory_item_id) REFERENCES inventory_items(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY night_audit_runs
    ADD CONSTRAINT fk_tenant_guard_2603536595ac26cd FOREIGN KEY (tenant_id, run_by) REFERENCES users(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY night_audit_runs
    ADD CONSTRAINT fk_tenant_guard_cf6be0e33235422b FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY notifications
    ADD CONSTRAINT fk_tenant_guard_0f1e8d24752f33f7 FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY notifications
    ADD CONSTRAINT fk_tenant_guard_8ced2d706a203931 FOREIGN KEY (tenant_id, user_id) REFERENCES users(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY outbox_events
    ADD CONSTRAINT fk_tenant_guard_d9352903b16dde13 FOREIGN KEY (tenant_id, idempotency_key_id) REFERENCES idempotency_keys(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY outlets
    ADD CONSTRAINT fk_tenant_guard_a05da651d8e89817 FOREIGN KEY (tenant_id, revenue_center_id) REFERENCES revenue_centers(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY payroll_records
    ADD CONSTRAINT fk_tenant_guard_1a660d3d56618be9 FOREIGN KEY (tenant_id, employee_id) REFERENCES employees(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY payroll_records
    ADD CONSTRAINT fk_tenant_guard_546ba860255fd5c7 FOREIGN KEY (tenant_id, payroll_run_id) REFERENCES payroll_runs(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY payroll_runs
    ADD CONSTRAINT fk_tenant_guard_4736cc1e94a624ba FOREIGN KEY (tenant_id, approved_by) REFERENCES users(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY payroll_runs
    ADD CONSTRAINT fk_tenant_guard_ec2570ebdd93f3eb FOREIGN KEY (tenant_id, run_by) REFERENCES users(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY pos_orders
    ADD CONSTRAINT fk_tenant_guard_067295ccc65d7e14 FOREIGN KEY (tenant_id, edge_sync_id) REFERENCES edge_sync_events(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY pos_orders
    ADD CONSTRAINT fk_tenant_guard_36eb15932dbd5406 FOREIGN KEY (tenant_id, revenue_center_id) REFERENCES revenue_centers(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY pos_orders
    ADD CONSTRAINT fk_tenant_guard_71f85c5dfcb4cdaa FOREIGN KEY (tenant_id, served_by) REFERENCES users(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY pos_printer_routes
    ADD CONSTRAINT fk_tenant_guard_4ac20101dc1b057d FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY pos_printer_routes
    ADD CONSTRAINT fk_tenant_guard_7716ee699e3d5680 FOREIGN KEY (tenant_id, outlet_id) REFERENCES outlets(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY pos_sessions
    ADD CONSTRAINT fk_tenant_guard_be452bed93c000d7 FOREIGN KEY (tenant_id, cashier_id) REFERENCES users(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY pos_terminals
    ADD CONSTRAINT fk_tenant_guard_43eafb6273063aa4 FOREIGN KEY (tenant_id, outlet_id) REFERENCES outlets(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY pos_terminals
    ADD CONSTRAINT fk_tenant_guard_ba35c5dc19aca076 FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY pricing_rules
    ADD CONSTRAINT fk_tenant_guard_2d3f6649564ef984 FOREIGN KEY (tenant_id, room_type_id) REFERENCES room_types(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY pricing_rules
    ADD CONSTRAINT fk_tenant_guard_5de638285d132329 FOREIGN KEY (tenant_id, rate_plan_id) REFERENCES rate_plans(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY promo_codes
    ADD CONSTRAINT fk_tenant_guard_513861cb89d0e046 FOREIGN KEY (tenant_id, channel_id) REFERENCES channels(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY promo_codes
    ADD CONSTRAINT fk_tenant_guard_5f93632ba8d340fe FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY promo_codes
    ADD CONSTRAINT fk_tenant_guard_cb3c4b4292ce13de FOREIGN KEY (tenant_id, rate_plan_id) REFERENCES rate_plans(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY property_billing_settings
    ADD CONSTRAINT fk_tenant_guard_c21d12b5e99d467b FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY property_frontdesk_settings
    ADD CONSTRAINT fk_tenant_guard_b414013eedf96026 FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY property_housekeeping_settings
    ADD CONSTRAINT fk_tenant_guard_dcad0eaecda82466 FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY property_module_configs
    ADD CONSTRAINT fk_tenant_guard_4dbe57cea0f0c2de FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY property_modules
    ADD CONSTRAINT fk_tenant_guard_1a2ad446fcf6db86 FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY property_pos_settings
    ADD CONSTRAINT fk_tenant_guard_f8ef004724d1df53 FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY property_reservation_settings
    ADD CONSTRAINT fk_tenant_guard_ed9e564815290e41 FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY purchase_order_items
    ADD CONSTRAINT fk_tenant_guard_29cde3b96fbc4aab FOREIGN KEY (tenant_id, inventory_item_id) REFERENCES inventory_items(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY purchase_order_items
    ADD CONSTRAINT fk_tenant_guard_a2105b7cf46823f2 FOREIGN KEY (tenant_id, purchase_order_id) REFERENCES purchase_orders(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY purchase_orders
    ADD CONSTRAINT fk_tenant_guard_40a69e316169ef78 FOREIGN KEY (tenant_id, supplier_id) REFERENCES suppliers(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY purchase_orders
    ADD CONSTRAINT fk_tenant_guard_b4c2e6b5ce6f109f FOREIGN KEY (tenant_id, approval_request_id) REFERENCES approval_requests(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY rate_plan_prices
    ADD CONSTRAINT fk_tenant_guard_ab55f88a2b563363 FOREIGN KEY (tenant_id, rate_plan_id) REFERENCES rate_plans(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY rate_plan_prices
    ADD CONSTRAINT fk_tenant_guard_f1edce97bd491e81 FOREIGN KEY (tenant_id, room_type_id) REFERENCES room_types(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY rate_plans
    ADD CONSTRAINT fk_tenant_guard_44bbb506d96657b7 FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY rate_restrictions
    ADD CONSTRAINT fk_tenant_guard_39bf0d33630b1d4a FOREIGN KEY (tenant_id, room_type_id) REFERENCES room_types(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY rate_restrictions
    ADD CONSTRAINT fk_tenant_guard_59098862bfae2873 FOREIGN KEY (tenant_id, rate_plan_id) REFERENCES rate_plans(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY reservation_guests
    ADD CONSTRAINT fk_tenant_guard_7d40c25e19a88f4a FOREIGN KEY (tenant_id, reservation_id) REFERENCES reservations(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY reservation_guests
    ADD CONSTRAINT fk_tenant_guard_f2c43f3a0cdc6ef0 FOREIGN KEY (tenant_id, guest_id) REFERENCES guests(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY reservation_notes
    ADD CONSTRAINT fk_tenant_guard_0be551816c4c2730 FOREIGN KEY (tenant_id, reservation_id) REFERENCES reservations(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY reservation_notes
    ADD CONSTRAINT fk_tenant_guard_ec7c360335ba5861 FOREIGN KEY (tenant_id, created_by) REFERENCES users(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY reservations
    ADD CONSTRAINT fk_tenant_guard_dc2174d5b0bc2620 FOREIGN KEY (tenant_id, created_by) REFERENCES users(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY room_status_log
    ADD CONSTRAINT fk_tenant_guard_7760d83b75db993d FOREIGN KEY (tenant_id, room_id) REFERENCES rooms(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY room_status_log
    ADD CONSTRAINT fk_tenant_guard_afd24affda66fde2 FOREIGN KEY (tenant_id, changed_by) REFERENCES users(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY rooms
    ADD CONSTRAINT fk_tenant_guard_c70c65cff40db3ea FOREIGN KEY (tenant_id, connecting_room_id) REFERENCES rooms(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY shift_handovers
    ADD CONSTRAINT fk_tenant_guard_11613dc0d24f037b FOREIGN KEY (tenant_id, incoming_user_id) REFERENCES users(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY shift_handovers
    ADD CONSTRAINT fk_tenant_guard_26d85a3b60f153be FOREIGN KEY (tenant_id, pos_session_id) REFERENCES pos_sessions(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY shift_handovers
    ADD CONSTRAINT fk_tenant_guard_94bae049c3fdccef FOREIGN KEY (tenant_id, department_id) REFERENCES departments(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY shift_handovers
    ADD CONSTRAINT fk_tenant_guard_a38f09ab94c601ed FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY shift_handovers
    ADD CONSTRAINT fk_tenant_guard_f944d828735f1584 FOREIGN KEY (tenant_id, outgoing_user_id) REFERENCES users(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY shift_templates
    ADD CONSTRAINT fk_tenant_guard_2a2a3823e7592129 FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY shift_templates
    ADD CONSTRAINT fk_tenant_guard_73c9cf0630bb0496 FOREIGN KEY (tenant_id, department_id) REFERENCES departments(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY stays
    ADD CONSTRAINT fk_tenant_guard_0e5a9a3a0308dc4a FOREIGN KEY (tenant_id, room_id) REFERENCES rooms(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY stays
    ADD CONSTRAINT fk_tenant_guard_5f6eb90b755bc5ff FOREIGN KEY (tenant_id, reservation_id) REFERENCES reservations(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY stock_levels
    ADD CONSTRAINT fk_tenant_guard_697bc2fb41c1b473 FOREIGN KEY (tenant_id, location_id) REFERENCES inventory_locations(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY stock_levels
    ADD CONSTRAINT fk_tenant_guard_a4db59bdfced54cf FOREIGN KEY (tenant_id, item_id) REFERENCES inventory_items(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY stock_movements
    ADD CONSTRAINT fk_tenant_guard_2093b1b7b10f9fff FOREIGN KEY (tenant_id, location_id) REFERENCES inventory_locations(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY stock_movements
    ADD CONSTRAINT fk_tenant_guard_331a17cc8c69f1c9 FOREIGN KEY (tenant_id, item_id) REFERENCES inventory_items(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY support_tickets
    ADD CONSTRAINT fk_tenant_guard_cf1bf8b9e477f521 FOREIGN KEY (tenant_id, opened_by_user_id) REFERENCES users(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY translations
    ADD CONSTRAINT fk_tenant_guard_03fd981d1baa8967 FOREIGN KEY (tenant_id, translated_by) REFERENCES users(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY user_property_roles
    ADD CONSTRAINT fk_tenant_guard_1275dab8fe209579 FOREIGN KEY (tenant_id, role_id) REFERENCES roles(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY user_property_roles
    ADD CONSTRAINT fk_tenant_guard_77a6338b32863eef FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY user_property_roles
    ADD CONSTRAINT fk_tenant_guard_e3d955643fb00e1b FOREIGN KEY (tenant_id, user_id) REFERENCES users(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY user_tenant_roles
    ADD CONSTRAINT fk_tenant_guard_08351842dc242715 FOREIGN KEY (tenant_id, tenant_role_id) REFERENCES tenant_roles(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY user_tenant_roles
    ADD CONSTRAINT fk_tenant_guard_3c11c5990022fd82 FOREIGN KEY (tenant_id, assigned_by) REFERENCES users(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY user_tenant_roles
    ADD CONSTRAINT fk_tenant_guard_b6cccfffe6794500 FOREIGN KEY (tenant_id, user_id) REFERENCES users(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY users
    ADD CONSTRAINT fk_tenant_guard_1b9bd3ed7ca959e2 FOREIGN KEY (tenant_id, department_id) REFERENCES departments(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY users
    ADD CONSTRAINT fk_tenant_guard_9152dd0a290e3035 FOREIGN KEY (tenant_id, created_by) REFERENCES users(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY work_orders
    ADD CONSTRAINT fk_tenant_guard_258e673931a1f76d FOREIGN KEY (tenant_id, assigned_to) REFERENCES employees(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY work_orders
    ADD CONSTRAINT fk_tenant_guard_622f9d36fa4024ea FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY work_orders
    ADD CONSTRAINT fk_tenant_guard_7a44470deec05cc2 FOREIGN KEY (tenant_id, request_id) REFERENCES maintenance_requests(tenant_id, id) DEFERRABLE;
ALTER TABLE ONLY work_orders
    ADD CONSTRAINT fk_tenant_guard_bfb8775ff4923911 FOREIGN KEY (tenant_id, verified_by) REFERENCES users(tenant_id, id) DEFERRABLE;

-- Tenant-safe relationship guards for high-risk transactional paths.
-- These complement the simple UUID FKs above and prevent cross-tenant links.
ALTER TABLE ONLY room_types
    ADD CONSTRAINT fk_room_types_tenant_property FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY rooms
    ADD CONSTRAINT fk_rooms_tenant_property FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY rooms
    ADD CONSTRAINT fk_rooms_tenant_room_type FOREIGN KEY (tenant_id, room_type_id) REFERENCES room_types(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY availability_calendar
    ADD CONSTRAINT fk_availability_calendar_tenant_property FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY availability_calendar
    ADD CONSTRAINT fk_availability_calendar_tenant_room_type FOREIGN KEY (tenant_id, room_type_id) REFERENCES room_types(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY availability_locks
    ADD CONSTRAINT fk_availability_locks_tenant_property FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY availability_locks
    ADD CONSTRAINT fk_availability_locks_tenant_property_room_type FOREIGN KEY (tenant_id, property_id, room_type_id) REFERENCES room_types(tenant_id, property_id, id) DEFERRABLE;

ALTER TABLE ONLY revenue_centers
    ADD CONSTRAINT fk_revenue_centers_tenant_property FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY booking_engine_sites
    ADD CONSTRAINT fk_booking_engine_sites_tenant_property FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY booking_payment_attempts
    ADD CONSTRAINT fk_booking_payment_attempts_tenant_property FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY booking_payment_attempts
    ADD CONSTRAINT fk_booking_payment_attempts_tenant_session FOREIGN KEY (tenant_id, session_id) REFERENCES booking_sessions(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY booking_session_rooms
    ADD CONSTRAINT fk_booking_session_rooms_tenant_session FOREIGN KEY (tenant_id, session_id) REFERENCES booking_sessions(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY booking_session_rooms
    ADD CONSTRAINT fk_booking_session_rooms_tenant_room_type FOREIGN KEY (tenant_id, room_type_id) REFERENCES room_types(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY booking_session_rooms
    ADD CONSTRAINT fk_booking_session_rooms_tenant_rate_plan FOREIGN KEY (tenant_id, rate_plan_id) REFERENCES rate_plans(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY booking_session_rooms
    ADD CONSTRAINT fk_booking_session_rooms_tenant_availability_lock FOREIGN KEY (tenant_id, availability_lock_id) REFERENCES availability_locks(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY booking_session_room_nights
    ADD CONSTRAINT fk_booking_session_room_nights_tenant_session FOREIGN KEY (tenant_id, session_id) REFERENCES booking_sessions(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY booking_session_room_nights
    ADD CONSTRAINT fk_booking_session_room_nights_tenant_session_room FOREIGN KEY (tenant_id, session_id, session_room_id) REFERENCES booking_session_rooms(tenant_id, session_id, id) DEFERRABLE;

ALTER TABLE ONLY booking_session_room_nights
    ADD CONSTRAINT fk_booking_session_room_nights_tenant_room_type FOREIGN KEY (tenant_id, room_type_id) REFERENCES room_types(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY booking_session_room_nights
    ADD CONSTRAINT fk_booking_session_room_nights_tenant_rate_plan FOREIGN KEY (tenant_id, rate_plan_id) REFERENCES rate_plans(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY reservations
    ADD CONSTRAINT fk_reservations_tenant_property FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY reservations
    ADD CONSTRAINT fk_reservations_tenant_primary_guest FOREIGN KEY (tenant_id, primary_guest_id) REFERENCES guests(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY reservations
    ADD CONSTRAINT fk_reservations_tenant_rate_plan FOREIGN KEY (tenant_id, rate_plan_id) REFERENCES rate_plans(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY reservations
    ADD CONSTRAINT fk_reservations_tenant_channel FOREIGN KEY (tenant_id, channel_id) REFERENCES channels(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY reservations
    ADD CONSTRAINT fk_reservations_tenant_group FOREIGN KEY (tenant_id, group_id) REFERENCES groups(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY reservation_rooms
    ADD CONSTRAINT fk_reservation_rooms_tenant_reservation FOREIGN KEY (tenant_id, reservation_id) REFERENCES reservations(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY reservation_rooms
    ADD CONSTRAINT fk_reservation_rooms_tenant_room FOREIGN KEY (tenant_id, room_id) REFERENCES rooms(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY reservation_rooms
    ADD CONSTRAINT fk_reservation_rooms_tenant_room_assignment_type FOREIGN KEY (tenant_id, room_id, room_type_id) REFERENCES rooms(tenant_id, id, room_type_id) DEFERRABLE;

ALTER TABLE ONLY reservation_rooms
    ADD CONSTRAINT fk_reservation_rooms_tenant_room_type FOREIGN KEY (tenant_id, room_type_id) REFERENCES room_types(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY reservation_rooms
    ADD CONSTRAINT fk_reservation_rooms_tenant_folio FOREIGN KEY (tenant_id, folio_id) REFERENCES folios(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY folios
    ADD CONSTRAINT fk_folios_tenant_property FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY folios
    ADD CONSTRAINT fk_folios_tenant_reservation FOREIGN KEY (tenant_id, reservation_id) REFERENCES reservations(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY folio_charges
    ADD CONSTRAINT fk_folio_charges_tenant_folio FOREIGN KEY (tenant_id, folio_id) REFERENCES folios(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY folio_charges
    ADD CONSTRAINT fk_folio_charges_tenant_property FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY folio_charges
    ADD CONSTRAINT fk_folio_charges_tenant_property_revenue_center FOREIGN KEY (tenant_id, property_id, revenue_center_id) REFERENCES revenue_centers(tenant_id, property_id, id) DEFERRABLE;

ALTER TABLE ONLY folio_payments
    ADD CONSTRAINT fk_folio_payments_tenant_folio FOREIGN KEY (tenant_id, folio_id) REFERENCES folios(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY invoices
    ADD CONSTRAINT fk_invoices_tenant_property FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY invoices
    ADD CONSTRAINT fk_invoices_tenant_folio FOREIGN KEY (tenant_id, folio_id) REFERENCES folios(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY invoices
    ADD CONSTRAINT fk_invoices_tenant_company FOREIGN KEY (tenant_id, company_id) REFERENCES companies(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY invoice_items
    ADD CONSTRAINT fk_invoice_items_tenant_invoice FOREIGN KEY (tenant_id, invoice_id) REFERENCES invoices(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY invoice_items
    ADD CONSTRAINT fk_invoice_items_tenant_property FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY invoice_items
    ADD CONSTRAINT fk_invoice_items_tenant_property_revenue_center FOREIGN KEY (tenant_id, property_id, revenue_center_id) REFERENCES revenue_centers(tenant_id, property_id, id) DEFERRABLE;

ALTER TABLE ONLY outlets
    ADD CONSTRAINT fk_outlets_tenant_property FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY outlets
    ADD CONSTRAINT fk_outlets_tenant_property_revenue_center FOREIGN KEY (tenant_id, property_id, revenue_center_id) REFERENCES revenue_centers(tenant_id, property_id, id) DEFERRABLE;

ALTER TABLE ONLY pos_sessions
    ADD CONSTRAINT fk_pos_sessions_tenant_outlet FOREIGN KEY (tenant_id, outlet_id) REFERENCES outlets(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY pos_orders
    ADD CONSTRAINT fk_pos_orders_tenant_outlet FOREIGN KEY (tenant_id, outlet_id) REFERENCES outlets(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY pos_orders
    ADD CONSTRAINT fk_pos_orders_tenant_property_outlet FOREIGN KEY (tenant_id, property_id, outlet_id) REFERENCES outlets(tenant_id, property_id, id) DEFERRABLE;

ALTER TABLE ONLY pos_orders
    ADD CONSTRAINT fk_pos_orders_tenant_property FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY pos_orders
    ADD CONSTRAINT fk_pos_orders_tenant_property_revenue_center FOREIGN KEY (tenant_id, property_id, revenue_center_id) REFERENCES revenue_centers(tenant_id, property_id, id) DEFERRABLE;

ALTER TABLE ONLY pos_orders
    ADD CONSTRAINT fk_pos_orders_tenant_folio FOREIGN KEY (tenant_id, folio_id) REFERENCES folios(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY pos_orders
    ADD CONSTRAINT fk_pos_orders_tenant_session FOREIGN KEY (tenant_id, session_id) REFERENCES pos_sessions(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY pos_order_items
    ADD CONSTRAINT fk_pos_order_items_tenant_order FOREIGN KEY (tenant_id, order_id) REFERENCES pos_orders(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY pos_order_items
    ADD CONSTRAINT fk_pos_order_items_tenant_menu_item FOREIGN KEY (tenant_id, menu_item_id) REFERENCES menu_items(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY journal_entry_lines
    ADD CONSTRAINT fk_journal_entry_lines_tenant_property FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY journal_entry_lines
    ADD CONSTRAINT fk_journal_entry_lines_tenant_property_revenue_center FOREIGN KEY (tenant_id, property_id, revenue_center_id) REFERENCES revenue_centers(tenant_id, property_id, id) DEFERRABLE;

ALTER TABLE ONLY corporate_accounts
    ADD CONSTRAINT corporate_accounts_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY corporate_accounts
    ADD CONSTRAINT corporate_accounts_company_id_fkey FOREIGN KEY (company_id) REFERENCES companies(id);

ALTER TABLE ONLY corporate_accounts
    ADD CONSTRAINT corporate_accounts_account_manager_id_fkey FOREIGN KEY (account_manager_id) REFERENCES users(id);

ALTER TABLE ONLY corporate_accounts
    ADD CONSTRAINT corporate_accounts_default_billing_contact_id_fkey FOREIGN KEY (default_billing_contact_id) REFERENCES corporate_account_contacts(id);

ALTER TABLE ONLY corporate_account_contacts
    ADD CONSTRAINT corporate_account_contacts_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY corporate_account_contacts
    ADD CONSTRAINT corporate_account_contacts_corporate_account_id_fkey FOREIGN KEY (corporate_account_id) REFERENCES corporate_accounts(id) ON DELETE CASCADE;

ALTER TABLE ONLY corporate_account_limits
    ADD CONSTRAINT corporate_account_limits_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY corporate_account_limits
    ADD CONSTRAINT corporate_account_limits_corporate_account_id_fkey FOREIGN KEY (corporate_account_id) REFERENCES corporate_accounts(id) ON DELETE CASCADE;

ALTER TABLE ONLY corporate_account_limits
    ADD CONSTRAINT corporate_account_limits_property_id_fkey FOREIGN KEY (property_id) REFERENCES properties(id);

ALTER TABLE ONLY corporate_account_limits
    ADD CONSTRAINT corporate_account_limits_approved_by_fkey FOREIGN KEY (approved_by) REFERENCES users(id);

ALTER TABLE ONLY corporate_account_holds
    ADD CONSTRAINT corporate_account_holds_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY corporate_account_holds
    ADD CONSTRAINT corporate_account_holds_corporate_account_id_fkey FOREIGN KEY (corporate_account_id) REFERENCES corporate_accounts(id) ON DELETE CASCADE;

ALTER TABLE ONLY corporate_account_holds
    ADD CONSTRAINT corporate_account_holds_placed_by_fkey FOREIGN KEY (placed_by) REFERENCES users(id);

ALTER TABLE ONLY corporate_account_holds
    ADD CONSTRAINT corporate_account_holds_released_by_fkey FOREIGN KEY (released_by) REFERENCES users(id);

ALTER TABLE ONLY corporate_statements
    ADD CONSTRAINT corporate_statements_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY corporate_statements
    ADD CONSTRAINT corporate_statements_corporate_account_id_fkey FOREIGN KEY (corporate_account_id) REFERENCES corporate_accounts(id);

ALTER TABLE ONLY corporate_statements
    ADD CONSTRAINT corporate_statements_created_by_fkey FOREIGN KEY (created_by) REFERENCES users(id);

ALTER TABLE ONLY corporate_statement_items
    ADD CONSTRAINT corporate_statement_items_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY corporate_statement_items
    ADD CONSTRAINT corporate_statement_items_statement_id_fkey FOREIGN KEY (statement_id) REFERENCES corporate_statements(id) ON DELETE CASCADE;

ALTER TABLE ONLY corporate_statement_items
    ADD CONSTRAINT corporate_statement_items_invoice_id_fkey FOREIGN KEY (invoice_id) REFERENCES invoices(id);

ALTER TABLE ONLY corporate_statement_items
    ADD CONSTRAINT corporate_statement_items_folio_payment_id_fkey FOREIGN KEY (folio_payment_id) REFERENCES folio_payments(id);

ALTER TABLE ONLY corporate_statement_items
    ADD CONSTRAINT corporate_statement_items_credit_note_id_fkey FOREIGN KEY (credit_note_id) REFERENCES credit_notes(id);

ALTER TABLE ONLY credit_notes
    ADD CONSTRAINT credit_notes_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY credit_notes
    ADD CONSTRAINT credit_notes_corporate_account_id_fkey FOREIGN KEY (corporate_account_id) REFERENCES corporate_accounts(id);

ALTER TABLE ONLY credit_notes
    ADD CONSTRAINT credit_notes_company_id_fkey FOREIGN KEY (company_id) REFERENCES companies(id);

ALTER TABLE ONLY credit_notes
    ADD CONSTRAINT credit_notes_invoice_id_fkey FOREIGN KEY (invoice_id) REFERENCES invoices(id);

ALTER TABLE ONLY credit_notes
    ADD CONSTRAINT credit_notes_approved_by_fkey FOREIGN KEY (approved_by) REFERENCES users(id);

ALTER TABLE ONLY credit_notes
    ADD CONSTRAINT credit_notes_created_by_fkey FOREIGN KEY (created_by) REFERENCES users(id);

ALTER TABLE ONLY ar_allocations
    ADD CONSTRAINT ar_allocations_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY ar_allocations
    ADD CONSTRAINT ar_allocations_corporate_account_id_fkey FOREIGN KEY (corporate_account_id) REFERENCES corporate_accounts(id);

ALTER TABLE ONLY ar_allocations
    ADD CONSTRAINT ar_allocations_invoice_id_fkey FOREIGN KEY (invoice_id) REFERENCES invoices(id);

ALTER TABLE ONLY ar_allocations
    ADD CONSTRAINT ar_allocations_folio_payment_id_fkey FOREIGN KEY (folio_payment_id) REFERENCES folio_payments(id);

ALTER TABLE ONLY ar_allocations
    ADD CONSTRAINT ar_allocations_credit_note_id_fkey FOREIGN KEY (credit_note_id) REFERENCES credit_notes(id);

ALTER TABLE ONLY ar_allocations
    ADD CONSTRAINT ar_allocations_allocated_by_fkey FOREIGN KEY (allocated_by) REFERENCES users(id);

ALTER TABLE ONLY payment_providers
    ADD CONSTRAINT payment_providers_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY payment_provider_accounts
    ADD CONSTRAINT payment_provider_accounts_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY payment_provider_accounts
    ADD CONSTRAINT payment_provider_accounts_property_id_fkey FOREIGN KEY (property_id) REFERENCES properties(id);

ALTER TABLE ONLY payment_provider_accounts
    ADD CONSTRAINT payment_provider_accounts_provider_id_fkey FOREIGN KEY (provider_id) REFERENCES payment_providers(id);

ALTER TABLE ONLY payment_webhook_events
    ADD CONSTRAINT payment_webhook_events_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY payment_webhook_events
    ADD CONSTRAINT payment_webhook_events_provider_account_id_fkey FOREIGN KEY (provider_account_id) REFERENCES payment_provider_accounts(id);

ALTER TABLE ONLY payment_transactions
    ADD CONSTRAINT payment_transactions_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY payment_transactions
    ADD CONSTRAINT payment_transactions_property_id_fkey FOREIGN KEY (property_id) REFERENCES properties(id);

ALTER TABLE ONLY payment_transactions
    ADD CONSTRAINT payment_transactions_provider_account_id_fkey FOREIGN KEY (provider_account_id) REFERENCES payment_provider_accounts(id);

ALTER TABLE ONLY payment_transactions
    ADD CONSTRAINT payment_transactions_webhook_event_id_fkey FOREIGN KEY (webhook_event_id) REFERENCES payment_webhook_events(id);

ALTER TABLE ONLY payment_transactions
    ADD CONSTRAINT payment_transactions_folio_payment_id_fkey FOREIGN KEY (folio_payment_id) REFERENCES folio_payments(id);

ALTER TABLE ONLY payment_transactions
    ADD CONSTRAINT payment_transactions_reservation_deposit_id_fkey FOREIGN KEY (reservation_deposit_id) REFERENCES reservation_deposits(id);

ALTER TABLE ONLY payment_reconciliations
    ADD CONSTRAINT payment_reconciliations_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY payment_reconciliations
    ADD CONSTRAINT payment_reconciliations_property_id_fkey FOREIGN KEY (property_id) REFERENCES properties(id);

ALTER TABLE ONLY payment_reconciliations
    ADD CONSTRAINT payment_reconciliations_provider_account_id_fkey FOREIGN KEY (provider_account_id) REFERENCES payment_provider_accounts(id);

ALTER TABLE ONLY payment_reconciliations
    ADD CONSTRAINT payment_reconciliations_reconciled_by_fkey FOREIGN KEY (reconciled_by) REFERENCES users(id);

ALTER TABLE ONLY payment_reconciliation_items
    ADD CONSTRAINT payment_reconciliation_items_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY payment_reconciliation_items
    ADD CONSTRAINT payment_reconciliation_items_reconciliation_id_fkey FOREIGN KEY (reconciliation_id) REFERENCES payment_reconciliations(id) ON DELETE CASCADE;

ALTER TABLE ONLY payment_reconciliation_items
    ADD CONSTRAINT payment_reconciliation_items_payment_transaction_id_fkey FOREIGN KEY (payment_transaction_id) REFERENCES payment_transactions(id);

ALTER TABLE ONLY payment_reconciliation_items
    ADD CONSTRAINT payment_reconciliation_items_folio_payment_id_fkey FOREIGN KEY (folio_payment_id) REFERENCES folio_payments(id);

ALTER TABLE ONLY mobile_money_disbursements
    ADD CONSTRAINT mobile_money_disbursements_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY mobile_money_disbursements
    ADD CONSTRAINT mobile_money_disbursements_provider_account_id_fkey FOREIGN KEY (provider_account_id) REFERENCES payment_provider_accounts(id);

ALTER TABLE ONLY mobile_money_disbursements
    ADD CONSTRAINT mobile_money_disbursements_payment_transaction_id_fkey FOREIGN KEY (payment_transaction_id) REFERENCES payment_transactions(id);

ALTER TABLE ONLY mobile_money_disbursements
    ADD CONSTRAINT mobile_money_disbursements_supplier_id_fkey FOREIGN KEY (supplier_id) REFERENCES suppliers(id);

ALTER TABLE ONLY mobile_money_disbursements
    ADD CONSTRAINT mobile_money_disbursements_payroll_record_id_fkey FOREIGN KEY (payroll_record_id) REFERENCES payroll_records(id);

ALTER TABLE ONLY mobile_money_disbursements
    ADD CONSTRAINT mobile_money_disbursements_approved_by_fkey FOREIGN KEY (approved_by) REFERENCES users(id);

ALTER TABLE ONLY mobile_money_disbursements
    ADD CONSTRAINT mobile_money_disbursements_created_by_fkey FOREIGN KEY (created_by) REFERENCES users(id);

ALTER TABLE ONLY fiscal_provider_configs
    ADD CONSTRAINT fiscal_provider_configs_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY fiscal_provider_configs
    ADD CONSTRAINT fiscal_provider_configs_property_id_fkey FOREIGN KEY (property_id) REFERENCES properties(id);

ALTER TABLE ONLY fiscal_provider_configs
    ADD CONSTRAINT fiscal_provider_configs_provider_id_fkey FOREIGN KEY (provider_id) REFERENCES fiscal_providers(id);

ALTER TABLE ONLY fiscal_submission_batches
    ADD CONSTRAINT fiscal_submission_batches_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY fiscal_submission_batches
    ADD CONSTRAINT fiscal_submission_batches_property_id_fkey FOREIGN KEY (property_id) REFERENCES properties(id);

ALTER TABLE ONLY fiscal_submission_batches
    ADD CONSTRAINT fiscal_submission_batches_provider_config_id_fkey FOREIGN KEY (provider_config_id) REFERENCES fiscal_provider_configs(id);

ALTER TABLE ONLY fiscal_submission_attempts
    ADD CONSTRAINT fiscal_submission_attempts_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY fiscal_submission_attempts
    ADD CONSTRAINT fiscal_submission_attempts_provider_config_id_fkey FOREIGN KEY (provider_config_id) REFERENCES fiscal_provider_configs(id);

ALTER TABLE ONLY fiscal_submission_attempts
    ADD CONSTRAINT fiscal_submission_attempts_batch_id_fkey FOREIGN KEY (batch_id) REFERENCES fiscal_submission_batches(id);

ALTER TABLE ONLY fiscal_submission_attempts
    ADD CONSTRAINT fiscal_submission_attempts_fiscal_receipt_id_fkey FOREIGN KEY (fiscal_receipt_id) REFERENCES fiscal_receipts(id);

ALTER TABLE ONLY fiscal_document_mappings
    ADD CONSTRAINT fiscal_document_mappings_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY fiscal_document_mappings
    ADD CONSTRAINT fiscal_document_mappings_provider_config_id_fkey FOREIGN KEY (provider_config_id) REFERENCES fiscal_provider_configs(id);

ALTER TABLE ONLY fiscal_document_mappings
    ADD CONSTRAINT fiscal_document_mappings_invoice_id_fkey FOREIGN KEY (invoice_id) REFERENCES invoices(id);

ALTER TABLE ONLY fiscal_document_mappings
    ADD CONSTRAINT fiscal_document_mappings_credit_note_id_fkey FOREIGN KEY (credit_note_id) REFERENCES credit_notes(id);

ALTER TABLE ONLY fiscal_document_mappings
    ADD CONSTRAINT fiscal_document_mappings_fiscal_receipt_id_fkey FOREIGN KEY (fiscal_receipt_id) REFERENCES fiscal_receipts(id);

ALTER TABLE ONLY tax_jurisdictions
    ADD CONSTRAINT tax_jurisdictions_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY tax_jurisdictions
    ADD CONSTRAINT tax_jurisdictions_fiscal_provider_id_fkey FOREIGN KEY (fiscal_provider_id) REFERENCES fiscal_providers(id);

ALTER TABLE ONLY tax_report_runs
    ADD CONSTRAINT tax_report_runs_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY tax_report_runs
    ADD CONSTRAINT tax_report_runs_property_id_fkey FOREIGN KEY (property_id) REFERENCES properties(id);

ALTER TABLE ONLY tax_report_runs
    ADD CONSTRAINT tax_report_runs_jurisdiction_id_fkey FOREIGN KEY (jurisdiction_id) REFERENCES tax_jurisdictions(id);

ALTER TABLE ONLY tax_report_runs
    ADD CONSTRAINT tax_report_runs_generated_by_fkey FOREIGN KEY (generated_by) REFERENCES users(id);

ALTER TABLE ONLY tax_report_lines
    ADD CONSTRAINT tax_report_lines_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY tax_report_lines
    ADD CONSTRAINT tax_report_lines_report_run_id_fkey FOREIGN KEY (report_run_id) REFERENCES tax_report_runs(id) ON DELETE CASCADE;

ALTER TABLE ONLY tax_report_lines
    ADD CONSTRAINT tax_report_lines_tax_rate_id_fkey FOREIGN KEY (tax_rate_id) REFERENCES tax_rates(id);

ALTER TABLE ONLY tax_report_lines
    ADD CONSTRAINT tax_report_lines_invoice_id_fkey FOREIGN KEY (invoice_id) REFERENCES invoices(id);

ALTER TABLE ONLY tax_report_lines
    ADD CONSTRAINT tax_report_lines_credit_note_id_fkey FOREIGN KEY (credit_note_id) REFERENCES credit_notes(id);

-- Tenant-safe relationship guards for corporate AR, payments, and fiscal adapters.
ALTER TABLE ONLY corporate_accounts
    ADD CONSTRAINT fk_corporate_accounts_tenant_company FOREIGN KEY (tenant_id, company_id) REFERENCES companies(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY corporate_accounts
    ADD CONSTRAINT fk_corporate_accounts_tenant_account_manager FOREIGN KEY (tenant_id, account_manager_id) REFERENCES users(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY corporate_accounts
    ADD CONSTRAINT fk_corporate_accounts_tenant_billing_contact FOREIGN KEY (tenant_id, default_billing_contact_id) REFERENCES corporate_account_contacts(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY corporate_account_contacts
    ADD CONSTRAINT fk_corporate_account_contacts_tenant_account FOREIGN KEY (tenant_id, corporate_account_id) REFERENCES corporate_accounts(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY corporate_account_limits
    ADD CONSTRAINT fk_corporate_account_limits_tenant_account FOREIGN KEY (tenant_id, corporate_account_id) REFERENCES corporate_accounts(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY corporate_account_limits
    ADD CONSTRAINT fk_corporate_account_limits_tenant_property FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY corporate_account_limits
    ADD CONSTRAINT fk_corporate_account_limits_tenant_approved_by FOREIGN KEY (tenant_id, approved_by) REFERENCES users(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY corporate_account_holds
    ADD CONSTRAINT fk_corporate_account_holds_tenant_account FOREIGN KEY (tenant_id, corporate_account_id) REFERENCES corporate_accounts(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY corporate_account_holds
    ADD CONSTRAINT fk_corporate_account_holds_tenant_placed_by FOREIGN KEY (tenant_id, placed_by) REFERENCES users(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY corporate_account_holds
    ADD CONSTRAINT fk_corporate_account_holds_tenant_released_by FOREIGN KEY (tenant_id, released_by) REFERENCES users(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY corporate_statements
    ADD CONSTRAINT fk_corporate_statements_tenant_account FOREIGN KEY (tenant_id, corporate_account_id) REFERENCES corporate_accounts(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY corporate_statements
    ADD CONSTRAINT fk_corporate_statements_tenant_created_by FOREIGN KEY (tenant_id, created_by) REFERENCES users(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY corporate_statement_items
    ADD CONSTRAINT fk_corporate_statement_items_tenant_statement FOREIGN KEY (tenant_id, statement_id) REFERENCES corporate_statements(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY corporate_statement_items
    ADD CONSTRAINT fk_corporate_statement_items_tenant_invoice FOREIGN KEY (tenant_id, invoice_id) REFERENCES invoices(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY corporate_statement_items
    ADD CONSTRAINT fk_corporate_statement_items_tenant_folio_payment FOREIGN KEY (tenant_id, folio_payment_id) REFERENCES folio_payments(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY corporate_statement_items
    ADD CONSTRAINT fk_corporate_statement_items_tenant_credit_note FOREIGN KEY (tenant_id, credit_note_id) REFERENCES credit_notes(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY credit_notes
    ADD CONSTRAINT fk_credit_notes_tenant_account FOREIGN KEY (tenant_id, corporate_account_id) REFERENCES corporate_accounts(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY credit_notes
    ADD CONSTRAINT fk_credit_notes_tenant_company FOREIGN KEY (tenant_id, company_id) REFERENCES companies(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY credit_notes
    ADD CONSTRAINT fk_credit_notes_tenant_invoice FOREIGN KEY (tenant_id, invoice_id) REFERENCES invoices(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY credit_notes
    ADD CONSTRAINT fk_credit_notes_tenant_approved_by FOREIGN KEY (tenant_id, approved_by) REFERENCES users(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY credit_notes
    ADD CONSTRAINT fk_credit_notes_tenant_created_by FOREIGN KEY (tenant_id, created_by) REFERENCES users(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY ar_allocations
    ADD CONSTRAINT fk_ar_allocations_tenant_account FOREIGN KEY (tenant_id, corporate_account_id) REFERENCES corporate_accounts(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY ar_allocations
    ADD CONSTRAINT fk_ar_allocations_tenant_invoice FOREIGN KEY (tenant_id, invoice_id) REFERENCES invoices(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY ar_allocations
    ADD CONSTRAINT fk_ar_allocations_tenant_folio_payment FOREIGN KEY (tenant_id, folio_payment_id) REFERENCES folio_payments(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY ar_allocations
    ADD CONSTRAINT fk_ar_allocations_tenant_credit_note FOREIGN KEY (tenant_id, credit_note_id) REFERENCES credit_notes(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY ar_allocations
    ADD CONSTRAINT fk_ar_allocations_tenant_allocated_by FOREIGN KEY (tenant_id, allocated_by) REFERENCES users(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY ota_room_type_mappings
    ADD CONSTRAINT fk_ota_room_type_mappings_tenant_connection FOREIGN KEY (tenant_id, channel_connection_id) REFERENCES channel_connections(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY ota_room_type_mappings
    ADD CONSTRAINT fk_ota_room_type_mappings_tenant_room_type FOREIGN KEY (tenant_id, room_type_id) REFERENCES room_types(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY ota_room_type_mappings
    ADD CONSTRAINT fk_ota_room_type_mappings_tenant_rate_plan FOREIGN KEY (tenant_id, rate_plan_id) REFERENCES rate_plans(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY payment_provider_accounts
    ADD CONSTRAINT fk_payment_provider_accounts_tenant_property FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY payment_provider_accounts
    ADD CONSTRAINT fk_payment_provider_accounts_tenant_provider FOREIGN KEY (tenant_id, provider_id) REFERENCES payment_providers(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY payment_webhook_events
    ADD CONSTRAINT fk_payment_webhook_events_tenant_provider_account FOREIGN KEY (tenant_id, provider_account_id) REFERENCES payment_provider_accounts(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY payment_transactions
    ADD CONSTRAINT fk_payment_transactions_tenant_property FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY payment_transactions
    ADD CONSTRAINT fk_payment_transactions_tenant_provider_account FOREIGN KEY (tenant_id, provider_account_id) REFERENCES payment_provider_accounts(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY payment_transactions
    ADD CONSTRAINT fk_payment_transactions_tenant_webhook_event FOREIGN KEY (tenant_id, webhook_event_id) REFERENCES payment_webhook_events(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY payment_transactions
    ADD CONSTRAINT fk_payment_transactions_tenant_folio_payment FOREIGN KEY (tenant_id, folio_payment_id) REFERENCES folio_payments(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY payment_transactions
    ADD CONSTRAINT fk_payment_transactions_tenant_reservation_deposit FOREIGN KEY (tenant_id, reservation_deposit_id) REFERENCES reservation_deposits(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY payment_reconciliations
    ADD CONSTRAINT fk_payment_reconciliations_tenant_property FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY payment_reconciliations
    ADD CONSTRAINT fk_payment_reconciliations_tenant_provider_account FOREIGN KEY (tenant_id, provider_account_id) REFERENCES payment_provider_accounts(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY payment_reconciliations
    ADD CONSTRAINT fk_payment_reconciliations_tenant_reconciled_by FOREIGN KEY (tenant_id, reconciled_by) REFERENCES users(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY payment_reconciliation_items
    ADD CONSTRAINT fk_payment_reconciliation_items_tenant_reconciliation FOREIGN KEY (tenant_id, reconciliation_id) REFERENCES payment_reconciliations(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY payment_reconciliation_items
    ADD CONSTRAINT fk_payment_reconciliation_items_tenant_transaction FOREIGN KEY (tenant_id, payment_transaction_id) REFERENCES payment_transactions(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY payment_reconciliation_items
    ADD CONSTRAINT fk_payment_reconciliation_items_tenant_folio_payment FOREIGN KEY (tenant_id, folio_payment_id) REFERENCES folio_payments(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY mobile_money_disbursements
    ADD CONSTRAINT fk_mobile_money_disbursements_tenant_provider_account FOREIGN KEY (tenant_id, provider_account_id) REFERENCES payment_provider_accounts(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY mobile_money_disbursements
    ADD CONSTRAINT fk_mobile_money_disbursements_tenant_payment_transaction FOREIGN KEY (tenant_id, payment_transaction_id) REFERENCES payment_transactions(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY mobile_money_disbursements
    ADD CONSTRAINT fk_mobile_money_disbursements_tenant_supplier FOREIGN KEY (tenant_id, supplier_id) REFERENCES suppliers(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY mobile_money_disbursements
    ADD CONSTRAINT fk_mobile_money_disbursements_tenant_payroll_record FOREIGN KEY (tenant_id, payroll_record_id) REFERENCES payroll_records(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY mobile_money_disbursements
    ADD CONSTRAINT fk_mobile_money_disbursements_tenant_approved_by FOREIGN KEY (tenant_id, approved_by) REFERENCES users(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY mobile_money_disbursements
    ADD CONSTRAINT fk_mobile_money_disbursements_tenant_created_by FOREIGN KEY (tenant_id, created_by) REFERENCES users(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY fiscal_provider_configs
    ADD CONSTRAINT fk_fiscal_provider_configs_tenant_property FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY fiscal_submission_batches
    ADD CONSTRAINT fk_fiscal_submission_batches_tenant_property FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY fiscal_submission_batches
    ADD CONSTRAINT fk_fiscal_submission_batches_tenant_provider_config FOREIGN KEY (tenant_id, provider_config_id) REFERENCES fiscal_provider_configs(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY fiscal_submission_attempts
    ADD CONSTRAINT fk_fiscal_submission_attempts_tenant_provider_config FOREIGN KEY (tenant_id, provider_config_id) REFERENCES fiscal_provider_configs(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY fiscal_submission_attempts
    ADD CONSTRAINT fk_fiscal_submission_attempts_tenant_batch FOREIGN KEY (tenant_id, batch_id) REFERENCES fiscal_submission_batches(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY fiscal_submission_attempts
    ADD CONSTRAINT fk_fiscal_submission_attempts_tenant_receipt FOREIGN KEY (tenant_id, fiscal_receipt_id) REFERENCES fiscal_receipts(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY fiscal_document_mappings
    ADD CONSTRAINT fk_fiscal_document_mappings_tenant_provider_config FOREIGN KEY (tenant_id, provider_config_id) REFERENCES fiscal_provider_configs(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY fiscal_document_mappings
    ADD CONSTRAINT fk_fiscal_document_mappings_tenant_invoice FOREIGN KEY (tenant_id, invoice_id) REFERENCES invoices(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY fiscal_document_mappings
    ADD CONSTRAINT fk_fiscal_document_mappings_tenant_credit_note FOREIGN KEY (tenant_id, credit_note_id) REFERENCES credit_notes(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY fiscal_document_mappings
    ADD CONSTRAINT fk_fiscal_document_mappings_tenant_receipt FOREIGN KEY (tenant_id, fiscal_receipt_id) REFERENCES fiscal_receipts(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY tax_report_runs
    ADD CONSTRAINT fk_tax_report_runs_tenant_property FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY tax_report_runs
    ADD CONSTRAINT fk_tax_report_runs_tenant_generated_by FOREIGN KEY (tenant_id, generated_by) REFERENCES users(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY tax_report_runs
    ADD CONSTRAINT fk_tax_report_runs_tenant_jurisdiction FOREIGN KEY (tenant_id, jurisdiction_id) REFERENCES tax_jurisdictions(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY tax_report_lines
    ADD CONSTRAINT fk_tax_report_lines_tenant_report_run FOREIGN KEY (tenant_id, report_run_id) REFERENCES tax_report_runs(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY tax_report_lines
    ADD CONSTRAINT fk_tax_report_lines_tenant_tax_rate FOREIGN KEY (tenant_id, tax_rate_id) REFERENCES tax_rates(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY tax_report_lines
    ADD CONSTRAINT fk_tax_report_lines_tenant_invoice FOREIGN KEY (tenant_id, invoice_id) REFERENCES invoices(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY tax_report_lines
    ADD CONSTRAINT fk_tax_report_lines_tenant_credit_note FOREIGN KEY (tenant_id, credit_note_id) REFERENCES credit_notes(tenant_id, id) DEFERRABLE;

-- Tenant-safe relationship guards for enterprise PMS domains.
ALTER TABLE ONLY accounting_accounts
    ADD CONSTRAINT fk_accounting_accounts_tenant_property FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY accounting_accounts
    ADD CONSTRAINT fk_accounting_accounts_tenant_parent FOREIGN KEY (tenant_id, parent_account_id) REFERENCES accounting_accounts(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY journal_entries
    ADD CONSTRAINT fk_journal_entries_tenant_property FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY journal_entries
    ADD CONSTRAINT fk_journal_entries_tenant_posted_by FOREIGN KEY (tenant_id, posted_by) REFERENCES users(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY journal_entries
    ADD CONSTRAINT fk_journal_entries_tenant_voided_by FOREIGN KEY (tenant_id, voided_by) REFERENCES users(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY journal_entry_lines
    ADD CONSTRAINT fk_journal_entry_lines_tenant_entry FOREIGN KEY (tenant_id, journal_entry_id) REFERENCES journal_entries(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY journal_entry_lines
    ADD CONSTRAINT fk_journal_entry_lines_tenant_account FOREIGN KEY (tenant_id, account_id) REFERENCES accounting_accounts(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY booking_policies
    ADD CONSTRAINT fk_booking_policies_tenant_property FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY reservation_policy_snapshots
    ADD CONSTRAINT fk_reservation_policy_snapshots_tenant_reservation FOREIGN KEY (tenant_id, reservation_id) REFERENCES reservations(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY reservation_policy_snapshots
    ADD CONSTRAINT fk_reservation_policy_snapshots_tenant_policy FOREIGN KEY (tenant_id, policy_id) REFERENCES booking_policies(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY reservation_deposits
    ADD CONSTRAINT fk_reservation_deposits_tenant_property FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY reservation_deposits
    ADD CONSTRAINT fk_reservation_deposits_tenant_reservation FOREIGN KEY (tenant_id, reservation_id) REFERENCES reservations(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY reservation_deposits
    ADD CONSTRAINT fk_reservation_deposits_tenant_folio FOREIGN KEY (tenant_id, folio_id) REFERENCES folios(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY reservation_deposits
    ADD CONSTRAINT fk_reservation_deposits_tenant_policy_snapshot FOREIGN KEY (tenant_id, policy_snapshot_id) REFERENCES reservation_policy_snapshots(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY folio_charge_taxes
    ADD CONSTRAINT fk_folio_charge_taxes_tenant_charge FOREIGN KEY (tenant_id, folio_charge_id) REFERENCES folio_charges(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY folio_charge_taxes
    ADD CONSTRAINT fk_folio_charge_taxes_tenant_tax_rate FOREIGN KEY (tenant_id, tax_rate_id) REFERENCES tax_rates(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY invoice_item_taxes
    ADD CONSTRAINT fk_invoice_item_taxes_tenant_item FOREIGN KEY (tenant_id, invoice_item_id) REFERENCES invoice_items(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY invoice_item_taxes
    ADD CONSTRAINT fk_invoice_item_taxes_tenant_tax_rate FOREIGN KEY (tenant_id, tax_rate_id) REFERENCES tax_rates(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY reservation_room_nights
    ADD CONSTRAINT fk_reservation_room_nights_tenant_reservation FOREIGN KEY (tenant_id, reservation_id) REFERENCES reservations(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY reservation_room_nights
    ADD CONSTRAINT fk_reservation_room_nights_tenant_reservation_room FOREIGN KEY (tenant_id, reservation_room_id) REFERENCES reservation_rooms(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY reservation_room_nights
    ADD CONSTRAINT fk_reservation_room_nights_tenant_room FOREIGN KEY (tenant_id, room_id) REFERENCES rooms(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY reservation_room_nights
    ADD CONSTRAINT fk_reservation_room_nights_tenant_room_type FOREIGN KEY (tenant_id, room_type_id) REFERENCES room_types(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY reservation_room_nights
    ADD CONSTRAINT fk_reservation_room_nights_tenant_rate_plan FOREIGN KEY (tenant_id, rate_plan_id) REFERENCES rate_plans(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY room_moves
    ADD CONSTRAINT fk_room_moves_tenant_reservation FOREIGN KEY (tenant_id, reservation_id) REFERENCES reservations(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY room_moves
    ADD CONSTRAINT fk_room_moves_tenant_stay FOREIGN KEY (tenant_id, stay_id) REFERENCES stays(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY room_moves
    ADD CONSTRAINT fk_room_moves_tenant_from_room FOREIGN KEY (tenant_id, from_room_id) REFERENCES rooms(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY room_moves
    ADD CONSTRAINT fk_room_moves_tenant_to_room FOREIGN KEY (tenant_id, to_room_id) REFERENCES rooms(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY room_moves
    ADD CONSTRAINT fk_room_moves_tenant_approved_by FOREIGN KEY (tenant_id, approved_by) REFERENCES users(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY room_moves
    ADD CONSTRAINT fk_room_moves_tenant_moved_by FOREIGN KEY (tenant_id, moved_by) REFERENCES users(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY housekeeping_tasks
    ADD CONSTRAINT fk_housekeeping_tasks_tenant_room FOREIGN KEY (tenant_id, room_id) REFERENCES rooms(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY leave_requests
    ADD CONSTRAINT fk_leave_requests_tenant_employee FOREIGN KEY (tenant_id, employee_id) REFERENCES employees(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY leave_requests
    ADD CONSTRAINT fk_leave_requests_tenant_requested_by FOREIGN KEY (tenant_id, requested_by) REFERENCES users(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY leave_requests
    ADD CONSTRAINT fk_leave_requests_tenant_approved_by FOREIGN KEY (tenant_id, approved_by) REFERENCES users(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY staff_rosters
    ADD CONSTRAINT fk_staff_rosters_tenant_property FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY staff_rosters
    ADD CONSTRAINT fk_staff_rosters_tenant_employee FOREIGN KEY (tenant_id, employee_id) REFERENCES employees(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY staff_rosters
    ADD CONSTRAINT fk_staff_rosters_tenant_shift_template FOREIGN KEY (tenant_id, shift_template_id) REFERENCES shift_templates(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY staff_rosters
    ADD CONSTRAINT fk_staff_rosters_tenant_created_by FOREIGN KEY (tenant_id, created_by) REFERENCES users(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY labor_forecasts
    ADD CONSTRAINT fk_labor_forecasts_tenant_property FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY labor_forecasts
    ADD CONSTRAINT fk_labor_forecasts_tenant_department FOREIGN KEY (tenant_id, department_id) REFERENCES departments(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY sales_leads
    ADD CONSTRAINT fk_sales_leads_tenant_property FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY sales_leads
    ADD CONSTRAINT fk_sales_leads_tenant_company FOREIGN KEY (tenant_id, company_id) REFERENCES companies(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY sales_leads
    ADD CONSTRAINT fk_sales_leads_tenant_guest FOREIGN KEY (tenant_id, guest_id) REFERENCES guests(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY sales_leads
    ADD CONSTRAINT fk_sales_leads_tenant_assigned_to FOREIGN KEY (tenant_id, assigned_to) REFERENCES users(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY sales_activities
    ADD CONSTRAINT fk_sales_activities_tenant_lead FOREIGN KEY (tenant_id, lead_id) REFERENCES sales_leads(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY sales_activities
    ADD CONSTRAINT fk_sales_activities_tenant_company FOREIGN KEY (tenant_id, company_id) REFERENCES companies(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY sales_activities
    ADD CONSTRAINT fk_sales_activities_tenant_guest FOREIGN KEY (tenant_id, guest_id) REFERENCES guests(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY sales_activities
    ADD CONSTRAINT fk_sales_activities_tenant_assigned_to FOREIGN KEY (tenant_id, assigned_to) REFERENCES users(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY corporate_rate_agreements
    ADD CONSTRAINT fk_corporate_rate_agreements_tenant_property FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY corporate_rate_agreements
    ADD CONSTRAINT fk_corporate_rate_agreements_tenant_company FOREIGN KEY (tenant_id, company_id) REFERENCES companies(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY corporate_rate_agreements
    ADD CONSTRAINT fk_corporate_rate_agreements_tenant_rate_plan FOREIGN KEY (tenant_id, rate_plan_id) REFERENCES rate_plans(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY corporate_rate_agreements
    ADD CONSTRAINT fk_corporate_rate_agreements_tenant_created_by FOREIGN KEY (tenant_id, created_by) REFERENCES users(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY tenant_profiles
    ADD CONSTRAINT fk_tenant_profiles_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY tenant_profiles
    ADD CONSTRAINT fk_tenant_profiles_verified_by_platform_user FOREIGN KEY (verified_by_platform_user_id) REFERENCES platform_users(id) DEFERRABLE;

ALTER TABLE ONLY tenant_contacts
    ADD CONSTRAINT fk_tenant_contacts_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY tenant_contacts
    ADD CONSTRAINT fk_tenant_contacts_tenant_user FOREIGN KEY (tenant_id, user_id) REFERENCES users(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY tenant_contact_roles
    ADD CONSTRAINT fk_tenant_contact_roles_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY tenant_contact_roles
    ADD CONSTRAINT fk_tenant_contact_roles_contact FOREIGN KEY (tenant_id, contact_id) REFERENCES tenant_contacts(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY tenant_contact_roles
    ADD CONSTRAINT fk_tenant_contact_roles_property FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY tenant_contact_roles
    ADD CONSTRAINT fk_tenant_contact_roles_role FOREIGN KEY (role_code) REFERENCES contact_role_catalog(role_code) DEFERRABLE;

ALTER TABLE ONLY tenant_contact_roles
    ADD CONSTRAINT fk_tenant_contact_roles_created_by FOREIGN KEY (tenant_id, created_by) REFERENCES users(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY contact_channels
    ADD CONSTRAINT fk_contact_channels_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY contact_channels
    ADD CONSTRAINT fk_contact_channels_contact FOREIGN KEY (tenant_id, contact_id) REFERENCES tenant_contacts(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY communication_consents
    ADD CONSTRAINT fk_communication_consents_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY communication_consents
    ADD CONSTRAINT fk_communication_consents_contact FOREIGN KEY (tenant_id, contact_id) REFERENCES tenant_contacts(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY communication_consents
    ADD CONSTRAINT fk_communication_consents_channel FOREIGN KEY (tenant_id, contact_id, contact_channel_id) REFERENCES contact_channels(tenant_id, contact_id, id) DEFERRABLE;

ALTER TABLE ONLY tenant_verification_cases
    ADD CONSTRAINT fk_tenant_verification_cases_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY tenant_verification_cases
    ADD CONSTRAINT fk_tenant_verification_cases_submitted_by FOREIGN KEY (tenant_id, submitted_by_user_id) REFERENCES users(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY tenant_verification_cases
    ADD CONSTRAINT fk_tenant_verification_cases_assigned_platform_user FOREIGN KEY (assigned_platform_user_id) REFERENCES platform_users(id) DEFERRABLE;

ALTER TABLE ONLY tenant_verification_cases
    ADD CONSTRAINT fk_tenant_verification_cases_approved_by_platform_user FOREIGN KEY (approved_by_platform_user_id) REFERENCES platform_users(id) DEFERRABLE;

ALTER TABLE ONLY tenant_verification_cases
    ADD CONSTRAINT fk_tenant_verification_cases_rejected_by_platform_user FOREIGN KEY (rejected_by_platform_user_id) REFERENCES platform_users(id) DEFERRABLE;

ALTER TABLE ONLY tenant_verification_documents
    ADD CONSTRAINT fk_tenant_verification_documents_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY tenant_verification_documents
    ADD CONSTRAINT fk_tenant_verification_documents_case FOREIGN KEY (tenant_id, verification_case_id) REFERENCES tenant_verification_cases(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY tenant_verification_documents
    ADD CONSTRAINT fk_tenant_verification_documents_verified_by_platform_user FOREIGN KEY (verified_by_platform_user_id) REFERENCES platform_users(id) DEFERRABLE;

ALTER TABLE ONLY report_catalog
    ADD CONSTRAINT fk_report_catalog_module FOREIGN KEY (module_id) REFERENCES module_catalog(module_id) DEFERRABLE;

ALTER TABLE ONLY report_subscriptions
    ADD CONSTRAINT fk_report_subscriptions_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY report_subscriptions
    ADD CONSTRAINT fk_report_subscriptions_property FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY report_subscriptions
    ADD CONSTRAINT fk_report_subscriptions_report FOREIGN KEY (report_code) REFERENCES report_catalog(report_code) DEFERRABLE;

ALTER TABLE ONLY report_subscriptions
    ADD CONSTRAINT fk_report_subscriptions_created_by FOREIGN KEY (tenant_id, created_by) REFERENCES users(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY report_subscription_recipients
    ADD CONSTRAINT fk_report_subscription_recipients_subscription FOREIGN KEY (tenant_id, subscription_id) REFERENCES report_subscriptions(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY report_subscription_recipients
    ADD CONSTRAINT fk_report_subscription_recipients_contact FOREIGN KEY (tenant_id, contact_id) REFERENCES tenant_contacts(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY report_subscription_recipients
    ADD CONSTRAINT fk_report_subscription_recipients_channel FOREIGN KEY (tenant_id, contact_id, contact_channel_id) REFERENCES contact_channels(tenant_id, contact_id, id) DEFERRABLE;

ALTER TABLE ONLY report_runs
    ADD CONSTRAINT fk_report_runs_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) DEFERRABLE;

ALTER TABLE ONLY report_runs
    ADD CONSTRAINT fk_report_runs_property FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY report_runs
    ADD CONSTRAINT fk_report_runs_subscription FOREIGN KEY (tenant_id, subscription_id) REFERENCES report_subscriptions(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY report_runs
    ADD CONSTRAINT fk_report_runs_report FOREIGN KEY (report_code) REFERENCES report_catalog(report_code) DEFERRABLE;

ALTER TABLE ONLY report_deliveries
    ADD CONSTRAINT fk_report_deliveries_report_run FOREIGN KEY (tenant_id, report_run_id) REFERENCES report_runs(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY report_deliveries
    ADD CONSTRAINT fk_report_deliveries_property FOREIGN KEY (tenant_id, property_id) REFERENCES properties(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY report_deliveries
    ADD CONSTRAINT fk_report_deliveries_subscription_recipient FOREIGN KEY (tenant_id, subscription_recipient_id) REFERENCES report_subscription_recipients(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY report_deliveries
    ADD CONSTRAINT fk_report_deliveries_contact FOREIGN KEY (tenant_id, contact_id) REFERENCES tenant_contacts(tenant_id, id) DEFERRABLE;

ALTER TABLE ONLY report_deliveries
    ADD CONSTRAINT fk_report_deliveries_channel FOREIGN KEY (tenant_id, contact_id, contact_channel_id) REFERENCES contact_channels(tenant_id, contact_id, id) DEFERRABLE;


-- ================================================================================
-- 24. ROW LEVEL SECURITY
-- ================================================================================

-- Enable RLS

ALTER TABLE accounting_accounts ENABLE ROW LEVEL SECURITY;

ALTER TABLE allotments ENABLE ROW LEVEL SECURITY;

ALTER TABLE approval_request_steps ENABLE ROW LEVEL SECURITY;

ALTER TABLE approval_requests ENABLE ROW LEVEL SECURITY;

ALTER TABLE approval_workflow_steps ENABLE ROW LEVEL SECURITY;

ALTER TABLE approval_workflows ENABLE ROW LEVEL SECURITY;

ALTER TABLE attendance ENABLE ROW LEVEL SECURITY;

ALTER TABLE audit_logs ENABLE ROW LEVEL SECURITY;

ALTER TABLE feature_flags ENABLE ROW LEVEL SECURITY;

ALTER TABLE idempotency_keys ENABLE ROW LEVEL SECURITY;

ALTER TABLE maintenance_windows ENABLE ROW LEVEL SECURITY;

ALTER TABLE module_catalog ENABLE ROW LEVEL SECURITY;

ALTER TABLE module_dependencies ENABLE ROW LEVEL SECURITY;

ALTER TABLE business_profiles ENABLE ROW LEVEL SECURITY;

ALTER TABLE business_profile_modules ENABLE ROW LEVEL SECURITY;

ALTER TABLE tenant_profiles ENABLE ROW LEVEL SECURITY;

ALTER TABLE contact_role_catalog ENABLE ROW LEVEL SECURITY;

ALTER TABLE tenant_contacts ENABLE ROW LEVEL SECURITY;

ALTER TABLE tenant_contact_roles ENABLE ROW LEVEL SECURITY;

ALTER TABLE contact_channels ENABLE ROW LEVEL SECURITY;

ALTER TABLE communication_consents ENABLE ROW LEVEL SECURITY;

ALTER TABLE tenant_verification_cases ENABLE ROW LEVEL SECURITY;

ALTER TABLE tenant_verification_documents ENABLE ROW LEVEL SECURITY;

ALTER TABLE report_catalog ENABLE ROW LEVEL SECURITY;

ALTER TABLE report_subscriptions ENABLE ROW LEVEL SECURITY;

ALTER TABLE report_subscription_recipients ENABLE ROW LEVEL SECURITY;

ALTER TABLE report_runs ENABLE ROW LEVEL SECURITY;

ALTER TABLE report_deliveries ENABLE ROW LEVEL SECURITY;

ALTER TABLE outbox_events ENABLE ROW LEVEL SECURITY;

ALTER TABLE permission_catalog ENABLE ROW LEVEL SECURITY;

ALTER TABLE platform_alerts ENABLE ROW LEVEL SECURITY;

ALTER TABLE platform_audit_logs ENABLE ROW LEVEL SECURITY;

ALTER TABLE platform_break_glass_access ENABLE ROW LEVEL SECURITY;

ALTER TABLE platform_incidents ENABLE ROW LEVEL SECURITY;

ALTER TABLE platform_jobs ENABLE ROW LEVEL SECURITY;

ALTER TABLE platform_job_runs ENABLE ROW LEVEL SECURITY;

ALTER TABLE platform_permissions ENABLE ROW LEVEL SECURITY;

ALTER TABLE platform_roles ENABLE ROW LEVEL SECURITY;

ALTER TABLE platform_role_permissions ENABLE ROW LEVEL SECURITY;

ALTER TABLE platform_services ENABLE ROW LEVEL SECURITY;

ALTER TABLE platform_sessions ENABLE ROW LEVEL SECURITY;

ALTER TABLE platform_users ENABLE ROW LEVEL SECURITY;

ALTER TABLE platform_user_roles ENABLE ROW LEVEL SECURITY;

ALTER TABLE plan_entitlements ENABLE ROW LEVEL SECURITY;

ALTER TABLE schema_version_history ENABLE ROW LEVEL SECURITY;

ALTER TABLE service_health_checks ENABLE ROW LEVEL SECURITY;

ALTER TABLE support_ticket_notes ENABLE ROW LEVEL SECURITY;

ALTER TABLE support_tickets ENABLE ROW LEVEL SECURITY;

ALTER TABLE tenant_lifecycle_events ENABLE ROW LEVEL SECURITY;

ALTER TABLE tenant_roles ENABLE ROW LEVEL SECURITY;

ALTER TABLE tenant_role_permissions ENABLE ROW LEVEL SECURITY;

ALTER TABLE tenant_usage_snapshots ENABLE ROW LEVEL SECURITY;

ALTER TABLE user_tenant_roles ENABLE ROW LEVEL SECURITY;

ALTER TABLE workflow_catalog ENABLE ROW LEVEL SECURITY;

ALTER TABLE workflow_steps ENABLE ROW LEVEL SECURITY;

ALTER TABLE availability_calendar ENABLE ROW LEVEL SECURITY;

ALTER TABLE availability_locks ENABLE ROW LEVEL SECURITY;

ALTER TABLE booking_engine_configs ENABLE ROW LEVEL SECURITY;

ALTER TABLE booking_engine_sites ENABLE ROW LEVEL SECURITY;

ALTER TABLE booking_policies ENABLE ROW LEVEL SECURITY;

ALTER TABLE booking_payment_attempts ENABLE ROW LEVEL SECURITY;

ALTER TABLE booking_session_room_nights ENABLE ROW LEVEL SECURITY;

ALTER TABLE booking_session_rooms ENABLE ROW LEVEL SECURITY;

ALTER TABLE booking_sessions ENABLE ROW LEVEL SECURITY;

ALTER TABLE buildings ENABLE ROW LEVEL SECURITY;

ALTER TABLE cash_float_movements ENABLE ROW LEVEL SECURITY;

ALTER TABLE channel_connections ENABLE ROW LEVEL SECURITY;

ALTER TABLE channels ENABLE ROW LEVEL SECURITY;

ALTER TABLE companies ENABLE ROW LEVEL SECURITY;

ALTER TABLE corporate_accounts ENABLE ROW LEVEL SECURITY;

ALTER TABLE corporate_account_contacts ENABLE ROW LEVEL SECURITY;

ALTER TABLE corporate_account_limits ENABLE ROW LEVEL SECURITY;

ALTER TABLE corporate_account_holds ENABLE ROW LEVEL SECURITY;

ALTER TABLE corporate_rate_agreements ENABLE ROW LEVEL SECURITY;

ALTER TABLE corporate_statements ENABLE ROW LEVEL SECURITY;

ALTER TABLE corporate_statement_items ENABLE ROW LEVEL SECURITY;

ALTER TABLE credit_notes ENABLE ROW LEVEL SECURITY;

ALTER TABLE ar_allocations ENABLE ROW LEVEL SECURITY;

ALTER TABLE currency_rates ENABLE ROW LEVEL SECURITY;

ALTER TABLE departments ENABLE ROW LEVEL SECURITY;

ALTER TABLE document_sequences ENABLE ROW LEVEL SECURITY;

ALTER TABLE edge_sync_events ENABLE ROW LEVEL SECURITY;

ALTER TABLE employees ENABLE ROW LEVEL SECURITY;

ALTER TABLE event_booking_items ENABLE ROW LEVEL SECURITY;

ALTER TABLE event_bookings ENABLE ROW LEVEL SECURITY;

ALTER TABLE event_packages ENABLE ROW LEVEL SECURITY;

ALTER TABLE event_spaces ENABLE ROW LEVEL SECURITY;

ALTER TABLE events ENABLE ROW LEVEL SECURITY;

ALTER TABLE fiscal_receipts ENABLE ROW LEVEL SECURITY;

ALTER TABLE fiscal_provider_configs ENABLE ROW LEVEL SECURITY;

ALTER TABLE fiscal_submission_batches ENABLE ROW LEVEL SECURITY;

ALTER TABLE fiscal_submission_attempts ENABLE ROW LEVEL SECURITY;

ALTER TABLE fiscal_document_mappings ENABLE ROW LEVEL SECURITY;

ALTER TABLE tax_jurisdictions ENABLE ROW LEVEL SECURITY;

ALTER TABLE tax_report_runs ENABLE ROW LEVEL SECURITY;

ALTER TABLE tax_report_lines ENABLE ROW LEVEL SECURITY;

ALTER TABLE floors ENABLE ROW LEVEL SECURITY;

ALTER TABLE folio_charges ENABLE ROW LEVEL SECURITY;

ALTER TABLE folio_charge_taxes ENABLE ROW LEVEL SECURITY;

ALTER TABLE folio_payments ENABLE ROW LEVEL SECURITY;

ALTER TABLE folios ENABLE ROW LEVEL SECURITY;

ALTER TABLE groups ENABLE ROW LEVEL SECURITY;

ALTER TABLE guest_contacts ENABLE ROW LEVEL SECURITY;

ALTER TABLE guest_documents ENABLE ROW LEVEL SECURITY;

ALTER TABLE guest_feedback ENABLE ROW LEVEL SECURITY;

ALTER TABLE guest_preferences ENABLE ROW LEVEL SECURITY;

ALTER TABLE guests ENABLE ROW LEVEL SECURITY;

ALTER TABLE housekeeping_assignments ENABLE ROW LEVEL SECURITY;

ALTER TABLE housekeeping_tasks ENABLE ROW LEVEL SECURITY;

ALTER TABLE inventory_items ENABLE ROW LEVEL SECURITY;

ALTER TABLE inventory_locations ENABLE ROW LEVEL SECURITY;

ALTER TABLE invoice_items ENABLE ROW LEVEL SECURITY;

ALTER TABLE invoice_item_taxes ENABLE ROW LEVEL SECURITY;

ALTER TABLE invoices ENABLE ROW LEVEL SECURITY;

ALTER TABLE journal_entries ENABLE ROW LEVEL SECURITY;

ALTER TABLE journal_entry_lines ENABLE ROW LEVEL SECURITY;

ALTER TABLE kitchen_tickets ENABLE ROW LEVEL SECURITY;

ALTER TABLE labor_forecasts ENABLE ROW LEVEL SECURITY;

ALTER TABLE leave_requests ENABLE ROW LEVEL SECURITY;

ALTER TABLE lost_and_found ENABLE ROW LEVEL SECURITY;

ALTER TABLE loyalty_accounts ENABLE ROW LEVEL SECURITY;

ALTER TABLE loyalty_transactions ENABLE ROW LEVEL SECURITY;

ALTER TABLE maintenance_requests ENABLE ROW LEVEL SECURITY;

ALTER TABLE menu_categories ENABLE ROW LEVEL SECURITY;

ALTER TABLE menu_item_recipes ENABLE ROW LEVEL SECURITY;

ALTER TABLE menu_items ENABLE ROW LEVEL SECURITY;

ALTER TABLE night_audit_runs ENABLE ROW LEVEL SECURITY;

ALTER TABLE notifications ENABLE ROW LEVEL SECURITY;

ALTER TABLE ota_room_type_mappings ENABLE ROW LEVEL SECURITY;

ALTER TABLE outlets ENABLE ROW LEVEL SECURITY;

ALTER TABLE payroll_records ENABLE ROW LEVEL SECURITY;

ALTER TABLE payroll_runs ENABLE ROW LEVEL SECURITY;

ALTER TABLE permissions ENABLE ROW LEVEL SECURITY;

ALTER TABLE payment_providers ENABLE ROW LEVEL SECURITY;

ALTER TABLE payment_provider_accounts ENABLE ROW LEVEL SECURITY;

ALTER TABLE payment_webhook_events ENABLE ROW LEVEL SECURITY;

ALTER TABLE payment_transactions ENABLE ROW LEVEL SECURITY;

ALTER TABLE payment_reconciliations ENABLE ROW LEVEL SECURITY;

ALTER TABLE payment_reconciliation_items ENABLE ROW LEVEL SECURITY;

ALTER TABLE mobile_money_disbursements ENABLE ROW LEVEL SECURITY;

ALTER TABLE pos_order_items ENABLE ROW LEVEL SECURITY;

ALTER TABLE pos_orders ENABLE ROW LEVEL SECURITY;

ALTER TABLE pos_printer_routes ENABLE ROW LEVEL SECURITY;

ALTER TABLE pos_sessions ENABLE ROW LEVEL SECURITY;

ALTER TABLE pos_terminals ENABLE ROW LEVEL SECURITY;

ALTER TABLE property_billing_settings ENABLE ROW LEVEL SECURITY;

ALTER TABLE property_frontdesk_settings ENABLE ROW LEVEL SECURITY;

ALTER TABLE property_housekeeping_settings ENABLE ROW LEVEL SECURITY;

ALTER TABLE property_module_configs ENABLE ROW LEVEL SECURITY;

ALTER TABLE property_modules ENABLE ROW LEVEL SECURITY;

ALTER TABLE property_pos_settings ENABLE ROW LEVEL SECURITY;

ALTER TABLE property_reservation_settings ENABLE ROW LEVEL SECURITY;

ALTER TABLE pricing_rules ENABLE ROW LEVEL SECURITY;

ALTER TABLE promo_codes ENABLE ROW LEVEL SECURITY;

ALTER TABLE properties ENABLE ROW LEVEL SECURITY;

ALTER TABLE purchase_order_items ENABLE ROW LEVEL SECURITY;

ALTER TABLE purchase_orders ENABLE ROW LEVEL SECURITY;

ALTER TABLE rate_plan_prices ENABLE ROW LEVEL SECURITY;

ALTER TABLE rate_plans ENABLE ROW LEVEL SECURITY;

ALTER TABLE rate_restrictions ENABLE ROW LEVEL SECURITY;

ALTER TABLE refresh_tokens ENABLE ROW LEVEL SECURITY;

ALTER TABLE revenue_centers ENABLE ROW LEVEL SECURITY;

ALTER TABLE reservation_guests ENABLE ROW LEVEL SECURITY;

ALTER TABLE reservation_notes ENABLE ROW LEVEL SECURITY;

ALTER TABLE reservation_deposits ENABLE ROW LEVEL SECURITY;

ALTER TABLE reservation_policy_snapshots ENABLE ROW LEVEL SECURITY;

ALTER TABLE reservation_room_nights ENABLE ROW LEVEL SECURITY;

ALTER TABLE reservation_rooms ENABLE ROW LEVEL SECURITY;

ALTER TABLE reservations ENABLE ROW LEVEL SECURITY;

ALTER TABLE role_permissions ENABLE ROW LEVEL SECURITY;

ALTER TABLE roles ENABLE ROW LEVEL SECURITY;

ALTER TABLE room_moves ENABLE ROW LEVEL SECURITY;

ALTER TABLE room_status_log ENABLE ROW LEVEL SECURITY;

ALTER TABLE room_types ENABLE ROW LEVEL SECURITY;

ALTER TABLE rooms ENABLE ROW LEVEL SECURITY;

ALTER TABLE sales_activities ENABLE ROW LEVEL SECURITY;

ALTER TABLE sales_leads ENABLE ROW LEVEL SECURITY;

ALTER TABLE shift_handovers ENABLE ROW LEVEL SECURITY;

ALTER TABLE shift_templates ENABLE ROW LEVEL SECURITY;

ALTER TABLE staff_rosters ENABLE ROW LEVEL SECURITY;

ALTER TABLE stays ENABLE ROW LEVEL SECURITY;

ALTER TABLE stock_levels ENABLE ROW LEVEL SECURITY;

ALTER TABLE stock_movements ENABLE ROW LEVEL SECURITY;

ALTER TABLE suppliers ENABLE ROW LEVEL SECURITY;

ALTER TABLE tax_rates ENABLE ROW LEVEL SECURITY;

ALTER TABLE tenant_configs ENABLE ROW LEVEL SECURITY;

ALTER TABLE tenant_module_configs ENABLE ROW LEVEL SECURITY;

ALTER TABLE tenant_modules ENABLE ROW LEVEL SECURITY;

ALTER TABLE tenants ENABLE ROW LEVEL SECURITY;

ALTER TABLE translations ENABLE ROW LEVEL SECURITY;

ALTER TABLE user_property_roles ENABLE ROW LEVEL SECURITY;

ALTER TABLE users ENABLE ROW LEVEL SECURITY;

ALTER TABLE work_orders ENABLE ROW LEVEL SECURITY;


-- Explicit FORCE RLS on tenant-scoped data tables. Platform identity/RBAC
-- tables are excluded because platform authorization helpers read them under
-- SECURITY DEFINER.
ALTER TABLE ONLY accounting_accounts FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY allotments FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY approval_requests FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY approval_workflows FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY ar_allocations FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY attendance FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY audit_logs FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY availability_calendar FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY availability_locks FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY booking_engine_configs FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY booking_engine_sites FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY booking_payment_attempts FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY booking_policies FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY booking_session_room_nights FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY booking_session_rooms FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY booking_sessions FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY buildings FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY cash_float_movements FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY channel_connections FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY channels FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY companies FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY corporate_account_contacts FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY corporate_account_holds FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY corporate_account_limits FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY corporate_accounts FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY corporate_rate_agreements FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY corporate_statement_items FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY corporate_statements FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY credit_notes FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY currency_rates FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY departments FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY document_sequences FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY edge_sync_events FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY employees FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY event_booking_items FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY event_bookings FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY event_packages FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY event_spaces FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY events FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY feature_flags FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY fiscal_document_mappings FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY fiscal_provider_configs FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY fiscal_receipts FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY fiscal_submission_attempts FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY fiscal_submission_batches FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY floors FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY communication_consents FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY contact_channels FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY folio_charge_taxes FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY folio_charges FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY folio_payments FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY folios FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY groups FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY guest_contacts FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY guest_documents FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY guest_feedback FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY guest_preferences FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY guests FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY housekeeping_assignments FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY housekeeping_tasks FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY idempotency_keys FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY inventory_items FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY inventory_locations FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY invoice_item_taxes FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY invoice_items FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY invoices FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY journal_entries FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY journal_entry_lines FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY kitchen_tickets FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY labor_forecasts FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY leave_requests FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY lost_and_found FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY loyalty_accounts FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY loyalty_transactions FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY maintenance_requests FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY maintenance_windows FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY menu_categories FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY menu_item_recipes FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY menu_items FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY mobile_money_disbursements FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY night_audit_runs FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY notifications FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY ota_room_type_mappings FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY outbox_events FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY outlets FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY payment_provider_accounts FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY payment_providers FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY payment_reconciliation_items FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY payment_reconciliations FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY payment_transactions FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY payment_webhook_events FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY payroll_records FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY payroll_runs FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY permissions FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY pos_order_items FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY pos_orders FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY pos_printer_routes FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY pos_sessions FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY pos_terminals FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY pricing_rules FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY promo_codes FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY properties FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY property_billing_settings FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY property_frontdesk_settings FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY property_housekeeping_settings FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY property_module_configs FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY property_modules FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY property_pos_settings FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY property_reservation_settings FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY purchase_order_items FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY purchase_orders FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY rate_plan_prices FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY rate_plans FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY rate_restrictions FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY report_deliveries FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY report_runs FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY report_subscription_recipients FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY report_subscriptions FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY reservation_deposits FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY reservation_guests FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY reservation_notes FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY reservation_policy_snapshots FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY reservation_room_nights FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY reservation_rooms FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY reservations FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY revenue_centers FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY roles FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY room_moves FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY room_status_log FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY room_types FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY rooms FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY sales_activities FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY sales_leads FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY shift_handovers FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY shift_templates FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY staff_rosters FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY stays FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY stock_levels FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY stock_movements FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY suppliers FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY support_tickets FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY tax_jurisdictions FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY tax_rates FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY tax_report_lines FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY tax_report_runs FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY tenant_configs FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY tenant_contact_roles FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY tenant_contacts FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY tenant_lifecycle_events FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY tenant_module_configs FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY tenant_modules FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY tenant_profiles FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY tenant_roles FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY tenant_usage_snapshots FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY tenant_verification_cases FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY tenant_verification_documents FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY tenants FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY translations FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY user_property_roles FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY user_tenant_roles FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY users FORCE ROW LEVEL SECURITY;
ALTER TABLE ONLY work_orders FORCE ROW LEVEL SECURITY;


-- Policies

CREATE POLICY tenant_isolation ON accounting_accounts USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON allotments USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON approval_request_steps USING ((EXISTS ( SELECT 1
   FROM approval_requests ar
  WHERE ((ar.id = approval_request_steps.request_id) AND (ar.tenant_id = current_tenant_id())))));

CREATE POLICY tenant_isolation ON approval_requests USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON approval_workflow_steps USING ((EXISTS ( SELECT 1
   FROM approval_workflows aw
  WHERE ((aw.id = approval_workflow_steps.workflow_id) AND (aw.tenant_id = current_tenant_id())))));

CREATE POLICY tenant_isolation ON approval_workflows USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON attendance USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON audit_logs USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_or_platform ON feature_flags USING (((tenant_id IS NULL) OR (tenant_id = current_tenant_id()))) WITH CHECK (((tenant_id IS NULL) OR (tenant_id = current_tenant_id())));

CREATE POLICY tenant_isolation ON idempotency_keys USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_or_platform ON maintenance_windows USING (((tenant_id IS NULL) OR (tenant_id = current_tenant_id()))) WITH CHECK (((tenant_id IS NULL) OR (tenant_id = current_tenant_id())));

CREATE POLICY catalog_read ON module_catalog USING (true) WITH CHECK (platform_user_has_permission(current_platform_user_id(), 'platform.admin.all'));

CREATE POLICY catalog_read ON module_dependencies USING (true) WITH CHECK (platform_user_has_permission(current_platform_user_id(), 'platform.admin.all'));

CREATE POLICY catalog_read ON business_profiles USING (true) WITH CHECK (platform_user_has_permission(current_platform_user_id(), 'platform.admin.all'));

CREATE POLICY catalog_read ON business_profile_modules USING (true) WITH CHECK (platform_user_has_permission(current_platform_user_id(), 'platform.admin.all'));

CREATE POLICY tenant_or_platform ON tenant_profiles USING (((tenant_id = current_tenant_id()) OR platform_user_has_permission(current_platform_user_id(), 'platform.tenants.view'))) WITH CHECK (((tenant_id = current_tenant_id()) OR platform_user_has_permission(current_platform_user_id(), 'platform.tenants.manage')));

CREATE POLICY catalog_read ON contact_role_catalog USING (true) WITH CHECK (platform_user_has_permission(current_platform_user_id(), 'platform.admin.all'));

CREATE POLICY tenant_isolation ON tenant_contacts USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON tenant_contact_roles USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON contact_channels USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON communication_consents USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_or_platform_verification ON tenant_verification_cases USING (((tenant_id = current_tenant_id()) OR platform_user_has_permission(current_platform_user_id(), 'platform.tenants.verification.manage') OR platform_user_has_permission(current_platform_user_id(), 'platform.tenants.verify'))) WITH CHECK (((tenant_id = current_tenant_id()) OR platform_user_has_permission(current_platform_user_id(), 'platform.tenants.verification.manage') OR platform_user_has_permission(current_platform_user_id(), 'platform.tenants.verify')));

CREATE POLICY tenant_or_platform_verification ON tenant_verification_documents USING (((tenant_id = current_tenant_id()) OR platform_user_has_permission(current_platform_user_id(), 'platform.tenants.verification_documents.view') OR platform_user_has_permission(current_platform_user_id(), 'platform.tenants.verification.manage'))) WITH CHECK (((tenant_id = current_tenant_id()) OR platform_user_has_permission(current_platform_user_id(), 'platform.tenants.verification.manage')));

CREATE POLICY catalog_read ON report_catalog USING (true) WITH CHECK (platform_user_has_permission(current_platform_user_id(), 'platform.admin.all'));

CREATE POLICY tenant_isolation ON report_subscriptions USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON report_subscription_recipients USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON report_runs USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON report_deliveries USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON outbox_events USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY catalog_read ON permission_catalog USING (true) WITH CHECK (platform_user_has_permission(current_platform_user_id(), 'platform.admin.all'));

CREATE POLICY platform_admin ON platform_alerts USING (platform_user_has_permission(current_platform_user_id(), 'platform.monitoring.view')) WITH CHECK (platform_user_has_permission(current_platform_user_id(), 'platform.monitoring.manage'));

CREATE POLICY platform_admin ON platform_audit_logs USING (platform_user_has_permission(current_platform_user_id(), 'platform.audit.view')) WITH CHECK (platform_user_has_permission(current_platform_user_id(), 'platform.audit.write'));

CREATE POLICY platform_admin ON platform_break_glass_access USING (platform_user_has_permission(current_platform_user_id(), 'platform.support.impersonate')) WITH CHECK (platform_user_has_permission(current_platform_user_id(), 'platform.support.impersonate'));

CREATE POLICY platform_admin ON platform_incidents USING (platform_user_has_permission(current_platform_user_id(), 'platform.monitoring.view')) WITH CHECK (platform_user_has_permission(current_platform_user_id(), 'platform.monitoring.manage'));

CREATE POLICY platform_admin ON platform_jobs USING (platform_user_has_permission(current_platform_user_id(), 'platform.monitoring.view')) WITH CHECK (platform_user_has_permission(current_platform_user_id(), 'platform.monitoring.manage'));

CREATE POLICY platform_admin ON platform_job_runs USING (platform_user_has_permission(current_platform_user_id(), 'platform.monitoring.view')) WITH CHECK (platform_user_has_permission(current_platform_user_id(), 'platform.monitoring.manage'));

CREATE POLICY platform_admin ON platform_permissions USING (platform_user_has_permission(current_platform_user_id(), 'platform.security.manage')) WITH CHECK (platform_user_has_permission(current_platform_user_id(), 'platform.security.manage'));

CREATE POLICY platform_admin ON platform_roles USING (platform_user_has_permission(current_platform_user_id(), 'platform.security.manage')) WITH CHECK (platform_user_has_permission(current_platform_user_id(), 'platform.security.manage'));

CREATE POLICY platform_admin ON platform_role_permissions USING (platform_user_has_permission(current_platform_user_id(), 'platform.security.manage')) WITH CHECK (platform_user_has_permission(current_platform_user_id(), 'platform.security.manage'));

CREATE POLICY platform_admin ON platform_services USING (platform_user_has_permission(current_platform_user_id(), 'platform.monitoring.view')) WITH CHECK (platform_user_has_permission(current_platform_user_id(), 'platform.monitoring.manage'));

CREATE POLICY platform_self ON platform_sessions USING ((platform_user_id = current_platform_user_id()) OR platform_user_has_permission(current_platform_user_id(), 'platform.security.manage')) WITH CHECK ((platform_user_id = current_platform_user_id()) OR platform_user_has_permission(current_platform_user_id(), 'platform.security.manage'));

CREATE POLICY platform_self ON platform_users USING ((id = current_platform_user_id()) OR platform_user_has_permission(current_platform_user_id(), 'platform.security.manage')) WITH CHECK ((id = current_platform_user_id()) OR platform_user_has_permission(current_platform_user_id(), 'platform.security.manage'));

CREATE POLICY platform_admin ON platform_user_roles USING (platform_user_has_permission(current_platform_user_id(), 'platform.security.manage')) WITH CHECK (platform_user_has_permission(current_platform_user_id(), 'platform.security.manage'));

CREATE POLICY platform_admin ON plan_entitlements USING (platform_user_has_permission(current_platform_user_id(), 'platform.billing.manage')) WITH CHECK (platform_user_has_permission(current_platform_user_id(), 'platform.billing.manage'));

CREATE POLICY platform_admin ON schema_version_history USING (platform_user_has_permission(current_platform_user_id(), 'platform.admin.all')) WITH CHECK (platform_user_has_permission(current_platform_user_id(), 'platform.admin.all'));

CREATE POLICY platform_admin ON service_health_checks USING (platform_user_has_permission(current_platform_user_id(), 'platform.monitoring.view')) WITH CHECK (platform_user_has_permission(current_platform_user_id(), 'platform.monitoring.manage'));

CREATE POLICY tenant_or_platform ON support_ticket_notes USING ((EXISTS ( SELECT 1 FROM support_tickets st WHERE ((st.id = support_ticket_notes.ticket_id) AND ((st.tenant_id = current_tenant_id()) OR platform_user_has_permission(current_platform_user_id(), 'platform.support.view')))))) WITH CHECK ((EXISTS ( SELECT 1 FROM support_tickets st WHERE ((st.id = support_ticket_notes.ticket_id) AND ((st.tenant_id = current_tenant_id()) OR platform_user_has_permission(current_platform_user_id(), 'platform.support.manage'))))));

CREATE POLICY tenant_or_platform ON support_tickets USING (((tenant_id = current_tenant_id()) OR platform_user_has_permission(current_platform_user_id(), 'platform.support.view'))) WITH CHECK (((tenant_id = current_tenant_id()) OR platform_user_has_permission(current_platform_user_id(), 'platform.support.manage')));

CREATE POLICY platform_admin ON tenant_lifecycle_events USING (platform_user_has_permission(current_platform_user_id(), 'platform.tenants.view')) WITH CHECK (platform_user_has_permission(current_platform_user_id(), 'platform.tenants.manage'));

CREATE POLICY tenant_isolation ON tenant_roles USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON tenant_role_permissions USING ((EXISTS ( SELECT 1 FROM tenant_roles tr WHERE ((tr.id = tenant_role_permissions.tenant_role_id) AND (tr.tenant_id = current_tenant_id())))));

CREATE POLICY tenant_or_platform ON tenant_usage_snapshots USING (((tenant_id = current_tenant_id()) OR platform_user_has_permission(current_platform_user_id(), 'platform.tenants.view'))) WITH CHECK (((tenant_id = current_tenant_id()) OR platform_user_has_permission(current_platform_user_id(), 'platform.tenants.manage')));

CREATE POLICY tenant_isolation ON user_tenant_roles USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY catalog_read ON workflow_catalog USING (true) WITH CHECK (platform_user_has_permission(current_platform_user_id(), 'platform.admin.all'));

CREATE POLICY catalog_read ON workflow_steps USING (true) WITH CHECK (platform_user_has_permission(current_platform_user_id(), 'platform.admin.all'));

CREATE POLICY tenant_isolation ON availability_calendar USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON availability_locks USING (((tenant_id = current_tenant_id()) AND (EXISTS ( SELECT 1
   FROM room_types rt
  WHERE ((rt.id = availability_locks.room_type_id) AND (rt.tenant_id = current_tenant_id())))))) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON booking_engine_configs USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON booking_engine_sites USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON booking_policies USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON booking_payment_attempts USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON booking_session_room_nights USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON booking_session_rooms USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON booking_sessions USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON buildings USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON cash_float_movements USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON channel_connections USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON channels USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON companies USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON corporate_accounts USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON corporate_account_contacts USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON corporate_account_limits USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON corporate_account_holds USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON corporate_rate_agreements USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON corporate_statements USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON corporate_statement_items USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON credit_notes USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON ar_allocations USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON currency_rates USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON departments USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON document_sequences USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON edge_sync_events USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON employees USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON event_booking_items USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON event_bookings USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON event_packages USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON event_spaces USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON events USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON fiscal_receipts USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON fiscal_provider_configs USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON fiscal_submission_batches USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON fiscal_submission_attempts USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON fiscal_document_mappings USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON tax_jurisdictions USING (((tenant_id IS NULL) OR (tenant_id = current_tenant_id()))) WITH CHECK (((tenant_id IS NULL) OR (tenant_id = current_tenant_id())));

CREATE POLICY tenant_isolation ON tax_report_runs USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON tax_report_lines USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON floors USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON folio_charges USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON folio_charge_taxes USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON folio_payments USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON folios USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON groups USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON guest_contacts USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON guest_documents USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON guest_feedback USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON guest_preferences USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON guests USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON housekeeping_assignments USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON housekeeping_tasks USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON inventory_items USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON inventory_locations USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON invoice_items USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON invoice_item_taxes USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON invoices USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON journal_entries USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON journal_entry_lines USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON kitchen_tickets USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON labor_forecasts USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON leave_requests USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON lost_and_found USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON loyalty_accounts USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON loyalty_transactions USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON maintenance_requests USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON menu_categories USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON menu_item_recipes USING ((EXISTS ( SELECT 1
   FROM menu_items mi
  WHERE ((mi.id = menu_item_recipes.menu_item_id) AND (mi.tenant_id = current_tenant_id())))));

CREATE POLICY tenant_isolation ON menu_items USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON night_audit_runs USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON notifications USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON ota_room_type_mappings USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON outlets USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON payroll_records USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON payroll_runs USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON permissions USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON payment_providers USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON payment_provider_accounts USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON payment_webhook_events USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON payment_transactions USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON payment_reconciliations USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON payment_reconciliation_items USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON mobile_money_disbursements USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON pos_order_items USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON pos_orders USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON pos_printer_routes USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON pos_sessions USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON pos_terminals USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON property_billing_settings USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON property_frontdesk_settings USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON property_housekeeping_settings USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON property_module_configs USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON property_modules USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON property_pos_settings USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON property_reservation_settings USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON pricing_rules USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON promo_codes USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON properties USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON purchase_order_items USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON purchase_orders USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON rate_plan_prices USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON rate_plans USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON rate_restrictions USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON refresh_tokens USING ((EXISTS ( SELECT 1
   FROM users u
  WHERE ((u.id = refresh_tokens.user_id) AND (u.tenant_id = current_tenant_id())))));

CREATE POLICY tenant_isolation ON revenue_centers USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON reservation_guests USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON reservation_notes USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON reservation_deposits USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON reservation_policy_snapshots USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON reservation_room_nights USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON reservation_rooms USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON reservations USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON role_permissions USING ((role_id IN ( SELECT roles.id
   FROM roles
  WHERE (roles.tenant_id = current_tenant_id()))));

CREATE POLICY tenant_isolation ON roles USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON room_moves USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON room_status_log USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON room_types USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON rooms USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON sales_activities USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON sales_leads USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON shift_handovers USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON shift_templates USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON staff_rosters USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON stays USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON stock_levels USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON stock_movements USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON suppliers USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON tax_rates USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON tenant_configs USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON tenant_module_configs USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON tenant_modules USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_self ON tenants USING ((id = current_tenant_id())) WITH CHECK ((id = current_tenant_id()));

CREATE POLICY platform_tenant_governance ON tenants USING (platform_user_has_permission(current_platform_user_id(), 'platform.tenants.view')) WITH CHECK (platform_user_has_permission(current_platform_user_id(), 'platform.tenants.manage'));

CREATE POLICY tenant_isolation ON translations USING (((tenant_id IS NULL) OR (tenant_id = current_tenant_id()))) WITH CHECK (((tenant_id IS NULL) OR (tenant_id = current_tenant_id())));

CREATE POLICY tenant_isolation ON user_property_roles USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON users USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON work_orders USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));


-- ================================================================================
-- 25. TRIGGERS
-- ================================================================================

CREATE TRIGGER trg_accounting_accounts_updated_at BEFORE UPDATE ON accounting_accounts FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_feature_flags_updated_at BEFORE UPDATE ON feature_flags FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_idempotency_keys_updated_at BEFORE UPDATE ON idempotency_keys FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_maintenance_windows_updated_at BEFORE UPDATE ON maintenance_windows FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_module_catalog_updated_at BEFORE UPDATE ON module_catalog FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_outbox_events_updated_at BEFORE UPDATE ON outbox_events FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_permission_catalog_updated_at BEFORE UPDATE ON permission_catalog FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_platform_alerts_updated_at BEFORE UPDATE ON platform_alerts FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_platform_incidents_updated_at BEFORE UPDATE ON platform_incidents FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_platform_jobs_updated_at BEFORE UPDATE ON platform_jobs FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_platform_permissions_updated_at BEFORE UPDATE ON platform_permissions FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_platform_roles_updated_at BEFORE UPDATE ON platform_roles FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_platform_services_updated_at BEFORE UPDATE ON platform_services FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_platform_users_updated_at BEFORE UPDATE ON platform_users FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_plan_entitlements_updated_at BEFORE UPDATE ON plan_entitlements FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_support_tickets_updated_at BEFORE UPDATE ON support_tickets FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_tenant_roles_updated_at BEFORE UPDATE ON tenant_roles FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_user_tenant_roles_updated_at BEFORE UPDATE ON user_tenant_roles FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_workflow_catalog_updated_at BEFORE UPDATE ON workflow_catalog FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_allotments_updated_at BEFORE UPDATE ON allotments FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_approval_requests_updated_at BEFORE UPDATE ON approval_requests FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_approval_workflows_updated_at BEFORE UPDATE ON approval_workflows FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_attendance_updated_at BEFORE UPDATE ON attendance FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_availability_calendar_updated_at BEFORE UPDATE ON availability_calendar FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_availability_locks_updated_at BEFORE UPDATE ON availability_locks FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_availability_locks_capacity AFTER INSERT OR UPDATE OR DELETE ON availability_locks FOR EACH ROW EXECUTE FUNCTION apply_availability_lock_capacity();

CREATE TRIGGER trg_booking_engine_configs_updated_at BEFORE UPDATE ON booking_engine_configs FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_booking_engine_sites_updated_at BEFORE UPDATE ON booking_engine_sites FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_booking_policies_updated_at BEFORE UPDATE ON booking_policies FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_booking_payment_attempts_updated_at BEFORE UPDATE ON booking_payment_attempts FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_booking_sessions_updated_at BEFORE UPDATE ON booking_sessions FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_buildings_updated_at BEFORE UPDATE ON buildings FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_channel_connections_updated_at BEFORE UPDATE ON channel_connections FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_channels_updated_at BEFORE UPDATE ON channels FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_companies_updated_at BEFORE UPDATE ON companies FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_corporate_accounts_updated_at BEFORE UPDATE ON corporate_accounts FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_corporate_accounts_balance_guard BEFORE UPDATE ON corporate_accounts FOR EACH ROW EXECUTE FUNCTION guard_corporate_account_balance_write();

CREATE TRIGGER trg_corporate_account_contacts_updated_at BEFORE UPDATE ON corporate_account_contacts FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_corporate_account_limits_updated_at BEFORE UPDATE ON corporate_account_limits FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_corporate_account_holds_updated_at BEFORE UPDATE ON corporate_account_holds FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_corporate_rate_agreements_updated_at BEFORE UPDATE ON corporate_rate_agreements FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_corporate_statements_updated_at BEFORE UPDATE ON corporate_statements FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_credit_notes_updated_at BEFORE UPDATE ON credit_notes FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_ar_allocations_sync_balance AFTER INSERT OR UPDATE OR DELETE ON ar_allocations FOR EACH ROW EXECUTE FUNCTION sync_corporate_account_balance_from_ar();

CREATE TRIGGER trg_departments_updated_at BEFORE UPDATE ON departments FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_document_sequences_updated_at BEFORE UPDATE ON document_sequences FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_edge_sync_events_updated_at BEFORE UPDATE ON edge_sync_events FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_employees_updated_at BEFORE UPDATE ON employees FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_event_booking_items_updated_at BEFORE UPDATE ON event_booking_items FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_event_bookings_updated_at BEFORE UPDATE ON event_bookings FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_event_packages_updated_at BEFORE UPDATE ON event_packages FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_event_spaces_updated_at BEFORE UPDATE ON event_spaces FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_fiscal_receipts_updated_at BEFORE UPDATE ON fiscal_receipts FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_fiscal_receipts_financial_guard BEFORE UPDATE ON fiscal_receipts FOR EACH ROW EXECUTE FUNCTION guard_fiscal_receipts_financial_state();

CREATE TRIGGER trg_fiscal_receipts_no_delete BEFORE DELETE ON fiscal_receipts FOR EACH ROW EXECUTE FUNCTION prevent_financial_document_delete();

CREATE TRIGGER trg_fiscal_providers_updated_at BEFORE UPDATE ON fiscal_providers FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_fiscal_provider_configs_updated_at BEFORE UPDATE ON fiscal_provider_configs FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_fiscal_submission_batches_updated_at BEFORE UPDATE ON fiscal_submission_batches FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_floors_updated_at BEFORE UPDATE ON floors FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_folio_charge_taxes_financial_guard BEFORE INSERT OR UPDATE OR DELETE ON folio_charge_taxes FOR EACH ROW EXECUTE FUNCTION guard_folio_charge_taxes_financial_state();

CREATE TRIGGER trg_folio_charges_updated_at BEFORE UPDATE ON folio_charges FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_folio_charges_financial_guard BEFORE INSERT OR UPDATE ON folio_charges FOR EACH ROW EXECUTE FUNCTION guard_folio_charges_financial_state();

CREATE TRIGGER trg_folio_charges_guest_rollups AFTER INSERT OR UPDATE OR DELETE ON folio_charges FOR EACH ROW EXECUTE FUNCTION sync_guest_rollups_from_folio_charge();

CREATE TRIGGER trg_folio_charges_no_delete BEFORE DELETE ON folio_charges FOR EACH ROW EXECUTE FUNCTION prevent_financial_document_delete();

CREATE TRIGGER trg_folio_payments_updated_at BEFORE UPDATE ON folio_payments FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_folio_payments_financial_guard BEFORE INSERT OR UPDATE ON folio_payments FOR EACH ROW EXECUTE FUNCTION guard_folio_payments_financial_state();

CREATE TRIGGER trg_folio_payments_no_delete BEFORE DELETE ON folio_payments FOR EACH ROW EXECUTE FUNCTION prevent_financial_document_delete();

CREATE TRIGGER trg_folios_updated_at BEFORE UPDATE ON folios FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_folios_financial_guard BEFORE INSERT OR UPDATE ON folios FOR EACH ROW EXECUTE FUNCTION guard_folios_financial_state();

CREATE TRIGGER trg_folios_guest_rollups AFTER INSERT OR UPDATE OR DELETE ON folios FOR EACH ROW EXECUTE FUNCTION sync_guest_rollups_from_folio();

CREATE TRIGGER trg_folios_no_delete BEFORE DELETE ON folios FOR EACH ROW EXECUTE FUNCTION prevent_financial_document_delete();

CREATE TRIGGER trg_groups_updated_at BEFORE UPDATE ON groups FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_guest_contacts_updated_at BEFORE UPDATE ON guest_contacts FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_guest_documents_updated_at BEFORE UPDATE ON guest_documents FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_guest_feedback_updated_at BEFORE UPDATE ON guest_feedback FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_guest_preferences_updated_at BEFORE UPDATE ON guest_preferences FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_guests_updated_at BEFORE UPDATE ON guests FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_guests_normalize_name BEFORE INSERT OR UPDATE OF full_name, first_name, last_name ON guests FOR EACH ROW EXECUTE FUNCTION normalize_guest_name();

CREATE TRIGGER trg_housekeeping_assignments_updated_at BEFORE UPDATE ON housekeeping_assignments FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_housekeeping_tasks_updated_at BEFORE UPDATE ON housekeeping_tasks FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_inventory_items_updated_at BEFORE UPDATE ON inventory_items FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_inventory_locations_updated_at BEFORE UPDATE ON inventory_locations FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_invoice_item_taxes_financial_guard BEFORE INSERT OR UPDATE OR DELETE ON invoice_item_taxes FOR EACH ROW EXECUTE FUNCTION guard_invoice_item_taxes_financial_state();

CREATE TRIGGER trg_invoice_items_updated_at BEFORE UPDATE ON invoice_items FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_invoice_items_financial_guard BEFORE INSERT OR UPDATE OR DELETE ON invoice_items FOR EACH ROW EXECUTE FUNCTION guard_invoice_items_financial_state();

CREATE TRIGGER trg_invoices_updated_at BEFORE UPDATE ON invoices FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_invoices_financial_guard BEFORE INSERT OR UPDATE ON invoices FOR EACH ROW EXECUTE FUNCTION guard_invoices_financial_state();

CREATE TRIGGER trg_invoices_sync_ar_balance AFTER INSERT OR UPDATE OR DELETE ON invoices FOR EACH ROW EXECUTE FUNCTION sync_corporate_account_balance_from_ar();

CREATE TRIGGER trg_invoices_no_delete BEFORE DELETE ON invoices FOR EACH ROW EXECUTE FUNCTION prevent_financial_document_delete();

CREATE TRIGGER trg_journal_entries_guard_status BEFORE INSERT OR UPDATE ON journal_entries FOR EACH ROW EXECUTE FUNCTION guard_journal_entry_status();

CREATE TRIGGER trg_journal_entries_updated_at BEFORE UPDATE ON journal_entries FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_journal_entry_lines_guard_integrity BEFORE INSERT OR UPDATE OR DELETE ON journal_entry_lines FOR EACH ROW EXECUTE FUNCTION guard_journal_entry_line_integrity();

CREATE TRIGGER trg_journal_entry_lines_sync_totals AFTER INSERT OR UPDATE OR DELETE ON journal_entry_lines FOR EACH ROW EXECUTE FUNCTION sync_journal_entry_totals_from_lines();

CREATE TRIGGER trg_kitchen_tickets_updated_at BEFORE UPDATE ON kitchen_tickets FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_labor_forecasts_updated_at BEFORE UPDATE ON labor_forecasts FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_leave_requests_updated_at BEFORE UPDATE ON leave_requests FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_lost_and_found_updated_at BEFORE UPDATE ON lost_and_found FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_loyalty_accounts_updated_at BEFORE UPDATE ON loyalty_accounts FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_loyalty_transactions_updated_at BEFORE UPDATE ON loyalty_transactions FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_maintenance_requests_updated_at BEFORE UPDATE ON maintenance_requests FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_module_access_matrix_updated_at BEFORE UPDATE ON module_access_matrix FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_business_profiles_updated_at BEFORE UPDATE ON business_profiles FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_contact_role_catalog_updated_at BEFORE UPDATE ON contact_role_catalog FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_tenant_profiles_updated_at BEFORE UPDATE ON tenant_profiles FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_tenant_contacts_updated_at BEFORE UPDATE ON tenant_contacts FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_contact_channels_updated_at BEFORE UPDATE ON contact_channels FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_contact_channels_state BEFORE INSERT OR UPDATE ON contact_channels FOR EACH ROW EXECUTE FUNCTION guard_contact_channel_state();

CREATE TRIGGER trg_communication_consents_append_only BEFORE INSERT OR UPDATE OR DELETE ON communication_consents FOR EACH ROW EXECUTE FUNCTION guard_communication_consents_append_only();

CREATE TRIGGER trg_tenant_verification_cases_updated_at BEFORE UPDATE ON tenant_verification_cases FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_report_catalog_updated_at BEFORE UPDATE ON report_catalog FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_report_subscriptions_updated_at BEFORE UPDATE ON report_subscriptions FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_report_subscription_recipients_updated_at BEFORE UPDATE ON report_subscription_recipients FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_report_subscription_recipients_guard BEFORE INSERT OR UPDATE ON report_subscription_recipients FOR EACH ROW EXECUTE FUNCTION guard_report_subscription_recipient();

CREATE TRIGGER trg_report_runs_updated_at BEFORE UPDATE ON report_runs FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_report_deliveries_updated_at BEFORE UPDATE ON report_deliveries FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_report_deliveries_guard BEFORE INSERT OR UPDATE ON report_deliveries FOR EACH ROW EXECUTE FUNCTION guard_report_delivery_state();

CREATE TRIGGER trg_menu_categories_updated_at BEFORE UPDATE ON menu_categories FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_menu_item_recipes_updated_at BEFORE UPDATE ON menu_item_recipes FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_menu_items_updated_at BEFORE UPDATE ON menu_items FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_menu_items_tax_snapshot BEFORE INSERT OR UPDATE OF tax_rate_id ON menu_items FOR EACH ROW EXECUTE FUNCTION sync_menu_item_tax_snapshot();

CREATE TRIGGER trg_night_audit_runs_updated_at BEFORE UPDATE ON night_audit_runs FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_ota_room_type_mappings_updated_at BEFORE UPDATE ON ota_room_type_mappings FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_outlets_updated_at BEFORE UPDATE ON outlets FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_payroll_records_updated_at BEFORE UPDATE ON payroll_records FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_payroll_runs_updated_at BEFORE UPDATE ON payroll_runs FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_permissions_updated_at BEFORE UPDATE ON permissions FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_payment_providers_updated_at BEFORE UPDATE ON payment_providers FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_payment_provider_accounts_updated_at BEFORE UPDATE ON payment_provider_accounts FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_payment_transactions_updated_at BEFORE UPDATE ON payment_transactions FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_payment_reconciliations_updated_at BEFORE UPDATE ON payment_reconciliations FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_payment_reconciliation_items_updated_at BEFORE UPDATE ON payment_reconciliation_items FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_mobile_money_disbursements_updated_at BEFORE UPDATE ON mobile_money_disbursements FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_plans_updated_at BEFORE UPDATE ON plans FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_pos_order_items_updated_at BEFORE UPDATE ON pos_order_items FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_pos_orders_updated_at BEFORE UPDATE ON pos_orders FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_pos_orders_state_guard BEFORE INSERT OR UPDATE ON pos_orders FOR EACH ROW EXECUTE FUNCTION guard_pos_order_state();

CREATE TRIGGER trg_pos_printer_routes_updated_at BEFORE UPDATE ON pos_printer_routes FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_pos_sessions_updated_at BEFORE UPDATE ON pos_sessions FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_pos_terminals_updated_at BEFORE UPDATE ON pos_terminals FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_pricing_rules_updated_at BEFORE UPDATE ON pricing_rules FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_property_billing_settings_updated_at BEFORE UPDATE ON property_billing_settings FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_property_frontdesk_settings_updated_at BEFORE UPDATE ON property_frontdesk_settings FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_property_housekeeping_settings_updated_at BEFORE UPDATE ON property_housekeeping_settings FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_property_module_configs_updated_at BEFORE UPDATE ON property_module_configs FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_property_modules_updated_at BEFORE UPDATE ON property_modules FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_property_pos_settings_updated_at BEFORE UPDATE ON property_pos_settings FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_property_reservation_settings_updated_at BEFORE UPDATE ON property_reservation_settings FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_promo_codes_updated_at BEFORE UPDATE ON promo_codes FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_properties_updated_at BEFORE UPDATE ON properties FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_purchase_order_items_updated_at BEFORE UPDATE ON purchase_order_items FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_purchase_orders_updated_at BEFORE UPDATE ON purchase_orders FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_rate_plan_prices_updated_at BEFORE UPDATE ON rate_plan_prices FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_rate_plans_updated_at BEFORE UPDATE ON rate_plans FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_rate_restrictions_updated_at BEFORE UPDATE ON rate_restrictions FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_revenue_centers_updated_at BEFORE UPDATE ON revenue_centers FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_reservation_guests_updated_at BEFORE UPDATE ON reservation_guests FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_reservation_notes_updated_at BEFORE UPDATE ON reservation_notes FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_reservation_deposits_updated_at BEFORE UPDATE ON reservation_deposits FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_reservation_room_nights_updated_at BEFORE UPDATE ON reservation_room_nights FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_reservation_rooms_updated_at BEFORE UPDATE ON reservation_rooms FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_reservations_updated_at BEFORE UPDATE ON reservations FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_reservations_guest_rollups AFTER INSERT OR UPDATE OR DELETE ON reservations FOR EACH ROW EXECUTE FUNCTION sync_guest_rollups_from_reservation();

CREATE TRIGGER trg_roles_updated_at BEFORE UPDATE ON roles FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_room_status_log_updated_at BEFORE UPDATE ON room_status_log FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_room_types_updated_at BEFORE UPDATE ON room_types FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_sales_activities_updated_at BEFORE UPDATE ON sales_activities FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_sales_leads_updated_at BEFORE UPDATE ON sales_leads FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_rooms_updated_at BEFORE UPDATE ON rooms FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_shift_handovers_updated_at BEFORE UPDATE ON shift_handovers FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_shift_templates_updated_at BEFORE UPDATE ON shift_templates FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_staff_rosters_updated_at BEFORE UPDATE ON staff_rosters FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_stays_updated_at BEFORE UPDATE ON stays FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_stays_guest_rollups AFTER INSERT OR UPDATE OR DELETE ON stays FOR EACH ROW EXECUTE FUNCTION sync_guest_rollups_from_stay();

CREATE TRIGGER trg_stock_levels_updated_at BEFORE UPDATE ON stock_levels FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_stock_movements_updated_at BEFORE UPDATE ON stock_movements FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_stock_movements_append_only BEFORE UPDATE OR DELETE ON stock_movements FOR EACH ROW EXECUTE FUNCTION apply_stock_movement_to_levels();

CREATE TRIGGER trg_stock_movements_apply AFTER INSERT ON stock_movements FOR EACH ROW EXECUTE FUNCTION apply_stock_movement_to_levels();

CREATE TRIGGER trg_suppliers_updated_at BEFORE UPDATE ON suppliers FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_tax_rates_updated_at BEFORE UPDATE ON tax_rates FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_tax_jurisdictions_updated_at BEFORE UPDATE ON tax_jurisdictions FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_tax_report_runs_updated_at BEFORE UPDATE ON tax_report_runs FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_tenant_configs_updated_at BEFORE UPDATE ON tenant_configs FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_tenant_module_configs_updated_at BEFORE UPDATE ON tenant_module_configs FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_tenant_modules_updated_at BEFORE UPDATE ON tenant_modules FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_tenants_updated_at BEFORE UPDATE ON tenants FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_translations_updated_at BEFORE UPDATE ON translations FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_user_property_roles_updated_at BEFORE UPDATE ON user_property_roles FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_users_updated_at BEFORE UPDATE ON users FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_work_orders_updated_at BEFORE UPDATE ON work_orders FOR EACH ROW EXECUTE FUNCTION set_updated_at();


-- Tenant lifecycle write guards for launch-critical operational paths.
CREATE TRIGGER trg_lifecycle_tenant_modules BEFORE INSERT OR UPDATE OR DELETE ON tenant_modules FOR EACH ROW EXECUTE FUNCTION guard_tenant_operational_write();
CREATE TRIGGER trg_lifecycle_tenant_module_configs BEFORE INSERT OR UPDATE OR DELETE ON tenant_module_configs FOR EACH ROW EXECUTE FUNCTION guard_tenant_operational_write();
CREATE TRIGGER trg_lifecycle_property_modules BEFORE INSERT OR UPDATE OR DELETE ON property_modules FOR EACH ROW EXECUTE FUNCTION guard_tenant_operational_write();
CREATE TRIGGER trg_lifecycle_property_module_configs BEFORE INSERT OR UPDATE OR DELETE ON property_module_configs FOR EACH ROW EXECUTE FUNCTION guard_tenant_operational_write();
CREATE TRIGGER trg_lifecycle_reservations BEFORE INSERT OR UPDATE OR DELETE ON reservations FOR EACH ROW EXECUTE FUNCTION guard_tenant_operational_write();
CREATE TRIGGER trg_lifecycle_reservation_rooms BEFORE INSERT OR UPDATE OR DELETE ON reservation_rooms FOR EACH ROW EXECUTE FUNCTION guard_tenant_operational_write();
CREATE TRIGGER trg_lifecycle_reservation_room_nights BEFORE INSERT OR UPDATE OR DELETE ON reservation_room_nights FOR EACH ROW EXECUTE FUNCTION guard_tenant_operational_write();
CREATE TRIGGER trg_lifecycle_stays BEFORE INSERT OR UPDATE OR DELETE ON stays FOR EACH ROW EXECUTE FUNCTION guard_tenant_operational_write();
CREATE TRIGGER trg_lifecycle_folios BEFORE INSERT OR UPDATE OR DELETE ON folios FOR EACH ROW EXECUTE FUNCTION guard_tenant_operational_write();
CREATE TRIGGER trg_lifecycle_folio_charges BEFORE INSERT OR UPDATE OR DELETE ON folio_charges FOR EACH ROW EXECUTE FUNCTION guard_tenant_operational_write();
CREATE TRIGGER trg_lifecycle_folio_payments BEFORE INSERT OR UPDATE OR DELETE ON folio_payments FOR EACH ROW EXECUTE FUNCTION guard_tenant_operational_write();
CREATE TRIGGER trg_lifecycle_invoices BEFORE INSERT OR UPDATE OR DELETE ON invoices FOR EACH ROW EXECUTE FUNCTION guard_tenant_operational_write();
CREATE TRIGGER trg_lifecycle_invoice_items BEFORE INSERT OR UPDATE OR DELETE ON invoice_items FOR EACH ROW EXECUTE FUNCTION guard_tenant_operational_write();
CREATE TRIGGER trg_lifecycle_fiscal_receipts BEFORE INSERT OR UPDATE OR DELETE ON fiscal_receipts FOR EACH ROW EXECUTE FUNCTION guard_tenant_operational_write();
CREATE TRIGGER trg_lifecycle_booking_sessions BEFORE INSERT OR UPDATE OR DELETE ON booking_sessions FOR EACH ROW EXECUTE FUNCTION guard_tenant_operational_write();
CREATE TRIGGER trg_lifecycle_booking_session_rooms BEFORE INSERT OR UPDATE OR DELETE ON booking_session_rooms FOR EACH ROW EXECUTE FUNCTION guard_tenant_operational_write();
CREATE TRIGGER trg_lifecycle_booking_session_room_nights BEFORE INSERT OR UPDATE OR DELETE ON booking_session_room_nights FOR EACH ROW EXECUTE FUNCTION guard_tenant_operational_write();
CREATE TRIGGER trg_lifecycle_booking_payment_attempts BEFORE INSERT OR UPDATE OR DELETE ON booking_payment_attempts FOR EACH ROW EXECUTE FUNCTION guard_tenant_operational_write();
CREATE TRIGGER trg_lifecycle_pos_orders BEFORE INSERT OR UPDATE OR DELETE ON pos_orders FOR EACH ROW EXECUTE FUNCTION guard_tenant_operational_write();
CREATE TRIGGER trg_lifecycle_pos_order_items BEFORE INSERT OR UPDATE OR DELETE ON pos_order_items FOR EACH ROW EXECUTE FUNCTION guard_tenant_operational_write();
CREATE TRIGGER trg_lifecycle_payment_transactions BEFORE INSERT OR UPDATE OR DELETE ON payment_transactions FOR EACH ROW EXECUTE FUNCTION guard_tenant_operational_write();
CREATE TRIGGER trg_lifecycle_payment_reconciliations BEFORE INSERT OR UPDATE OR DELETE ON payment_reconciliations FOR EACH ROW EXECUTE FUNCTION guard_tenant_operational_write();
CREATE TRIGGER trg_lifecycle_journal_entries BEFORE INSERT OR UPDATE OR DELETE ON journal_entries FOR EACH ROW EXECUTE FUNCTION guard_tenant_operational_write();
CREATE TRIGGER trg_lifecycle_journal_entry_lines BEFORE INSERT OR UPDATE OR DELETE ON journal_entry_lines FOR EACH ROW EXECUTE FUNCTION guard_tenant_operational_write();
CREATE TRIGGER trg_lifecycle_stock_movements BEFORE INSERT OR UPDATE OR DELETE ON stock_movements FOR EACH ROW EXECUTE FUNCTION guard_tenant_operational_write();
CREATE TRIGGER trg_lifecycle_purchase_orders BEFORE INSERT OR UPDATE OR DELETE ON purchase_orders FOR EACH ROW EXECUTE FUNCTION guard_tenant_operational_write();
CREATE TRIGGER trg_lifecycle_purchase_order_items BEFORE INSERT OR UPDATE OR DELETE ON purchase_order_items FOR EACH ROW EXECUTE FUNCTION guard_tenant_operational_write();
CREATE TRIGGER trg_lifecycle_night_audit_runs BEFORE INSERT OR UPDATE OR DELETE ON night_audit_runs FOR EACH ROW EXECUTE FUNCTION guard_tenant_operational_write();
CREATE TRIGGER trg_lifecycle_report_subscriptions BEFORE INSERT OR UPDATE OR DELETE ON report_subscriptions FOR EACH ROW EXECUTE FUNCTION guard_tenant_operational_write();
CREATE TRIGGER trg_lifecycle_report_subscription_recipients BEFORE INSERT OR UPDATE OR DELETE ON report_subscription_recipients FOR EACH ROW EXECUTE FUNCTION guard_tenant_operational_write();
CREATE TRIGGER trg_lifecycle_report_runs BEFORE INSERT OR UPDATE OR DELETE ON report_runs FOR EACH ROW EXECUTE FUNCTION guard_tenant_operational_write();
CREATE TRIGGER trg_lifecycle_report_deliveries BEFORE INSERT OR UPDATE OR DELETE ON report_deliveries FOR EACH ROW EXECUTE FUNCTION guard_tenant_operational_write();
CREATE TRIGGER trg_lifecycle_idempotency_keys BEFORE INSERT OR UPDATE OR DELETE ON idempotency_keys FOR EACH ROW EXECUTE FUNCTION guard_tenant_operational_write();
CREATE TRIGGER trg_lifecycle_outbox_events BEFORE INSERT OR UPDATE OR DELETE ON outbox_events FOR EACH ROW EXECUTE FUNCTION guard_tenant_operational_write();


-- ================================================================================
-- 26. CANONICAL BOOT CATALOGS
-- ================================================================================

INSERT INTO module_catalog (
  module_id, name, category, access_scope, launch_status, is_platform_visible,
  is_tenant_visible, is_property_scoped, required_plan_code, display_order, description
) VALUES
  ('reservations', 'Reservations', 'core_pms', 'property', 'active', false, true, true, 'starter', 10, 'Room reservations and stay lifecycle'),
  ('tenant_admin', 'Tenant Administration', 'platform', 'tenant', 'active', false, true, false, 'starter', 15, 'Company profile, business contacts, verification readiness, tenant users, modules, and report subscriptions'),
  ('frontdesk', 'Front Desk', 'core_pms', 'property', 'active', false, true, true, 'starter', 20, 'Arrivals, departures, room rack, and walk-ins'),
  ('housekeeping', 'Housekeeping', 'operations', 'property', 'active', false, true, true, 'starter', 30, 'Room cleaning, inspection, and assignment workflows'),
  ('maintenance', 'Maintenance', 'operations', 'property', 'active', false, true, true, 'starter', 40, 'Maintenance requests, work orders, and room blocks'),
  ('billing', 'Billing', 'finance', 'property', 'active', false, true, true, 'starter', 50, 'Folios, charges, payments, invoices, and document sequences'),
  ('fiscal', 'Fiscal Compliance', 'finance', 'property', 'active', false, true, true, 'professional', 60, 'Fiscal receipt submission, recovery, and tax authority adapters'),
  ('payments', 'Payments', 'finance', 'property', 'active', false, true, true, 'professional', 70, 'Provider transactions, webhooks, reconciliation, and disbursements'),
  ('night_audit', 'Night Audit', 'finance', 'property', 'active', false, true, true, 'professional', 80, 'Daily close, revenue checks, and audit controls'),
  ('reports', 'Reports', 'operations', 'property', 'active', false, true, true, 'starter', 90, 'Operational, financial, occupancy, and tax reporting'),
  ('analytics', 'Analytics', 'revenue', 'tenant', 'active', false, true, false, 'professional', 100, 'Company-wide dashboards and decision intelligence'),
  ('booking_engine', 'Booking Engine', 'revenue', 'property', 'active', false, true, true, 'professional', 110, 'Direct web booking, policies, promo codes, and availability publishing'),
  ('pos', 'POS', 'operations', 'property', 'active', false, true, true, 'professional', 160, 'Restaurant, bar, outlet POS, and kitchen tickets'),
  ('inventory', 'Inventory', 'operations', 'property', 'active', false, true, true, 'professional', 170, 'Items, stock levels, movements, and location control'),
  ('procurement', 'Procurement', 'operations', 'both', 'active', false, true, true, 'professional', 180, 'Suppliers, purchase orders, approvals, and receiving'),
  ('events', 'Events and Banquet', 'revenue', 'property', 'active', false, true, true, 'professional', 190, 'Event spaces, packages, bookings, and banquet charges'),
  ('corporate_accounts', 'Corporate Accounts', 'finance', 'tenant', 'active', false, true, false, 'professional', 200, 'Company customers, AR limits, contacts, statements, and allocations'),
  ('staffing', 'Staffing', 'workforce', 'property', 'active', false, true, true, 'professional', 210, 'Rosters, attendance, and shift planning'),
  ('hr', 'HR', 'workforce', 'tenant', 'active', false, true, false, 'professional', 220, 'Employee records required by staffing and payroll-adjacent flows'),
  ('edge_sync', 'Edge Sync', 'integration', 'property', 'active', false, true, true, 'enterprise', 230, 'Offline devices, event queues, and conflict visibility'),
  ('platform_admin', 'Platform Admin Console', 'platform', 'platform', 'internal', true, false, false, NULL, 240, 'Platform governance, support, monitoring, and tenant lifecycle operations'),
  ('channel_manager', 'Channel Manager', 'integration', 'property', 'deferred', false, true, true, 'enterprise', 300, 'OTA and channel-manager integration'),
  ('payroll', 'Payroll', 'future', 'tenant', 'deferred', false, true, false, 'enterprise', 330, 'Statutory payroll compliance'),
  ('loyalty', 'Loyalty', 'guest', 'tenant', 'deferred', false, true, false, 'enterprise', 340, 'Guest loyalty program'),
  ('crm', 'CRM', 'guest', 'tenant', 'deferred', false, true, false, 'enterprise', 350, 'Guest CRM and engagement'),
  ('sales_crm', 'Sales CRM', 'revenue', 'tenant', 'deferred', false, true, false, 'enterprise', 351, 'Sales lead pipeline'),
  ('approval_workflows', 'Approval Workflows', 'operations', 'tenant', 'deferred', false, true, false, 'enterprise', 360, 'Generic approval workflow engine'),
  ('translations', 'Translations', 'platform', 'tenant', 'deferred', false, true, false, 'enterprise', 370, 'Database-managed translations'),
  ('generic_events', 'Generic Events', 'integration', 'tenant', 'deferred', false, true, false, 'enterprise', 380, 'Generic event stream and outbox-adjacent domain events'),
  ('guest_feedback', 'Guest Feedback', 'guest', 'property', 'deferred', false, true, true, 'enterprise', 390, 'Survey and reputation workflow')
ON CONFLICT (module_id) DO UPDATE SET
  name = EXCLUDED.name,
  category = EXCLUDED.category,
  access_scope = EXCLUDED.access_scope,
  launch_status = EXCLUDED.launch_status,
  is_platform_visible = EXCLUDED.is_platform_visible,
  is_tenant_visible = EXCLUDED.is_tenant_visible,
  is_property_scoped = EXCLUDED.is_property_scoped,
  required_plan_code = EXCLUDED.required_plan_code,
  display_order = EXCLUDED.display_order,
  description = EXCLUDED.description,
  updated_at = now();

INSERT INTO business_profiles (id, code, name, description, is_active, display_order) VALUES
  ('10101010-0000-0000-0000-000000000001', 'HOTEL', 'Hotel', 'Independent or business hotel with rooms, front desk, billing, fiscal, payments, POS, inventory, and reporting', true, 10),
  ('10101010-0000-0000-0000-000000000002', 'LODGE', 'Lodge', 'Hospitality lodge with rooms, guest operations, restaurant/bar, procurement, inventory, and excursions-adjacent service controls', true, 20),
  ('10101010-0000-0000-0000-000000000003', 'RESORT', 'Resort', 'Multi-outlet resort with rooms, spa/events, POS, inventory, analytics, and stronger revenue-center reporting', true, 30),
  ('10101010-0000-0000-0000-000000000004', 'GUEST_HOUSE', 'Guest House', 'Small guest house needing lean reservations, front desk, housekeeping, billing, payments, fiscal, and reports', true, 40),
  ('10101010-0000-0000-0000-000000000005', 'SAFARI_CAMP', 'Safari Camp', 'Safari camp with lodge-style stays, remote/edge sync, POS, procurement, inventory, and fiscal operations', true, 50),
  ('10101010-0000-0000-0000-000000000006', 'HOSPITALITY_GROUP', 'Hospitality Group', 'Multi-property hospitality group with tenant analytics, corporate accounts, platform-grade governance, and property operations', true, 60)
ON CONFLICT (code) DO UPDATE SET
  name = EXCLUDED.name,
  description = EXCLUDED.description,
  is_active = EXCLUDED.is_active,
  display_order = EXCLUDED.display_order,
  updated_at = now();

INSERT INTO business_profile_modules (
  business_profile_id, module_id, is_default, is_optional, display_order
) VALUES
  ('10101010-0000-0000-0000-000000000001', 'reservations', true, false, 10),
  ('10101010-0000-0000-0000-000000000001', 'tenant_admin', true, false, 15),
  ('10101010-0000-0000-0000-000000000001', 'frontdesk', true, false, 20),
  ('10101010-0000-0000-0000-000000000001', 'housekeeping', true, false, 30),
  ('10101010-0000-0000-0000-000000000001', 'maintenance', true, false, 40),
  ('10101010-0000-0000-0000-000000000001', 'billing', true, false, 50),
  ('10101010-0000-0000-0000-000000000001', 'fiscal', true, false, 60),
  ('10101010-0000-0000-0000-000000000001', 'payments', true, false, 70),
  ('10101010-0000-0000-0000-000000000001', 'night_audit', true, false, 80),
  ('10101010-0000-0000-0000-000000000001', 'reports', true, false, 90),
  ('10101010-0000-0000-0000-000000000001', 'booking_engine', true, false, 100),
  ('10101010-0000-0000-0000-000000000001', 'pos', false, true, 110),
  ('10101010-0000-0000-0000-000000000001', 'inventory', false, true, 120),
  ('10101010-0000-0000-0000-000000000001', 'procurement', false, true, 130),
  ('10101010-0000-0000-0000-000000000002', 'reservations', true, false, 10),
  ('10101010-0000-0000-0000-000000000002', 'tenant_admin', true, false, 15),
  ('10101010-0000-0000-0000-000000000002', 'frontdesk', true, false, 20),
  ('10101010-0000-0000-0000-000000000002', 'housekeeping', true, false, 30),
  ('10101010-0000-0000-0000-000000000002', 'maintenance', true, false, 40),
  ('10101010-0000-0000-0000-000000000002', 'billing', true, false, 50),
  ('10101010-0000-0000-0000-000000000002', 'fiscal', true, false, 60),
  ('10101010-0000-0000-0000-000000000002', 'payments', true, false, 70),
  ('10101010-0000-0000-0000-000000000002', 'night_audit', true, false, 80),
  ('10101010-0000-0000-0000-000000000002', 'reports', true, false, 90),
  ('10101010-0000-0000-0000-000000000002', 'booking_engine', true, false, 100),
  ('10101010-0000-0000-0000-000000000002', 'pos', true, false, 110),
  ('10101010-0000-0000-0000-000000000002', 'inventory', true, false, 120),
  ('10101010-0000-0000-0000-000000000002', 'procurement', true, false, 130),
  ('10101010-0000-0000-0000-000000000002', 'edge_sync', false, true, 140),
  ('10101010-0000-0000-0000-000000000003', 'reservations', true, false, 10),
  ('10101010-0000-0000-0000-000000000003', 'tenant_admin', true, false, 15),
  ('10101010-0000-0000-0000-000000000003', 'frontdesk', true, false, 20),
  ('10101010-0000-0000-0000-000000000003', 'housekeeping', true, false, 30),
  ('10101010-0000-0000-0000-000000000003', 'maintenance', true, false, 40),
  ('10101010-0000-0000-0000-000000000003', 'billing', true, false, 50),
  ('10101010-0000-0000-0000-000000000003', 'fiscal', true, false, 60),
  ('10101010-0000-0000-0000-000000000003', 'payments', true, false, 70),
  ('10101010-0000-0000-0000-000000000003', 'night_audit', true, false, 80),
  ('10101010-0000-0000-0000-000000000003', 'reports', true, false, 90),
  ('10101010-0000-0000-0000-000000000003', 'analytics', true, false, 100),
  ('10101010-0000-0000-0000-000000000003', 'booking_engine', true, false, 110),
  ('10101010-0000-0000-0000-000000000003', 'pos', true, false, 120),
  ('10101010-0000-0000-0000-000000000003', 'inventory', true, false, 130),
  ('10101010-0000-0000-0000-000000000003', 'procurement', true, false, 140),
  ('10101010-0000-0000-0000-000000000003', 'events', false, true, 150),
  ('10101010-0000-0000-0000-000000000004', 'reservations', true, false, 10),
  ('10101010-0000-0000-0000-000000000004', 'tenant_admin', true, false, 15),
  ('10101010-0000-0000-0000-000000000004', 'frontdesk', true, false, 20),
  ('10101010-0000-0000-0000-000000000004', 'housekeeping', true, false, 30),
  ('10101010-0000-0000-0000-000000000004', 'maintenance', true, false, 40),
  ('10101010-0000-0000-0000-000000000004', 'billing', true, false, 50),
  ('10101010-0000-0000-0000-000000000004', 'fiscal', true, false, 60),
  ('10101010-0000-0000-0000-000000000004', 'payments', true, false, 70),
  ('10101010-0000-0000-0000-000000000004', 'reports', true, false, 80),
  ('10101010-0000-0000-0000-000000000005', 'reservations', true, false, 10),
  ('10101010-0000-0000-0000-000000000005', 'tenant_admin', true, false, 15),
  ('10101010-0000-0000-0000-000000000005', 'frontdesk', true, false, 20),
  ('10101010-0000-0000-0000-000000000005', 'housekeeping', true, false, 30),
  ('10101010-0000-0000-0000-000000000005', 'maintenance', true, false, 40),
  ('10101010-0000-0000-0000-000000000005', 'billing', true, false, 50),
  ('10101010-0000-0000-0000-000000000005', 'fiscal', true, false, 60),
  ('10101010-0000-0000-0000-000000000005', 'payments', true, false, 70),
  ('10101010-0000-0000-0000-000000000005', 'night_audit', true, false, 80),
  ('10101010-0000-0000-0000-000000000005', 'reports', true, false, 90),
  ('10101010-0000-0000-0000-000000000005', 'booking_engine', true, false, 100),
  ('10101010-0000-0000-0000-000000000005', 'pos', true, false, 110),
  ('10101010-0000-0000-0000-000000000005', 'inventory', true, false, 120),
  ('10101010-0000-0000-0000-000000000005', 'procurement', true, false, 130),
  ('10101010-0000-0000-0000-000000000005', 'edge_sync', true, false, 140),
  ('10101010-0000-0000-0000-000000000006', 'reservations', true, false, 10),
  ('10101010-0000-0000-0000-000000000006', 'tenant_admin', true, false, 15),
  ('10101010-0000-0000-0000-000000000006', 'frontdesk', true, false, 20),
  ('10101010-0000-0000-0000-000000000006', 'housekeeping', true, false, 30),
  ('10101010-0000-0000-0000-000000000006', 'maintenance', true, false, 40),
  ('10101010-0000-0000-0000-000000000006', 'billing', true, false, 50),
  ('10101010-0000-0000-0000-000000000006', 'fiscal', true, false, 60),
  ('10101010-0000-0000-0000-000000000006', 'payments', true, false, 70),
  ('10101010-0000-0000-0000-000000000006', 'night_audit', true, false, 80),
  ('10101010-0000-0000-0000-000000000006', 'reports', true, false, 90),
  ('10101010-0000-0000-0000-000000000006', 'analytics', true, false, 100),
  ('10101010-0000-0000-0000-000000000006', 'booking_engine', true, false, 110),
  ('10101010-0000-0000-0000-000000000006', 'pos', true, false, 120),
  ('10101010-0000-0000-0000-000000000006', 'inventory', true, false, 130),
  ('10101010-0000-0000-0000-000000000006', 'procurement', true, false, 140),
  ('10101010-0000-0000-0000-000000000006', 'events', false, true, 150),
  ('10101010-0000-0000-0000-000000000006', 'corporate_accounts', false, true, 160),
  ('10101010-0000-0000-0000-000000000006', 'staffing', false, true, 170),
  ('10101010-0000-0000-0000-000000000006', 'hr', false, true, 180),
  ('10101010-0000-0000-0000-000000000006', 'edge_sync', false, true, 190)
ON CONFLICT (business_profile_id, module_id) DO UPDATE SET
  is_default = EXCLUDED.is_default,
  is_optional = EXCLUDED.is_optional,
  display_order = EXCLUDED.display_order;

INSERT INTO contact_role_catalog (role_code, name, description, scope, is_active, display_order) VALUES
  ('owner_managing_director', 'Owner / Managing Director', 'Hospitality company owner, managing director, or executive sponsor for business decisions and management reports', 'tenant', true, 10),
  ('general_manager', 'General Manager', 'Hotel or group general manager responsible for operations', 'both', true, 20),
  ('primary_contact', 'Primary Contact', 'Main business contact for tenant administration', 'tenant', true, 30),
  ('legal_contact', 'Legal Contact', 'Legal or company secretary contact for agreements and notices', 'tenant', true, 40),
  ('billing_contact', 'Billing Contact', 'Billing contact for invoices, subscriptions, and receivables', 'tenant', true, 50),
  ('finance_contact', 'Finance Contact', 'Finance contact for payments, fiscal, AR, and reconciliation', 'tenant', true, 60),
  ('operations_contact', 'Operations Contact', 'Operations contact for property workflows and escalations', 'both', true, 70),
  ('technical_contact', 'Technical Contact', 'Technical contact for integrations, devices, and access issues', 'tenant', true, 80),
  ('compliance_contact', 'Compliance Contact', 'Compliance contact for tax, licensing, and verification reviews', 'tenant', true, 90),
  ('emergency_contact', 'Emergency Contact', 'Urgent business continuity or incident escalation contact', 'both', true, 100),
  ('authorized_signatory', 'Authorized Signatory', 'Person authorized to approve onboarding, contracts, verification, and commercial changes', 'tenant', true, 110),
  ('report_recipient', 'Report Recipient', 'Recipient of scheduled management, finance, operational, or compliance reports', 'both', true, 120)
ON CONFLICT (role_code) DO UPDATE SET
  name = EXCLUDED.name,
  description = EXCLUDED.description,
  scope = EXCLUDED.scope,
  is_active = EXCLUDED.is_active,
  display_order = EXCLUDED.display_order,
  updated_at = now();

INSERT INTO report_catalog (
  report_code, module_id, name, description, scope, sensitivity_level,
  supports_email, supports_sms, supports_whatsapp, supports_in_app,
  default_format, is_active, display_order
) VALUES
  ('daily_management_summary', 'reports', 'Daily Management Summary', 'Daily occupancy, revenue-center, arrivals, departures, open folios, and operational exception summary for leadership', 'property', 'confidential', true, false, true, true, 'pdf', true, 10),
  ('monthly_executive_summary', 'reports', 'Monthly Executive Summary', 'Tenant-level executive pack covering occupancy, revenue centers, payments, fiscal, AR, and operational health', 'tenant', 'confidential', true, false, true, true, 'pdf', true, 20),
  ('night_audit_close', 'night_audit', 'Night Audit Close', 'Night audit control report for business date close, revenue checks, and exceptions', 'property', 'confidential', true, false, false, true, 'pdf', true, 30),
  ('revenue_center_summary', 'reports', 'Revenue Center Summary', 'Revenue attribution by property, revenue center, source, invoice item, POS, and journal lines', 'both', 'confidential', true, false, true, true, 'xlsx', true, 40),
  ('occupancy_forecast', 'reservations', 'Occupancy Forecast', 'Future occupancy, arrivals, departures, blocks, and availability pressure by room type', 'property', 'internal', true, false, true, true, 'pdf', true, 50),
  ('payments_reconciliation', 'payments', 'Payment Reconciliation', 'Provider, folio, invoice, and reconciliation summary for finance review', 'property', 'regulated', true, false, false, true, 'xlsx', true, 60),
  ('fiscal_receipt_summary', 'fiscal', 'Fiscal Receipt Summary', 'Fiscal receipt submission, acceptance, retry, and exception report for compliance', 'property', 'regulated', true, false, false, true, 'pdf', true, 70),
  ('corporate_ar_aging', 'corporate_accounts', 'Corporate AR Aging', 'Corporate account balances, statements, credits, allocations, and aging buckets', 'tenant', 'regulated', true, false, false, true, 'xlsx', true, 80),
  ('inventory_low_stock', 'inventory', 'Inventory Low Stock', 'Low stock, replenishment, and procurement exception report by property/location', 'property', 'internal', true, true, true, true, 'pdf', true, 90),
  ('housekeeping_status', 'housekeeping', 'Housekeeping Status', 'Room cleanliness, inspection, and maintenance coordination status by property', 'property', 'internal', true, true, true, true, 'pdf', true, 100)
ON CONFLICT (report_code) DO UPDATE SET
  module_id = EXCLUDED.module_id,
  name = EXCLUDED.name,
  description = EXCLUDED.description,
  scope = EXCLUDED.scope,
  sensitivity_level = EXCLUDED.sensitivity_level,
  supports_email = EXCLUDED.supports_email,
  supports_sms = EXCLUDED.supports_sms,
  supports_whatsapp = EXCLUDED.supports_whatsapp,
  supports_in_app = EXCLUDED.supports_in_app,
  default_format = EXCLUDED.default_format,
  is_active = EXCLUDED.is_active,
  display_order = EXCLUDED.display_order,
  updated_at = now();

INSERT INTO permission_catalog (code, namespace, access_scope, description, is_platform_permission, is_tenant_permission) VALUES
  ('platform.admin.all', 'platform', 'platform', 'Full platform administration flow permission', true, false),
  ('platform.tenants.view', 'platform', 'platform', 'View tenant accounts and lifecycle flow permission', true, false),
  ('platform.tenants.manage', 'platform', 'platform', 'Manage tenant lifecycle and plan assignments flow permission', true, false),
  ('platform.billing.manage', 'platform', 'platform', 'Manage plans, entitlements, and billing controls flow permission', true, false),
  ('platform.monitoring.view', 'platform', 'platform', 'View service health, jobs, alerts, and incidents flow permission', true, false),
  ('platform.monitoring.manage', 'platform', 'platform', 'Manage service health, jobs, alerts, and incidents flow permission', true, false),
  ('platform.support.view', 'platform', 'platform', 'View support tickets and customer context flow permission', true, false),
  ('platform.support.manage', 'platform', 'platform', 'Manage support tickets and operator notes flow permission', true, false),
  ('platform.support.impersonate', 'platform', 'platform', 'Perform audited break-glass tenant support access flow permission', true, false),
  ('platform.security.manage', 'platform', 'platform', 'Manage platform users, roles, permissions, and sessions flow permission', true, false),
  ('platform.audit.view', 'platform', 'platform', 'View platform audit logs flow permission', true, false),
  ('platform.audit.write', 'platform', 'platform', 'Write platform audit logs flow permission', true, false),
  ('platform.tenants.verify', 'platform', 'platform', 'Approve or reject tenant business verification cases', true, false),
  ('platform.tenants.verification_documents.view', 'platform', 'platform', 'View tenant verification document metadata and storage references', true, false),
  ('platform.tenants.verification.manage', 'platform', 'platform', 'Manage tenant verification cases, document review, and verification state', true, false),
  ('admin.all', 'admin', 'both', 'Legacy tenant/property super-admin permission', false, true),
  ('tenant.admin.all', 'tenant', 'tenant', 'Tenant-wide administrative permission', false, true),
  ('tenant.profile.view', 'tenant', 'tenant', 'View tenant business profile and onboarding readiness', false, true),
  ('tenant.profile.manage', 'tenant', 'tenant', 'Manage tenant business profile and business details', false, true),
  ('tenant.contacts.view', 'tenant', 'tenant', 'View tenant business contacts, roles, channels, and consents', false, true),
  ('tenant.contacts.manage', 'tenant', 'tenant', 'Manage tenant business contacts, roles, channels, and consents', false, true),
  ('tenant.verification.view', 'tenant', 'tenant', 'View tenant verification status, cases, and document metadata', false, true),
  ('tenant.settings.manage', 'tenant', 'tenant', 'Manage tenant settings and company profile', false, true),
  ('tenant.users.manage', 'tenant', 'tenant', 'Manage tenant users and tenant roles', false, true),
  ('module.manage', 'module', 'tenant', 'Manage tenant and property module activation', false, true),
  ('reservations.view', 'reservations', 'property', 'View reservations', false, true),
  ('reservations.create', 'reservations', 'property', 'Create reservations', false, true),
  ('reservations.cancel', 'reservations', 'property', 'Cancel reservations', false, true),
  ('checkin.process', 'frontdesk', 'property', 'Process check-ins', false, true),
  ('checkout.process', 'frontdesk', 'property', 'Process check-outs', false, true),
  ('housekeeping.manage', 'housekeeping', 'property', 'Manage housekeeping tasks', false, true),
  ('maintenance.manage', 'maintenance', 'property', 'Manage maintenance work orders', false, true),
  ('folio.view', 'folio', 'property', 'View folios', false, true),
  ('folio.post_charge', 'folio', 'property', 'Post charges to folios', false, true),
  ('folio.post_payment', 'folio', 'property', 'Post payments to folios', false, true),
  ('billing.invoice', 'billing', 'property', 'Issue and manage invoices', false, true),
  ('fiscal.manage', 'fiscal', 'property', 'Manage fiscal receipts and submissions', false, true),
  ('payments.reconcile', 'payments', 'property', 'Reconcile payments', false, true),
  ('night_audit.run', 'finance', 'property', 'Run night audit', false, true),
  ('reports.view', 'reports', 'property', 'View reports', false, true),
  ('reports.subscriptions.view', 'reports', 'both', 'View scheduled report subscriptions and recipients', false, true),
  ('reports.subscriptions.manage', 'reports', 'both', 'Manage scheduled report subscriptions and recipients', false, true),
  ('reports.deliveries.view', 'reports', 'both', 'View report runs, delivery status, and delivery history', false, true),
  ('reports.deliveries.retry', 'reports', 'both', 'Retry failed report deliveries', false, true),
  ('reports.manual_generate', 'reports', 'both', 'Generate reports on demand', false, true),
  ('analytics.view', 'analytics', 'tenant', 'View company analytics', false, true),
  ('booking_engine.manage', 'reservation', 'property', 'Manage booking engine', false, true),
  ('pos.orders', 'pos', 'property', 'Manage POS orders', false, true),
  ('pos.sessions', 'pos', 'property', 'Manage POS sessions', false, true),
  ('pos.configure', 'pos', 'property', 'Configure POS terminals and printers', false, true),
  ('inventory.manage', 'inventory', 'property', 'Manage inventory', false, true),
  ('procurement.manage', 'procurement', 'both', 'Manage procurement', false, true),
  ('events.manage', 'events', 'property', 'Manage events and banquet', false, true),
  ('corporate_accounts.manage', 'corporate_accounts', 'tenant', 'Manage corporate accounts and AR', false, true),
  ('staffing.manage', 'staffing', 'property', 'Manage rosters and attendance', false, true),
  ('hr.manage', 'hr', 'tenant', 'Manage employee records', false, true),
  ('edge_sync.manage', 'edge_sync', 'property', 'Manage edge sync', false, true)
ON CONFLICT (code) DO UPDATE SET
  namespace = EXCLUDED.namespace,
  access_scope = EXCLUDED.access_scope,
  description = EXCLUDED.description,
  is_platform_permission = EXCLUDED.is_platform_permission,
  is_tenant_permission = EXCLUDED.is_tenant_permission,
  updated_at = now();

INSERT INTO platform_permissions (code, namespace, description) VALUES
  ('platform.admin.all', 'platform', 'Full platform administration'),
  ('platform.tenants.view', 'tenant', 'View tenant accounts and lifecycle'),
  ('platform.tenants.manage', 'tenant', 'Manage tenant lifecycle and plan assignments'),
  ('platform.billing.manage', 'billing', 'Manage plans, entitlements, and billing controls'),
  ('platform.monitoring.view', 'monitoring', 'View service health, jobs, alerts, and incidents'),
  ('platform.monitoring.manage', 'monitoring', 'Manage service health, jobs, alerts, and incidents'),
  ('platform.support.view', 'support', 'View support tickets and customer context'),
  ('platform.support.manage', 'support', 'Manage support tickets and operator notes'),
  ('platform.support.impersonate', 'support', 'Perform audited break-glass tenant support access'),
  ('platform.security.manage', 'security', 'Manage platform users, roles, permissions, and sessions'),
  ('platform.audit.view', 'platform', 'View platform audit logs'),
  ('platform.audit.write', 'platform', 'Write platform audit logs'),
  ('platform.tenants.verify', 'tenant', 'Approve or reject tenant business verification cases'),
  ('platform.tenants.verification_documents.view', 'tenant', 'View tenant verification document metadata and storage references'),
  ('platform.tenants.verification.manage', 'tenant', 'Manage tenant verification cases, document review, and verification state')
ON CONFLICT (code) DO UPDATE SET
  namespace = EXCLUDED.namespace,
  description = EXCLUDED.description,
  updated_at = now();

INSERT INTO module_access_matrix (
  module_id, screen_key, screen_label, http_method, api_pattern, permission_code,
  route_scope, guard_mode, access_scope, is_tanzania_v1, is_enabled_by_default, notes
) VALUES
  ('reservations', 'reservations.calendar', 'Reservations Calendar', 'GET', '/api/properties/:propertyId/reservations*', 'reservations.view', 'property', 'staff_permission', 'property', true, true, 'View room bookings and availability'),
  ('reservations', 'reservations.create', 'New Reservation', 'POST', '/api/properties/:propertyId/reservations*', 'reservations.create', 'property', 'staff_permission', 'property', true, true, 'Create direct, walk-in, group, and corporate reservations'),
  ('reservations', 'reservations.cancel', 'Cancel Reservation', 'POST', '/api/properties/:propertyId/reservations/:id/cancel', 'reservations.cancel', 'property', 'staff_permission', 'property', true, true, 'Cancel or void reservations according to policy'),
  ('frontdesk', 'frontdesk.board', 'Front Desk Board', 'GET', '/api/properties/:propertyId/frontdesk*', 'reservations.view', 'property', 'staff_permission', 'property', true, true, 'Arrivals, departures, room rack, and stay search'),
  ('frontdesk', 'frontdesk.checkin', 'Check-In', 'POST', '/api/properties/:propertyId/checkins*', 'checkin.process', 'property', 'staff_permission', 'property', true, true, 'Process arrivals, walk-ins, and room assignments'),
  ('frontdesk', 'frontdesk.checkout', 'Check-Out', 'POST', '/api/properties/:propertyId/checkouts*', 'checkout.process', 'property', 'staff_permission', 'property', true, true, 'Process departures and checkout validations'),
  ('housekeeping', 'housekeeping.board', 'Housekeeping Board', 'ANY', '/api/properties/:propertyId/housekeeping*', 'housekeeping.manage', 'property', 'staff_permission', 'property', true, true, 'Room cleaning, inspection, and assignment workflows'),
  ('maintenance', 'maintenance.work_orders', 'Maintenance Work Orders', 'ANY', '/api/properties/:propertyId/maintenance*', 'maintenance.manage', 'property', 'staff_permission', 'property', true, true, 'Maintenance requests, work orders, and room blocks'),
  ('billing', 'billing.folios', 'Folios', 'GET', '/api/properties/:propertyId/folios*', 'folio.view', 'property', 'staff_permission', 'property', true, true, 'View guest and company folios'),
  ('billing', 'billing.charges', 'Post Charges', 'POST', '/api/properties/:propertyId/folios/:id/charges*', 'folio.post_charge', 'property', 'staff_permission', 'property', true, true, 'Post lodging, POS, adjustment, and incidental charges'),
  ('billing', 'billing.payments', 'Post Payments', 'POST', '/api/properties/:propertyId/folios/:id/payments*', 'folio.post_payment', 'property', 'staff_permission', 'property', true, true, 'Post payments and deposits to folios'),
  ('billing', 'billing.invoices', 'Invoices', 'ANY', '/api/properties/:propertyId/invoices*', 'billing.invoice', 'property', 'staff_permission', 'property', true, true, 'Issue and manage invoices and fiscal documents'),
  ('fiscal', 'fiscal.receipts', 'Fiscal Receipts', 'ANY', '/api/properties/:propertyId/fiscal*', 'fiscal.manage', 'property', 'staff_permission', 'property', true, true, 'TRA EFD/VFD fiscal receipt submission and recovery'),
  ('payments', 'payments.reconciliation', 'Payment Reconciliation', 'ANY', '/api/properties/:propertyId/payments*', 'payments.reconcile', 'property', 'staff_permission', 'property', true, true, 'Mobile money, card, cash, bank, and webhook reconciliation'),
  ('night_audit', 'night_audit.close', 'Night Audit', 'POST', '/api/properties/:propertyId/night-audit*', 'night_audit.run', 'property', 'staff_permission', 'property', true, true, 'Daily close, audit checks, and revenue controls'),
  ('tenant_admin', 'tenant.profile.view', 'Tenant Profile', 'GET', '/api/tenants/:tenantId/profile', 'tenant.profile.view', 'tenant', 'staff_permission', 'tenant', true, true, 'View tenant business profile, registered addresses, verification readiness, and onboarding details'),
  ('tenant_admin', 'tenant.profile.manage', 'Manage Tenant Profile', 'PATCH', '/api/tenants/:tenantId/profile', 'tenant.profile.manage', 'tenant', 'staff_permission', 'tenant', true, true, 'Update tenant business profile, phone, email, addresses, and business registration details'),
  ('tenant_admin', 'tenant.contacts.view', 'Tenant Contacts', 'GET', '/api/tenants/:tenantId/contacts*', 'tenant.contacts.view', 'tenant', 'staff_permission', 'tenant', true, true, 'View tenant business contacts, roles, channels, and consent state'),
  ('tenant_admin', 'tenant.contacts.manage', 'Manage Tenant Contacts', 'ANY', '/api/tenants/:tenantId/contacts*', 'tenant.contacts.manage', 'tenant', 'staff_permission', 'tenant', true, true, 'Create and manage business contacts, roles, channel verification, and consent records'),
  ('tenant_admin', 'tenant.channel.verify', 'Verify Contact Channel', 'POST', '/api/tenants/:tenantId/contacts/:contactId/channels/:channelId/verify', 'tenant.contacts.manage', 'tenant', 'staff_permission', 'tenant', true, true, 'Verify email, SMS, WhatsApp, and phone endpoints for business contacts'),
  ('tenant_admin', 'tenant.contact.consents', 'Contact Consents', 'POST', '/api/tenants/:tenantId/contacts/:contactId/channels/:channelId/consents', 'tenant.contacts.manage', 'tenant', 'staff_permission', 'tenant', true, true, 'Capture append-only communication consent records for operational reports and alerts'),
  ('tenant_admin', 'tenant.verification.view', 'Tenant Verification', 'GET', '/api/tenants/:tenantId/verification*', 'tenant.verification.view', 'tenant', 'staff_permission', 'tenant', true, true, 'View business verification case status and document metadata'),
  ('reports', 'reports.operational', 'Reports', 'GET', '/api/properties/:propertyId/reports*', 'reports.view', 'property', 'staff_permission', 'property', true, true, 'Operational, finance, occupancy, and tax reports'),
  ('reports', 'reports.subscriptions.tenant.view', 'Tenant Report Subscriptions', 'GET', '/api/tenants/:tenantId/report-subscriptions*', 'reports.subscriptions.view', 'tenant', 'staff_permission', 'tenant', true, true, 'View tenant-level scheduled reports and recipients'),
  ('reports', 'reports.subscriptions.tenant.manage', 'Manage Tenant Report Subscriptions', 'ANY', '/api/tenants/:tenantId/report-subscriptions*', 'reports.subscriptions.manage', 'tenant', 'staff_permission', 'tenant', true, true, 'Create, pause, update, or archive tenant-level report subscriptions'),
  ('reports', 'reports.subscriptions.property.view', 'Property Report Subscriptions', 'GET', '/api/properties/:propertyId/report-subscriptions*', 'reports.subscriptions.view', 'property', 'staff_permission', 'property', true, true, 'View property-level scheduled reports and recipients'),
  ('reports', 'reports.subscriptions.property.manage', 'Manage Property Report Subscriptions', 'ANY', '/api/properties/:propertyId/report-subscriptions*', 'reports.subscriptions.manage', 'property', 'staff_permission', 'property', true, true, 'Create, pause, update, or archive property-level report subscriptions'),
  ('reports', 'reports.manual_generate.tenant', 'Generate Tenant Report', 'POST', '/api/tenants/:tenantId/reports/:reportCode/runs', 'reports.manual_generate', 'tenant', 'staff_permission', 'tenant', true, true, 'Generate tenant-scoped reports on demand with idempotency'),
  ('reports', 'reports.manual_generate.property', 'Generate Property Report', 'POST', '/api/properties/:propertyId/reports/:reportCode/runs', 'reports.manual_generate', 'property', 'staff_permission', 'property', true, true, 'Generate property-scoped reports on demand with idempotency'),
  ('reports', 'reports.runs.view', 'Report Runs', 'GET', '/api/tenants/:tenantId/report-runs*', 'reports.deliveries.view', 'tenant', 'staff_permission', 'both', true, true, 'View report generation and delivery history across tenant and property scopes'),
  ('reports', 'reports.delivery.retry', 'Retry Report Delivery', 'POST', '/api/tenants/:tenantId/report-deliveries/:deliveryId/retry', 'reports.deliveries.retry', 'tenant', 'staff_permission', 'both', true, true, 'Retry failed or scheduled report deliveries through the outbox worker'),
  ('analytics', 'analytics.dashboard', 'Analytics', 'GET', '/api/tenants/:tenantId/analytics*', 'analytics.view', 'tenant', 'staff_permission', 'tenant', true, true, 'Company-wide dashboards across properties'),
  ('booking_engine', 'booking_engine.admin', 'Booking Engine', 'ANY', '/api/properties/:propertyId/booking-engine*', 'booking_engine.manage', 'property', 'staff_permission', 'property', true, true, 'Direct web booking site, policies, promo codes, and availability publishing'),
  ('booking_engine', 'booking_engine.public_availability', 'Public Availability', 'GET', '/api/public/properties/:propertyId/booking-engine/availability*', NULL, 'public_property', 'module_only', 'property', true, true, 'Public booking engine availability checks require module enablement only'),
  ('booking_engine', 'booking_engine.public_session', 'Public Booking Session', 'POST', '/api/public/properties/:propertyId/booking-engine/sessions*', NULL, 'public_property', 'module_only', 'property', true, true, 'Public booking session creation requires booking engine module enablement only'),
  ('pos', 'pos.orders', 'POS Orders', 'ANY', '/api/properties/:propertyId/pos/orders*', 'pos.orders', 'property', 'staff_permission', 'property', true, true, 'Restaurant, bar, and outlet order taking'),
  ('pos', 'pos.sessions', 'POS Sessions', 'ANY', '/api/properties/:propertyId/pos/sessions*', 'pos.sessions', 'property', 'staff_permission', 'property', true, true, 'Cash drawer, shift, float, and closing controls'),
  ('pos', 'pos.configure', 'POS Configuration', 'ANY', '/api/properties/:propertyId/pos/config*', 'pos.configure', 'property', 'staff_permission', 'property', true, true, 'Terminals, printers, receipt templates, and peripherals'),
  ('inventory', 'inventory.stock', 'Inventory', 'ANY', '/api/properties/:propertyId/inventory*', 'inventory.manage', 'property', 'staff_permission', 'property', true, true, 'Items, locations, stock levels, and movements'),
  ('procurement', 'procurement.purchase_orders', 'Procurement', 'ANY', '/api/tenants/:tenantId/procurement*', 'procurement.manage', 'tenant', 'staff_permission', 'both', true, true, 'Suppliers and purchase orders with property delivery/use context'),
  ('events', 'events.banquets', 'Events and Banquet', 'ANY', '/api/properties/:propertyId/events*', 'events.manage', 'property', 'staff_permission', 'property', true, true, 'Function rooms, packages, event bookings, and banquet charges'),
  ('corporate_accounts', 'corporate_accounts.ar', 'Corporate Accounts', 'ANY', '/api/tenants/:tenantId/corporate-accounts*', 'corporate_accounts.manage', 'tenant', 'staff_permission', 'tenant', true, true, 'Companies as hotel customers, AR limits, contacts, statements, and allocations'),
  ('staffing', 'staffing.rosters', 'Staff Rosters', 'ANY', '/api/properties/:propertyId/staffing*', 'staffing.manage', 'property', 'staff_permission', 'property', true, true, 'Rosters, attendance, and shift planning'),
  ('hr', 'hr.employees', 'Employees', 'ANY', '/api/tenants/:tenantId/hr*', 'hr.manage', 'tenant', 'staff_permission', 'tenant', true, true, 'Employee records required by rosters and attendance'),
  ('edge_sync', 'edge_sync.devices', 'Edge Sync', 'ANY', '/api/properties/:propertyId/edge-sync*', 'edge_sync.manage', 'property', 'staff_permission', 'property', true, true, 'Offline device sync, event queues, and conflict visibility'),
  ('platform_admin', 'platform.tenants', 'Tenant Governance', 'ANY', '/api/platform/tenants*', 'platform.tenants.manage', 'platform', 'platform_permission', 'platform', true, true, 'Platform tenant lifecycle and plan governance'),
  ('platform_admin', 'platform.tenant_verification', 'Tenant Verification Governance', 'ANY', '/api/platform/tenants/:tenantId/verification*', 'platform.tenants.verification.manage', 'platform', 'platform_permission', 'platform', true, true, 'Platform review, approval, rejection, suspension, and document-metadata visibility for tenant verification'),
  ('platform_admin', 'platform.tenant_verification_approve', 'Approve Tenant Verification', 'POST', '/api/platform/tenants/:tenantId/verification/:caseId/approve', 'platform.tenants.verify', 'platform', 'platform_permission', 'platform', true, true, 'Approve a submitted tenant business verification case'),
  ('platform_admin', 'platform.tenant_verification_reject', 'Reject Tenant Verification', 'POST', '/api/platform/tenants/:tenantId/verification/:caseId/reject', 'platform.tenants.verify', 'platform', 'platform_permission', 'platform', true, true, 'Reject a tenant business verification case with an auditable reason'),
  ('platform_admin', 'platform.monitoring', 'Platform Monitoring', 'ANY', '/api/platform/monitoring*', 'platform.monitoring.view', 'platform', 'platform_permission', 'platform', true, true, 'Platform service health, jobs, alerts, and incidents'),
  ('platform_admin', 'platform.support', 'Platform Support', 'ANY', '/api/platform/support*', 'platform.support.manage', 'platform', 'platform_permission', 'platform', true, true, 'Support tickets, operator notes, and approved break-glass workflows')
ON CONFLICT (module_id, screen_key, http_method, api_pattern, permission_code)
DO UPDATE SET
  screen_label = EXCLUDED.screen_label,
  http_method = EXCLUDED.http_method,
  route_scope = EXCLUDED.route_scope,
  guard_mode = EXCLUDED.guard_mode,
  access_scope = EXCLUDED.access_scope,
  is_tanzania_v1 = EXCLUDED.is_tanzania_v1,
  is_enabled_by_default = EXCLUDED.is_enabled_by_default,
  notes = EXCLUDED.notes,
  updated_at = now();

INSERT INTO workflow_catalog (workflow_code, module_id, name, description, actor_scope) VALUES
  ('guest_booking_to_checkout', 'reservations', 'Guest Booking to Checkout', 'Booking engine or staff reservation through stay, billing, fiscalization, and checkout', 'property'),
  ('platform_tenant_onboarding', 'platform_admin', 'Platform Tenant Onboarding', 'Platform operator provisions tenant, plan, modules, and launch profile', 'platform'),
  ('tenant_business_onboarding', 'tenant_admin', 'Tenant Business Onboarding', 'Tenant administrator captures business profile, executive contacts, channels, consents, and verification documents', 'tenant'),
  ('tenant_business_verification', 'platform_admin', 'Tenant Business Verification', 'Platform operator reviews tenant verification case, document metadata, and approval/rejection decision', 'platform'),
  ('management_report_delivery', 'reports', 'Management Report Delivery', 'Scheduled or manual report generation through verified recipient channels, outbox dispatch, and delivery audit trail', 'tenant'),
  ('procurement_to_stock', 'procurement', 'Procurement to Stock', 'Purchase request, approval, receiving, stock movement, and low-stock control', 'property'),
  ('payment_reconciliation_close', 'payments', 'Payment Reconciliation Close', 'Provider transaction matching, variance handling, approval, and audit trail', 'property')
ON CONFLICT (workflow_code) DO UPDATE SET
  module_id = EXCLUDED.module_id,
  name = EXCLUDED.name,
  description = EXCLUDED.description,
  actor_scope = EXCLUDED.actor_scope,
  updated_at = now();

INSERT INTO workflow_steps (workflow_code, step_order, step_key, step_label, table_name, permission_code, api_pattern) VALUES
  ('guest_booking_to_checkout', 10, 'availability_search', 'Search Availability', 'availability_calendar', 'reservations.view', '/api/properties/:propertyId/availability*'),
  ('guest_booking_to_checkout', 20, 'reservation_create', 'Create Reservation', 'reservations', 'reservations.create', '/api/properties/:propertyId/reservations*'),
  ('guest_booking_to_checkout', 30, 'checkin', 'Check In', 'stays', 'checkin.process', '/api/properties/:propertyId/checkins*'),
  ('guest_booking_to_checkout', 40, 'folio_posting', 'Post Charges and Payments', 'folios', 'folio.post_charge', '/api/properties/:propertyId/folios*'),
  ('guest_booking_to_checkout', 50, 'invoice_fiscalize', 'Issue Invoice and Fiscal Receipt', 'invoices', 'billing.invoice', '/api/properties/:propertyId/invoices*'),
  ('platform_tenant_onboarding', 10, 'tenant_create', 'Create Tenant', 'tenants', 'platform.tenants.manage', '/api/platform/tenants*'),
  ('platform_tenant_onboarding', 20, 'entitlements', 'Assign Plan Entitlements', 'plan_entitlements', 'platform.billing.manage', '/api/platform/plans*'),
  ('platform_tenant_onboarding', 30, 'support_ready', 'Enable Support and Monitoring', 'platform_services', 'platform.monitoring.manage', '/api/platform/monitoring*'),
  ('tenant_business_onboarding', 10, 'profile', 'Capture Business Profile', 'tenant_profiles', 'tenant.profile.manage', '/api/tenants/:tenantId/profile'),
  ('tenant_business_onboarding', 20, 'contacts', 'Capture Business Contacts', 'tenant_contacts', 'tenant.contacts.manage', '/api/tenants/:tenantId/contacts*'),
  ('tenant_business_onboarding', 30, 'channels', 'Verify Contact Channels', 'contact_channels', 'tenant.contacts.manage', '/api/tenants/:tenantId/contacts/:contactId/channels/:channelId/verify'),
  ('tenant_business_onboarding', 40, 'consents', 'Capture Communication Consents', 'communication_consents', 'tenant.contacts.manage', '/api/tenants/:tenantId/contacts/:contactId/channels/:channelId/consents'),
  ('tenant_business_onboarding', 50, 'subscriptions', 'Configure Management Reports', 'report_subscriptions', 'reports.subscriptions.manage', '/api/tenants/:tenantId/report-subscriptions*'),
  ('tenant_business_verification', 10, 'case_submit', 'Submit Verification Case', 'tenant_verification_cases', 'tenant.verification.view', '/api/tenants/:tenantId/verification*'),
  ('tenant_business_verification', 20, 'document_metadata', 'Review Document Metadata', 'tenant_verification_documents', 'platform.tenants.verification_documents.view', '/api/platform/tenants/:tenantId/verification*'),
  ('tenant_business_verification', 30, 'approve_reject', 'Approve or Reject Verification', 'tenant_verification_cases', 'platform.tenants.verify', '/api/platform/tenants/:tenantId/verification/:caseId/approve'),
  ('management_report_delivery', 10, 'schedule', 'Schedule Report Subscription', 'report_subscriptions', 'reports.subscriptions.manage', '/api/tenants/:tenantId/report-subscriptions*'),
  ('management_report_delivery', 20, 'generate', 'Generate Report Run', 'report_runs', 'reports.manual_generate', '/api/tenants/:tenantId/reports/:reportCode/runs'),
  ('management_report_delivery', 30, 'deliver', 'Deliver Report', 'report_deliveries', 'reports.deliveries.view', '/api/tenants/:tenantId/report-runs*'),
  ('management_report_delivery', 40, 'retry', 'Retry Failed Delivery', 'report_deliveries', 'reports.deliveries.retry', '/api/tenants/:tenantId/report-deliveries/:deliveryId/retry'),
  ('procurement_to_stock', 10, 'purchase_order', 'Create Purchase Order', 'purchase_orders', 'procurement.manage', '/api/tenants/:tenantId/procurement*'),
  ('procurement_to_stock', 20, 'approval', 'Approve Purchase', 'approval_requests', 'procurement.manage', '/api/tenants/:tenantId/procurement/approvals*'),
  ('procurement_to_stock', 30, 'receive_stock', 'Receive Stock', 'stock_movements', 'inventory.manage', '/api/properties/:propertyId/inventory*'),
  ('payment_reconciliation_close', 10, 'provider_transaction', 'Capture Provider Transaction', 'payment_transactions', 'payments.reconcile', '/api/properties/:propertyId/payments*'),
  ('payment_reconciliation_close', 20, 'reconcile', 'Reconcile Payment', 'payment_reconciliations', 'payments.reconcile', '/api/properties/:propertyId/payments/reconciliations*')
ON CONFLICT (workflow_code, step_order) DO UPDATE SET
  step_key = EXCLUDED.step_key,
  step_label = EXCLUDED.step_label,
  table_name = EXCLUDED.table_name,
  permission_code = EXCLUDED.permission_code,
  api_pattern = EXCLUDED.api_pattern;

INSERT INTO schema_version_history (version_key, description, applied_by, metadata) VALUES
  ('production_foundation_v1', 'Self-contained hospitality foundation schema with platform RBAC, tenant RBAC, catalogs, idempotency, and outbox primitives', 'schema.sql', '{"source":"canonical_boot_catalog"}'),
  ('production_correctness_v2', 'Production correctness hardening: context assertions, lifecycle guards, business profiles, FORCE RLS, financial race controls, soft-delete uniqueness, and hot-path indexes', 'schema.sql', '{"source":"canonical_boot_catalog","hospitality_only":true,"launch_market":"TZ"}'),
  ('tenant_onboarding_reports_v3', 'Tenant onboarding business profile, contact directory, business verification, report subscriptions, and executive report delivery foundation', 'schema.sql', '{"source":"canonical_boot_catalog","hospitality_only":true,"owner_managing_director_is_hospitality_leadership":true}')
ON CONFLICT (version_key) DO UPDATE SET
  description = EXCLUDED.description,
  applied_at = now(),
  applied_by = EXCLUDED.applied_by,
  metadata = EXCLUDED.metadata;
