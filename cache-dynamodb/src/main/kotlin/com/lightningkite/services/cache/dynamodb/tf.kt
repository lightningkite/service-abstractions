package com.lightningkite.services.cache.dynamodb

import com.lightningkite.services.Untested
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.terraform.TerraformEmitterAws
import com.lightningkite.services.terraform.TerraformEmitterAwsVpc
import com.lightningkite.services.terraform.TerraformNeed
import kotlinx.serialization.json.JsonPrimitive

// REVIEW NOTE: Unlike Redis/Memcached tf.kt files, this function does NOT emit Terraform resources
// to create the DynamoDB table. Instead, it only generates the settings URL and relies on
// DynamoDbCache's auto-provisioning (see ready() in DynamoDbCache.kt) to create the table on
// first use. This is simpler but has a tradeoff: first access may be slow (~10-30 seconds for
// table creation). Consider whether explicit Terraform-managed table creation would be preferred
// for production environments where predictable startup time is important. - by Claude

// REVIEW NOTE: This function does not add IAM policy statements to emitter.policyStatements.
// The caller's execution context (Lambda/EC2) must already have DynamoDB permissions via other
// means (instance profile, execution role, etc.). If explicit permission management is desired,
// policy statements like dynamodb:GetItem, dynamodb:PutItem, dynamodb:DeleteItem, dynamodb:UpdateItem,
// dynamodb:CreateTable, dynamodb:DescribeTable, dynamodb:UpdateTimeToLive, dynamodb:DescribeTimeToLive
// could be added. - by Claude

// REVIEW NOTE: Import TerraformEmitterAwsVpc appears unused in this file. - by Claude

/**
 * Configures a DynamoDB-based cache for Terraform deployment.
 *
 * This function generates a Cache.Settings URL pointing to AWS DynamoDB. Unlike other cache
 * implementations (Redis, Memcached), this does NOT create Terraform resources for the table.
 * Instead, the DynamoDbCache implementation auto-creates the table on first use.
 *
 * Table naming: The table name is derived from [TerraformEmitter.projectPrefix] with hyphens
 * replaced by underscores for consistency.
 *
 * Prerequisites:
 * - DynamoDbCache must be referenced in the server definition (URL scheme must be registered)
 * - The execution context must have DynamoDB permissions (via IAM role, instance profile, etc.)
 *
 * @receiver TerraformNeed for Cache.Settings that will be fulfilled with the DynamoDB URL
 * @throws IllegalArgumentException if 'dynamodb' URL scheme is not registered (DynamoDbCache not referenced)
 */
@Untested
context(emitter: TerraformEmitterAws) public fun TerraformNeed<Cache.Settings>.awsDynamoDb(
): Unit {
    if(!Cache.Settings.supports("dynamodb")) throw IllegalArgumentException("You need to reference 'DynamoDbCache' in your server definition to use this.")
    emitter.fulfillSetting(name, JsonPrimitive("dynamodb://${emitter.applicationRegion}/${emitter.projectPrefix.replace('-', '_')}"))
}