package com.lightningkite.services.files.s3

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.data.Data
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Regression test for FIX 19: [S3ExternalFileSystem.put] must not buffer unknown-size content into a
 * single in-memory [ByteArray] via `RequestBody.fromBytes`. Instead it should spool the content to a
 * temp file (a bounded, streaming copy) to learn its length, upload from that file, and delete it.
 */
class S3PutUnknownSizeTest {
    init {
        @Suppress("UNUSED_EXPRESSION")
        S3ExternalFileSystem
    }

    /** A minimal [S3Client] stub that records whatever [RequestBody] `put()` hands it. */
    private class CapturingS3Client : S3Client {
        var capturedRequestBody: RequestBody? = null
        override fun serviceName(): String = "s3"
        override fun close() = Unit

        override fun putObject(putObjectRequest: PutObjectRequest, requestBody: RequestBody): PutObjectResponse {
            capturedRequestBody = requestBody
            return PutObjectResponse.builder().build()
        }
    }

    private fun injectS3(system: S3ExternalFileSystem, client: S3Client) {
        val field = S3ExternalFileSystem::class.java.getDeclaredField("s3\$delegate")
        field.isAccessible = true
        field.set(system, lazyOf(client))
    }

    @Test
    fun unknownSizeContentIsSpooledToATempFileAndCleanedUp() = runTest {
        val system = S3ExternalFileSystem(
            name = "test",
            region = Region.US_EAST_1,
            credentialProvider = StaticCredentialsProvider.create(
                AwsBasicCredentials.create("test-access", "test-secret")
            ),
            bucket = "test-bucket",
            signedUrlDuration = null,
            context = TestSettingContext(),
        )
        val fake = CapturingS3Client()
        injectS3(system, fake)

        // Nontrivial size so a naive fromBytes() implementation would still "work" - the point is
        // proving the temp file is used and cleaned up, not that a huge payload fits in memory.
        val payload = ByteArray(500_000) { (it % 256).toByte() }
        // Data.Source with size = null is exactly the "unknown size" case that used to force
        // RequestBody.fromBytes(content.data.bytes()) - a full in-memory buffer.
        val unknownSizeData = Data.Source(Buffer().also { it.write(payload) }, size = null)

        val tempDir = File(System.getProperty("java.io.tmpdir"))
        val tempFilesBefore = tempDir.listFiles()?.toSet() ?: emptySet()

        system.root.then("unknown-size.bin").put(TypedData(unknownSizeData, MediaType.Application.OctetStream))

        val tempFilesAfter = tempDir.listFiles()?.toSet() ?: emptySet()
        assertEquals(tempFilesBefore, tempFilesAfter, "put() must clean up its spooled temp file")

        val body = fake.capturedRequestBody ?: error("putObject was never called")
        assertEquals(payload.size.toLong(), body.optionalContentLength().orElse(-1L), "content length must be known before upload")
        val uploaded = body.contentStreamProvider()!!.newStream().readBytes()
        assertContentEquals(payload, uploaded)
    }
}
