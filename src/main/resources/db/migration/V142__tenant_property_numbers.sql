-- V142 — the immutable, human-facing reference a support call or a printed invoice can use
-- instead of a UUID. Crockford Base32 (excludes I/L/O/U so it's safe to read aloud or type
-- back), backend-generated, never derived from anything about the record it names.
--
-- Application code (TenantOnboardingService, PropertyManagementService) generates its own
-- value with SecureRandom and retries on the rare unique-index collision — that's the real
-- generation path, and it owns the entropy source. The DEFAULT below exists for the same
-- reason `id uuid DEFAULT gen_random_uuid()` already does on both tables: a safety net for any
-- insert that doesn't specify one (fixtures, ad-hoc tooling, a future caller that forgets),
-- rather than a NOT NULL column with no fallback.

CREATE OR REPLACE FUNCTION generate_human_identifier(p_prefix text) RETURNS text AS $$
DECLARE
    alphabet text := '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
    raw bytea := gen_random_bytes(5);
    buffer bigint := 0;
    bits_in_buffer int := 0;
    result text := '';
    byte_index int;
    byte_value int;
    symbol_index int;
BEGIN
    FOR byte_index IN 0..4 LOOP
        byte_value := get_byte(raw, byte_index);
        buffer := (buffer << 8) | byte_value;
        bits_in_buffer := bits_in_buffer + 8;
        WHILE bits_in_buffer >= 5 LOOP
            bits_in_buffer := bits_in_buffer - 5;
            symbol_index := (buffer >> bits_in_buffer) & 31;
            result := result || substr(alphabet, symbol_index + 1, 1);
        END LOOP;
    END LOOP;
    RETURN p_prefix || '-' || result;
END;
$$ LANGUAGE plpgsql VOLATILE;

ALTER TABLE tenants ADD COLUMN tenant_number text NOT NULL DEFAULT generate_human_identifier('TN');
ALTER TABLE properties ADD COLUMN property_number text NOT NULL DEFAULT generate_human_identifier('PR');

CREATE UNIQUE INDEX uq_tenants_tenant_number ON tenants (tenant_number);
CREATE UNIQUE INDEX uq_properties_property_number ON properties (property_number);
