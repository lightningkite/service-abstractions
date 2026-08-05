package com.lightningkite.services.cache.redis

import com.lightningkite.services.Untested
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.test.assertPlannableAwsVpc
import kotlin.test.Test

@OptIn(Untested::class)
class TfTest {
    init {
        // Force class init so RedisCache's companion registers the URL scheme;
        // the bare reference is the point of this line.
        @Suppress("UNUSED_EXPRESSION")
        RedisCache
    }

    @Test
    fun test() {
        assertPlannableAwsVpc<Cache.Settings>(
            name = "redis",
            fulfill = {
                it.awsElasticacheRedis()
            }
        )
    }

    @Test
    fun testServerless() {
        assertPlannableAwsVpc<Cache.Settings>(
            name = "redis-sls",
            fulfill = {
                it.awsElasticacheRedisServerless()
            }
        )
    }
}