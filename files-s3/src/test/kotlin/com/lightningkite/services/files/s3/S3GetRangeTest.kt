package com.lightningkite.services.files.s3

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.data.MediaType
import kotlinx.coroutines.test.runTest
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.http.AbortableInputStream
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectResponse
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.S3Exception
import java.io.ByteArrayInputStream
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * [S3ExternalFileSystem.getRange] against a stubbed client, so the parts that are easy to get wrong
 * are checked without a bucket: the exact `Range` header, and the fact that S3's 416 answer to a
 * range beyond the end of an object is the contract's empty window rather than a thrown error.
 */
class S3GetRangeTest {
    init {
        @Suppress("UNUSED_EXPRESSION")
        S3ExternalFileSystem
    }

    /** Records the request it is given and answers with [body], or throws [failWith] if set. */
    private class RangeS3Client(
        private val body: ByteArray = ByteArray(0),
        private val failWith: RuntimeException? = null,
    ) : S3Client {
        var capturedRange: String? = null
        override fun serviceName(): String = "s3"
        override fun close() = Unit

        override fun getObject(getObjectRequest: GetObjectRequest): ResponseInputStream<GetObjectResponse> {
            capturedRange = getObjectRequest.range()
            failWith?.let { throw it }
            return ResponseInputStream(
                GetObjectResponse.builder()
                    .contentType(MediaType.Text.Plain.toString())
                    .contentLength(body.size.toLong())
                    .build(),
                AbortableInputStream.create(ByteArrayInputStream(body))
            )
        }
    }

    /** Only [headObject] matters here: the 416 path re-heads the object for its content type. */
    private class HeadOnlyS3AsyncClient(private val contentLength: Long) : S3AsyncClient {
        override fun serviceName(): String = "s3"
        override fun close() = Unit

        override fun headObject(consumer: Consumer<HeadObjectRequest.Builder>): CompletableFuture<HeadObjectResponse> =
            CompletableFuture.completedFuture(
                HeadObjectResponse.builder()
                    .contentType(MediaType.Text.Plain.toString())
                    .contentLength(contentLength)
                    .lastModified(Instant.now())
                    .build()
            )
    }

    private fun newSystem(sync: S3Client, async: S3AsyncClient? = null): S3ExternalFileSystem {
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
        S3ExternalFileSystem::class.java.getDeclaredField("s3\$delegate").also { it.isAccessible = true }
            .set(system, lazyOf(sync))
        async?.let {
            S3ExternalFileSystem::class.java.getDeclaredField("s3Async\$delegate").also { f -> f.isAccessible = true }
                .set(system, lazyOf(it))
        }
        return system
    }

    @Test
    fun requestsAnInclusiveByteRangeAndReturnsWhatCameBack() = runTest {
        val fake = RangeS3Client(body = "456".toByteArray())
        val result = newSystem(fake).root.then("file.txt").getRange(4L..6L)!!

        // Both ends inclusive, exactly as HTTP means it - an off-by-one here silently drops a byte.
        assertEquals("bytes=4-6", fake.capturedRange)
        assertEquals("456", result.data.text())
        assertEquals(3L, result.data.size)
        assertEquals(MediaType.Text.Plain, result.mediaType)
    }

    @Test
    fun aRangeBeyondTheEndIsAnEmptyWindowRatherThanA416() = runTest {
        val system = newSystem(
            sync = RangeS3Client(failWith = S3Exception.builder().statusCode(416).message("InvalidRange").build()),
            async = HeadOnlyS3AsyncClient(contentLength = 0L),
        )
        val result = system.root.then("empty.txt").getRange(0L..15L)!!
        assertEquals(0L, result.data.size)
        assertEquals(MediaType.Text.Plain, result.mediaType, "content type must survive the 416 detour")
    }

    @Test
    fun aMissingObjectIsNull() = runTest {
        val system = newSystem(RangeS3Client(failWith = NoSuchKeyException.builder().statusCode(404).build()))
        assertNull(system.root.then("gone.txt").getRange(0L..15L))
    }

    /** Any other S3 failure is a real failure and must not be mistaken for an empty window. */
    @Test
    fun otherS3FailuresPropagate() = runTest {
        val system = newSystem(RangeS3Client(failWith = S3Exception.builder().statusCode(403).build()))
        assertFailsWith<S3Exception> { system.root.then("denied.txt").getRange(0L..15L) }
    }

    @Test
    fun nonsensicalRangesAreRejectedBeforeAnyRequest() = runTest {
        val fake = RangeS3Client()
        val file = newSystem(fake).root.then("file.txt")
        assertFailsWith<IllegalArgumentException> { file.getRange(-1L..5L) }
        assertFailsWith<IllegalArgumentException> { file.getRange(5L..1L) }
        assertNull(fake.capturedRange, "a bad range must not reach S3")
    }
}
