package com.lightningkite.services.cache.redis

import com.lightningkite.services.Untested
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.terraform.TerraformNeed
import com.lightningkite.services.terraform.TerraformProviderImport
import com.lightningkite.services.terraform.TerraformServiceResult
import com.lightningkite.services.terraform.terraformJsonObject

@Untested
public fun TerraformNeed<Cache.Settings>.awsElasticache(
    type: String = "cache.t2.micro",
    count: Int = 1
): TerraformServiceResult<Cache> = TerraformServiceResult(
    need = this,
    setting = "redis://\${aws_elasticache_cluster.${name}.cluster_address}:6379",
    requireProviders = setOf(TerraformProviderImport.aws),
    content = terraformJsonObject {
        "resource.aws_elasticache_subnet_group.$name" {
            "name"       - "${cloudInfo.projectPrefix}-${name}"
            "subnet_ids" - (cloudInfo.applicationVpc?.privateSubnetsExpression ?: throw IllegalArgumentException("Must have private subnets if you want to use Memcached."))
        }
        "resource.aws_elasticache_cluster.$name" {
            "cluster_id"           - "${cloudInfo.projectPrefix}-${name}"
            "engine"               - "redis"
            "node_type"            - type
            "num_cache_nodes"      - count
            "parameter_group_name" - "default.redis3.2"
            "port"                 - 6379
            "security_group_ids"   - listOf(cloudInfo.applicationVpc!!.securityGroupExpression)
            "subnet_group_name"    - expression("aws_elasticache_subnet_group.${name}.name")
        }
    }
)
