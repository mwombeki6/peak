package com.mwombeki.peak.reporting.internal.web

import com.mwombeki.peak.reporting.api.AddReportRecipientRequest
import com.mwombeki.peak.reporting.api.CreateReportRunRequest
import com.mwombeki.peak.reporting.api.CreateReportSubscriptionRequest
import com.mwombeki.peak.reporting.api.ReportCatalogResponse
import com.mwombeki.peak.reporting.api.ReportDeliveryResponse
import com.mwombeki.peak.reporting.api.ReportDownloadLinkResponse
import com.mwombeki.peak.reporting.api.ReportingPort
import com.mwombeki.peak.reporting.api.ReportingSettingsResponse
import com.mwombeki.peak.reporting.api.ReportRunResponse
import com.mwombeki.peak.reporting.api.ReportSubscriptionResponse
import com.mwombeki.peak.reporting.api.UpdateReportingSettingsRequest
import com.mwombeki.peak.reporting.api.UpdateReportSubscriptionRequest
import java.util.UUID
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}")
class TenantReportingController(
    private val reportingPort: ReportingPort,
) {
    @GetMapping("/reporting/settings")
    fun settings(
        @PathVariable tenantId: UUID,
    ): ReportingSettingsResponse = reportingPort.tenantSettings(tenantId)

    @PutMapping("/reporting/settings")
    fun updateSettings(
        @PathVariable tenantId: UUID,
        @RequestBody request: UpdateReportingSettingsRequest,
    ): ReportingSettingsResponse =
        reportingPort.updateTenantSettings(tenantId, request)

    @GetMapping("/reports/catalog")
    fun catalog(
        @PathVariable tenantId: UUID,
    ): List<ReportCatalogResponse> = reportingPort.catalog(tenantId)

    @GetMapping("/report-subscriptions")
    fun subscriptions(
        @PathVariable tenantId: UUID,
    ): List<ReportSubscriptionResponse> =
        reportingPort.listSubscriptions(tenantId = tenantId)

    @PostMapping("/report-subscriptions")
    fun createSubscription(
        @PathVariable tenantId: UUID,
        @RequestBody request: CreateReportSubscriptionRequest,
    ): ReportSubscriptionResponse =
        reportingPort.createSubscription(
            tenantId = tenantId,
            request = request,
        )

    @GetMapping("/report-runs")
    fun runs(
        @PathVariable tenantId: UUID,
    ): List<ReportRunResponse> = reportingPort.listRuns(tenantId)

    @GetMapping("/report-runs/{runId}")
    fun run(
        @PathVariable tenantId: UUID,
        @PathVariable runId: UUID,
    ): ReportRunResponse = reportingPort.getRun(tenantId, runId)

    @PostMapping("/report-runs/{runId}/download-link")
    fun downloadLink(
        @PathVariable tenantId: UUID,
        @PathVariable runId: UUID,
    ): ReportDownloadLinkResponse =
        reportingPort.downloadLink(tenantId, runId)

    @GetMapping("/report-runs/{runId}/deliveries")
    fun deliveries(
        @PathVariable tenantId: UUID,
        @PathVariable runId: UUID,
    ): List<ReportDeliveryResponse> =
        reportingPort.deliveries(tenantId, runId)

    @PostMapping("/report-deliveries/{deliveryId}/retry")
    fun retryDelivery(
        @PathVariable tenantId: UUID,
        @PathVariable deliveryId: UUID,
    ): ReportDeliveryResponse =
        reportingPort.retryDelivery(tenantId, deliveryId)
}

