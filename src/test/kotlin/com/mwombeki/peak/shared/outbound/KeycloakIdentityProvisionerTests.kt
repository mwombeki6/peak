package com.mwombeki.peak.shared.outbound

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Duration
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import tools.jackson.databind.ObjectMapper

class KeycloakIdentityProvisionerTests {

    private var server: HttpServer? = null
    private val objectMapper = ObjectMapper()

    @AfterTest
    fun stopServer() {
        server?.stop(0)
        server = null
    }

    @Test
    fun provisionsAnIdentityWithoutAnEmailAddress() {
        var createdBody = ""
        val provisioner = provisionerFor(
            users = { exchange -> respond(exchange, 200, "[]") },
            createUser = { exchange ->
                createdBody = exchange.requestBody.bufferedReader().readText()
                exchange.responseHeaders.add("Location", "$BASE/admin/realms/$REALM/users/$SUBJECT")
                respond(exchange, 201, "")
            },
        )

        val provisioned = provisioner.provision(
            ProvisionIdentity(
                username = "+255754000001",
                phoneNumber = "+255754000001",
                firstName = "Asha",
                lastName = "Mwakalinga",
                tenantId = TENANT,
                peakUserId = PEAK_USER,
            ),
        )

        assertEquals(SUBJECT, provisioned.subjectId)
        assertFalse(provisioned.alreadyExisted)

        // The field must be absent, not empty. Keycloak treats "" as an address and every blank
        // one collides on the realm's uniqueness check, so the second hotelier without email
        // would fail to provision at all.
        val sent = objectMapper.readTree(createdBody)
        assertFalse(sent.has("email"), "an absent email must not be sent as a field")
        assertFalse(sent.has("emailVerified"))
        assertEquals("+255754000001", sent.path("username").asString())
    }

    @Test
    fun resolvesAnExistingIdentityThatCarriesOurOwnMark() {
        var createAttempted = false
        val provisioner = provisionerFor(
            users = { exchange ->
                respond(
                    exchange,
                    200,
                    """[{"id":"$SUBJECT","attributes":{"peakUserId":["$PEAK_USER"]}}]""",
                )
            },
            createUser = { exchange ->
                createAttempted = true
                respond(exchange, 201, "")
            },
        )

        val provisioned = provisioner.provision(command())

        assertEquals(SUBJECT, provisioned.subjectId)
        assertTrue(provisioned.alreadyExisted)
        // A retry after a timeout must resolve rather than duplicate; a second identity for one
        // person would silently shadow the first at login.
        assertFalse(createAttempted, "an already-linked identity must not be created again")
    }

    @Test
    fun refusesAnExistingIdentityThatIsNotLinkedToThisPeakUser() {
        val provisioner = provisionerFor(
            users = { exchange -> respond(exchange, 200, """[{"id":"$SUBJECT"}]""") },
            createUser = { exchange -> respond(exchange, 201, "") },
        )

        // Whoever claimed the username first would otherwise inherit the role Peak is about to
        // grant, which turns pre-registration into privilege.
        assertFailsWith<IdentityProvisioningException> { provisioner.provision(command()) }
    }

    @Test
    fun refusesAnExistingIdentityLinkedToADifferentPeakUser() {
        val provisioner = provisionerFor(
            users = { exchange ->
                respond(
                    exchange,
                    200,
                    """[{"id":"$SUBJECT","attributes":{"peakUserId":["${UUID.randomUUID()}"]}}]""",
                )
            },
            createUser = { exchange -> respond(exchange, 201, "") },
        )

        assertFailsWith<IdentityProvisioningException> { provisioner.provision(command()) }
    }

    @Test
    fun refusesWhenKeycloakCreatesAnIdentityButReportsNoLocation() {
        val provisioner = provisionerFor(
            users = { exchange -> respond(exchange, 200, "[]") },
            createUser = { exchange -> respond(exchange, 201, "") },
        )

        // Committing Peak-side state against a subject id we never received would strand the
        // identity: it exists, and nothing points at it.
        assertFailsWith<IdentityProvisioningException> { provisioner.provision(command()) }
    }

