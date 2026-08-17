package com.mwombeki.peak.shared.ephemeral

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager

/**
 * Which store a module gets, and why it never has to ask.
 *
 * With Valkey switched on, [RateLimitStore] and [EphemeralStateStore] resolve to the
 * fallback pair — cache first, database behind it. With Valkey off, they resolve to the
 * PostgreSQL adapters directly. Both arrangements satisfy the same interface, so a
 * developer machine with no cache, a migration runtime that needs none, and a production
 * API node all inject the same type and none of them contains a branch on which it got.
 */
@Configuration(proxyBeanMethods = false)
class EphemeralStoreConfiguration {

    @Bean
    fun databaseRateLimitStore(
        jdbcTemplate: JdbcTemplate,
        transactionManager: PlatformTransactionManager,
        properties: EphemeralStoreProperties,
    ): PostgresRateLimitStore = PostgresRateLimitStore(
        jdbcTemplate = jdbcTemplate,
        transactionManager = transactionManager,
        pruneBatchSize = properties.databasePruneBatchSize,
    )

    @Bean
    fun databaseEphemeralStateStore(
        jdbcTemplate: JdbcTemplate,
        properties: EphemeralStoreProperties,
    ): PostgresEphemeralStateStore = PostgresEphemeralStateStore(
        jdbcTemplate = jdbcTemplate,
        pruneBatchSize = properties.databasePruneBatchSize,
    )

    @Bean
    @ConditionalOnProperty(
        prefix = "peak.ephemeral.valkey",
        name = ["enabled"],
        havingValue = "true",
    )
    fun valkeyRateLimitStore(
        stringRedisTemplate: StringRedisTemplate,
        properties: EphemeralStoreProperties,
    ): ValkeyRateLimitStore = ValkeyRateLimitStore(stringRedisTemplate, properties.keyPrefix)

    @Bean
    @ConditionalOnProperty(
        prefix = "peak.ephemeral.valkey",
        name = ["enabled"],
        havingValue = "true",
    )
    fun valkeyEphemeralStateStore(
        stringRedisTemplate: StringRedisTemplate,
        properties: EphemeralStoreProperties,
    ): ValkeyEphemeralStateStore =
        ValkeyEphemeralStateStore(stringRedisTemplate, properties.keyPrefix)

    @Bean
    @Primary
    @ConditionalOnProperty(
        prefix = "peak.ephemeral.valkey",
        name = ["enabled"],
        havingValue = "true",
    )
    fun rateLimitStore(
        cache: ValkeyRateLimitStore,
        database: PostgresRateLimitStore,
    ): RateLimitStore = FallbackRateLimitStore(cache, database)

    @Bean
    @Primary
    @ConditionalOnProperty(
        prefix = "peak.ephemeral.valkey",
        name = ["enabled"],
        havingValue = "true",
    )
    fun ephemeralStateStore(
        cache: ValkeyEphemeralStateStore,
        database: PostgresEphemeralStateStore,
    ): EphemeralStateStore = FallbackEphemeralStateStore(cache, database)
}
