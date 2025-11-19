package com.lightningkite.services.ai.ollama

import com.lightningkite.services.ai.*
import dev.langchain4j.model.ollama.OllamaChatModel
import dev.langchain4j.model.ollama.OllamaStreamingChatModel
import dev.langchain4j.model.ollama.OllamaEmbeddingModel
import java.time.Duration

/**
 * Registers all Ollama implementations for LangChain4J Settings.
 *
 * Ollama allows running LLMs locally on your machine.
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
 * URL format: `ollama-chat://model-name?baseUrl=...&param=value`
 *
 * Supported parameters:
 * - baseUrl: Ollama server URL (default: http://localhost:11434)
 * - temperature: Sampling temperature (default: 0.8)
 * - topP: Nucleus sampling parameter (optional)
 * - topK: Top-K sampling parameter (optional)
 * - timeout: Request timeout in seconds (default: 60)
 * - numPredict: Maximum tokens to predict (optional)
 * - stop: Stop sequences, comma-separated (optional, chat only)
 *
 * Available models (must be pulled first with `ollama pull <model>`):
 * - llama3.2 (3B and 1B variants)
 * - llama3.1 (8B, 70B, 405B variants)
 * - mistral
 * - codellama
 * - phi3
 * - gemma2
 *
 * Example:
 * ```
 * ollama-chat://llama3.2?baseUrl=http://localhost:11434
 * ollama-chat://mistral?temperature=0.7
 * ```
 *
 * ## Embedding Models
 *
 * URL format: `ollama-embedding://model-name?baseUrl=...&param=value`
 *
 * Supported parameters:
 * - baseUrl: Ollama server URL (default: http://localhost:11434)
 * - timeout: Request timeout in seconds (default: 60)
 *
 * Available embedding models:
 * - nomic-embed-text (137M params, 768 dimensions)
 * - mxbai-embed-large (335M params, 1024 dimensions)
 * - all-minilm (23M params, 384 dimensions, fast)
 *
 * Example:
 * ```
 * ollama-embedding://nomic-embed-text?baseUrl=http://localhost:11434
 * ```
 */
public object OllamaRegistration {
    init {
        // Register ChatLanguageModel
        ChatLanguageModelSettings.register("ollama-chat") { name, url, context ->
            val params = parseUrlParams(url)
            val modelName = extractModelName(url)

            val builder = OllamaChatModel.builder()
                .baseUrl(params["baseUrl"] ?: "http://localhost:11434")
                .modelName(modelName.ifEmpty { "llama3.2" })

            // Optional parameters
            params["temperature"]?.toDoubleOrNull()?.let { builder.temperature(it) }
            params["topP"]?.toDoubleOrNull()?.let { builder.topP(it) }
            params["topK"]?.toIntOrNull()?.let { builder.topK(it) }
            params["timeout"]?.toLongOrNull()?.let { builder.timeout(Duration.ofSeconds(it)) }
            params["numPredict"]?.toIntOrNull()?.let { builder.numPredict(it) }
            params["stop"]?.split(",")?.let { builder.stop(it) }

            builder.build()
        }

        // Register StreamingChatLanguageModel
        StreamingChatLanguageModelSettings.register("ollama-chat") { name, url, context ->
            val params = parseUrlParams(url)
            val modelName = extractModelName(url)

            val builder = OllamaStreamingChatModel.builder()
                .baseUrl(params["baseUrl"] ?: "http://localhost:11434")
                .modelName(modelName.ifEmpty { "llama3.2" })

            // Optional parameters
            params["temperature"]?.toDoubleOrNull()?.let { builder.temperature(it) }
            params["topP"]?.toDoubleOrNull()?.let { builder.topP(it) }
            params["topK"]?.toIntOrNull()?.let { builder.topK(it) }
            params["timeout"]?.toLongOrNull()?.let { builder.timeout(Duration.ofSeconds(it)) }
            params["numPredict"]?.toIntOrNull()?.let { builder.numPredict(it) }

            builder.build()
        }

        // Register EmbeddingModel
        EmbeddingModelSettings.register("ollama-embedding") { name, url, context ->
            val params = parseUrlParams(url)
            val modelName = extractModelName(url)

            val builder = OllamaEmbeddingModel.builder()
                .baseUrl(params["baseUrl"] ?: "http://localhost:11434")
                .modelName(modelName.ifEmpty { "nomic-embed-text" })

            // Optional parameters
            params["timeout"]?.toLongOrNull()?.let { builder.timeout(Duration.ofSeconds(it)) }

            builder.build()
        }
    }

    /**
     * Ensures Ollama implementations are registered.
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
private val register = OllamaRegistration
