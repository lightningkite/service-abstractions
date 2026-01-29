package com.lightningkite.services.cache.redis

import com.lightningkite.services.Untested
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.terraform.TerraformEmitterAwsVpc
import com.lightningkite.services.terraform.TerraformNeed
import com.lightningkite.services.terraform.TerraformProvider
import com.lightningkite.services.terraform.TerraformProviderImport
import kotlinx.datetime.LocalTime
import kotlinx.serialization.json.JsonPrimitive

/**
 * Creates an AWS ElastiCache Redis cluster for caching.
 *
 * Emits Terraform resources for:
 * - `aws_elasticache_subnet_group`: Subnet group for the cluster
 * - `aws_elasticache_cluster`: The Redis cluster itself
 *
 * The generated settings URL connects to the first cache node on the default database (0).
 *
 * @param type The EC2 instance type for cache nodes (e.g., "cache.t2.micro", "cache.m5.large")
 * @param parameterGroupName The Redis parameter group (default: "default.redis7")
 * @param count The number of cache nodes to create (1 for non-cluster mode)
 */
@Untested
context(emitter: TerraformEmitterAwsVpc) public fun TerraformNeed<Cache.Settings>.awsElasticacheRedis(
    type: String = "cache.t2.micro",
    parameterGroupName: String = "default.redis7",
    count: Int = 1
): Unit {
    if(!Cache.Settings.supports("redis")) throw IllegalArgumentException("You need to reference 'RedisCache' in your server definition to use this.")
    emitter.fulfillSetting(
        name,
        JsonPrimitive(value = $$"redis://${element(aws_elasticache_cluster.$${name}.cache_nodes, 0).address}:${element(aws_elasticache_cluster.$${name}.cache_nodes, 0).port}/0")
    )
    emptyList<TerraformProvider>().forEach { emitter.require(it) }
    setOf(TerraformProviderImport.aws).forEach { emitter.require(it) }
    emitter.emit(name) {
        "resource.aws_elasticache_subnet_group.${name}" {
            "name" - "${emitter.projectPrefix}-${name}"
            "subnet_ids" - expression(emitter.applicationVpc.privateSubnets)
        }
        "resource.aws_elasticache_cluster.${name}" {
            "cluster_id" - "${emitter.projectPrefix}-${name}"
            "engine" - "redis"
            "node_type" - type
            "num_cache_nodes" - count
            "parameter_group_name" - parameterGroupName
            "port" - 6379
            "security_group_ids" - listOf<String>(expression(emitter.applicationVpc.securityGroup))
            "subnet_group_name" - expression("aws_elasticache_subnet_group.${name}.name")
        }
    }
}



/**
 * Creates an AWS ElastiCache Serverless Redis cache.
 *
 * Serverless caches automatically scale based on demand without managing nodes.
 * Uses ECPU (ElastiCache Processing Units) for pricing.
 *
 * Emits Terraform resources for:
 * - `aws_elasticache_serverless_cache`: The serverless Redis cache
 *
 * @param version The Redis major engine version (e.g., "7" for Redis 7.x)
 * @param dailySnapshotTime Time of day for automatic snapshots (UTC)
 * @param maxEcpuPerSecond Maximum ECPU per second (controls performance ceiling)
 * @param maxStorageGb Maximum storage in gigabytes
 * @param snapshotRetentionLimit Number of daily snapshots to retain
 */
@Untested
context(emitter: TerraformEmitterAwsVpc) public fun TerraformNeed<Cache.Settings>.awsElasticacheRedisServerless(
    version: String = "1.6",
    dailySnapshotTime: LocalTime = LocalTime(9, 0),
    maxEcpuPerSecond: Int = 5000,
    maxStorageGb: Int = 10,
    snapshotRetentionLimit: Int = 1,
): Unit {
    if(!Cache.Settings.supports("redis")) throw IllegalArgumentException("You need to reference 'RedisCache' in your server definition to use this.")
    emitter.fulfillSetting(
        name,
        JsonPrimitive(value = $$"redis://${aws_elasticache_serverless_cache.$${name}.endpoint[0].address}:${aws_elasticache_serverless_cache.$${name}.endpoint[0].port}/0")
    )
    emptyList<TerraformProvider>().forEach { emitter.require(it) }
    setOf(TerraformProviderImport.aws).forEach { emitter.require(it) }
    emitter.emit(name) {
        "resource.aws_elasticache_serverless_cache.${name}" {
            "name" - "${emitter.projectPrefix}-${name}"
            "engine" - "redis"
            "cache_usage_limits" {
                "data_storage" {
                    "maximum" - maxStorageGb
                    "unit" - "GB"
                }
                "ecpu_per_second" {
                    "maximum" - maxEcpuPerSecond
                }
            }
            "daily_snapshot_time" - dailySnapshotTime.toString()
            "major_engine_version" - version
            "snapshot_retention_limit" - snapshotRetentionLimit
            "security_group_ids" - listOf<String>(expression(emitter.applicationVpc.securityGroup))
            "subnet_ids" - expression(emitter.applicationVpc.privateSubnets)
        }
    }
}
