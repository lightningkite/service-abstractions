package com.lightningkite.serviceabstractions.cache.memcached

import com.lightningkite.serviceabstractions.MetricSink
import com.lightningkite.serviceabstractions.SettingContext
import com.lightningkite.serviceabstractions.cache.Cache
import com.lightningkite.serviceabstractions.cache.test.CacheTest
import com.lightningkite.serviceabstractions.test.runTestWithClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.modules.SerializersModule
import net.rubyeye.xmemcached.XMemcachedClient
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Ignore
import org.junit.Test
import java.lang.Exception
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class MemcachedTest : CacheTest() {
    override val cache: Cache? by lazy {
        if (EmbeddedMemcached.available) {
            try {
                val client = XMemcachedClient("127.0.0.1", 11211)
                MemcachedCache(client, object : SettingContext {
                    override val serializersModule: SerializersModule = SerializersModule {}
                    override val metricSink: MetricSink = MetricSink.None
                })
            } catch (e: Exception) {
                println("Could not connect to Memcached: ${e.message}")
                null
            }
        } else {
            println("Memcached is not available on this system.")
            null
        }
    }
    override fun runSuspendingTest(body: suspend CoroutineScope.() -> Unit) = runBlocking { body() }
    override val waitScale: Duration
        get() = 1.seconds

    companion object {
        private var memcachedProcess: Process? = null

        @JvmStatic
        @BeforeClass
        fun start() {
            if (EmbeddedMemcached.available) {
                try {
                    memcachedProcess = EmbeddedMemcached.start()
                    // Give it a moment to start up
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

    // We're using the default expirationTest from CacheTest
}