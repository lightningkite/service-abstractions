package com.lightningkite.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * Tests for ConcurrentMutableMap functionality.
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
    fun testGetOrPut() {
        val map = ConcurrentMutableMap<String, String>()

        val value1 = map.getOrPut("key1") { "value1" }
        val value2 = map.getOrPut("key1") { "should not be called" }

        assertEquals("value1", value1)
        assertEquals("value1", value2)
        assertEquals(1, map.size)
    }

    @Test
    fun testConcurrentWrites() = runTest {
        val map = ConcurrentMutableMap<String, Int>()
        val iterations = 1000

        // Launch multiple coroutines that write to the map concurrently
        val jobs = (1..10).map { threadId ->
            async(Dispatchers.Default) {
                repeat(iterations) { i ->
                    map["thread-$threadId-$i"] = i
                }
            }
        }

        jobs.awaitAll()

        // All writes should have succeeded
        assertEquals(10 * iterations, map.size)
    }

    @Test
    fun testConcurrentGetOrPut() = runTest {
        val map = ConcurrentMutableMap<String, Int>()
        val key = "shared-key"
        var constructorCalls = 0

        // Note: This test may be flaky due to the race condition mentioned in SharedResources
        // On JVM with ConcurrentHashMap, getOrPut should be thread-safe and only call the
        // constructor once, but the behavior isn't strictly guaranteed
        val jobs = (1..10).map {
            async(Dispatchers.Default) {
                map.getOrPut(key) {
                    synchronized(this@ConcurrentMutableMapTest) {
                        constructorCalls++
                    }
                    42
                }
            }
        }

        jobs.awaitAll()

        assertEquals(42, map[key])
        // Note: constructorCalls might be > 1 due to concurrent getOrPut calls
        // This is expected behavior and documented in SharedResources
        assertTrue(constructorCalls >= 1, "Constructor should be called at least once")
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
}
