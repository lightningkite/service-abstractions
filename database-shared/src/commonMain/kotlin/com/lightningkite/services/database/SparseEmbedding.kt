package com.lightningkite.services.database

import kotlinx.serialization.Serializable

/**
 * Represents a sparse vector embedding for similarity search.
 *
 * Sparse embeddings only store non-zero values, making them efficient for
 * high-dimensional but sparse representations. Typically produced by models
 * like SPLADE, BM25 variants, or learned sparse retrievers.
 *
 * @property indices The indices of non-zero values (sorted ascending)
 * @property values The non-zero values corresponding to each index
 * @property dimensions The total dimension count of the full vector
 */
@Serializable
public data class SparseEmbedding(
    val indices: IntArray,
    val values: FloatArray,
    val dimensions: Int,
) {
    init {
        require(indices.size == values.size) {
            "indices and values must have same length: ${indices.size} vs ${values.size}"
        }
        require(indices.all { it in 0 until dimensions }) {
            "All indices must be within [0, dimensions)"
        }
    }

    public val nonZeroCount: Int get() = indices.size

    /** Get value at index, returns 0 if not present */
    public operator fun get(index: Int): Float {
        val pos = indices.asList().binarySearch(index)
        return if (pos >= 0) values[pos] else 0f
    }

    public companion object {
        public fun fromMap(map: Map<Int, Float>, dimensions: Int): SparseEmbedding {
            val sorted = map.entries.sortedBy { it.key }
            return SparseEmbedding(
                indices = sorted.map { it.key }.toIntArray(),
                values = sorted.map { it.value }.toFloatArray(),
                dimensions = dimensions
            )
        }

        /** Convert dense embedding to sparse (keeping only non-zero values) */
        public fun fromDense(embedding: Embedding, threshold: Float = 0f): SparseEmbedding {
            val nonZero = embedding.values.withIndex()
                .filter { kotlin.math.abs(it.value) > threshold }
            return SparseEmbedding(
                indices = nonZero.map { it.index }.toIntArray(),
                values = nonZero.map { it.value }.toFloatArray(),
                dimensions = embedding.dimensions
            )
        }
    }

    override fun equals(other: Any?): Boolean =
        other is SparseEmbedding &&
                dimensions == other.dimensions &&
                indices.contentEquals(other.indices) &&
                values.contentEquals(other.values)

    override fun hashCode(): Int =
        31 * (31 * indices.contentHashCode() + values.contentHashCode()) + dimensions

    override fun toString(): String =
        "SparseEmbedding(${nonZeroCount}/${dimensions} non-zero)"
}
