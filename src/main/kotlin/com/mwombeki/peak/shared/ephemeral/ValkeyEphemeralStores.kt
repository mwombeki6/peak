package com.mwombeki.peak.shared.ephemeral

import java.time.Duration
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript

/**
 * The fast path. Everything that can go wrong with the cache is reported as
 * [EphemeralStoreUnavailableException] so that the pair above has exactly one thing to
 * decide, and so that "the cache is sick" can never arrive at a caller disguised as "the
 * subject is within its limit".
 *
 * `StringRedisTemplate` stops here. Nothing outside this file — and certainly no business
 * module — holds a Redis type, because the day one does is the day Valkey stops being
 * something the deployment is allowed to lose.
 */
class ValkeyRateLimitStore(
    private val redis: StringRedisTemplate,
    private val keyPrefix: String,
) : RateLimitStore {

    override fun consume(
        scope: RateLimitScope,
        subject: String,
        limit: Int,
        window: Duration,
    ): RateLimitDecision {
        requireWindow(limit, window)
        val key = ephemeralKey(keyPrefix, scope.keyspace, subject)
        val answer = runConsume(key, window)

        // A script that answered in an unexpected shape is a broken store, not an allow.
        val used = (answer.getOrNull(0) as? Number)?.toLong()
            ?: throw EphemeralStoreUnavailableException("Valkey returned no counter value")
        val remaining = (answer.getOrNull(1) as? Number)?.toLong() ?: window.toMillis()

        return decide(used, limit, Duration.ofMillis(remaining))
    }

    override fun reset(scope: RateLimitScope, subject: String) {
        val key = ephemeralKey(keyPrefix, scope.keyspace, subject)
        try {
            redis.delete(key)
        } catch (ex: DataAccessException) {
            throw EphemeralStoreUnavailableException("Valkey refused a rate-limit reset", ex)
        }
    }

    private fun runConsume(key: String, window: Duration): List<*> {
        val answer = try {
            redis.execute(CONSUME, listOf(key), window.toMillis().toString())
        } catch (ex: DataAccessException) {
            throw EphemeralStoreUnavailableException("Valkey refused a rate-limit consume", ex)
        }
        return answer
            ?: throw EphemeralStoreUnavailableException("Valkey returned no rate-limit result")
    }

    private companion object {
        /**
         * One round trip, and no gap in which a counter can exist without a deadline: INCR
         * creates the key and the same script gives it one.
         *
         * The expiry is re-applied whenever PTTL reports there is none, which matters more
         * than it looks. A counter that lost its deadline — a client that died between two
         * commands in some earlier implementation, an operator's PERSIST — would otherwise
         * lock its subject out permanently, and the subject is a member of staff trying to
         * sign in.
         */
        val CONSUME: RedisScript<List<*>> = RedisScript.of(
            """
            local used = redis.call('INCR', KEYS[1])
            local ttl = redis.call('PTTL', KEYS[1])
            if ttl < 0 then
                redis.call('PEXPIRE', KEYS[1], ARGV[1])
                ttl = tonumber(ARGV[1])
            end
            return { used, ttl }
            """.trimIndent(),
            List::class.java,
        )
    }
}

class ValkeyEphemeralStateStore(
    private val redis: StringRedisTemplate,
    private val keyPrefix: String,
) : EphemeralStateStore {

    override fun put(scope: EphemeralStateScope, key: String, value: String, ttl: Duration) {
        require(!ttl.isZero && !ttl.isNegative) { "Ephemeral state requires a positive ttl" }
        val namespaced = ephemeralKey(keyPrefix, scope.keyspace, key)
        try {
            redis.opsForValue().set(namespaced, value, ttl)
        } catch (ex: DataAccessException) {
            throw EphemeralStoreUnavailableException("Valkey refused an ephemeral write", ex)
        }
    }

    override fun get(scope: EphemeralStateScope, key: String): String? {
        val namespaced = ephemeralKey(keyPrefix, scope.keyspace, key)
        return try {
            redis.opsForValue().get(namespaced)
        } catch (ex: DataAccessException) {
            throw EphemeralStoreUnavailableException("Valkey refused an ephemeral read", ex)
        }
    }

    override fun remove(scope: EphemeralStateScope, key: String) {
        val namespaced = ephemeralKey(keyPrefix, scope.keyspace, key)
        try {
            redis.delete(namespaced)
        } catch (ex: DataAccessException) {
            throw EphemeralStoreUnavailableException("Valkey refused an ephemeral delete", ex)
        }
    }
}
