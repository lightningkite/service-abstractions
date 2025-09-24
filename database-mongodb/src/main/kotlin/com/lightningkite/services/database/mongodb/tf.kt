package com.lightningkite.services.database.mongodb

import com.lightningkite.services.Untested
import com.lightningkite.services.database.Database
import com.lightningkite.services.terraform.TerraformEmitterAws
import com.lightningkite.services.terraform.TerraformEmitterAwsVpc
import com.lightningkite.services.terraform.TerraformEmitterKnownIpAddresses
import com.lightningkite.services.terraform.TerraformJsonObject
import com.lightningkite.services.terraform.TerraformNeed
import com.lightningkite.services.terraform.TerraformProvider
import com.lightningkite.services.terraform.TerraformProviderImport
import com.lightningkite.services.terraform.terraformJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Deprecated("Deprecated by Atlas")
@Untested
context(emitter: TerraformEmitterAws) public fun TerraformNeed<Database.Settings>.mongodbAtlasServerless(
    orgId: String,
    continuousBackupEnabled: Boolean,
    existingProjectId: String? = null,
): Unit {
    if(!Database.Settings.supports("mongodb+srv")) throw IllegalArgumentException("You need to reference MongoDatabase in your server definition to use this.")
    emitter.fulfillSetting(
        name, JsonPrimitive(
            value = $$"""
        mongodb+srv://$${emitter.projectPrefix}$$name-main:${random_password.$$name.result}@${replace(resource.mongodbatlas_serverless_instance.$$name.connection_strings_standard_srv, "mongodb+srv://", "")}/default?retryWrites=true&w=majority
    """.trimIndent()
        )
    )
    emptyList<TerraformProvider>().forEach { emitter.require(it) }
    setOf(TerraformProviderImport.mongodbAtlas).forEach { emitter.require(it) }
    emitter.emit(name) {
        val projectName1 = "${emitter.projectPrefix.filter { it.isLetterOrDigit() }}${name}"
        val region1 = emitter.applicationRegion.uppercase().replace("-", "_")
        val projectId1 = if (existingProjectId == null) {
            "resource.mongodbatlas_project.${name}" {
                "name" - "$projectName1"
                "org_id" - orgId
                "is_collect_database_specifics_statistics_enabled" - true
                "is_data_explorer_enabled" - true
                "is_performance_advisor_enabled" - true
                "is_realtime_performance_panel_enabled" - true
                "is_schema_advisor_enabled" - true
            }
            expression("mongodbatlas_project.${name}.id")
        } else existingProjectId
        "resource.random_password.${name}" {
            "length" - 32
            "special" - true
            "override_special" - "-_"
        }
        "resource.mongodbatlas_serverless_instance.${name}" {
            "project_id" - projectId1
            "name" - "$projectName1"

            "provider_settings_backing_provider_name" - "AWS"
            "provider_settings_provider_name" - "SERVERLESS"
            "provider_settings_region_name" - region1

            "continuous_backup_enabled" - continuousBackupEnabled
        }
        "resource.mongodbatlas_database_user.${name}" {
            "username" - "$projectName1-main"
            "password" - expression("random_password.${name}.result")
            "project_id" - expression("mongodbatlas_project.${name}.id")
            "auth_database_name" - "admin"

            "roles" - listOf<JsonObject>(
                terraformJsonObject {
                    "role_name" - "readWrite"
                    "database_name" - "default"
                },
                terraformJsonObject {
                    "role_name" - "readAnyDatabase"
                    "database_name" - "admin"
                }
            )
        }
        (emitter as? TerraformEmitterKnownIpAddresses)?.let<TerraformEmitterKnownIpAddresses, Unit> { emitter ->
            "resource.mongodbatlas_project_ip_access_list.database" {
                "project_id" - projectId1
                "cidr_block" - $$"${element($${emitter.applicationIpAddresses},0)}/32"
                "comment" - "Main Compute"
            }
        } ?: run {
            "resource.mongodbatlas_project_ip_access_list.database" {
                "project_id" - projectId1
                "cidr_block" - "0.0.0.0/0"
                "comment" - "Anywhere"
            }
        }
    }
}

