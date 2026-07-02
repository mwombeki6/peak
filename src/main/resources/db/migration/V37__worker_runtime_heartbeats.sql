CREATE TABLE worker_runtime_heartbeats (
    worker_id text PRIMARY KEY,
    status text NOT NULL,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    started_at timestamptz NOT NULL DEFAULT now(),
    last_seen_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_worker_runtime_heartbeats_id
        CHECK (worker_id = btrim(worker_id) AND length(worker_id) BETWEEN 3 AND 255),
    CONSTRAINT chk_worker_runtime_heartbeats_status
        CHECK (status IN ('running', 'stopped'))
);

CREATE INDEX idx_worker_runtime_heartbeats_active
    ON worker_runtime_heartbeats (last_seen_at DESC)
    WHERE status = 'running';

REVOKE ALL ON TABLE worker_runtime_heartbeats FROM PUBLIC;
GRANT SELECT ON TABLE worker_runtime_heartbeats TO pms_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE worker_runtime_heartbeats TO pms_worker;

COMMENT ON TABLE worker_runtime_heartbeats IS
    'Operational liveness leases written by worker runtimes and read by API readiness.';
