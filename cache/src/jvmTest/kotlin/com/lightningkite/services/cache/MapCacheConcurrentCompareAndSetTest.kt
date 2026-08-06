package com.lightningkite.services.cache

import com.lightningkite.services.TestSettingContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies that [MapCache.compareAndSet] is genuinely atomic under real concurrency — before this
 * fix, `MapCache` had no `compareAndSet` override at all, so it silently fell back to the interface's
 * non-atomic default (separate `get()` then `set()`), which would let every racer here "win".
 *
 * Uses [Dispatchers.Default] (not virtual time) so multiple OS threads actually contend on the same key.
 */
class MapCacheConcurrentCompareAndSetTest {

    @Test
    fun onlyOneRacerWinsAnUnsetKey() = runBlocking(Dispatchers.Default) {
        val cache = Cache.Settings("ram").invoke("test", TestSettingContext())
        val key = "init-lock"
        val workers = 100

        val results = withContext(Dispatchers.Default) {
            (1..workers).map { id ->
                async {
                    cache.compareAndSet(key, Int.serializer(), expected = null, new = id)
                }
            }.awaitAll()
        }

        assertEquals(1, results.count { it }, "Exactly one compareAndSet on an unset key must win")
    }

    @Test
    fun concurrentCompareAndSetNeverLosesAnUpdate() = runBlocking(Dispatchers.Default) {
        val cache = Cache.Settings("ram").invoke("test", TestSettingContext())
        val key = "counter"
        val workers = 100

        cache.set(key, 0, Int.serializer())

        // Each worker retries its own compareAndSet against the latest observed value until it wins,
        // so every one of them contributes exactly one increment — same shape as the modify() CAS test.
        val results = withContext(Dispatchers.Default) {
            (1..workers).map {
                async {
                    var won = false
                    while (!won) {
                        val current = cache.get(key, Int.serializer())
                        won = cache.compareAndSet(key, Int.serializer(), expected = current, new = (current ?: 0) + 1)
                    }
                    won
                }
            }.awaitAll()
        }

        assertTrue(results.all { it })
        assertEquals(workers, cache.get(key, Int.serializer()), "No increments should be lost")
    }
}
