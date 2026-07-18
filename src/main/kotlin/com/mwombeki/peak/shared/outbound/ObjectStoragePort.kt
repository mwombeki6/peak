package com.mwombeki.peak.shared.outbound

import java.time.Duration
import org.springframework.modulith.NamedInterface

/** Private, integrity-checked object storage at the shared outbound boundary. */
@NamedInterface("outbound")
interface ObjectStoragePort {
    val bucketName: String

    fun validatePrivateEncryptedBucket()
    fun isHealthy(): Boolean
    fun putIfAbsent(command: StoreObject): StoredObject
    fun presignedGet(objectKey: String, expiry: Duration): String
    fun delete(objectKey: String)
}

data class StoreObject(
    val objectKey: String,
    val bytes: ByteArray,
    val contentType: String,
    val sha256: String,
)

data class StoredObject(
    val objectKey: String,
    val etag: String?,
    val contentLength: Long,
    val sha256: String,
)
