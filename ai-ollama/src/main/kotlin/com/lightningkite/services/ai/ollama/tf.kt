package com.lightningkite.services.ai.ollama

import com.lightningkite.services.ai.LlmAccess
import com.lightningkite.services.terraform.TerraformEmitter
import com.lightningkite.services.terraform.TerraformNeed
import kotlinx.serialization.json.JsonPrimitive

/**
 * Configures Ollama access for LLM inference.
 *
 * Ollama is self-hosted and requires no API keys or cloud resources.
 * This simply generates the `ollama://` settings URL.
 *
 * ```kotlin
 * context(emitter) {
 *     need<LlmAccess.Settings>("llm").ollama(modelId = "llama3")
 *
 *     // Or with a remote Ollama server:
 *     need<LlmAccess.Settings>("llm").ollama(
 *         modelId = "mistral",
 *         baseUrl = "http://ollama.internal:11434"
 *     )
 * }
 * ```
 *
 * @param modelId Ollama model name (e.g., "llama3", "mistral", "codellama").
 * @param baseUrl Ollama server URL. Defaults to `http://localhost:11434`.
 */
context(emitter: TerraformEmitter)
public fun TerraformNeed<LlmAccess.Settings>.ollama(
    modelId: String,
    baseUrl: String = "http://localhost:11434",
): Unit {
    if (!LlmAccess.Settings.supports("ollama")) {
        throw IllegalArgumentException(
            "The 'ollama' scheme is not registered on LlmAccess.Settings. " +
                "Add the ai-ollama dependency and reference OllamaSchemeRegistrar.ensureRegistered()."
        )
    }
    emitter.fulfillSetting(name, JsonPrimitive("ollama://${modelId}?baseUrl=${baseUrl}"))
}
