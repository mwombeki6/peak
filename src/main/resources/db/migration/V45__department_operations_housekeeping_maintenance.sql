-- Phase 4: canonical housekeeping, corrective maintenance, room blocks, and custody.

ALTER TABLE property_housekeeping_settings
    ADD COLUMN IF NOT EXISTS inspection_required boolean NOT NULL DEFAULT true,
    ADD COLUMN IF NOT EXISTS stayover_enabled boolean NOT NULL DEFAULT true;

UPDATE property_housekeeping_settings
SET inspection_required = (supervisor_inspection_lock = 'required');

ALTER TABLE housekeeping_tasks
    ADD COLUMN IF NOT EXISTS property_id uuid,
    ADD COLUMN IF NOT EXISTS source_stay_id uuid,
    ADD COLUMN IF NOT EXISTS assigned_to uuid,
    ADD COLUMN IF NOT EXISTS created_by uuid,
    ADD COLUMN IF NOT EXISTS completed_by uuid,
    ADD COLUMN IF NOT EXISTS inspected_by uuid,
    ADD COLUMN IF NOT EXISTS inspection_notes text,
    ADD COLUMN IF NOT EXISTS inspection_completed_at timestamptz;

UPDATE housekeeping_tasks ht
SET property_id = r.property_id
FROM rooms r
WHERE r.tenant_id = ht.tenant_id
  AND r.id = ht.room_id
  AND ht.property_id IS NULL;

UPDATE housekeeping_tasks
SET type = COALESCE(type, 'departure_clean');

UPDATE housekeeping_tasks
SET type = CASE
    WHEN type = 'inspection' THEN 'deep_clean'
    WHEN type = 'linen_change' THEN 'stayover_clean'
    ELSE type
END
WHERE type IN ('inspection', 'linen_change');

ALTER TABLE housekeeping_tasks
    ALTER COLUMN property_id SET NOT NULL,
    ALTER COLUMN type SET NOT NULL,
    DROP CONSTRAINT IF EXISTS chk_housekeeping_tasks_status,
    DROP CONSTRAINT IF EXISTS chk_housekeeping_tasks_type,
    ADD CONSTRAINT chk_housekeeping_tasks_status CHECK (
        status IN (
            'pending', 'assigned', 'in_progress', 'awaiting_inspection',
            'completed', 'skipped', 'cancelled'
        )
    ),
    ADD CONSTRAINT chk_housekeeping_tasks_type CHECK (
        type IN ('stayover_clean', 'departure_clean', 'deep_clean', 'turndown')
    ),
    ADD CONSTRAINT fk_housekeeping_tasks_property
        FOREIGN KEY (tenant_id, property_id)
        REFERENCES properties(tenant_id, id) DEFERRABLE,
    ADD CONSTRAINT fk_housekeeping_tasks_room
        FOREIGN KEY (tenant_id, room_id)
        REFERENCES rooms(tenant_id, id) DEFERRABLE,
    ADD CONSTRAINT fk_housekeeping_tasks_source_stay
        FOREIGN KEY (tenant_id, source_stay_id)
        REFERENCES stays(tenant_id, id) DEFERRABLE,
    ADD CONSTRAINT fk_housekeeping_tasks_assignee
        FOREIGN KEY (tenant_id, assigned_to)
        REFERENCES users(tenant_id, id) DEFERRABLE,
    ADD CONSTRAINT fk_housekeeping_tasks_creator
        FOREIGN KEY (tenant_id, created_by)
        REFERENCES users(tenant_id, id) DEFERRABLE,
    ADD CONSTRAINT fk_housekeeping_tasks_completed_by
        FOREIGN KEY (tenant_id, completed_by)
        REFERENCES users(tenant_id, id) DEFERRABLE,
    ADD CONSTRAINT fk_housekeeping_tasks_inspected_by
        FOREIGN KEY (tenant_id, inspected_by)
        REFERENCES users(tenant_id, id) DEFERRABLE;

CREATE UNIQUE INDEX idx_housekeeping_departure_stay
    ON housekeeping_tasks (tenant_id, property_id, source_stay_id)
    WHERE type = 'departure_clean' AND source_stay_id IS NOT NULL;
CREATE UNIQUE INDEX idx_housekeeping_stayover_day
    ON housekeeping_tasks (tenant_id, property_id, source_stay_id, scheduled_date)
    WHERE type = 'stayover_clean' AND source_stay_id IS NOT NULL
      AND status <> 'cancelled';
