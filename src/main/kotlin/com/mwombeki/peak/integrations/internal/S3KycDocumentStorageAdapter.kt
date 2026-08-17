package com.mwombeki.peak.integrations.internal

import com.mwombeki.peak.shared.outbound.KYC_DOCUMENT_OBJECT_STORAGE_QUALIFIER
import com.mwombeki.peak.shared.outbound.ObjectStoragePort
import com.mwombeki.peak.shared.outbound.StoreObject
import com.mwombeki.peak.shared.outbound.StoredObject
import io.minio.BucketExistsArgs
import io.minio.GetBucketEncryptionArgs
import io.minio.GetBucketPolicyArgs
import io.minio.GetPresignedObjectUrlArgs
import io.minio.Http
import io.minio.MakeBucketArgs
import io.minio.MinioClient
import io.minio.PutObjectArgs
import io.minio.RemoveObjectArgs
import io.minio.StatObjectArgs
import io.minio.ServerSideEncryption
import io.minio.errors.ErrorResponseException
import java.io.ByteArrayInputStream
import java.time.Duration
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.stereotype.Component

@ConfigurationProperties(prefix = "peak.verification.storage")
data class KycDocumentStorageProperties(
    val enabled: Boolean = false,
    val endpoint: String = "http://localhost:9000",
    val accessKey: String = "peak",
    val secretKey: String = "peak-development-secret",
    val bucket: String = "peak-private-kyc-documents",
    val region: String = "us-east-1",
)

/**
 * A second, distinct private bucket from the report one — KYC documents are regulated
 * government-ID / business-registration evidence, not internal export artifacts, and the
 * two must never share a lifecycle, retention posture, or bucket. Same MinIO/S3-compatible
 * wire logic as [S3ReportObjectStorageAdapter] because the mechanics genuinely are
 * identical; only the bucket, credentials, and the qualifier that keeps them from being
 * autowired into each other's callers differ.
 */
