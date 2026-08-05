package com.lightningkite.services.pubsub.aws

import com.lightningkite.services.TestSettingContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.*
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Verifies that [DynamoDbPubSub.collect] lets exceptions thrown by the CALLER's own downstream
 * `collect {}` logic propagate, rather than mistaking them for a deserialization failure.
 *
 * The `collect()` loop used to wrap both `decode(message)` and `collector.emit(value)` in the
 * same try/catch that exists only to skip malformed messages, so a bug in the caller's own
 * collector body was silently swallowed and logged at debug level - unlike RedisPubSub/LocalPubSub,
 * where such an exception propagates out of `collect()` and terminates that subscriber.
 *
 * Uses a [Proxy]-backed fake [DynamoDbAsyncClient] (same technique as
 * [DynamoDbPubSubInitTerminalTest]) so this runs without live AWS infrastructure - the module's
 * one integration test class is [org.junit.Ignore]d because it needs a real/embedded DynamoDB,
 * but the decode/emit path this test exercises doesn't need real polling semantics, just a
 * canned response.
 */
class DynamoDbPubSubCollectExceptionTest {

    /**
     * A [DynamoDbAsyncClient] proxy that reports the table as already ACTIVE with TTL already
     * enabled (so `doInitialize()` short-circuits without creating anything), and returns exactly
     * one message the first time `collect()`'s poll loop queries for new items - every other query
     * (the initial max-seq lookup, and every later poll) returns an empty page.
     */
    private fun singleMessageClient(message: String): DynamoDbAsyncClient {
        var pollCallCount = 0
        return Proxy.newProxyInstance(
            DynamoDbAsyncClient::class.java.classLoader,
            arrayOf(DynamoDbAsyncClient::class.java),
        ) { _, method, args ->
            when (method.name) {
                "describeTable" -> CompletableFuture.completedFuture(
                    DescribeTableResponse.builder()
                        .table(TableDescription.builder().tableStatus(TableStatus.ACTIVE).build())
                        .build()
                )

                "describeTimeToLive" -> CompletableFuture.completedFuture(
                    DescribeTimeToLiveResponse.builder()
                        .timeToLiveDescription(
                            TimeToLiveDescription.builder().timeToLiveStatus(TimeToLiveStatus.ENABLED).build()
                        )
                        .build()
                )

                "query" -> {
                    @Suppress("UNCHECKED_CAST")
                    val consumer = args[0] as Consumer<QueryRequest.Builder>
                    val request = QueryRequest.builder().also { consumer.accept(it) }.build()

                    // The initial "current max seq" bootstrap query filters on attribute_exists(message);
                    // the ordinary poll queries don't. Only the first ordinary poll returns a message.
                    val isMaxSeqLookup = request.filterExpression() != null
                    val items = if (!isMaxSeqLookup && pollCallCount++ == 0) {
                        listOf(
                            mapOf(
                                "channel" to AttributeValue.fromS("chan"),
                                "seq" to AttributeValue.fromN("1"),
                                "message" to AttributeValue.fromS(message),
                            )
                        )
                    } else {
                        emptyList()
                    }
                    // count() must be set explicitly - real DynamoDB always populates it, but a
                    // hand-built response leaves it null, which NPEs in collect()'s poll handling.
                    val response = QueryResponse.builder().items(items).count(items.size).build()
                    CompletableFuture.completedFuture(response)
                }

                "toString" -> "SingleMessageDynamoDbAsyncClient"
                "hashCode" -> System.identityHashCode(this)
                "equals" -> false
                else -> throw UnsupportedOperationException(
                    "Fake client method ${method.name} should not be invoked in this test",
                )
            }
        } as DynamoDbAsyncClient
    }

    @Test
    fun `exception from the caller's collect block propagates instead of being swallowed`() = runBlocking {
        val client = singleMessageClient("hello")
        val pubsub = DynamoDbPubSub(
            name = "test",
            makeClient = { client },
            tableName = "some-table",
            context = TestSettingContext(),
            // A positive poll interval ensures collect()'s loop actually suspends via delay()
            // every iteration, so if the caller's exception is (incorrectly) swallowed instead of
            // propagating, withTimeout below gets a fair chance to interrupt the loop instead of
            // racing against a tight, non-suspending busy loop.
            pollInterval = 10.milliseconds,
        )
        val channel = pubsub.string("chan")

        val thrown = assertFailsWith<IllegalStateException> {
            withTimeout(2.seconds) {
                channel.collect { throw IllegalStateException("boom from caller") }
            }
        }
        // On the JVM, CancellationException (which withTimeout throws once its own deadline
        // elapses) is itself an IllegalStateException, so assertFailsWith<IllegalStateException>
        // alone can't distinguish "the caller's exception propagated" from "the poll loop swallowed
        // it and collect() just ran until timeout". Rule the latter out explicitly.
        assertFalse(thrown is CancellationException, "collect() should have propagated the caller's exception, not timed out: $thrown")
        assertEquals("boom from caller", thrown.message)
    }
}
