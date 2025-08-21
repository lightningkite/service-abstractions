package com.lightningkite.services.database.mongodb

import com.lightningkite.services.Untested
import com.lightningkite.services.database.Database
import com.lightningkite.services.terraform.TerraformEmitterAws
import com.lightningkite.services.terraform.TerraformNeed
import com.lightningkite.services.terraform.TerraformProviderImport
import com.lightningkite.services.terraform.oldStyle
import com.lightningkite.services.terraform.terraformJsonObject

@Deprecated("Deprecated by Atlas")
@Untested
context(emitter: TerraformEmitterAws) public fun TerraformNeed<Database.Settings>.mongodbAtlasServerless(
    orgId: String,
    continuousBackupEnabled: Boolean,
    existingProjectId: String? = null,
): Unit = oldStyle(
    need = this,
    setting = $$"""
        mongodb+srv://$${emitter.projectPrefix}$$name-main:${random_password.$$name.result}@${replace(resource.mongodbatlas_serverless_instance.$$name.connection_strings_standard_srv, "mongodb+srv://", "")}/default?retryWrites=true&w=majority
    """.trimIndent(),
    requireProviders = setOf(TerraformProviderImport.mongodbAtlas),
    content = {

        if (existingProjectId == null) {
            "resource.mongodbatlas_project.$name" {
                "name" - "${emitter.projectPrefix}$name"
                "org_id" - orgId
                "is_collect_database_specifics_statistics_enabled" - true
                "is_data_explorer_enabled" - true
                "is_performance_advisor_enabled" - true
                "is_realtime_performance_panel_enabled" - true
                "is_schema_advisor_enabled" - true
            }
        }
        "resource.random_password.${name}" {
            "length" - 32
            "special" - true
            "override_special" - "-_"
        }
        "resource.mongodbatlas_serverless_instance.$name" {
            "project_id" - (existingProjectId ?: expression("mongodbatlas_project.$name.id"))
            "name" - "${emitter.projectPrefix}$name"

            "provider_settings_backing_provider_name" - "AWS"
            "provider_settings_provider_name" - "SERVERLESS"
            "provider_settings_region_name" - emitter.applicationRegion.uppercase().replace("-", "_")

            "continuous_backup_enabled" - continuousBackupEnabled
        }
        "resource.mongodbatlas_database_user.$name" {
            "username" - "\"${emitter.projectPrefix}$name-main"
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
    }
)


@Untested
context(emitter: TerraformEmitterAws) public fun TerraformNeed<Database.Settings>.mongodbAtlas(
    orgId: String,
    backupEnabled: Boolean = true,
    zoneName: String? = null,
    maxSize: String = "M10",
    minSize: String = "M40",
    existingProjectId: String? = null,
): Unit = oldStyle(
    need = this,
    setting = $$"""
        mongodb+srv://$${emitter.projectPrefix}$$name-main:${random_password.$$name.result}@${replace(mongodbatlas_advanced_cluster.$$name.connection_strings[0].standard_srv, "mongodb+srv://", "")}/default?retryWrites=true&w=majority
    """.trimIndent(),
    requireProviders = setOf(TerraformProviderImport.mongodbAtlas),
    content = {

        if (existingProjectId == null) {
            "resource.mongodbatlas_project.$name" {
                "name" - "${emitter.projectPrefix}$name"
                "org_id" - orgId
                "is_collect_database_specifics_statistics_enabled" - true
                "is_data_explorer_enabled" - true
                "is_performance_advisor_enabled" - true
                "is_realtime_performance_panel_enabled" - true
                "is_schema_advisor_enabled" - true
            }
        }
        "resource.random_password.${name}" {
            "length" - 32
            "special" - true
            "override_special" - "-_"
        }
        "resource.mongodbatlas_advanced_cluster.$name" {
            "project_id" - (existingProjectId ?: expression("mongodbatlas_project.$name.id"))
            "name" - "${emitter.projectPrefix}$name"
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
                    "region_name" - emitter.applicationRegion.uppercase()
                        .replace("-", "_")
                }
            }
        }
        "resource.mongodbatlas_database_user.$name" {
            "username" - "\"${emitter.projectPrefix}$name-main"
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
    }
)


@Untested
context(emitter: TerraformEmitterAws) public fun TerraformNeed<Database.Settings>.mongodbFlex(
    orgId: String,
    backupEnabled: Boolean = true,
    zoneName: String? = null,
    existingProjectId: String? = null,
): Unit = oldStyle(
    need = this,
    setting = $$"""
        mongodb+srv://$${emitter.projectPrefix}$$name-main:${random_password.$$name.result}@${replace(mongodbatlas_advanced_cluster.$$name.connection_strings[0].standard_srv, "mongodb+srv://", "")}/default?retryWrites=true&w=majority
    """.trimIndent(),
    requireProviders = setOf(TerraformProviderImport.mongodbAtlas),
    content = {

        if (existingProjectId == null) {
            "resource.mongodbatlas_project.$name" {
                "name" - "${emitter.projectPrefix}$name"
                "org_id" - orgId
                "is_collect_database_specifics_statistics_enabled" - true
                "is_data_explorer_enabled" - true
                "is_performance_advisor_enabled" - true
                "is_realtime_performance_panel_enabled" - true
                "is_schema_advisor_enabled" - true
            }
        }
        "resource.random_password.${name}" {
            "length" - 32
            "special" - true
            "override_special" - "-_"
        }
        "resource.mongodbatlas_advanced_cluster.${name}" {
            "project_id" - (existingProjectId ?: expression("mongodbatlas_project.$name.id"))
            "name" - "${emitter.projectPrefix}$name"
            "cluster_type" - "REPLICASET"

            "backup_enabled" - backupEnabled

            "replication_specs" {
                "zone_name" - zoneName
                "region_configs" {
                    "provider_name" - "FLEX"
                    "backing_provider_name" - "AWS"
                    "region_name" - emitter.applicationRegion.uppercase().replace("-", "_")
                    "priority" - 7
                }
            }
        }
        "resource.mongodbatlas_database_user.$name" {
            "username" - "\"${emitter.projectPrefix}$name-main"
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
    }
)