    @Test
    fun treatsAConcurrentCreateAsAConflictRatherThanSuccess() {
        val provisioner = provisionerFor(
            users = { exchange -> respond(exchange, 200, "[]") },
            createUser = { exchange -> respond(exchange, 409, """{"errorMessage":"exists"}""") },
        )

        assertFailsWith<IdentityProvisioningException> { provisioner.provision(command()) }
    }

    @Test
    fun refusesToSendAnActivationLinkToAnIdentityWithNoEmail() {
        var actionsEmailCalled = false
        val provisioner = provisionerFor(
            users = { exchange -> respond(exchange, 200, "[]") },
            createUser = { exchange -> respond(exchange, 201, "") },
            subject = { exchange -> respond(exchange, 200, """{"id":"$SUBJECT","username":"+255754000001"}""") },
            executeActions = { exchange ->
                actionsEmailCalled = true
                respond(exchange, 204, "")
            },
        )

        assertFailsWith<IdentityProvisioningException> {
            provisioner.sendActivationLink(
                SendActivationLink(SUBJECT, "https://app.example.com/activate", Duration.ofHours(24)),
            )
        }
        // Keycloak answers 204 whether or not it had an address to send to, so calling it would
        // report success for a link that was never sent.
        assertFalse(actionsEmailCalled, "must refuse before asking Keycloak to send")
    }

    @Test
    fun sendsAnActivationLinkWhenTheIdentityHasAnEmail() {
        var actionsEmailCalled = false
        val provisioner = provisionerFor(
            users = { exchange -> respond(exchange, 200, "[]") },
            createUser = { exchange -> respond(exchange, 201, "") },
            subject = { exchange ->
                respond(exchange, 200, """{"id":"$SUBJECT","email":"manager@hotel.co.tz"}""")
            },
            executeActions = { exchange ->
                actionsEmailCalled = true
                respond(exchange, 204, "")
            },
        )

        provisioner.sendActivationLink(
            SendActivationLink(SUBJECT, "https://app.example.com/activate", Duration.ofHours(24)),
        )

        assertTrue(actionsEmailCalled)
    }

    @Test
    fun marksTheEmailVerifiedAndClearsRequiredActionsOnTheExistingSubject() {
        var putBody = ""
        val provisioner = provisionerFor(
            users = { exchange -> respond(exchange, 200, "[]") },
            createUser = { exchange -> respond(exchange, 201, "") },
            subject = { exchange ->
                if (exchange.requestMethod == "PUT") {
                    putBody = exchange.requestBody.bufferedReader().readText()
                    respond(exchange, 204, "")
                } else {
                    respond(
                        exchange,
                        200,
                        """{"id":"$SUBJECT","email":"manager@hotel.co.tz","emailVerified":false,"requiredActions":["VERIFY_EMAIL","UPDATE_PASSWORD"]}""",
                    )
                }
            },
        )
        provisioner.markEmailVerified(MarkEmailVerified(SUBJECT))
        val verified = objectMapper.readTree(putBody)
        assertTrue(verified.path("emailVerified").asBoolean())
        putBody = ""
        provisioner.clearRequiredActions(SUBJECT)
        val cleared = objectMapper.readTree(putBody)
        assertEquals(0, cleared.path("requiredActions").size())
    }

    @Test
    fun storesAPermanentPasswordRatherThanARequiredAction() {
        var passwordBody = ""
        val provisioner = provisionerFor(
            users = { exchange -> respond(exchange, 200, "[]") },
            createUser = { exchange -> respond(exchange, 201, "") },
            resetPassword = { exchange ->
                passwordBody = exchange.requestBody.bufferedReader().readText()
                respond(exchange, 204, "")
            },
        )
        provisioner.establishPassword(EstablishPassword(SUBJECT, "a-long-enough-secret-1"))
        val sent = objectMapper.readTree(passwordBody)
        assertEquals("password", sent.path("type").asString())
        assertEquals("a-long-enough-secret-1", sent.path("value").asString())
        assertFalse(sent.path("temporary").asBoolean())
    }

