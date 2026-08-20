package com.mwombeki.peak.shared.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CrockfordBase32Tests {

    @Test
    fun encodesAllZeroBytesAsAllZeroCharacters() {
        assertEquals("00000000", CrockfordBase32.encode(ByteArray(5)))
    }

    @Test
    fun encodesAllOnesBytesAsAllZCharacters() {
        val allOnes = ByteArray(5) { 0xFF.toByte() }
        assertEquals("ZZZZZZZZ", CrockfordBase32.encode(allOnes))
    }

    @Test
    fun encodesASingleByteIntoTwoCharacters() {
        assertEquals("ZW", CrockfordBase32.encode(byteArrayOf(0xFF.toByte())))
        assertEquals("00", CrockfordBase32.encode(byteArrayOf(0x00)))
    }

    @Test
    fun fiveBytesProduceExactlyEightCharacters() {
        val random = java.security.SecureRandom()
        repeat(50) {
            val bytes = ByteArray(5)
            random.nextBytes(bytes)
            assertEquals(8, CrockfordBase32.encode(bytes).length)
        }
    }

    @Test
    fun neverEmitsVisuallyConfusableLetters() {
        val random = java.security.SecureRandom()
        repeat(200) {
            val bytes = ByteArray(5)
            random.nextBytes(bytes)
            val encoded = CrockfordBase32.encode(bytes)
            assertTrue(encoded.none { it in "ILOU" }, "encoded value '$encoded' contained a banned letter")
        }
    }

    @Test
    fun isDeterministic() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        assertEquals(CrockfordBase32.encode(bytes), CrockfordBase32.encode(bytes))
    }
}
