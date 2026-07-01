package com.mwombeki.peak.pos.internal.web

import com.mwombeki.peak.pos.api.ApproveVarianceRequest
import com.mwombeki.peak.pos.api.CloseSessionRequest
import com.mwombeki.peak.pos.api.FolioTransferRequest
import com.mwombeki.peak.pos.api.OpenSessionRequest
import com.mwombeki.peak.pos.internal.PosSessionService
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v1/properties/{propertyId}/pos-sessions")
class PosSessionController (
    private val posSessionService: PosSessionService,
){
    @PostMapping("/open")
    @PreAuthorize("hasAnyRole('ROLE_TENANT_ADMIN', 'ROLE_PROPERTY_MANAGER', 'ROLE_CASHIER')")
    fun openNewSession(
        @PathVariable propertyId: UUID,
        @RequestBody request: OpenSessionRequest
    ): ResponseEntity<Map<String, UUID>> {
        // Enforce that the propertyId in the URL path matches our request logic
        val openRequestWithProperty = request.copy(propertyId = propertyId)
        val sessionId = posSessionService.openSession(openRequestWithProperty)
        return ResponseEntity.ok(mapOf("sessionId" to sessionId))
    }

    @PostMapping("/{sessionId}/close")
    @PreAuthorize("hasAnyRole('ROLE_TENANT_ADMIN', 'ROLE_PROPERTY_MANAGER', 'ROLE_CASHIER')")
    fun closeActiveSession(
        @PathVariable propertyId: UUID,
        @PathVariable sessionId: UUID,
        @RequestBody request: CloseSessionRequest
    ): ResponseEntity<Map<String, String>> {
        posSessionService.closeSession(sessionId, request)
        return ResponseEntity.ok(mapOf("status" to "Session close instruction processed successfully."))
    }

    @PostMapping("/{sessionId}/variance-approve")
    @PreAuthorize("hasAnyRole('ROLE_TENANT_ADMIN', 'ROLE_PROPERTY_MANAGER')") //  Enforces independent approval!
    fun approveVariance(
        @PathVariable propertyId: UUID,
        @PathVariable sessionId: UUID,
        @RequestBody request: ApproveVarianceRequest
    ): ResponseEntity<Map<String, String>> {
        posSessionService.approveSessionVariance(sessionId, request)
        return ResponseEntity.ok(mapOf("status" to "Session variance approved and closed successfully."))
    }

    @PostMapping("/{sessionId}/folio-transfer")
    @PreAuthorize("hasAnyRole('ROLE_TENANT_ADMIN', 'ROLE_PROPERTY_MANAGER', 'ROLE_CASHIER')")
    fun transferChargeToRoomFolio(
        @PathVariable propertyId: UUID,
        @PathVariable sessionId: UUID,
        @RequestBody request: FolioTransferRequest
    ): ResponseEntity<Map<String, String>> {
        posSessionService.transferToFolio(sessionId, request)
        return ResponseEntity.ok(mapOf("status" to "Charge successfully transferred to guest folio ledger."))
    }
}