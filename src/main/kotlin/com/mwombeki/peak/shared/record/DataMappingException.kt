package com.mwombeki.peak.shared.record

import com.mwombeki.peak.shared.exception.BusinessException
import org.springframework.http.HttpStatus

/**
 * Data Layer Guard.
 * Thrown if database mapping utilities fail to parse relational rows into clean domain objects.
 */
class DataMappingException(message: String, cause: Throwable? = null) :
    BusinessException(
        message = message,
        status = HttpStatus.INTERNAL_SERVER_ERROR,
        errorCode = "DATABASE_MAPPING_FAILURE",
        cause = cause,
    )
