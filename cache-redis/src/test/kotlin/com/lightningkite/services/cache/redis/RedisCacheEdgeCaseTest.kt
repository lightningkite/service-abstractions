// by Claude
package com.lightningkite.services.cache.redis

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.cache.get
import com.lightningkite.services.cache.modify
import com.lightningkite.services.cache.set
import com.lightningkite.services.cache.setIfNotExists
import io.lettuce.core.RedisClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import redis.embedded.RedisServer
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Edge case tests for RedisCache implementation.
 * These tests verify behavior not covered by the shared CacheTest suite.
 */
class RedisCacheEdgeCaseTest {

    private val cache: Cache by lazy {
        RedisCache("test-edge-cases", RedisClient.create("redis://127.0.0.1:6379/0"), TestSettingContext())
    }

    companion object {
        lateinit var redisServer: RedisServer
        var serverAvailable = false

        @JvmStatic
        @BeforeClass
        fun start() {
            try {
                redisServer = RedisServer.builder()
                    .port(6379)
                    .setting("bind 127.0.0.1")
                    .slaveOf("localhost", 6379)
                    .setting("daemonize no")
                    .setting("appendonly no")
                    .setting("replica-read-only no")
                    .setting("maxmemory 128M")
                    .build()
                redisServer.start()
                serverAvailable = true
            } catch (e: Exception) {
                println("Could not start embedded Redis: ${e.message}")
            }
        }

        @JvmStatic
        @AfterClass
        fun stop() {
            if (serverAvailable) {
                redisServer.stop()
            }
        }
    }

    @Serializable
    data class TestData(val value: String, val count: Int)

    @Test
    fun testCompareAndSetWhenBothExpectedAndNewAreNull() = runBlocking {
        assumeTrue("Redis not available", serverAvailable)

        // Both expected and new are null should return true
        val result = cache.compareAndSet<TestData>("redis-cas-null-null-test", cache.context.internalSerializersModule.serializer(), null, null)
        assertTrue(result, "CAS with both null should succeed")
    }

    @Test
    fun testCompareAndSetWhenNewIsNullDeletesKey() = runBlocking {
        assumeTrue("Redis not available", serverAvailable)
        val key = "redis-cas-delete-test-${System.currentTimeMillis()}"

        // Set initial value
        cache.set(key, TestData("initial", 1))
        assertEquals(TestData("initial", 1), cache.get<TestData>(key))

        // CAS to delete (expected exists, new is null)
        val result = cache.compareAndSet(key, cache.context.internalSerializersModule.serializer<TestData>(), TestData("initial", 1), null)
        assertTrue(result, "CAS delete should succeed")
        assertNull(cache.get<TestData>(key), "Key should be deleted after CAS with null new value")
    }

    @Test
    fun testCompareAndSetWhenExpectedIsNullButKeyExists() = runBlocking {
        assumeTrue("Redis not available", serverAvailable)
        val key = "redis-cas-expected-null-exists-test-${System.currentTimeMillis()}"

        // Set initial value
        cache.set(key, TestData("exists", 42))

        // Try to CAS with expected=null but key exists - should fail
        val result = cache.compareAndSet(key, cache.context.internalSerializersModule.serializer<TestData>(), null, TestData("new", 100))
        assertFalse(result, "CAS should fail when expected is null but key exists")

        // Original value should remain
        assertEquals(TestData("exists", 42), cache.get<TestData>(key))
    }

    @Test
    fun testCompareAndSetWithCorrectExpectedValueUpdates() = runBlocking {
        assumeTrue("Redis not available", serverAvailable)
        val key = "redis-cas-update-test-${System.currentTimeMillis()}"

        // Set initial value
        cache.set(key, TestData("v1", 1))

        // CAS with correct expected should succeed
        val result = cache.compareAndSet(key, cache.context.internalSerializersModule.serializer<TestData>(), TestData("v1", 1), TestData("v2", 2))
        assertTrue(result, "CAS with correct expected should succeed")
        assertEquals(TestData("v2", 2), cache.get<TestData>(key))
    }