context(emitter: TerraformEmitterAws) public fun TerraformNeed<Database.Settings>.mongodbAtlas(
    orgId: String,
    backupEnabled: Boolean = true,
    zoneName: String? = null,
    minSize: String = "M10",
    maxSize: String = "M40",
    existingProjectId: String? = null,
): Unit {
    if(!Database.Settings.supports("mongodb+srv")) throw IllegalArgumentException("You need to reference MongoDatabase in your server definition to use this.")
    emitter.fulfillSetting(
        this@mongodbAtlas.name, JsonPrimitive(
            value = $$"""
        mongodb+srv://$${emitter.projectPrefix}$$name-main:${random_password.$$name.result}@${replace(mongodbatlas_advanced_cluster.$$name.connection_strings[0].standard_srv, "mongodb+srv://", "")}/default?retryWrites=true&w=majority
    """.trimIndent()
        )
    )
    emptyList<com.lightningkite.services.terraform.TerraformProvider>().forEach { emitter.require(it) }
    setOf(TerraformProviderImport.mongodbAtlas).forEach { emitter.require(it) }
    emitter.emit(this.name) { // MongoDB ATLAS Network Container - View Highlighted section below.
        // MongoDB ATLAS VPC Peer Conf
        // IP Whitelist on ATLAS side
        // # UPDATE - MongoDB ATLAS provider 1.0.0 made mongodbatlas_project_ip_whitelist resource and replaced with mongodbatlas_project_ip_access_list
        // AWS VPC Peer Conf
        // VPC Peer Device to ATLAS Route Table Association on AWS
        val projectName = "${emitter.projectPrefix.filter { it.isLetterOrDigit() }}$name"
        val region = emitter.applicationRegion.uppercase().replace("-", "_")

        val projectId = if (existingProjectId == null) {
            "resource.mongodbatlas_project.$name" {
                "name" - "$projectName"
                "org_id" - orgId
                "is_collect_database_specifics_statistics_enabled" - true
                "is_data_explorer_enabled" - true
                "is_performance_advisor_enabled" - true
                "is_realtime_performance_panel_enabled" - true
                "is_schema_advisor_enabled" - true
            }
            expression("mongodbatlas_project.$name.id")
        } else existingProjectId
        "resource.random_password.${name}" {
            "length" - 32
            "special" - true
            "override_special" - "-_"
        }
        "resource.mongodbatlas_advanced_cluster.$name" {
            "project_id" - projectId
            "name" - "$projectName"
            "cluster_type" - "REPLICASET"

            "backup_enabled" - backupEnabled

            "replication_specs" {
                "zone_name" - zoneName
                "region_configs" {
                    "auto_scaling" {
                        "compute_enabled" - true
                        "compute_min_instance_size" - minSize
                        "compute_max_instance_size" - maxSize
                        "compute_scale_down_enabled" - true
                        "disk_gb_enabled" - true
                    }
                    "electable_specs" {
                        "instance_size" - minSize
                        "node_count" - 3
                    }
                    "analytics_specs" {
                        "instance_size" - minSize
                        "node_count" - 1
                    }
                    "priority" - 7
                    "provider_name" - "AWS"
                    "region_name" - region
                }
            }
        }
        "resource.mongodbatlas_database_user.$name" {
            "username" - "$projectName-main"
            "password" - expression("random_password.$name.result")
            "project_id" - expression("mongodbatlas_project.$name.id")
            "auth_database_name" - "admin"

            "roles" - listOf(
                terraformJsonObject {
                    "role_name" - "readWrite"
                    "database_name" - "default"
                },
                terraformJsonObject {
                    "role_name" - "readAnyDatabase"
                    "database_name" - "admin"
                }
            )
        }
        (emitter as? TerraformEmitterAwsVpc)?.let { emitter ->
            val atlasCidr = "192.168.248.0/21"
            val cidr = emitter.applicationVpc.cidr
            // MongoDB ATLAS Network Container - View Highlighted section below.
            "resource.mongodbatlas_network_container.atlas_network_container" {
                "project_id" - projectId
                "atlas_cidr_block" - atlasCidr
                "provider_name" - "AWS"
                "region_name" - region
            }
            // MongoDB ATLAS VPC Peer Conf
            "data.aws_caller_identity.current" {}
            "resource.mongodbatlas_network_peering.atlas_network_peering" {
                "accepter_region_name" - region.lowercase().replace("_", "-")
                "project_id" - projectId
                "container_id" - expression("mongodbatlas_network_container.atlas_network_container.container_id")
                "provider_name" - "AWS"
                "route_table_cidr_block" - cidr
                "vpc_id" - expression(emitter.applicationVpc.id)
                "aws_account_id" - expression("data.aws_caller_identity.current.account_id")
            }
            // IP Whitelist on ATLAS side
            // # UPDATE - MongoDB ATLAS provider 1.0.0 made mongodbatlas_project_ip_whitelist resource and replaced with mongodbatlas_project_ip_access_list
            "resource.mongodbatlas_project_ip_access_list.atlas_ip_access_list_1" {
                "project_id" - projectId
                "cidr_block" - cidr
                "comment" - "CIDR block for Staging AWS Public Subnet Access for Atlas"
            }
            // AWS VPC Peer Conf
            "resource.aws_vpc_peering_connection_accepter.peer" {
                "vpc_peering_connection_id" - expression("mongodbatlas_network_peering.atlas_network_peering.connection_id")
                "auto_accept" - true
            }
            "data.aws_vpc_peering_connection.vpc_peering_conn_ds" {
                "vpc_id" - expression("mongodbatlas_network_peering.atlas_network_peering.atlas_vpc_name")
                "cidr_block" - atlasCidr
                "peer_region" - region.lowercase().replace("_", "-")
            }
            "data.aws_route_table.application_subnets_route_table" {
                "subnet_id" - expression("element(${emitter.applicationVpc.applicationSubnets}, 0)")
            }
            // VPC Peer Device to ATLAS Route Table Association on AWS
            "resource.aws_route.aws_peer_to_atlas_route_1" {
                "route_table_id" - expression("data.aws_route_table.application_subnets_route_table.id")
                "destination_cidr_block" - atlasCidr
                "vpc_peering_connection_id" - expression("data.aws_vpc_peering_connection.vpc_peering_conn_ds.id")
            }
        } ?: (emitter as? TerraformEmitterKnownIpAddresses)?.let { emitter ->
            "resource.mongodbatlas_project_ip_access_list.database" {
                "project_id" - projectId
                "cidr_block" - $$"${element($${emitter.applicationIpAddresses},0)}/32"
                "comment" - "Main Compute"
            }
        } ?: run {
            "resource.mongodbatlas_project_ip_access_list.database" {
                "project_id" - projectId
                "cidr_block" - "0.0.0.0/0"
                "comment" - "Anywhere"
            }
        }
    }
}

