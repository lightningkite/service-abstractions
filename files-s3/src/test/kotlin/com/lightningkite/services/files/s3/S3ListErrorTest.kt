package com.lightningkite.services.files.s3

import com.lightningkite.services.TestSettingContext
import kotlinx.coroutines.test.runTest
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Verifies that [S3FileObject.list] propagates exceptions from the underlying S3 client
 * rather than swallowing them and returning null (the prior behaviour).
 */
class S3ListErrorTest {

    init {
        S3PublicFileSystem
    }

    /**
     * A minimal [S3AsyncClient] stub whose `listObjectsV2` always fails with the supplied error.
     * Only the overloads exercised by [S3FileObject.list] are overridden.
     */
    private class FailingS3AsyncClient(private val error: RuntimeException) : S3AsyncClient {
        override fun serviceName(): String = "s3"
        override fun close() = Unit

        override fun listObjectsV2(
            consumer: Consumer<ListObjectsV2Request.Builder>,
        ): CompletableFuture<ListObjectsV2Response> =
            CompletableFuture.failedFuture(error)

        override fun listObjectsV2(
            request: ListObjectsV2Request,
        ): CompletableFuture<ListObjectsV2Response> =
            CompletableFuture.failedFuture(error)
    }

    /**
     * Replaces the lazy-delegate of [S3PublicFileSystem.s3Async] so [S3FileObject.list]
     * routes its call through the supplied stub without touching the network.
     */
    private fun injectS3Async(system: S3PublicFileSystem, client: S3AsyncClient) {
        val field = S3PublicFileSystem::class.java.getDeclaredField("s3Async\$delegate")
        field.isAccessible = true
        field.set(system, lazyOf<S3AsyncClient>(client))
    }

    @Test
    fun listPropagatesException() = runTest {
        val system = S3PublicFileSystem(
            name = "test",
            region = Region.US_EAST_1,
            credentialProvider = StaticCredentialsProvider.create(
                AwsBasicCredentials.create("test-access", "test-secret")
            ),
            bucket = "test-bucket",
            signedUrlDuration = null,
            context = TestSettingContext(),
        )
        val boom = RuntimeException("simulated S3 failure")
        injectS3Async(system, FailingS3AsyncClient(boom))

        val file = S3FileObject(system, File("some/prefix"))
        val thrown = assertFailsWith<RuntimeException> { file.list() }
        // S3AsyncClient wraps the cause in a CompletionException via .await();
        // either the original is re-thrown or it appears as the cause.
        val message = thrown.message ?: thrown.cause?.message
        assertEquals("simulated S3 failure", message ?: thrown.cause?.message)
    }
}
