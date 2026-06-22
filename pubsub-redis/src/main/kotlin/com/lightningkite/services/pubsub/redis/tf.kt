package com.lightningkite.services.pubsub.redis

import com.lightningkite.services.Untested
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.pubsub.PubSub
import com.lightningkite.services.terraform.*
import kotlinx.datetime.LocalTime
import kotlinx.serialization.json.JsonPrimitive
import com.lightningkite.services.cache.redis.*


context(emitter: TerraformEmitterAws) public fun TerraformNeed<PubSub.Settings>.redis(
    reusableRedisSetting: ReusableRedisSetting
): ReusableRedisSetting {
    val c = reusableRedisSetting
    emitter.fulfillSetting(
        name,
        JsonPrimitive(value = c.connectionStringExpression)
    )
    return c
}

@Untested
context(emitter: TerraformEmitterAws) public fun TerraformNeed<PubSub.Settings>.awsElasticacheRedis(
    type: String = "cache.t2.micro",
    parameterGroupName: String = "default.redis7",
    count: Int = 1,
): ReusableRedisSetting = redis(awsElasticacheRedis(name, type, parameterGroupName, count))


/**
 * Creates an AWS ElastiCache Redis cluster for caching.
 *
 * @param type The instance type to use for the cache nodes.
 * @param count The number of cache nodes to create.
 * @return A TerraformServiceResult with the configuration for the Redis cluster.
 */
@Untested
context(emitter: TerraformEmitterAws) public fun TerraformNeed<PubSub.Settings>.awsElasticacheRedisServerless(
    version: String = "1.6",
    dailySnapshotTime: LocalTime = LocalTime(9, 0),
    maxEcpuPerSecond: Int = 5000,
    maxStorageGb: Int = 10,
    snapshotRetentionLimit: Int = 1,
): ReusableRedisSetting = redis(awsElasticacheRedisServerless(name, version, dailySnapshotTime, maxEcpuPerSecond, maxStorageGb, snapshotRetentionLimit))
