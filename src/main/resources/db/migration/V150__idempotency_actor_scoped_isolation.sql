-- idempotency_keys was scoped for lookup and uniqueness by tenant_id alone:
--
--   idx_idempotency_keys_global_key  UNIQUE (idempotency_key)            WHERE tenant_id IS NULL AND status <> 'expired'
--   idx_idempotency_keys_tenant_key  UNIQUE (tenant_id, idempotency_key) WHERE tenant_id IS NOT NULL AND status <> 'expired'
--
-- Every non-tenant actor — platform operators, onboarding applicants, and unauthenticated
-- guests alike — has tenant_id NULL, so idx_idempotency_keys_global_key put all of them in one
-- shared global namespace: two different platform users, or two different onboarding
-- applicants, reusing the same Idempotency-Key value would collide on INSERT and then compete
-- for the same row in findExisting(). The request-hash comparison happens to prevent the worst
-- outcome (returning someone else's cached response) as long as the payload always embeds a
-- non-forgeable identity marker, but that was never an enforced invariant — it was incidental.
-- And within one tenant, two different tenant users reusing a key were never distinguished
-- either, for the same underlying reason.
--
-- actor_type/actor_id already exist on every row (set by JdbcIdempotencyPort since this table
-- was created) but were never part of the lookup or the uniqueness constraint. This makes them
-- load-bearing.

DROP INDEX idx_idempotency_keys_global_key;
DROP INDEX idx_idempotency_keys_tenant_key;

-- One constraint covers every row rather than two partial ones, because NULLS NOT DISTINCT is
-- what actually closes the gap: tenant_id, property_id and actor_id are all nullable
-- depending on identity type, and plain UNIQUE never treats two NULLs as colliding — which is
-- exactly the semantic that let every guest/platform/applicant actor share one slot before.
-- status <> 'expired' is carried forward from V28 unchanged: an expired row must not block a
-- fresh reservation from reusing the same key.
-- (idx_idempotency_keys_tenant_id_id, which backs outbox_events' tenant-guard FK, is a
-- separate concern — row identity, not lookup-key uniqueness — and is untouched.)
CREATE UNIQUE INDEX idx_idempotency_keys_actor_scope
    ON idempotency_keys (tenant_id, property_id, actor_type, actor_id, idempotency_key)
    NULLS NOT DISTINCT
    WHERE status <> 'expired';
