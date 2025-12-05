package com.lightningkite.services.database

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer

/**
 * Type-safe interface for querying and modifying a database collection/table.
 *
 * Table provides a uniform CRUD API across different database backends using
 * [Condition] for queries and [Modification] for updates. All operations are
 * type-safe via the generic Model parameter.
 *
 * ## Core Operations
 *
 * ### Queries
 * - [find] - Stream matching records with sorting and pagination
 * - [count] - Count matching records
 * - [aggregate] - Compute statistics (sum, average, etc.)
 *
 * ### Writes
 * - [insert] - Insert new records
 * - [updateOne]/[updateMany] - Modify existing records
 * - [replaceOne] - Replace entire record
 * - [upsertOne] - Insert or update (atomic)
 * - [deleteOne]/[deleteMany] - Remove records
 *
 * ## Usage Examples
 *
 * ```kotlin
 * val userTable: Table<User> = database.table<User>()
 *
 * // Query
 * val adults = userTable.find(
 *     condition = User.path.age gte 18,
 *     orderBy = listOf(SortPart(User.path.name, ascending = true)),
 *     limit = 100
 * ).toList()
 *
 * // Update
 * userTable.updateOne(
 *     condition = User.path._id eq userId,
 *     modification = modification<User> { it ->
 *         it.age += 1
 *         it.lastLoginAt assign Clock.System.now()
 *     }
 * )
 *
 * // Insert
 * val newUser = User(name = "Alice", age = 30)
 * userTable.insert(listOf(newUser))
 *
 * // Delete
 * userTable.deleteMany(condition = User.path.active eq false)
 * ```
 *
 * ## Performance Variants
 *
 * Most operations have "*IgnoringResult" variants that skip returning old values:
 *
 * ```kotlin
 * // Returns EntryChange<User> with old and new values
 * val change = userTable.updateOne(condition, modification)
 *
 * // Returns Boolean - faster, less memory
 * val wasUpdated = userTable.updateOneIgnoringResult(condition, modification)
 * ```
 *
 * Use ignoring-result variants when you don't need the old/new values for better performance.
 *
 * ## Streaming Results
 *
 * [find] returns a Flow<Model> that streams results incrementally:
 *
 * ```kotlin
 * userTable.find(condition).collect { user ->
 *     processUser(user)  // Handles one at a time
 * }
 * ```
 *
 * This is memory-efficient for large result sets.
 *
 * ## Important Gotchas
 *
 * - **Ordering**: Without orderBy, result order is database-dependent
 * - **Transactions**: Not all backends support multi-document transactions
 * - **Indexes**: Ensure proper indexes for performance (use @Index annotation)
 * - **Serialization**: Model must be @Serializable and registered in SerializersModule
 * - **maxQueryMs**: Prevents runaway queries (default: 15 seconds)
 *
 * @param Model The data model type stored in this table (must be @Serializable)
 * @property serializer KSerializer for Model type
 * @see Database
 * @see Condition
 * @see Modification
 */
public interface Table<Model : Any> {
    public val serializer: KSerializer<Model>

    /**
     * The field collection this wraps, if any.
     */
    public val wraps: Table<Model>? get() = null

    /**
     * The full condition that will be sent to the database in the end.  Used to help analyze security rules.
     */
    public suspend fun fullCondition(condition: Condition<Model>): Condition<Model> = condition

    /**
     * The mask that will be used on data coming out of the database.  Used to help analyze security rules.
     */
    public suspend fun mask(): Mask<Model> = Mask()

    /**
     * Query for items in the collection.
     */
    public suspend fun find(
        condition: Condition<Model>,
        orderBy: List<SortPart<Model>> = listOf(),
        skip: Int = 0,
        limit: Int = Int.MAX_VALUE,
        maxQueryMs: Long = 15_000
    ): Flow<Model>

