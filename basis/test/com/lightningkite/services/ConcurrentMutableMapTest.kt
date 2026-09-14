package com.lightningkite.services

import kotlin.test.*

/**
 * Common multiplatform tests for [ConcurrentMutableMap].
 *
 * These verify the [MutableMap] contract plus the atomic [ConcurrentMutableMap.compute] and
 * [ConcurrentMutableMap.computeIfAbsent] operations on every platform. JVM-specific concurrent-thread
 * tests live in jvmTest.
 */
class ConcurrentMutableMapTest {

    @Test
    fun testBasicOperations() {
        val map = ConcurrentMutableMap<String, Int>()

        map["key1"] = 1
        map["key2"] = 2

        assertEquals(1, map["key1"])
        assertEquals(2, map["key2"])
        assertEquals(2, map.size)
    }

    @Test
    fun testRemove() {
        val map = ConcurrentMutableMap<String, String>()

        map["key1"] = "value1"
        map["key2"] = "value2"

        val removed = map.remove("key1")

        assertEquals("value1", removed)
        assertNull(map["key1"])
        assertEquals(1, map.size)
    }

    @Test
    fun testClear() {
        val map = ConcurrentMutableMap<String, Int>()

        map["key1"] = 1
        map["key2"] = 2
        map.clear()

        assertTrue(map.isEmpty())
        assertEquals(0, map.size)
    }

    @Test
    fun testContainsKey() {
        val map = ConcurrentMutableMap<String, Int>()

        map["exists"] = 42

        assertTrue(map.containsKey("exists"))
        assertFalse(map.containsKey("missing"))
    }

    @Test
    fun testPutAll() {
        val map = ConcurrentMutableMap<String, Int>()
        val sourceMap = mapOf("key1" to 1, "key2" to 2, "key3" to 3)

        map.putAll(sourceMap)

        assertEquals(3, map.size)
        assertEquals(1, map["key1"])
        assertEquals(2, map["key2"])
        assertEquals(3, map["key3"])
    }

    @Test
    fun testKeys() {
        val map = ConcurrentMutableMap<String, Int>()

        map["key1"] = 1
        map["key2"] = 2

        val keys = map.keys

        assertTrue(keys.contains("key1"))
        assertTrue(keys.contains("key2"))
        assertEquals(2, keys.size)
    }

    @Test
    fun testValues() {
        val map = ConcurrentMutableMap<String, Int>()

        map["key1"] = 1
        map["key2"] = 2

        val values = map.values

        assertTrue(values.contains(1))
        assertTrue(values.contains(2))
        assertEquals(2, values.size)
    }

    @Test
    fun testEntries() {
        val map = ConcurrentMutableMap<String, Int>()

        map["key1"] = 1
        map["key2"] = 2

        val entries = map.entries

        assertEquals(2, entries.size)
        assertTrue(entries.any { it.key == "key1" && it.value == 1 })
        assertTrue(entries.any { it.key == "key2" && it.value == 2 })
    }

    @Test
    fun testOverwriteExistingValue() {
        val map = ConcurrentMutableMap<String, String>()

        map["key"] = "value1"
        map["key"] = "value2"

        assertEquals("value2", map["key"])
        assertEquals(1, map.size)
    }

    // --- compute ---

    @Test
    fun testComputeOnAbsentKey() {
        val map = ConcurrentMutableMap<String, Int>()

        val result = map.compute("new") { key, existing ->
            assertEquals("new", key)
            assertNull(existing)
            10
        }

        assertEquals(10, result)
        assertEquals(10, map["new"])
    }

    @Test
    fun testComputeOnExistingKey() {
        val map = ConcurrentMutableMap<String, Int>()
        map["counter"] = 5

        val result = map.compute("counter") { _, existing ->
            assertEquals(5, existing)
            (existing ?: 0) + 1
        }

        assertEquals(6, result)
        assertEquals(6, map["counter"])
    }

    @Test
    fun testComputeReturningNullRemovesEntry() {
        val map = ConcurrentMutableMap<String, Int>()
        map["doomed"] = 99

        val result = map.compute("doomed") { _, _ -> null }

        assertNull(result)
        assertFalse(map.containsKey("doomed"))
        assertEquals(0, map.size)
    }

    @Test
    fun testComputeReturningNullForAbsentKeyIsNoOp() {
        val map = ConcurrentMutableMap<String, Int>()

        val result = map.compute("never") { _, existing ->
            assertNull(existing)
            null
        }

        assertNull(result)
        assertFalse(map.containsKey("never"))
        assertEquals(0, map.size)
    }

    // --- computeIfAbsent ---

    @Test
    fun testComputeIfAbsentOnAbsentKey() {
        val map = ConcurrentMutableMap<String, Int>()
        var calls = 0

        val result = map.computeIfAbsent("k") { calls++; 7 }

        assertEquals(7, result)
        assertEquals(1, calls)
        assertEquals(7, map["k"])
    }

    @Test
    fun testComputeIfAbsentSkipsWhenPresent() {
        val map = ConcurrentMutableMap<String, Int>()
        map["k"] = 42
        var calls = 0

        val result = map.computeIfAbsent("k") { calls++; 7 }

        assertEquals(42, result)
        assertEquals(0, calls)
        assertEquals(42, map["k"])
    }
}
