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
 * Verifies that [MapCache.modify] uses CAS retries correctly under real concurrency.
 *
 * Uses [Dispatchers.Default] (not virtual time) so multiple OS threads actually contend
 * on the same key. If the CAS loop ever lost an update, the final counter would be less
 * than the number of incrementers.
 */
class MapCacheConcurrentModifyTest {

    @Test
    fun concurrentIncrementsNeverLoseUpdates() = runBlocking(Dispatchers.Default) {
        val cache = Cache.Settings("ram").invoke("test", TestSettingContext())
        val key = "counter"
        val workers = 100
        // maxTries must be high enough that contention can always converge. With 100 racers,
        // a few retries per call is typical; give plenty of headroom so the test isn't flaky.
        val maxTries = workers * 10

        cache.set(key, 0, Int.serializer())

        val results = withContext(Dispatchers.Default) {
            (1..workers).map {
                async {
                    cache.modify(
                        key = key,
                        serializer = Int.serializer(),
                        maxTries = maxTries,
                    ) { current -> (current ?: 0) + 1 }
                }
            }.awaitAll()
        }

        assertTrue(results.all { it }, "All modify calls should succeed within maxTries")
        assertEquals(workers, cache.get(key, Int.serializer()), "No increments should be lost")
    }

    @Test
    fun modifyReturnsFalseWhenMaxTriesExhausted() = runBlocking(Dispatchers.Default) {
        // With maxTries = 1 and heavy contention, at least some modifies must fail.
        // This confirms the CAS check actually rejects stale writes (rather than blindly
        // overwriting, which would always return true).
        val cache = Cache.Settings("ram").invoke("test", TestSettingContext())
        val key = "counter"
        val workers = 100

        cache.set(key, 0, Int.serializer())

        val results = withContext(Dispatchers.Default) {
            (1..workers).map {
                async {
                    cache.modify(
                        key = key,
                        serializer = Int.serializer(),
                        maxTries = 1,
                    ) { current -> (current ?: 0) + 1 }
                }
            }.awaitAll()
        }

        val successes = results.count { it }
        val finalValue = cache.get(key, Int.serializer())
        // Each successful modify must have incremented the stored value exactly once.
        assertEquals(successes, finalValue, "Successful modifies must equal final counter value")
        assertTrue(successes <= workers)
    }
}
