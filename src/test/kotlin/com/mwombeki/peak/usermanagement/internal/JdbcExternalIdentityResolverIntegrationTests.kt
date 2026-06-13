package com.mwombeki.peak.usermanagement.internal

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.shared.context.ExternalIdentityPrincipal
import com.mwombeki.peak.shared.context.ExternalIdentityResolver
import com.mwombeki.peak.shared.context.ResolvedExternalIdentity
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Testcontainers

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class JdbcExternalIdentityResolverIntegrationTests {

    @Autowired
    private lateinit var resolver: ExternalIdentityResolver

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun resolvesActiveTenantIdentityLink() {
        val fixture = tenantIdentityFixture()
        insertTenantIdentityFixture(fixture)

        val resolved = resolver.resolve(
            ExternalIdentityPrincipal(
                issuer = fixture.issuer,
                subject = fixture.subject,
            ),
        )

        assertEquals(
            ResolvedExternalIdentity.Tenant(
                tenantId = fixture.tenantId,
                tenantUserId = fixture.userId,
            ),
            resolved,
        )

        val lastSeenAt = jdbcTemplate.queryForObject(
            """
            SELECT last_seen_at
            FROM identity_links
            WHERE id = ?
            """.trimIndent(),
            java.time.OffsetDateTime::class.java,
            fixture.identityLinkId,
        )
        assertNotNull(lastSeenAt)
    }

    @Test
    fun doesNotResolveInactiveTenantUser() {
        val fixture = tenantIdentityFixture(userStatus = "disabled")
        insertTenantIdentityFixture(fixture)

        val resolved = resolver.resolve(
            ExternalIdentityPrincipal(
                issuer = fixture.issuer,
                subject = fixture.subject,
            ),
        )

        assertNull(resolved)
    }

    @Test
    fun doesNotResolveRevokedTenantIdentityLink() {
        val fixture = tenantIdentityFixture(revoked = true)
        insertTenantIdentityFixture(fixture)

        val resolved = resolver.resolve(
            ExternalIdentityPrincipal(
                issuer = fixture.issuer,
                subject = fixture.subject,
            ),
        )

        assertNull(resolved)
    }

    @Test
    fun resolvesActivePlatformIdentityLink() {
        val fixture = platformIdentityFixture()
        insertPlatformIdentityFixture(fixture)

        val resolved = resolver.resolve(
            ExternalIdentityPrincipal(
                issuer = fixture.issuer,
                subject = fixture.subject,
            ),
        )

        assertEquals(
            ResolvedExternalIdentity.Platform(fixture.platformUserId),
            resolved,
        )

        val wasSeen = jdbcTemplate.queryForObject(
            """
            SELECT last_seen_at IS NOT NULL
            FROM identity_links
            WHERE id = ?
            """.trimIndent(),
            Boolean::class.java,
            fixture.identityLinkId,
        )
        assertTrue(wasSeen == true)
    }

    private fun tenantIdentityFixture(
        userStatus: String = "active",
        revoked: Boolean = false,
    ): TenantIdentityFixture {
        val tenantId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val subject = "tenant-subject-$userId"
        return TenantIdentityFixture(
            planId = UUID.randomUUID(),
            tenantId = tenantId,
            userId = userId,
            identityLinkId = UUID.randomUUID(),
            issuer = "https://issuer.example.com/realms/$tenantId",
            subject = subject,
            email = "$subject@example.com",
            userStatus = userStatus,
            revoked = revoked,
        )
    }

    private fun platformIdentityFixture(): PlatformIdentityFixture {
        val platformUserId = UUID.randomUUID()
        val subject = "platform-subject-$platformUserId"
        return PlatformIdentityFixture(
            platformUserId = platformUserId,
            identityLinkId = UUID.randomUUID(),
            issuer = "https://issuer.example.com/realms/platform",
            subject = subject,
            email = "$subject@example.com",
        )
    }

    private fun insertTenantIdentityFixture(fixture: TenantIdentityFixture) {
        insertPlan(fixture.planId)
        insertTenant(fixture.tenantId, fixture.planId)
        jdbcTemplate.update(
            """
            INSERT INTO users (
                id,
                tenant_id,
                full_name,
                email,
                status,
                is_active
            )
            VALUES (?, ?, ?, ?, ?, true)
            """.trimIndent(),
            fixture.userId,
            fixture.tenantId,
            "Tenant User ${fixture.userId}",
            fixture.email,
            fixture.userStatus,
        )
        jdbcTemplate.update(
            """
            INSERT INTO identity_links (
                id,
                identity_mode,
                provider,
                issuer,
                subject,
                tenant_id,
                user_id,
                email,
                revoked_at
            )
            VALUES (?, 'tenant', 'oidc', ?, ?, ?, ?, ?, CASE WHEN ? THEN now() ELSE NULL END)
            """.trimIndent(),
            fixture.identityLinkId,
            fixture.issuer,
            fixture.subject,
            fixture.tenantId,
            fixture.userId,
            fixture.email,
            fixture.revoked,
        )
    }

    private fun insertPlatformIdentityFixture(fixture: PlatformIdentityFixture) {
        jdbcTemplate.update(
            """
            INSERT INTO platform_users (id, full_name, email, status)
            VALUES (?, ?, ?, 'active')
            """.trimIndent(),
            fixture.platformUserId,
            "Platform User ${fixture.platformUserId}",
            fixture.email,
        )
        jdbcTemplate.update(
            """
            INSERT INTO identity_links (
                id,
                identity_mode,
                provider,
                issuer,
                subject,
                platform_user_id,
                email
            )
            VALUES (?, 'platform', 'oidc', ?, ?, ?, ?)
            """.trimIndent(),
            fixture.identityLinkId,
            fixture.issuer,
            fixture.subject,
            fixture.platformUserId,
            fixture.email,
        )
    }

    private fun insertPlan(id: UUID) {
        jdbcTemplate.update(
            """
            INSERT INTO plans (id, name, code)
            VALUES (?, ?, ?)
            """.trimIndent(),
            id,
            "Plan $id",
            "plan-$id",
        )
    }

    private fun insertTenant(id: UUID, planId: UUID) {
        jdbcTemplate.update(
            """
            INSERT INTO tenants (
                id,
                name,
                slug,
                schema_name,
                plan_id
            )
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
            id,
            "Tenant $id",
            "tenant-$id",
            "tenant_$id".replace("-", "_"),
            planId,
        )
    }

    private data class TenantIdentityFixture(
        val planId: UUID,
        val tenantId: UUID,
        val userId: UUID,
        val identityLinkId: UUID,
        val issuer: String,
        val subject: String,
        val email: String,
        val userStatus: String,
        val revoked: Boolean,
    )

    private data class PlatformIdentityFixture(
        val platformUserId: UUID,
        val identityLinkId: UUID,
        val issuer: String,
        val subject: String,
        val email: String,
    )
}
