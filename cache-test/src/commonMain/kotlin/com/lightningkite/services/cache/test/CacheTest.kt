package com.lightningkite.services.cache.test

import com.lightningkite.services.cache.*
import com.lightningkite.services.data.HealthStatus
import com.lightningkite.services.test.runTestWithClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.serialization.builtins.serializer
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
    fun compareAndSetTtlBehavior() {
        // RedisCache's compareAndSet went through a rewrite to use cached EVALSHA Lua
        // scripts with an empty-string sentinel for "no TTL". This test pins down the
        // observable contract for all implementations:
        //
        //   1) insert via expected=null, new=value with TTL → key expires.
        //   2) insert via expected=null, new=value WITHOUT TTL → key persists past the
        //      TTL window other tests use, i.e. the sentinel did not accidentally apply
        //      a TTL.
        //   3) update via expected=current, new=other with TTL → expires.
        //   4) update via expected=current, new=other WITHOUT TTL → persists.
        //   5) delete via expected=current, new=null removes the key.
        //
        // Regressions in the Lua TTL branches (e.g. ``if ARGV[2] then`` in Lua, which is
        // ALWAYS true for any non-nil argv) would manifest as either case (2) or (4)
        // expiring unexpectedly.
        val cache = cache ?: run {
            println("Could not test because the cache is not supported on this system.")
            return
        }
        runSuspendingTest {
            val intSerializer = Int.serializer()
            // Case 1: insert with TTL expires.
            val keyTtlInsert = "cas-ttl-insert-${Uuid.random()}"
            assertTrue(cache.compareAndSet(keyTtlInsert, intSerializer, null, 1, waitScale))
            assertEquals(1, cache.get<Int>(keyTtlInsert))
            delay(waitScale * 1.5)
            assertEquals(null, cache.get<Int>(keyTtlInsert), "TTL on CAS insert must expire the key")

            // Case 2: insert without TTL persists past the TTL window.
            val keyNoTtlInsert = "cas-no-ttl-insert-${Uuid.random()}"
            assertTrue(cache.compareAndSet(keyNoTtlInsert, intSerializer, null, 2, null))
            assertEquals(2, cache.get<Int>(keyNoTtlInsert))
            delay(waitScale * 1.5)
            assertEquals(2, cache.get<Int>(keyNoTtlInsert), "CAS insert without TTL must not expire")
            cache.remove(keyNoTtlInsert)

            // Case 3: update existing value with TTL expires.
            val keyTtlUpdate = "cas-ttl-update-${Uuid.random()}"
            cache.set(keyTtlUpdate, 10)
            assertTrue(cache.compareAndSet(keyTtlUpdate, intSerializer, 10, 11, waitScale))
            assertEquals(11, cache.get<Int>(keyTtlUpdate))
            delay(waitScale * 1.5)
            assertEquals(null, cache.get<Int>(keyTtlUpdate), "TTL on CAS update must expire the key")

            // Case 4: update existing value without TTL persists.
            val keyNoTtlUpdate = "cas-no-ttl-update-${Uuid.random()}"
            cache.set(keyNoTtlUpdate, 20)
            assertTrue(cache.compareAndSet(keyNoTtlUpdate, intSerializer, 20, 21, null))
            assertEquals(21, cache.get<Int>(keyNoTtlUpdate))
            delay(waitScale * 1.5)
            assertEquals(21, cache.get<Int>(keyNoTtlUpdate), "CAS update without TTL must not expire")
            cache.remove(keyNoTtlUpdate)

            // Case 5: delete via expected=current, new=null removes the key.
            val keyDelete = "cas-delete-${Uuid.random()}"
            cache.set(keyDelete, 30)
            assertTrue(cache.compareAndSet(keyDelete, intSerializer, 30, null))
            assertEquals(null, cache.get<Int>(keyDelete))

            // Mismatched expected must not modify the value or its expiration.
            val keyMismatch = "cas-mismatch-${Uuid.random()}"
            cache.set(keyMismatch, 40)
            assertFalse(cache.compareAndSet(keyMismatch, intSerializer, 999, 41))
            assertEquals(40, cache.get<Int>(keyMismatch), "Mismatched expected must not update value")
            cache.remove(keyMismatch)
        }
    }

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

    @Test
    fun getAndRemoveTest() {
        val cache = cache ?: run {
            println("Could not test because the cache is not supported on this system.")
            return
        }
        runSuspendingTest {
            val key = "get-and-delete-${Uuid.random()}"

            // Non-existent key returns null without error
            assertNull(cache.getAndRemove<String>(key))

            // Returns the value and removes the key
            cache.set(key, "hello")
            assertEquals("hello", cache.getAndRemove<String>(key))
            assertNull(cache.get<String>(key), "Key must be absent after getAndDelete")

            // Subsequent call on the now-missing key returns null
            assertNull(cache.getAndRemove<String>(key))
        }
    }

    /**
     * `add` must be a true atomic increment: concurrent callers may interleave freely, but every
     * successful `add` must contribute exactly once to the stored counter (no lost updates). This
     * runs across all CAS backends, not just DynamoDB, to pin the contract everywhere.
     *
     * Two scenarios:
     *  1. No TTL — `concurrency` concurrent `add(key, 1)` must leave the counter at exactly
     *     `concurrency`, and the set of returned values must be exactly `1..concurrency` (each
     *     increment observed once).
     *  2. Around the expiry boundary — a TTL is set, the clock is advanced past it so the row is
     *     logically expired, then `concurrency` concurrent `add(key, 1, ttl)` run. Treating the
     *     expired row as absent, the counter must again end at exactly `concurrency` with returned
     *     values exactly `1..concurrency`. This is the case where DynamoDB's old blind-set fallback
     *     dropped concurrent increments.
     */
    @Test
    fun concurrentAddTest() {
        val cache = cache ?: run {
            println("Could not test because the cache is not supported on this system.")
            return
        }
        val concurrency = 50
        runSuspendingTest {
            // Scenario 1: no TTL.
            val key = "concurrent-add-${Uuid.random()}"
            val results = (1..concurrency).map { async { cache.add(key, 1L) } }.awaitAll()
            assertEquals(concurrency.toLong(), cache.get<Long>(key), "concurrent add lost increments")
            assertEquals((1L..concurrency).toSet(), results.toSet(),
                "each concurrent add must observe a unique post-increment value")
            cache.remove(key)
        }
        runSuspendingTest {
            // Scenario 2: around the expiry boundary (expired row must behave as absent).
            val key = "concurrent-add-expiry-${Uuid.random()}"
            cache.add(key, 999L, waitScale)
            delay(waitScale * 1.5) // advance past TTL; row is now logically expired
            assertEquals(null, cache.get<Int>(key), "row should be expired before the concurrent burst")
            val results = (1..concurrency).map { async { cache.add(key, 1L, waitScale) } }.awaitAll()
            assertEquals(concurrency.toLong(), cache.get<Long>(key),
                "expired row must reset then accumulate every concurrent increment exactly once")
            assertEquals((1L..concurrency).toSet(), results.toSet(),
                "each concurrent add against an expired row must observe a unique post-reset value")
            cache.remove(key)
        }
    }
}