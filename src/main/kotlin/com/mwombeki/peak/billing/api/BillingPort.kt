package com.mwombeki.peak.billing.api

import java.util.UUID
import org.springframework.modulith.NamedInterface

@NamedInterface("api")
interface BillingPort {
    fun openReservationFolio(
        tenantId: UUID,
        propertyId: UUID,
        reservationId: UUID,
        idempotencyKeyId: UUID,
    ): UUID

    fun postRoomChargeForReservation(
        tenantId: UUID,
        propertyId: UUID,
        reservationId: UUID,
        idempotencyKeyId: UUID,
    ): UUID?

    fun postConfirmedPayment(
        tenantId: UUID,
        propertyId: UUID,
        request: ConfirmedPaymentRequest,
        idempotencyKeyId: UUID?,
    ): UUID

    fun reverseConfirmedPayment(
        tenantId: UUID,
        propertyId: UUID,
        request: ConfirmedPaymentReversalRequest,
        idempotencyKeyId: UUID,
    ): UUID

    fun checkoutFinancialState(
        tenantId: UUID,
        propertyId: UUID,
        reservationId: UUID,
    ): CheckoutFinancialState

    fun closeFolio(
        tenantId: UUID,
        propertyId: UUID,
        folioId: UUID,
    )

    fun listFolios(propertyId: UUID): List<FolioResponse>
    fun getFolio(propertyId: UUID, folioId: UUID): FolioResponse?
    fun postCharge(propertyId: UUID, folioId: UUID, request: PostChargeRequest): BillingMutationReceipt
    fun postPosCharge(
        tenantId: UUID,
        propertyId: UUID,
        folioId: UUID,
        request: PostChargeRequest,
        idempotencyKeyId: UUID,
    ): UUID
    fun reverseCharge(
        propertyId: UUID,
        folioId: UUID,
        chargeId: UUID,
        request: ReverseChargeRequest,
    ): BillingMutationReceipt
    fun issueInvoice(propertyId: UUID, folioId: UUID, request: IssueInvoiceRequest): InvoiceResponse
    fun listInvoices(propertyId: UUID): List<InvoiceResponse>
    fun getInvoice(propertyId: UUID, invoiceId: UUID): InvoiceResponse?
}
