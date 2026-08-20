package com.mwombeki.peak.shared.ephemeral

import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * The outage policy, in code.
 *
 * A Valkey that is unreachable moves the limiter to PostgreSQL. It does not raise the
 * limit, it does not skip the check, and it does not let the request through — those are
 * the three ways a cache outage quietly becomes an open door, and none of them is reachable
 * from here.
 *
 * A PostgreSQL failure on the fallback path is deliberately not caught. If the database is
 * also gone the caller sees the failure and the request is refused, which is the safe
 * answer; swallowing it here is the only way rate limiting could disappear without anyone
 * being told.
 */
class FallbackRateLimitStore(
    private val cache: RateLimitStore,
    private val database: RateLimitStore,
) : RateLimitStore {

    private val degradation = EphemeralDegradation(logger, "rate limiting")

    override fun consume(
        scope: RateLimitScope,
        subject: String,
        limit: Int,
        window: Duration,
    ): RateLimitDecision {
        return try {
            val decision = cache.consume(scope, subject, limit, window)
            degradation.answered()
            decision
        } catch (ex: EphemeralStoreUnavailableException) {
            degradation.unavailable(ex)
            database.consume(scope, subject, limit, window)
        }
    }

    override fun reset(scope: RateLimitScope, subject: String) {
        // Both, always. A reset that only cleared the cache would leave the database
        // counter to refuse the very subject that has just proved it is legitimate, and a
        // reset that only cleared the database would be undone the moment Valkey recovered.
        try {
            cache.reset(scope, subject)
            degradation.answered()
        } catch (ex: EphemeralStoreUnavailableException) {
            degradation.unavailable(ex)
        }
        database.reset(scope, subject)
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(FallbackRateLimitStore::class.java)
    }
}

/**
 * Same policy, weaker stakes: a miss here costs a database round trip, never a decision.
 */
class FallbackEphemeralStateStore(
    private val cache: EphemeralStateStore,
    private val database: EphemeralStateStore,
) : EphemeralStateStore {

    private val degradation = EphemeralDegradation(logger, "ephemeral state")

    override fun put(scope: EphemeralStateScope, key: String, value: String, ttl: Duration) {
        try {
            cache.put(scope, key, value, ttl)
            degradation.answered()
        } catch (ex: EphemeralStoreUnavailableException) {
            degradation.unavailable(ex)
            database.put(scope, key, value, ttl)
        }
    }

    override fun get(scope: EphemeralStateScope, key: String): String? {
        return try {
            val value = cache.get(scope, key)
            degradation.answered()
            value
        } catch (ex: EphemeralStoreUnavailableException) {
            degradation.unavailable(ex)
            database.get(scope, key)
        }
    }

    override fun remove(scope: EphemeralStateScope, key: String) {
        // A revocation that only removed one copy is a revocation that comes back.
        try {
            cache.remove(scope, key)
            degradation.answered()
        } catch (ex: EphemeralStoreUnavailableException) {
            degradation.unavailable(ex)
        }
        database.remove(scope, key)
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(FallbackEphemeralStateStore::class.java)
    }
}

/**
 * Reports the edges of an outage rather than every call inside one.
 *
 * Logging each fallback would produce a line per request for as long as Valkey is down,
 * which buries the one line an operator needs and costs more than the failure it describes.
 */
private class EphemeralDegradation(
    private val logger: Logger,
    private val what: String,
) {
    private val degraded = AtomicBoolean(false)

    fun unavailable(cause: EphemeralStoreUnavailableException) {
        if (degraded.compareAndSet(false, true)) {
            logger.warn("Valkey is unreachable; {} is now served by PostgreSQL", what, cause)
        }
    }

    fun answered() {
        if (degraded.compareAndSet(true, false)) {
            logger.info("Valkey is answering again; {} is back on the cache", what)
        }
    }
}
