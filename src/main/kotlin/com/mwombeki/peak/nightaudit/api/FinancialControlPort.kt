package com.mwombeki.peak.nightaudit.api

import java.time.LocalDate
import java.util.UUID
import org.springframework.modulith.NamedInterface

@NamedInterface("api")
interface FinancialControlPort {
    fun dailyBrief(
        propertyId: UUID,
        businessDate: LocalDate,
    ): DailyControlBriefResponse

    fun listCases(
        propertyId: UUID,
        businessDate: LocalDate?,
        status: String?,
        limit: Int,
    ): List<FinancialControlCaseResponse>

    fun getCase(
        propertyId: UUID,
        caseId: UUID,
    ): FinancialControlCaseResponse?

    fun assignCase(
        propertyId: UUID,
        caseId: UUID,
        request: AssignFinancialControlCaseRequest,
    ): FinancialControlCaseResponse

    fun resolveCase(
        propertyId: UUID,
        caseId: UUID,
        request: ResolveFinancialControlCaseRequest,
    ): FinancialControlCaseResponse
}
