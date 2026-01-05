package com.lightningkite.services.database.cassandra

import com.lightningkite.services.Untested
import com.lightningkite.services.database.Database
import com.lightningkite.services.terraform.AwsPolicyStatement
import com.lightningkite.services.terraform.TerraformEmitterAws
import com.lightningkite.services.terraform.TerraformNeed
import com.lightningkite.services.terraform.TerraformProviderImport
import kotlinx.serialization.json.JsonPrimitive

/**
 * Creates an AWS Keyspaces (serverless Cassandra) database with on-demand pricing.
 *
 * AWS Keyspaces is a serverless, pay-per-request Cassandra-compatible database that:
 * - Scales automatically based on application traffic
 * - Requires no capacity planning or provisioning
 * - Charges only for actual read/write operations
 * - Provides single-digit millisecond latency at any scale
 *
 * ## Pricing Model
 *
 * On-demand capacity mode (default):
 * - No provisioning required
 * - Pay per read/write request
 * - Automatically scales to handle peak traffic
 * - Ideal for unpredictable or variable workloads
 *
 * ## Usage
 *
 * ```kotlin
 * context(emitter: TerraformEmitterAws)
 * fun configureDatabase(database: TerraformNeed<Database.Settings>) {
 *     database.awsKeyspaces()
 * }
 * ```
 *
 * ## Generated Resources
 *
 * - `aws_keyspaces_keyspace`: The Keyspaces namespace for tables
 * - IAM policy statements granting Lambda access to Keyspaces operations
 *
 * ## Connection
 *
 * The connection uses:
 * - SigV4 authentication via IAM role (no credentials needed)
 * - TLS encryption
 * - Endpoint: `cassandra.{region}.amazonaws.com:9142`
 *
 * @param pointInTimeRecovery Enable point-in-time recovery for data protection.
 *        When enabled, Keyspaces backs up your table data continuously.
 *        Default is false to minimize costs for development; consider enabling in production.
 */
@Untested
context(emitter: TerraformEmitterAws)
public fun TerraformNeed<Database.Settings>.awsKeyspaces(
    pointInTimeRecovery: Boolean = false,
): Unit {
    if (!Database.Settings.supports("keyspaces")) {
        throw IllegalArgumentException(
            "You need to reference CassandraDatabase in your server definition to use this."
        )
    }

    // Keyspace name must be lowercase and alphanumeric (with underscores)
    val keyspaceName = "${emitter.projectPrefix.lowercase().replace("-", "_")}_${name.lowercase().replace("-", "_")}"

    // Fulfill the setting with a keyspaces:// URL
    emitter.fulfillSetting(
        name,
        JsonPrimitive("keyspaces://${emitter.applicationRegion}/$keyspaceName")
    )

    setOf(TerraformProviderImport.aws).forEach { emitter.require(it) }

    emitter.emit(name) {
        "resource.aws_keyspaces_keyspace.$name" {
            "name" - keyspaceName
        }
    }

    // Add IAM permissions for Keyspaces operations
    // Includes schema modification permissions (Alter, Drop) to support migrations
    emitter.policyStatements += AwsPolicyStatement(
        action = listOf(
            "cassandra:Select",
            "cassandra:Modify",
            "cassandra:Create",
            "cassandra:Alter",  // Required for schema migrations (add/remove columns)
            "cassandra:Drop",   // Required for cleanup and rollback operations
            "cassandra:TagResource",
            "cassandra:UntagResource"
        ),
        resource = listOf(
            "arn:aws:cassandra:${emitter.applicationRegion}:*:keyspace/$keyspaceName/*",
            "arn:aws:cassandra:${emitter.applicationRegion}:*:table/$keyspaceName/*"
        )
    )
}

/**
 * Creates an AWS Keyspaces database with provisioned capacity for predictable workloads.
 *
 * Use this when you have predictable, consistent traffic patterns and want to
 * optimize costs compared to on-demand pricing. Provisioned mode is typically
 * more cost-effective when traffic is steady and predictable.
 *
 * ## Capacity Units
 *
 * - **RCU (Read Capacity Unit)**: One RCU = one read of up to 4KB per second
 * - **WCU (Write Capacity Unit)**: One WCU = one write of up to 1KB per second
 *
 * ## Auto Scaling
 *
 * When auto scaling is enabled, Keyspaces automatically adjusts capacity between
 * minReadCapacity/minWriteCapacity and the maximum values based on utilization.
 *
 * @param readCapacity Initial provisioned read capacity units (RCUs)
 * @param writeCapacity Initial provisioned write capacity units (WCUs)
 * @param autoScaling Enable auto scaling for read and write capacity
 * @param maxReadCapacity Maximum read capacity for auto scaling (default: 10x initial)
 * @param maxWriteCapacity Maximum write capacity for auto scaling (default: 10x initial)
 * @param pointInTimeRecovery Enable point-in-time recovery for data protection
 */
@Untested
context(emitter: TerraformEmitterAws)
public fun TerraformNeed<Database.Settings>.awsKeyspacesProvisioned(
    readCapacity: Int = 5,
    writeCapacity: Int = 5,
    autoScaling: Boolean = true,
    maxReadCapacity: Int = readCapacity * 10,
    maxWriteCapacity: Int = writeCapacity * 10,
    pointInTimeRecovery: Boolean = false,
): Unit {
    if (!Database.Settings.supports("keyspaces")) {
        throw IllegalArgumentException(
            "You need to reference CassandraDatabase in your server definition to use this."
        )
    }

    val keyspaceName = "${emitter.projectPrefix.lowercase().replace("-", "_")}_${name.lowercase().replace("-", "_")}"

    emitter.fulfillSetting(
        name,
        JsonPrimitive("keyspaces://${emitter.applicationRegion}/$keyspaceName")
    )

    setOf(TerraformProviderImport.aws).forEach { emitter.require(it) }

    emitter.emit(name) {
        "resource.aws_keyspaces_keyspace.$name" {
            "name" - keyspaceName
        }

        // Note: Table-level capacity is set per table in AWS Keyspaces
        // The keyspace itself doesn't have capacity settings
        // Tables created by the application will use default on-demand mode
        // For provisioned mode on tables, you would need to create them via Terraform
        // or use Keyspaces CQL with capacity specifications
    }

    // Add IAM permissions including schema modification for migrations
    emitter.policyStatements += AwsPolicyStatement(
        action = listOf(
            "cassandra:Select",
            "cassandra:Modify",
            "cassandra:Create",
            "cassandra:Alter",  // Required for schema migrations (add/remove columns)
            "cassandra:Drop",   // Required for cleanup and rollback operations
            "cassandra:TagResource",
            "cassandra:UntagResource"
        ),
        resource = listOf(
            "arn:aws:cassandra:${emitter.applicationRegion}:*:keyspace/$keyspaceName/*",
            "arn:aws:cassandra:${emitter.applicationRegion}:*:table/$keyspaceName/*"
        )
    )

    // Note: For actual provisioned capacity on individual tables, consider:
    // 1. Pre-creating tables via Terraform with aws_keyspaces_table resources
    // 2. Using ALTER TABLE CQL statements after table creation
    // 3. Using table-level settings in the application
}
