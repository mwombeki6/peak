package com.mwombeki.peak.usermanagement.internal.web

import com.fasterxml.jackson.annotation.JsonInclude
import com.mwombeki.peak.shared.exception.ApiProblemFactory
import com.mwombeki.peak.usermanagement.internal.application.StaffProvisionConflictException
import com.mwombeki.peak.usermanagement.internal.application.StaffProvisionInProgressException
import com.mwombeki.peak.usermanagement.internal.application.StaffProvisionService
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.net.URI
import java.time.Instant
import java.util.UUID
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestController
@RequestMapping("/api/v1")
class StaffProvisionController(
    private val staff: StaffProvisionService,
    private val apiProblemFactory: ApiProblemFactory,
) {
    @PostMapping("/tenants/{tenantId}/staff")
    fun provision(
        @PathVariable tenantId: UUID,
        @Valid @RequestBody request: ProvisionStaffHttpRequest,
    ): ResponseEntity<ProvisionStaffHttpResponse> {
        val receipt = staff.provision(
            StaffProvisionService.ProvisionCommand(
                tenantId = tenantId,
                fullName = request.fullName,
                phoneNumber = request.phoneNumber,
                propertyId = request.propertyId,
                propertyRoleId = request.propertyRoleId,
            ),
        )
        return ResponseEntity
            .created(URI.create("/api/v1/tenants/$tenantId/staff/${receipt.userId}"))
            .body(
                ProvisionStaffHttpResponse(
                    userId = receipt.userId,
                    tenantId = receipt.tenantId,
                    staffNumber = receipt.staffNumber,
                    phoneNumber = receipt.phoneNumber,
                    propertyId = receipt.propertyId,
                    propertyRoleId = receipt.propertyRoleId,
                    activationSecret = receipt.activationSecret,
                    activationExpiresAt = receipt.activationExpiresAt,
                    replayed = receipt.replayed,
                ),
            )
    }

    @GetMapping("/tenants/{tenantId}/staff")
    fun listStaff(
        @PathVariable tenantId: UUID,
        @RequestParam(required = false) propertyId: UUID?,
    ): List<StaffDirectoryHttpEntry> =
        staff.listStaff(tenantId, propertyId).map {
            StaffDirectoryHttpEntry(
                userId = it.userId,
                fullName = it.fullName,
                staffNumber = it.staffNumber,
                phoneNumber = it.phoneNumber,
                status = it.status,
                isActive = it.isActive,
                propertyId = it.propertyId,
                propertyRoleId = it.propertyRoleId,
            )
        }

    @PostMapping("/staff/credentials/activate")
    fun activate(
        @Valid @RequestBody request: ActivateStaffCredentialHttpRequest,
    ): ResponseEntity<Void> {
        try {
            staff.activate(
                StaffProvisionService.ActivateCommand(
                    tenantId = request.tenantId,
                    staffNumber = request.staffNumber,
                    secret = request.secret,
                    pin = request.pin,
                ),
            )
        } catch (ex: IllegalArgumentException) {
            throw StaffActivationRejectedException()
        }
        return ResponseEntity.ok().build()
    }

}

/**
 * Held outside the controller.
 *
 * Controller-local `@ExceptionHandler` methods on a module bean are never reached: the
 * Spring Modulith observability interceptor renders the invoked method for its trace
 * span and throws NullPointerException first. Spring logs the handler failure and
 * rethrows the original exception, so every designed 4xx left the container as a 500
 * carrying the raw message.
 */
// A controller-specific advice must outrank GlobalExceptionHandler, whose
// @ExceptionHandler(Exception) catch-all otherwise turns every domain exception
// it does not name explicitly into a 500. Both default to LOWEST_PRECEDENCE, and
// the tie is broken arbitrarily.
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = [StaffProvisionController::class])
class StaffProvisionExceptionAdvice(
    private val apiProblemFactory: ApiProblemFactory,
) {
    @ExceptionHandler(StaffProvisionConflictException::class)
    fun handleConflict(ex: StaffProvisionConflictException): ResponseEntity<ProblemDetail> {
        return apiProblemFactory.response(HttpStatus.CONFLICT, "Staff provision conflict", ex.safeDetail())
    }

    @ExceptionHandler(StaffProvisionInProgressException::class)
    fun handleInProgress(ex: StaffProvisionInProgressException): ResponseEntity<ProblemDetail> {
        return apiProblemFactory.response(HttpStatus.CONFLICT, "Staff provision in progress", ex.safeDetail())
    }

    @ExceptionHandler(StaffActivationRejectedException::class)
    fun handleActivationRejected(
        @Suppress("UNUSED_PARAMETER") ex: StaffActivationRejectedException,
    ): ResponseEntity<ProblemDetail> {
        return apiProblemFactory.response(
            HttpStatus.BAD_REQUEST,
            "Staff activation rejected",
            "Activation was not accepted",
        )
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleInvalid(ex: IllegalArgumentException): ResponseEntity<ProblemDetail> {
        return apiProblemFactory.response(
            HttpStatus.BAD_REQUEST,
            "Invalid staff request",
            ex.message ?: "Staff request is invalid",
        )
    }

    private fun RuntimeException.safeDetail(): String = message ?: "Staff request was not accepted"
}

data class ProvisionStaffHttpRequest(
    @field:NotBlank val fullName: String,
    val phoneNumber: String? = null,
    @field:NotNull val propertyId: UUID,
    @field:NotNull val propertyRoleId: UUID,
)

data class ProvisionStaffHttpResponse(
    val userId: UUID,
    val tenantId: UUID,
    val staffNumber: String,
    val phoneNumber: String?,
    val propertyId: UUID,
    val propertyRoleId: UUID,
    val activationSecret: String?,
    val activationExpiresAt: Instant,
    val replayed: Boolean,
)

data class ActivateStaffCredentialHttpRequest(
    @field:NotNull val tenantId: UUID,
    @field:NotBlank val staffNumber: String,
    @field:NotBlank val secret: String,
    @field:NotBlank val pin: String,
)

class StaffActivationRejectedException : RuntimeException()

@JsonInclude(JsonInclude.Include.ALWAYS)
data class StaffDirectoryHttpEntry(
    val userId: UUID,
    val fullName: String,
    /** Null until a staff number has been allocated, which is a real state during onboarding. */
    val staffNumber: String?,
    val phoneNumber: String?,
    val status: String,
    val isActive: Boolean,
    /** Null when the user exists but holds no property role yet. */
    val propertyId: UUID?,
    val propertyRoleId: UUID?,
)
