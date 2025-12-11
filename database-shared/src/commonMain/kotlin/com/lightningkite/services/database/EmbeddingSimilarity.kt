package com.lightningkite.services.database

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Utility functions for computing similarity between embeddings.
 * Used by in-memory implementations and for condition evaluation.
 */
public object EmbeddingSimilarity {

    // ===== Dense Embedding Operations =====

    public fun cosineSimilarity(a: Embedding, b: Embedding): Float {
        require(a.dimensions == b.dimensions) { "Dimension mismatch: ${a.dimensions} vs ${b.dimensions}" }
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.values.indices) {
            dot += a.values[i] * b.values[i]
            normA += a.values[i] * a.values[i]
            normB += b.values[i] * b.values[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        if (denom == 0f) return 0f
        val cosine = dot / denom
        // Normalize from [-1, 1] to [0, 1]
        return (cosine + 1f) / 2f
    }

    public fun dotProduct(a: Embedding, b: Embedding): Float {
        require(a.dimensions == b.dimensions) { "Dimension mismatch" }
        var sum = 0f
        for (i in a.values.indices) {
            sum += a.values[i] * b.values[i]
        }
        return sum
    }

    public fun euclideanDistance(a: Embedding, b: Embedding): Float {
        require(a.dimensions == b.dimensions) { "Dimension mismatch" }
        var sum = 0f
        for (i in a.values.indices) {
            val diff = a.values[i] - b.values[i]
            sum += diff * diff
        }
        return sqrt(sum)
    }

    /** Euclidean distance normalized to [0, 1] similarity score */
    public fun euclideanSimilarity(a: Embedding, b: Embedding): Float {
        return 1f / (1f + euclideanDistance(a, b))
    }

    public fun manhattanDistance(a: Embedding, b: Embedding): Float {
        require(a.dimensions == b.dimensions) { "Dimension mismatch" }
        var sum = 0f
        for (i in a.values.indices) {
            sum += abs(a.values[i] - b.values[i])
        }
        return sum
    }

    /** Manhattan distance normalized to [0, 1] similarity score */
    public fun manhattanSimilarity(a: Embedding, b: Embedding): Float {
        return 1f / (1f + manhattanDistance(a, b))
    }

    /** Compute similarity using specified metric, returns normalized [0,1] score where possible */
    public fun similarity(a: Embedding, b: Embedding, metric: SimilarityMetric): Float {
        return when (metric) {
            SimilarityMetric.Cosine -> cosineSimilarity(a, b)
            SimilarityMetric.DotProduct -> dotProduct(a, b) // Not normalized
            SimilarityMetric.Euclidean -> euclideanSimilarity(a, b)
            SimilarityMetric.Manhattan -> manhattanSimilarity(a, b)
        }
    }

    /** Compute raw distance using specified metric */
    public fun distance(a: Embedding, b: Embedding, metric: SimilarityMetric): Float {
        return when (metric) {
            SimilarityMetric.Cosine -> 1f - cosineSimilarity(a, b) // Convert similarity to distance
            SimilarityMetric.DotProduct -> -dotProduct(a, b) // Negate for distance interpretation
            SimilarityMetric.Euclidean -> euclideanDistance(a, b)
            SimilarityMetric.Manhattan -> manhattanDistance(a, b)
        }
    }

    // ===== Sparse Embedding Operations =====

    public fun cosineSimilarity(a: SparseEmbedding, b: SparseEmbedding): Float {
        require(a.dimensions == b.dimensions) { "Dimension mismatch" }
        var dot = 0f
        var normA = 0f
        var normB = 0f

        // Compute norms
        for (v in a.values) normA += v * v
        for (v in b.values) normB += v * v

        // Compute dot product (only overlapping indices matter)
        var i = 0
        var j = 0
        while (i < a.indices.size && j < b.indices.size) {
            when {
                a.indices[i] == b.indices[j] -> {
                    dot += a.values[i] * b.values[j]
                    i++
                    j++
                }
                a.indices[i] < b.indices[j] -> i++
                else -> j++
            }
        }

        val denom = sqrt(normA) * sqrt(normB)
        if (denom == 0f) return 0f
        val cosine = dot / denom
        return (cosine + 1f) / 2f
    }

    public fun dotProduct(a: SparseEmbedding, b: SparseEmbedding): Float {
        require(a.dimensions == b.dimensions) { "Dimension mismatch" }
        var dot = 0f
        var i = 0
        var j = 0
        while (i < a.indices.size && j < b.indices.size) {
            when {
                a.indices[i] == b.indices[j] -> {
                    dot += a.values[i] * b.values[j]
                    i++
                    j++
                }
                a.indices[i] < b.indices[j] -> i++
                else -> j++
            }
        }
        return dot
    }

    public fun euclideanDistance(a: SparseEmbedding, b: SparseEmbedding): Float {
        require(a.dimensions == b.dimensions) { "Dimension mismatch" }
        var sum = 0f
        var i = 0
        var j = 0
        while (i < a.indices.size || j < b.indices.size) {
            val aIdx = if (i < a.indices.size) a.indices[i] else Int.MAX_VALUE
            val bIdx = if (j < b.indices.size) b.indices[j] else Int.MAX_VALUE
            when {
                aIdx == bIdx -> {
                    val diff = a.values[i] - b.values[j]
                    sum += diff * diff
                    i++
                    j++
                }
                aIdx < bIdx -> {
                    sum += a.values[i] * a.values[i]
                    i++
                }
                else -> {
                    sum += b.values[j] * b.values[j]
                    j++
                }
            }
        }
        return sqrt(sum)
    }

    public fun euclideanSimilarity(a: SparseEmbedding, b: SparseEmbedding): Float {
        return 1f / (1f + euclideanDistance(a, b))
    }

    public fun manhattanDistance(a: SparseEmbedding, b: SparseEmbedding): Float {
        require(a.dimensions == b.dimensions) { "Dimension mismatch" }
        var sum = 0f
        var i = 0
        var j = 0
        while (i < a.indices.size || j < b.indices.size) {
            val aIdx = if (i < a.indices.size) a.indices[i] else Int.MAX_VALUE
            val bIdx = if (j < b.indices.size) b.indices[j] else Int.MAX_VALUE
            when {
                aIdx == bIdx -> {
                    sum += abs(a.values[i] - b.values[j])
                    i++
                    j++
                }
                aIdx < bIdx -> {
                    sum += abs(a.values[i])
                    i++
                }
                else -> {
                    sum += abs(b.values[j])
                    j++
                }
            }
        }
        return sum
    }

    public fun manhattanSimilarity(a: SparseEmbedding, b: SparseEmbedding): Float {
        return 1f / (1f + manhattanDistance(a, b))
    }

    public fun similarity(a: SparseEmbedding, b: SparseEmbedding, metric: SimilarityMetric): Float {
        return when (metric) {
            SimilarityMetric.Cosine -> cosineSimilarity(a, b)
            SimilarityMetric.DotProduct -> dotProduct(a, b)
            SimilarityMetric.Euclidean -> euclideanSimilarity(a, b)
            SimilarityMetric.Manhattan -> manhattanSimilarity(a, b)
        }
    }

    public fun distance(a: SparseEmbedding, b: SparseEmbedding, metric: SimilarityMetric): Float {
        return when (metric) {
            SimilarityMetric.Cosine -> 1f - cosineSimilarity(a, b)
            SimilarityMetric.DotProduct -> -dotProduct(a, b)
            SimilarityMetric.Euclidean -> euclideanDistance(a, b)
            SimilarityMetric.Manhattan -> manhattanDistance(a, b)
        }
    }
}
