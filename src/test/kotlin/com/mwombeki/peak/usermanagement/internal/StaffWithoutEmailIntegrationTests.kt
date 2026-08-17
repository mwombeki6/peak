package com.mwombeki.peak.usermanagement.internal

import com.mwombeki.peak.TestcontainersConfiguration
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * A waiter is a person, not an email address.
 *
 * `users.email` was `NOT NULL`, so onboarding a restaurant meant inventing an address for every
 * waiter, cashier and housekeeper — `amina0142@noemail.peak.local` and the like. That is
 * identity data which is garbage by construction, and it then has to be trusted never to
 * receive anything, forever, by everyone who touches it. A staff member with no email and no
 * phone is a completely ordinary hotel employee.
 *
 * The staff number is what replaces it as the thing a person types. Unique per **tenant**
 * rather than per property, so a group can move someone between hotels without reissuing it,
 * and stable across role changes because a role is an assignment while this is identity — a
 * waiter promoted to supervisor keeps `0142`.
 *
 * The `status` half of this is a separate defect that happens to live in the same column set.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class StaffWithoutEmailIntegrationTests {

    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @AfterTest
    fun resetSession() {
        jdbcTemplate.execute("RESET ALL")
    }

    @Test
    fun aStaffMemberCanExistWithNoEmailAddress() {
        val tenantId = seedTenant()
        val staffNumber = allocate(tenantId)

        jdbcTemplate.update(
            """
            INSERT INTO users (id, tenant_id, full_name, staff_number, status, is_active)
            VALUES (?, ?, 'Amina Hassan', ?, 'active', true)
            """.trimIndent(),
            UUID.randomUUID(), tenantId, staffNumber,
        )

        assertEquals("0001", staffNumber)
        assertNull(
            jdbcTemplate.queryForObject(
                "SELECT email FROM users WHERE tenant_id = ? AND staff_number = ?",
                String::class.java,
                tenantId, staffNumber,
            ),
            "no address should have been invented on her behalf",
        )
    }

    /** Several staff without email must coexist — NULLs are distinct in the unique index. */
    @Test
    fun manyStaffCanShareTheAbsenceOfAnEmailAddress() {
        val tenantId = seedTenant()

        repeat(3) { insertStaff(tenantId, allocate(tenantId)) }

        assertEquals(
            3,
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM users WHERE tenant_id = ? AND email IS NULL",
                Int::class.java,
                tenantId,
            ),
        )
    }

    @Test
    fun twoStaffInOneTenantCannotShareANumber() {
        val tenantId = seedTenant()
        insertStaff(tenantId, "0001")

        assertFailsWith<DuplicateKeyException> { insertStaff(tenantId, "0001") }
    }

    /** Numbers are per tenant. Two hotel groups both start at 0001 and never collide. */
    @Test
    fun staffNumbersRestartPerTenant() {
        val first = seedTenant()
        val second = seedTenant()

        assertEquals("0001", allocate(first).also { insertStaff(first, it) })
        assertEquals("0001", allocate(second).also { insertStaff(second, it) })
        assertEquals("0002", allocate(first))
    }

    /**
     * `status` is compared to `'active'` by `resolve_oidc_identity_link`,
     * `user_has_tenant_permission` and `user_has_property_permission`. It was nullable with no
     * CHECK, so a NULL read as "not active" in all three: the user could not authenticate, held
     * no permission anywhere, and nothing in the system said why.
     */
    @Test
    fun aUserCannotBeCreatedWithNoStatus() {
        val tenantId = seedTenant()

        val refused = assertFailsWith<DataIntegrityViolationException> {
            jdbcTemplate.update(
                "INSERT INTO users (id, tenant_id, full_name, status) VALUES (?, ?, 'X', NULL)",
                UUID.randomUUID(), tenantId,
            )
        }

        // Asserting the reason, not just the failure. Before email became nullable this test
        // passed against the email NOT NULL constraint and proved nothing about status.
        assertContains(refused.message.orEmpty(), "status", ignoreCase = true)
    }

    @Test
    fun aUserCannotBeCreatedWithAStatusNothingUnderstands() {
        val tenantId = seedTenant()

        val refused = assertFailsWith<DataIntegrityViolationException> {
            jdbcTemplate.update(
                "INSERT INTO users (id, tenant_id, full_name, status) VALUES (?, ?, 'X', 'asleep')",
                UUID.randomUUID(), tenantId,
            )
        }

        assertContains(refused.message.orEmpty(), "chk_users_status")
    }

    /** The control: the three statuses the lifecycle service actually writes are accepted. */
    @Test
    fun theStatusesTheApplicationWritesAreStillAccepted() {
        val tenantId = seedTenant()

        listOf("active", "locked", "disabled").forEach { status ->
            jdbcTemplate.update(
                "INSERT INTO users (id, tenant_id, full_name, status) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), tenantId, "Staff $status", status,
            )
        }

        assertEquals(
            3,
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM users WHERE tenant_id = ?",
                Int::class.java,
                tenantId,
            ),
        )
    }

    private fun allocate(tenantId: UUID): String =
        jdbcTemplate.queryForObject(
            "SELECT allocate_staff_number(?)",
            String::class.java,
            tenantId,
        )!!

    private fun insertStaff(tenantId: UUID, staffNumber: String) {
        jdbcTemplate.update(
            """
            INSERT INTO users (id, tenant_id, full_name, staff_number, status, is_active)
            VALUES (?, ?, 'Staff', ?, 'active', true)
            """.trimIndent(),
            UUID.randomUUID(), tenantId, staffNumber,
        )
    }

    private fun seedTenant(): UUID {
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
        return tenantId
    }
}
