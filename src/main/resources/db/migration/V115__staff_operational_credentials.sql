-- V115 — the credential a staff member types on a terminal.
--
-- A manager issues a one-time activation secret; the staff member chooses the
-- PIN. A manager who knows everyone's PIN can act as anyone, and from that
-- moment the audit trail names the wrong person for every action — worse than
-- no audit trail, because it reads as evidence.
--
-- Six digits is a million combinations. That is acceptable only because it is
-- never sufficient alone: valid only inside a registered device context (V116),
-- only for permissions marked operational (V114), and only until the session
-- expires (V117). Weakening any one of those three makes the other two
-- insufficient.
--
-- The verifier is bcrypt over a peppered PIN. The pepper is configuration, not
-- data, so a stolen database yields nothing on its own — with a search space
-- this small, a plain hash would give up every PIN in the hotel. It cannot be
-- retrofitted either: re-peppering requires plaintext nobody keeps, so adding it
-- later would mean resetting every staff member.

CREATE TABLE staff_credentials (
    user_id uuid PRIMARY KEY REFERENCES users(id) DEFERRABLE,
    tenant_id uuid NOT NULL REFERENCES tenants(id) DEFERRABLE,
    pin_hash text NOT NULL,
    pin_set_at timestamptz NOT NULL DEFAULT now(),
    failed_attempts integer NOT NULL DEFAULT 0,
    locked_until timestamptz,
    last_verified_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_staff_credentials_attempts CHECK (failed_attempts >= 0)
);

COMMENT ON TABLE staff_credentials IS
    'Verifier for the PIN a staff member types on a registered device. Never readable: a '
    'manager may reset a PIN and can never learn one.';

CREATE TABLE staff_activation_secrets (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES tenants(id) DEFERRABLE,
    user_id uuid NOT NULL REFERENCES users(id) DEFERRABLE,
    secret_hash text NOT NULL,
    issued_by uuid NOT NULL REFERENCES users(id) DEFERRABLE,
    issued_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    CONSTRAINT chk_staff_activation_window CHECK (expires_at > issued_at)
);

-- One live secret per staff member. Issuing a new one is how a reset works, so
-- two live secrets would mean two people could set the same person's PIN.
CREATE UNIQUE INDEX uq_staff_activation_live
    ON staff_activation_secrets (tenant_id, user_id)
    WHERE consumed_at IS NULL;

CREATE INDEX idx_staff_activation_user
    ON staff_activation_secrets (tenant_id, user_id, issued_at DESC);

ALTER TABLE staff_credentials ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff_credentials FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON staff_credentials
    USING (tenant_id = current_tenant_id());

ALTER TABLE staff_activation_secrets ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff_activation_secrets FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON staff_activation_secrets
    USING (tenant_id = current_tenant_id());

GRANT SELECT, INSERT, UPDATE ON staff_credentials TO pms_app;
GRANT SELECT, INSERT, UPDATE ON staff_activation_secrets TO pms_app;

-- A credential history that can be deleted is not a credential history. A PIN is
-- retired by being replaced, and a secret by being consumed; neither disappears.
REVOKE DELETE ON staff_credentials FROM pms_app, pms_worker;
REVOKE DELETE ON staff_activation_secrets FROM pms_app, pms_worker;

DO $migration$
BEGIN
    -- A credential table without RLS is a credential table every tenant can read.
    IF NOT EXISTS (
        SELECT 1 FROM pg_class
        WHERE relname = 'staff_credentials' AND relrowsecurity AND relforcerowsecurity
    ) THEN
        RAISE EXCEPTION 'staff_credentials must have row level security enabled and forced';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_class
        WHERE relname = 'staff_activation_secrets' AND relrowsecurity AND relforcerowsecurity
    ) THEN
        RAISE EXCEPTION
            'staff_activation_secrets must have row level security enabled and forced';
    END IF;
END;
$migration$;
