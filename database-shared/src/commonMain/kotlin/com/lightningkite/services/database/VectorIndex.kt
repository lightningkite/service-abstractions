package com.lightningkite.services.database

import kotlinx.serialization.SerialInfo

/**
 * Marks an [Embedding] or [SparseEmbedding] field for vector indexing.
 *
 * When the database schema is created/migrated, backends should create
 * appropriate vector indexes based on this annotation.
 *
 * ```kotlin
 * @GenerateDataClassPaths
 * @Serializable
 * data class Document(
 *     override val _id: UUID = UUID.random(),
 *     val title: String,
 *     @VectorIndex(dimensions = 1536)
 *     val embedding: Embedding,
 *     @VectorIndex(dimensions = 30000, sparse = true)
 *     val sparseEmbedding: SparseEmbedding,
 * ) : HasId<UUID>
 * ```
 *
 * ## Backend-Specific Behavior
 *
 * **MongoDB Atlas:**
 * - Creates a vector search index with the specified dimensions and metric
 * - indexType "hnsw" is the default and recommended
 *
 * **PostgreSQL (pgvector):**
 * - Creates an HNSW or IVFFlat index based on indexType
 * - indexType "hnsw" creates: `CREATE INDEX ... USING hnsw (field vector_cosine_ops)`
 * - indexType "ivfflat" creates: `CREATE INDEX ... USING ivfflat (field vector_cosine_ops)`
 *
 * **In-Memory:**
 * - Annotation is informational only; no index is created
 *
 * @param dimensions Expected embedding dimensions (required for index creation)
 * @param metric Default similarity metric for queries on this field
 * @param sparse True for sparse vector indexes (SparseEmbedding fields)
 * @param indexType Backend-specific index type hint:
 *   - "auto" (default): Let backend choose optimal index type
 *   - "hnsw": Hierarchical Navigable Small World (better query performance)
 *   - "ivfflat": Inverted File Flat (faster builds, lower memory)
 *   - "exact": No approximate index, use exact search
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
public annotation class VectorIndex(
    val dimensions: Int,
    val metric: SimilarityMetric = SimilarityMetric.Cosine,
    val sparse: Boolean = false,
    val indexType: String = "auto",
)
