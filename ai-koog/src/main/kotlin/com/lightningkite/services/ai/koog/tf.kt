package com.lightningkite.services.ai.koog

import com.lightningkite.services.terraform.AwsPolicyStatement
import com.lightningkite.services.terraform.TerraformEmitterAws
import com.lightningkite.services.terraform.TerraformNeed
import com.lightningkite.services.terraform.TerraformProviderImport
import kotlinx.serialization.json.JsonPrimitive

/**
 * Configures AWS Bedrock access for LLM inference.
 *
 * AWS Bedrock is a fully managed service, so this function primarily:
 * - Generates the connection URL for the settings
 * - Adds IAM policy statements for InvokeModel permissions
 *
 * IMPORTANT: Model access must be manually enabled in the AWS Bedrock Console
 * before using this configuration. This cannot be automated via Terraform.
 *
 * @param modelId The Bedrock model ID (e.g., "anthropic.claude-3-5-sonnet-20241022-v2:0",
 *                "amazon.nova-pro-v1:0", "meta.llama3-70b-instruct-v1:0")
 * @param region Optional AWS region override. Defaults to the emitter's applicationRegion.
 *
 * Example usage in Terraform DSL:
 * ```kotlin
 * context(emitter) {
 *     need<LLMClientAndModel.Settings>("main-llm").awsBedrock(
 *         modelId = "anthropic.claude-3-5-sonnet-20241022-v2:0"
 *     )
 * }
 * ```
 *
 * @see <a href="https://docs.aws.amazon.com/bedrock/latest/userguide/model-access.html">AWS Bedrock Model Access</a>
 */
context(emitter: TerraformEmitterAws)
public fun TerraformNeed<LLMClientAndModel.Settings>.awsBedrock(
    modelId: String,
    region: String = emitter.applicationRegion,
): Unit {
    if (!LLMClientAndModel.Settings.supports("bedrock")) {
        throw IllegalArgumentException(
            "You need to register the 'bedrock' scheme in LLMClientAndModel.Settings to use this. " +
                "Make sure you have the Bedrock client dependency and have registered the scheme."
        )
    }

    // URL format: bedrock://{model-id}?region={region}
    emitter.fulfillSetting(
        name,
        JsonPrimitive("bedrock://${modelId}?region=${region}")
    )

    setOf(TerraformProviderImport.aws).forEach { emitter.require(it) }

    // Add IAM policy for Bedrock model invocation
    // Using specific model ARN for least-privilege access
    emitter.policyStatements += AwsPolicyStatement(
        action = listOf(
            "bedrock:InvokeModel",
            "bedrock:InvokeModelWithResponseStream"
        ),
        resource = listOf(
            "arn:aws:bedrock:${region}::foundation-model/${modelId}"
        )
    )
}

/**
 * Configures AWS Bedrock access with permissions for multiple models.
 *
 * Use this when your application needs to switch between different models
 * or when you want to grant broader permissions.
 *
 * @param modelIds List of Bedrock model IDs to grant access to
 * @param defaultModelId The model ID to use by default in the settings URL
 * @param region Optional AWS region override
 */
context(emitter: TerraformEmitterAws)
public fun TerraformNeed<LLMClientAndModel.Settings>.awsBedrockMultiModel(
    modelIds: List<String>,
    defaultModelId: String = modelIds.first(),
    region: String = emitter.applicationRegion,
): Unit {
    require(modelIds.isNotEmpty()) { "modelIds cannot be empty" }
    require(defaultModelId in modelIds) { "defaultModelId must be in modelIds list" }
    emitter.fulfillSetting(
        name,
        JsonPrimitive("bedrock://${defaultModelId}?region=${region}")
    )

    setOf(TerraformProviderImport.aws).forEach { emitter.require(it) }

    // Add IAM policy for all specified models
    emitter.policyStatements += AwsPolicyStatement(
        action = listOf(
            "bedrock:InvokeModel",
            "bedrock:InvokeModelWithResponseStream"
        ),
        resource = modelIds.map { modelId ->
            "arn:aws:bedrock:${region}::foundation-model/${modelId}"
        }
    )
}

/**
 * Configures AWS Bedrock access with wildcard permissions for all models.
 *
 * WARNING: This grants access to invoke ANY Bedrock model. Use with caution
 * and only when necessary. Prefer [awsBedrock] or [awsBedrockMultiModel]
 * for least-privilege access.
 *
 * @param defaultModelId The model ID to use in the settings URL
 * @param region Optional AWS region override
 */
context(emitter: TerraformEmitterAws)
public fun TerraformNeed<LLMClientAndModel.Settings>.awsBedrockAllModels(
    defaultModelId: String,
    region: String = emitter.applicationRegion,
): Unit {
    emitter.fulfillSetting(
        name,
        JsonPrimitive("bedrock://${defaultModelId}?region=${region}")
    )

    setOf(TerraformProviderImport.aws).forEach { emitter.require(it) }

    // Wildcard access to all foundation models
    emitter.policyStatements += AwsPolicyStatement(
        action = listOf(
            "bedrock:InvokeModel",
            "bedrock:InvokeModelWithResponseStream"
        ),
        resource = listOf(
            "arn:aws:bedrock:${region}::foundation-model/*"
        )
    )
}