@Component
@Qualifier(KYC_DOCUMENT_OBJECT_STORAGE_QUALIFIER)
@ConditionalOnProperty(
    prefix = "peak.verification.storage",
    name = ["enabled"],
    havingValue = "true",
)
class S3KycDocumentStorageAdapter(
    private val properties: KycDocumentStorageProperties,
) : ObjectStoragePort, HealthIndicator {
    private val client = MinioClient.builder()
        .endpoint(properties.endpoint)
        .credentials(properties.accessKey, properties.secretKey)
        .region(properties.region)
        .build()

    override val bucketName: String = properties.bucket

    init {
        // Idempotent by construction (bucketExists guards it), so this is safe to run on
        // every startup rather than depending on an external bootstrap step having run first
        // — the same self-sufficiency Postgres migrations already give the schema.
        if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build())) {
            client.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build())
        }
    }

    override fun validatePrivateEncryptedBucket() {
        check(
            client.bucketExists(
                BucketExistsArgs.builder().bucket(bucketName).build(),
            ),
        ) {
            "Private KYC document bucket does not exist"
        }
        val policy = try {
            client.getBucketPolicy(
                GetBucketPolicyArgs.builder().bucket(bucketName).build(),
            )
        } catch (ex: ErrorResponseException) {
            if (ex.errorResponse().code() == "NoSuchBucketPolicy") {
                null
            } else {
                throw ex
            }
        }
        check(
            policy == null || !reportBucketPolicyGrantsPublicAccess(policy),
        ) {
            "KYC document bucket policy must not grant public access"
        }
        client.getBucketEncryption(
            GetBucketEncryptionArgs.builder().bucket(bucketName).build(),
        )
    }

    override fun isHealthy(): Boolean {
        return runCatching {
            client.bucketExists(
                BucketExistsArgs.builder().bucket(bucketName).build(),
            )
        }.getOrDefault(false)
    }

    override fun putIfAbsent(
        command: StoreObject,
    ): StoredObject {
        val existing = statOrNull(command.objectKey)
        if (existing != null) {
            val existingHash = existing.userMetadata().caseInsensitiveValue("sha256")
                ?: existing.headers().caseInsensitiveValue("x-amz-meta-sha256")
            check(existingHash == command.sha256) {
                "Object key already contains different document content"
            }
            return StoredObject(
                objectKey = command.objectKey,
                etag = existing.etag(),
                contentLength = existing.size(),
                sha256 = command.sha256,
            )
        }
        val response = client.putObject(
            PutObjectArgs.builder()
                .bucket(bucketName)
                .`object`(command.objectKey)
                .stream(
                    ByteArrayInputStream(command.bytes),
                    command.bytes.size.toLong(),
                    -1,
                )
                .contentType(command.contentType)
                .userMetadata(mapOf("sha256" to command.sha256))
                .headers(mapOf("If-None-Match" to "*"))
                .sse(ServerSideEncryption.S3())
                .build(),
        )
        return StoredObject(
            objectKey = command.objectKey,
            etag = response.etag(),
            contentLength = command.bytes.size.toLong(),
            sha256 = command.sha256,
        )
    }

    override fun presignedGet(
        objectKey: String,
        expiry: Duration,
    ): String {
        require(!expiry.isZero && !expiry.isNegative) {
            "Signed URL expiry must be positive"
        }
        return client.getPresignedObjectUrl(
            GetPresignedObjectUrlArgs.builder()
                .bucket(bucketName)
                .`object`(objectKey)
                .method(Http.Method.GET)
                .expiry(expiry.seconds.toInt())
                .build(),
        )
    }

    override fun presignedPut(
        objectKey: String,
        expiry: Duration,
    ): String {
        require(!expiry.isZero && !expiry.isNegative) {
            "Signed URL expiry must be positive"
        }
        return client.getPresignedObjectUrl(
            GetPresignedObjectUrlArgs.builder()
                .bucket(bucketName)
                .`object`(objectKey)
                .method(Http.Method.PUT)
                .expiry(expiry.seconds.toInt())
                .build(),
        )
    }

    override fun stat(objectKey: String): StoredObject? {
        val found = statOrNull(objectKey) ?: return null
        val hash = found.userMetadata().caseInsensitiveValue("sha256")
            ?: found.headers().caseInsensitiveValue("x-amz-meta-sha256")
            ?: ""
        return StoredObject(
            objectKey = objectKey,
            etag = found.etag(),
            contentLength = found.size(),
            sha256 = hash,
        )
    }

    override fun delete(objectKey: String) {
        client.removeObject(
            RemoveObjectArgs.builder()
                .bucket(bucketName)
                .`object`(objectKey)
                .build(),
        )
    }

    override fun health(): Health {
        return if (isHealthy()) {
            Health.up().withDetail("bucket", bucketName).build()
        } else {
            Health.down().withDetail("bucket", bucketName).build()
        }
    }

    private fun statOrNull(objectKey: String) = try {
        client.statObject(
            StatObjectArgs.builder()
                .bucket(bucketName)
                .`object`(objectKey)
                .build(),
        )
    } catch (ex: ErrorResponseException) {
        if (ex.errorResponse().code() in setOf("NoSuchKey", "NoSuchObject")) {
            null
        } else {
            throw ex
        }
    }

    private fun Http.Headers.caseInsensitiveValue(key: String): String? =
        entries().firstOrNull { it.key.equals(key, ignoreCase = true) }?.value

    private fun okhttp3.Headers.caseInsensitiveValue(key: String): String? =
        (0 until size).firstNotNullOfOrNull { index ->
            value(index).takeIf { name(index).equals(key, ignoreCase = true) }
        }
}

@Component
@Qualifier(KYC_DOCUMENT_OBJECT_STORAGE_QUALIFIER)
@ConditionalOnProperty(
    prefix = "peak.verification.storage",
    name = ["enabled"],
    havingValue = "false",
    matchIfMissing = true,
)
class DisabledKycDocumentStorageAdapter(
    private val properties: KycDocumentStorageProperties,
) : ObjectStoragePort {
    override val bucketName: String = properties.bucket
    override fun validatePrivateEncryptedBucket() =
        error("KYC document object storage is disabled")
    override fun isHealthy() = false
    override fun putIfAbsent(command: StoreObject): StoredObject =
        error("KYC document object storage is disabled")
    override fun presignedGet(objectKey: String, expiry: Duration): String =
        error("KYC document object storage is disabled")
    override fun presignedPut(objectKey: String, expiry: Duration): String =
        error("KYC document object storage is disabled")
    override fun stat(objectKey: String): StoredObject? =
        error("KYC document object storage is disabled")
    override fun delete(objectKey: String) =
        error("KYC document object storage is disabled")
}
