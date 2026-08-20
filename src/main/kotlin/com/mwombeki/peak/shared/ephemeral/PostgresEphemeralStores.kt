package com.mwombeki.peak.shared.ephemeral

import java.time.Duration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/**
 * What answers when Valkey does not.
 *
 * This is not a second cache. It is the reason an operator can lose the cache without
 * losing the limiter, so it does the one thing the cache does — count attempts in a fixed
 * window — with the same semantics and more slowly.
 *
 * The counter runs in its own transaction rather than the caller's. A limiter rolled back
 * with the work it was guarding stops counting exactly the attempts worth counting: the
 * ones that failed. Every refused sign-in and every rejected PIN would be erased along with
 * the failure that produced it, and no budget would ever be reached.
 */
class PostgresRateLimitStore(
    private val jdbcTemplate: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
    private val pruneBatchSize: Int,
) : RateLimitStore {

    private val ownTransaction = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    override fun consume(
        scope: RateLimitScope,
        subject: String,
        limit: Int,
        window: Duration,
    ): RateLimitDecision {
        requireWindow(limit, window)
        requireSubject(subject)

        val counted = requireNotNull(
            ownTransaction.execute { _ -> count(scope, subject, window) },
        ) { "Rate limit transaction returned no counter" }

        return decide(counted.used, limit, Duration.ofMillis(counted.remainingMillis))
    }

    override fun reset(scope: RateLimitScope, subject: String) {
        requireSubject(subject)
        ownTransaction.execute { _ ->
            jdbcTemplate.update(
                "DELETE FROM ephemeral_rate_limit_counters WHERE scope = ? AND subject = ?",
                scope.keyspace,
                subject,
            )
        }
    }

    /**
     * One statement does the whole decision, so two terminals hitting the same PIN in the
     * same millisecond cannot both read three and both write four.
     *
     * The CASE arms are the window rollover: a row whose deadline has passed is reused from
     * one rather than incremented, which is what keeps the table bounded by distinct
     * subjects instead of by attempts.
     */
    private fun count(scope: RateLimitScope, subject: String, window: Duration): Counted {
        pruneExpiredCounters(scope.keyspace)

        val seconds = intervalSeconds(window)
        val row = jdbcTemplate.queryForMap(
            """
            INSERT INTO ephemeral_rate_limit_counters AS c (scope, subject, used, expires_at)
            VALUES (?, ?, 1, now() + make_interval(secs => ?))
            ON CONFLICT (scope, subject) DO UPDATE
            SET used = CASE WHEN c.expires_at <= now() THEN 1 ELSE c.used + 1 END,
                expires_at = CASE
                                 WHEN c.expires_at <= now() THEN now() + make_interval(secs => ?)
                                 ELSE c.expires_at
                             END
            RETURNING used,
                      GREATEST(
                          0,
                          EXTRACT(EPOCH FROM (expires_at - now())) * 1000
                      )::bigint AS remaining_millis
            """.trimIndent(),
            scope.keyspace,
            subject,
            seconds,
            seconds,
        )

        return Counted(
            used = (row["used"] as Number).toLong(),
            remainingMillis = (row["remaining_millis"] as Number).toLong(),
        )
    }

    private fun pruneExpiredCounters(scope: String) {
        jdbcTemplate.update(
            """
            DELETE FROM ephemeral_rate_limit_counters
            WHERE ctid IN (
                SELECT ctid
                FROM ephemeral_rate_limit_counters
                WHERE scope = ?
                  AND expires_at <= now()
                ORDER BY expires_at
                LIMIT ?
                FOR UPDATE SKIP LOCKED
            )
            """.trimIndent(),
            scope,
            pruneBatchSize,
        )
    }

    private data class Counted(val used: Long, val remainingMillis: Long)
}

/**
 * The same trade as [PostgresRateLimitStore], without the transaction isolation.
 *
 * A cache write that is rolled back with its caller loses a cache entry, which is the
 * behaviour a cache is allowed to have. A counter that is rolled back loses evidence of an
 * attempt, which is not.
 */
class PostgresEphemeralStateStore(
    private val jdbcTemplate: JdbcTemplate,
    private val pruneBatchSize: Int,
) : EphemeralStateStore {

    override fun put(scope: EphemeralStateScope, key: String, value: String, ttl: Duration) {
        require(!ttl.isZero && !ttl.isNegative) { "Ephemeral state requires a positive ttl" }
        requireSubject(key)
        pruneExpiredEntries(scope.keyspace)

        jdbcTemplate.update(
            """
            INSERT INTO ephemeral_state_entries AS e (scope, entry_key, entry_value, expires_at)
            VALUES (?, ?, ?, now() + make_interval(secs => ?))
            ON CONFLICT (scope, entry_key) DO UPDATE
            SET entry_value = EXCLUDED.entry_value,
                expires_at = EXCLUDED.expires_at
            """.trimIndent(),
            scope.keyspace,
            key,
            value,
            intervalSeconds(ttl),
        )
    }

    /**
     * The deadline is enforced in the predicate, not by the sweep, so a row that outlived
     * it is invisible from the moment it did rather than from whenever something got round
     * to deleting it.
     */
    override fun get(scope: EphemeralStateScope, key: String): String? {
        requireSubject(key)
        val rows = jdbcTemplate.queryForList(
            """
            SELECT entry_value
            FROM ephemeral_state_entries
            WHERE scope = ?
              AND entry_key = ?
              AND expires_at > now()
            """.trimIndent(),
            scope.keyspace,
            key,
        )
        return rows.firstOrNull()?.get("entry_value") as String?
    }

    override fun remove(scope: EphemeralStateScope, key: String) {
        requireSubject(key)
        jdbcTemplate.update(
            "DELETE FROM ephemeral_state_entries WHERE scope = ? AND entry_key = ?",
            scope.keyspace,
            key,
        )
    }

    private fun pruneExpiredEntries(scope: String) {
        jdbcTemplate.update(
            """
            DELETE FROM ephemeral_state_entries
            WHERE ctid IN (
                SELECT ctid
                FROM ephemeral_state_entries
                WHERE scope = ?
                  AND expires_at <= now()
                ORDER BY expires_at
                LIMIT ?
                FOR UPDATE SKIP LOCKED
            )
            """.trimIndent(),
            scope,
            pruneBatchSize,
        )
    }
}
