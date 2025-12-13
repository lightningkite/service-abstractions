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
 * Note: This test is ignored by default because it requires AWS credentials.
 * To run this test, you need to set the following environment variables:
 * - AWS_ACCESS_KEY_ID
 * - AWS_SECRET_ACCESS_KEY
 * - AWS_BUCKET
 * - AWS_REGION
 */
class S3PublicFileSystemTest : FileSystemTests() {
    
    override val system: S3PublicFileSystem? by lazy {
        S3PublicFileSystem
        PublicFileSystem.Settings(
            "s3://lk@lightningkite-unit-test-bucket.us-west-2.amazonaws.com"
        ).invoke("test", TestSettingContext()) as? S3PublicFileSystem
    }
    
    override fun uploadHeaders(builder: HttpRequestBuilder) {
        builder.headers.append("Content-Type", "text/plain")
    }

    @Test fun signingPerformance() {
        val system = system ?: return
        val file = system.root.then("test.txt")
//        val mine = performance(10_000) {
//            file.signedUrl
//        }
        val theirs = performance(10_000) {
            file.signedUrl
        }
//        println("Mine: $mine")
        println("Theirs: $theirs")
//        println("Ratio: ${mine / theirs}")
//        assert(mine < theirs)
    }

    @Test fun settingParse() {
        S3PublicFileSystem
        PublicFileSystem.Settings("s3://demo-example-files20220920193513533900000004.s3-us-west-2.amazonaws.com/?signedUrlDuration=1d")
            .invoke("test", TestSettingContext()) as? S3PublicFileSystem
    }
}