// by Claude
package com.lightningkite.services.cache.memcached

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.cache.get
import com.lightningkite.services.cache.modify
import com.lightningkite.services.cache.set
import com.lightningkite.services.cache.setIfNotExists
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Edge case tests for MemcachedCache implementation.
 * These tests verify behavior not covered by the shared CacheTest suite.
 */
class MemcachedCacheEdgeCaseTest {

    init {
        // Trigger companion object initialization
        MemcachedCache
    }

    private val cache: Cache? by lazy {
        if (EmbeddedMemcached.available) {
            Cache.Settings("memcached-test").invoke("test-edge-cases", TestSettingContext())
        } else {
            null
        }
    }

    companion object {
        private var memcachedProcess: Process? = null

        @JvmStatic
        @BeforeClass
        fun start() {
            if (EmbeddedMemcached.available) {
                try {
                    memcachedProcess = EmbeddedMemcached.start()
                    Thread.sleep(1000)
                } catch (e: Exception) {
                    println("Could not start embedded Memcached: ${e.message}")
                }
            }
        }

        @JvmStatic
        @AfterClass
        fun stop() {
            memcachedProcess?.destroy()
        }
    }

    @Serializable
    data class TestData(val value: String, val count: Int)

    @Test
    fun testCompareAndSetWhenBothExpectedAndNewAreNull() = runBlocking {
        assumeTrue("Memcached not available", cache != null)
        val c = cache!!

        // Both expected and new are null should return true
        val result = c.compareAndSet<TestData>("cas-null-null-test", c.context.internalSerializersModule.serializer(), null, null)
        assertTrue(result, "CAS with both null should succeed")
    }

    @Test
    fun testCompareAndSetWhenNewIsNullDeletesKey() = runBlocking {
        assumeTrue("Memcached not available", cache != null)
        val c = cache!!
        val key = "cas-delete-test-${System.currentTimeMillis()}"

        // Set initial value
        c.set(key, TestData("initial", 1))
        assertEquals(TestData("initial", 1), c.get<TestData>(key))

        // CAS to delete (expected exists, new is null)
        val result = c.compareAndSet(key, c.context.internalSerializersModule.serializer<TestData>(), TestData("initial", 1), null)
        assertTrue(result, "CAS delete should succeed")
        assertNull(c.get<TestData>(key), "Key should be deleted after CAS with null new value")
    }

    @Test
    fun testCompareAndSetWhenExpectedIsNullButKeyExists() = runBlocking {
        assumeTrue("Memcached not available", cache != null)
        val c = cache!!
        val key = "cas-expected-null-exists-test-${System.currentTimeMillis()}"

        // Set initial value
        c.set(key, TestData("exists", 42))

        // Try to CAS with expected=null but key exists - should fail
        val result = c.compareAndSet(key, c.context.internalSerializersModule.serializer<TestData>(), null, TestData("new", 100))
        assertFalse(result, "CAS should fail when expected is null but key exists")

        // Original value should remain
        assertEquals(TestData("exists", 42), c.get<TestData>(key))
    }

    @Test
    fun testCompareAndSetWithCorrectExpectedValueUpdates() = runBlocking {
        assumeTrue("Memcached not available", cache != null)
        val c = cache!!
        val key = "cas-update-test-${System.currentTimeMillis()}"

        // Set initial value
        c.set(key, TestData("v1", 1))

        // CAS with correct expected should succeed
        val result = c.compareAndSet(key, c.context.internalSerializersModule.serializer<TestData>(), TestData("v1", 1), TestData("v2", 2))
        assertTrue(result, "CAS with correct expected should succeed")
        assertEquals(TestData("v2", 2), c.get<TestData>(key))
    }

