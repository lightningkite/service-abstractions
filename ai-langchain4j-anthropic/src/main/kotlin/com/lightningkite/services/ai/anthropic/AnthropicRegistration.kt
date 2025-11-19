package com.lightningkite.services.ai.anthropic

import com.lightningkite.services.ai.*
import dev.langchain4j.model.anthropic.AnthropicChatModel
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel
import java.time.Duration

/**
 * Registers all Anthropic implementations for LangChain4J Settings.
 *
 * This object automatically registers support for:
 * - ChatLanguageModel (synchronous)
 * - StreamingChatLanguageModel (streaming)
 *
 * Simply include this module as a dependency and the registrations will happen automatically.
 *
 * ## Chat Models
 *
 * URL format: `anthropic-chat://model-name?apiKey=...&param=value`
 *
 * Supported parameters:
 * - apiKey: Anthropic API key (required, can use ${ENV_VAR} syntax)
 * - temperature: Sampling temperature 0.0-1.0 (default: 1.0)
 * - topP: Nucleus sampling parameter (default: not set)
 * - topK: Top-K sampling parameter (default: not set)
 * - maxTokens: Maximum tokens in response (default: 4096)
 * - timeout: Request timeout in seconds (default: 60)
 * - maxRetries: Maximum retry attempts (default: 3)
 * - logRequests: Log requests to console (default: false)
 * - logResponses: Log responses to console (default: false)
 *
 * Available models:
 * - claude-3-5-sonnet-20241022 (most capable)
 * - claude-3-5-haiku-20241022 (fast and efficient)
 * - claude-3-opus-20240229 (previous flagship)
 * - claude-3-sonnet-20240229
 * - claude-3-haiku-20240307
 *
 * Example:
 * ```
 * anthropic-chat://claude-3-5-sonnet-20241022?apiKey=${ANTHROPIC_API_KEY}
 * anthropic-chat://claude-3-5-haiku-20241022?apiKey=sk-ant-...&temperature=0.7
 * ```
 */
public object AnthropicRegistration {
    init {
        // Register ChatLanguageModel
        ChatLanguageModelSettings.register("anthropic-chat") { name, url, context ->
            val params = parseUrlParams(url)
            val modelName = extractModelName(url)

            val apiKey = params["apiKey"]?.let(::resolveEnvVars)
                ?: System.getenv("ANTHROPIC_API_KEY")
                ?: throw IllegalArgumentException("Anthropic API key not provided in URL or ANTHROPIC_API_KEY environment variable")

            val builder = AnthropicChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName.ifEmpty { "claude-3-5-sonnet-20241022" })

            // Optional parameters
            params["temperature"]?.toDoubleOrNull()?.let { builder.temperature(it) }
            params["topP"]?.toDoubleOrNull()?.let { builder.topP(it) }
            params["topK"]?.toIntOrNull()?.let { builder.topK(it) }
            params["maxTokens"]?.toIntOrNull()?.let { builder.maxTokens(it) }
            params["timeout"]?.toLongOrNull()?.let { builder.timeout(Duration.ofSeconds(it)) }
            params["maxRetries"]?.toIntOrNull()?.let { builder.maxRetries(it) }
            params["logRequests"]?.toBooleanStrictOrNull()?.let { builder.logRequests(it) }
            params["logResponses"]?.toBooleanStrictOrNull()?.let { builder.logResponses(it) }

            builder.build()
        }

        // Register StreamingChatLanguageModel
        StreamingChatLanguageModelSettings.register("anthropic-chat") { name, url, context ->
            val params = parseUrlParams(url)
            val modelName = extractModelName(url)

            val apiKey = params["apiKey"]?.let(::resolveEnvVars)
                ?: System.getenv("ANTHROPIC_API_KEY")
                ?: throw IllegalArgumentException("Anthropic API key not provided in URL or ANTHROPIC_API_KEY environment variable")

            val builder = AnthropicStreamingChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName.ifEmpty { "claude-3-5-sonnet-20241022" })

            // Optional parameters
            params["temperature"]?.toDoubleOrNull()?.let { builder.temperature(it) }
            params["topP"]?.toDoubleOrNull()?.let { builder.topP(it) }
            params["topK"]?.toIntOrNull()?.let { builder.topK(it) }
            params["maxTokens"]?.toIntOrNull()?.let { builder.maxTokens(it) }
            params["timeout"]?.toLongOrNull()?.let { builder.timeout(Duration.ofSeconds(it)) }
            params["logRequests"]?.toBooleanStrictOrNull()?.let { builder.logRequests(it) }
            params["logResponses"]?.toBooleanStrictOrNull()?.let { builder.logResponses(it) }

            builder.build()
        }
    }

    /**
     * Ensures Anthropic implementations are registered.
     * Safe to call multiple times (idempotent).
     *
     * Note: Registration happens automatically when this class is loaded,
     * so calling this method is usually not necessary.
     */
    public fun ensure() {
        // Registration already happened in init block
    }
}

// Trigger initialization by referencing the object
private val register = AnthropicRegistration
