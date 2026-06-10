package com.lightningkite.services.database.postgres

import com.lightningkite.services.MetricAttributes
import com.lightningkite.services.MetricAttributesBuilder
import com.lightningkite.services.MetricSpan
import com.lightningkite.services.Namespaced
import com.lightningkite.services.SettingContext
import com.lightningkite.services.metricsTrace
import com.lightningkite.services.database.*
import com.lightningkite.services.database.Table
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.modules.SerializersModule
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils.statementsRequiredToActualizeScheme
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.sql.Connection.TRANSACTION_READ_COMMITTED
import java.sql.Connection.TRANSACTION_SERIALIZABLE

public class PostgresCollection<T : Any>(
    public val db: Database,
    override val name: String,
    override val serializer: KSerializer<T>,
    public val serializersModule: SerializersModule,
    override val context: SettingContext,
) : Table<T>, Namespaced {
    private var format = DbMapLikeFormat(serializersModule)

    private val table = SerialDescriptorTable(name, serializersModule, serializer.descriptor)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    /** Cancels the internal scope, releasing the prepare job and its resources. Called by [PostgresDatabase.disconnect]. */
    internal fun close() {
        scope.cancel()
    }

    private suspend inline fun <T> t(noinline action: suspend Transaction.() -> T): T =
        newSuspendedTransaction(
            Dispatchers.IO,
            db = db,
            transactionIsolation = TRANSACTION_READ_COMMITTED,
            statement = {
                action()
            })

    // Per-collection default span attributes shared by every operation.
    private fun baseAttributes(operation: String): MetricAttributes = MetricAttributes {
        put(com.lightningkite.services.database.Database.MetricKeys.system, "postgresql")
        put(com.lightningkite.services.database.Database.MetricKeys.operation, operation)
        put(com.lightningkite.services.database.Database.MetricKeys.collection, name)
    }

    // Thin wrapper over metricsTrace: opens the `<name>.<operation>` span (with the postgres-specific
    // default attributes plus any caller-supplied [extra]), records the RED metric inside it, and
    // hands the started span to [block] for dynamic per-result attributes.
    private suspend inline fun <R> traced(
        operation: String,
        noinline extraBlock: (MetricAttributesBuilder.() -> Unit)? = null,
        noinline block: suspend (MetricSpan) -> R,
    ): R {
        val attrs = if (extraBlock != null) MetricAttributes { putAll(baseAttributes(operation)); extraBlock() } else baseAttributes(operation)
        return metricsTrace(operation, attributes = attrs, action = block)
    }

    @OptIn(ExperimentalSerializationApi::class)
    private val prepare = scope.async(Dispatchers.Unconfined, start = CoroutineStart.LAZY) {
        t {
//            MigrationUtils.statementsRequiredForDatabaseMigration
            statementsRequiredToActualizeScheme(table).forEach {
                exec(it)
            }
        }
    }

    override suspend fun find(
        condition: Condition<T>,
        orderBy: List<SortPart<T>>,
        skip: Int,
        limit: Int,
        maxQueryMs: Long,
    ): Flow<T> = traced(
        operation = "find",
        extraBlock = {
            put(com.lightningkite.services.database.Database.MetricKeys.limit, limit.toLong())
            put(com.lightningkite.services.database.Database.MetricKeys.skip, skip.toLong())
        }
    ) { span ->
        prepare.await()
        val items = t {
            table
                .selectAll()
                .where { condition(condition, serializer, table, format).asOp() }
                .orderBy(*orderBy.map {
                    @Suppress("UNCHECKED_CAST")
                    (
                            if (it.field.serializerAny.descriptor.kind == PrimitiveKind.STRING && serializationOverride(
                                    it.field.serializerAny.descriptor
                                ) == null
                            ) {
                                // TODO: Check database default collation to skip extra work
                                if (it.ignoreCase) (table.col[it.field.colName]!! as Column<String>).lowerCase()
                                else AsciiValue(table.col[it.field.colName]!! as Column<String>)
                            } else table.col[it.field.colName]!!
                            ) to if (it.ascending) SortOrder.ASC else SortOrder.DESC
                }
                    .toTypedArray())
                .limit(limit).offset(skip.toLong())
                .toList()
//                .prep
                .map {
                    format.decode(serializer, it)
                }
        }
        span.enrich(MetricAttributes { put(com.lightningkite.services.database.Database.MetricKeys.resultCount, items.size.toLong()) })
        items.asFlow()
    }

    override suspend fun count(condition: Condition<T>): Int = traced(
        operation = "count"
    ) { span ->
        prepare.await()
        val result = t {
            table
                .selectAll().where { condition(condition, serializer, table, format).asOp() }
                .count().toInt()
        }
        span.enrich(MetricAttributes { put(com.lightningkite.services.database.Database.MetricKeys.count, result.toLong()) })
        result
    }

    override suspend fun <Key> groupCount(condition: Condition<T>, groupBy: DataClassPath<T, Key>): Map<Key, Int> =
        traced(
            operation = "groupCount",
            extraBlock = { put(com.lightningkite.services.database.Database.MetricKeys.groupBy, groupBy.colName) }
        ) { span ->
            prepare.await()
            val result = t {
                @Suppress("UNCHECKED_CAST")
                val groupCol = table.col[groupBy.colName] as Column<Key>
                val count = Count(stringLiteral("*"))
                table.select(groupCol, count)
                    .where { condition(condition, serializer, table, format).asOp() }
                    .groupBy(table.col[groupBy.colName]!!).associate { it[groupCol] to it[count].toInt() }
            }
            span.enrich(MetricAttributes { put(com.lightningkite.services.database.Database.MetricKeys.groups, result.size.toLong()) })
            result
        }

    override suspend fun <N : Number?> aggregate(
        aggregate: Aggregate,
        condition: Condition<T>,
        property: DataClassPath<T, N>,
    ): Double? = traced(
        operation = "aggregate",
        extraBlock = {
            put(com.lightningkite.services.database.Database.MetricKeys.aggregate, aggregate.toString())
            put(com.lightningkite.services.database.Database.MetricKeys.property, property.colName)
        }
    ) { span ->
        prepare.await()
        t {
            @Suppress("UNCHECKED_CAST")
            val valueCol = table.col[property.colName] as Column<Double>
            val agg = when (aggregate) {
                Aggregate.Sum -> Sum(valueCol, DoubleColumnType())
                Aggregate.Average -> Avg(valueCol, 8)
                Aggregate.StandardDeviationSample -> StdDevSamp(valueCol, 8)
                Aggregate.StandardDeviationPopulation -> StdDevPop(valueCol, 8)
            }
            table.select(agg)
                .where { condition(condition, serializer, table, format).asOp() }
                .firstOrNull()?.get(agg)?.toDouble()
        }
    }

    override suspend fun <N : Number?, Key> groupAggregate(
        aggregate: Aggregate,
        condition: Condition<T>,
        groupBy: DataClassPath<T, Key>,
        property: DataClassPath<T, N>,
    ): Map<Key, Double?> = traced(
        operation = "groupAggregate",
        extraBlock = {
            put(com.lightningkite.services.database.Database.MetricKeys.aggregate, aggregate.toString())
            put(com.lightningkite.services.database.Database.MetricKeys.groupBy, groupBy.colName)
            put(com.lightningkite.services.database.Database.MetricKeys.property, property.colName)
        }
    ) { span ->
        prepare.await()
        val result = t {
            @Suppress("UNCHECKED_CAST")
            val groupCol = table.col[groupBy.colName] as Column<Key>

            @Suppress("UNCHECKED_CAST")
            val valueCol = table.col[property.colName] as Column<Double>
            val agg = when (aggregate) {
                Aggregate.Sum -> Sum(valueCol, DoubleColumnType())
                Aggregate.Average -> Avg(valueCol, 8)
                Aggregate.StandardDeviationSample -> StdDevSamp(valueCol, 8)
                Aggregate.StandardDeviationPopulation -> StdDevPop(valueCol, 8)
            }
            table.select(groupCol, agg)
                .where { condition(condition, serializer, table, format).asOp() }
                .groupBy(table.col[groupBy.colName]!!).associate { it[groupCol] to it[agg]?.toDouble() }
        }
        span.enrich(MetricAttributes { put(com.lightningkite.services.database.Database.MetricKeys.groups, result.size.toLong()) })
        result
    }

    override suspend fun insert(models: Iterable<T>): List<T> = traced(
        operation = "insert"
    ) { span ->
        prepare.await()
        val modelsList = models.toList()
        span.enrich(MetricAttributes { put(com.lightningkite.services.database.Database.MetricKeys.insertCount, modelsList.size.toLong()) })
        t {
            table.batchInsert(modelsList) {
                format.encode(serializer, it, this)
            }
        }
        modelsList
    }

    override suspend fun replaceOne(condition: Condition<T>, model: T, orderBy: List<SortPart<T>>): EntryChange<T> {
        return updateOne(condition, Modification.Assign(model), orderBy)
    }

    override suspend fun replaceOneIgnoringResult(
        condition: Condition<T>,
        model: T,
        orderBy: List<SortPart<T>>,
    ): Boolean {
        return updateOneIgnoringResult(condition, Modification.Assign(model), orderBy)
    }

    override suspend fun upsertOne(
        condition: Condition<T>,
        modification: Modification<T>,
        model: T,
    ): EntryChange<T> {
        return newSuspendedTransaction(db = db, transactionIsolation = TRANSACTION_SERIALIZABLE) {
            val existing = findOne(condition)
            if (existing == null) {
                EntryChange(null, insert(listOf(model)).first())
            } else
                updateOne(condition, modification)
        }
    }

    override suspend fun upsertOneIgnoringResult(
        condition: Condition<T>,
        modification: Modification<T>,
        model: T,
    ): Boolean {
        return newSuspendedTransaction(db = db, transactionIsolation = TRANSACTION_SERIALIZABLE) {
            val existing = findOne(condition)
            if (existing == null) {
                insert(listOf(model))
                false
            } else
                updateOneIgnoringResult(condition, modification, listOf())
        }
    }

    override suspend fun updateOne(
        condition: Condition<T>,
        modification: Modification<T>,
        orderBy: List<SortPart<T>>,
    ): EntryChange<T> = traced(
        operation = "updateOne"
    ) { span ->
        if (orderBy.isNotEmpty()) throw UnsupportedOperationException()
        val result = t {
            val old = table.updateReturningOld(
                where = { condition(condition, serializer, table, format).asOp() },
                limit = 1,
                body = {
                    it.modification(modification, serializer, table, format)
                }
            )
            old.map { format.decode(serializer, it) }.firstOrNull()?.let {
                EntryChange(it, modification(it))
            } ?: EntryChange()
        }
        span.enrich(MetricAttributes { put(com.lightningkite.services.database.Database.MetricKeys.updated, if (result.old != null) 1L else 0L) })
        result
    }

    override suspend fun updateOneIgnoringResult(
        condition: Condition<T>,
        modification: Modification<T>,
        orderBy: List<SortPart<T>>,
    ): Boolean = traced(
        operation = "updateOneIgnoringResult"
    ) { span ->
        if (orderBy.isNotEmpty()) throw UnsupportedOperationException()
        val count = t {
            table.update(
                where = { condition(condition, serializer, table, format).asOp() },
                limit = null,
                body = {
                    it.modification(modification, serializer, table, format)
                }
            )
        }
        span.enrich(MetricAttributes { put(com.lightningkite.services.database.Database.MetricKeys.updated, count.toLong()) })
        count > 0
    }

    override suspend fun updateMany(condition: Condition<T>, modification: Modification<T>): CollectionChanges<T> =
        traced(
            operation = "updateMany"
        ) { span ->
            val result = t {
                val old = table.updateReturningOld(
                    where = { condition(condition, serializer, table, format).asOp() },
                    limit = null,
                    body = {
                        it.modification(modification, serializer, table, format)
                    }
                )
                CollectionChanges(old.map { format.decode(serializer, it) }.map {
                    EntryChange(it, modification(it))
                })
            }
            span.enrich(MetricAttributes { put(com.lightningkite.services.database.Database.MetricKeys.updated, result.changes.size.toLong()) })
            result
        }

    override suspend fun updateManyIgnoringResult(condition: Condition<T>, modification: Modification<T>): Int = traced(
        operation = "updateManyIgnoringResult"
    ) { span ->
        val count = t {
            table.update(
                where = { condition(condition, serializer, table, format).asOp() },
                limit = null,
                body = {
                    it.modification(modification, serializer, table, format)
                }
            )
        }
        span.enrich(MetricAttributes { put(com.lightningkite.services.database.Database.MetricKeys.updated, count.toLong()) })
        count
    }

    override suspend fun deleteOne(condition: Condition<T>, orderBy: List<SortPart<T>>): T? = traced(
        operation = "deleteOne"
    ) { span ->
        if (orderBy.isNotEmpty()) throw UnsupportedOperationException()
        val result = t {
            table.deleteReturningWhere(
                limit = 1,
                where = { condition(condition, serializer, table, format).asOp() }
            ).firstOrNull()?.let { format.decode(serializer, it) }
        }
        span.enrich(MetricAttributes { put(com.lightningkite.services.database.Database.MetricKeys.deleted, if (result != null) 1L else 0L) })
        result
    }

    override suspend fun deleteOneIgnoringOld(condition: Condition<T>, orderBy: List<SortPart<T>>): Boolean = traced(
        operation = "deleteOneIgnoringOld"
    ) { span ->
        if (orderBy.isNotEmpty()) throw UnsupportedOperationException()
        val count = t {
            table.deleteWhere(
                limit = 1,
                op = { it.condition(condition, serializer, table, format).asOp() }
            )
        }
        span.enrich(MetricAttributes { put(com.lightningkite.services.database.Database.MetricKeys.deleted, count.toLong()) })
        count > 0
    }

    override suspend fun deleteMany(condition: Condition<T>): List<T> = traced(
        operation = "deleteMany"
    ) { span ->
        prepare.await()
        val result = t {
            table.deleteReturningWhere(
                where = { condition(condition, serializer, table, format).asOp() }
            ).map { format.decode(serializer, it) }
        }
        span.enrich(MetricAttributes { put(com.lightningkite.services.database.Database.MetricKeys.deleted, result.size.toLong()) })
        result
    }

    override suspend fun deleteManyIgnoringOld(condition: Condition<T>): Int = traced(
        operation = "deleteManyIgnoringOld"
    ) { span ->
        prepare.await()
        val count = t {
            table.deleteWhere(
                op = { it.condition(condition, serializer, table, format).asOp() }
            )
        }
        span.enrich(MetricAttributes { put(com.lightningkite.services.database.Database.MetricKeys.deleted, count.toLong()) })
        count
    }

    override suspend fun findSimilar(
        vectorField: DataClassPath<T, Embedding>,
        params: DenseVectorSearchParams,
        condition: Condition<T>,
        maxQueryMs: Long,
    ): Flow<ScoredResult<T>> = flow {
        val results = traced(
            operation = "findSimilar",
            extraBlock = {
                put(com.lightningkite.services.database.Database.MetricKeys.vectorField, vectorField.colName)
                put(com.lightningkite.services.database.Database.MetricKeys.metric, params.metric.toString())
                put(com.lightningkite.services.database.Database.MetricKeys.limit, params.limit.toLong())
                params.minScore?.let { put(com.lightningkite.services.database.Database.MetricKeys.minScore, it.toDouble()) }
            }
        ) { span ->
            prepare.await()

            // Get the column for the vector field
            @Suppress("UNCHECKED_CAST")
            val vectorCol = table.col[vectorField.colName] as Column<List<Float>>

            // Convert embedding to pgvector array format
            val queryVector = params.queryVector.values.toList()

            // Get the distance operator for the similarity metric
            val distanceOp = when (params.metric) {
                SimilarityMetric.Cosine -> VectorCosineDistanceOp(vectorCol, queryVector)
                SimilarityMetric.Euclidean -> VectorEuclideanDistanceOp(vectorCol, queryVector)
                SimilarityMetric.DotProduct -> VectorDotProductDistanceOp(vectorCol, queryVector)
                SimilarityMetric.Manhattan -> VectorManhattanDistanceOp(vectorCol, queryVector)
            }

            val minScore = params.minScore
            val results = t {
                // Build the query - include distance expression in select
                // We need to select all table columns plus the distance expression
                table
                    .select(table.columns + distanceOp)
                    .where { condition(condition, serializer, table, format).asOp() }
                    .orderBy(distanceOp to SortOrder.ASC)
                    .limit(params.limit)
                    .toList()
                    .map { resultRow ->
                        // Decode the model
                        val model = format.decode(serializer, resultRow)

                        // Get the distance value from the result
                        val distance = resultRow[distanceOp]

                        // Normalize the distance to a similarity score
                        val score = when (params.metric) {
                            SimilarityMetric.Cosine -> {
                                // Cosine distance is 0-2, convert to similarity -1 to 1, then normalize to 0-1
                                val similarity = 1f - distance
                                // Normalize from [-1, 1] to [0, 1]
                                (similarity + 1f) / 2f
                            }

                            SimilarityMetric.Euclidean -> {
                                // Euclidean: 1 / (1 + distance)
                                1f / (1f + distance)
                            }

                            SimilarityMetric.DotProduct -> {
                                // DotProduct: pgvector uses negative inner product, so negate to get the score
                                -distance
                            }

                            SimilarityMetric.Manhattan -> {
                                // Manhattan: 1 / (1 + distance)
                                1f / (1f + distance)
                            }
                        }

                        ScoredResult(model, score)
                    }
                    .filter { minScore == null || it.score >= minScore }
            }

            span.enrich(MetricAttributes { put(com.lightningkite.services.database.Database.MetricKeys.resultCount, results.size.toLong()) })
            results
        }
        results.forEach { emit(it) }
    }

    override suspend fun findSimilarSparse(
        vectorField: DataClassPath<T, SparseEmbedding>,
        params: SparseVectorSearchParams,
        condition: Condition<T>,
        maxQueryMs: Long,
    ): Flow<ScoredResult<T>> = flow {
        val results = traced(
            operation = "findSimilarSparse",
            extraBlock = {
                put(com.lightningkite.services.database.Database.MetricKeys.vectorField, vectorField.colName)
                put(com.lightningkite.services.database.Database.MetricKeys.metric, params.metric.toString())
                put(com.lightningkite.services.database.Database.MetricKeys.limit, params.limit.toLong())
                params.minScore?.let { put(com.lightningkite.services.database.Database.MetricKeys.minScore, it.toDouble()) }
            }
        ) { span ->
            prepare.await()

            // Get the column for the sparse vector field
            // Note: SparseEmbedding is stored as JSON or custom type in PostgreSQL
            // For pgvector sparsevec support, we need pgvector 0.7.0+
            @Suppress("UNCHECKED_CAST")
            val vectorCol = table.col[vectorField.colName] as? Column<String>
                ?: throw IllegalStateException("Sparse vector column '${vectorField.colName}' not found in table")

            // Get the distance operator for the similarity metric
            val distanceOp = when (params.metric) {
                SimilarityMetric.Cosine -> SparseVectorCosineDistanceOp(vectorCol, params.queryVector)
                SimilarityMetric.Euclidean -> SparseVectorEuclideanDistanceOp(vectorCol, params.queryVector)
                SimilarityMetric.DotProduct -> SparseVectorDotProductDistanceOp(vectorCol, params.queryVector)
                SimilarityMetric.Manhattan -> throw UnsupportedOperationException(
                    "Manhattan distance is not supported for sparse vectors in pgvector. " +
                            "Use Cosine, Euclidean, or DotProduct instead."
                )
            }

            val minScore = params.minScore
            val results = t {
                // Build the query - include distance expression in select
                table
                    .select(table.columns + distanceOp)
                    .where { condition(condition, serializer, table, format).asOp() }
                    .orderBy(distanceOp to SortOrder.ASC)
                    .limit(params.limit)
                    .toList()
                    .map { resultRow ->
                        val model = format.decode(serializer, resultRow)
                        val distance = resultRow[distanceOp]

                        val score = when (params.metric) {
                            SimilarityMetric.Cosine -> {
                                val similarity = 1f - distance
                                (similarity + 1f) / 2f
                            }

                            SimilarityMetric.Euclidean -> {
                                1f / (1f + distance)
                            }

                            SimilarityMetric.DotProduct -> {
                                -distance
                            }

                            else -> distance
                        }

                        ScoredResult(model, score)
                    }
                    .filter { minScore == null || it.score >= minScore }
            }

            span.enrich(MetricAttributes { put(com.lightningkite.services.database.Database.MetricKeys.resultCount, results.size.toLong()) })
            results
        }
        results.forEach { emit(it) }
    }

}

