package com.lightningkite.services.files.s3

import com.lightningkite.services.TestSettingContext
import kotlinx.coroutines.test.runTest
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response
import software.amazon.awssdk.services.s3.model.S3Object
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression test for FIX 18: [S3ExternalFileSystem.flow] must only return objects that are direct
 * children of the listed directory, not sibling keys that merely share a string prefix with it.
 *
 * Before the fix, `unixPathOf` never appended a trailing "/" to the S3 `prefix`, so listing
 * "users/1" also matched keys like "users/10/b" and "users/123/c" at the raw-string level.
 */
class S3FlowPrefixScopingTest {
    init {
        // Force class init so S3ExternalFileSystem's companion registers the URL scheme;
        // the bare reference is the point of this line.
        @Suppress("UNUSED_EXPRESSION")
        S3ExternalFileSystem
    }

    /**
     * A minimal [S3AsyncClient] stub that returns every canned key whose raw string starts with the
     * requested prefix - i.e. it faithfully reproduces S3's byte-prefix-only matching, leaving any
     * further scoping (delimiter boundaries) to the caller, exactly like the real service does.
     */
    private class FakeS3AsyncClient(private val allKeys: List<String>) : S3AsyncClient {
        override fun serviceName(): String = "s3"
        override fun close() = Unit

        override fun listObjectsV2(
            consumer: Consumer<ListObjectsV2Request.Builder>,
        ): CompletableFuture<ListObjectsV2Response> {
            val prefix = ListObjectsV2Request.builder().also { consumer.accept(it) }.build().prefix() ?: ""
            val matching = allKeys.filter { it.startsWith(prefix) }
            return CompletableFuture.completedFuture(
                ListObjectsV2Response.builder()
                    .contents(matching.map { S3Object.builder().key(it).build() })
                    .isTruncated(false)
                    .build()
            )
        }
    }

    private fun injectS3Async(system: S3ExternalFileSystem, client: S3AsyncClient) {
        val field = S3ExternalFileSystem::class.java.getDeclaredField("s3Async\$delegate")
        field.isAccessible = true
        field.set(system, lazyOf(client))
    }

    private fun newSystem(client: S3AsyncClient): S3ExternalFileSystem {
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
        injectS3Async(system, client)
        return system
    }

    @Test
    fun listingADirectoryExcludesSiblingsSharingAStringPrefix() = runTest {
        val system = newSystem(FakeS3AsyncClient(listOf("users/1/a", "users/10/b", "users/123/c")))

        val children = system.root.then("users/1").list()

        assertEquals(listOf("users/1/a"), children.map { it.path.parts.joinToString("/") })
    }

    @Test
    fun listingTheRootStillWorks() = runTest {
        val system = newSystem(FakeS3AsyncClient(listOf("top-level.txt")))

        val children = system.root.list()

        assertEquals(listOf("top-level.txt"), children.map { it.path.parts.joinToString("/") })
    }
}
