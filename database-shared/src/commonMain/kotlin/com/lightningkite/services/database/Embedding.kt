package com.lightningkite.services.database

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Represents a dense vector embedding for similarity search.
 *
 * Dense embeddings have a value for every dimension. Typically produced by
 * models like OpenAI text-embedding-3-small, Ollama nomic-embed-text, etc.
 *
 * @property values The embedding vector values (one per dimension)
 */
@Serializable(EmbeddingSerializer::class)
@JvmInline
public value class Embedding(public val values: FloatArray) {
    public val dimensions: Int get() = values.size

    public companion object {
        public fun of(vararg values: Float): Embedding = Embedding(values)
        public fun fromDoubles(values: DoubleArray): Embedding =
            Embedding(FloatArray(values.size) { values[it].toFloat() })
        public fun fromList(values: List<Number>): Embedding =
            Embedding(FloatArray(values.size) { values[it].toFloat() })
    }

    override fun toString(): String = "Embedding(${values.size} dims)"
}

// equals/hashCode for value class with array need special handling via serializer
