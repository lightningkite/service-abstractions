package com.lightningkite.services.cache.redis

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.cache.test.CacheTest
import io.lettuce.core.RedisClient
import kotlinx.coroutines.*
import org.junit.AfterClass
import org.junit.BeforeClass
import redis.embedded.RedisServer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class RedisTest: CacheTest() {
    override val cache: Cache? by lazy {
        RedisCache(RedisClient.create("redis://127.0.0.1:6379/0"), TestSettingContext())
    }
    override fun runSuspendingTest(body: suspend CoroutineScope.() -> Unit) = runBlocking { body() }
    override val waitScale: Duration
        get() = 0.25.seconds

    companion object {
        lateinit var redisServer: RedisServer
        @JvmStatic
        @BeforeClass
        fun start() {
            redisServer = RedisServer.builder()
                .port(6379)
                .setting("bind 127.0.0.1") // good for local development on Windows to prevent security popups
                .slaveOf("localhost", 6379)
                .setting("daemonize no")
                .setting("appendonly no")
                .setting("replica-read-only no")
                .setting("maxmemory 128M")
                .build()
            redisServer.start()
        }
        @JvmStatic
        @AfterClass
        fun stop() {
            redisServer.stop()
        }
    }

}