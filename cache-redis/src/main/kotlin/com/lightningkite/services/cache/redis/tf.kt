package com.lightningkite.services.cache.redis

import com.lightningkite.services.Untested
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.terraform.TerraformNeed
import com.lightningkite.services.terraform.TerraformServiceResult
import com.lightningkite.services.terraform.terraformJsonObject

@Untested
public fun TerraformNeed<Cache>.awsElasticache(
    type: String = "cache.t2.micro",
    count: Int = 1
): TerraformServiceResult<Cache> = TerraformServiceResult(
    need = this,
    terraformExpression = "redis://\${aws_elasticache_cluster.${name}.cluster_address}:6379",
    out = terraformJsonObject {
        "resource.aws_elasticache_subnet_group.$name" {
            "name"       - "${cloudInfo.projectPrefix}-${name}"
            "subnet_ids" - (cloudInfo.applicationVpcPrivateSubnetsExpression ?: throw IllegalArgumentException("Must have private subnets if you want to use Redis."))
        }
        "resource.aws_elasticache_cluster.$name" {
            "cluster_id"           - "${cloudInfo.projectPrefix}-${name}"
            "engine"               - "redis"
            "node_type"            - type
            "num_cache_nodes"      - count
            "parameter_group_name" - "default.redis3.2"
            "port"                 - 6379
            "security_group_ids"   - listOf(cloudInfo.applicationVpcSecurityGroupExpression!!)
            "subnet_group_name"    - tfExpression("aws_elasticache_subnet_group.${name}.name")
        }
    }
)
