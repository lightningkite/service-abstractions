package com.lightningkite.services.pubsub.redis

import com.lightningkite.services.TestSettingContext
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.serializer
import org.junit.AfterClass
import org.junit.BeforeClass
import redis.embedded.RedisServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RedisPubSubTest {

    companion object {
        private const val PORT = 16379
        lateinit var server: RedisServer
        lateinit var client: RedisClient
        lateinit var admin: StatefulRedisConnection<String, String>

        @JvmStatic
        @BeforeClass
        fun startUp() {
            server = RedisServer.builder()
                .port(PORT)
                .setting("bind 127.0.0.1")
                .setting("daemonize no")
                .setting("appendonly no")
                .setting("maxmemory 64M")
                .build()
            server.start()
            client = RedisClient.create("redis://127.0.0.1:$PORT")
            admin = client.connect()
        }

        @JvmStatic
        @AfterClass
        fun shutDown() {
            admin.close()
            client.shutdown()
            server.stop()
        }

        fun connectedClients(): Int {
            val info = admin.sync().info("clients")
            return info.lineSequence()
                .first { it.startsWith("connected_clients:") }
                .substringAfter(":")
                .trim()
                .toInt()
        }
    }

    @Test
    fun stringRoundTrip() = runBlocking {
        val pubsub = RedisPubSub("test", TestSettingContext(), client)
        val channel = pubsub.string("round-trip-${System.nanoTime()}")
        val received = CompletableDeferred<String>()
        val job = launch {
            channel.collect { msg ->
                if (!received.isCompleted) received.complete(msg)
            }
        }
        // Wait for subscription to register with Redis before publishing.
        delay(300)
        channel.emit("hello")
        assertEquals("hello", withTimeout(2000) { received.await() })
        job.cancel()
    }

    @Test
    fun typedRoundTrip() = runBlocking {
        val pubsub = RedisPubSub("test", TestSettingContext(), client)
        val channel = pubsub.get("typed-${System.nanoTime()}", Int.serializer())
        val received = CompletableDeferred<Int>()
        val job = launch {
            channel.collect { v ->
                if (!received.isCompleted) received.complete(v)
            }
        }
        delay(300)
        channel.emit(42)
        assertEquals(42, withTimeout(2000) { received.await() })
        job.cancel()
    }

    /**
     * Regression test: prior to the shared-connection fix, every [emit] opened a
     * fresh pub/sub connection (TCP + TLS handshake under `rediss://`). Under load
     * this pegged Netty event loops in `Net.connect0`. Confirm one [RedisPubSub]
     * instance opens at most one connection for publishing regardless of emit count.
     */
    @Test
    fun emitReusesSingleConnection() = runBlocking {
        val pubsub = RedisPubSub("test", TestSettingContext(), client)
        val channel = pubsub.string("reuse-${System.nanoTime()}")

        // First emit lazily opens the shared publish connection.
        channel.emit("warmup")
        delay(100)
        val before = connectedClients()

        repeat(200) { channel.emit("burst-$it") }
        delay(200)
        val after = connectedClients()

        val delta = after - before
        // Old behavior: ~200 new connections (or churn as closes lagged).
        // New behavior: 0. Allow tiny variance for any housekeeping.
        assertTrue(
            delta <= 2,
            "emit should reuse the shared publish connection; observed +$delta connections after 200 emits (before=$before, after=$after)"
        )
    }
}
