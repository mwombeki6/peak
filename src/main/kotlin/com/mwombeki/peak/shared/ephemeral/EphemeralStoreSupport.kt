package com.mwombeki.peak.shared.ephemeral

import java.time.Duration

/**
 * Valkey has one flat keyspace, so the scope has to be carried in the key itself. The
 * PostgreSQL store keeps the same two parts in two columns, where the scope is also what
 * the expiry index is organised by.
 */
internal fun ephemeralKey(keyPrefix: String, keyspace: String, subject: String): String {
    requireSubject(subject)
    return "$keyPrefix:$keyspace:$subject"
}

/**
 * Valkey keys are visible to `MONITOR`, the slowlog and a memory dump, and the instance has
 * no volume and no encryption at rest. A raw guest phone number as a key would put personal
 * data somewhere with none of the protections the database gives it, so callers pass an
 * already-opaque subject — a hashed identity, an account id — and this only refuses the
 * empty case it can actually detect.
 */
internal fun requireSubject(subject: String) {
    require(subject.isNotBlank()) { "Ephemeral subject must not be blank" }
}

/** `make_interval(secs => ?)` takes a double, and this is the only place that conversion lives. */
internal fun intervalSeconds(duration: Duration): Double = duration.toMillis() / 1000.0

internal fun requireWindow(limit: Int, window: Duration) {
    require(limit > 0) { "Rate limit must be greater than zero" }
    require(!window.isZero && !window.isNegative) { "Rate limit window must be positive" }
}

internal fun decide(used: Long, limit: Int, remaining: Duration): RateLimitDecision {
    val allowed = used <= limit
    return RateLimitDecision(
        allowed = allowed,
        used = used,
        limit = limit,
        retryAfter = if (allowed) Duration.ZERO else remaining,
    )
}
