package com.mwombeki.peak.frontdesk.api

import java.util.UUID
import org.springframework.modulith.NamedInterface

@NamedInterface("api")
interface FrontDeskPort {
    fun checkIn(propertyId: UUID, request: CheckInRequest): FrontDeskMutationReceipt
    fun createWalkIn(propertyId: UUID, request: WalkInRequest): FrontDeskMutationReceipt
    fun checkOut(propertyId: UUID, stayId: UUID, request: CheckoutRequest): FrontDeskMutationReceipt
    fun checkOutWithFiscalOverride(propertyId: UUID, stayId: UUID, request: CheckoutRequest): FrontDeskMutationReceipt
    fun listStays(propertyId: UUID): List<StayResponse>
    fun getStay(propertyId: UUID, stayId: UUID): StayResponse?
}