context(emitter: TerraformEmitterAws) public fun TerraformNeed<Database.Settings>.mongodbAtlasFree(
    orgId: String,
    zoneName: String? = null,
    existingProjectId: String? = null,
): Unit {
    if(!Database.Settings.supports("mongodb+srv")) throw IllegalArgumentException("You need to reference MongoDatabase in your server definition to use this.")
    emitter.fulfillSetting(
        this@mongodbAtlasFree.name, JsonPrimitive(
            value = $$"""
        mongodb+srv://$${emitter.projectPrefix}$$name-main:${random_password.$$name.result}@${replace(mongodbatlas_advanced_cluster.$$name.connection_strings[0].standard_srv, "mongodb+srv://", "")}/default?retryWrites=true&w=majority
    """.trimIndent()
        )
    )
    emptyList<com.lightningkite.services.terraform.TerraformProvider>().forEach { emitter.require(it) }
    setOf(TerraformProviderImport.mongodbAtlas).forEach { emitter.require(it) }
    emitter.emit(this.name) { // MongoDB ATLAS Network Container - View Highlighted section below.
        // MongoDB ATLAS VPC Peer Conf
        // IP Whitelist on ATLAS side
        // # UPDATE - MongoDB ATLAS provider 1.0.0 made mongodbatlas_project_ip_whitelist resource and replaced with mongodbatlas_project_ip_access_list
        // AWS VPC Peer Conf
        // VPC Peer Device to ATLAS Route Table Association on AWS
        val projectName = "${emitter.projectPrefix.filter { it.isLetterOrDigit() }}$name"
        val region = emitter.applicationRegion.uppercase().replace("-", "_")

        val projectId = if (existingProjectId == null) {
            "resource.mongodbatlas_project.$name" {
                "name" - "$projectName"
                "org_id" - orgId
                "is_collect_database_specifics_statistics_enabled" - true
                "is_data_explorer_enabled" - true
                "is_performance_advisor_enabled" - true
                "is_realtime_performance_panel_enabled" - true
                "is_schema_advisor_enabled" - true
            }
            expression("mongodbatlas_project.$name.id")
        } else existingProjectId
        "resource.random_password.${name}" {
            "length" - 32
            "special" - true
            "override_special" - "-_"
        }
        "resource.mongodbatlas_advanced_cluster.$name" {
            "project_id" - projectId
            "name" - "$projectName"
            "cluster_type" - "REPLICASET"

            "replication_specs" {
                "zone_name" - zoneName
                "region_configs" {
                    "electable_specs" {
                        "instance_size" - "M0"
                    }
                    "priority" - 7
                    "provider_name" - "TENANT"
                    "backing_provider_name" - "AWS"
                    "region_name" - region
                }
            }
        }
        "resource.mongodbatlas_database_user.$name" {
            "username" - "$projectName-main"
            "password" - expression("random_password.$name.result")
            "project_id" - expression("mongodbatlas_project.$name.id")
            "auth_database_name" - "admin"

            "roles" - listOf(
                terraformJsonObject {
                    "role_name" - "readWrite"
                    "database_name" - "default"
                },
                terraformJsonObject {
                    "role_name" - "readAnyDatabase"
                    "database_name" - "admin"
                }
            )
        }
        (emitter as? TerraformEmitterKnownIpAddresses)?.let { emitter ->
            "resource.mongodbatlas_project_ip_access_list.database" {
                "project_id" - projectId
                "cidr_block" - $$"${element($${emitter.applicationIpAddresses},0)}/32"
                "comment" - "Main Compute"
            }
        } ?: run {
            "resource.mongodbatlas_project_ip_access_list.database" {
                "project_id" - projectId
                "cidr_block" - "0.0.0.0/0"
                "comment" - "Anywhere"
            }
        }
    }
}


