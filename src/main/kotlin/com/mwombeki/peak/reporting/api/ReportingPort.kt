package com.mwombeki.peak.reporting.api

import java.util.UUID

interface ReportingPort {
    fun tenantSettings(tenantId: UUID): ReportingSettingsResponse
    fun updateTenantSettings(
        tenantId: UUID,
        request: UpdateReportingSettingsRequest,
    ): ReportingSettingsResponse
    fun propertySettings(propertyId: UUID): ReportingSettingsResponse
    fun updatePropertySettings(
        propertyId: UUID,
        request: UpdateReportingSettingsRequest,
    ): ReportingSettingsResponse
    fun catalog(tenantId: UUID): List<ReportCatalogResponse>
    fun listSubscriptions(
        tenantId: UUID? = null,
        propertyId: UUID? = null,
    ): List<ReportSubscriptionResponse>
    fun listAllSubscriptionsForTenant(
        tenantId: UUID,
    ): List<ReportSubscriptionResponse>
    fun createSubscription(
        tenantId: UUID? = null,
        propertyId: UUID? = null,
        request: CreateReportSubscriptionRequest,
    ): ReportSubscriptionResponse
    fun updateSubscription(
        subscriptionId: UUID,
        request: UpdateReportSubscriptionRequest,
        propertyId: UUID? = null,
    ): ReportSubscriptionResponse
    fun transitionSubscription(
        subscriptionId: UUID,
        action: String,
        propertyId: UUID? = null,
    ): ReportSubscriptionResponse
    fun addRecipient(
        subscriptionId: UUID,
        request: AddReportRecipientRequest,
        propertyId: UUID? = null,
    ): ReportSubscriptionResponse
    fun disableRecipient(
        subscriptionId: UUID,
        recipientId: UUID,
        propertyId: UUID? = null,
    ): ReportSubscriptionResponse
    fun createRun(
        propertyId: UUID,
        reportCode: String,
        request: CreateReportRunRequest,
    ): ReportRunResponse
    fun listRuns(tenantId: UUID): List<ReportRunResponse>
    fun getRun(tenantId: UUID, runId: UUID): ReportRunResponse
    fun downloadLink(
        tenantId: UUID,
        runId: UUID,
    ): ReportDownloadLinkResponse
    fun deliveries(
        tenantId: UUID,
        runId: UUID,
    ): List<ReportDeliveryResponse>
    fun retryDelivery(
        tenantId: UUID,
        deliveryId: UUID,
    ): ReportDeliveryResponse
}
