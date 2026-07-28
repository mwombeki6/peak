package com.mwombeki.peak.usermanagement.internal.web

import com.mwombeki.peak.usermanagement.api.HospitalitySessionResponse
import com.mwombeki.peak.usermanagement.api.PlatformSessionResponse
import com.mwombeki.peak.usermanagement.api.SessionContextPort
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
class SessionContextController(
    private val sessionContextPort: SessionContextPort,
) {

    @GetMapping("/session")
    fun hospitalitySession(): HospitalitySessionResponse =
        sessionContextPort.hospitalitySession()

    @GetMapping("/platform/session")
    fun platformSession(): PlatformSessionResponse =
        sessionContextPort.platformSession()
}
