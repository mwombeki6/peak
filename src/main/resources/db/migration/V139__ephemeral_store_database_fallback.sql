-- Valkey holds the rate-limit counters. These two tables hold them when Valkey does not.
--
-- The outage policy is that losing the cache may cost latency and may cost a window of
-- counting, and may never cost the limit itself. With nowhere durable to count, a Valkey
-- restart during a credential-stuffing run removes every per-IP, per-phone and per-account
-- bound on the platform at once, the run continues at full speed, and every dashboard
-- reports the system healthy throughout — because it is, apart from the thing that was
-- supposed to be stopping the run.
--
-- Neither table is authoritative for anything. No decision is later justified by a row
-- here, nothing reconciles against them, and truncating both mid-shift costs one window of
-- throttling. Reservations, folios, payments, fiscal documents, staff membership and the
-- night audit are untouched by that, which is the property that makes this safe to keep
-- outside the model rather than inside it.
--
-- There is no tenant column, deliberately. A flood from one address and a mistyped code
-- name no hotel, and inventing an owner for them produces exactly the defect V137 had to
-- undo, where a miss belonging to nobody was charged to every tenant at once. With no
-- tenant to bind there is no tenant predicate to enforce, so these are closed by grant
-- instead: the three runtime logins may count, and nothing else. pms_readonly_support is
-- not granted here, and the blanket SELECT V14 issued was a one-time grant that does not
-- reach tables created after it — support can read what a guest paid, and has no business
-- reading how often that guest mistyped a code.

CREATE TABLE ephemeral_rate_limit_counters (
    scope text NOT NULL,
    subject text NOT NULL,
    used bigint NOT NULL CHECK (used > 0),
    expires_at timestamptz NOT NULL,
    PRIMARY KEY (scope, subject)
);

-- Orders the opportunistic sweep and keeps it inside one scope, so a sweep running for
-- staff PINs never walks the rows belonging to unauthenticated request counting.
CREATE INDEX idx_ephemeral_rate_limit_expiry
    ON ephemeral_rate_limit_counters (scope, expires_at);

COMMENT ON TABLE ephemeral_rate_limit_counters IS
    'Fixed-window attempt counters, mirroring what Valkey holds so that losing Valkey '
    'costs latency rather than the limit. Not authoritative: no decision is justified by '
    'a row here and truncating it costs one throttling window.';

COMMENT ON COLUMN ephemeral_rate_limit_counters.subject IS
    'Opaque already-hashed identity, address or account id. Never a raw phone number: '
    'these rows outlive the request and are readable by every runtime login.';

CREATE TABLE ephemeral_state_entries (
    scope text NOT NULL,
    entry_key text NOT NULL,
    entry_value text NOT NULL,
    expires_at timestamptz NOT NULL,
    PRIMARY KEY (scope, entry_key)
);

CREATE INDEX idx_ephemeral_state_expiry
    ON ephemeral_state_entries (scope, expires_at);

COMMENT ON TABLE ephemeral_state_entries IS
    'Short-lived cached values — revocation hints, presence, bounded read caches — that a '
    'restart is allowed to lose. A miss means "ask the authoritative table", never "no".';

GRANT SELECT, INSERT, UPDATE, DELETE ON
    ephemeral_rate_limit_counters,
    ephemeral_state_entries
TO pms_app, pms_platform, pms_worker;

DO $migration$
DECLARE
    runtime_role text;
    privilege text;
    target text;
BEGIN
    FOREACH target IN ARRAY ARRAY[
        'ephemeral_rate_limit_counters',
        'ephemeral_state_entries'
    ]
    LOOP
        -- The upsert reads through RETURNING and the sweep deletes, so a missing privilege
        -- surfaces as a failed limiter under load rather than at deployment.
        FOREACH runtime_role IN ARRAY ARRAY['pms_app', 'pms_platform', 'pms_worker']
        LOOP
            FOREACH privilege IN ARRAY ARRAY['SELECT', 'INSERT', 'UPDATE', 'DELETE']
            LOOP
                IF NOT pg_catalog.has_table_privilege(
                    runtime_role, 'public.' || target, privilege
                ) THEN
                    RAISE EXCEPTION
                        '% cannot % on %, so the PostgreSQL limiter cannot answer when '
                        'Valkey is down', runtime_role, privilege, target;
                END IF;
            END LOOP;
        END LOOP;

        IF pg_catalog.has_table_privilege(
            'pms_readonly_support', 'public.' || target, 'SELECT'
        ) THEN
            RAISE EXCEPTION
                'pms_readonly_support can read %, which exposes per-guest throttling '
                'state to support without a tenant predicate to bound it', target;
        END IF;
    END LOOP;
END;
$migration$;
