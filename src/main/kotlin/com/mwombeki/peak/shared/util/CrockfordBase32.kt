package com.mwombeki.peak.shared.util

/**
 * Crockford's Base32 (https://www.crockford.com/base32.html) — excludes I, L, O and U so an
 * operator reading a tenant/property number aloud, or a support agent typing one back in, never
 * has to guess whether a character was a letter or a digit.
 */
object CrockfordBase32 {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder()
        var buffer = 0
        var bitsInBuffer = 0
        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bitsInBuffer += 8
            while (bitsInBuffer >= 5) {
                bitsInBuffer -= 5
                out.append(ALPHABET[(buffer shr bitsInBuffer) and 0x1F])
            }
        }
        if (bitsInBuffer > 0) {
            out.append(ALPHABET[(buffer shl (5 - bitsInBuffer)) and 0x1F])
        }
        return out.toString()
    }
}
