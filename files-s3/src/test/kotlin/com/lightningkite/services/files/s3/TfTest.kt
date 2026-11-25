package com.lightningkite.services.files.s3

import com.lightningkite.services.files.ExternalFileSystem
import com.lightningkite.services.terraform.KmsKeySource
import com.lightningkite.services.terraform.TerraformEmitterAwsDomain
import com.lightningkite.services.terraform.TerraformNeed
import com.lightningkite.services.test.TerraformEmitterAwsTest
import com.lightningkite.services.test.TerraformEmitterAwsTestWithDomain
import com.lightningkite.services.test.assertPlannableAws
import com.lightningkite.services.test.assertPlannableAwsDomain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Tests for Terraform configuration generation for S3 buckets.
 *
 * These tests verify that the [awsS3Bucket] function generates valid Terraform configuration
 * that can be planned (but not applied) by Terraform.
 */
class TfTest {

    init {
        // Force class init so S3ExternalFileSystem's companion registers the URL scheme;
        // the bare reference is the point of this line.
        @Suppress("UNUSED_EXPRESSION")
        S3ExternalFileSystem
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
        assertPlannableAws<ExternalFileSystem.Settings>("testPublicBucket") {
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
        assertPlannableAws<ExternalFileSystem.Settings>("testSignedBucket") {
            it.awsS3Bucket(
                forceDestroy = true,
                signedUrlDuration = 1.hours
            )
        }
    }

    @Test
    fun testPublicBucketOnDomain() {
        assertPlannableAwsDomain<ExternalFileSystem.Settings>("testPublicBucketOnDomain") {
            it.awsS3Bucket(signedUrlDuration = null, domain = "files.test.cs.lightningkite.com")
        }
    }

    @Test
    fun testSignedBucketOnDomain() {
        assertPlannableAwsDomain<ExternalFileSystem.Settings>("testSignedBucketOnDomain") {
            it.awsS3Bucket(
                signedUrlDuration = 1.hours,
                domain = "files.test.cs.lightningkite.com",
                kmsKey = KmsKeySource.CreateDedicated,
            )
        }
    }

    /** Generates (without planning, so no AWS access is needed) and returns the bucket's resources and setting. */
    private fun generate(
        dir: String,
        fulfill: context(TerraformEmitterAwsDomain) (TerraformNeed<ExternalFileSystem.Settings>) -> Unit,
    ): Pair<JsonObject, String> {
        val emitter = TerraformEmitterAwsTestWithDomain(File("build/test/$dir"), "test", serializer<ExternalFileSystem.Settings>())
        with(emitter) { fulfill(TerraformNeed("test")) }
        emitter.write()
        return emitter.files.getValue("test").toJsonObject() to emitter.settings.getValue("test").jsonPrimitive.content
    }

    private fun JsonObject.resource(type: String, name: String): JsonObject? =
        this["resource"]?.jsonObject?.get(type)?.jsonObject?.get(name)?.jsonObject

    @Test
    fun signedBucketLeavesTheUrlKeyToTheContext() {
        // The key that lets every server verify S3 presigned URLs comes from SettingContext.deriveSecret,
        // so Terraform generates no secret for it.
        val (tf, setting) = generate("signedBucketLeavesTheUrlKeyToTheContext") { it.awsS3Bucket(signedUrlDuration = 1.hours) }
        assertNull(tf["resource"]!!.jsonObject["random_id"])
        assertTrue(setting.endsWith("/?signedUrlDuration=1h"), setting)
    }

    @Test
    fun signedDomainGeneration() {
        val (tf, setting) = generate("signedDomainGeneration") {
            it.awsS3Bucket(signedUrlDuration = 1.hours, domain = "files.test.cs.lightningkite.com")
        }
        val behavior = tf.resource("aws_cloudfront_distribution", "test")!!["default_cache_behavior"]!!.jsonObject
        assertEquals(listOf("\${aws_cloudfront_key_group.test.id}"), behavior["trusted_key_groups"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertNotNull(tf.resource("tls_private_key", "test_cloudfront"))
        assertEquals("aws.acm", tf.resource("aws_acm_certificate", "test_cloudfront")!!["provider"]!!.jsonPrimitive.content)
        assertTrue("cloudfront.amazonaws.com" in tf.resource("aws_s3_bucket_policy", "test")!!["policy"]!!.jsonPrimitive.content)
        assertEquals(true, tf.resource("aws_s3_bucket_public_access_block", "test")!!["block_public_policy"]!!.jsonPrimitive.content.toBoolean())
        assertTrue("domain=files.test.cs.lightningkite.com" in setting, setting)
        assertTrue("signedUrlDuration=1h" in setting, setting)
        assertTrue("cloudFrontKeyId=\${aws_cloudfront_public_key.test.id}" in setting, setting)
        assertTrue("cloudFrontPrivateKey=\${urlencode(base64encode(tls_private_key.test_cloudfront.private_key_pem_pkcs8))}" in setting, setting)
    }

    @Test
    fun publicDomainGeneration() {
        val (tf, setting) = generate("publicDomainGeneration") {
            it.awsS3Bucket(signedUrlDuration = null, domain = "files.test.cs.lightningkite.com")
        }
        val behavior = tf.resource("aws_cloudfront_distribution", "test")!!["default_cache_behavior"]!!.jsonObject
        assertNull(behavior["trusted_key_groups"])
        assertNull(tf.resource("tls_private_key", "test_cloudfront"))
        // One policy: readable through CloudFront, and still publicly on the S3 host for links issued before.
        val policy = Json.parseToJsonElement(tf.resource("aws_s3_bucket_policy", "test")!!["policy"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("PublicReadGetObject", "AllowCloudFrontRead"),
            policy.jsonObject["Statement"]!!.jsonArray.map { it.jsonObject["Sid"]!!.jsonPrimitive.content },
        )
        assertEquals(false, tf.resource("aws_s3_bucket_public_access_block", "test")!!["block_public_policy"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(setting.endsWith("/?signedUrlDuration=forever&domain=files.test.cs.lightningkite.com"), setting)
    }

    @Test
    fun infiniteDurationOnDomainIsPublic() {
        val (tf, setting) = generate("infiniteDurationOnDomainIsPublic") {
            it.awsS3Bucket(signedUrlDuration = Duration.INFINITE, domain = "files.test.cs.lightningkite.com")
        }
        val behavior = tf.resource("aws_cloudfront_distribution", "test")!!["default_cache_behavior"]!!.jsonObject
        assertNull(behavior["trusted_key_groups"])
        assertNull(tf.resource("tls_private_key", "test_cloudfront"))
        assertEquals(false, tf.resource("aws_s3_bucket_public_access_block", "test")!!["block_public_policy"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(setting.endsWith("/?signedUrlDuration=forever&domain=files.test.cs.lightningkite.com"), setting)
    }

    @Test
    fun dedicatedKmsKeyLetsCloudFrontDecrypt() {
        val (tf, _) = generate("dedicatedKmsKeyLetsCloudFrontDecrypt") {
            it.awsS3Bucket(
                signedUrlDuration = 1.hours,
                domain = "files.test.cs.lightningkite.com",
                kmsKey = KmsKeySource.CreateDedicated,
            )
        }
        val policy = tf.resource("aws_kms_key_policy", "test_cmk")!!["policy"]!!.jsonPrimitive.content
        assertTrue("kms:Decrypt" in policy && "aws_cloudfront_distribution.test.arn" in policy, policy)
    }

    @Test
    fun domainNeedsAZone() {
        // Plain AWS emitter: no zone to fall back on.
        val emitter = TerraformEmitterAwsTest(File("build/test/domainNeedsAZone"), "test", serializer<ExternalFileSystem.Settings>())
        assertFailsWith<IllegalArgumentException> {
            with(emitter) { TerraformNeed<ExternalFileSystem.Settings>("test").awsS3Bucket(domain = "files.example.com") }
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