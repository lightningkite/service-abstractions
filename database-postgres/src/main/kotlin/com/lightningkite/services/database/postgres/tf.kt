package com.lightningkite.services.database.postgres

import com.lightningkite.services.Untested
import com.lightningkite.services.database.Database
import com.lightningkite.services.terraform.TerraformNeed
import com.lightningkite.services.terraform.TerraformProviderImport
import com.lightningkite.services.terraform.TerraformServiceResult
import com.lightningkite.services.terraform.terraformJsonObject

/**
 * Creates an AWS Aurora Serverless V2 PostgreSQL cluster for database operations.
 *
 * @param minCapacity The minimum capacity in Aurora Capacity Units (ACUs) for the database.
 * @param maxCapacity The maximum capacity in Aurora Capacity Units (ACUs) for the database.
 * @param autoPause Whether to enable auto-pausing for the database.
 * @return A TerraformServiceResult with the configuration for the Aurora PostgreSQL cluster.
 */
@Untested
public fun TerraformNeed<Database.Settings>.awsAuroraServerlessV2(
    minCapacity: Double = 0.5,
    maxCapacity: Double = 2.0,
    autoPause: Boolean = true,
): TerraformServiceResult<Database.Settings> = TerraformServiceResult(
    need = this,
    setting = $$"""
        postgresql://master:${random_password.$$name.result}@${aws_rds_cluster.$$name.endpoint}/$$name
    """.trimIndent(),
    requireProviders = setOf(TerraformProviderImport.aws),
    content = terraformJsonObject {
        if (cloudInfo.applicationVpc != null) {
            "resource.aws_db_subnet_group.$name" {
                "name" - "${cloudInfo.projectPrefix}-${name}"
                "subnet_ids" - (cloudInfo.applicationVpc?.privateSubnetsExpression ?: throw IllegalArgumentException("Must have private subnets if you want to use Aurora."))
            }
        }
        
        "resource.random_password.${name}" {
            "length" - 32
            "special" - true
            "override_special" - "-_"
        }
        
        "resource.aws_rds_cluster.$name" {
            "cluster_identifier" - "${cloudInfo.projectPrefix}-${name}"
            "engine" - "aurora-postgresql"
            "engine_mode" - "provisioned"
            "engine_version" - "13.6"
            "database_name" - "${name}"
            "master_username" - "master"
            "master_password" - expression("random_password.${name}.result")
            "skip_final_snapshot" - true
            "final_snapshot_identifier" - "${cloudInfo.projectPrefix}-${name}"
            
            if (cloudInfo.applicationVpc != null) {
                "vpc_security_group_ids" - listOf(cloudInfo.applicationVpc!!.securityGroupExpression)
                "db_subnet_group_name" - expression("aws_db_subnet_group.${name}.name")
            }
            
            "serverlessv2_scaling_configuration" {
                "min_capacity" - minCapacity
                "max_capacity" - maxCapacity
            }
        }
        
        "resource.aws_rds_cluster_instance.$name" {
            "publicly_accessible" - (cloudInfo.applicationVpc == null)
            "cluster_identifier" - expression("aws_rds_cluster.${name}.id")
            "instance_class" - "db.serverless"
            "engine" - expression("aws_rds_cluster.${name}.engine")
            "engine_version" - expression("aws_rds_cluster.${name}.engine_version")
            
            if (cloudInfo.applicationVpc != null) {
                "db_subnet_group_name" - expression("aws_db_subnet_group.${name}.name")
            }
        }
    }
)

/**
 * Creates an AWS Aurora Serverless V1 PostgreSQL cluster for database operations.
 *
 * @param minCapacity The minimum capacity in Aurora Capacity Units (ACUs) for the database.
 * @param maxCapacity The maximum capacity in Aurora Capacity Units (ACUs) for the database.
 * @param autoPause Whether to enable auto-pausing for the database.
 * @return A TerraformServiceResult with the configuration for the Aurora PostgreSQL cluster.
 */
@Untested
public fun TerraformNeed<Database.Settings>.awsAuroraServerlessV1(
    minCapacity: Int = 2,
    maxCapacity: Int = 4,
    autoPause: Boolean = true,
): TerraformServiceResult<Database.Settings> = TerraformServiceResult(
    need = this,
    setting = $$"""
        postgresql://master:${random_password.$$name.result}@${aws_rds_cluster.$$name.endpoint}/$$name
    """.trimIndent(),
    requireProviders = setOf(TerraformProviderImport.aws),
    content = terraformJsonObject {
        if (cloudInfo.applicationVpc != null) {
            "resource.aws_db_subnet_group.$name" {
                "name" - "${cloudInfo.projectPrefix}-${name}"
                "subnet_ids" - (cloudInfo.applicationVpc?.privateSubnetsExpression ?: throw IllegalArgumentException("Must have private subnets if you want to use Aurora."))
            }
        }
        
        "resource.random_password.${name}" {
            "length" - 32
            "special" - true
            "override_special" - "-_"
        }
        
        "resource.aws_rds_cluster.$name" {
            "cluster_identifier" - "${cloudInfo.projectPrefix}-${name}"
            "engine" - "aurora-postgresql"
            "engine_mode" - "serverless"
            "engine_version" - "10.18"
            "database_name" - "${name}"
            "master_username" - "master"
            "master_password" - expression("random_password.${name}.result")
            "skip_final_snapshot" - true
            "final_snapshot_identifier" - "${cloudInfo.projectPrefix}-${name}"
            "enable_http_endpoint" - true
            
            if (cloudInfo.applicationVpc != null) {
                "vpc_security_group_ids" - listOf(cloudInfo.applicationVpc!!.securityGroupExpression)
                "db_subnet_group_name" - expression("aws_db_subnet_group.${name}.name")
            }
            
            "scaling_configuration" {
                "auto_pause" - autoPause
                "min_capacity" - minCapacity
                "max_capacity" - maxCapacity
                "seconds_until_auto_pause" - 300
                "timeout_action" - "ForceApplyCapacityChange"
            }
        }
    }
)