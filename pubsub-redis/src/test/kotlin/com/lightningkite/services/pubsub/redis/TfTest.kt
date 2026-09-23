package com.lightningkite.services.pubsub.redis

import com.lightningkite.services.Untested
import com.lightningkite.services.pubsub.PubSub
import com.lightningkite.services.test.assertPlannableAwsVpc
import kotlin.test.Test

@OptIn(Untested::class)
class TfTest {
    init {
        // Force class init so RedisPubSub's companion registers the URL scheme;
        // the bare reference is the point of this line.
        @Suppress("UNUSED_EXPRESSION")
        RedisPubSub
    }

    @Test
    @Suppress("DEPRECATION")
    fun test() {
        assertPlannableAwsVpc<PubSub.Settings>(
            name = "redis",
            fulfill = {
                it.awsElasticacheRedis()
            }
        )
    }

    @Test
    fun testReplicationGroup() {
        assertPlannableAwsVpc<PubSub.Settings>(
            name = "redis-rg",
            fulfill = {
                it.awsElasticacheRedisReplicationGroup()
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

    @Test
    fun testValkey() {
        assertPlannableAwsVpc<PubSub.Settings>(
            name = "valkey",
            fulfill = {
                it.awsElasticacheValkey()
            }
        )
    }

    @Test
    fun testValkeyServerless() {
        assertPlannableAwsVpc<PubSub.Settings>(
            name = "valkey-sls",
            fulfill = {
                it.awsElasticacheValkeyServerless()
            }
        )
    }
}