package com.mwombeki.peak.reporting.api

import java.time.Duration

interface ObjectStoragePort {
    val bucketName: String

    fun validatePrivateEncryptedBucket()
    fun isHealthy(): Boolean
    fun putIfAbsent(command: StoreReportObject): StoredReportObject
    fun presignedGet(objectKey: String, expiry: Duration): String
    fun delete(objectKey: String)
}

data class StoreReportObject(
    val objectKey: String,
    val bytes: ByteArray,
    val contentType: String,
    val sha256: String,
)

data class StoredReportObject(
    val objectKey: String,
    val etag: String?,
    val contentLength: Long,
    val sha256: String,
)
