package com.lightningkite.services.pubsub.redis

import com.lightningkite.services.cache.Cache
import com.lightningkite.services.cache.get
import com.lightningkite.services.cache.set
import com.lightningkite.services.pubsub.PubSub
import com.lightningkite.services.pubsub.redis.RedisPubSub
import com.lightningkite.services.pubsub.redis.awsElasticacheRedis
import com.lightningkite.services.pubsub.redis.awsElasticacheRedisServerless
import com.lightningkite.services.test.assertPlannableAws
import com.lightningkite.services.test.assertPlannableAwsVpc
import com.lightningkite.services.test.expensive
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals

class TfTest {
    init {
        RedisPubSub
    }
    @Test fun test() {
        assertPlannableAwsVpc<PubSub.Settings>(
            name = "redis",
            fulfill = {
                it.awsElasticacheRedis()
            }
        )
    }
    @Test fun testServerless() {
        assertPlannableAwsVpc<PubSub.Settings>(
            name = "redis-sls",
            fulfill = {
                it.awsElasticacheRedisServerless()
            }
        )
    }
}