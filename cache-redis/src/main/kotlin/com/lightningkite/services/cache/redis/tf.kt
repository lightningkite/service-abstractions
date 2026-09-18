package com.lightningkite.services.cache.redis

import com.lightningkite.services.Untested
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.terraform.*
import kotlinx.datetime.LocalTime
import kotlinx.serialization.json.JsonPrimitive

public class ReusableRedisSetting(
    public val connectionStringExpression: String
)

/**
 * Emits a node-based ElastiCache replication group (cluster mode disabled) for a Redis-compatible [engine].
 * The settings URL targets the primary endpoint, which stays stable across failover.
 */
context(emitter: TerraformEmitterAws) private fun awsElasticacheReplicationGroup(
    name: String,
    engine: String,
    engineVersion: String,
    type: String,
    parameterGroupName: String,
    count: Int,
): ReusableRedisSetting {
    if (!Cache.Settings.supports("redis")) throw IllegalArgumentException("You need to reference 'RedisCache' in your server definition to use this.")
    emptyList<TerraformProvider>().forEach { emitter.require(it) }
    setOf(TerraformProviderImport.aws).forEach { emitter.require(it) }

    val vpcInfo = emitter.applicationVpc as? AwsVpc.VpcInfo

    emitter.emit(name) {
        if (vpcInfo != null) {
            "resource.aws_elasticache_subnet_group.${name}" {
                "name" - "${emitter.projectPrefix}-${name}"
                "subnet_ids" - vpcInfo.privateSubnets
            }
        }
        "resource.aws_elasticache_replication_group.${name}" {
            "replication_group_id" - "${emitter.projectPrefix}-${name}".lowercase()
            "description" - "${engine.replaceFirstChar { it.uppercase() }} cache for ${emitter.projectPrefix}-${name}"
            "engine" - engine
            "engine_version" - engineVersion
            "node_type" - type
            "num_cache_clusters" - count
            "automatic_failover_enabled" - (count > 1)
            "parameter_group_name" - parameterGroupName
            "port" - 6379
            if (vpcInfo != null) {
                "security_group_ids" - listOf<String>(vpcInfo.securityGroup)
                "subnet_group_name" - expression("aws_elasticache_subnet_group.${name}.name")
            }
        }
    }
    return ReusableRedisSetting($$"redis://${aws_elasticache_replication_group.$${name}.primary_endpoint_address}:${aws_elasticache_replication_group.$${name}.port}/0")
}



/**
 * Emits an ElastiCache Serverless cache for a Redis-compatible [engine].
 */
@Untested
context(emitter: TerraformEmitterAws) private fun awsElasticacheServerless(
    name: String,
    engine: String,
    version: String,
    dailySnapshotTime: LocalTime,
    maxEcpuPerSecond: Int,
    maxStorageGb: Int,
    snapshotRetentionLimit: Int,
    kmsKey: KmsKeySource?,
): ReusableRedisSetting {
    if (!Cache.Settings.supports("redis")) throw IllegalArgumentException("You need to reference 'RedisCache' in your server definition to use this.")
    emptyList<TerraformProvider>().forEach { emitter.require(it) }
    setOf(TerraformProviderImport.aws).forEach { emitter.require(it) }

    val vpcInfo = emitter.applicationVpc as? AwsVpc.VpcInfo
    val kmsKeyArn = (kmsKey ?: emitter.encryptionKey).resolveKeyArn(name)

    emitter.emit(name) {
        "resource.aws_elasticache_serverless_cache.${name}" {
            "name" - "${emitter.projectPrefix}-${name}"
            "engine" - engine
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
            if (kmsKeyArn != null) "kms_key_id" - kmsKeyArn
            if (vpcInfo != null) {
                "security_group_ids" - listOf<String>(vpcInfo.securityGroup)
                "subnet_ids" - vpcInfo.privateSubnets
            }
        }
    }
    return ReusableRedisSetting($$"redis://${aws_elasticache_serverless_cache.$${name}.endpoint[0].address}:${aws_elasticache_serverless_cache.$${name}.endpoint[0].port}/0")
}


