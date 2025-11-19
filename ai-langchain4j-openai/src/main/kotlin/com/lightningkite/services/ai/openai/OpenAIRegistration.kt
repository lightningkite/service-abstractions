package com.lightningkite.services.ai.openai

import com.lightningkite.services.ai.*
import dev.langchain4j.model.openai.OpenAiChatModel
import dev.langchain4j.model.openai.OpenAiStreamingChatModel
import dev.langchain4j.model.openai.OpenAiEmbeddingModel
import java.time.Duration

/**
 * Registers all OpenAI implementations for LangChain4J Settings.
 *
 * This object automatically registers support for:
 * - ChatLanguageModel (synchronous)
 * - StreamingChatLanguageModel (streaming)
 * - EmbeddingModel
 *
 * Simply include this module as a dependency and the registrations will happen automatically.
 *
 * ## Chat Models
 *
 * URL format: `openai-chat://model-name?apiKey=...&param=value`
 *
 * Supported parameters:
 * - apiKey: OpenAI API key (required, can use ${ENV_VAR} syntax)
 * - temperature: Sampling temperature 0.0-2.0 (default: 0.7)
 * - topP: Nucleus sampling parameter (default: 1.0)
 * - maxTokens: Maximum tokens in response (optional)
 * - timeout: Request timeout in seconds (default: 60)
 * - maxRetries: Maximum retry attempts (default: 3)
 * - logRequests: Log requests to console (default: false)
 * - logResponses: Log responses to console (default: false)
 *
 * Example:
 * ```
 * openai-chat://gpt-4-turbo?apiKey=${OPENAI_API_KEY}&temperature=0.7
 * openai-chat://gpt-3.5-turbo?apiKey=sk-...&maxTokens=1000
 * ```
 *
 * ## Embedding Models
 *
 * URL format: `openai-embedding://model-name?apiKey=...&param=value`
 *
 * Supported parameters:
 * - apiKey: OpenAI API key (required, can use ${ENV_VAR} syntax)
 * - timeout: Request timeout in seconds (default: 60)
 * - maxRetries: Maximum retry attempts (default: 3)
 * - logRequests: Log requests to console (default: false)
 * - logResponses: Log responses to console (default: false)
 *
 * Available models:
 * - text-embedding-3-small (1536 dimensions, efficient)
 * - text-embedding-3-large (3072 dimensions, most capable)
 * - text-embedding-ada-002 (1536 dimensions, legacy)
 *
 * Example:
 * ```
 * openai-embedding://text-embedding-3-small?apiKey=${OPENAI_API_KEY}
 * openai-embedding://text-embedding-3-large?apiKey=sk-...&timeout=120
 * ```
 */
public object OpenAIRegistration {
    init {
        // Register ChatLanguageModel
        ChatLanguageModelSettings.register("openai-chat") { name, url, context ->
            val params = parseUrlParams(url)
            val modelName = extractModelName(url)

            val apiKey = params["apiKey"]?.let(::resolveEnvVars)
                ?: System.getenv("OPENAI_API_KEY")
                ?: throw IllegalArgumentException("OpenAI API key not provided in URL or OPENAI_API_KEY environment variable")

            val builder = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName.ifEmpty { "gpt-4o" })

            // Optional parameters
            params["temperature"]?.toDoubleOrNull()?.let { builder.temperature(it) }
            params["topP"]?.toDoubleOrNull()?.let { builder.topP(it) }
            params["maxTokens"]?.toIntOrNull()?.let { builder.maxTokens(it) }
            params["timeout"]?.toLongOrNull()?.let { builder.timeout(Duration.ofSeconds(it)) }
            params["maxRetries"]?.toIntOrNull()?.let { builder.maxRetries(it) }
            params["logRequests"]?.toBooleanStrictOrNull()?.let { builder.logRequests(it) }
            params["logResponses"]?.toBooleanStrictOrNull()?.let { builder.logResponses(it) }

            builder.build()
        }

        // Register StreamingChatLanguageModel
        StreamingChatLanguageModelSettings.register("openai-chat") { name, url, context ->
            val params = parseUrlParams(url)
            val modelName = extractModelName(url)

            val apiKey = params["apiKey"]?.let(::resolveEnvVars)
                ?: System.getenv("OPENAI_API_KEY")
                ?: throw IllegalArgumentException("OpenAI API key not provided in URL or OPENAI_API_KEY environment variable")

            val builder = OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName.ifEmpty { "gpt-4o" })

            // Optional parameters
            params["temperature"]?.toDoubleOrNull()?.let { builder.temperature(it) }
            params["topP"]?.toDoubleOrNull()?.let { builder.topP(it) }
            params["maxTokens"]?.toIntOrNull()?.let { builder.maxTokens(it) }
            params["timeout"]?.toLongOrNull()?.let { builder.timeout(Duration.ofSeconds(it)) }
            params["logRequests"]?.toBooleanStrictOrNull()?.let { builder.logRequests(it) }
            params["logResponses"]?.toBooleanStrictOrNull()?.let { builder.logResponses(it) }

            builder.build()
        }

        // Register EmbeddingModel
        EmbeddingModelSettings.register("openai-embedding") { name, url, context ->
            val params = parseUrlParams(url)
            val modelName = extractModelName(url)

            val apiKey = params["apiKey"]?.let(::resolveEnvVars)
                ?: System.getenv("OPENAI_API_KEY")
                ?: throw IllegalArgumentException("OpenAI API key not provided in URL or OPENAI_API_KEY environment variable")

            val builder = OpenAiEmbeddingModel.builder()
                .apiKey(apiKey)
                .modelName(modelName.ifEmpty { "text-embedding-3-small" })

            // Optional parameters
            params["timeout"]?.toLongOrNull()?.let { builder.timeout(Duration.ofSeconds(it)) }
            params["maxRetries"]?.toIntOrNull()?.let { builder.maxRetries(it) }
            params["logRequests"]?.toBooleanStrictOrNull()?.let { builder.logRequests(it) }
            params["logResponses"]?.toBooleanStrictOrNull()?.let { builder.logResponses(it) }

            builder.build()
        }
    }

    /**
     * Ensures OpenAI implementations are registered.
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
private val register = OpenAIRegistration
