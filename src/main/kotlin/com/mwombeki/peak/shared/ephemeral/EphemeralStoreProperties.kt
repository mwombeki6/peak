package com.mwombeki.peak.shared.ephemeral

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Connection settings are Spring's `spring.data.redis.*`; only what Peak decides lives here.
 */
@ConfigurationProperties(prefix = "peak.ephemeral")
data class EphemeralStoreProperties(
    val valkey: Valkey = Valkey(),

    /**
     * Namespaces every key Peak writes.
     *
     * The instance is Peak's alone today, and the prefix costs nothing while that holds.
     * It is what makes an ACL that grants one key pattern possible later without rewriting
     * the adapters, and it makes an unexpected key obvious to anyone reading the keyspace.
     */
    val keyPrefix: String = "peak",

    /**
     * How many expired rows the PostgreSQL store may retire per call.
     *
     * The fallback table is bounded by the number of distinct subjects ever seen, not by
     * call volume, so a small opportunistic sweep is enough and a scheduled job would be a
     * second thing to operate.
     */
    val databasePruneBatchSize: Int = 100,
) {
    data class Valkey(
        val enabled: Boolean = false,
    )
}
