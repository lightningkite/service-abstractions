package com.lightningkite.services.cache.test

import com.lightningkite.services.cache.*
import com.lightningkite.services.data.HealthStatus
import com.lightningkite.services.test.runTestWithClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

abstract class CacheTest {
    abstract val cache: Cache?

    open fun runSuspendingTest(body: suspend CoroutineScope.() -> Unit) = runTestWithClock { body() }

    @Test
    fun test() {
        val cache = cache ?: run {
            println("Could not test because the cache is not supported on this system.")
            return
        }
        runSuspendingTest {
            val key = "key-${Uuid.random()}"
            assertEquals(null, cache.get<Int>(key))
            cache.set(key, 8)
            assertEquals(8, cache.get<Int>(key))
            cache.set(key, 1)
            assertEquals(1, cache.get<Int>(key))
            assertTrue(cache.modify<Int>(key) { it?.plus(1) })
            assertEquals(2, cache.get<Int>(key))
            assertEquals(4, cache.add(key, 2))
            assertEquals(4, cache.get<Int>(key))
            cache.remove(key)
            assertEquals(null, cache.get<Int>(key))
            cache.setIfNotExists(key, 2)
            cache.setIfNotExists(key, 3)
            assertEquals(2, cache.get<Int>(key))

            cache.remove(key)
            assertTrue(cache.modify<Int>(key) { it?.plus(1) ?: 0 })
            assertEquals(0, cache.get<Int>(key))

            cache.remove(key)
            assertEquals(1, cache.add(key, 1))
            assertEquals(1, cache.get<Int>(key))
            assertEquals(2, cache.add(key, 1))
            assertEquals(2, cache.get<Int>(key))
        }
    }

    @Test
    fun healthCheck() {
        val cache = cache ?: run {
            println("Could not test because the cache is not supported on this system.")
            return
        }
        runSuspendingTest {
            val h = cache.healthCheck()
            assertEquals(HealthStatus.Level.OK, h.level, "Health status not OK; got ${h}")
        }
    }

    @Test
    fun addReturnValueTest() {
        val cache = cache ?: run {
            println("Could not test because the cache is not supported on this system.")
            return
        }
        runSuspendingTest {
            val key = "add-return-${Uuid.random()}"

            // Adding to non-existent key should return the value
            assertEquals(5, cache.add(key, 5))
            assertEquals(5, cache.get<Int>(key))

            // Adding again should return the sum
            assertEquals(8, cache.add(key, 3))
            assertEquals(8, cache.get<Int>(key))

            // Negative values (decrement)
            assertEquals(5, cache.add(key, -3))
            assertEquals(5, cache.get<Int>(key))

            // Multiple additions in sequence
            assertEquals(15, cache.add(key, 10))
            assertEquals(35, cache.add(key, 20))
            assertEquals(35, cache.get<Int>(key))

            cache.remove(key)

            // Test with Long values
            assertEquals(1000000000L, cache.add(key, 1000000000L))
            assertEquals(2000000000L, cache.add(key, 1000000000L))
            assertEquals(2000000000L, cache.get<Long>(key))

            cache.remove(key)

            // Zero addition
            cache.set(key, 42)
            assertEquals(42, cache.add(key, 0))
            assertEquals(42, cache.get<Int>(key))
        }
    }

    open val waitScale: Duration = 0.25.seconds

    @Test
    fun expirationTest() {
        val cache = cache ?: run {
            println("Could not test because the cache is not supported on this system.")
            return
        }
        runSuspendingTest {
            val key = "x"
            assertEquals(null, cache.get<Int>(key))
            cache.set<Int>(key, 1, waitScale)
            assertEquals(1, cache.get<Int>(key))
            delay(waitScale * 1.5)
            assertEquals(null, cache.get<Int>(key))
            cache.set<Int>(key, 1, waitScale)
            assertEquals(2, cache.add(key, 1, waitScale))
            assertEquals(2, cache.get<Int>(key))
            delay(waitScale * 1.5)
            assertEquals(null, cache.get<Int>(key))
            assertEquals(1, cache.add(key, 1, waitScale))
            assertEquals(1, cache.get<Int>(key))
            delay(waitScale * 1.5)
            assertEquals(null, cache.get<Int>(key))
        }
        runSuspendingTest {
            val key = "y"
            assertEquals(null, cache.get<Int>(key))
            assertEquals(1, cache.add(key, 1, waitScale))
            assertEquals(1, cache.get<Int>(key))
            delay(waitScale * 1.5)
            assertEquals(null, cache.get<Int>(key))
        }
    }
}