// ===== pgvector distance operators =====

/**
 * Base class for pgvector distance operators.
 * These operators compute distance between vectors using pgvector extension operators.
 */
private sealed class VectorDistanceOp(
    private val column: Expression<List<Float>>,
    private val queryVector: List<Float>,
    private val operator: String,
) : ExpressionWithColumnType<Float>() {
    override val columnType: IColumnType<Float> = FloatColumnType()

    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        // Cast the column to vector type for pgvector compatibility
        queryBuilder.append("(")
        queryBuilder.append(column)
        queryBuilder.append("::vector)")
        queryBuilder.append(" ")
        queryBuilder.append(operator)
        queryBuilder.append(" ")
        // Format query vector as pgvector array: '[1.0, 2.0, 3.0]'
        queryBuilder.append("'[")
        queryVector.forEachIndexed { index, value ->
            if (index > 0) queryBuilder.append(", ")
            queryBuilder.append(value.toString())
        }
        queryBuilder.append("]'::vector")
    }
}

/**
 * Cosine distance operator (<=>)
 * Returns distance in range [0, 2]
 */
private class VectorCosineDistanceOp(
    column: Expression<List<Float>>,
    queryVector: List<Float>,
) : VectorDistanceOp(column, queryVector, "<=>")

/**
 * Euclidean distance operator (<->)
 * Returns L2 distance
 */
