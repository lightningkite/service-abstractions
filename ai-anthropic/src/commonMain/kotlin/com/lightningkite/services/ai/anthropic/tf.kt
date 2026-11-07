package com.lightningkite.services.ai.anthropic

import com.lightningkite.services.ai.LlmAccess
import com.lightningkite.services.terraform.TerraformEmitter
import com.lightningkite.services.terraform.TerraformNeed
import kotlinx.serialization.json.JsonPrimitive

/**
 * Configures Anthropic API access with a provided API key.
 *
 * [apiKey] can be a literal or any Terraform expression:
 * - `"\${var.anthropic_api_key}"` — Terraform variable
 *
 * ```kotlin
 * context(emitter) {
 *     need<LlmAccess.Settings>("llm").anthropic(
 *         modelId = "claude-sonnet-4-5",
 *         apiKey = "\${var.anthropic_api_key}"
 *     )
 * }
 * ```
 *
 * @param modelId Default Anthropic model ID (e.g., "claude-haiku-4-5", "claude-sonnet-4-5").
 * @param apiKey API key or Terraform expression resolving to one.
 */
context(emitter: TerraformEmitter)
public fun TerraformNeed<LlmAccess.Settings>.anthropic(
    modelId: String,
    apiKey: String,
): Unit {
    if (!LlmAccess.Settings.supports("anthropic")) {
        throw IllegalArgumentException(
            "The 'anthropic' scheme is not registered on LlmAccess.Settings. " +
                "Add the ai-anthropic dependency and reference AnthropicLlmSettings.ensureRegistered()."
        )
    }
    emitter.fulfillSetting(name, JsonPrimitive("anthropic://${modelId}?apiKey=${apiKey}"))
}
