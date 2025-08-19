package com.lightningkite.services.cache.redis

import com.lightningkite.services.cache.Cache
import com.lightningkite.services.cache.get
import com.lightningkite.services.cache.set
import com.lightningkite.services.test.assertPlannableAws
import com.lightningkite.services.test.assertTerraformApply
import com.lightningkite.services.test.expensive
import com.lightningkite.services.test.withAwsSpecific
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals

class TfTest {
    @Test fun test() {
        assertPlannableAws<Cache.Settings>(vpc = true) {
            it.awsElasticacheRedis()
        }
    }
    @Test fun expensiveTest() {
        RedisCache
        expensive {
            assertTerraformApply(
                name = "aws-redis",
                domain = false,
                vpc = true,
                serializer = Cache.Settings.serializer(),
                fulfill = {
                    it.awsElasticacheRedis()
                }
            )
        }
    }
    @Test fun testServerless() {
        assertPlannableAws<Cache.Settings>(vpc = true) {
            it.awsElasticacheRedisServerless()
        }
    }
    @Test fun expensiveTestServerless() {
        RedisCache
        expensive {
            assertTerraformApply(
                name = "aws-redis-serverless",
                domain = false,
                vpc = true,
                serializer = Cache.Settings.serializer(),
                fulfill = {
                    it.awsElasticacheRedisServerless()
                }
            )
        }
    }
}