package com.lightningkite.services.pubsub.redis

import com.lightningkite.services.Untested
import com.lightningkite.services.pubsub.PubSub
import com.lightningkite.services.terraform.TerraformEmitterAwsVpc
import com.lightningkite.services.terraform.TerraformNeed
import com.lightningkite.services.terraform.TerraformProvider
import com.lightningkite.services.terraform.TerraformProviderImport
import kotlinx.datetime.LocalTime
import kotlinx.serialization.json.JsonPrimitive

@Untested
context(emitter: TerraformEmitterAwsVpc) public fun TerraformNeed<PubSub.Settings>.awsElasticacheRedis(
    type: String = "cache.t2.micro",
    parameterGroupName: String = "default.redis7",
    count: Int = 1
): Unit {
    if(!PubSub.Settings.supports("redis")) throw IllegalArgumentException("You need to reference RedisPubSub in your server definition to use this.")
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
 * Creates an AWS ElastiCache Redis cluster for caching.
 *
 * @param type The instance type to use for the cache nodes.
 * @param count The number of cache nodes to create.
 * @return A TerraformServiceResult with the configuration for the Redis cluster.
 */
@Untested
context(emitter: TerraformEmitterAwsVpc) public fun TerraformNeed<PubSub.Settings>.awsElasticacheRedisServerless(
    version: String = "1.6",
    dailySnapshotTime: LocalTime = LocalTime(9, 0),
    maxEcpuPerSecond: Int = 5000,
    maxStorageGb: Int = 10,
    snapshotRetentionLimit: Int = 1,
): Unit {
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