/**
 * Creates an AWS ElastiCache Redis cluster for caching.
 *
 * Emits Terraform resources for:
 * - `aws_elasticache_subnet_group`: Subnet group for the cluster
 * - `aws_elasticache_cluster`: The Redis cluster itself
 *
 * The generated settings URL connects to the first cache node on the default database (0).
 *
 * @param name The base name of the terraform resources
 * @param type The EC2 instance type for cache nodes (e.g., "cache.t4g.micro", "cache.m5.large")
 * @param parameterGroupName The Redis parameter group (default: "default.redis7")
 * @param count The number of cache nodes to create (1 for non-cluster mode)
 */
context(emitter: TerraformEmitterAws) public fun awsElasticacheRedis(
    name: String,
    type: String = "cache.t4g.micro",
    parameterGroupName: String = "default.redis7",
    count: Int = 1,
): ReusableRedisSetting {
    if (!Cache.Settings.supports("redis")) throw IllegalArgumentException("You need to reference 'RedisCache' in your server definition to use this.")
    emptyList<TerraformProvider>().forEach { emitter.require(it) }
    setOf(TerraformProviderImport.aws).forEach { emitter.require(it) }

    val vpcInfo = emitter.applicationVpc as? AwsVpc.VpcInfo

    emitter.emit(name) {
        if (vpcInfo != null) {
            "resource.aws_elasticache_subnet_group.${name}" {
                "name" - "${emitter.projectPrefix}-${name}"
                "subnet_ids" - vpcInfo.privateSubnets
            }
        }
        "resource.aws_elasticache_cluster.${name}" {
            "cluster_id" - "${emitter.projectPrefix}-${name}".lowercase()
            "engine" - "redis"
            "node_type" - type
            "num_cache_nodes" - count
            "parameter_group_name" - parameterGroupName
            "port" - 6379
            if (vpcInfo != null) {
                "security_group_ids" - listOf<String>(vpcInfo.securityGroup)
                "subnet_group_name" - expression("aws_elasticache_subnet_group.${name}.name")
            }
        }
    }
    return ReusableRedisSetting($$"redis://${element(aws_elasticache_cluster.$${name}.cache_nodes, 0).address}:${element(aws_elasticache_cluster.$${name}.cache_nodes, 0).port}/0")
}

/**
 * Creates an AWS ElastiCache Redis cache.
 *
 * The cache is created as a replication group with cluster mode disabled; the settings URL targets the
 * primary endpoint (default database 0), which stays stable across failover.
 *
 * Emits Terraform resources for:
 * - `aws_elasticache_subnet_group`: Subnet group for the cache
 * - `aws_elasticache_replication_group`: The Redis replication group itself
 *
 * @param name The base name of the terraform resources
 * @param type The EC2 instance type for cache nodes (e.g., "cache.t4g.micro", "cache.m5.large")
 * @param parameterGroupName The Redis parameter group; must match [engineVersion] (default: "default.redis7")
 * @param count The number of cache nodes (1 primary plus `count - 1` replicas); automatic failover is enabled when greater than 1
 * @param engineVersion The Redis engine version (e.g., "7.1")
 */
context(emitter: TerraformEmitterAws) public fun awsElasticacheRedisReplicationGroup(
    name: String,
    type: String = "cache.t4g.micro",
    parameterGroupName: String = "default.redis7",
    count: Int = 1,
    engineVersion: String = "7.1",
): ReusableRedisSetting = awsElasticacheReplicationGroup(name, "redis", engineVersion, type, parameterGroupName, count)


/**
 * Creates an AWS ElastiCache Serverless Redis cache.
 *
 * Serverless caches automatically scale based on demand without managing nodes.
 * Uses ECPU (ElastiCache Processing Units) for pricing.
 *
 * Emits Terraform resources for:
 * - `aws_elasticache_serverless_cache`: The serverless Redis cache
 *
 * @param name The base name of the terraform resources
 * @param version The Redis major engine version (e.g., "7" for Redis 7.x)
 * @param dailySnapshotTime Time of day for automatic snapshots (UTC)
 * @param maxEcpuPerSecond Maximum ECPU per second (controls performance ceiling)
 * @param maxStorageGb Maximum storage in gigabytes
 * @param snapshotRetentionLimit Number of daily snapshots to retain
 */
