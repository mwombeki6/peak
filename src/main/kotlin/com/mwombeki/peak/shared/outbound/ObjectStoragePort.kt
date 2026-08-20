package com.mwombeki.peak.shared.outbound

import java.io.InputStream
import java.time.Duration
import org.springframework.modulith.NamedInterface

/**
 * Distinguishes the KYC document bucket from the [ObjectStoragePort] bean every other caller
 * gets by default (the report bucket, `@Primary`) — a regulated-document upload must never be
 * silently wired to the wrong bucket because a constructor parameter forgot a qualifier.
 */
const val KYC_DOCUMENT_OBJECT_STORAGE_QUALIFIER = "kycDocumentObjectStorage"

/** Private, integrity-checked object storage at the shared outbound boundary. */
@NamedInterface("outbound")
interface ObjectStoragePort {
    val bucketName: String

    fun validatePrivateEncryptedBucket()
    fun isHealthy(): Boolean
    fun putIfAbsent(command: StoreObject): StoredObject
    fun presignedGet(objectKey: String, expiry: Duration): String

    /**
     * A short-lived, single-object write authorization. The caller never sees a bucket
     * credential. This binds only the object key and the expiry, not content-type or size —
     * MinIO/S3 presigned PUT URLs don't carry those constraints the way a presigned POST
     * policy would. [stat] after upload is what actually enforces size; content is verified
     * by the malware-scan step reading the real bytes, not by the URL's signature.
     */
    fun presignedPut(objectKey: String, expiry: Duration): String

    /** `null` if the object does not exist — the caller must not assume a claimed key was ever written. */
    fun stat(objectKey: String): StoredObject?
    fun delete(objectKey: String)

    /** The caller must close the stream. Used only by the malware-scan step to read real bytes. */
    fun getObject(objectKey: String): InputStream
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
