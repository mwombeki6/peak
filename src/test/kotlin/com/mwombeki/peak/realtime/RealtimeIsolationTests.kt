package com.mwombeki.peak.realtime

import com.mwombeki.peak.audit.api.AuditPort
import com.mwombeki.peak.realtime.internal.SseRegistry
import com.mwombeki.peak.shared.context.RequestContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RealtimeIsolationTests {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockBean
    private lateinit var requestContextHolder: RequestContextHolder

    @MockBean
    private lateinit var auditPort: AuditPort

    private val tenantA = UUID.randomUUID()
    private val tenantB = UUID.randomUUID()
    private val propertyA = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        // Default to tenant A
        val context = RequestContext(
            identity = RequestIdentity.Tenant(tenantA, "user-a", emptySet()),
            correlationId = "test-corr-id"
        )
        `when`(requestContextHolder.current()).thenReturn(context)
    }

    @Test
    fun `should allow access to own tenant stream`() {
        mockMvc.get("/api/v1/realtime/tenants/$tenantA/properties/$propertyA/stream")
            .andExpect {
                status { isOk() }
                content { contentType(MediaType.TEXT_EVENT_STREAM) }
            }
    }

    @Test
    fun `should deny access to another tenant stream`() {
        mockMvc.get("/api/v1/realtime/tenants/$tenantB/properties/$propertyA/stream")
            .andExpect {
                status { isInternalServerError() } // SecurityException usually maps to 500 in basic spring if not handled
            }
    }
}
