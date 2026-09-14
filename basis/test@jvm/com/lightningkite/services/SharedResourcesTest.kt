package com.lightningkite.services

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/**
 * Tests for SharedResources functionality.
 */
class SharedResourcesTest {

    private class TestResource(val value: String)

    private object TestResourceKey : SharedResources.Key<TestResource> {
        var setupCallCount = 0

        override fun setup(context: SettingContext): TestResource {
            setupCallCount++
            return TestResource("test-value-$setupCallCount")
        }
    }

    @BeforeTest
    fun resetCounter() {
        TestResourceKey.setupCallCount = 0
    }

    @Test
    fun testResourceCreatedOnFirstAccess() {
        val resources = SharedResources()
        val context = TestSettingContext()

        assertEquals(0, TestResourceKey.setupCallCount)

        val resource = resources.get(TestResourceKey, context)

        assertEquals(1, TestResourceKey.setupCallCount)
        assertEquals("test-value-1", resource.value)
    }

    @Test
    fun testResourceCachedAfterCreation() {
        val resources = SharedResources()
        val context = TestSettingContext()

        val resource1 = resources.get(TestResourceKey, context)
        val resource2 = resources.get(TestResourceKey, context)

        // Should only call setup once
        assertEquals(1, TestResourceKey.setupCallCount)
        // Should return same instance
        assertSame(resource1, resource2)
    }

    @Test
    fun testMultipleResourceTypes() {
        val resources = SharedResources()
        val context = TestSettingContext()

        val key1 = object : SharedResources.Key<String> {
            override fun setup(context: SettingContext) = "value1"
        }

        val key2 = object : SharedResources.Key<Int> {
            override fun setup(context: SettingContext) = 42
        }

        val value1 = resources.get(key1, context)
        val value2 = resources.get(key2, context)

        assertEquals("value1", value1)
        assertEquals(42, value2)
    }

    @Test
    fun testContextOperatorOverload() {
        val context = TestSettingContext()

        val resource1 = context[TestResourceKey]
        val resource2 = context[TestResourceKey]

        // Operator should work same as get()
        assertEquals(1, TestResourceKey.setupCallCount)
        assertSame(resource1, resource2)
    }

    @Test
    fun testSetupCalledAtMostOnceUnderConcurrentAccess() {
        // The getOrSetupAtomic path on JVM must use ConcurrentHashMap.computeIfAbsent so
        // that setup runs exactly once per key even when many threads request the resource
        // simultaneously. Previously SharedResources used getOrPut, which could call setup
        // multiple times in a race; only the first put wins but the discarded values may
        // hold open connections or threads.
        val setupCalls = AtomicInteger(0)
        val key = object : SharedResources.Key<Int> {
            override fun setup(context: SettingContext): Int {
                setupCalls.incrementAndGet()
                // Small spin to widen the race window.
                Thread.sleep(2)
                return 7
            }
        }
        val resources = SharedResources()
        val context = TestSettingContext()
        val threadCount = 32
        val pool = Executors.newFixedThreadPool(threadCount)
        try {
            val gate = CountDownLatch(1)
            val done = CountDownLatch(threadCount)
            val seen = AtomicInteger(0)
            repeat(threadCount) {
                pool.submit {
                    gate.await()
                    if (resources.get(key, context) == 7) seen.incrementAndGet()
                    done.countDown()
                }
            }
            gate.countDown()
            done.await()
            assertEquals(threadCount, seen.get(), "every caller should see the cached value")
            assertEquals(1, setupCalls.get(), "setup must run exactly once under concurrent access")
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun testSetupReturningNullIsCachedAndNotRerun() {
        // ConcurrentHashMap.computeIfAbsent rejects null values, so the JVM actual wraps
        // null with a sentinel. Verify that a null-returning setup is cached: subsequent
        // gets must NOT call setup again.
        val setupCalls = AtomicInteger(0)
        val key = object : SharedResources.Key<String?> {
            override fun setup(context: SettingContext): String? {
                setupCalls.incrementAndGet()
                return null
            }
        }
        val resources = SharedResources()
        val context = TestSettingContext()

        assertNull(resources.get(key, context))
        assertNull(resources.get(key, context))
        assertNull(resources.get(key, context))
        assertEquals(1, setupCalls.get(), "null result must be cached, setup must not re-run")
    }
}
