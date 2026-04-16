package com.lightningkite.services.embedding.test

import com.lightningkite.services.database.Embedding

public val SAMPLE_TEXTS: List<String> = listOf(
    "The quick brown fox jumps over the lazy dog.",
    "Machine learning models process large datasets.",
    "The weather in Paris is pleasant in spring.",
)

/** Asserts the embedding has at least [minDimensions] dimensions and no NaN values. */
public fun assertValidEmbedding(embedding: Embedding, minDimensions: Int = 1) {
    require(embedding.dimensions >= minDimensions) {
        "Expected at least $minDimensions dimensions; got ${embedding.dimensions}"
    }
    for (i in embedding.values.indices) {
        require(!embedding.values[i].isNaN()) {
            "Embedding contains NaN at index $i"
        }
    }
}
