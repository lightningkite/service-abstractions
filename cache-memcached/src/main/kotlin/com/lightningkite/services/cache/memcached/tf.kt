package com.lightningkite.services.cache.memcached

import com.lightningkite.services.cache.Cache
import com.lightningkite.services.terraform.TerraformNeed
import com.lightningkite.services.terraform.TerraformProviderImport
import com.lightningkite.services.terraform.TerraformServiceResult
import com.lightningkite.services.terraform.terraformJsonObject

/**
 * Creates an AWS ElastiCache Memcached cluster for caching.
 *
 * @param type The instance type to use for the cache nodes.
 * @param count The number of cache nodes to create.
 * @return A TerraformServiceResult with the configuration for the Memcached cluster.
 */
public fun TerraformNeed<Cache.Settings>.awsElasticacheMemcached(
    type: String = "cache.t2.micro",
    count: Int = 1
): TerraformServiceResult<Cache> = TerraformServiceResult(
    need = this,
    setting = "memcached://\${aws_elasticache_cluster.${name}.configuration_endpoint}",
    requireProviders = setOf(TerraformProviderImport.aws),
    content = terraformJsonObject {
        "resource.aws_elasticache_subnet_group.$name" {
            "name"       - "${cloudInfo.projectPrefix}-${name}"
            "subnet_ids" - (cloudInfo.applicationVpc?.privateSubnetsExpression ?: throw IllegalArgumentException("Must have private subnets if you want to use Memcached."))
        }
        "resource.aws_elasticache_cluster.$name" {
            "cluster_id"           - "${cloudInfo.projectPrefix}-${name}"
            "engine"               - "memcached"
            "node_type"            - type
            "num_cache_nodes"      - count
            "parameter_group_name" - "default.memcached1.6"
            "port"                 - 11211
            "security_group_ids"   - listOf(cloudInfo.applicationVpc!!.securityGroupExpression)
            "subnet_group_name"    - "\${aws_elasticache_subnet_group.${name}.name}"
        }
    }
)