CREATE INDEX idx_housekeeping_board
    ON housekeeping_tasks (tenant_id, property_id, scheduled_date, status, priority);

CREATE TABLE housekeeping_inspections (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    task_id uuid NOT NULL,
    inspected_by uuid NOT NULL,
    result text NOT NULL,
    notes text,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_housekeeping_inspection_result
        CHECK (result IN ('passed', 'failed')),
    CONSTRAINT fk_housekeeping_inspections_property
        FOREIGN KEY (tenant_id, property_id)
        REFERENCES properties(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_housekeeping_inspections_task
        FOREIGN KEY (tenant_id, task_id)
        REFERENCES housekeeping_tasks(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_housekeeping_inspections_user
        FOREIGN KEY (tenant_id, inspected_by)
        REFERENCES users(tenant_id, id) DEFERRABLE
);

ALTER TABLE maintenance_requests
    ADD COLUMN IF NOT EXISTS property_id uuid;

UPDATE maintenance_requests mr
SET property_id = r.property_id
FROM rooms r
WHERE r.tenant_id = mr.tenant_id
  AND r.id = mr.room_id
  AND mr.property_id IS NULL;

UPDATE maintenance_requests SET status = 'resolved' WHERE status = 'completed';

ALTER TABLE maintenance_requests
    ALTER COLUMN property_id SET NOT NULL,
    DROP CONSTRAINT IF EXISTS chk_maintenance_requests_status,
    ADD CONSTRAINT chk_maintenance_requests_status
        CHECK (status IN ('open', 'in_progress', 'resolved', 'cancelled', 'deferred')),
    ADD CONSTRAINT fk_maintenance_requests_property
        FOREIGN KEY (tenant_id, property_id)
        REFERENCES properties(tenant_id, id) DEFERRABLE,
    ADD CONSTRAINT fk_maintenance_requests_room
        FOREIGN KEY (tenant_id, room_id)
        REFERENCES rooms(tenant_id, id) DEFERRABLE,
    ADD CONSTRAINT fk_maintenance_requests_reporter
        FOREIGN KEY (tenant_id, reported_by)
        REFERENCES users(tenant_id, id) DEFERRABLE;

ALTER TABLE work_orders
    ADD COLUMN IF NOT EXISTS room_id uuid,
    ADD COLUMN IF NOT EXISTS created_by uuid,
    ADD COLUMN IF NOT EXISTS hold_reason text,
    ADD COLUMN IF NOT EXISTS completion_notes text;

UPDATE work_orders wo
SET room_id = mr.room_id
FROM maintenance_requests mr
WHERE mr.tenant_id = wo.tenant_id
  AND mr.id = wo.request_id
  AND wo.room_id IS NULL;

UPDATE work_orders SET status = 'awaiting_verification' WHERE status = 'completed';

ALTER TABLE work_orders
    DROP CONSTRAINT IF EXISTS work_orders_status_check,
    ADD CONSTRAINT work_orders_status_check CHECK (
        status IN (
            'open', 'assigned', 'in_progress', 'on_hold',
            'awaiting_verification', 'verified', 'cancelled'
        )
    ),
    ADD CONSTRAINT fk_work_orders_property
        FOREIGN KEY (tenant_id, property_id)
        REFERENCES properties(tenant_id, id) DEFERRABLE,
    ADD CONSTRAINT fk_work_orders_request
        FOREIGN KEY (tenant_id, request_id)
        REFERENCES maintenance_requests(tenant_id, id) DEFERRABLE,
    ADD CONSTRAINT fk_work_orders_room
        FOREIGN KEY (tenant_id, room_id)
        REFERENCES rooms(tenant_id, id) DEFERRABLE,
    ADD CONSTRAINT fk_work_orders_assignee
        FOREIGN KEY (tenant_id, assigned_to)
        REFERENCES users(tenant_id, id) DEFERRABLE,
    ADD CONSTRAINT fk_work_orders_creator
        FOREIGN KEY (tenant_id, created_by)
        REFERENCES users(tenant_id, id) DEFERRABLE,
    ADD CONSTRAINT fk_work_orders_verifier
        FOREIGN KEY (tenant_id, verified_by)
        REFERENCES users(tenant_id, id) DEFERRABLE;

ALTER TABLE rooms DROP CONSTRAINT IF EXISTS chk_rooms_status;
UPDATE rooms SET status = 'out_of_order'
WHERE status IN ('maintenance', 'blocked');
ALTER TABLE rooms
    ADD CONSTRAINT chk_rooms_status CHECK (
        status IN (
            'vacant_clean', 'vacant_dirty', 'occupied',
            'maintenance', 'out_of_service', 'out_of_order'
        )
    );

ALTER TABLE room_status_log DROP CONSTRAINT IF EXISTS chk_room_status_log_status;
UPDATE room_status_log SET status = 'out_of_order'
WHERE status IN ('maintenance', 'blocked');
ALTER TABLE room_status_log
    ADD CONSTRAINT chk_room_status_log_status CHECK (
        status IN (
            'vacant_clean', 'vacant_dirty', 'occupied',
            'maintenance', 'out_of_service', 'out_of_order'
        )
    );

CREATE TABLE room_blocks (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    room_id uuid NOT NULL,
    work_order_id uuid,
    block_type text NOT NULL,
    status text NOT NULL DEFAULT 'active',
    reason text NOT NULL,
    blocked_by uuid NOT NULL,
    blocked_at timestamptz NOT NULL DEFAULT now(),
    released_by uuid,
    released_at timestamptz,
    release_reason text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_room_blocks_type
        CHECK (block_type IN ('out_of_service', 'out_of_order')),
    CONSTRAINT chk_room_blocks_status CHECK (status IN ('active', 'released')),
    CONSTRAINT chk_room_blocks_reason CHECK (length(trim(reason)) >= 3),
    CONSTRAINT chk_room_blocks_release CHECK (
        (status = 'active' AND released_by IS NULL AND released_at IS NULL)
        OR
        (status = 'released' AND released_by IS NOT NULL AND released_at IS NOT NULL)
    ),
    CONSTRAINT fk_room_blocks_property
        FOREIGN KEY (tenant_id, property_id)
        REFERENCES properties(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_room_blocks_room
        FOREIGN KEY (tenant_id, room_id)
        REFERENCES rooms(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_room_blocks_work_order
        FOREIGN KEY (tenant_id, work_order_id)
        REFERENCES work_orders(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_room_blocks_blocked_by
        FOREIGN KEY (tenant_id, blocked_by)
        REFERENCES users(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_room_blocks_released_by
        FOREIGN KEY (tenant_id, released_by)
        REFERENCES users(tenant_id, id) DEFERRABLE
);

CREATE UNIQUE INDEX idx_room_blocks_one_active
    ON room_blocks (tenant_id, property_id, room_id)
    WHERE status = 'active';
CREATE INDEX idx_room_blocks_property_status
    ON room_blocks (tenant_id, property_id, status, block_type);

ALTER TABLE lost_and_found
    ADD COLUMN IF NOT EXISTS disposition_reason text,
    ADD COLUMN IF NOT EXISTS updated_by uuid;

ALTER TABLE lost_and_found
    DROP CONSTRAINT IF EXISTS fk_lost_and_found_found_by,
    ADD CONSTRAINT fk_lost_and_found_property
        FOREIGN KEY (tenant_id, property_id)
        REFERENCES properties(tenant_id, id) DEFERRABLE,
    ADD CONSTRAINT fk_lost_and_found_room
        FOREIGN KEY (tenant_id, room_id)
        REFERENCES rooms(tenant_id, id) DEFERRABLE,
    ADD CONSTRAINT fk_lost_and_found_found_by
        FOREIGN KEY (tenant_id, found_by)
        REFERENCES users(tenant_id, id) DEFERRABLE,
    ADD CONSTRAINT fk_lost_and_found_updated_by
        FOREIGN KEY (tenant_id, updated_by)
        REFERENCES users(tenant_id, id) DEFERRABLE;

CREATE TABLE lost_and_found_custody_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    item_id uuid NOT NULL,
    from_status text,
    to_status text NOT NULL,
    actor_id uuid NOT NULL,
    claimant_details text,
    reason text,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_lost_custody_from
        CHECK (from_status IS NULL OR from_status IN (
            'held', 'claimed', 'returned', 'disposed', 'donated'
        )),
    CONSTRAINT chk_lost_custody_to
        CHECK (to_status IN ('held', 'claimed', 'returned', 'disposed', 'donated')),
    CONSTRAINT fk_lost_custody_property
        FOREIGN KEY (tenant_id, property_id)
        REFERENCES properties(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_lost_custody_item
        FOREIGN KEY (tenant_id, item_id)
        REFERENCES lost_and_found(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_lost_custody_actor
        FOREIGN KEY (tenant_id, actor_id)
        REFERENCES users(tenant_id, id) DEFERRABLE
);

CREATE TABLE room_state_transitions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    room_id uuid NOT NULL,
    from_status text NOT NULL,
    to_status text NOT NULL,
    reason text NOT NULL,
    source_type text NOT NULL,
    source_id uuid,
    changed_by uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_room_state_transition_property
        FOREIGN KEY (tenant_id, property_id)
        REFERENCES properties(tenant_id, id) DEFERRABLE,
    CONSTRAINT fk_room_state_transition_room
        FOREIGN KEY (tenant_id, room_id)
        REFERENCES rooms(tenant_id, id) DEFERRABLE
);

CREATE OR REPLACE FUNCTION reject_housekeeping_inspection_self_approval()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_worker uuid;
BEGIN
    SELECT COALESCE(completed_by, assigned_to)
    INTO v_worker
    FROM housekeeping_tasks
    WHERE tenant_id = NEW.tenant_id AND id = NEW.task_id;

    IF v_worker IS NOT NULL AND v_worker = NEW.inspected_by THEN
        RAISE EXCEPTION 'Housekeeping inspection requires a different user';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_housekeeping_inspection_separation
    BEFORE INSERT ON housekeeping_inspections
    FOR EACH ROW EXECUTE FUNCTION reject_housekeeping_inspection_self_approval();

CREATE TRIGGER trg_housekeeping_inspections_lifecycle
    BEFORE INSERT OR UPDATE OR DELETE ON housekeeping_inspections
    FOR EACH ROW EXECUTE FUNCTION guard_tenant_operational_write();
CREATE TRIGGER trg_room_blocks_lifecycle
    BEFORE INSERT OR UPDATE OR DELETE ON room_blocks
    FOR EACH ROW EXECUTE FUNCTION guard_tenant_operational_write();
CREATE TRIGGER trg_lost_custody_lifecycle
    BEFORE INSERT OR UPDATE OR DELETE ON lost_and_found_custody_events
    FOR EACH ROW EXECUTE FUNCTION guard_tenant_operational_write();
CREATE TRIGGER trg_room_state_transitions_lifecycle
    BEFORE INSERT OR UPDATE OR DELETE ON room_state_transitions
    FOR EACH ROW EXECUTE FUNCTION guard_tenant_operational_write();
CREATE TRIGGER trg_room_blocks_updated_at
    BEFORE UPDATE ON room_blocks
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE housekeeping_inspections ENABLE ROW LEVEL SECURITY;
ALTER TABLE housekeeping_inspections FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON housekeeping_inspections
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());
ALTER TABLE room_blocks ENABLE ROW LEVEL SECURITY;
ALTER TABLE room_blocks FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON room_blocks
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());
ALTER TABLE lost_and_found_custody_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE lost_and_found_custody_events FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON lost_and_found_custody_events
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());
ALTER TABLE room_state_transitions ENABLE ROW LEVEL SECURITY;
ALTER TABLE room_state_transitions FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON room_state_transitions
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

ALTER TABLE housekeeping_tasks FORCE ROW LEVEL SECURITY;
ALTER TABLE maintenance_requests FORCE ROW LEVEL SECURITY;
ALTER TABLE work_orders FORCE ROW LEVEL SECURITY;
ALTER TABLE lost_and_found FORCE ROW LEVEL SECURITY;

GRANT SELECT, INSERT, UPDATE ON TABLE
    property_housekeeping_settings,
    housekeeping_tasks,
    housekeeping_assignments,
    housekeeping_inspections,
    maintenance_requests,
    work_orders,
    room_blocks,
    lost_and_found,
    lost_and_found_custody_events,
    room_state_transitions
TO pms_app;

GRANT SELECT, INSERT, UPDATE ON TABLE housekeeping_tasks TO pms_worker;
REVOKE DELETE ON TABLE
    housekeeping_inspections,
    lost_and_found_custody_events,
    room_state_transitions
FROM pms_app, pms_worker;
