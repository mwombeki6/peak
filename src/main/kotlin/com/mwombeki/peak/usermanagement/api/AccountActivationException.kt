package com.mwombeki.peak.usermanagement.api

import org.springframework.http.HttpStatus
import org.springframework.modulith.NamedInterface

@NamedInterface("api")
class AccountActivationException(
    val code: String,
    val status: HttpStatus,
    message: String,
) : RuntimeException(message)
