package com.lightningkite.services.cache.memcached

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.cache.test.CacheTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import net.rubyeye.xmemcached.XMemcachedClient
import org.junit.AfterClass
import org.junit.BeforeClass
import java.lang.Exception
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class MemcachedTest : CacheTest() {
    init {
        MemcachedCache
    }

    override val cache: Cache? by lazy {
        if (EmbeddedMemcached.available) {
            Cache.Settings("memcached-test").invoke("test", TestSettingContext())
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