package com.lightningkite.services.cache.redis

import com.lightningkite.services.TestSettingContext
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import redis.embedded.RedisServer
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Pins the Lua-script TTL fix in RedisCache against real embedded Redis.
 *
 * Background: prior to the 1.0.0 fix, the Lua scripts used `if ARGV[2] then` to
 * detect "no TTL", but in Lua both `0` and `""` are truthy — only `nil` is falsy.
 * Combined with passing `""` from Kotlin for "no TTL", the script would call
 * `PSETEX` with a malformed TTL argument. The fix:
 *   - Kotlin sends `""` as the sentinel for "no TTL" (already did).
 *   - Lua now checks `if ARGV[2] ~= ''` (or `ARGV[3]` for UPDATE).
 *
 * These tests verify the result via direct TTL inspection on the real key, so
 * any regression of the Lua sentinel logic will be caught — `MapCache`-style
 * semantic tests would not.
 */
class RedisCasTtlFixTest {

    private val context = TestSettingContext()
    private val client: RedisClient by lazy { RedisClient.create("redis://127.0.0.1:6379/0") }
    private val cache: RedisCache by lazy { RedisCache("test-cas-ttl", client, context) }
    private val syncConnection: StatefulRedisConnection<String, String> by lazy { client.connect() }

    @Serializable
    data class Box(val v: String)

    private val serializer = serializer<Box>()

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

    private fun ttl(key: String): Long = syncConnection.sync().ttl(key)

    @Test
    fun casInsertWithoutTtlHasNoExpiry() = runBlocking {
        assumeTrue("Redis not available", serverAvailable)
        val key = "cas-insert-no-ttl-${System.currentTimeMillis()}"
        syncConnection.sync().del(key)

        val ok = cache.compareAndSet(key, serializer, expected = null, new = Box("inserted"), timeToLive = null)
        assertTrue(ok, "CAS insert should succeed")

        // -1 = no expiry, -2 = key doesn't exist; the bug would have produced a positive (or error).
        assertEquals(-1L, ttl(key), "Key inserted without TTL must have no expiry")
        assertEquals(Box("inserted"), cache.get(key, serializer))
    }

    @Test
    fun casInsertWithTtlHasPositiveExpiry() = runBlocking {
        assumeTrue("Redis not available", serverAvailable)
        val key = "cas-insert-ttl-${System.currentTimeMillis()}"
        syncConnection.sync().del(key)

        val ok = cache.compareAndSet(key, serializer, expected = null, new = Box("with-ttl"), timeToLive = 60.seconds)
        assertTrue(ok, "CAS insert with TTL should succeed")

        val t = ttl(key)
        assertTrue(t in 1..60, "Key with 60s TTL should report 1..60 seconds, got $t")
        assertEquals(Box("with-ttl"), cache.get(key, serializer))
    }

    @Test
    fun casUpdateWithoutTtlHasNoExpiry() = runBlocking {
        assumeTrue("Redis not available", serverAvailable)
        val key = "cas-update-no-ttl-${System.currentTimeMillis()}"
        syncConnection.sync().del(key)

        // Seed with an existing value that has no TTL.
        cache.set(key, Box("v1"), serializer, timeToLive = null)
        assertEquals(-1L, ttl(key), "Sanity: seeded key should have no TTL")

        val ok = cache.compareAndSet(key, serializer, expected = Box("v1"), new = Box("v2"), timeToLive = null)
        assertTrue(ok, "CAS update should succeed")

        assertEquals(-1L, ttl(key), "Updated key without TTL must remain without expiry")
        assertEquals(Box("v2"), cache.get(key, serializer))
    }

    @Test
    fun casUpdateWithTtlHasPositiveExpiry() = runBlocking {
        assumeTrue("Redis not available", serverAvailable)
        val key = "cas-update-ttl-${System.currentTimeMillis()}"
        syncConnection.sync().del(key)

        cache.set(key, Box("v1"), serializer, timeToLive = null)

        val ok = cache.compareAndSet(key, serializer, expected = Box("v1"), new = Box("v2"), timeToLive = 60.seconds)
        assertTrue(ok, "CAS update with TTL should succeed")

        val t = ttl(key)
        assertTrue(t in 1..60, "Updated key with 60s TTL should report 1..60 seconds, got $t")
        assertEquals(Box("v2"), cache.get(key, serializer))
    }

    @Test
    fun casDeleteRemovesKey() = runBlocking {
        assumeTrue("Redis not available", serverAvailable)
        val key = "cas-delete-${System.currentTimeMillis()}"
        syncConnection.sync().del(key)

        cache.set(key, Box("v1"), serializer, timeToLive = null)

        val ok = cache.compareAndSet(key, serializer, expected = Box("v1"), new = null, timeToLive = null)
        assertTrue(ok, "CAS delete should succeed")

        assertEquals(-2L, ttl(key), "Deleted key must not exist (TTL == -2)")
        assertNull(cache.get(key, serializer))
    }
}
