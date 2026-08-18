package com.mwombeki.peak.pos.internal.web

import com.mwombeki.peak.pos.api.PosPrintJobFailureRequest
import com.mwombeki.peak.pos.api.PosPrintJobReclaimRequest
import com.mwombeki.peak.pos.api.PosPrintJobResponse
import com.mwombeki.peak.pos.internal.PosPrintJobService
import jakarta.validation.Valid
import java.util.UUID
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/properties/{propertyId}/pos-print-jobs")
class PosPrintJobController(
    private val printJobs: PosPrintJobService,
) {
    @GetMapping
    fun list(
        @PathVariable propertyId: UUID,
        @RequestParam(required = false) status: String?,
    ): List<PosPrintJobResponse> = printJobs.listJobs(propertyId, status)

    @PostMapping("/{jobId}/claim")
    fun claim(
        @PathVariable propertyId: UUID,
        @PathVariable jobId: UUID,
    ): PosPrintJobResponse = printJobs.claim(propertyId, jobId)

    @PostMapping("/{jobId}/printed")
    fun printed(
        @PathVariable propertyId: UUID,
        @PathVariable jobId: UUID,
    ): PosPrintJobResponse = printJobs.printed(propertyId, jobId)

    @PostMapping("/{jobId}/failed")
    fun failed(
        @PathVariable propertyId: UUID,
        @PathVariable jobId: UUID,
        @Valid @RequestBody request: PosPrintJobFailureRequest,
    ): PosPrintJobResponse = printJobs.failed(propertyId, jobId, request)

    @PostMapping("/{jobId}/reclaim")
    fun reclaim(
        @PathVariable propertyId: UUID,
        @PathVariable jobId: UUID,
        @Valid @RequestBody request: PosPrintJobReclaimRequest,
    ): PosPrintJobResponse = printJobs.reclaim(propertyId, jobId, request)

    @PostMapping("/{jobId}/reprint")
    fun reprint(
        @PathVariable propertyId: UUID,
        @PathVariable jobId: UUID,
    ): PosPrintJobResponse = printJobs.reprint(propertyId, jobId)
}
