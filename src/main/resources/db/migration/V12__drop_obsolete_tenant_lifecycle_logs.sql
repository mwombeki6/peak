-- The canonical tenant lifecycle audit stream is tenant_lifecycle_events.
-- V11 introduced a duplicate table without FKs/RLS; remove it forward-only.
DROP TABLE IF EXISTS tenant_lifecycle_logs;
