package com.mwombeki.peak.frontdesk.api

import java.util.UUID
import java.time.LocalDate
import org.springframework.modulith.NamedInterface

@NamedInterface("api")
interface FrontDeskPort {
    fun checkIn(propertyId: UUID, request: CheckInRequest): FrontDeskMutationReceipt
    fun createWalkIn(propertyId: UUID, request: WalkInRequest): FrontDeskMutationReceipt
    fun checkOut(propertyId: UUID, stayId: UUID, request: CheckoutRequest): FrontDeskMutationReceipt
    fun checkOutWithFiscalOverride(propertyId: UUID, stayId: UUID, request: CheckoutRequest): FrontDeskMutationReceipt
    fun checkOutWithUnpaidOverride(
        propertyId: UUID,
        stayId: UUID,
        request: UnpaidCheckoutOverrideRequest,
    ): FrontDeskMutationReceipt
    fun listStays(propertyId: UUID): List<StayResponse>
    fun getStay(propertyId: UUID, stayId: UUID): StayResponse?
}

@NamedInterface("api")
interface HousekeepingStaySummaryPort {
    fun inHouseStaySummaries(
        tenantId: UUID,
        propertyId: UUID,
        businessDate: LocalDate,
    ): List<HousekeepingStaySummary>
}

data class HousekeepingStaySummary(
    val stayId: UUID,
    val roomId: UUID,
    val checkInDate: LocalDate,
    val checkOutDate: LocalDate,
)