    @Test
    fun testCompareAndSetWithWrongExpectedValueFails() = runBlocking {
        assumeTrue("Redis not available", serverAvailable)
        val key = "redis-cas-wrong-expected-test-${System.currentTimeMillis()}"

        // Set initial value
        cache.set(key, TestData("actual", 1))

        // CAS with wrong expected should fail
        val result = cache.compareAndSet(key, cache.context.internalSerializersModule.serializer<TestData>(), TestData("expected", 2), TestData("new", 3))
        assertFalse(result, "CAS with wrong expected should fail")

        // Original value should remain unchanged
        assertEquals(TestData("actual", 1), cache.get<TestData>(key))
    }

    @Test
    fun testCompareAndSetWithTTL() = runBlocking {
        assumeTrue("Redis not available", serverAvailable)
        val key = "redis-cas-ttl-test-${System.currentTimeMillis()}"

        // CAS create with TTL (expected null, new value)
        val result = cache.compareAndSet(key, cache.context.internalSerializersModule.serializer<TestData>(), null, TestData("with-ttl", 1), 2.seconds)
        assertTrue(result, "CAS create with TTL should succeed")
        assertEquals(TestData("with-ttl", 1), cache.get<TestData>(key))

        // Wait for expiration
        kotlinx.coroutines.delay(2500)
        assertNull(cache.get<TestData>(key), "Value should expire after TTL")
    }

    @Test
    fun testSetIfNotExistsReturnsCorrectly() = runBlocking {
        assumeTrue("Redis not available", serverAvailable)
        val key = "redis-set-if-not-exists-test-${System.currentTimeMillis()}"

        // First call should succeed
        val first = cache.setIfNotExists(key, TestData("first", 1))
        assertTrue(first, "First setIfNotExists should return true")

        // Second call should fail
        val second = cache.setIfNotExists(key, TestData("second", 2))
        assertFalse(second, "Second setIfNotExists should return false")

        // Value should be from first call
        assertEquals(TestData("first", 1), cache.get<TestData>(key))
    }

    @Test
    fun testRemoveNonExistentKeySucceeds() = runBlocking {
        assumeTrue("Redis not available", serverAvailable)
        val key = "redis-remove-nonexistent-test-${System.currentTimeMillis()}"

        // Should not throw
        cache.remove(key)
    }

    @Test
    fun testGetNonExistentKeyReturnsNull() = runBlocking {
        assumeTrue("Redis not available", serverAvailable)
        val key = "redis-get-nonexistent-test-${System.currentTimeMillis()}"

        assertNull(cache.get<TestData>(key))
    }

    @Test
    fun testModifyReturnsAfterMaxTries() = runBlocking {
        assumeTrue("Redis not available", serverAvailable)
        val key = "redis-modify-max-tries-test-${System.currentTimeMillis()}"

        cache.set(key, TestData("initial", 1))

        // Modify with maxTries=1 should try once
        val result = cache.modify<TestData>(key, maxTries = 1) { current ->
            current?.copy(count = current.count + 1)
        }
        assertTrue(result, "Modify should succeed")
        assertEquals(TestData("initial", 2), cache.get<TestData>(key))
    }

    @Test
    fun testAddWithNonExistentKey() = runBlocking {
        assumeTrue("Redis not available", serverAvailable)
        val key = "redis-add-new-key-test-${System.currentTimeMillis()}"

        // Remove to ensure key doesn't exist
        cache.remove(key)

        // Add to non-existent key should create it
        cache.add(key, 5)
        assertEquals(5, cache.get<Int>(key))
    }

    @Test
    fun testAddWithExistingKey() = runBlocking {
        assumeTrue("Redis not available", serverAvailable)
        val key = "redis-add-existing-key-test-${System.currentTimeMillis()}"

        cache.add(key, 10)
        cache.add(key, 5)
        assertEquals(15, cache.get<Int>(key))
    }

    @Test
    fun testAddWithNegativeValue() = runBlocking {
        assumeTrue("Redis not available", serverAvailable)
        val key = "redis-add-negative-test-${System.currentTimeMillis()}"

        cache.add(key, 10)
        cache.add(key, -3)
        assertEquals(7, cache.get<Int>(key))
    }

    @Test
    fun testJsonProperty() {
        assumeTrue("Redis not available", serverAvailable)
        val c = cache as RedisCache

        // Verify json is accessible and properly configured
        val encoded = c.json.encodeToString(TestData.serializer(), TestData("test", 42))
        assertTrue(encoded.contains("test"))
        assertTrue(encoded.contains("42"))
    }
}
