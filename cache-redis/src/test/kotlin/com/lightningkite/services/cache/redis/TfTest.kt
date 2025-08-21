package com.lightningkite.services.cache.redis

import com.lightningkite.services.cache.Cache
import com.lightningkite.services.cache.get
import com.lightningkite.services.cache.set
import com.lightningkite.services.test.assertPlannableAws
import com.lightningkite.services.test.assertPlannableAwsVpc
import com.lightningkite.services.test.expensive
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals

class TfTest {
    init {
        RedisCache
    }
    @Test fun test() {
        assertPlannableAwsVpc<Cache.Settings>(
            name = "redis",
            fulfill = {
                it.awsElasticacheRedis()
            }
        )
    }
    @Test fun testServerless() {
        assertPlannableAwsVpc<Cache.Settings>(
            name = "redis-sls",
            fulfill = {
                it.awsElasticacheRedisServerless()
            }
        )
    }
}