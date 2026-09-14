package com.lightningkite.services.embedding

import com.lightningkite.services.database.Embedding
import kotlinx.serialization.Serializable

@Serializable
public data class EmbeddingModelId(
    val id: String,
    /** Name of the [EmbeddingService] that serves this model. Used for routing when multiple services are loaded. */
    val access: String? = null,
)

@Serializable
public data class EmbeddingModelInfo(
    val id: EmbeddingModelId,
    val name: String,
    val description: String? = null,
    /** Output vector dimensionality. Null means unknown (e.g. custom or self-hosted model). */
    val dimensions: Int? = null,
    /** Maximum input tokens the model accepts. Null means unknown. */
    val maxInputTokens: Int? = null,
    /** Cost in USD per million input tokens. */
    val usdPerMillionTokens: Double = 0.0,
)

@Serializable
public data class EmbeddingResult(
    /** Embedding vectors in the same order as the input texts. */
    val embeddings: List<Embedding>,
    val usage: EmbeddingUsage,
)

@Serializable
public data class EmbeddingUsage(
    /** Total input tokens consumed across all texts in the batch. */
    val inputTokens: Int,
)