    @Test
    fun testCompareAndSetWithWrongExpectedValueFails() = runBlocking {
        assumeTrue("Memcached not available", cache != null)
        val c = cache!!
        val key = "cas-wrong-expected-test-${System.currentTimeMillis()}"

        // Set initial value
        c.set(key, TestData("actual", 1))

        // CAS with wrong expected should fail
        val result = c.compareAndSet(key, c.context.internalSerializersModule.serializer<TestData>(), TestData("expected", 2), TestData("new", 3))
        assertFalse(result, "CAS with wrong expected should fail")

        // Original value should remain unchanged
        assertEquals(TestData("actual", 1), c.get<TestData>(key))
    }

    @Test
    fun testCompareAndSetWithTTL() = runBlocking {
        assumeTrue("Memcached not available", cache != null)
        val c = cache!!
        val key = "cas-ttl-test-${System.currentTimeMillis()}"

        // CAS create with TTL (expected null, new value)
        val result = c.compareAndSet(key, c.context.internalSerializersModule.serializer<TestData>(), null, TestData("with-ttl", 1), 2.seconds)
        assertTrue(result, "CAS create with TTL should succeed")
        assertEquals(TestData("with-ttl", 1), c.get<TestData>(key))

        // Wait for expiration
        kotlinx.coroutines.delay(2500)
        assertNull(c.get<TestData>(key), "Value should expire after TTL")
    }

    @Test
    fun testSetIfNotExistsReturnsCorrectly() = runBlocking {
        assumeTrue("Memcached not available", cache != null)
        val c = cache!!
        val key = "set-if-not-exists-test-${System.currentTimeMillis()}"

        // First call should succeed
        val first = c.setIfNotExists(key, TestData("first", 1))
        assertTrue(first, "First setIfNotExists should return true")

        // Second call should fail
        val second = c.setIfNotExists(key, TestData("second", 2))
        assertFalse(second, "Second setIfNotExists should return false")

        // Value should be from first call
        assertEquals(TestData("first", 1), c.get<TestData>(key))
    }

    @Test
    fun testRemoveNonExistentKeySucceeds() = runBlocking {
        assumeTrue("Memcached not available", cache != null)
        val c = cache!!
        val key = "remove-nonexistent-test-${System.currentTimeMillis()}"

        // Should not throw
        c.remove(key)
    }

    @Test
    fun testGetNonExistentKeyReturnsNull() = runBlocking {
        assumeTrue("Memcached not available", cache != null)
        val c = cache!!
        val key = "get-nonexistent-test-${System.currentTimeMillis()}"

        assertNull(c.get<TestData>(key))
    }

    @Test
    fun testModifyReturnsAfterMaxTries() = runBlocking {
        assumeTrue("Memcached not available", cache != null)
        val c = cache!!
        val key = "modify-max-tries-test-${System.currentTimeMillis()}"

        c.set(key, TestData("initial", 1))

        // Modify with maxTries=1 should try once
        val result = c.modify<TestData>(key, maxTries = 1) { current ->
            current?.copy(count = current.count + 1)
        }
        assertTrue(result, "Modify should succeed")
        assertEquals(TestData("initial", 2), c.get<TestData>(key))
    }

    @Test
    fun testAddWithNonExistentKey() = runBlocking {
        assumeTrue("Memcached not available", cache != null)
        val c = cache!!
        val key = "add-new-key-test-${System.currentTimeMillis()}"

        // Remove to ensure key doesn't exist
        c.remove(key)

        // Add to non-existent key should create it
        c.add(key, 5)
        assertEquals(5, c.get<Int>(key))
    }

    @Test
    fun testAddWithExistingKey() = runBlocking {
        assumeTrue("Memcached not available", cache != null)
        val c = cache!!
        val key = "add-existing-key-test-${System.currentTimeMillis()}"

        c.add(key, 10)
        c.add(key, 5)
        assertEquals(15, c.get<Int>(key))
    }

    @Test
    fun testJsonProperty() {
        assumeTrue("Memcached not available", cache != null)
        val c = cache as MemcachedCache

        // Verify json is accessible and properly configured
        val encoded = c.json.encodeToString(TestData.serializer(), TestData("test", 42))
        assertTrue(encoded.contains("test"))
        assertTrue(encoded.contains("42"))
    }
}
