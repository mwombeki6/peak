package com.mwombeki.peak.usermanagement.internal

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.usermanagement.internal.application.StaffCredentialService
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * A manager creates the person and hands over a one-time code. The staff member chooses their
 * own PIN, and nobody else ever learns it.
 *
 * That asymmetry is the point. A manager who knows everyone's PIN can act as anyone, and from
 * that moment the audit trail records the wrong person for every action — which is worse than
 * having no audit trail, because it reads as evidence.
 *
 * Six digits is a million combinations, which is not much. It is acceptable here only because
 * it is never sufficient on its own: valid only inside a registered device context, only for
 * permissions marked operational, and only until the session expires. Three conditions, and
 * weakening any one of them makes the other two insufficient.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class StaffCredentialIntegrationTests {

    @Autowired private lateinit var credentials: StaffCredentialService
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @AfterTest
    fun resetSession() {
        jdbcTemplate.execute("RESET ALL")
    }

    @Test
    fun activationLetsStaffChooseAPinTheManagerNeverSees() {
        val staff = seedStaff()
        val secret = credentials.issueActivation(staff.tenantId, staff.userId, staff.managerId)

        credentials.activate(staff.tenantId, staff.staffNumber, secret.plaintext, "418205")

        assertEquals(
            staff.userId,
            credentials.verify(staff.tenantId, staff.propertyId, staff.localSuffix, "418205"),
        )
        assertNull(credentials.verify(staff.tenantId, staff.propertyId, staff.localSuffix, "000000"))
    }

    /**
     * The stored verifier must not contain the PIN in any recoverable form. With only a million
     * possibilities, a database dump would otherwise yield every staff PIN in the hotel.
     */
    @Test
    fun thePinIsNotRecoverableFromStorage() {
        val staff = activatedStaff(pin = "418205")

        val stored = requireNotNull(
            jdbcTemplate.queryForObject(
                "SELECT pin_hash FROM staff_credentials WHERE user_id = ?",
                String::class.java,
                staff.userId,
            ),
        )

        assertFalse(stored.contains("418205"))
        assertTrue(stored.startsWith("\$2"), "expected a bcrypt verifier, found $stored")
    }

    /**
     * A one-time secret is one time. Otherwise a manager who saw the slip could set the PIN
     * themselves at any point afterwards, which is exactly what this design exists to prevent.
     */
    @Test
    fun anActivationSecretCannotBeUsedTwice() {
        val staff = seedStaff()
        val secret = credentials.issueActivation(staff.tenantId, staff.userId, staff.managerId)
        credentials.activate(staff.tenantId, staff.staffNumber, secret.plaintext, "418205")

        assertFailsWith<IllegalArgumentException> {
            credentials.activate(staff.tenantId, staff.staffNumber, secret.plaintext, "999999")
        }
        assertEquals(
            staff.userId,
            credentials.verify(staff.tenantId, staff.propertyId, staff.localSuffix, "418205"),
            "the original PIN must survive a failed second activation",
        )
    }

    @Test
    fun aWrongActivationSecretIsRefused() {
        val staff = seedStaff()
        credentials.issueActivation(staff.tenantId, staff.userId, staff.managerId)

        assertFailsWith<IllegalArgumentException> {
            credentials.activate(staff.tenantId, staff.staffNumber, "000000000", "418205")
        }
    }

    /** The PINs an attacker tries first must not be available to choose. */
    @Test
    fun obviousPinsAreRefused() {
        listOf("000000", "111111", "123456", "654321", "012345", "999999").forEach { weak ->
            val staff = seedStaff()
            val secret = credentials.issueActivation(
                staff.tenantId, staff.userId, staff.managerId,
            )

            assertFailsWith<IllegalArgumentException>("$weak should be refused") {
                credentials.activate(staff.tenantId, staff.staffNumber, secret.plaintext, weak)
            }
        }
    }

    @Test
    fun aPinMustBeSixDigits() {
        listOf("12345", "1234567", "41820a", "", "  4182 ").forEach { malformed ->
            val staff = seedStaff()
            val secret = credentials.issueActivation(
                staff.tenantId, staff.userId, staff.managerId,
            )

            assertFailsWith<IllegalArgumentException>("'$malformed' should be refused") {
                credentials.activate(
                    staff.tenantId, staff.staffNumber, secret.plaintext, malformed,
                )
            }
        }
    }

    /**
     * Lockout is on the account here. The device half lives on the terminal, because a device
     * gate has to run before this one — an unregistered terminal must never reach a PIN check
     * at all.
     */
    @Test
    fun repeatedWrongPinsLockTheAccount() {
        val staff = activatedStaff(pin = "418205")

        repeat(5) { credentials.verify(staff.tenantId, staff.propertyId, staff.localSuffix, "000000") }

        assertNull(
            credentials.verify(staff.tenantId, staff.propertyId, staff.localSuffix, "418205"),
            "the correct PIN must not work while the account is locked",
        )
    }

    /** A correct PIN clears the count, so ordinary mistyping never accumulates into a lockout. */
    @Test
    fun asuccessfulEntryForgivesEarlierMistakes() {
        val staff = activatedStaff(pin = "418205")

        repeat(4) { credentials.verify(staff.tenantId, staff.propertyId, staff.localSuffix, "000000") }
        assertNotNull(credentials.verify(staff.tenantId, staff.propertyId, staff.localSuffix, "418205"))
        repeat(4) { credentials.verify(staff.tenantId, staff.propertyId, staff.localSuffix, "000000") }

        assertNotNull(
            credentials.verify(staff.tenantId, staff.propertyId, staff.localSuffix, "418205"),
            "four mistakes, a success, then four more must not equal eight",
        )
    }

    /** Issuing a fresh secret is how a reset works, and it must kill the old PIN immediately. */
    @Test
    fun aManagerCanResetButNotRead() {
        val staff = activatedStaff(pin = "418205")

        val fresh = credentials.issueActivation(staff.tenantId, staff.userId, staff.managerId)

        assertNull(
            credentials.verify(staff.tenantId, staff.propertyId, staff.localSuffix, "418205"),
            "issuing a new activation secret must invalidate the old PIN at once",
        )
        credentials.activate(staff.tenantId, staff.staffNumber, fresh.plaintext, "770311")
        assertEquals(
            staff.userId,
            credentials.verify(staff.tenantId, staff.propertyId, staff.localSuffix, "770311"),
        )
    }

    /**
     * Local sequences restart per property, so two hotels both have an 00001. The same local
     * suffix and the same PIN at two properties must resolve to two different people — that is
     * the whole reason the lookup is scoped by property rather than global.
     */
    @Test
    fun theSameLocalSuffixAndPinAtTwoPropertiesAreTwoDifferentPeople() {
        val ours = activatedStaff(pin = "418205")
        val theirs = activatedStaff(pin = "418205")

        assertEquals(ours.localSuffix, theirs.localSuffix, "both properties should start at 00001")
        assertEquals(ours.userId, credentials.verify(ours.tenantId, ours.propertyId, "00001", "418205"))
        assertEquals(theirs.userId, credentials.verify(theirs.tenantId, theirs.propertyId, "00001", "418205"))
        assertNotEquals(ours.userId, theirs.userId)
    }

    /** A number that exists only at another property must not resolve here at all. */
    @Test
    fun aStaffNumberThatExistsOnlyAtAnotherPropertyIsNotFound() {
        val ours = activatedStaff(pin = "418205")
        val theirs = activatedStaff(pin = "770311")
        val theirSecond = hireAt(theirs.tenantId, theirs.propertyId)
        val secret = credentials.issueActivation(
            theirs.tenantId, theirSecond.userId, theirSecond.managerId,
        )
        credentials.activate(theirs.tenantId, theirSecond.staffNumber, secret.plaintext, "550194")

        assertEquals("00002", theirSecond.localSuffix, "the second hire at that property")
        assertNull(
            credentials.verify(ours.tenantId, ours.propertyId, "00002", "550194"),
            "our property has no 00002, and the other property's must not leak across",
        )
    }

    private fun activatedStaff(pin: String): Staff {
        val staff = seedStaff()
        val secret = credentials.issueActivation(staff.tenantId, staff.userId, staff.managerId)
        credentials.activate(staff.tenantId, staff.staffNumber, secret.plaintext, pin)
        return staff
    }

    private fun seedStaff(): Staff {
        val planId = UUID.randomUUID()
        val tenantId = UUID.randomUUID()

        jdbcTemplate.update(
            "INSERT INTO plans (id, name, code) VALUES (?, ?, ?)",
            planId, "Plan $planId", "plan-$planId",
        )
        jdbcTemplate.update(
            "INSERT INTO tenants (id, name, slug, schema_name, plan_id) VALUES (?, ?, ?, ?, ?)",
            tenantId, "Tenant $tenantId", "tenant-$tenantId",
            "tenant_$tenantId".replace("-", "_"), planId,
        )
        return seedStaffIn(tenantId)
    }

    private fun seedStaffIn(tenantId: UUID): Staff {
        val propertyId = UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO properties (id, tenant_id, name) VALUES (?, ?, ?)",
            propertyId, tenantId, "Property $propertyId",
        )
        return hireAt(tenantId, propertyId)
    }

    /** A second hire at an already-seeded property, so its local sequence advances instead of restarting. */
    private fun hireAt(tenantId: UUID, propertyId: UUID): Staff {
        val managerId = UUID.randomUUID()
        val userId = UUID.randomUUID()

        jdbcTemplate.update(
            """
            INSERT INTO users (id, tenant_id, full_name, email, status, is_active)
            VALUES (?, ?, 'Manager', ?, 'active', true)
            """.trimIndent(),
            managerId, tenantId, "mgr-$managerId@example.com",
        )
        jdbcTemplate.update(
            """
            INSERT INTO users (id, tenant_id, full_name, status, is_active)
            VALUES (?, ?, 'Amina Hassan', 'active', true)
            """.trimIndent(),
            userId, tenantId,
        )
        val staffNumber = requireNotNull(
            jdbcTemplate.queryForObject(
                "SELECT allocate_property_staff_number(?, ?, ?)",
                String::class.java,
                tenantId, propertyId, userId,
            ),
        )
        return Staff(tenantId, propertyId, userId, managerId, staffNumber)
    }

    private data class Staff(
        val tenantId: UUID,
        val propertyId: UUID,
        val userId: UUID,
        val managerId: UUID,
        val staffNumber: String,
    ) {
        /** The local sequence typed on a terminal — everything after the last '-' in the full number. */
        val localSuffix: String get() = staffNumber.substringAfterLast('-')
    }
}
