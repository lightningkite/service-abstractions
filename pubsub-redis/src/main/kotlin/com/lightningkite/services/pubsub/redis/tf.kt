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


context(emitter: TerraformEmitterAws) public fun TerraformNeed<PubSub.Settings>.awsElasticacheRedis(
    type: String = "cache.t2.micro",
    parameterGroupName: String = "default.redis7",
    count: Int = 1,
): ReusableRedisSetting = redis(awsElasticacheRedis(name, type, parameterGroupName, count))


/**
 * Creates an AWS ElastiCache Redis cache for pub/sub, as a replication group with cluster mode disabled.
 *
 * @param type The EC2 instance type for cache nodes (e.g., "cache.t4g.micro", "cache.m5.large")
 * @param parameterGroupName The Redis parameter group; must match [engineVersion] (default: "default.redis7")
 * @param count The number of cache nodes (1 primary plus `count - 1` replicas)
 * @param engineVersion The Redis engine version (e.g., "7.1")
 */
context(emitter: TerraformEmitterAws) public fun TerraformNeed<PubSub.Settings>.awsElasticacheRedisReplicationGroup(
    type: String = "cache.t4g.micro",
    parameterGroupName: String = "default.redis7",
    count: Int = 1,
    engineVersion: String = "7.1",
): ReusableRedisSetting = redis(awsElasticacheRedisReplicationGroup(name, type, parameterGroupName, count, engineVersion))


/**
 * Creates an AWS ElastiCache Valkey cache for pub/sub.
 *
 * @param type The EC2 instance type for cache nodes (e.g., "cache.t4g.micro", "cache.m5.large")
 * @param parameterGroupName The Valkey parameter group; must match [engineVersion] (default: "default.valkey8")
 * @param count The number of cache nodes (1 primary plus `count - 1` replicas)
 * @param engineVersion The Valkey engine version (e.g., "8.0", "7.2")
 */
context(emitter: TerraformEmitterAws) public fun TerraformNeed<PubSub.Settings>.awsElasticacheValkey(
    type: String = "cache.t4g.micro",
    parameterGroupName: String = "default.valkey9",
    count: Int = 1,
    engineVersion: String = "9.0",
): ReusableRedisSetting = redis(awsElasticacheValkey(name, type, parameterGroupName, count, engineVersion))


/**
 * Creates an AWS ElastiCache Redis cluster for caching.
 *
 * @param type The instance type to use for the cache nodes.
 * @param count The number of cache nodes to create.
 * @return A TerraformServiceResult with the configuration for the Redis cluster.
 */
@Untested
context(emitter: TerraformEmitterAws) public fun TerraformNeed<PubSub.Settings>.awsElasticacheRedisServerless(
    version: String = "7",
    dailySnapshotTime: LocalTime = LocalTime(9, 0),
    maxEcpuPerSecond: Int = 5000,
    maxStorageGb: Int = 10,
    snapshotRetentionLimit: Int = 1,
): ReusableRedisSetting = redis(awsElasticacheRedisServerless(name, version, dailySnapshotTime, maxEcpuPerSecond, maxStorageGb, snapshotRetentionLimit))


/**
 * Creates an AWS ElastiCache Serverless Valkey cache for pub/sub.
 *
 * @param version The Valkey major engine version (e.g., "8" for Valkey 8.x)
 * @param dailySnapshotTime Time of day for automatic snapshots (UTC)
 * @param maxEcpuPerSecond Maximum ECPU per second (controls performance ceiling)
 * @param maxStorageGb Maximum storage in gigabytes
 * @param snapshotRetentionLimit Number of daily snapshots to retain
 */
@Untested
context(emitter: TerraformEmitterAws) public fun TerraformNeed<PubSub.Settings>.awsElasticacheValkeyServerless(
    version: String = "9",
    dailySnapshotTime: LocalTime = LocalTime(9, 0),
    maxEcpuPerSecond: Int = 5000,
    maxStorageGb: Int = 10,
    snapshotRetentionLimit: Int = 1,
    kmsKey: KmsKeySource? = null,
): ReusableRedisSetting = redis(awsElasticacheValkeyServerless(name, version, dailySnapshotTime, maxEcpuPerSecond, maxStorageGb, snapshotRetentionLimit, kmsKey))
