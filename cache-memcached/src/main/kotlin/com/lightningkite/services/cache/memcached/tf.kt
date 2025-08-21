package com.lightningkite.services.cache.memcached

import com.lightningkite.services.Untested
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.terraform.TerraformEmitterAws
import com.lightningkite.services.terraform.TerraformEmitterAwsVpc
import com.lightningkite.services.terraform.TerraformNeed
import com.lightningkite.services.terraform.TerraformProviderImport
import com.lightningkite.services.terraform.oldStyle
import com.lightningkite.services.terraform.terraformJsonObject
import kotlinx.datetime.LocalTime

/**
 * Creates an AWS ElastiCache Memcached cluster for caching.
 *
 * @param type The instance type to use for the cache nodes.
 * @param count The number of cache nodes to create.
 * @return A TerraformServiceResult with the configuration for the Memcached cluster.
 */
@Untested
context(emitter: TerraformEmitterAwsVpc) public fun TerraformNeed<Cache.Settings>.awsElasticacheMemcached(
    type: String = "cache.t2.micro",
    parameterGroupName: String = "default.memcached1.6",
    count: Int = 1
): Unit = oldStyle(
    need = this,
    setting = "memcached-aws://\${aws_elasticache_cluster.${name}.configuration_endpoint}",
    requireProviders = setOf(TerraformProviderImport.aws),
    content = {
        "resource.aws_elasticache_subnet_group.$name" {
            "name"       - "${emitter.projectPrefix}-${name}"
            "subnet_ids" - emitter.applicationPrivateSubnetsExpression
        }
        "resource.aws_elasticache_cluster.$name" {
            "cluster_id"           - "${emitter.projectPrefix}-${name}"
            "engine"               - "memcached"
            "node_type"            - type
            "num_cache_nodes"      - count
            "parameter_group_name" - parameterGroupName
            "port"                 - 11211
            "security_group_ids"   - listOf(emitter.applicationSecurityGroupExpression)
            "subnet_group_name"    - "\${aws_elasticache_subnet_group.${name}.name}"
        }
    }
)

/**
 * Creates an AWS ElastiCache Memcached cluster for caching.
 *
 * @param type The instance type to use for the cache nodes.
 * @param count The number of cache nodes to create.
 * @return A TerraformServiceResult with the configuration for the Memcached cluster.
 */
@Untested
context(emitter: TerraformEmitterAwsVpc) public fun TerraformNeed<Cache.Settings>.awsElasticacheMemcachedServerless(
    version: String = "1.6",
    dailySnapshotTime: LocalTime = LocalTime(9, 0),
    maxEcpuPerSecond: Int = 5000,
    maxStorageGb: Int = 10,
    snapshotRetentionLimit: Int = 1,
): Unit = oldStyle(
    need = this,
    setting = $$"memcached-aws://${aws_elasticache_serverless_cache.$${name}.endpoint[0].address}:${aws_elasticache_serverless_cache.$${name}.endpoint[0].port}",
    requireProviders = setOf(TerraformProviderImport.aws),
    content = {
        "resource.aws_elasticache_serverless_cache.$name" {
            "name"           - "${emitter.projectPrefix}-${name}"
            "engine"               - "memcached"
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
            "security_group_ids"   - listOf(emitter.applicationSecurityGroupExpression)
            "subnet_ids"    - emitter.applicationPrivateSubnetsExpression
        }
    }
)