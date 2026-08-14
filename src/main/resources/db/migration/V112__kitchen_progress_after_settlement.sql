-- V112 — the kitchen may finish an order that has already been paid for.
--
-- V40 froze pos_order_items once settlement_status left ('unsettled','failed'),
-- so a bill cannot change after money has moved. That is correct for quantity,
-- price and tax. It is wrong for service_state, which records whether the food
-- reached the table and carries no financial meaning at all.
--
-- The two were never distinguished, so the guard caught both. Paying before the
-- food arrives is the normal bar and takeaway sequence — the drink is paid for
-- and then poured — and in that sequence the kitchen's next action threw
-- "POS order items are immutable after settlement starts". Mobile money was
-- worse: settlement_status sits at 'pending' while the guest answers their
-- handset, which is exactly when the kitchen is cooking.
--
-- No test caught it because every POS test settles last. Nothing had ever
-- settled an order and then advanced its ticket.
--
-- The guard now compares the columns that decide what the guest owes. Those
-- stay frozen. service_state, sent_quantity and the void-disposition columns
-- stay writable, because a settled order still has to be prepared, handed over,
-- and — if it is wrong — voided with its stock returned.

CREATE OR REPLACE FUNCTION guard_pos_order_item_mutation() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, public, pg_temp
AS $function$
DECLARE
    v_tenant_id uuid;
    v_order_id uuid;
    v_status text;
    v_settlement_status text;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'POS order items cannot be deleted; void the item instead';
    END IF;

    v_tenant_id := NEW.tenant_id;
    v_order_id := NEW.order_id;
    SELECT status, settlement_status
    INTO v_status, v_settlement_status
    FROM pos_orders
    WHERE tenant_id = v_tenant_id
      AND id = v_order_id
    FOR SHARE;

    IF v_status IS NULL THEN
        RAISE EXCEPTION 'POS order % was not found', v_order_id;
    END IF;

    -- Before settlement starts, everything is still editable.
    IF v_status = 'open' AND v_settlement_status IN ('unsettled', 'failed') THEN
        RETURN NEW;
    END IF;

    -- Past this point money is moving or has moved. A new line is never allowed:
    -- that would change the bill after the guest agreed to it.
    IF TG_OP = 'INSERT' THEN
        RAISE EXCEPTION 'POS order items are immutable after settlement starts';
    END IF;

    -- An update is allowed only while it leaves every amount alone. Service
    -- progress and void bookkeeping are not amounts.
    IF NEW.menu_item_id IS NOT DISTINCT FROM OLD.menu_item_id
       AND NEW.item_name IS NOT DISTINCT FROM OLD.item_name
       AND NEW.quantity IS NOT DISTINCT FROM OLD.quantity
       AND NEW.unit_price IS NOT DISTINCT FROM OLD.unit_price
       AND NEW.subtotal IS NOT DISTINCT FROM OLD.subtotal
       AND NEW.tax_amount IS NOT DISTINCT FROM OLD.tax_amount
       AND NEW.total_price IS NOT DISTINCT FROM OLD.total_price
       AND NEW.modifiers IS NOT DISTINCT FROM OLD.modifiers
    THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'POS order items are immutable after settlement starts';
END;
$function$;

DO $migration$
DECLARE
    v_present integer;
BEGIN
    -- The guard names eight columns. If a ninth money-bearing column is added to
    -- pos_order_items later and not listed there, it silently becomes writable
    -- after payment — a bill that can change once the guest has gone.
    SELECT count(*)
    INTO v_present
    FROM information_schema.columns
    WHERE table_name = 'pos_order_items'
      AND column_name IN (
          'menu_item_id', 'item_name', 'quantity', 'unit_price',
          'subtotal', 'tax_amount', 'total_price', 'modifiers'
      );

    IF v_present <> 8 THEN
        RAISE EXCEPTION
            'pos_order_items no longer has the eight financial columns the settlement guard '
            'compares (found %). Update guard_pos_order_item_mutation to match, or an amount '
            'becomes writable after payment.',
            v_present;
    END IF;

    -- The trigger must still be attached. Redefining the function silently does
    -- nothing if nobody dropped and recreated the trigger.
    IF NOT EXISTS (
        SELECT 1 FROM pg_trigger
        WHERE tgname = 'trg_pos_order_items_mutation_guard'
          AND NOT tgisinternal
    ) THEN
        RAISE EXCEPTION 'trg_pos_order_items_mutation_guard is missing; nothing is guarding '
                        'pos_order_items after settlement';
    END IF;
END;
$migration$;