private class VectorEuclideanDistanceOp(
    column: Expression<List<Float>>,
    queryVector: List<Float>,
) : VectorDistanceOp(column, queryVector, "<->")

/**
 * Dot product distance operator (<#>)
 * Returns negative inner product
 */
private class VectorDotProductDistanceOp(
    column: Expression<List<Float>>,
    queryVector: List<Float>,
) : VectorDistanceOp(column, queryVector, "<#>")

/**
 * Manhattan distance operator (<+>)
 * Returns L1 distance
 */
private class VectorManhattanDistanceOp(
    column: Expression<List<Float>>,
    queryVector: List<Float>,
) : VectorDistanceOp(column, queryVector, "<+>")


// ===== pgvector sparse vector distance operators =====

/**
 * Base class for pgvector sparse vector distance operators.
 * Uses the sparsevec type from pgvector 0.7.0+.
 * Sparse vectors are stored in the format '{index1:value1,index2:value2,...}/dimensions'
 * Note: pgvector uses 1-based indices.
 */
private sealed class SparseVectorDistanceOp(
    private val column: Expression<String>,
    private val queryVector: SparseEmbedding,
    private val operator: String,
) : ExpressionWithColumnType<Float>() {
    override val columnType: IColumnType<Float> = FloatColumnType()

    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        // Cast the column to sparsevec type
        queryBuilder.append("(")
        queryBuilder.append(column)
        queryBuilder.append("::sparsevec)")
        queryBuilder.append(" ")
        queryBuilder.append(operator)
        queryBuilder.append(" ")
        // Format query sparse vector as pgvector sparsevec: '{1:1.0,3:2.0}/5'
        // Note: pgvector uses 1-based indices, so we add 1 to each index
        queryBuilder.append("'{")
        queryVector.indices.forEachIndexed { i, idx ->
            if (i > 0) queryBuilder.append(",")
            queryBuilder.append((idx + 1).toString()) // Convert to 1-based
            queryBuilder.append(":")
            queryBuilder.append(queryVector.values[i].toString())
        }
        queryBuilder.append("}/")
        queryBuilder.append(queryVector.dimensions.toString())
        queryBuilder.append("'::sparsevec")
    }
}

/**
 * Sparse vector cosine distance operator (<=>)
 */
private class SparseVectorCosineDistanceOp(
    column: Expression<String>,
    queryVector: SparseEmbedding,
) : SparseVectorDistanceOp(column, queryVector, "<=>")

/**
 * Sparse vector Euclidean distance operator (<->)
 */
private class SparseVectorEuclideanDistanceOp(
    column: Expression<String>,
    queryVector: SparseEmbedding,
) : SparseVectorDistanceOp(column, queryVector, "<->")

/**
 * Sparse vector dot product distance operator (<#>)
 */
private class SparseVectorDotProductDistanceOp(
    column: Expression<String>,
    queryVector: SparseEmbedding,
) : SparseVectorDistanceOp(column, queryVector, "<#>")