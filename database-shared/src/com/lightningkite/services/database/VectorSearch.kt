package com.lightningkite.services.database

import kotlinx.serialization.Serializable

/**
 * Result from a vector similarity search, including the similarity score.
 *
 * @param T The model type
 * @property model The matched record
 * @property score Similarity score (interpretation depends on metric, normalized to 0-1 where possible)
 */
@Serializable
public data class ScoredResult<T>(
    val model: T,
    val score: Float,
)

/**
 * Parameters for vector search operations.
 *
 * @param V The vector type (Embedding or SparseEmbedding)
 * @property queryVector The vector to search for similar items
 * @property metric The similarity metric to use
 * @property limit Maximum number of results to return
 * @property minScore Minimum similarity score threshold (results below this are filtered out)
 * @property numCandidates Number of candidates for approximate search (higher = better accuracy, slower).
 *   Recommended: 10-20x limit. Ignored by backends that don't support approximate search.
 * @property exact Force exact nearest neighbor search instead of approximate.
 *   Slower but guarantees exact results.
 */
@Serializable
public data class VectorSearchParams<V>(
    val queryVector: V,
    val metric: SimilarityMetric = SimilarityMetric.Cosine,
    val limit: Int = 10,
    val minScore: Float? = null,
    val numCandidates: Int? = null,
    val exact: Boolean = false,
)

/** Type alias for dense vector search parameters */
public typealias DenseVectorSearchParams = VectorSearchParams<Embedding>

/** Type alias for sparse vector search parameters */
public typealias SparseVectorSearchParams = VectorSearchParams<SparseEmbedding>