@Untested
context(emitter: TerraformEmitterAws) public fun TerraformNeed<Database.Settings>.mongodbAtlasFlex(
    orgId: String,
    backupEnabled: Boolean = true,
    zoneName: String? = null,
    existingProjectId: String? = null,
): Unit {
    if(!Database.Settings.supports("mongodb+srv")) throw IllegalArgumentException("You need to reference MongoDatabase in your server definition to use this.")
    emitter.fulfillSetting(
        name, JsonPrimitive(
            value = $$"""
        mongodb+srv://$${emitter.projectPrefix}$$name-main:${random_password.$$name.result}@${replace(mongodbatlas_advanced_cluster.$$name.connection_strings[0].standard_srv, "mongodb+srv://", "")}/default?retryWrites=true&w=majority
    """.trimIndent()
        )
    )
    emptyList<TerraformProvider>().forEach { emitter.require(it) }
    setOf(TerraformProviderImport.mongodbAtlas).forEach { emitter.require(it) }
    emitter.emit(name) {
        val projectName1 = "${emitter.projectPrefix.filter { it.isLetterOrDigit() }}${name}"
        val region1 = emitter.applicationRegion.uppercase().replace("-", "_")
        val projectId1 = if (existingProjectId == null) {
            "resource.mongodbatlas_project.${name}" {
                "name" - "$projectName1"
                "org_id" - orgId
                "is_collect_database_specifics_statistics_enabled" - true
                "is_data_explorer_enabled" - true
                "is_performance_advisor_enabled" - true
                "is_realtime_performance_panel_enabled" - true
                "is_schema_advisor_enabled" - true
            }
            expression("mongodbatlas_project.${name}.id")
        } else existingProjectId
        "resource.random_password.${name}" {
            "length" - 32
            "special" - true
            "override_special" - "-_"
        }
        "resource.mongodbatlas_advanced_cluster.${name}" {
            "project_id" - projectId1
            "name" - "$projectName1"
            "cluster_type" - "REPLICASET"

            "backup_enabled" - backupEnabled

            "replication_specs" {
                "zone_name" - zoneName
                "region_configs" {
                    "provider_name" - "FLEX"
                    "backing_provider_name" - "AWS"
                    "region_name" - region1
                    "priority" - 7
                }
            }
        }
        "resource.mongodbatlas_database_user.${name}" {
            "username" - "$projectName1-main"
            "password" - expression("random_password.${name}.result")
            "project_id" - expression("mongodbatlas_project.${name}.id")
            "auth_database_name" - "admin"

            "roles" - listOf<JsonObject>(
                terraformJsonObject {
                    "role_name" - "readWrite"
                    "database_name" - "default"
                },
                terraformJsonObject {
                    "role_name" - "readAnyDatabase"
                    "database_name" - "admin"
                }
            )
        }
        (emitter as? TerraformEmitterKnownIpAddresses)?.let<TerraformEmitterKnownIpAddresses, Unit> { emitter ->
            "resource.mongodbatlas_project_ip_access_list.database" {
                "project_id" - projectId1
                "cidr_block" - $$"${element($${emitter.applicationIpAddresses},0)}/32"
                "comment" - "Main Compute"
            }
        } ?: run {
            "resource.mongodbatlas_project_ip_access_list.database" {
                "project_id" - projectId1
                "cidr_block" - "0.0.0.0/0"
                "comment" - "Anywhere"
            }
        }
    }
}
