// by Claude
package com.lightningkite.services.cache.dynamodb

import com.lightningkite.services.TestSettingContext
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Verifies the documented PUBLICATION-mode contract of [DynamoDbCache.client].
 *
 * The cache's client field switched from SYNCHRONIZED to PUBLICATION lazy mode. Under
 * PUBLICATION, multiple threads racing on the first access may each invoke `makeClient`,
 * but only one of the produced values is published and all subsequent reads return it.
 * The AWS SDK async client is thread-safe so the redundant constructions are harmless.
 *
 * This test does not exercise any DynamoDB I/O — it only inspects how `lazy` resolves
 * the `client` property under concurrent first access.
 */
class DynamoDbCacheLazyInitTest {

    /**
     * Builds a no-op stand-in for [DynamoDbAsyncClient] via a JDK dynamic proxy. The
     * cache's lazy block never invokes any method on the returned client, so the proxy
     * only needs object identity — any actual method call would throw, which is the
     * desired fail-fast behavior if the test starts touching the client unexpectedly.
     */
    private fun fakeClient(): DynamoDbAsyncClient = Proxy.newProxyInstance(
        DynamoDbAsyncClient::class.java.classLoader,
        arrayOf(DynamoDbAsyncClient::class.java),
    ) { _, method, _ ->
        // toString/equals/hashCode are still useful for debugging and collection use.
        when (method.name) {
            "toString" -> "FakeDynamoDbAsyncClient"
            "hashCode" -> System.identityHashCode(this)
            "equals" -> false
            else -> throw UnsupportedOperationException("Fake client method ${method.name} should not be called")
        }
    } as DynamoDbAsyncClient

    @Test
    fun `concurrent first-access uses PUBLICATION semantics safely`() {
        val threadCount = 32
        val invocationCount = AtomicInteger(0)
        val cache = DynamoDbCache(
            name = "lazy-init-test",
            makeClient = {
                invocationCount.incrementAndGet()
                fakeClient()
            },
            tableName = "irrelevant",
            context = TestSettingContext(),
        )

        val executor = Executors.newFixedThreadPool(threadCount)
        try {
            val start = CountDownLatch(1)
            val done = CountDownLatch(threadCount)
            val observed = arrayOfNulls<DynamoDbAsyncClient>(threadCount)
            val failures = mutableListOf<Throwable>()

            repeat(threadCount) { i ->
                executor.submit {
                    try {
                        start.await()
                        observed[i] = cache.client
                    } catch (t: Throwable) {
                        synchronized(failures) { failures.add(t) }
                    } finally {
                        done.countDown()
                    }
                }
            }

            start.countDown()
            assertTrue(done.await(10, TimeUnit.SECONDS), "Threads did not finish in time")

            assertTrue(failures.isEmpty(), "Unexpected failures racing on client: $failures")

            // PUBLICATION may invoke makeClient between 1 and threadCount times.
            val count = invocationCount.get()
            assertTrue(count >= 1, "Expected at least one client construction, got $count")
            assertTrue(
                count <= threadCount,
                "Expected at most $threadCount client constructions, got $count",
            )

            // Every observing thread saw a non-null client.
            observed.forEachIndexed { i, c -> assertNotNull(c, "Thread $i saw null client") }

            // Subsequent reads return the published instance — same reference each call.
            val published = cache.client
            repeat(8) { assertSame(published, cache.client, "client must be stable after publication") }
        } finally {
            executor.shutdownNow()
        }
    }
}
