package com.lightningkite.services.cache.memcached

import com.lightningkite.services.Untested
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.terraform.TerraformEmitterAwsVpc
import com.lightningkite.services.terraform.TerraformNeed
import com.lightningkite.services.terraform.TerraformProvider
import com.lightningkite.services.terraform.TerraformProviderImport
import kotlinx.datetime.LocalTime
import kotlinx.serialization.json.JsonPrimitive

/**
 * Creates an AWS ElastiCache Memcached cluster for caching.
 *
 * Emits Terraform resources for:
 * - `aws_elasticache_subnet_group`: Subnet group for the cluster
 * - `aws_elasticache_cluster`: The Memcached cluster itself
 *
 * The generated settings URL uses the cluster's configuration endpoint.
 *
 * @param type The EC2 instance type for cache nodes (e.g., "cache.t2.micro", "cache.m5.large")
 * @param parameterGroupName The Memcached parameter group (default: "default.memcached1.6")
 * @param count The number of cache nodes to create (1-40)
 */
@Untested
context(emitter: TerraformEmitterAwsVpc) public fun TerraformNeed<Cache.Settings>.awsElasticacheMemcached(
    type: String = "cache.t2.micro",
    parameterGroupName: String = "default.memcached1.6",
    count: Int = 1
): Unit {
    if(!Cache.Settings.supports("memcached-aws")) throw IllegalArgumentException("You need to reference 'MemcachedCache' in your server definition to use this.")
    emitter.fulfillSetting(
        name,
        JsonPrimitive(value = "memcached-aws://\${aws_elasticache_cluster.${name}.configuration_endpoint}")
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
            "engine" - "memcached"
            "node_type" - type
            "num_cache_nodes" - count
            "parameter_group_name" - parameterGroupName
            "port" - 11211
            "security_group_ids" - listOf<String>(expression(emitter.applicationVpc.securityGroup))
            "subnet_group_name" - "\${aws_elasticache_subnet_group.${name}.name}"
        }
    }
}

/**
 * Creates an AWS ElastiCache Serverless Memcached cache.
 *
 * Serverless caches automatically scale based on demand without managing nodes.
 * Uses ECPU (ElastiCache Processing Units) for pricing.
 *
 * Emits Terraform resources for:
 * - `aws_elasticache_serverless_cache`: The serverless Memcached cache
 *
 * @param version The Memcached major version (e.g., "1.6")
 * @param dailySnapshotTime Time of day for automatic snapshots (UTC)
 * @param maxEcpuPerSecond Maximum ECPU per second (controls performance ceiling)
 * @param maxStorageGb Maximum storage in gigabytes
 * @param snapshotRetentionLimit Number of daily snapshots to retain
 */
@Untested
context(emitter: TerraformEmitterAwsVpc) public fun TerraformNeed<Cache.Settings>.awsElasticacheMemcachedServerless(
    version: String = "1.6",
    dailySnapshotTime: LocalTime = LocalTime(9, 0),
    maxEcpuPerSecond: Int = 5000,
    maxStorageGb: Int = 10,
    snapshotRetentionLimit: Int = 1,
): Unit {
    if(!Cache.Settings.supports("memcached-aws")) throw IllegalArgumentException("You need to reference 'MemcachedCache' in your server definition to use this.")
    emitter.fulfillSetting(
        name,
        JsonPrimitive(value = $$"memcached-aws://${aws_elasticache_serverless_cache.$${name}.endpoint[0].address}:${aws_elasticache_serverless_cache.$${name}.endpoint[0].port}")
    )
    emptyList<TerraformProvider>().forEach { emitter.require(it) }
    setOf(TerraformProviderImport.aws).forEach { emitter.require(it) }
    emitter.emit(name) {
        "resource.aws_elasticache_serverless_cache.${name}" {
            "name" - "${emitter.projectPrefix}-${name}"
            "engine" - "memcached"
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