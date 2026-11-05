package com.lightningkite.services.cache

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.test.runTestWithClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Every operation on an [CacheKey.OfInstant] must store epoch milliseconds, so [Cache.add] keeps working on it. */
class CacheKeyTest {
    private val cache = Cache.Settings("ram").invoke("test", TestSettingContext())
    private val at = Instant.fromEpochSeconds(100)

    @Test
    fun instantKeyStoresMillisecondsThroughEveryOperation() = runTestWithClock {
        val key = CacheKey.OfInstant("instant")

        assertTrue(cache.setIfNotExists(key, at))
        assertFalse(cache.setIfNotExists(key, at + 1.seconds))
        assertEquals(at, cache.get(key))
        assertEquals(at + 5.seconds, cache.add(key, 5.seconds))
        assertEquals(at.toEpochMilliseconds() + 5_000, cache.get<Long>("instant"))

        assertTrue(cache.compareAndSet(key, at + 5.seconds, at + 10.seconds))
        assertFalse(cache.compareAndSet(key, at, at + 20.seconds))
        assertTrue(cache.modify(key) { it!! + 1.seconds })
        assertEquals(at + 11.seconds, cache.add(key, 0.seconds))

        assertEquals(at + 11.seconds, cache.getAndRemove(key))
        assertNull(cache.get(key))
    }

    @Test
    fun countersAddAndRemove() = runTestWithClock {
        val longs = CacheKey.OfLong("longs")
        val ints = CacheKey.OfInt("ints")

        assertEquals(3L, cache.add(longs, 3L))
        assertEquals(5L, cache.add(longs, 2L))
        assertEquals(-1, cache.add(ints, -1))
        cache.remove(longs)
        assertNull(cache.get(longs))
        assertEquals(-1, cache.get(ints))
    }

    @Test
    fun serializedKeyRoundTrips() = runTestWithClock {
        val key = CacheKey<List<String>>("list")
        cache.set(key, listOf("a", "b"))
        assertTrue(cache.modify(key) { it.orEmpty() + "c" })
        assertEquals(listOf("a", "b", "c"), cache.get(key))
    }
}