@Untested
context(emitter: TerraformEmitterAws) public fun awsElasticacheRedisServerless(
    name: String,
    // ElastiCache Serverless Redis only accepts the major version "7" (Redis OSS 7.x); "1.6" is a Memcached version.
    version: String = "7",
    dailySnapshotTime: LocalTime = LocalTime(9, 0),
    maxEcpuPerSecond: Int = 5000,
    maxStorageGb: Int = 10,
    snapshotRetentionLimit: Int = 1,
    kmsKey: KmsKeySource? = null,
): ReusableRedisSetting = awsElasticacheServerless(name, "redis", version, dailySnapshotTime, maxEcpuPerSecond, maxStorageGb, snapshotRetentionLimit, kmsKey)

/**
 * Creates an AWS ElastiCache Valkey cache.
 *
 * Valkey is wire-compatible with Redis, so the result is consumed through the same `redis://` settings URL.
 * The cache is created as a replication group with cluster mode disabled; the settings URL targets the
 * primary endpoint, which stays stable across failover.
 *
 * Emits Terraform resources for:
 * - `aws_elasticache_subnet_group`: Subnet group for the cache
 * - `aws_elasticache_replication_group`: The Valkey replication group itself
 *
 * @param name The base name of the terraform resources
 * @param type The EC2 instance type for cache nodes (e.g., "cache.t4g.micro", "cache.m5.large")
 * @param parameterGroupName The Valkey parameter group; must match [engineVersion] (default: "default.valkey8")
 * @param count The number of cache nodes (1 primary plus `count - 1` replicas); automatic failover is enabled when greater than 1
 * @param engineVersion The Valkey engine version (e.g., "8.0", "7.2")
 */
context(emitter: TerraformEmitterAws) public fun awsElasticacheValkey(
    name: String,
    type: String = "cache.t4g.micro",
    parameterGroupName: String = "default.valkey9",
    count: Int = 1,
    engineVersion: String = "9.0",
): ReusableRedisSetting = awsElasticacheReplicationGroup(name, "valkey", engineVersion, type, parameterGroupName, count)

/**
 * Creates an AWS ElastiCache Serverless Valkey cache.
 *
 * Serverless caches automatically scale based on demand without managing nodes.
 * Uses ECPU (ElastiCache Processing Units) for pricing.
 *
 * Emits Terraform resources for:
 * - `aws_elasticache_serverless_cache`: The serverless Valkey cache
 *
 * @param name The base name of the terraform resources
 * @param version The Valkey major engine version (e.g., "8" for Valkey 8.x)
 * @param dailySnapshotTime Time of day for automatic snapshots (UTC)
 * @param maxEcpuPerSecond Maximum ECPU per second (controls performance ceiling)
 * @param maxStorageGb Maximum storage in gigabytes
 * @param snapshotRetentionLimit Number of daily snapshots to retain
 */
@Untested
context(emitter: TerraformEmitterAws) public fun awsElasticacheValkeyServerless(
    name: String,
    version: String = "9",
    dailySnapshotTime: LocalTime = LocalTime(9, 0),
    maxEcpuPerSecond: Int = 5000,
    maxStorageGb: Int = 10,
    snapshotRetentionLimit: Int = 1,
    kmsKey: KmsKeySource? = null,
): ReusableRedisSetting = awsElasticacheServerless(name, "valkey", version, dailySnapshotTime, maxEcpuPerSecond, maxStorageGb, snapshotRetentionLimit, kmsKey)

context(emitter: TerraformEmitterAws) public fun TerraformNeed<Cache.Settings>.redis(
    reusableRedisSetting: ReusableRedisSetting
): ReusableRedisSetting {
    val c = reusableRedisSetting
    emitter.fulfillSetting(
        name,
        JsonPrimitive(value = c.connectionStringExpression)
    )
    return c
}


/**
 * Creates an AWS ElastiCache Redis cluster for caching.
 *
 * Emits Terraform resources for:
 * - `aws_elasticache_subnet_group`: Subnet group for the cluster
 * - `aws_elasticache_cluster`: The Redis cluster itself
 *
 * The generated settings URL connects to the first cache node on the default database (0).
 *
 * @param type The EC2 instance type for cache nodes (e.g., "cache.t4g.micro", "cache.m5.large")
 * @param parameterGroupName The Redis parameter group (default: "default.redis7")
 * @param count The number of cache nodes to create (1 for non-cluster mode)
 */
context(emitter: TerraformEmitterAws) public fun TerraformNeed<Cache.Settings>.awsElasticacheRedis(
    type: String = "cache.t4g.micro",
    parameterGroupName: String = "default.redis7",
    count: Int = 1,
): ReusableRedisSetting = redis(awsElasticacheRedis(name, type, parameterGroupName, count))


