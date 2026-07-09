package com.mwombeki.peak.integrations.internal

import com.mwombeki.peak.reporting.api.ObjectStoragePort
import com.mwombeki.peak.reporting.api.StoreReportObject
import com.mwombeki.peak.reporting.api.StoredReportObject
import io.minio.BucketExistsArgs
import io.minio.GetBucketEncryptionArgs
import io.minio.GetBucketPolicyArgs
import io.minio.GetPresignedObjectUrlArgs
import io.minio.Http
import io.minio.MinioClient
import io.minio.PutObjectArgs
import io.minio.RemoveObjectArgs
import io.minio.StatObjectArgs
import io.minio.ServerSideEncryption
import io.minio.errors.ErrorResponseException
import java.io.ByteArrayInputStream
import java.time.Duration
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.stereotype.Component

@ConfigurationProperties(prefix = "peak.reporting.storage")
data class ReportObjectStorageProperties(
    val enabled: Boolean = false,
    val endpoint: String = "http://localhost:9000",
    val accessKey: String = "peak",
    val secretKey: String = "peak-development-secret",
    val bucket: String = "peak-private-reports",
    val region: String = "us-east-1",
)

@Component
@ConditionalOnProperty(
    prefix = "peak.reporting.storage",
    name = ["enabled"],
    havingValue = "true",
)
class S3ReportObjectStorageAdapter(
    private val properties: ReportObjectStorageProperties,
) : ObjectStoragePort, HealthIndicator {
    private val client = MinioClient.builder()
        .endpoint(properties.endpoint)
        .credentials(properties.accessKey, properties.secretKey)
        .region(properties.region)
        .build()

    override val bucketName: String = properties.bucket

    override fun validatePrivateEncryptedBucket() {
        check(
            client.bucketExists(
                BucketExistsArgs.builder().bucket(bucketName).build(),
            ),
        ) {
            "Private report bucket does not exist"
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
            policy == null ||
                !policy.contains("\"Principal\":\"*\"") &&
                !policy.contains("\"Principal\": \"*\""),
        ) {
            "Report bucket policy must not grant public access"
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
        command: StoreReportObject,
    ): StoredReportObject {
        val existing = statOrNull(command.objectKey)
        if (existing != null) {
            val existingHash = existing.userMetadata().caseInsensitiveValue(
                "sha256",
            ) ?: existing.headers().caseInsensitiveValue(
                "x-amz-meta-sha256",
            )
            check(existingHash == command.sha256) {
                "Object key already contains different report content"
            }
            return StoredReportObject(
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
        return StoredReportObject(
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
@ConditionalOnProperty(
    prefix = "peak.reporting.storage",
    name = ["enabled"],
    havingValue = "false",
    matchIfMissing = true,
)
class DisabledReportObjectStorageAdapter(
    private val properties: ReportObjectStorageProperties,
) : ObjectStoragePort {
    override val bucketName: String = properties.bucket
    override fun validatePrivateEncryptedBucket() =
        error("Report object storage is disabled")
    override fun isHealthy() = false
    override fun putIfAbsent(command: StoreReportObject): StoredReportObject =
        error("Report object storage is disabled")
    override fun presignedGet(objectKey: String, expiry: Duration): String =
        error("Report object storage is disabled")
    override fun delete(objectKey: String) =
        error("Report object storage is disabled")
}
