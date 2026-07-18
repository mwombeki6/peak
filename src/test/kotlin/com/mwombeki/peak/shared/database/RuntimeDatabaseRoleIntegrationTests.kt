package com.mwombeki.peak.shared.database

import com.mwombeki.peak.TestcontainersConfiguration
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Testcontainers

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class RuntimeDatabaseRoleIntegrationTests {

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    @Autowired
    private lateinit var dataSource: DataSource

    @Test
    fun apiRoleCannotReadMigrationHistoryOrRunWorkerClaims() {
        assertFailsWith<DataAccessException> {
            inTransaction {
                setRole(API_ROLE)
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM flyway_schema_history",
                    Int::class.java,
                )
            }
        }

        assertFailsWith<DataAccessException> {
            inTransaction {
                setRole(API_ROLE)
                jdbcTemplate.queryForList(
                    "SELECT id FROM claim_outbox_events(?, ?, ?)",
                    UUID::class.java,
                    "api-runtime",
                    "platform",
                    1,
                )
            }
        }
    }

    @Test
    fun publicApiRoleRequiresTenantBindingForBookingWrites() {
        val fixture = insertPublicBookingFixture()

        val resolvedTenantId = inTransaction {
            setRole(API_ROLE)
            jdbcTemplate.queryForObject(
                "SELECT tenant_id FROM resolve_public_property_scope(?, 'booking_engine')",
                UUID::class.java,
                fixture.propertyId,
            )
        }
        assertEquals(fixture.tenantId, resolvedTenantId)

        assertFailsWith<DataAccessException> {
            inTransaction {
                setRole(API_ROLE)
                insertBookingSession(UUID.randomUUID(), fixture)
            }
        }

        val sessionId = UUID.randomUUID()
        inTransaction {
            setRole(API_ROLE)
            bindTenant(fixture.tenantId)
            assertEquals(1, insertBookingSession(sessionId, fixture))
            assertEquals(
                1,
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM booking_sessions WHERE id = ?",
                    Int::class.java,
                    sessionId,
                ),
            )
        }
    }

    @Test
    fun apiRoleCanPersistPhase2TenantPropertyAndCommunicationState() {
        val tenantFixture = insertTenantFixture(status = "active")
        val tenantRoleId = UUID.randomUUID()
        val propertyId = UUID.randomUUID()
        val contactId = UUID.randomUUID()

        inTransaction {
            setRole(API_ROLE)
            bindTenant(tenantFixture.tenantId)

            assertEquals(
                1,
                jdbcTemplate.update(
                    """
                    INSERT INTO tenant_roles (id, tenant_id, name, code, is_system, is_active)
                    VALUES (?, ?, ?, ?, false, true)
                    """.trimIndent(),
                    tenantRoleId,
                    tenantFixture.tenantId,
                    "Runtime Tenant Role $tenantRoleId",
                    "runtime-tenant-role-$tenantRoleId",
                ),
            )
            assertEquals(
                1,
                jdbcTemplate.update(
                    """
                    INSERT INTO properties (id, tenant_id, name, code, status, is_active)
                    VALUES (?, ?, ?, ?, 'draft', false)
                    """.trimIndent(),
                    propertyId,
                    tenantFixture.tenantId,
                    "Runtime Property $propertyId",
                    "R${propertyId.toString().take(8)}",
                ),
            )
            assertEquals(
                1,
                jdbcTemplate.update(
                    """
                    INSERT INTO tenant_contacts (id, tenant_id, full_name, status)
                    VALUES (?, ?, ?, 'active')
                    """.trimIndent(),
                    contactId,
                    tenantFixture.tenantId,
                    "Runtime Contact $contactId",
                ),
            )
        }
    }

    @Test
    fun platformRoleCanManageTenantsOnlyWithPlatformContextAndPermission() {
        val platformFixture = insertPlatformFixture()
        val tenantFixture = insertTenantFixture(status = "trial")

        assertFailsWith<DataAccessException> {
            inTransaction {
                setRole(API_ROLE)
                bindPlatform(platformFixture.platformUserId)
                jdbcTemplate.update(
                    "UPDATE tenants SET status = 'active' WHERE id = ?",
                    tenantFixture.tenantId,
                )
            }
        }

        inTransaction {
            setRole(PLATFORM_ROLE)
            bindPlatform(platformFixture.platformUserId)
            assertEquals(
                1,
                jdbcTemplate.update(
                    "UPDATE tenants SET status = 'active' WHERE id = ?",
                    tenantFixture.tenantId,
                ),
            )
            assertEquals(
                1,
                jdbcTemplate.update(
                    """
                    INSERT INTO tenant_lifecycle_events (
                        tenant_id,
                        event_type,
                        reason,
                        performed_by_platform_user_id
                    )
                    VALUES (?, 'activated', 'runtime role test', ?)
                    """.trimIndent(),
                    tenantFixture.tenantId,
                    platformFixture.platformUserId,
                ),
            )
        }
    }

    @Test
    fun tenantContinuityLockIsNarrowlyOwnedAndCallableOnlyByApiRole() {
        val tenant = insertTenantFixture(status = "active")
        val otherTenant = insertTenantFixture(status = "active")

        val functionSecurity = jdbcTemplate.queryForMap(
            """
            SELECT owner.rolname AS owner_name,
                   owner.rolcanlogin AS owner_can_login,
                   owner.rolinherit AS owner_inherits,
                   owner.rolsuper AS owner_is_superuser,
                   owner.rolbypassrls AS owner_bypasses_rls,
                   function.prosecdef AS security_definer,
                   COALESCE(
                       'search_path=pg_catalog, pg_temp' = ANY(function.proconfig),
                       false
                   ) AS safe_search_path
            FROM pg_catalog.pg_proc AS function
            JOIN pg_catalog.pg_namespace AS namespace
              ON namespace.oid = function.pronamespace
            JOIN pg_catalog.pg_roles AS owner
              ON owner.oid = function.proowner
            WHERE namespace.nspname = 'public'
              AND function.proname = 'lock_tenant_administrator_continuity'
              AND function.pronargs = 1
            """.trimIndent(),
        )
        assertEquals(CONTINUITY_OWNER_ROLE, functionSecurity["owner_name"])
        assertEquals(false, functionSecurity["owner_can_login"])
        assertEquals(false, functionSecurity["owner_inherits"])
        assertEquals(false, functionSecurity["owner_is_superuser"])
        assertEquals(false, functionSecurity["owner_bypasses_rls"])
        assertEquals(true, functionSecurity["security_definer"])
        assertEquals(true, functionSecurity["safe_search_path"])

        assertTrue(roleCanExecuteContinuityLock(API_ROLE))
        listOf(PLATFORM_ROLE, WORKER_ROLE, SUPPORT_ROLE).forEach { role ->
            assertFalse(roleCanExecuteContinuityLock(role))
            assertFailsWith<DataAccessException> {
                inTransaction {
                    setRole(role)
                    jdbcTemplate.queryForObject(
                        "SELECT public.lock_tenant_administrator_continuity(?)",
                        Boolean::class.java,
                        tenant.tenantId,
                    )
                }
            }
        }

        val locked = inTransaction {
            setRole(API_ROLE)
            bindTenant(tenant.tenantId)
            jdbcTemplate.queryForObject(
                "SELECT public.lock_tenant_administrator_continuity(?)",
                Boolean::class.java,
                tenant.tenantId,
            )
        }
        assertEquals(true, locked)

        assertFailsWith<DataAccessException> {
            inTransaction {
                setRole(API_ROLE)
                bindTenant(tenant.tenantId)
                jdbcTemplate.queryForObject(
                    "SELECT public.lock_tenant_administrator_continuity(?)",
                    Boolean::class.java,
                    otherTenant.tenantId,
                )
            }
        }

        assertFalse(roleHasTenantPrivilege(API_ROLE, "INSERT"))
        assertFalse(roleHasTenantPrivilege(API_ROLE, "UPDATE"))
        assertFalse(roleHasTenantPrivilege(API_ROLE, "DELETE"))
        assertFalse(roleHasTenantColumnPrivilege(API_ROLE, "id", "UPDATE"))
        assertFalse(roleHasTenantPrivilege(CONTINUITY_OWNER_ROLE, "UPDATE"))
        assertFalse(roleHasTenantPrivilege(CONTINUITY_OWNER_ROLE, "INSERT"))
        assertFalse(roleHasTenantPrivilege(CONTINUITY_OWNER_ROLE, "DELETE"))
        assertTrue(roleHasTenantColumnPrivilege(CONTINUITY_OWNER_ROLE, "id", "UPDATE"))
        assertFalse(
            roleHasTenantColumnPrivilege(CONTINUITY_OWNER_ROLE, "deleted_at", "UPDATE"),
        )
        assertEquals(
            0,
            jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM pg_catalog.pg_auth_members AS membership
                JOIN pg_catalog.pg_roles AS granted_role
                  ON granted_role.oid = membership.roleid
                JOIN pg_catalog.pg_roles AS member_role
                  ON member_role.oid = membership.member
                WHERE granted_role.rolname = ?
                   OR member_role.rolname = ?
                """.trimIndent(),
                Int::class.java,
                CONTINUITY_OWNER_ROLE,
                CONTINUITY_OWNER_ROLE,
            ),
        )
    }

    @Test
    fun apiRoleContinuityLockSerializesConcurrentBootstrapTransactions() {
        val tenant = insertTenantFixture(status = "active")
        val firstConnection = dataSource.connection
        val secondAttempting = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()

        try {
            firstConnection.autoCommit = false
            bindTenantRuntimeConnection(firstConnection, tenant.tenantId)
            assertTrue(acquireTenantContinuityLock(firstConnection, tenant.tenantId))

            val secondLock = executor.submit<Boolean> {
                dataSource.connection.use { secondConnection ->
                    secondConnection.autoCommit = false
                    try {
                        bindTenantRuntimeConnection(secondConnection, tenant.tenantId)
                        secondAttempting.countDown()
                        val acquired = acquireTenantContinuityLock(
                            secondConnection,
                            tenant.tenantId,
                        )
                        secondConnection.commit()
                        acquired
                    } catch (ex: Exception) {
                        secondConnection.rollback()
                        throw ex
                    }
                }
            }

            assertTrue(secondAttempting.await(5, TimeUnit.SECONDS))
            Thread.sleep(250)
            assertFalse(secondLock.isDone, "Second bootstrap must wait for the first transaction")

            firstConnection.commit()
            assertTrue(secondLock.get(5, TimeUnit.SECONDS))
        } finally {
            if (!firstConnection.isClosed) {
                runCatching { firstConnection.rollback() }
                firstConnection.close()
            }
            executor.shutdownNow()
        }
    }

    @Test
    fun onlyPlatformRuntimeCanEvaluateExactSupportSessionGrant() {
        val platformFixture = insertPlatformFixture()
        val tenantFixture = insertTenantFixture(status = "active")
        val supportSessionId = insertActiveSupportGrant(
            platformUserId = platformFixture.platformUserId,
            tenantId = tenantFixture.tenantId,
            actionCode = "platform.tenants.manage",
        )

        val allowed = inTransaction {
            setRole(PLATFORM_ROLE)
            bindPlatform(platformFixture.platformUserId)
            jdbcTemplate.queryForObject(
                "SELECT can_support_session_access_tenant(?, ?, ?, ?)",
                Boolean::class.java,
                platformFixture.platformUserId,
                supportSessionId,
                tenantFixture.tenantId,
                "platform.tenants.manage",
            )
        }
        assertEquals(true, allowed)

        assertFailsWith<DataAccessException> {
            inTransaction {
                setRole(API_ROLE)
                bindPlatform(platformFixture.platformUserId)
                jdbcTemplate.queryForObject(
                    "SELECT can_support_session_access_tenant(?, ?, ?, ?)",
                    Boolean::class.java,
                    platformFixture.platformUserId,
                    supportSessionId,
                    tenantFixture.tenantId,
                    "platform.tenants.manage",
                )
            }
        }
    }

    @Test
    fun tenantAndPlatformRuntimeRolesCannotCrossControlPlaneWriteBoundaries() {
        val platformFixture = insertPlatformFixture()
        val tenantFixture = insertTenantFixture(status = "active")

        assertFailsWith<DataAccessException> {
            inTransaction {
                setRole(API_ROLE)
                bindTenant(tenantFixture.tenantId)
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM platform_releases",
                    Int::class.java,
                )
            }
        }

        assertFailsWith<DataAccessException> {
            inTransaction {
                setRole(PLATFORM_ROLE)
                bindPlatform(platformFixture.platformUserId)
                jdbcTemplate.update(
                    "INSERT INTO properties (tenant_id, name, code) VALUES (?, 'Forbidden', 'NOPE')",
                    tenantFixture.tenantId,
                )
            }
        }
    }

    @Test
    fun platformRoleCanPersistAdministrationReliabilitySideEffects() {
        val platformFixture = insertPlatformFixture()
        val idempotencyId = UUID.randomUUID()
        val targetUserId = UUID.randomUUID()
        val outboxEventId = UUID.randomUUID()

        inTransaction {
            setRole(PLATFORM_ROLE)
            bindPlatform(platformFixture.platformUserId)

            assertEquals(
                1,
                jdbcTemplate.update(
                    """
                    INSERT INTO idempotency_keys (
                        id,
                        idempotency_key,
                        request_method,
                        request_path,
                        request_hash,
                        actor_type,
                        actor_id,
                        operation_type,
                        resource_type,
                        status,
                        expires_at
                    )
                    VALUES (?, ?, 'POST', '/api/v1/platform/users', ?, 'platform_user', ?, ?, ?, 'processing', now() + interval '1 day')
                    """.trimIndent(),
                    idempotencyId,
                    "runtime-platform-${UUID.randomUUID()}",
                    "runtime-request-hash",
                    platformFixture.platformUserId,
                    "platform.user.create",
                    "platform_users",
                ),
            )
            assertEquals(
                1,
                jdbcTemplate.update(
                    """
                    INSERT INTO platform_users (id, full_name, email, status)
                    VALUES (?, ?, ?, 'active')
                    """.trimIndent(),
                    targetUserId,
                    "Runtime Target $targetUserId",
                    "runtime-target-$targetUserId@example.com",
                ),
            )
            assertEquals(
                1,
                jdbcTemplate.update(
                    """
                    INSERT INTO outbox_events (
                        id,
                        aggregate_type,
                        aggregate_id,
                        event_type,
                        destination,
                        payload,
                        correlation_id,
                        idempotency_key_id
                    )
                    VALUES (?, 'platform_users', ?, 'platform.users.created', 'platform', '{}'::jsonb, ?, ?)
                    """.trimIndent(),
                    outboxEventId,
                    targetUserId,
                    UUID.randomUUID(),
                    idempotencyId,
                ),
            )
        }
    }

    @Test
    fun workerRoleUsesOutboxFunctionsWithoutDirectTableAccess() {
        val eventId = insertOutboxEvent(destination = "edge_sync")

        assertFailsWith<DataAccessException> {
            inTransaction {
                setRole(WORKER_ROLE)
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM outbox_events",
                    Int::class.java,
                )
            }
        }

        val claimedIds = inTransaction {
            setRole(WORKER_ROLE)
            jdbcTemplate.queryForList(
                "SELECT id FROM claim_outbox_events(?, ?, ?)",
                UUID::class.java,
                "runtime-worker",
                "edge_sync",
                10,
            )
        }

        assertTrue(eventId in claimedIds)
    }

    @Test
    fun workerRoleOwnsHeartbeatWritesWhileApiRoleIsReadOnly() {
        val workerId = "runtime-worker-${UUID.randomUUID()}"
        inTransaction {
            setRole(WORKER_ROLE)
            assertEquals(
                1,
                jdbcTemplate.update(
                    """
                    INSERT INTO worker_runtime_heartbeats (worker_id, status)
                    VALUES (?, 'running')
                    """.trimIndent(),
                    workerId,
                ),
            )
        }

        val visible = inTransaction {
            setRole(API_ROLE)
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM worker_runtime_heartbeats WHERE worker_id = ?",
                Int::class.java,
                workerId,
            )
        }
        assertEquals(1, visible)

        assertFailsWith<DataAccessException> {
            inTransaction {
                setRole(API_ROLE)
                jdbcTemplate.update(
                    "UPDATE worker_runtime_heartbeats SET status = 'stopped' WHERE worker_id = ?",
                    workerId,
                )
            }
        }
    }

    @Test
    fun workerRoleCanResolveOnlyBoundTenantConsentedChannels() {
        val tenant = insertTenantFixture(status = "active")
        val otherTenant = insertTenantFixture(status = "active")
        val channelId = insertConsentedChannel(tenant.tenantId)
        insertConsentedChannel(otherTenant.tenantId)

        val visibleWithoutTenant = inTransaction {
            setRole(WORKER_ROLE)
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM contact_channels",
                Int::class.java,
            )
        }
        assertEquals(0, visibleWithoutTenant)

        val visibleWithTenant = inTransaction {
            setRole(WORKER_ROLE)
            bindTenant(tenant.tenantId)
            jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM contact_channels cc
                JOIN tenant_contacts tc
                  ON tc.tenant_id = cc.tenant_id
                 AND tc.id = cc.contact_id
                 AND tc.status = 'active'
                 AND tc.deleted_at IS NULL
                WHERE cc.id = ?
                  AND cc.tenant_id = ?
                  AND cc.is_active = true
                  AND cc.verification_status = 'verified'
                  AND cc.deleted_at IS NULL
                  AND contact_channel_has_active_consent(
                        cc.tenant_id,
                        cc.contact_id,
                        cc.id,
                        'operational_reports'
                      )
                """.trimIndent(),
                Int::class.java,
                channelId,
                tenant.tenantId,
            )
        }
        assertEquals(1, visibleWithTenant)

        assertFailsWith<DataAccessException> {
            inTransaction {
                setRole(WORKER_ROLE)
                bindTenant(tenant.tenantId)
                jdbcTemplate.update(
                    "UPDATE contact_channels SET label = 'forbidden' WHERE id = ?",
                    channelId,
                )
            }
        }
    }

    @Test
    fun apiRoleCanEvaluateCrossTenantMockProviderGuardWithoutReadingAccounts() {
        val tenant = insertTenantFixture(status = "active")
        val providerId = UUID.randomUUID()
        val accountId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO payment_providers (
                id, tenant_id, provider_code, name, provider_type
            )
            VALUES (?, ?, 'contract_mock', 'Runtime Mock', 'mobile_money')
            """.trimIndent(),
            providerId,
            tenant.tenantId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO payment_provider_accounts (
                id, tenant_id, provider_id, account_name, secret_ref
            )
            VALUES (?, ?, ?, 'Runtime Mock Account', 'literal:test')
            """.trimIndent(),
            accountId,
            tenant.tenantId,
            providerId,
        )

        val activeMocks = inTransaction {
            setRole(API_ROLE)
            jdbcTemplate.queryForObject(
                "SELECT payment_account_count FROM active_contract_mock_provider_counts()",
                Long::class.java,
            )
        }

        assertTrue(requireNotNull(activeMocks) >= 1L)
        val directlyVisible = inTransaction {
            setRole(API_ROLE)
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM payment_provider_accounts",
                Int::class.java,
            )
        }
        assertEquals(0, directlyVisible)
    }

    private fun <T> inTransaction(block: () -> T): T {
        return transactionTemplate.execute { block() }
            ?: error("Transaction returned null")
    }

    private fun setRole(role: String) {
        require(role in setOf(API_ROLE, PLATFORM_ROLE, WORKER_ROLE, SUPPORT_ROLE)) {
            "Unexpected test role: $role"
        }
        jdbcTemplate.execute("SET LOCAL ROLE $role")
    }

    private fun roleCanExecuteContinuityLock(role: String): Boolean {
        return jdbcTemplate.queryForObject(
            """
            SELECT pg_catalog.has_function_privilege(
                ?,
                'public.lock_tenant_administrator_continuity(uuid)',
                'EXECUTE'
            )
            """.trimIndent(),
            Boolean::class.java,
            role,
        ) == true
    }

    private fun roleHasTenantPrivilege(role: String, privilege: String): Boolean {
        return jdbcTemplate.queryForObject(
            "SELECT pg_catalog.has_table_privilege(?, 'public.tenants', ?)",
            Boolean::class.java,
            role,
            privilege,
        ) == true
    }

    private fun roleHasTenantColumnPrivilege(
        role: String,
        column: String,
        privilege: String,
    ): Boolean {
        return jdbcTemplate.queryForObject(
            "SELECT pg_catalog.has_column_privilege(?, 'public.tenants', ?, ?)",
            Boolean::class.java,
            role,
            column,
            privilege,
        ) == true
    }

    private fun bindTenantRuntimeConnection(connection: Connection, tenantId: UUID) {
        connection.createStatement().use { statement ->
            statement.execute("SET LOCAL ROLE $API_ROLE")
        }
        connection.prepareStatement(
            "SELECT pg_catalog.set_config('app.current_tenant_id', ?, true)",
        ).use { statement ->
            statement.setString(1, tenantId.toString())
            statement.executeQuery().use { rows -> assertTrue(rows.next()) }
        }
    }

    private fun acquireTenantContinuityLock(connection: Connection, tenantId: UUID): Boolean {
        return connection.prepareStatement(
            "SELECT public.lock_tenant_administrator_continuity(?)",
        ).use { statement ->
            statement.setObject(1, tenantId)
            statement.executeQuery().use { rows ->
                assertTrue(rows.next())
                rows.getBoolean(1)
            }
        }
    }

    private fun bindTenant(tenantId: UUID) {
        setLocal("app.current_tenant_id", tenantId.toString())
        setLocal("app.current_tenant_user_id", "")
        setLocal("app.current_platform_user_id", "")
        jdbcTemplate.execute("SELECT assert_no_mixed_context()")
    }

    private fun bindPlatform(platformUserId: UUID) {
        setLocal("app.current_tenant_id", "")
        setLocal("app.current_tenant_user_id", "")
        setLocal("app.current_platform_user_id", platformUserId.toString())
        jdbcTemplate.execute("SELECT assert_no_mixed_context()")
    }

    private fun setLocal(name: String, value: String) {
        jdbcTemplate.queryForObject(
            "SELECT set_config(?, ?, true)",
            String::class.java,
            name,
            value,
        )
    }

    private fun insertPublicBookingFixture(): PublicBookingFixture {
        val tenantFixture = insertTenantFixture(status = "trial")
        val propertyId = UUID.randomUUID()

        jdbcTemplate.update(
            """
            INSERT INTO properties (id, tenant_id, name, code, status, is_active)
            VALUES (?, ?, ?, ?, 'active', true)
            """.trimIndent(),
            propertyId,
            tenantFixture.tenantId,
            "Runtime Role Property $propertyId",
            "P${propertyId.toString().take(8)}",
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenant_modules (tenant_id, module_id, is_enabled, is_configured)
            VALUES (?, 'booking_engine', true, true)
            """.trimIndent(),
            tenantFixture.tenantId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO property_modules (tenant_id, property_id, module_id, is_enabled, is_configured)
            VALUES (?, ?, 'booking_engine', true, true)
            """.trimIndent(),
            tenantFixture.tenantId,
            propertyId,
        )

        return PublicBookingFixture(
            tenantId = tenantFixture.tenantId,
            propertyId = propertyId,
        )
    }

    private fun insertTenantFixture(status: String): TenantFixture {
        val planId = UUID.randomUUID()
        val tenantId = UUID.randomUUID()

        jdbcTemplate.update(
            """
            INSERT INTO plans (id, name, code)
            VALUES (?, ?, ?)
            """.trimIndent(),
            planId,
            "Runtime Role Plan $planId",
            "runtime-plan-$planId",
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenants (id, name, slug, status, schema_name, plan_id)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            tenantId,
            "Runtime Role Tenant $tenantId",
            "runtime-${tenantId.toString().take(8)}",
            status,
            "tenant_${tenantId.toString().replace("-", "")}",
            planId,
        )

        return TenantFixture(tenantId)
    }

    private fun insertPlatformFixture(): PlatformFixture {
        val platformUserId = UUID.randomUUID()
        val platformRoleId = UUID.randomUUID()

        jdbcTemplate.update(
            """
            INSERT INTO platform_users (id, full_name, email, status)
            VALUES (?, ?, ?, 'active')
            """.trimIndent(),
            platformUserId,
            "Runtime Platform User $platformUserId",
            "runtime-platform-$platformUserId@example.com",
        )
        jdbcTemplate.update(
            """
            INSERT INTO platform_roles (id, name, code, is_system)
            VALUES (?, ?, ?, true)
            """.trimIndent(),
            platformRoleId,
            "Runtime Platform Role $platformRoleId",
            "runtime-platform-role-$platformRoleId",
        )
        jdbcTemplate.update(
            """
            INSERT INTO platform_role_permissions (platform_role_id, platform_permission_id)
            SELECT ?, id
            FROM platform_permissions
            WHERE code = 'platform.admin.all'
            """.trimIndent(),
            platformRoleId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO platform_user_roles (platform_user_id, platform_role_id)
            VALUES (?, ?)
            """.trimIndent(),
            platformUserId,
            platformRoleId,
        )

        return PlatformFixture(platformUserId)
    }

    private fun insertActiveSupportGrant(
        platformUserId: UUID,
        tenantId: UUID,
        actionCode: String,
    ): UUID {
        val supportSessionId = UUID.randomUUID()
        val approver = insertPlatformFixture()
        val ticketId = UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO support_tickets (id, tenant_id, ticket_number, subject) VALUES (?, ?, ?, ?)",
            ticketId, tenantId, "SUP-${ticketId.toString().take(8)}", "Runtime role access",
        )
        jdbcTemplate.update(
            """
            INSERT INTO platform_break_glass_access (
                id,
                platform_user_id,
                tenant_id,
                support_ticket_id,
                action_code,
                reason,
                status,
                approved_by,
                approved_at,
                activated_at,
                starts_at,
                expires_at
            ) VALUES (
                ?, ?, ?, ?, ?, 'Runtime role support grant', 'active', ?,
                now(), now(), now() - interval '1 minute',
                now() + interval '1 hour'
            )
            """.trimIndent(),
            supportSessionId,
            platformUserId,
            tenantId,
            ticketId,
            actionCode,
            approver.platformUserId,
        )
        return supportSessionId
    }

    private fun insertBookingSession(
        sessionId: UUID,
        fixture: PublicBookingFixture,
    ): Int {
        return jdbcTemplate.update(
            """
            INSERT INTO booking_sessions (
                id,
                tenant_id,
                property_id,
                check_in_date,
                check_out_date,
                guest_name,
                guest_email,
                status,
                expires_at
            )
            VALUES (?, ?, ?, current_date + 1, current_date + 2, ?, ?, 'payment_pending', now() + interval '15 minutes')
            """.trimIndent(),
            sessionId,
            fixture.tenantId,
            fixture.propertyId,
            "Runtime Guest",
            "runtime-guest@example.com",
        )
    }

    private fun insertOutboxEvent(destination: String): UUID {
        val eventId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO outbox_events (
                id,
                aggregate_type,
                aggregate_id,
                event_type,
                destination,
                payload,
                correlation_id
            )
            VALUES (?, 'runtime_role', ?, 'runtime.role.test', ?, '{}'::jsonb, ?)
            """.trimIndent(),
            eventId,
            eventId,
            destination,
            UUID.randomUUID(),
        )
        return eventId
    }

    private fun insertConsentedChannel(tenantId: UUID): UUID {
        val contactId = UUID.randomUUID()
        val channelId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO tenant_contacts (id, tenant_id, full_name, status)
            VALUES (?, ?, ?, 'active')
            """.trimIndent(),
            contactId,
            tenantId,
            "Runtime Delivery Contact $contactId",
        )
        jdbcTemplate.update(
            """
            INSERT INTO contact_channels (
                id,
                tenant_id,
                contact_id,
                channel_type,
                address,
                normalized_address,
                verification_status
            )
            VALUES (?, ?, ?, 'email', ?, ?, 'verified')
            """.trimIndent(),
            channelId,
            tenantId,
            contactId,
            "runtime-$channelId@example.com",
            "runtime-$channelId@example.com",
        )
        jdbcTemplate.update(
            """
            INSERT INTO communication_consents (
                tenant_id,
                contact_id,
                contact_channel_id,
                purpose,
                status,
                policy_version,
                capture_source
            )
            VALUES (?, ?, ?, 'operational_reports', 'active', 'runtime-v1', 'api')
            """.trimIndent(),
            tenantId,
            contactId,
            channelId,
        )
        return channelId
    }

    private data class PublicBookingFixture(
        val tenantId: UUID,
        val propertyId: UUID,
    )

    private data class TenantFixture(
        val tenantId: UUID,
    )

    private data class PlatformFixture(
        val platformUserId: UUID,
    )

    private companion object {
        const val API_ROLE = "pms_app"
        const val PLATFORM_ROLE = "pms_platform"
        const val WORKER_ROLE = "pms_worker"
        const val SUPPORT_ROLE = "pms_readonly_support"
        const val CONTINUITY_OWNER_ROLE = "pms_tenant_continuity_owner"
    }
}
