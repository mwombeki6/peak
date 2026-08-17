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
            credentials.verify(staff.tenantId, staff.staffNumber, "418205"),
        )
        assertNull(credentials.verify(staff.tenantId, staff.staffNumber, "000000"))
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
            credentials.verify(staff.tenantId, staff.staffNumber, "418205"),
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

        repeat(5) { credentials.verify(staff.tenantId, staff.staffNumber, "000000") }

        assertNull(
            credentials.verify(staff.tenantId, staff.staffNumber, "418205"),
            "the correct PIN must not work while the account is locked",
        )
    }

    /** A correct PIN clears the count, so ordinary mistyping never accumulates into a lockout. */
    @Test
    fun asuccessfulEntryForgivesEarlierMistakes() {
        val staff = activatedStaff(pin = "418205")

        repeat(4) { credentials.verify(staff.tenantId, staff.staffNumber, "000000") }
        assertNotNull(credentials.verify(staff.tenantId, staff.staffNumber, "418205"))
        repeat(4) { credentials.verify(staff.tenantId, staff.staffNumber, "000000") }

        assertNotNull(
            credentials.verify(staff.tenantId, staff.staffNumber, "418205"),
            "four mistakes, a success, then four more must not equal eight",
        )
    }

    /** Issuing a fresh secret is how a reset works, and it must kill the old PIN immediately. */
    @Test
    fun aManagerCanResetButNotRead() {
        val staff = activatedStaff(pin = "418205")

        val fresh = credentials.issueActivation(staff.tenantId, staff.userId, staff.managerId)

        assertNull(
            credentials.verify(staff.tenantId, staff.staffNumber, "418205"),
            "issuing a new activation secret must invalidate the old PIN at once",
        )
        credentials.activate(staff.tenantId, staff.staffNumber, fresh.plaintext, "770311")
        assertEquals(
            staff.userId,
            credentials.verify(staff.tenantId, staff.staffNumber, "770311"),
        )
    }

    /**
     * Staff numbers restart per tenant, so two hotels both have an 0001. The same number and
     * the same PIN in two tenants must resolve to two different people — that is the whole
     * reason the lookup is tenant-scoped rather than global.
     */
    @Test
    fun theSameNumberAndPinInTwoTenantsAreTwoDifferentPeople() {
        val ours = activatedStaff(pin = "418205")
        val theirs = activatedStaff(pin = "418205")

        assertEquals(ours.staffNumber, theirs.staffNumber, "both tenants should start at 0001")
        assertEquals(ours.userId, credentials.verify(ours.tenantId, "0001", "418205"))
        assertEquals(theirs.userId, credentials.verify(theirs.tenantId, "0001", "418205"))
        assertNotEquals(ours.userId, theirs.userId)
    }

    /** A number that exists only in another tenant must not resolve here at all. */
    @Test
    fun aStaffNumberThatExistsOnlyElsewhereIsNotFound() {
        val ours = activatedStaff(pin = "418205")
        val theirs = activatedStaff(pin = "770311")
        val theirSecond = seedStaffIn(theirs.tenantId)
        val secret = credentials.issueActivation(
            theirs.tenantId, theirSecond.userId, theirSecond.managerId,
        )
        credentials.activate(theirs.tenantId, theirSecond.staffNumber, secret.plaintext, "550194")

        assertEquals("0002", theirSecond.staffNumber, "the second staff member in that tenant")
        assertNull(
            credentials.verify(ours.tenantId, "0002", "550194"),
            "our tenant has no 0002, and the other tenant's must not leak across",
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
        val managerId = UUID.randomUUID()
        val userId = UUID.randomUUID()

        jdbcTemplate.update(
            """
            INSERT INTO users (id, tenant_id, full_name, email, status, is_active)
            VALUES (?, ?, 'Manager', ?, 'active', true)
            """.trimIndent(),
            managerId, tenantId, "mgr-$managerId@example.com",
        )
        val staffNumber = requireNotNull(
            jdbcTemplate.queryForObject(
                "SELECT allocate_staff_number(?)", String::class.java, tenantId,
            ),
        )
        jdbcTemplate.update(
            """
            INSERT INTO users (id, tenant_id, full_name, staff_number, status, is_active)
            VALUES (?, ?, 'Amina Hassan', ?, 'active', true)
            """.trimIndent(),
            userId, tenantId, staffNumber,
        )
        return Staff(tenantId, userId, managerId, staffNumber)
    }

    private data class Staff(
        val tenantId: UUID,
        val userId: UUID,
        val managerId: UUID,
        val staffNumber: String,
    )
}