    /**
     * Query for items in the collection.
     */
    public suspend fun findPartial(
        fields: Set<DataClassPathPartial<Model>>,
        condition: Condition<Model>,
        orderBy: List<SortPart<Model>> = listOf(),
        skip: Int = 0,
        limit: Int = Int.MAX_VALUE,
        maxQueryMs: Long = 15_000,
    ): Flow<Partial<Model>> = find(
        condition = condition,
        orderBy = orderBy,
        skip = skip,
        limit = limit,
        maxQueryMs = maxQueryMs
    ).map {
        partialOf(it, fields)
    }

    /**
     * Count the number of matching items in the collection.
     */
    public suspend fun count(
        condition: Condition<Model> = Condition.Always
    ): Int

    /**
     * Count the number of matching items in each group.
     */
    public suspend fun <Key> groupCount(
        condition: Condition<Model> = Condition.Always,
        groupBy: DataClassPath<Model, Key>
    ): Map<Key, Int>

    /**
     * Aggregate a particular numerical field on all matching items.
     */
    public suspend fun <N : Number?> aggregate(
        aggregate: Aggregate,
        condition: Condition<Model> = Condition.Always,
        property: DataClassPath<Model, N>
    ): Double?

    /**
     * Aggregate a particular numerical field on all matching items by group.
     */
    public suspend fun <N : Number?, Key> groupAggregate(
        aggregate: Aggregate,
        condition: Condition<Model> = Condition.Always,
        groupBy: DataClassPath<Model, Key>,
        property: DataClassPath<Model, N>
    ): Map<Key, Double?>


    /**
     * Insert items into the collection.
     * @return The items that were actually inserted in the end.
     */
    public suspend fun insert(
        models: Iterable<Model>
    ): List<Model>


    /**
     * Replaces a single item via a condition.
     * @return The old and new items.
     */
    public suspend fun replaceOne(
        condition: Condition<Model>,
        model: Model,
        orderBy: List<SortPart<Model>> = listOf()
    ): EntryChange<Model>

    /**
     * Replaces a single item via a condition.
     * @return If a change was made to the database.
     */
    public suspend fun replaceOneIgnoringResult(
        condition: Condition<Model>,
        model: Model,
        orderBy: List<SortPart<Model>> = listOf()
    ): Boolean

    /**
     * Inserts an item if it doesn't exist, but otherwise modifies it.
     * @return The old and new items.
     */
    public suspend fun upsertOne(
        condition: Condition<Model>,
        modification: Modification<Model>,
        model: Model
    ): EntryChange<Model>

    /**
     * Inserts an item if it doesn't exist, but otherwise modifies it.
     * @return If there was an existing element that matched the condition.
     */
    public suspend fun upsertOneIgnoringResult(
        condition: Condition<Model>,
        modification: Modification<Model>,
        model: Model
    ): Boolean

    /**
     * Updates a single item in the collection.
     * @return The old and new items.
     */
    public suspend fun updateOne(
        condition: Condition<Model>,
        modification: Modification<Model>,
        orderBy: List<SortPart<Model>> = listOf(),
    ): EntryChange<Model>

    /**
     * Updates a single item in the collection.
     * @return If a change was made to the database.
     */
    public suspend fun updateOneIgnoringResult(
        condition: Condition<Model>,
        modification: Modification<Model>,
        orderBy: List<SortPart<Model>> = listOf()
    ): Boolean


    /**
     * Updates many items in the collection.
     * @return The changes made to the collection.
     */
    public suspend fun updateMany(
        condition: Condition<Model>,
        modification: Modification<Model>,
    ): CollectionChanges<Model>

    /**
     * Updates many items in the collection.
     * @return The number of entries affected.
     */
    public suspend fun updateManyIgnoringResult(
        condition: Condition<Model>,
        modification: Modification<Model>,
    ): Int

    /**
     * Deletes a single item from the collection.
     * @return The item removed from the collection.
     */
    public suspend fun deleteOne(
        condition: Condition<Model>,
        orderBy: List<SortPart<Model>> = listOf()
    ): Model?

