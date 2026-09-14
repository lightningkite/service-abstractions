package com.lightningkite.services.embedding

import com.lightningkite.services.Service
import com.lightningkite.services.Setting
import com.lightningkite.services.SettingContext
import com.lightningkite.services.UrlSettingParser
import com.lightningkite.services.database.Embedding
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Provider-agnostic text embedding service. Converts text into dense vector
 * representations suitable for similarity search, clustering, and retrieval.
 *
 * Implementations register URL schemes on [Settings.Companion] and are
 * instantiated via URL-based configuration, e.g.:
 * ```
 * openai://text-embedding-3-small?apiKey=${OPENAI_API_KEY}
 * ```
 */
public interface EmbeddingService : Service {

    /** Returns the embedding models available from this provider. */
    public suspend fun getModels(): List<EmbeddingModelInfo>

    /**
     * Embed one or more texts into dense vectors.
     *
     * @param texts Input texts to embed. Providers may impose per-request batch limits
     *   (e.g. OpenAI caps at 2048); implementations should chunk internally when needed.
     * @param model Which embedding model to use.
     * @return Embeddings in the same order as [texts], plus token usage.
     */
    public suspend fun embed(texts: List<String>, model: EmbeddingModelId): EmbeddingResult

    /**
     * URL-based configuration for an [EmbeddingService] implementation.
     *
     * Provider modules register their URL schemes via this parser's companion object.
     *
     * @property url Connection string defining provider, model, and options.
     */
    @Serializable
    @JvmInline
    public value class Settings(public val url: String) : Setting<EmbeddingService> {
        override fun invoke(name: String, context: SettingContext): EmbeddingService =
            parse(name, url, context)

        public companion object : UrlSettingParser<EmbeddingService>()
    }
}

/**
 * Convenience: embed a single text and return just the vector.
 */
public suspend fun EmbeddingService.embed(text: String, model: EmbeddingModelId): Embedding {
    return embed(listOf(text), model).embeddings.first()
}

/**
 * Convenience: embed multiple texts and return just the vectors (no usage info).
 */
public suspend fun EmbeddingService.embedTexts(texts: List<String>, model: EmbeddingModelId): List<Embedding> {
    return embed(texts, model).embeddings
}
