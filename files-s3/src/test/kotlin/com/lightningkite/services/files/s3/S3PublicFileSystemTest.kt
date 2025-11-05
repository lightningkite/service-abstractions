package com.lightningkite.services.files.s3

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.files.PublicFileSystem
import com.lightningkite.services.files.test.FileSystemTests
import com.lightningkite.services.test.performance
import io.ktor.client.request.*
import kotlin.test.Test

/**
 * Tests for [S3PublicFileSystem].
 *
 * This test class extends [FileSystemTests] to run the shared file system test suite
 * against the S3 implementation. It also includes S3-specific tests for performance
 * and configuration parsing.
 *
 * Note: These tests require AWS credentials and access to a real S3 bucket.
 * The tests use the AWS profile "lk" and bucket "lightningkite-unit-test-bucket" in us-west-2.
 * To run these tests locally, configure your AWS credentials using:
 * - AWS CLI: `aws configure --profile lk`
 * - Or set environment variables: AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY, AWS_REGION
 */
class S3PublicFileSystemTest : FileSystemTests() {

    /**
     * The S3 file system instance for testing.
     * Uses the "lk" AWS profile and a test bucket in us-west-2.
     * Returns null if the system cannot be initialized (e.g., missing credentials).
     */
    override val system: S3PublicFileSystem? by lazy {
        S3PublicFileSystem
        PublicFileSystem.Settings(
            "s3://lk@lightningkite-unit-test-bucket.us-west-2.amazonaws.com"
        ).invoke("test", TestSettingContext()) as? S3PublicFileSystem
    }

    /**
     * Configures HTTP headers for upload URL tests.
     * S3 requires Content-Type to be set when uploading via signed URLs.
     */
    override fun uploadHeaders(builder: HttpRequestBuilder) {
        builder.headers.append("Content-Type", "text/plain")
    }

    /**
     * Performance comparison test between custom signing implementation and AWS SDK.
     *
     * This test validates that the custom AWS Signature V4 implementation is faster
     * than the official AWS SDK presigner. It generates 10,000 signed URLs with each
     * method and compares the execution times.
     */
    @Test fun signingPerformance() {
        val system = system ?: return
        val file = system.root.then("test.txt")
        val mine = performance(10_000) {
            file.signedUrl
        }
        val theirs = performance(10_000) {
            file.signedUrlOfficial
        }
        println("Mine: $mine")
        println("Theirs: $theirs")
        println("Ratio: ${mine / theirs}")
        assert(mine < theirs)
    }

    /**
     * Tests parsing of S3 settings URL with duration parameter.
     *
     * Verifies that the URL parser correctly handles:
     * - Bucket names without profile/credentials (uses default credential chain)
     * - Query parameters (signedUrlDuration with duration format like "1d")
     */
    @Test fun settingParse() {
        S3PublicFileSystem
        PublicFileSystem.Settings("s3://demo-example-files20220920193513533900000004.s3-us-west-2.amazonaws.com/?signedUrlDuration=1d")
            .invoke("test", TestSettingContext()) as? S3PublicFileSystem
    }
}

/*
 * TODO: Test improvements for S3PublicFileSystemTest
 *
 * 1. Add test for credential refresh behavior to ensure credentials are properly cached and refreshed
 *    when they expire.
 *
 * 2. Add test for parseInternalUrl and parseExternalUrl methods with various URL formats.
 *
 * 3. Add test for health check functionality to verify it correctly identifies bucket access issues.
 *
 * 4. Add tests for different credential provider configurations (static credentials, profile, default chain).
 *
 * 5. Add test for signing key caching to verify keys are reused within the same day.
 *
 * 6. Consider adding mock-based tests that don't require real AWS credentials for CI/CD pipelines.
 *
 * 7. Add test for URL parsing edge cases (special characters, encoded paths, multiple query parameters).
 *
 * 8. Add test to verify CORS configuration compatibility with browser uploads.
 */