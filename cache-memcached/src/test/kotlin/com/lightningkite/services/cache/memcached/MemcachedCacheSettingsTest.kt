// by Claude
package com.lightningkite.services.cache.memcached

import com.lightningkite.services.cache.Cache
import com.lightningkite.services.cache.memcached.MemcachedCache.Companion.memcached
import com.lightningkite.services.cache.memcached.MemcachedCache.Companion.memcachedAws
import com.lightningkite.services.cache.memcached.MemcachedCache.Companion.memcachedTest
import org.junit.Test
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for MemcachedCache URL parsing and factory methods.
 * These tests do not require a running Memcached instance.
 */
class MemcachedCacheSettingsTest {

    init {
        // Trigger companion object initialization
        MemcachedCache
    }

    @Test
    fun testMemcachedFactoryMethod() {
        val settings = Cache.Settings.memcached(InetSocketAddress("localhost", 11211))
        assertEquals("memcached://localhost:11211", settings.url)
    }

    @Test
    fun testMemcachedFactoryMethodMultipleHosts() {
        val settings = Cache.Settings.memcached(
            InetSocketAddress("host1", 11211),
            InetSocketAddress("host2", 11212),
            InetSocketAddress("host3", 11213)
        )
        assertEquals("memcached://host1:11211,host2:11212,host3:11213", settings.url)
    }

    @Test
    fun testMemcachedTestFactoryMethod() {
        val settings = Cache.Settings.memcachedTest()
        assertEquals("memcached-test", settings.url)
    }

    @Test
    fun testMemcachedAwsFactoryMethod() {
        val settings = Cache.Settings.memcachedAws("my-cluster.cfg.cache.amazonaws.com", 11211)
        assertEquals("memcached-aws://my-cluster.cfg.cache.amazonaws.com:11211", settings.url)
    }

    @Test
    fun testMemcachedAwsFactoryMethodCustomPort() {
        val settings = Cache.Settings.memcachedAws("my-cluster.cfg.cache.amazonaws.com", 12345)
        assertEquals("memcached-aws://my-cluster.cfg.cache.amazonaws.com:12345", settings.url)
    }

    @Test
    fun testSupportedSchemes() {
        assertTrue(Cache.Settings.supports("memcached"))
        assertTrue(Cache.Settings.supports("memcached-test"))
        assertTrue(Cache.Settings.supports("memcached-aws"))
    }

    @Test
    fun testRegisteredOptions() {
        val options = Cache.Settings.options
        assertTrue(options.any { it.contains("memcached") })
    }
}
