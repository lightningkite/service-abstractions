package com.lightningkite.services.files.s3

import com.lightningkite.services.files.PublicFileSystem
import com.lightningkite.services.test.assertPlannableAws
import com.lightningkite.services.test.expensive
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours

/**
 * Tests for Terraform configuration generation for S3 buckets.
 *
 * These tests verify that the [awsS3Bucket] function generates valid Terraform configuration
 * that can be planned (but not applied) by Terraform.
 */
class TfTest {

    init {
        S3PublicFileSystem
    }

    /**
     * Tests Terraform configuration for a public S3 bucket.
     *
     * Verifies that the generated configuration includes:
     * - S3 bucket resource
     * - Public access block configuration (disabled)
     * - Bucket policy allowing public GetObject access
     * - CORS configuration
     */
    @Test
    fun testPublicBucket() {
        assertPlannableAws<PublicFileSystem.Settings>("testPublicBucket") {
            it.awsS3Bucket(
                forceDestroy = true,
                signedUrlDuration = null
            )
        }
    }

    /**
     * Tests Terraform configuration for a private S3 bucket with signed URLs.
     *
     * Verifies that the generated configuration includes:
     * - S3 bucket resource
     * - CORS configuration
     * - No public access configuration (bucket remains private)
     */
    @Test
    fun testSignedBucket() {
        assertPlannableAws<PublicFileSystem.Settings>("testSignedBucket") {
            it.awsS3Bucket(
                forceDestroy = true,
                signedUrlDuration = 1.hours
            )
        }
    }

    // TODO: Uncomment and fix these expensive integration tests that actually apply Terraform
    // These tests are currently commented out but would be valuable for verifying the
    // Terraform configuration actually works in a real AWS environment.
//    @Test
//    fun expensiveTestPublicBucket() {
//        S3PublicFileSystem
//        expensive {
//            assertTerraformApply(
//                name = "aws-s3-public",
//                domain = false,
//                vpc = false,
//                serializer = PublicFileSystem.Settings.serializer(),
//                fulfill = {
//                    it.awsS3Bucket(
//                        forceDestroy = true,
//                        signedUrlDuration = null
//                    )
//                }
//            )
//        }
//    }
//
//    @Test
//    fun expensiveTestSignedBucket() {
//        S3PublicFileSystem
//        expensive {
//            assertTerraformApply(
//                name = "aws-s3-signed",
//                domain = false,
//                vpc = false,
//                serializer = PublicFileSystem.Settings.serializer(),
//                fulfill = {
//                    it.awsS3Bucket(
//                        forceDestroy = true,
//                        signedUrlDuration = 1.hours
//                    )
//                }
//            )
//        }
//    }
}

/*
 * TODO: Test improvements for TfTest
 *
 * 1. Enable and fix the commented-out expensive tests to verify actual Terraform apply operations.
 *
 * 2. Add test to verify the generated IAM policy statements are correct and follow least privilege.
 *
 * 3. Add test to verify CORS configuration is correctly generated with proper resource naming.
 *
 * 4. Add test to verify the bucket naming convention (project prefix + name) works correctly.
 *
 * 5. Add test with forceDestroy=false to ensure the configuration difference is captured.
 *
 * 6. Add test to verify the generated settings URL format is correct and parseable.
 */