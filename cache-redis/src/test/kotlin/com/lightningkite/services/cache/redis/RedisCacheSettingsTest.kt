// by Claude
package com.lightningkite.services.cache.redis

import com.lightningkite.services.cache.Cache
import com.lightningkite.services.cache.redis.RedisCache.Companion.redis
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for RedisCache URL parsing and factory methods.
 * These tests do not require a running Redis instance.
 */
class RedisCacheSettingsTest {

    init {
        // Trigger companion object initialization
        RedisCache
    }

    @Test
    fun testRedisFactoryMethod() {
        val settings = Cache.Settings.redis("localhost:6379")
        assertEquals("redis://localhost:6379", settings.url)
    }

    @Test
    fun testRedisFactoryMethodWithDatabase() {
        val settings = Cache.Settings.redis("localhost:6379/1")
        assertEquals("redis://localhost:6379/1", settings.url)
    }

    @Test
    fun testRedisFactoryMethodWithAuth() {
        val settings = Cache.Settings.redis("user:password@host:6379")
        assertEquals("redis://user:password@host:6379", settings.url)
    }

    @Test
    fun testSupportedSchemes() {
        assertTrue(Cache.Settings.supports("redis"))
    }

    @Test
    fun testRegisteredOptions() {
        val options = Cache.Settings.options
        assertTrue(options.any { it.contains("redis") })
    }
}
