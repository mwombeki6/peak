@file:NamedInterface("util")

package com.mwombeki.peak.shared.util

import java.security.SecureRandom
import org.springframework.dao.DuplicateKeyException
import org.springframework.modulith.NamedInterface

/**
 * Backend-generated, immutable, human-facing identifiers — tenant numbers, property numbers,
 * and anything else in this shape. Cryptographically random and never derived from anything
 * about the record they name, so knowing one reveals nothing about the tenant/property it
 * belongs to.
 */
@NamedInterface("util")
class HumanIdentifierGenerator(private val random: SecureRandom = SecureRandom()) {

    /** 5 random bytes → 8 Crockford Base32 characters, prefixed. `generate("TN")` → `TN-H8Q5T2MV`. */
    fun generate(prefix: String): String {
        val bytes = ByteArray(5)
        random.nextBytes(bytes)
        return "$prefix-${CrockfordBase32.encode(bytes)}"
    }
}

/**
 * True only when this violation is the named unique constraint — a caller retrying identifier
 * generation must not swallow a different collision (e.g. a user-chosen code) and misreport it.
 *
 * The Postgres driver is a `runtimeOnly` dependency (deliberately — nothing in this module
 * compiles against driver-specific types), so this reads the constraint name out of the
 * exception's message chain rather than `PSQLException.serverErrorMessage`, matching the text
 * Postgres itself reports: `duplicate key value violates unique constraint "<name>"`.
 */
fun DuplicateKeyException.violatesConstraint(constraintName: String): Boolean {
    var throwable: Throwable? = this
    while (throwable != null) {
        if (throwable.message?.contains("\"$constraintName\"") == true) return true
        throwable = throwable.cause
    }
    return false
}
