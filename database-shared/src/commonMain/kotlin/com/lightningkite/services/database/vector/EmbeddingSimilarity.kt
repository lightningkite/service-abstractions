@file:Suppress("PackageDirectoryMismatch")
package com.lightningkite.services.database

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Utility functions for computing similarity between embeddings.
 * Used by in-memory implementations and for condition evaluation.
 */
public object EmbeddingSimilarity {

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
}
