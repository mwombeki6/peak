package com.mwombeki.peak.nightaudit.internal.web

import com.mwombeki.peak.nightaudit.api.AssignFinancialControlCaseRequest
import com.mwombeki.peak.nightaudit.api.DailyControlBriefResponse
import com.mwombeki.peak.nightaudit.api.FinancialControlCaseResponse
import com.mwombeki.peak.nightaudit.api.FinancialControlNotFoundException
import com.mwombeki.peak.nightaudit.api.FinancialControlPort
import com.mwombeki.peak.nightaudit.api.ResolveFinancialControlCaseRequest
import java.time.LocalDate
import java.util.UUID
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/properties/{propertyId}/financial-control")
class FinancialControlController(
    private val financialControlPort: FinancialControlPort,
) {
    @GetMapping("/briefs/{businessDate}")
    fun dailyBrief(
        @PathVariable propertyId: UUID,
        @PathVariable
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        businessDate: LocalDate,
    ): DailyControlBriefResponse =
        financialControlPort.dailyBrief(propertyId, businessDate)

    @GetMapping("/cases")
    fun listCases(
        @PathVariable propertyId: UUID,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        businessDate: LocalDate?,
        @RequestParam(required = false) status: String?,
        @RequestParam(defaultValue = "100") limit: Int,
    ): List<FinancialControlCaseResponse> =
        financialControlPort.listCases(propertyId, businessDate, status, limit)

    @GetMapping("/cases/{caseId}")
    fun getCase(
        @PathVariable propertyId: UUID,
        @PathVariable caseId: UUID,
    ): FinancialControlCaseResponse =
        financialControlPort.getCase(propertyId, caseId)
            ?: throw FinancialControlNotFoundException(
                "Financial-control case was not found",
            )

    @PostMapping("/cases/{caseId}/assign")
    fun assignCase(
        @PathVariable propertyId: UUID,
        @PathVariable caseId: UUID,
        @RequestBody request: AssignFinancialControlCaseRequest,
    ): FinancialControlCaseResponse =
        financialControlPort.assignCase(propertyId, caseId, request)

    @PostMapping("/cases/{caseId}/resolve")
    fun resolveCase(
        @PathVariable propertyId: UUID,
        @PathVariable caseId: UUID,
        @RequestBody request: ResolveFinancialControlCaseRequest,
    ): FinancialControlCaseResponse =
        financialControlPort.resolveCase(propertyId, caseId, request)
}
