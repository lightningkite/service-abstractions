package com.lightningkite.services.ai.openai

import com.lightningkite.services.ai.LlmAccess
import com.lightningkite.services.terraform.TerraformEmitter
import com.lightningkite.services.terraform.TerraformNeed
import kotlinx.serialization.json.JsonPrimitive

/**
 * Configures OpenAI-compatible API access with a provided API key.
 *
 * Works with OpenAI itself and any compatible server (LM Studio, vLLM, Groq,
 * Together, Fireworks, OpenRouter, etc.) via [baseUrl].
 *
 * [apiKey] can be a literal or any Terraform expression:
 * - `"\${var.openai_api_key}"` — Terraform variable
 *
 * ```kotlin
 * context(emitter) {
 *     need<LlmAccess.Settings>("llm").openai(
 *         modelId = "gpt-4o",
 *         apiKey = "\${var.openai_api_key}"
 *     )
 * }
 * ```
 *
 * @param modelId Model ID (e.g., "gpt-4o", "gpt-5", "o3-mini").
 * @param apiKey API key or Terraform expression resolving to one.
 * @param baseUrl Override for the API root (e.g., `"http://localhost:1234/v1"` for LM Studio).
 *   Null uses the default `https://api.openai.com/v1`.
 */
context(emitter: TerraformEmitter)
public fun TerraformNeed<LlmAccess.Settings>.openai(
    modelId: String,
    apiKey: String,
    baseUrl: String? = null,
): Unit {
    if (!LlmAccess.Settings.supports("openai")) {
        throw IllegalArgumentException(
            "The 'openai' scheme is not registered on LlmAccess.Settings. " +
                "Add the ai-openai dependency and reference OpenAiLlmSettings.ensureRegistered()."
        )
    }
    val url = buildString {
        append("openai://")
        append(modelId)
        append("?apiKey=")
        append(apiKey)
        if (baseUrl != null) {
            append("&baseUrl=")
            append(baseUrl)
        }
    }
    emitter.fulfillSetting(name, JsonPrimitive(url))
}
