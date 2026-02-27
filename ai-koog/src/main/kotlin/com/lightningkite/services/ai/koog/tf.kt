package com.lightningkite.services.ai.koog

import ai.koog.prompt.llm.LLModel
import com.lightningkite.services.ai.koog.rag.EmbedderSettings
import com.lightningkite.services.terraform.AwsPolicyStatement
import com.lightningkite.services.terraform.TerraformEmitterAws
import com.lightningkite.services.terraform.TerraformNeed
import com.lightningkite.services.terraform.TerraformProviderImport
import kotlinx.serialization.json.JsonPrimitive

/**
 * Regional prefixes that indicate a model ID is a cross-region inference profile.
 * When a model ID starts with one of these prefixes (e.g., "us.", "eu.", "ap."),
 * it uses the inference-profile ARN format instead of foundation-model ARN format.
 */
private val inferenceProfilePrefixes = listOf("us.", "eu.", "ap.", "us-", "eu-", "ap-")

/**
 * Determines if a Bedrock model ID represents a cross-region inference profile.
 *
 * Cross-region inference profiles have regional prefixes like "us.", "eu.", or "ap."
 * and require a different ARN format than foundation models.
 *
 * @param modelId The Bedrock model ID to check
 * @return true if this is an inference profile, false if it's a foundation model
 */
internal fun isInferenceProfile(modelId: String): Boolean =
    inferenceProfilePrefixes.any { modelId.startsWith(it) }

/**
 * Extracts the base model ID from an inference profile model ID by removing the regional prefix.
 * For example: "us.anthropic.claude-haiku-4-5-20251001-v1:0" -> "anthropic.claude-haiku-4-5-20251001-v1:0"
 *
 * @param modelId The inference profile model ID with regional prefix
 * @return The base model ID without the prefix, or the original ID if no prefix found
 */
internal fun getBaseModelId(modelId: String): String {
    for (prefix in inferenceProfilePrefixes) {
        if (modelId.startsWith(prefix)) {
            return modelId.removePrefix(prefix)
        }
    }
    return modelId
}

/**
 * Generates the correct ARN(s) for a Bedrock model based on whether it's a foundation model
 * or a cross-region inference profile.
 *
 * - Foundation models: Returns single ARN `arn:aws:bedrock:{region}::foundation-model/{modelId}`
 * - Inference profiles: Returns TWO ARNs:
 *   1. `arn:aws:bedrock:{region}:*:inference-profile/{modelId}` - for the inference profile itself
 *   2. `arn:aws:bedrock:*::foundation-model/{baseModelId}` - for the underlying foundation model
 *      (uses wildcard region because Bedrock routes requests to different regions internally)
 *
 * The wildcard (*) in inference profile ARNs matches any account ID, which is required
 * because inference profiles are account-specific resources.
 *
 * @param modelId The Bedrock model ID
 * @param region The AWS region
 * @return List of ARN strings required for IAM permissions
 */
internal fun bedrockModelArns(modelId: String, region: String): List<String> =
    if (isInferenceProfile(modelId)) {
        val baseModelId = getBaseModelId(modelId)
        listOf(
            // Permission for the inference profile itself
            "arn:aws:bedrock:${region}:*:inference-profile/${modelId}",
            // Permission for the underlying foundation model (any region, since Bedrock routes internally)
            "arn:aws:bedrock:*::foundation-model/${baseModelId}"
        )
    } else {
        listOf("arn:aws:bedrock:${region}::foundation-model/${modelId}")
    }

// Keep for backwards compatibility with tests
internal fun bedrockModelArn(modelId: String, region: String): String =
    bedrockModelArns(modelId, region).first()

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
    // Note: Cross-region inference profiles (us., eu., ap. prefixes) require permissions on both
    // the inference profile AND the underlying foundation model (which can be in any region)
    emitter.policyStatements += AwsPolicyStatement(
        action = listOf(
            "bedrock:InvokeModel",
            "bedrock:InvokeModelWithResponseStream"
        ),
        resource = bedrockModelArns(modelId, region)
    )
}

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
@JvmName("awsBedrockEmbedder")
context(emitter: TerraformEmitterAws)
public fun TerraformNeed<EmbedderSettings>.awsBedrock(
    modelId: String,
    region: String = emitter.applicationRegion,
): Unit {
    if (!EmbedderSettings.supports("bedrock")) {
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
    // Note: Cross-region inference profiles (us., eu., ap. prefixes) require permissions on both
    // the inference profile AND the underlying foundation model (which can be in any region)
    emitter.policyStatements += AwsPolicyStatement(
        action = listOf(
            "bedrock:InvokeModel",
            "bedrock:InvokeModelWithResponseStream"
        ),
        resource = bedrockModelArns(modelId, region)
    )
}


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
    model: LLModel,
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
        JsonPrimitive("bedrock://${model.id}?region=${region}")
    )

    setOf(TerraformProviderImport.aws).forEach { emitter.require(it) }

    // Add IAM policy for Bedrock model invocation
    // Using specific model ARN for least-privilege access
    // Note: Cross-region inference profiles (us., eu., ap. prefixes) require permissions on both
    // the inference profile AND the underlying foundation model (which can be in any region)
    emitter.policyStatements += AwsPolicyStatement(
        action = listOf(
            "bedrock:InvokeModel",
            "bedrock:InvokeModelWithResponseStream"
        ),
        resource = bedrockModelArns(model.id, region)
    )
}

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
@JvmName("awsBedrockEmbedder")
public fun TerraformNeed<EmbedderSettings>.awsBedrock(
    model: LLModel,
    region: String = emitter.applicationRegion,
): Unit {
    if (!EmbedderSettings.supports("bedrock")) {
        throw IllegalArgumentException(
            "You need to register the 'bedrock' scheme in LLMClientAndModel.Settings to use this. " +
                "Make sure you have the Bedrock client dependency and have registered the scheme."
        )
    }

    // URL format: bedrock://{model-id}?region={region}
    emitter.fulfillSetting(
        name,
        JsonPrimitive("bedrock://${model.id}?region=${region}")
    )

    setOf(TerraformProviderImport.aws).forEach { emitter.require(it) }

    // Add IAM policy for Bedrock model invocation
    // Using specific model ARN for least-privilege access
    // Note: Cross-region inference profiles (us., eu., ap. prefixes) require permissions on both
    // the inference profile AND the underlying foundation model (which can be in any region)
    emitter.policyStatements += AwsPolicyStatement(
        action = listOf(
            "bedrock:InvokeModel",
            "bedrock:InvokeModelWithResponseStream"
        ),
        resource = bedrockModelArns(model.id, region)
    )
}

