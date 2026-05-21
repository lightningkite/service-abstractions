// by Claude
package com.lightningkite.services.pubsub.aws

import com.lightningkite.services.TestSettingContext
import kotlinx.coroutines.runBlocking
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertSame

/**
 * Verifies that [DynamoDbPubSub] treats initialization failure as terminal.
 *
 * The pubsub uses a single [kotlinx.coroutines.CompletableDeferred] gated by an
 * [java.util.concurrent.atomic.AtomicBoolean] to ensure `doInitialize()` runs at most
 * once. When the first attempt fails, the deferred completes exceptionally and every
 * subsequent caller re-throws that cached failure without retrying — retrying would
 * only hammer DynamoDB with the same broken request.
 *
 * This test exercises that contract by driving initialization through `healthCheck()`
 * (which calls `ensureReady()`) with a fake client whose `describeTable` always fails.
 */
class DynamoDbPubSubInitTerminalTest {

    private class InitFailure(message: String) : RuntimeException(message)

    /**
     * A [DynamoDbAsyncClient] proxy whose `describeTable` calls fail synchronously with
     * a counted [InitFailure]. The pubsub's `doInitialize()` only ever calls
     * `describeTable` (and possibly `createTable`/`describeTimeToLive`) — any of those
     * failing during the describe phase short-circuits initialization.
     */
    private fun failingClient(counter: AtomicInteger, error: Throwable): DynamoDbAsyncClient =
        Proxy.newProxyInstance(
            DynamoDbAsyncClient::class.java.classLoader,
            arrayOf(DynamoDbAsyncClient::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "describeTable" -> {
                    counter.incrementAndGet()
                    // Return a failed future so the SDK's .await() bridge surfaces the error.
                    CompletableFuture.failedFuture<DescribeTableResponse>(error)
                }
                "toString" -> "FailingDynamoDbAsyncClient"
                "hashCode" -> System.identityHashCode(this)
                "equals" -> false
                else -> throw UnsupportedOperationException(
                    "Fake client method ${method.name} should not be invoked during failed init",
                )
            }
        } as DynamoDbAsyncClient

    @Test
    fun `init failure is terminal and does not retry`() = runBlocking {
        val describeCalls = AtomicInteger(0)
        val rootCause = InitFailure("simulated describeTable failure")
        val client = failingClient(describeCalls, rootCause)

        val pubsub = DynamoDbPubSub(
            name = "init-terminal-test",
            makeClient = { client },
            tableName = "some-table",
            context = TestSettingContext(),
        )

        // Drive ensureReady() via emit() — healthCheck swallows exceptions into a status.
        val channel = pubsub.string("any-channel")

        val first = assertFails { runBlocking { channel.emit("first") } }
        val second = assertFails { runBlocking { channel.emit("second") } }

        // Both calls surface the same underlying error class.
        assertSame(rootCause, unwrap(first), "First failure should be the original cause")
        assertSame(rootCause, unwrap(second), "Second failure should re-throw the cached cause without retry")

        // The critical assertion: describeTable was called exactly once. The terminal
        // CompletableDeferred replays the cached failure for every subsequent ensureReady().
        assertEquals(
            1,
            describeCalls.get(),
            "describeTable must only run once; subsequent ensureReady() calls must reuse cached failure",
        )
    }

    /**
     * `CompletableDeferred.completeExceptionally` wraps the original throwable when it is
     * later awaited. Unwrap one layer so the assertion can verify the original cause was
     * propagated rather than a fresh exception (which would imply a retry).
     */
    private tailrec fun unwrap(t: Throwable): Throwable {
        val cause = t.cause
        return if (cause == null || cause === t) t else unwrap(cause)
    }
}