    /**
     * Deletes a single item from the collection.
     * @return Whether any items were deleted.
     */
    public suspend fun deleteOneIgnoringOld(
        condition: Condition<Model>,
        orderBy: List<SortPart<Model>> = listOf()
    ): Boolean


    /**
     * Deletes many items from the collection.
     * @return The item removed from the collection.
     */
    public suspend fun deleteMany(
        condition: Condition<Model>
    ): List<Model>

    /**
     * Deletes many items from the collection.
     * @return The number of deleted items.
     */
    public suspend fun deleteManyIgnoringOld(
        condition: Condition<Model>
    ): Int

    // ===== Vector Search Methods =====

    /**
     * Find records similar to a query vector, ranked by similarity.
     *
     * Results are returned in order of similarity (most similar first).
     * This is the primary method for semantic/vector search.
     *
     * @param vectorField Path to the embedding field to search
     * @param params Vector search parameters (query vector, metric, limits)
     * @param condition Additional filter condition (pre-filter for databases that support it)
     * @param maxQueryMs Query timeout in milliseconds
     * @return Flow of scored results, ordered by similarity (highest first)
     *
     * ```kotlin
     * val results = documentTable.findSimilar(
     *     vectorField = Document.path.embedding,
     *     params = VectorSearchParams(
     *         queryVector = embedder.embed("machine learning"),
     *         limit = 10,
     *         minScore = 0.7f
     *     ),
     *     condition = Document.path.category eq "tech"
     * )
     * ```
     *
     * @throws UnsupportedOperationException if the backend does not support vector search
     */
    public suspend fun findSimilar(
        vectorField: DataClassPath<Model, Embedding>,
        params: DenseVectorSearchParams,
        condition: Condition<Model> = Condition.Always,
        maxQueryMs: Long = 15_000,
    ): Flow<ScoredResult<Model>> {
        throw UnsupportedOperationException(
            "This database backend does not support vector search. " +
            "Use InMemoryDatabase for testing or a vector-capable backend (MongoDB Atlas, PostgreSQL+pgvector)."
        )
    }

    /**
     * Find records similar to a sparse query vector, ranked by similarity.
     *
     * @param vectorField Path to the sparse embedding field to search
     * @param params Sparse vector search parameters
     * @param condition Additional filter condition
     * @param maxQueryMs Query timeout in milliseconds
     * @return Flow of scored results, ordered by similarity (highest first)
     *
     * @see findSimilar for dense vectors
     * @throws UnsupportedOperationException if the backend does not support sparse vector search
     */
    public suspend fun findSimilarSparse(
        vectorField: DataClassPath<Model, SparseEmbedding>,
        params: SparseVectorSearchParams,
        condition: Condition<Model> = Condition.Always,
        maxQueryMs: Long = 15_000,
    ): Flow<ScoredResult<Model>> {
        throw UnsupportedOperationException(
            "This database backend does not support sparse vector search."
        )
    }
}

// TODO: API Recommendation - Add batch insert optimization
//  insert() currently takes an Iterable and inserts all records.
//  Consider adding insertBatch() that takes a batch size parameter for chunked inserts,
//  which can significantly improve performance for large datasets by reducing round trips.
//  Example: suspend fun insertBatch(models: Iterable<Model>, batchSize: Int = 1000): List<Model>
//
// TODO: API Recommendation - Add cursor-based pagination
//  Current skip/limit pagination is inefficient for large offsets.
//  Add cursor-based pagination using _id or custom fields:
//  suspend fun findAfter(cursor: Model?, condition: Condition<Model>, limit: Int): Flow<Model>
//  This would enable efficient "infinite scroll" patterns.
//
// TODO: API Recommendation - Consider adding findOne convenience method
//  Common pattern is find().firstOrNull() for single results.
//  Add: suspend fun findOne(condition: Condition<Model>, orderBy: List<SortPart<Model>> = listOf()): Model?
//  This would allow backends to optimize single-result queries (LIMIT 1)
