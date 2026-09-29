package com.lightningkite.services.database.internal

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

abstract class ConcurrentMapTest {
    internal abstract fun <K : Any, V : Any> create(): ConcurrentMap<K, V>

    @Test
    fun basicOperations() {
        val map = create<String, Int>()
        assertTrue(map.isEmpty())
        assertNull(map.put("a", 1))
        assertEquals(1, map.put("a", 2))
        map.putAll(mapOf("b" to 3, "c" to 4))
        assertEquals(mapOf("a" to 2, "b" to 3, "c" to 4), map.toMap())
        assertTrue(map.containsKey("b"))
        assertTrue(map.containsValue(4))
        assertEquals(3, map.remove("b"))
        assertFalse(map.containsKey("b"))
        assertEquals(setOf("a", "c"), map.keys.toSet())
        map.clear()
        assertTrue(map.isEmpty())
    }

    @Test
    fun putIfAbsentOnlyStoresFirstValue() {
        val map = create<String, String>()
        assertNull(map.putIfAbsent("k", "first"))
        assertEquals("first", map.putIfAbsent("k", "second"))
        assertEquals("first", map["k"])
    }

    @Test
    fun getOrPutConcurrentKeepsExistingValue() {
        val map = create<String, String>()
        assertEquals("first", map.getOrPutConcurrent("k") { "first" })
        var called = false
        assertEquals("first", map.getOrPutConcurrent("k") { called = true; "second" })
        assertFalse(called)
    }

    @Test
    fun getOrPutConcurrentMayReenterTheMap() {
        val map = create<String, String>()
        val outer = map.getOrPutConcurrent("outer") { map.getOrPutConcurrent("inner") { "i" } + "o" }
        assertEquals("io", outer)
        assertEquals("i", map["inner"])
    }

    @Test
    fun entryViewWritesThrough() {
        val map = create<String, Int>()
        map.putAll(mapOf("a" to 1, "b" to 2, "c" to 3))
        for (entry in map.entries) if (entry.key == "a") entry.setValue(10)
        val iterator = map.entries.iterator()
        while (iterator.hasNext()) if (iterator.next().key == "b") iterator.remove()
        assertEquals(mapOf("a" to 10, "c" to 3), map.toMap())
    }

    @Test
    fun concurrentGetOrPutAgreesOnOneValue() {
        val map = create<Int, Any>()
        val threads = 8
        val keys = 500
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        try {
            val results = List(threads) {
                pool.submit<List<Any>> {
                    start.await()
                    List(keys) { key -> map.getOrPutConcurrent(key) { Any() } }
                }
            }
            start.countDown()
            val seen = results.map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(keys, map.size)
            for (key in 0 until keys) {
                for (perThread in seen) assertSame(map[key], perThread[key])
            }
        } finally {
            pool.shutdownNow()
        }
    }
}

class JvmConcurrentMapTest : ConcurrentMapTest() {
    internal override fun <K : Any, V : Any> create(): ConcurrentMap<K, V> = ConcurrentMap()
}