    @Test
    fun provisionsAnEmailIdentityAlreadyMarkedVerifiedWhenPeakProvedTheAddress() {
        var createdBody = ""
        val provisioner = provisionerFor(
            users = { exchange -> respond(exchange, 200, "[]") },
            createUser = { exchange ->
                createdBody = exchange.requestBody.bufferedReader().readText()
                exchange.responseHeaders.add("Location", "$BASE/admin/realms/$REALM/users/$SUBJECT")
                respond(exchange, 201, "")
            },
        )
        provisioner.provision(command().copy(emailVerified = true))
        val sent = objectMapper.readTree(createdBody)
        assertTrue(sent.path("emailVerified").asBoolean())
    }

    @Test
    fun treatsAnAlreadyDeletedSubjectAsUnwound() {
        val provisioner = provisionerFor(
            users = { exchange -> respond(exchange, 200, "[]") },
            createUser = { exchange -> respond(exchange, 201, "") },
            subject = { exchange -> respond(exchange, 404, "") },
        )

        // Unwinding is usually a retry of an unwind, so an identity that is already gone is the
        // outcome the caller wanted rather than a failure to report.
        provisioner.delete(SUBJECT, null)
    }

    private fun command() = ProvisionIdentity(
        username = "manager@hotel.co.tz",
        email = "manager@hotel.co.tz",
        firstName = "Neema",
        lastName = "Kimaro",
        tenantId = TENANT,
        peakUserId = PEAK_USER,
    )

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1L else bytes.size.toLong())
        if (bytes.isNotEmpty()) {
            exchange.responseBody.use { it.write(bytes) }
        } else {
            exchange.responseBody.close()
        }
    }

    private fun provisionerFor(
        users: (HttpExchange) -> Unit,
        createUser: (HttpExchange) -> Unit,
        subject: ((HttpExchange) -> Unit)? = null,
        executeActions: ((HttpExchange) -> Unit)? = null,
        resetPassword: ((HttpExchange) -> Unit)? = null,
    ): KeycloakIdentityProvisioner {
        val running = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        running.createContext("/realms/$REALM/protocol/openid-connect/token") { exchange ->
            respond(exchange, 200, """{"access_token":"test-admin-token","expires_in":300}""")
        }
        running.createContext("/admin/realms/$REALM/users") { exchange ->
            val path = exchange.requestURI.path
            val isCollection = path.trimEnd('/').endsWith("/users")
            when {
                isCollection && exchange.requestMethod == "POST" -> createUser(exchange)
                isCollection -> users(exchange)
                path.endsWith("/execute-actions-email") ->
                    executeActions?.invoke(exchange) ?: respond(exchange, 204, "")
                path.endsWith("/reset-password") ->
                    resetPassword?.invoke(exchange) ?: respond(exchange, 204, "")
                else -> subject?.invoke(exchange) ?: respond(exchange, 200, "{}")
            }
        }
        running.start()
        server = running

        return KeycloakIdentityProvisioner(
            KeycloakAdminProperties(
                enabled = true,
                baseUrl = "http://127.0.0.1:${running.address.port}",
                realm = REALM,
                clientId = "peak-provisioner",
                clientSecret = "test-secret",
                connectTimeout = Duration.ofSeconds(2),
                requestTimeout = Duration.ofSeconds(5),
            ),
            objectMapper,
        )
    }

    private companion object {
        const val REALM = "peak-hospitality"
        const val BASE = "http://127.0.0.1"
        const val SUBJECT = "0b6f1c9e-5f2a-4d3b-9a71-2c8e4f6d1a55"
        val TENANT: String = UUID.randomUUID().toString()
        val PEAK_USER: String = UUID.randomUUID().toString()
    }
}
