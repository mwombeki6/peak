package com.mwombeki.peak.property.api

import java.util.UUID

interface PropertyPort {
    fun createProperty(request: CreatePropertyRequest): UUID
    fun updateProperty(propertyId: UUID, request: UpdatePropertyRequest)
    fun getProperty(propertyId: UUID): PropertyResponse?
    fun listProperties(): List<PropertyResponse>
    fun deleteProperty(propertyId: UUID)
    fun suspendProperty(propertyId: UUID)
    fun archiveProperty(propertyId: UUID)
    fun checkReadiness(propertyId: UUID): PropertyReadinessResponse
    fun activateProperty(propertyId: UUID): PropertyReadinessResponse
    
    // Tax Configuration
    fun createTaxRate(request: CreateTaxRateRequest): UUID
    fun listTaxRates(): List<TaxRateResponse>
    
    // Module Management
    fun enableModule(propertyId: UUID, moduleId: String)
    fun disableModule(propertyId: UUID, moduleId: String)
    fun listEnabledModules(propertyId: UUID): List<String>
}