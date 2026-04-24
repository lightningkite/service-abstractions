package com.lightningkite.services.pubsub.redis

import com.lightningkite.services.Untested
import com.lightningkite.services.pubsub.PubSub
import com.lightningkite.services.test.assertPlannableAwsVpc
import kotlin.test.Test

@OptIn(Untested::class)
class TfTest {
    init {
        RedisPubSub
    }

    @Test
    fun test() {
        assertPlannableAwsVpc<PubSub.Settings>(
            name = "redis",
            fulfill = {
                it.awsElasticacheRedis()
            }
        )
    }

    @Test
    fun testServerless() {
        assertPlannableAwsVpc<PubSub.Settings>(
            name = "redis-sls",
            fulfill = {
                it.awsElasticacheRedisServerless()
            }
        )
    }
}