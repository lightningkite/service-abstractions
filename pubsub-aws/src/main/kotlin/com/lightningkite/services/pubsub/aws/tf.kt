package com.lightningkite.services.pubsub.aws

import com.lightningkite.services.pubsub.PubSub
import com.lightningkite.services.terraform.TerraformEmitterAws
import com.lightningkite.services.terraform.TerraformNeed
import kotlinx.serialization.json.JsonPrimitive

/**
 * Creates a DynamoDB-based PubSub infrastructure optimized for AWS Lambda.
 *
 * This is a lightweight alternative to [awsApiGatewayWebSocket] that uses direct
 * DynamoDB operations instead of WebSocket connections. This eliminates the
 * connection overhead that makes WebSocket-based PubSub slow in Lambda.
 *
 * ## Performance
 *
 * - **emit()**: ~5-20ms (DynamoDB PutItem, no connection overhead)
 * - **collect()**: Polls every 15ms by default
 * - **Latency**: Messages delivered within poll interval + DynamoDB latency
 *
 * ## Use Cases
 *
 * Ideal for:
 * - Lambda-to-Lambda communication with low latency requirements
 * - High-frequency message passing (e.g., real-time audio forwarding)
 * - Scenarios where WebSocket connection overhead is problematic
 *
 * ## Pricing
 *
 * - DynamoDB on-demand: ~$1.25 per million writes, ~$0.25 per million reads
 * - **Idle cost: $0**
 *
 * ## Usage
 *
 * ```kotlin
 * // In your Terraform configuration
 * context(emitter: TerraformEmitterAws) {
 *     TerraformNeed<PubSub.Settings>("pubsub").dynamoDb()
 * }
 *
 * // In your application
 * val pubsub = settings.pubsub("messaging", context)
 * pubsub.get<MyEvent>("events").emit(MyEvent(...))
 * ```
 *
 * Note: The DynamoDB table is auto-created by the DynamoDbPubSub implementation
 * if it doesn't exist. IAM permissions for DynamoDB are typically granted via
 * a wildcard policy on the main Lambda role.
 *
 * @param pollIntervalMs How often collectors poll for new messages (default: 15ms)
 */
context(emitter: TerraformEmitterAws)
public fun TerraformNeed<PubSub.Settings>.dynamoDb(
    pollIntervalMs: Int = 15,
): Unit {
    if (!PubSub.Settings.supports("dynamodb-pubsub")) {
        throw IllegalArgumentException("You need to reference DynamoDbPubSub in your server definition to use this.")
    }

    val region = emitter.applicationRegion
    // Use project prefix with underscores for DynamoDB table name
    val tableName = "${emitter.projectPrefix.replace('-', '_')}_${name.replace('-', '_')}_pubsub"

    // Fulfill setting with DynamoDB URL
    // The table will be auto-created by DynamoDbPubSub if it doesn't exist
    emitter.fulfillSetting(
        name,
        JsonPrimitive("dynamodb-pubsub://${region}/${tableName}?pollInterval=${pollIntervalMs}")
    )
}
