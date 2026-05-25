// by Claude
package com.lightningkite.services.cache.redis

import com.lightningkite.services.TestSettingContext
import io.lettuce.core.RedisClient
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import redis.embedded.RedisServer
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies that `RedisCache.eval` transparently recovers from a server-side script cache flush.
 *
 * When Redis loses a previously-loaded Lua script (e.g. after `SCRIPT FLUSH`, a server restart,
 * or memory pressure on the script cache), EVALSHA throws NOSCRIPT. RedisCache catches this,
 * reloads the script, updates its cached SHA, and retries — so callers never observe the failure.
 */
class RedisCasScriptFlushTest {

    private val cache: RedisCache by lazy {
        RedisCache("test-script-flush", RedisClient.create("redis://127.0.0.1:6379/0"), TestSettingContext())
    }

    companion object {
        lateinit var redisServer: RedisServer
        var serverAvailable: Boolean = false

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

    @Test
    fun reloadsScriptAfterFlush() = runBlocking {
        assumeTrue("Redis not available", serverAvailable)
        val key = "redis-cas-script-flush-${System.currentTimeMillis()}"
        val serializer = String.serializer()

        // First CAS: insert script loaded + EVALSHA cached.
        val firstResult = cache.compareAndSet(key, serializer, null, "value1")
        assertTrue(firstResult, "Initial insert CAS should succeed")
        assertEquals("value1", cache.get(key, serializer))

        // Wipe Redis's script cache out from under us; the locally cached SHA is now stale.
        cache.lettuceConnection.scriptFlush().awaitFirst()

        // Second CAS uses a different script (update), so we also need to prime + flush that one.
        // Trigger update script load by issuing a CAS update, then flush again before the real test.
        val warmup = cache.compareAndSet(key, serializer, "value1", "warmup")
        assertTrue(warmup, "Warmup update CAS should succeed")
        cache.lettuceConnection.scriptFlush().awaitFirst()

        // This update CAS must succeed despite the stale SHA — RedisCache.eval should catch
        // NOSCRIPT, reload LUA_CAS_UPDATE, and retry transparently.
        val secondResult = cache.compareAndSet(key, serializer, "warmup", "value2")
        assertTrue(secondResult, "Update CAS should succeed after SCRIPT FLUSH via NOSCRIPT recovery")
        assertEquals("value2", cache.get(key, serializer))
    }
}
