package com.lightningkite.services.files.s3

import com.lightningkite.services.MetricSink
import com.lightningkite.services.SettingContext
import com.lightningkite.services.files.PublicFileSystem
import com.lightningkite.services.files.test.FileSystemTests
import com.lightningkite.services.test.performance
import io.ktor.client.request.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import org.junit.Ignore
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

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
        S3PublicFileSystem(
            region = Region.US_WEST_2,
            bucket = "lightningkite-unit-test-bucket",
            signedUrlDuration = 15.minutes,
            credentialProvider = DefaultCredentialsProvider.builder().profileName("lk").build(),
            context = object: SettingContext {
                override val projectName: String get() = "files"
                override val internalSerializersModule: SerializersModule = EmptySerializersModule()
                override val metricSink: MetricSink = MetricSink.None(this)
                override val secretBasis: ByteArray = byteArrayOf()

            }
        )
    }
    
    override fun uploadHeaders(builder: HttpRequestBuilder) {
        builder.headers.append("Content-Type", "text/plain")
    }

    @Test fun signingPerformance() {
        val system = system ?: return
        val file = system.root.resolve("test.txt")
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
}