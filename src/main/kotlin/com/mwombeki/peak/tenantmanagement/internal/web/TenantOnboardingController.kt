package com.mwombeki.peak.tenantmanagement.internal.web

import com.mwombeki.peak.tenantmanagement.api.TenantOnboardingPort
import com.mwombeki.peak.tenantmanagement.api.TenantRegisterRequest
import com.mwombeki.peak.tenantmanagement.api.TenantResponse
import com.mwombeki.peak.tenantmanagement.api.TenantStatus
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("api/vi/tenants")
class TenantOnboardingController(
    private val tenantOnboardingPort: TenantOnboardingPort
){
    @PostMapping("/register")
    fun registerTenant(@RequestBody request: TenantRegisterRequest): ResponseEntity<TenantResponse> {
        val response = tenantOnboardingPort.registerNewTenant(request)
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    @GetMapping("/{id}")
    fun getTenant(@PathVariable id: UUID): ResponseEntity<TenantResponse> {
        val response = tenantOnboardingPort.getTenantById(id)
            ?:return ResponseEntity.notFound().build()
        return ResponseEntity.ok(response)
    }

    @PatchMapping("/{id}/status")
    fun updateStatus(
        @PathVariable id: UUID,
        @RequestParam status: TenantStatus
    ): ResponseEntity<TenantResponse> {
        val response = tenantOnboardingPort.updateTenantStatus(id, status)
        return ResponseEntity.ok(response)
    }
}