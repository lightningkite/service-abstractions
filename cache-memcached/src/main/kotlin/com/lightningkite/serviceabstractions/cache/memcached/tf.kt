package com.lightningkite.serviceabstractions.cache.memcached

import com.lightningkite.serviceabstractions.Untested
import com.lightningkite.serviceabstractions.cache.Cache
import com.lightningkite.serviceabstractions.terraform.TerraformJsonObject
import com.lightningkite.serviceabstractions.terraform.TerraformNeed
import com.lightningkite.serviceabstractions.terraform.TerraformServiceResult
import com.lightningkite.serviceabstractions.terraform.terraformJsonObject

/**
 * Creates an AWS ElastiCache Memcached cluster for caching.
 *
 * @param type The instance type to use for the cache nodes.
 * @param count The number of cache nodes to create.
 * @return A TerraformServiceResult with the configuration for the Memcached cluster.
 */
@Untested
public fun TerraformNeed<Cache>.awsElasticacheMemcached(
    type: String = "cache.t2.micro",
    count: Int = 1
): TerraformServiceResult<Cache> = TerraformServiceResult(
    need = this,
    terraformExpression = "memcached://\${aws_elasticache_cluster.${name}.configuration_endpoint}",
    out = terraformJsonObject {
        "resource.aws_elasticache_subnet_group.$name" {
            "name"       - "${cloudInfo.projectPrefix}-${name}"
            "subnet_ids" - (cloudInfo.applicationVpcPrivateSubnetsExpression ?: throw IllegalArgumentException("Must have private subnets if you want to use Memcached."))
        }
        "resource.aws_elasticache_cluster.$name" {
            "cluster_id"           - "${cloudInfo.projectPrefix}-${name}"
            "engine"               - "memcached"
            "node_type"            - type
            "num_cache_nodes"      - count
            "parameter_group_name" - "default.memcached1.6"
            "port"                 - 11211
            "security_group_ids"   - listOf(cloudInfo.applicationVpcSecurityGroupExpression!!)
            "subnet_group_name"    - "\${aws_elasticache_subnet_group.${name}.name}"
        }
    }
)