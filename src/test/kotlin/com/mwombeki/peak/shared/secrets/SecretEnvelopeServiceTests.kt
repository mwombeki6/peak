package com.mwombeki.peak.shared.secrets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import org.springframework.mock.env.MockEnvironment

class SecretEnvelopeServiceTests {
    private val environment = MockEnvironment()
    private val service = SecretEnvelopeService(
        properties = SecretEnvelopeProperties(
            keyReference = "literal:cGVhay1sb2NhbC1lbnZlbG9wZS1rZXktMzItYnl0ZSE=",
        ),
        secretReferenceResolver = SecretReferenceResolver(environment),
    )

    @Test
    fun encryptsWithUniqueNonceAndAuthenticatedContext() {
        val first = service.encrypt("one-time-token", "invitation-1")
        val second = service.encrypt("one-time-token", "invitation-1")

        assertNotEquals(first, second)
        assertEquals("one-time-token", service.decrypt(first, "invitation-1"))
        assertFailsWith<Exception> {
            service.decrypt(first, "invitation-2")
        }
    }

    @Test
    fun rejectsTamperedCiphertext() {
        val envelope = service.encrypt("one-time-token", "invitation-1")
        val tampered = envelope.dropLast(1) + if (envelope.last() == 'A') "B" else "A"

        assertFailsWith<Exception> {
            service.decrypt(tampered, "invitation-1")
        }
    }

    @Test
    fun decryptsPendingEnvelopeWithPreviousRotationKey() {
        val envelope = service.encrypt("one-time-token", "invitation-1")
        val rotated = SecretEnvelopeService(
            properties = SecretEnvelopeProperties(
                keyReference = "literal:bmV3LXBlYWstZW52ZWxvcGUta2V5LTMyLWJ5dGVzISE=",
                previousKeyReference =
                    "literal:cGVhay1sb2NhbC1lbnZlbG9wZS1rZXktMzItYnl0ZSE=",
            ),
            secretReferenceResolver = SecretReferenceResolver(environment),
        )

        assertEquals(
            "one-time-token",
            rotated.decrypt(envelope, "invitation-1"),
        )
    }
}
