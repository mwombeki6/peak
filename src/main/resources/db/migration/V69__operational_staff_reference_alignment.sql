-- Operational APIs assign work to tenant users. Remove the baseline employee
-- references that accidentally required the same UUID to exist in both models.
DO $$
DECLARE
    constraint_name text;
BEGIN
    FOR constraint_name IN
        SELECT c.conname
        FROM pg_constraint c
        WHERE c.conrelid = 'work_orders'::regclass
          AND c.contype = 'f'
          AND c.confrelid = 'employees'::regclass
    LOOP
        EXECUTE format(
            'ALTER TABLE work_orders DROP CONSTRAINT %I',
            constraint_name
        );
    END LOOP;
END
$$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'work_orders'::regclass
          AND conname = 'fk_work_orders_assignee'
    ) THEN
        ALTER TABLE work_orders
            ADD CONSTRAINT fk_work_orders_assignee
            FOREIGN KEY (tenant_id, assigned_to)
            REFERENCES users(tenant_id, id) DEFERRABLE;
    END IF;
END
$$;
