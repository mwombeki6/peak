package com.mwombeki.peak.integrations.internal

import com.mwombeki.peak.reporting.api.ObjectStoragePort
import jakarta.annotation.PostConstruct
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(
    prefix = "peak.reporting.storage",
    name = ["enabled"],
    havingValue = "true",
)
class ReportStorageStartupValidator(
    private val objectStoragePort: ObjectStoragePort,
) {
    @PostConstruct
    fun validate() {
        objectStoragePort.validatePrivateEncryptedBucket()
    }
}
