package com.lightningkite.services.cache.dynamodb

import com.lightningkite.services.TestSettingContext
import kotlinx.coroutines.runBlocking
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `DynamoDbCache` has no runnable local test harness here (the embedded-DynamoDB conformance suite
 * in [DynamoTest] is disabled — see its `@Ignore`), so this exercises [DynamoDbCache.disconnect]'s
 * lifecycle contract directly against a client factory, with no real network access: building or
 * closing a [DynamoDbAsyncClient] that's never used to make a request doesn't touch the network.
 */
class DynamoDbCacheDisconnectTest {

    private fun dummyClient(onClose: () -> Unit): DynamoDbAsyncClient =
        object : DynamoDbAsyncClientDelegate(
            DynamoDbAsyncClient.builder()
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("key", "secret")))
                .build()
        ) {
            override fun close() {
                onClose()
                super.close()
            }
        }

    @Test
    fun disconnectClosesTheClientAndIsIdempotentAndReconnectRebuilds() = runBlocking {
        var buildCount = 0
        var closeCount = 0
        val cache = DynamoDbCache(
            name = "disconnect-test",
            makeClient = { buildCount++; dummyClient { closeCount++ } },
            tableName = "unused",
            context = TestSettingContext(),
        )

        // Accessing `.client` alone (no operations) never hits the network — it just materializes
        // the lazy client from `makeClient`.
        cache.client
        assertEquals(1, buildCount)
        assertEquals(0, closeCount)

        cache.disconnect()
        assertEquals(1, closeCount, "disconnect() must close the built client")

        // Idempotent: a second disconnect() before anything rebuilds must not double-close or throw.
        cache.disconnect()
        assertEquals(1, closeCount, "a second disconnect() with nothing (re)built must be a no-op")

        // Disconnecting must not permanently brick the cache — the next access rebuilds a fresh client.
        cache.client
        assertEquals(2, buildCount, "accessing the cache after disconnect() must rebuild the client")
        assertEquals(1, closeCount, "rebuilding must not close the new client")
    }

    @Test
    fun disconnectWithoutEverConnectingIsANoOp() = runBlocking {
        var closeCount = 0
        val cache = DynamoDbCache(
            name = "disconnect-test-2",
            makeClient = { dummyClient { closeCount++ } },
            tableName = "unused",
            context = TestSettingContext(),
        )

        // Never accessed `.client`, so there is nothing to close.
        cache.disconnect()
        assertEquals(0, closeCount)
    }
}
