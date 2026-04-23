@file:OptIn(ExperimentalSerializationApi::class)

package com.lightningkite.services.database

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialInfo

/**
 * [InlineProperty] marks a generated [SerializableProperty] for an inline `value class`.
 *
 * Properties marked with this annotation are accessors to the wrapped type of an inline class.
 *
 * This is an opt-in feature for two reasons:
 *
 * 1. Support for inline properties is not yet supported on the `postgres` database
 * 2. Using this accessor is almost always less efficient than using the wrapper type directly.
 *
 *
 * ## Use Cases
 *
 * There are cases where you should use these properties, such as
 *
 * - An aggregate query on a type that wraps a number
 * - Doing `+=` or `-=` modifications on a type that wraps a number
 * - Doing string queries on a type that wraps a string
 *
 * Simply put, these properties should be used when the type system requires the underlying primitive.
 *
 * Please **do not use this for querying on equality**. Just use the wrapper type.
 *
 * ## Example
 *
 * ```kotlin
 * // a value class wrapping a Long
 * value class Money(val cents: Long) : Comparable<Money>
 *
 * @Serializeable
 * @GenerateDataClassPaths
 * data class Model(
 *    override val _id: Uuid,
 *    val money: Money = Money(cents = 0),
 * }
 *
 * // --- Generated properties ---
 * @InlineProperty
 * val Money_cents: SerializableProperty<Money, Long> get() = ...
 *
 * @InlineProperty
 * val <T> DataClassPath<T, Money>.cents: DataClassPath<T, Long> get() = ...
 * // ----------------------------
 *
 *
 * // == Good examples ==
 *
 * val model = Server.models.info.findOne(
 *    condition { it.money eq Money(100L) }     // use the wrapper type if you can (99% of the time)
 * )
 *
 * val totalMoneyInCents = Server.models.info.aggregate(
 *    Aggregate.Sum,
 *    Condition.Always,
 *    property = Model.money.cents              // aggregate requires Long type to work
 * )
 *
 * // == Bad example ==
 *
 * val model = Server.models.info.findOne(
 *    condition { it.money.cents eq 100L }     // Don't do this. Do the example above.
 * )
 *
 * ```
 * */
@Target(AnnotationTarget.PROPERTY)
@RequiresOptIn("Prefer using the wrapper type if possible.", RequiresOptIn.Level.WARNING)
public annotation class InlineProperty

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
