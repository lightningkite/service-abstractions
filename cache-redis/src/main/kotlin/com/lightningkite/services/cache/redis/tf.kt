package com.lightningkite.services.cache.redis

import com.lightningkite.services.Untested
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.terraform.TerraformNeed
import com.lightningkite.services.terraform.TerraformProviderImport
import com.lightningkite.services.terraform.TerraformServiceResult
import com.lightningkite.services.terraform.terraformJsonObject
import kotlinx.datetime.LocalTime

@Untested
public fun TerraformNeed<Cache.Settings>.awsElasticacheRedis(
    type: String = "cache.t2.micro",
    parameterGroupName: String = "default.redis7",
    count: Int = 1
): TerraformServiceResult<Cache.Settings> = TerraformServiceResult(
    need = this,
    setting = $$"redis://${element(aws_elasticache_cluster.$${name}.cache_nodes, 0).address}:${element(aws_elasticache_cluster.$${name}.cache_nodes, 0).port}/0",
    requireProviders = setOf(TerraformProviderImport.aws),
    content = terraformJsonObject {
        "resource.aws_elasticache_subnet_group.$name" {
            "name"       - "${cloudInfo.projectPrefix}-${name}"
            "subnet_ids" - (cloudInfo.applicationVpc?.privateSubnetsExpression ?: throw IllegalArgumentException("Must have private subnets if you want to use Redis."))
        }
        "resource.aws_elasticache_cluster.$name" {
            "cluster_id"           - "${cloudInfo.projectPrefix}-${name}"
            "engine"               - "redis"
            "node_type"            - type
            "num_cache_nodes"      - count
            "parameter_group_name" - parameterGroupName
            "port"                 - 6379
            "security_group_ids"   - listOf(cloudInfo.applicationVpc!!.securityGroupExpression)
            "subnet_group_name"    - expression("aws_elasticache_subnet_group.${name}.name")
        }
    }
)



/**
 * Creates an AWS ElastiCache Redis cluster for caching.
 *
 * @param type The instance type to use for the cache nodes.
 * @param count The number of cache nodes to create.
 * @return A TerraformServiceResult with the configuration for the Redis cluster.
 */
@Untested
public fun TerraformNeed<Cache.Settings>.awsElasticacheRedisServerless(
    version: String = "1.6",
    dailySnapshotTime: LocalTime = LocalTime(9, 0),
    maxEcpuPerSecond: Int = 5000,
    maxStorageGb: Int = 10,
    snapshotRetentionLimit: Int = 1,
): TerraformServiceResult<Cache.Settings> = TerraformServiceResult(
    need = this,
    setting = $$"redis://${aws_elasticache_serverless_cache.$${name}.endpoint[0].address}:${aws_elasticache_serverless_cache.$${name}.endpoint[0].port}/0",
    requireProviders = setOf(TerraformProviderImport.aws),
    content = terraformJsonObject {
        "resource.aws_elasticache_serverless_cache.$name" {
            "name"           - "${cloudInfo.projectPrefix}-${name}"
            "engine"               - "redis"
            "cache_usage_limits" {
                "data_storage" {
                    "maximum" - maxStorageGb
                    "unit"    - "GB"
                }
                "ecpu_per_second" {
                    "maximum" - maxEcpuPerSecond
                }
            }
            "daily_snapshot_time"      - dailySnapshotTime.toString()
            "major_engine_version"     - version
            "snapshot_retention_limit" - snapshotRetentionLimit
            "security_group_ids"   - listOf(cloudInfo.applicationVpc!!.securityGroupExpression)
            "subnet_ids"    - (cloudInfo.applicationVpc?.privateSubnetsExpression ?: throw IllegalArgumentException("Must have private subnets if you want to use Redis."))
        }
    }
)
