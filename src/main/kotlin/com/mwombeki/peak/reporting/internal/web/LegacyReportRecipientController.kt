package com.mwombeki.peak.reporting.internal.web

import com.mwombeki.peak.communication.api.ConfigureReportRecipientRequest
import com.mwombeki.peak.communication.api.ReportRecipientMutationReceipt
import com.mwombeki.peak.communication.api.ReportRecipientResponse
import com.mwombeki.peak.reliability.api.IdempotencyCommand
import com.mwombeki.peak.reliability.api.IdempotencyPort
import com.mwombeki.peak.reliability.api.IdempotencyReservation
import com.mwombeki.peak.reporting.api.AddReportRecipientRequest
import com.mwombeki.peak.reporting.api.CreateReportSubscriptionRequest
import com.mwombeki.peak.reporting.api.ReportingPort
import com.mwombeki.peak.shared.context.DatabaseSessionContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import java.util.UUID
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper

@Deprecated(
    message = "Use the reporting subscription APIs",
    replaceWith = ReplaceWith("/api/v1/*/report-subscriptions"),
)
@RestController
@RequestMapping("/api/v1/communication/report-recipients")
class LegacyReportRecipientController(
    private val reportingPort: ReportingPort,
    private val requestContextHolder: RequestContextHolder,
    private val databaseSessionContext: DatabaseSessionContext,
    private val idempotencyPort: IdempotencyPort,
    private val objectMapper: ObjectMapper,
) {
    @PostMapping
    @Transactional
    fun configure(
        @RequestBody request: ConfigureReportRecipientRequest,
    ): ReportRecipientMutationReceipt {
        val identity = tenantIdentity()
        databaseSessionContext.bind(identity)
        val reservation = idempotencyPort.reserve(
            IdempotencyCommand(
                operationType = "communication.report_recipient.configure",
                requestPayload = mapOf(
                    "contactId" to request.contactId,
                    "channelId" to request.channelId,
                    "reportCode" to request.reportCode,
                    "subscriptionName" to request.subscriptionName,
                    "propertyId" to request.propertyId,
                    "frequency" to request.frequency,
                    "scheduleTime" to request.scheduleTime,
                    "timezone" to request.timezone,
                    "deliveryFormat" to request.deliveryFormat,
                ),
                resourceType = "report_subscription_recipients",
            ),
        )
        return when (reservation) {
            is IdempotencyReservation.Started -> {
                val receipt = configureRecipient(identity.tenantId, request)
                idempotencyPort.markSucceeded(
                    recordId = reservation.recordId,
                    responseCode = 200,
                    responseBody = receipt,
                    resourceId = receipt.recipientId,
                )
                receipt
            }

            is IdempotencyReservation.Replay -> replay(reservation)
            is IdempotencyReservation.InProgress -> {
                throw IllegalStateException(
                    "Communication command is already being processed for this idempotency key",
                )
            }

            is IdempotencyReservation.Conflict -> {
                throw IllegalArgumentException(
                    "Idempotency key was already used for a different communication request",
                )
            }
        }
    }

    private fun configureRecipient(
        tenantId: UUID,
        request: ConfigureReportRecipientRequest,
    ): ReportRecipientMutationReceipt {
        val subscriptions = if (request.propertyId == null) {
            reportingPort.listSubscriptions(tenantId = tenantId)
        } else {
            reportingPort.listSubscriptions(propertyId = request.propertyId)
        }
        val subscription = subscriptions.firstOrNull {
            it.reportCode == request.reportCode.lowercase() &&
                it.subscriptionName.equals(
                    request.subscriptionName,
                    ignoreCase = true,
                )
        } ?: reportingPort.createSubscription(
            tenantId = tenantId.takeIf { request.propertyId == null },
            propertyId = request.propertyId,
            request = CreateReportSubscriptionRequest(
                reportCode = request.reportCode,
                subscriptionName = request.subscriptionName,
                propertyId = request.propertyId,
                frequency = request.frequency,
                scheduleTime = request.scheduleTime,
                timezone = request.timezone,
            ),
        )
        val updated = reportingPort.addRecipient(
            subscription.id,
            AddReportRecipientRequest(
                contactId = request.contactId,
                contactChannelId = request.channelId,
            ),
        )
        val recipient = updated.recipients.first {
            it.contactId == request.contactId &&
                it.contactChannelId == request.channelId
        }
        return ReportRecipientMutationReceipt(
            subscriptionId = updated.id,
            recipientId = recipient.id,
            contactId = recipient.contactId,
            channelId = recipient.contactChannelId,
            changed = true,
            replayed = false,
        )
    }

    private fun replay(
        reservation: IdempotencyReservation.Replay,
    ): ReportRecipientMutationReceipt {
        if (reservation.responseBody.isNullOrBlank()) {
            throw IllegalArgumentException(
                "Communication replay does not contain a stored response body",
            )
        }
        return objectMapper
            .readValue(
                reservation.responseBody,
                ReportRecipientMutationReceipt::class.java,
            )
            .copy(replayed = true)
    }

    @GetMapping
    fun list(): List<ReportRecipientResponse> {
        val subscriptions = reportingPort.listAllSubscriptionsForTenant(
            tenantIdentity().tenantId,
        )
        return subscriptions.flatMap { subscription ->
            subscription.recipients.map { recipient ->
                ReportRecipientResponse(
                    subscriptionId = subscription.id,
                    recipientId = recipient.id,
                    reportCode = subscription.reportCode,
                    subscriptionName = subscription.subscriptionName,
                    propertyId = subscription.propertyId,
                    frequency = subscription.frequency,
                    timezone = subscription.timezone,
                    contactId = recipient.contactId,
                    contactName = "",
                    channelId = recipient.contactChannelId,
                    channelType = recipient.channelType,
                    maskedAddress = recipient.destinationMasked,
                    deliveryFormat = "pdf",
                    enabled = recipient.enabled,
                    hasActiveConsent = recipient.enabled,
                )
            }
        }
    }

    private fun tenantIdentity(): RequestIdentity.Tenant {
        return requestContextHolder.current().identity as? RequestIdentity.Tenant
            ?: error("Tenant identity is required")
    }
}
