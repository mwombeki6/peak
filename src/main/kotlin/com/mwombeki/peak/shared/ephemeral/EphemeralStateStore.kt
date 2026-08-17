package com.mwombeki.peak.shared.ephemeral

import java.time.Duration

/**
 * Short-lived keyed values that a restart is allowed to lose.
 *
 * The contract is deliberately weak: a read may return null at any time, for any reason,
 * and a caller that cannot tolerate that is holding the wrong kind of data. Every value
 * here is an accelerator in front of a PostgreSQL answer, never the answer.
 *
 * The dangerous shape is a cache read that is treated as an authorization. A revocation
 * cache says "this session is known to be revoked"; a miss says nothing at all, and the
 * authoritative check still has to run.
 */
interface EphemeralStateStore {

    fun put(scope: EphemeralStateScope, key: String, value: String, ttl: Duration)

    /** The stored value, or null when absent, expired, or unreachable. */
    fun get(scope: EphemeralStateScope, key: String): String?

    fun remove(scope: EphemeralStateScope, key: String)
}

/**
 * The values Peak is allowed to keep outside PostgreSQL.
 *
 * Closed for the same reason as [RateLimitScope]. Reservations, orders, payments, folios,
 * fiscal documents, staff and property membership, KYB decisions, OTP challenges,
 * permissions and night audit state are absent because losing any of them is a data loss
 * incident, not a cold cache.
 */
enum class EphemeralStateScope(val keyspace: String) {
    /** Positive revocation hints only; a miss means "ask the database". */
    REVOCATION_CACHE("revocation"),

    /** Who is currently connected, for display. Rebuilt by the next heartbeat. */
    PRESENCE("presence"),

    /** A read-through cache, added only where profiling showed the repeated read. */
    BOUNDED_CACHE("cache"),
}
