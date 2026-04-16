package com.lightningkite.services.ai.bedrock

import com.lightningkite.services.ai.LlmAccess
import com.lightningkite.services.terraform.AwsPolicyStatement
import com.lightningkite.services.terraform.TerraformEmitterAws
import com.lightningkite.services.terraform.TerraformNeed
import com.lightningkite.services.terraform.TerraformProviderImport
import kotlinx.serialization.json.JsonPrimitive

/**
 * Regional prefixes indicating a cross-region inference profile.
 * Model IDs starting with these (e.g., "us.", "eu.") use the inference-profile
 * ARN format instead of foundation-model ARN format.
 */
private val inferenceProfilePrefixes = listOf("us.", "eu.", "ap.", "us-", "eu-", "ap-")

internal fun isInferenceProfile(modelId: String): Boolean =
    inferenceProfilePrefixes.any { modelId.startsWith(it) }

/**
 * Strips the regional prefix from an inference profile model ID.
 * E.g., "us.anthropic.claude-haiku-4-5-20251001-v1:0" -> "anthropic.claude-haiku-4-5-20251001-v1:0"
 */
internal fun getBaseModelId(modelId: String): String {
    for (prefix in inferenceProfilePrefixes) {
        if (modelId.startsWith(prefix)) return modelId.removePrefix(prefix)
    }
    return modelId
}

/**
 * Generates correct ARN(s) for a Bedrock model for IAM policies.
 *
 * - Foundation models: single ARN `arn:aws:bedrock:{region}::foundation-model/{modelId}`
 * - Inference profiles: two ARNs — one for the profile itself and one for the underlying
 *   foundation model with wildcard region (Bedrock routes internally).
 */
internal fun bedrockModelArns(modelId: String, region: String): List<String> =
    if (isInferenceProfile(modelId)) {
        val baseModelId = getBaseModelId(modelId)
        listOf(
            "arn:aws:bedrock:${region}:*:inference-profile/${modelId}",
            "arn:aws:bedrock:*::foundation-model/${baseModelId}"
        )
    } else {
        listOf("arn:aws:bedrock:${region}::foundation-model/${modelId}")
    }

/**
 * Configures AWS Bedrock access for LLM inference.
 *
 * Bedrock is a fully managed service — this generates the `bedrock://` connection URL
 * and adds IAM policy statements for `InvokeModel` / `InvokeModelWithResponseStream`.
 *
 * **Model access must be manually enabled in the AWS Bedrock Console** before deployment;
 * this cannot be automated via Terraform.
 *
 * @param modelId Bedrock model ID (e.g., `"anthropic.claude-sonnet-4-5-20250929-v1:0"`,
 *   `"amazon.nova-pro-v1:0"`, `"us.anthropic.claude-haiku-4-5"` for inference profiles).
 * @param region AWS region. Defaults to emitter's applicationRegion.
 *
 * ```kotlin
 * context(emitter) {
 *     need<LlmAccess.Settings>("llm").awsBedrock(
 *         modelId = "anthropic.claude-sonnet-4-5-20250929-v1:0"
 *     )
 * }
 * ```
 */
context(emitter: TerraformEmitterAws)
public fun TerraformNeed<LlmAccess.Settings>.awsBedrock(
    modelId: String,
    region: String = emitter.applicationRegion,
): Unit {
    if (!LlmAccess.Settings.supports("bedrock")) {
        throw IllegalArgumentException(
            "The 'bedrock' scheme is not registered on LlmAccess.Settings. " +
                "Add the ai-bedrock dependency and reference BedrockLlmSettings.ensureRegistered()."
        )
    }

    emitter.fulfillSetting(name, JsonPrimitive("bedrock://${modelId}?region=${region}"))
    setOf(TerraformProviderImport.aws).forEach { emitter.require(it) }

    emitter.policyStatements += AwsPolicyStatement(
        action = listOf(
            "bedrock:InvokeModel",
            "bedrock:InvokeModelWithResponseStream"
        ),
        resource = bedrockModelArns(modelId, region)
    )
}
