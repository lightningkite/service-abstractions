package com.lightningkite.services.cache

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.cache.test.CacheTest
import com.lightningkite.services.test.runTestWithClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/**
 * Runs the full shared conformance suite against a [PrefixCache] wrapping a [MapCache] — this
 * combination had no test coverage anywhere before (see cache cluster release-review LOW finding),
 * which is how the missing `compareAndSet` override went unnoticed.
 */
class PrefixCacheTest : CacheTest() {
    override val cache: Cache = PrefixCache(Cache.Settings("ram").invoke("test", TestSettingContext()), "prefix:")
}

/**
 * Two [PrefixCache] instances sharing one backing cache must not see each other's keys.
 */
class PrefixCacheIsolationTest {
    @Test
    fun differentPrefixesDoNotCollide() = runTestWithClock {
        val backing = Cache.Settings("ram").invoke("test", TestSettingContext())
        val a = PrefixCache(backing, "a:")
        val b = PrefixCache(backing, "b:")
        val key = "shared-key-${Uuid.random()}"

        a.set(key, 1)
        assertNull(b.get<Int>(key), "b must not see a's value under the same logical key")

        b.set(key, 2)
        assertEquals(1, a.get<Int>(key), "a's value must be unaffected by b's write")
        assertEquals(2, b.get<Int>(key))

        a.remove(key)
        assertNull(a.get<Int>(key))
        assertEquals(2, b.get<Int>(key), "removing a's key must not affect b's")
    }
}
