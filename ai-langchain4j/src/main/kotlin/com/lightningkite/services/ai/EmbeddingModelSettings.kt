package com.lightningkite.services.ai

import com.lightningkite.services.Setting
import com.lightningkite.services.SettingContext
import com.lightningkite.services.UrlSettingParser
import dev.langchain4j.model.embedding.EmbeddingModel
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Settings for instantiating a LangChain4J [EmbeddingModel].
 *
 * EmbeddingModel converts text into vector embeddings for semantic search,
 * similarity comparisons, and RAG (Retrieval-Augmented Generation) applications.
 *
 * The URL scheme determines which embedding provider to use:
 * - `openai-embedding://model-name?apiKey=...` - OpenAI (text-embedding-3-small, etc.)
 * - `ollama-embedding://model-name?baseUrl=...` - Ollama (nomic-embed-text, etc.)
 * - Other schemes registered by implementation modules
 *
 * Example usage:
 * ```kotlin
 * val settings = EmbeddingModelSettings("openai-embedding://text-embedding-3-small?apiKey=\${OPENAI_API_KEY}")
 * val model: EmbeddingModel = settings("my-embedding-model", context)
 * val embedding = model.embed("Hello, world!")
 * println("Dimension: ${embedding.vector().size}")
 * ```
 *
 * @property url Connection string defining the provider and configuration
 */
@Serializable
@JvmInline
public value class EmbeddingModelSettings(
    public val url: String
) : Setting<EmbeddingModel> {

    public companion object : UrlSettingParser<EmbeddingModel>()

    override fun invoke(name: String, context: SettingContext): EmbeddingModel {
        return parse(name, url, context)
    }
}
