package com.lightningkite.services.database

import kotlinx.serialization.Serializable

/** A search result and its relevance; the score's scale depends on the search. */
@Serializable
public data class ScoredResult<T>(
    val model: T,
    val score: Float,
)

/** One entry of a vector index: the [embedding] of the source row with the same [_id]. */
@Serializable
public data class VectorIndexEntry<ID : Comparable<ID>>(
    override val _id: ID,
    val embedding: Embedding,
) : HasId<ID>
