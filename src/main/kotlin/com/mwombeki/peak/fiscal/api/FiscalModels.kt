package com.mwombeki.peak.fiscal.api

import com.mwombeki.peak.shared.exception.BusinessException
import java.time.Instant
import java.util.UUID
import org.springframework.http.HttpStatus

data class ConfigureFiscalProviderRequest(
    val providerCode: String,
    val providerName: String,
    val authorityName: String = "Tanzania Revenue Authority",
    val environment: String = "sandbox",
    val endpointUrl: String,
    val secretRef: String,
    val deviceSerial: String? = null,
    val branchCode: String? = null,
    val taxpayerIdentifier: String,
    val isDefault: Boolean = true,
)

data class FiscalProviderConfigResponse(
    val id: UUID,
    val propertyId: UUID,
    val providerCode: String,
    val providerName: String,
    val environment: String,
    val endpointUrl: String,
    val deviceSerial: String?,
    val branchCode: String?,
    val taxpayerIdentifier: String,
    val isDefault: Boolean,
    val isActive: Boolean,
    val replayed: Boolean = false,
)

data class FiscalReceiptResponse(
    val id: UUID,
    val propertyId: UUID,
    val invoiceId: UUID,
    val fiscalMode: String,
    val receiptNumber: String,
    val fiscalCode: String?,
    val verificationCode: String?,
    val qrCodeUrl: String?,
    val status: String,
    val submittedAt: Instant,
    val replayed: Boolean = false,
)

open class FiscalException(
    message: String,
    status: HttpStatus,
    code: String,
) : BusinessException(message, status, code)

class FiscalNotFoundException(message: String) :
    FiscalException(message, HttpStatus.NOT_FOUND, "FISCAL_NOT_FOUND")

class FiscalConflictException(message: String) :
    FiscalException(message, HttpStatus.CONFLICT, "FISCAL_CONFLICT")

class FiscalRejectedException(message: String) :
    FiscalException(message, HttpStatus.BAD_REQUEST, "FISCAL_REJECTED")
