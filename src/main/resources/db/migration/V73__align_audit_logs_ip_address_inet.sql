-- Align audit_logs.ip_address with platform_audit_logs, which already stores
-- request provenance as inet. Both audit streams capture the same validated
-- remote address; keeping the tenant stream as text was an inconsistency that
-- blocked inet containment/range filters on audit provenance.
--
-- The application validates remote addresses before storage, so stored values
-- are IP-shaped or NULL. The conversion still routes every legacy value through
-- an exception-safe cast so a single malformed row can never fail a deploy:
-- unparseable values become NULL instead of aborting the migration.
--
-- ALTER COLUMN TYPE rewrites the table under an ACCESS EXCLUSIVE lock and does
-- not fire the row-level append-only trigger (that guards DML, not DDL). Run it
-- in the dedicated migration runtime during a deploy window.

CREATE FUNCTION pg_temp.audit_text_to_inet(value text) RETURNS inet
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF value IS NULL OR btrim(value) = '' THEN
        RETURN NULL;
    END IF;
    RETURN btrim(value)::inet;
EXCEPTION
    WHEN others THEN
        RETURN NULL;
END;
$$;

ALTER TABLE audit_logs
    ALTER COLUMN ip_address TYPE inet
    USING pg_temp.audit_text_to_inet(ip_address);
