package com.lightningkite.serviceabstractions.cache.redis

import com.lightningkite.serviceabstractions.MetricSink
import com.lightningkite.serviceabstractions.SettingContext
import com.lightningkite.serviceabstractions.TestSettingContext
import com.lightningkite.serviceabstractions.cache.Cache
import com.lightningkite.serviceabstractions.cache.test.CacheTest
import io.lettuce.core.RedisClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.take
import kotlinx.serialization.modules.SerializersModule
import org.junit.AfterClass
import org.junit.BeforeClass
import redis.embedded.RedisExecProvider
import redis.embedded.RedisServer
import redis.embedded.util.Architecture
import redis.embedded.util.OS
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