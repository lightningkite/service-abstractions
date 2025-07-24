package com.lightningkite.serviceabstractions.cache.test
import com.lightningkite.serviceabstractions.HealthStatus
import com.lightningkite.serviceabstractions.cache.*
import com.lightningkite.serviceabstractions.test.runTestWithClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlin.test.*
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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
            val key = "key"
            assertEquals(null, cache.get<Int>(key))
            cache.set(key, 8)
            assertEquals(8, cache.get<Int>(key))
            cache.set(key, 1)
            assertEquals(1, cache.get<Int>(key))
            assertTrue(cache.modify<Int>(key) { it?.plus(1) })
            assertEquals(2, cache.get<Int>(key))
            cache.add(key, 2)
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
            cache.add(key, 1)
            assertEquals(1, cache.get<Int>(key))
            cache.add(key, 1)
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
            assertEquals(HealthStatus.Level.OK, cache.healthCheck().level)
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
            cache.add(key, 1, waitScale)
            assertEquals(2, cache.get<Int>(key))
            delay(waitScale * 1.5)
            assertEquals(null, cache.get<Int>(key))
            cache.add(key, 1, waitScale)
            assertEquals(1, cache.get<Int>(key))
            delay(waitScale * 1.5)
            assertEquals(null, cache.get<Int>(key))
        }
        runSuspendingTest {
            val key = "y"
            assertEquals(null, cache.get<Int>(key))
            cache.add(key, 1, waitScale)
            assertEquals(1, cache.get<Int>(key))
            delay(waitScale * 1.5)
            assertEquals(null, cache.get<Int>(key))
        }
    }
}