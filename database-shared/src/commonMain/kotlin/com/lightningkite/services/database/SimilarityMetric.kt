package com.lightningkite.services.database

import kotlinx.serialization.Serializable

/**
 * Distance/similarity metrics for vector search.
 */
@Serializable
public enum class SimilarityMetric {
    /**
     * Cosine similarity (angle between vectors, ignores magnitude).
     * Range: -1 to 1, normalized to 0 to 1 where possible.
     * Higher = more similar.
     */
    Cosine,

    /**
     * Euclidean (L2) distance.
     * Range: 0 to infinity. Lower = more similar.
     * Scores normalized to 0-1 via 1/(1+distance) where supported.
     */
    Euclidean,

    /**
     * Dot product (inner product).
     * Best for normalized vectors. Higher = more similar.
     * No normalization applied (values depend on vector magnitudes).
     */
    DotProduct,

    /**
     * Manhattan (L1/Taxicab) distance.
     * Range: 0 to infinity. Lower = more similar.
     * Scores normalized to 0-1 via 1/(1+distance) where supported.
     */
    Manhattan
}