/**
 * Creates an AWS ElastiCache Redis cache.
 *
 * Emits Terraform resources for:
 * - `aws_elasticache_subnet_group`: Subnet group for the cache
 * - `aws_elasticache_replication_group`: The Redis replication group itself
 *
 * The generated settings URL connects to the primary endpoint on the default database (0).
 *
 * @param type The EC2 instance type for cache nodes (e.g., "cache.t4g.micro", "cache.m5.large")
 * @param parameterGroupName The Redis parameter group; must match [engineVersion] (default: "default.redis7")
 * @param count The number of cache nodes (1 primary plus `count - 1` replicas); automatic failover is enabled when greater than 1
 * @param engineVersion The Redis engine version (e.g., "7.1")
 */
context(emitter: TerraformEmitterAws) public fun TerraformNeed<Cache.Settings>.awsElasticacheRedisReplicationGroup(
    type: String = "cache.t4g.micro",
    parameterGroupName: String = "default.redis7",
    count: Int = 1,
    engineVersion: String = "7.1",
): ReusableRedisSetting = redis(awsElasticacheRedisReplicationGroup(name, type, parameterGroupName, count, engineVersion))


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
context(emitter: TerraformEmitterAws) public fun TerraformNeed<Cache.Settings>.awsElasticacheRedisServerless(
    version: String = "7",
    dailySnapshotTime: LocalTime = LocalTime(9, 0),
    maxEcpuPerSecond: Int = 5000,
    maxStorageGb: Int = 10,
    snapshotRetentionLimit: Int = 1,
    kmsKey: KmsKeySource? = null,
): ReusableRedisSetting = redis(awsElasticacheRedisServerless(name, version, dailySnapshotTime, maxEcpuPerSecond, maxStorageGb, snapshotRetentionLimit, kmsKey))


/**
 * Creates an AWS ElastiCache Valkey cache.
 *
 * Emits Terraform resources for:
 * - `aws_elasticache_subnet_group`: Subnet group for the cache
 * - `aws_elasticache_replication_group`: The Valkey replication group itself
 *
 * @param type The EC2 instance type for cache nodes (e.g., "cache.t4g.micro", "cache.m5.large")
 * @param parameterGroupName The Valkey parameter group; must match [engineVersion] (default: "default.valkey8")
 * @param count The number of cache nodes (1 primary plus `count - 1` replicas); automatic failover is enabled when greater than 1
 * @param engineVersion The Valkey engine version (e.g., "8.0", "7.2")
 */
context(emitter: TerraformEmitterAws) public fun TerraformNeed<Cache.Settings>.awsElasticacheValkey(
    type: String = "cache.t4g.micro",
    parameterGroupName: String = "default.valkey9",
    count: Int = 1,
    engineVersion: String = "9.0",
): ReusableRedisSetting = redis(awsElasticacheValkey(name, type, parameterGroupName, count, engineVersion))


/**
 * Creates an AWS ElastiCache Serverless Valkey cache.
 *
 * Serverless caches automatically scale based on demand without managing nodes.
 * Uses ECPU (ElastiCache Processing Units) for pricing.
 *
 * Emits Terraform resources for:
 * - `aws_elasticache_serverless_cache`: The serverless Valkey cache
 *
 * @param version The Valkey major engine version (e.g., "8" for Valkey 8.x)
 * @param dailySnapshotTime Time of day for automatic snapshots (UTC)
 * @param maxEcpuPerSecond Maximum ECPU per second (controls performance ceiling)
 * @param maxStorageGb Maximum storage in gigabytes
 * @param snapshotRetentionLimit Number of daily snapshots to retain
 */
@Untested
context(emitter: TerraformEmitterAws) public fun TerraformNeed<Cache.Settings>.awsElasticacheValkeyServerless(
    version: String = "9",
    dailySnapshotTime: LocalTime = LocalTime(9, 0),
    maxEcpuPerSecond: Int = 5000,
    maxStorageGb: Int = 10,
    snapshotRetentionLimit: Int = 1,
    kmsKey: KmsKeySource? = null,
): ReusableRedisSetting = redis(awsElasticacheValkeyServerless(name, version, dailySnapshotTime, maxEcpuPerSecond, maxStorageGb, snapshotRetentionLimit, kmsKey))
