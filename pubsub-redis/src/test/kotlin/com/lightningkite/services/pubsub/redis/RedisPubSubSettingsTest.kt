package com.lightningkite.services.pubsub.redis

import com.lightningkite.services.pubsub.PubSub
import kotlin.test.Test
import kotlin.test.assertTrue

class RedisPubSubSettingsTest {
    init {
        // The bare reference forces RedisPubSub's companion to register its URL schemes.
        @Suppress("UNUSED_EXPRESSION")
        RedisPubSub
    }

    @Test
    fun testSupportedSchemes() {
        assertTrue(PubSub.Settings.supports("redis"))
        // The ElastiCache Terraform helpers emit rediss:// URLs.
        assertTrue(PubSub.Settings.supports("rediss"))
    }
}