@RestController
@RequestMapping("/api/v1/properties/{propertyId}")
class PropertyReportingController(
    private val reportingPort: ReportingPort,
) {
    @GetMapping("/reporting/settings")
    fun settings(
        @PathVariable propertyId: UUID,
    ): ReportingSettingsResponse = reportingPort.propertySettings(propertyId)

    @PutMapping("/reporting/settings")
    fun updateSettings(
        @PathVariable propertyId: UUID,
        @RequestBody request: UpdateReportingSettingsRequest,
    ): ReportingSettingsResponse =
        reportingPort.updatePropertySettings(propertyId, request)

    @GetMapping("/report-subscriptions")
    fun subscriptions(
        @PathVariable propertyId: UUID,
    ): List<ReportSubscriptionResponse> =
        reportingPort.listSubscriptions(propertyId = propertyId)

    @PostMapping("/report-subscriptions")
    fun createSubscription(
        @PathVariable propertyId: UUID,
        @RequestBody request: CreateReportSubscriptionRequest,
    ): ReportSubscriptionResponse =
        reportingPort.createSubscription(
            propertyId = propertyId,
            request = request,
        )

    @PostMapping("/reports/{reportCode}/runs")
    fun createRun(
        @PathVariable propertyId: UUID,
        @PathVariable reportCode: String,
        @RequestBody request: CreateReportRunRequest,
    ): ReportRunResponse =
        reportingPort.createRun(propertyId, reportCode, request)
}

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/report-subscriptions")
class TenantReportSubscriptionMutationController(
    private val reportingPort: ReportingPort,
) {
    @PutMapping("/{subscriptionId}")
    fun update(
        @PathVariable tenantId: UUID,
        @PathVariable subscriptionId: UUID,
        @RequestBody request: UpdateReportSubscriptionRequest,
    ): ReportSubscriptionResponse {
        reportingPort.tenantSettings(tenantId)
        return reportingPort.updateSubscription(subscriptionId, request)
    }

    @PostMapping("/{subscriptionId}/pause")
    fun pause(
        @PathVariable tenantId: UUID,
        @PathVariable subscriptionId: UUID,
    ): ReportSubscriptionResponse {
        reportingPort.tenantSettings(tenantId)
        return reportingPort.transitionSubscription(subscriptionId, "pause")
    }

    @PostMapping("/{subscriptionId}/resume")
    fun resume(
        @PathVariable tenantId: UUID,
        @PathVariable subscriptionId: UUID,
    ): ReportSubscriptionResponse {
        reportingPort.tenantSettings(tenantId)
        return reportingPort.transitionSubscription(subscriptionId, "resume")
    }

    @PostMapping("/{subscriptionId}/archive")
    fun archive(
        @PathVariable tenantId: UUID,
        @PathVariable subscriptionId: UUID,
    ): ReportSubscriptionResponse {
        reportingPort.tenantSettings(tenantId)
        return reportingPort.transitionSubscription(subscriptionId, "archive")
    }

    @PostMapping("/{subscriptionId}/recipients")
    fun addRecipient(
        @PathVariable tenantId: UUID,
        @PathVariable subscriptionId: UUID,
        @RequestBody request: AddReportRecipientRequest,
    ): ReportSubscriptionResponse {
        reportingPort.tenantSettings(tenantId)
        return reportingPort.addRecipient(subscriptionId, request)
    }

    @PostMapping("/{subscriptionId}/recipients/{recipientId}/disable")
    fun disableRecipient(
        @PathVariable tenantId: UUID,
        @PathVariable subscriptionId: UUID,
        @PathVariable recipientId: UUID,
    ): ReportSubscriptionResponse {
        reportingPort.tenantSettings(tenantId)
        return reportingPort.disableRecipient(subscriptionId, recipientId)
    }
}

@RestController
@RequestMapping("/api/v1/properties/{propertyId}/report-subscriptions")
class PropertyReportSubscriptionMutationController(
    private val reportingPort: ReportingPort,
) {
    @PutMapping("/{subscriptionId}")
    fun update(
        @PathVariable propertyId: UUID,
        @PathVariable subscriptionId: UUID,
        @RequestBody request: UpdateReportSubscriptionRequest,
    ): ReportSubscriptionResponse =
        reportingPort.updateSubscription(
            subscriptionId = subscriptionId,
            request = request,
            propertyId = propertyId,
        )

    @PostMapping("/{subscriptionId}/pause")
    fun pause(
        @PathVariable propertyId: UUID,
        @PathVariable subscriptionId: UUID,
    ): ReportSubscriptionResponse =
        reportingPort.transitionSubscription(
            subscriptionId = subscriptionId,
            action = "pause",
            propertyId = propertyId,
        )

    @PostMapping("/{subscriptionId}/resume")
    fun resume(
        @PathVariable propertyId: UUID,
        @PathVariable subscriptionId: UUID,
    ): ReportSubscriptionResponse =
        reportingPort.transitionSubscription(
            subscriptionId = subscriptionId,
            action = "resume",
            propertyId = propertyId,
        )

    @PostMapping("/{subscriptionId}/archive")
    fun archive(
        @PathVariable propertyId: UUID,
        @PathVariable subscriptionId: UUID,
    ): ReportSubscriptionResponse =
        reportingPort.transitionSubscription(
            subscriptionId = subscriptionId,
            action = "archive",
            propertyId = propertyId,
        )

    @PostMapping("/{subscriptionId}/recipients")
    fun addRecipient(
        @PathVariable propertyId: UUID,
        @PathVariable subscriptionId: UUID,
        @RequestBody request: AddReportRecipientRequest,
    ): ReportSubscriptionResponse =
        reportingPort.addRecipient(
            subscriptionId = subscriptionId,
            request = request,
            propertyId = propertyId,
        )

    @PostMapping("/{subscriptionId}/recipients/{recipientId}/disable")
    fun disableRecipient(
        @PathVariable propertyId: UUID,
        @PathVariable subscriptionId: UUID,
        @PathVariable recipientId: UUID,
    ): ReportSubscriptionResponse =
        reportingPort.disableRecipient(
            subscriptionId = subscriptionId,
            recipientId = recipientId,
            propertyId = propertyId,
        )
}
