package com.lightningkite.services.database.mongodb

import com.lightningkite.services.database.Database
import com.lightningkite.services.terraform.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

public data class MongoAutoScale(
    val minSize: String = "M10",
    val maxSize: String = "M40",
)

public enum class ElectableNodeCount(internal val count: Int){
    `3`(3),
    `5`(5),
    `7`(7)
}

context(emitter: TerraformEmitterAws)
public fun TerraformNeed<Database.Settings>.mongodbAtlas(
    orgId: String,
    backupEnabled: Boolean = true,
    atlasSearch: Boolean = true,
    zoneName: String? = null,
    instanceSize: String = "M10",
    autoScale: MongoAutoScale? = null,
    electableNodeCount: ElectableNodeCount = ElectableNodeCount.`3`,
    analyticNodeCount: Int = 1,
    existingProjectId: String? = null,
): Unit {
    if (!Database.Settings.supports("mongodb+srv")) throw IllegalArgumentException("You need to reference MongoDatabase in your server definition to use this.")
    val projectName = "${emitter.projectPrefix.filter { it.isLetterOrDigit() }}$name"
    val userName = "$projectName-main"
    emitter.fulfillSetting(
        this@mongodbAtlas.name, JsonPrimitive(
            value = $$"""
        mongodb+srv://$$userName:${random_password.$$name.result}@${replace(mongodbatlas_advanced_cluster.$$name.connection_strings.standard_srv, "mongodb+srv://", "")}/default?retryWrites=true&w=majority
    """.trimIndent() + (if (atlasSearch) "&atlasSearch=true" else "")
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
        val region = emitter.applicationRegion.uppercase().replace("-", "_")

        val projectId = if (existingProjectId == null) {
            "resource.mongodbatlas_project.$name" {
                "name" - projectName
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
            "name" - projectName
            "cluster_type" - "REPLICASET"
            "backup_enabled" - backupEnabled
            if (autoScale != null)
                "use_effective_fields" - true

            "replication_specs" - listOf(
                terraformJsonObject {
                    "zone_name" - zoneName
                    "region_configs" - listOf(
                        terraformJsonObject {
                            if (autoScale != null) {
                                "auto_scaling" {
                                    "compute_enabled" - true
                                    "compute_min_instance_size" - autoScale.minSize
                                    "compute_max_instance_size" - autoScale.maxSize
                                    "compute_scale_down_enabled" - true
                                    "disk_gb_enabled" - true
                                }
                            }
                            "electable_specs" {
                                "instance_size" - instanceSize
                                "node_count" - electableNodeCount.count
                            }
                            if (analyticNodeCount > 0)
                                "analytics_specs" {
                                    "instance_size" - instanceSize
                                    "node_count" - analyticNodeCount
                                }
                            "priority" - 7
                            "provider_name" - "AWS"
                            "region_name" - region
                        }
                    )
                }
            )
        }
        "resource.mongodbatlas_database_user.$name" {
            "username" - userName
            "password" - expression("random_password.$name.result")
            "project_id" - projectId
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

        (emitter.applicationVpc as? AwsVpc.VpcInfo)?.also { vpcInfo ->
            val cidr = vpcInfo.cidr
            // MongoDB ATLAS VPC Peer Conf
            "data.aws_caller_identity.${this@mongodbAtlas.name}_current" {}
            "resource.mongodbatlas_network_peering.atlas_network_peering" {
                "accepter_region_name" - region.lowercase().replace("_", "-")
                "project_id" - projectId
                "container_id" - expression("one(values(mongodbatlas_advanced_cluster.${name}.replication_specs[0].container_id))")
                "provider_name" - "AWS"
                "route_table_cidr_block" - cidr
                "vpc_id" - vpcInfo.id
                "aws_account_id" - expression("data.aws_caller_identity.${this@mongodbAtlas.name}_current.account_id")
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
            "data.aws_route_table.application_subnets_route_table" {
                "subnet_id" - vpcInfo.applicationSubnet
            }
            // VPC Peer Device to ATLAS Route Table Association on AWS
            "resource.aws_route.aws_peer_to_atlas_route_1" {
                "route_table_id" - expression("data.aws_route_table.application_subnets_route_table.id")
                "destination_cidr_block" - expression("mongodbatlas_network_peering.atlas_network_peering.atlas_cidr_block")
                "vpc_peering_connection_id" - expression("aws_vpc_peering_connection_accepter.peer.id")
            }
            "resource.aws_vpc_security_group_egress_rule" {
                "atlas" {
                    "cidr_ipv4" - expression("mongodbatlas_network_peering.atlas_network_peering.atlas_cidr_block")
                    "security_group_id" - vpcInfo.securityGroup
                    "ip_protocol" - "tcp"
                    "from_port" - 27015
                    "to_port" - 27017
                }
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

context(emitter: TerraformEmitterAws)
public fun TerraformNeed<Database.Settings>.mongodbAtlasFree(
    orgId: String,
    atlasSearch: Boolean = true,
    zoneName: String? = null,
    existingProjectId: String? = null,
): Unit {
    if (!Database.Settings.supports("mongodb+srv")) throw IllegalArgumentException("You need to reference MongoDatabase in your server definition to use this.")
    val projectName = "${emitter.projectPrefix.filter { it.isLetterOrDigit() }}$name"
    val userName = "$projectName-main"
    emitter.fulfillSetting(
        this@mongodbAtlasFree.name, JsonPrimitive(
            value = $$"""
        mongodb+srv://$$userName:${random_password.$$name.result}@${replace(mongodbatlas_advanced_cluster.$$name.connection_strings.standard_srv, "mongodb+srv://", "")}/default?retryWrites=true&w=majority
    """.trimIndent() + (if (atlasSearch) "&atlasSearch=true" else "")
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
                "name" - projectName
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
            "name" - projectName
            "cluster_type" - "REPLICASET"

            "replication_specs" - listOf(
                terraformJsonObject {
                    "zone_name" - zoneName
                    "region_configs" - listOf(
                        terraformJsonObject {
                            "electable_specs" {
                                "instance_size" - "M0"
                            }
                            "priority" - 7
                            "provider_name" - "TENANT"
                            "backing_provider_name" - "AWS"
                            "region_name" - region
                        }
                    )
                }
            )
        }
        "resource.mongodbatlas_database_user.$name" {
            "username" - userName
            "password" - expression("random_password.$name.result")
            "project_id" - projectId
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

/**
 * Creates an AWS DocumentDB cluster as a MongoDB-compatible alternative to MongoDB Atlas.
 *
 * DocumentDB is an AWS-native service with MongoDB 5.0 API compatibility. It runs entirely
 * within your VPC — a VPC with subnet information ([AwsVpc.VpcInfo]) is required.
 *
 * ## Compatibility notes
 * - Atlas Search and $vectorSearch are not supported; `atlasSearch` is forced false.
 * - DocumentDB handles TLS internally; the generated connection string includes `tls=true`
 *   when [tls] is enabled. Your application must trust the Amazon root CA, which is bundled
 *   in the Amazon DocumentDB CA certificate (global-bundle.pem). Download it once and embed
 *   it in your container image or Lambda layer, then set `tlsCAFile` in your connection URL
 *   at runtime if needed.
 *
 * @param kmsKey How the cluster's storage-encryption key is chosen. Required (no default) because the key is
 *   fixed at creation — changing it later replaces the cluster — so it must be a deliberate choice and is
 *   intentionally NOT taken from the emitter's shared default. Use [KmsKeySource.AwsManaged] for the AWS-managed
 *   key (still encrypted at rest) or [KmsKeySource.CreateDedicated] for a dedicated customer-managed key.
 * @param instanceClass EC2 instance class for cluster nodes (e.g. "db.t3.medium", "db.r6g.large").
 * @param instanceCount Number of instances in the cluster. Use ≥ 2 for production HA.
 * @param engineVersion DocumentDB engine version ("5.0", "4.0", or "3.6").
 * @param backupRetentionPeriod Number of days to retain automated backups (1–35).
 * @param tls Whether to require TLS for connections (DocumentDB default is enabled).
 * @param skipFinalSnapshot Whether to skip the final snapshot on destroy. `true` eases teardown but loses data
 *   on destroy; production should leave this `false`.
 * @param deletionProtection Whether to block deletion of the cluster until manually disabled.
 */
context(emitter: TerraformEmitterAws)
public fun TerraformNeed<Database.Settings>.awsDocumentDb(
    kmsKey: KmsKeySource,
    instanceClass: String = "db.t3.medium",
    instanceCount: Int = 1,
    engineVersion: String = "5.0",
    backupRetentionPeriod: Int = 1,
    tls: Boolean = true,
    skipFinalSnapshot: Boolean = false,
    deletionProtection: Boolean = false,
): Unit {
    if (!Database.Settings.supports("mongodb")) throw IllegalArgumentException("You need to reference MongoDatabase in your server definition to use this.")
    val vpcInfo = emitter.applicationVpc as? AwsVpc.VpcInfo
        ?: throw IllegalArgumentException("awsDocumentDb requires a VPC with subnet information (AwsVpc.VpcInfo). DocumentDB cannot run outside a VPC.")
    val kmsKeyArn = kmsKey.resolveKeyArn(name)
    emitter.fulfillSetting(
        name, JsonPrimitive(
            value = $$"mongodb://master:${random_password.$$name.result}@${aws_docdb_cluster.$$name.endpoint}:${aws_docdb_cluster.$$name.port}/default?replicaSet=rs0&retryWrites=false" +
                    if (tls) "&tls=true" else ""
        )
    )
    setOf(TerraformProviderImport.aws).forEach { emitter.require(it) }
    // DocumentDB/RDS identifiers must be lowercase alphanumeric/hyphen, so normalize the project prefix.
    val identifier = "${emitter.projectPrefix}-${name}".lowercase()
    emitter.emit(name) {
        "resource.random_password.${name}" {
            "length" - 32
            "special" - true
            "override_special" - "-_"
        }
        "resource.aws_docdb_subnet_group.${name}" {
            "name" - identifier
            "subnet_ids" - vpcInfo.privateSubnets
        }
        "resource.aws_docdb_cluster.${name}" {
            "cluster_identifier" - identifier
            "engine" - "docdb"
            "engine_version" - engineVersion
            "master_username" - "master"
            "master_password" - expression("random_password.${name}.result")
            "db_subnet_group_name" - expression("aws_docdb_subnet_group.${name}.name")
            "vpc_security_group_ids" - listOf<String>(vpcInfo.securityGroup)
            "storage_encrypted" - true
            if (kmsKeyArn != null) "kms_key_id" - kmsKeyArn
            "backup_retention_period" - backupRetentionPeriod
            "skip_final_snapshot" - skipFinalSnapshot
            if (!skipFinalSnapshot) "final_snapshot_identifier" - "$identifier-final"
            "deletion_protection" - deletionProtection
        }
        for (i in 0 until instanceCount) {
            "resource.aws_docdb_cluster_instance.${name}_$i" {
                "identifier" - "$identifier-$i"
                "cluster_identifier" - expression("aws_docdb_cluster.${name}.id")
                "instance_class" - instanceClass
                "auto_minor_version_upgrade" - true
            }
        }
        "resource.aws_vpc_security_group_ingress_rule.${name}_docdb" {
            "security_group_id" - vpcInfo.securityGroup
            "referenced_security_group_id" - vpcInfo.securityGroup
            "ip_protocol" - "tcp"
            "from_port" - 27017
            "to_port" - 27017
        }
    }
}

context(emitter: TerraformEmitterAws)
public fun TerraformNeed<Database.Settings>.mongodbAtlasFlex(
    orgId: String,
    atlasSearch: Boolean = true,
    backupEnabled: Boolean = true,
    zoneName: String? = null,
    existingProjectId: String? = null,
): Unit {
    val projectName = "${emitter.projectPrefix.filter { it.isLetterOrDigit() }}$name"
    val userName = "$projectName-main"
    if (!Database.Settings.supports("mongodb+srv")) throw IllegalArgumentException("You need to reference MongoDatabase in your server definition to use this.")
    emitter.fulfillSetting(
        name, JsonPrimitive(
            value = $$"""
        mongodb+srv://$$userName:${random_password.$$name.result}@${replace(mongodbatlas_advanced_cluster.$$name.connection_strings.standard_srv, "mongodb+srv://", "")}/default?retryWrites=true&w=majority
    """.trimIndent() + (if (atlasSearch) "&atlasSearch=true" else "")
        )
    )
    emptyList<TerraformProvider>().forEach { emitter.require(it) }
    setOf(TerraformProviderImport.mongodbAtlas).forEach { emitter.require(it) }
    emitter.emit(name) {
        val projectName1 = "${emitter.projectPrefix.filter { it.isLetterOrDigit() }}${name}"
        val region1 = emitter.applicationRegion.uppercase().replace("-", "_")
        val projectId1 = if (existingProjectId == null) {
            "resource.mongodbatlas_project.${name}" {
                "name" - projectName1
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
            "name" - projectName1
            "cluster_type" - "REPLICASET"

            "backup_enabled" - backupEnabled

            "replication_specs" - listOf(
                terraformJsonObject {
                    "zone_name" - zoneName
                    "region_configs" - listOf(
                        terraformJsonObject {
                            "provider_name" - "FLEX"
                            "backing_provider_name" - "AWS"
                            "region_name" - region1
                            "priority" - 7
                        }
                    )
                }
            )
        }
        "resource.mongodbatlas_database_user.${name}" {
            "username" - userName
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
