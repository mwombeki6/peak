package com.mwombeki.peak.integrations.internal

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class S3ReportObjectStorageAdapterTests {
    @Test
    fun `detects wildcard principal bucket policy`() {
        assertTrue(
            reportBucketPolicyGrantsPublicAccess(
                """
                {
                  "Version": "2012-10-17",
                  "Statement": [{
                    "Effect": "Allow",
                    "Principal": "*",
                    "Action": "s3:GetObject",
                    "Resource": "arn:aws:s3:::peak-private-reports/*"
                  }]
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `detects wildcard aws principal bucket policy`() {
        assertTrue(
            reportBucketPolicyGrantsPublicAccess(
                """
                {
                  "Statement": {
                    "Effect": "Allow",
                    "Principal": { "AWS": "*" },
                    "Action": "s3:GetObject",
                    "Resource": "arn:aws:s3:::peak-private-reports/*"
                  }
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `detects wildcard principal array bucket policy`() {
        assertTrue(
            reportBucketPolicyGrantsPublicAccess(
                """
                {
                  "Statement": [{
                    "Effect": "Allow",
                    "Principal": { "AWS": [
                      "arn:aws:iam::123456789012:role/report-reader",
                      "*"
                    ] },
                    "Action": "s3:GetObject",
                    "Resource": "arn:aws:s3:::peak-private-reports/*"
                  }]
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `rejects allow not-principal bucket policy`() {
        assertTrue(
            reportBucketPolicyGrantsPublicAccess(
                """
                {
                  "Statement": [{
                    "Effect": "Allow",
                    "NotPrincipal": { "AWS": "arn:aws:iam::123456789012:role/report-reader" },
                    "Action": "s3:GetObject",
                    "Resource": "arn:aws:s3:::peak-private-reports/*"
                  }]
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `allows specific principal bucket policy`() {
        assertFalse(
            reportBucketPolicyGrantsPublicAccess(
                """
                {
                  "Statement": [{
                    "Effect": "Allow",
                    "Principal": { "AWS": "arn:aws:iam::123456789012:role/report-reader" },
                    "Action": "s3:GetObject",
                    "Resource": "arn:aws:s3:::peak-private-reports/*"
                  }]
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `allows deny wildcard bucket policy`() {
        assertFalse(
            reportBucketPolicyGrantsPublicAccess(
                """
                {
                  "Statement": [{
                    "Effect": "Deny",
                    "Principal": "*",
                    "Action": "s3:GetObject",
                    "Resource": "arn:aws:s3:::peak-private-reports/*"
                  }]
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `allows blank bucket policy response`() {
        assertFalse(reportBucketPolicyGrantsPublicAccess(""))
        assertFalse(reportBucketPolicyGrantsPublicAccess("   \n  "))
    }

    @Test
    fun `invalid bucket policy fails closed`() {
        assertFailsWith<IllegalStateException> {
            reportBucketPolicyGrantsPublicAccess("not-json")
        }
    }
}
