package com.lightningkite.services.ai.koog

import ai.koog.embeddings.base.Vector as KoogVector
import com.lightningkite.services.database.Embedding as DbEmbedding
import com.lightningkite.services.database.SparseEmbedding as DbSparseEmbedding

/**
 * Adapters between Koog AI embeddings and database embeddings.
 *
 * Koog uses [KoogVector] which stores embeddings as `List<Double>`,
 * while the database module uses [DbEmbedding] which stores as `FloatArray`
 * for more efficient storage and computation.
 *
 * Example usage:
 * ```kotlin
 * // Get embedding from Koog embedder
 * val koogEmbedding: Vector = embedder.embed("query text")
 *
 * // Convert to database embedding for storage/search
 * val dbEmbedding: Embedding = koogEmbedding.toDbEmbedding()
 *
 * // Use in vector search
 * val results = table.findSimilar(
 *     vectorField = Document.path.embedding,
 *     params = VectorSearchParams(dbEmbedding, limit = 10)
 * )
 * ```
 */
public object EmbeddingAdapter {

    /**
     * Convert Koog embedding to database embedding.
     *
     * @param koogVector The Koog Vector (List<Double>)
     * @return Database Embedding (FloatArray)
     */
    public fun toDatabase(koogVector: KoogVector): DbEmbedding {
        return DbEmbedding(FloatArray(koogVector.values.size) { koogVector.values[it].toFloat() })
    }

    /**
     * Convert database embedding to Koog embedding.
     *
     * @param dbEmbedding The database Embedding (FloatArray)
     * @return Koog Vector (List<Double>)
     */
    public fun toKoog(dbEmbedding: DbEmbedding): KoogVector {
        return KoogVector(dbEmbedding.values.map { it.toDouble() })
    }
}

/**
 * Extension function to convert Koog Vector to database Embedding.
 */
public fun KoogVector.toDbEmbedding(): DbEmbedding = EmbeddingAdapter.toDatabase(this)

/**
 * Extension function to convert database Embedding to Koog Vector.
 */
public fun DbEmbedding.toKoogVector(): KoogVector = EmbeddingAdapter.toKoog(this)
