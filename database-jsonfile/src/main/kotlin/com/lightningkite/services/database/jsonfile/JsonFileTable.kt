package com.lightningkite.services.database.jsonfile

import com.lightningkite.services.MetricAttributes
import com.lightningkite.services.MetricSpan
import com.lightningkite.services.Namespaced
import com.lightningkite.services.SettingContext
import com.lightningkite.services.metricsTrace
import com.lightningkite.services.kfile.KFile
import com.lightningkite.services.database.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.StringFormat
import kotlinx.serialization.builtins.ListSerializer
import java.io.Closeable
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.comparisons.then
import kotlin.uuid.Uuid

/**
 * An InMemoryFieldCollection with the added feature of loading data from a file at creation
 * and writing the collection data into a file when closing.
 */
public class JsonFileTable<Model : Any>(
    public val encoding: StringFormat,
    serializer: KSerializer<Model>,
    public val file: KFile,
    public val tableName: String,
    override val context: SettingContext,
) : InMemoryTable<Model>(
    data = ConcurrentHashMap<Any?, Model>(),
    serializer = serializer
), Closeable, Namespaced {
    public companion object {
        internal val logger = KotlinLogging.logger("com.lightningkite.services.database.jsonfile.JsonFileTable")
    }

    override val name: String get() = tableName

    private fun baseAttributes(operation: String, extra: Map<String, Any?> = emptyMap()): MetricAttributes =
        MetricAttributes(
            buildMap {
                put("db.system", "jsonfile")
                put("db.operation", operation)
                put("db.collection", tableName)
                putAll(extra)
            }
        )

    private suspend inline fun <R> traced(
        operation: String,
        extra: Map<String, Any?> = emptyMap(),
        noinline block: suspend (MetricSpan) -> R,
    ): R = metricsTrace(operation, attributes = baseAttributes(operation, extra), action = block)

    private val scope = CoroutineScope(Dispatchers.IO)
    private val dumpLock = ReentrantLock()

    @OptIn(ObsoleteCoroutinesApi::class)
    private val saveScope = scope.actor<Unit>(start = CoroutineStart.LAZY) {
        handleCollectionDump()
    }

    init {
        preload(
            encoding.decodeFromString(
                ListSerializer(serializer),
                file.readStringOrNull() ?: "[]"
            )
        )
        val shutdownHook = Thread {
            handleCollectionDump()
        }
        Runtime.getRuntime().addShutdownHook(shutdownHook)
    }

    override fun close() {
        scope.launch {
            saveScope.send(Unit)
        }
    }

    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
    public fun handleCollectionDump() {
        // Mutex prevents the shutdown hook and close() from racing on concurrent writes.
        dumpLock.withLock {
            // A unique temp name keeps overlapping writers (e.g. another instance pointing at the
            // same file, or this instance's shutdown hook) from clobbering each other's temp file.
            // Writing a fresh file then atomically renaming guarantees no leftover trailing bytes
            // from a previously-longer serialization and is crash-safe.
            val temp = file.parent!!.then("${file.name}.${Uuid.random()}.saving")
            try {
                temp.writeString(encoding.encodeToString(ListSerializer(serializer), data.values.toList()))
                temp.atomicMove(file)
            } catch (e: Throwable) {
                temp.delete()
                throw e
            }
            logger.debug { "Saved $file" }
        }
    }

    // ===== Traced database operations =====

    override suspend fun find(
        condition: Condition<Model>,
        orderBy: List<SortPart<Model>>,
        skip: Int,
        limit: Int,
        maxQueryMs: Long
    ): Flow<Model> = flow {
        val results = traced(
            operation = "find",
            extra = mapOf(
                "db.limit" to limit.toLong(),
                "db.skip" to skip.toLong(),
            )
        ) { span ->
            val results = super.find(condition, orderBy, skip, limit, maxQueryMs).toList()
            span.enrich(MetricAttributes(mapOf("db.result_count" to results.size.toLong())))
            results
        }
        results.forEach { emit(it) }
    }

    override suspend fun count(condition: Condition<Model>): Int = traced(
        operation = "count"
    ) { span ->
        val result = super.count(condition)
        span.enrich(MetricAttributes(mapOf("db.count" to result.toLong())))
        result
    }

    override suspend fun <Key> groupCount(
        condition: Condition<Model>,
        groupBy: DataClassPath<Model, Key>
    ): Map<Key, Int> = traced(
        operation = "groupCount",
        extra = mapOf("db.groupBy" to groupBy.toString())
    ) { span ->
        val result = super.groupCount(condition, groupBy)
        span.enrich(MetricAttributes(mapOf("db.groups" to result.size.toLong())))
        result
    }

    override suspend fun <N : Number?> aggregate(
        aggregate: Aggregate,
        condition: Condition<Model>,
        property: DataClassPath<Model, N>
    ): Double? = traced(
        operation = "aggregate",
        extra = mapOf(
            "db.aggregate" to aggregate.toString(),
            "db.property" to property.toString(),
        )
    ) { span ->
        super.aggregate(aggregate, condition, property)
    }

    override suspend fun <N : Number?, Key> groupAggregate(
        aggregate: Aggregate,
        condition: Condition<Model>,
        groupBy: DataClassPath<Model, Key>,
        property: DataClassPath<Model, N>
    ): Map<Key, Double?> = traced(
        operation = "groupAggregate",
        extra = mapOf(
            "db.aggregate" to aggregate.toString(),
            "db.groupBy" to groupBy.toString(),
            "db.property" to property.toString(),
        )
    ) { span ->
        val result = super.groupAggregate(aggregate, condition, groupBy, property)
        span.enrich(MetricAttributes(mapOf("db.groups" to result.size.toLong())))
        result
    }

    override suspend fun insert(models: Iterable<Model>): List<Model> = traced(
        operation = "insert"
    ) { span ->
        val modelsList = models.toList()
        span.enrich(MetricAttributes(mapOf("db.insert_count" to modelsList.size.toLong())))
        super.insert(modelsList)
    }

    override suspend fun replaceOne(
        condition: Condition<Model>,
        model: Model,
        orderBy: List<SortPart<Model>>
    ): EntryChange<Model> = traced(
        operation = "replaceOne"
    ) { span ->
        val result = super.replaceOne(condition, model, orderBy)
        span.enrich(MetricAttributes(mapOf("db.replaced" to if (result.old != null) 1L else 0L)))
        result
    }

    override suspend fun replaceOneIgnoringResult(
        condition: Condition<Model>,
        model: Model,
        orderBy: List<SortPart<Model>>
    ): Boolean = traced(
        operation = "replaceOneIgnoringResult"
    ) { span ->
        val result = super.replaceOneIgnoringResult(condition, model, orderBy)
        span.enrich(MetricAttributes(mapOf("db.replaced" to if (result) 1L else 0L)))
        result
    }

    override suspend fun upsertOne(
        condition: Condition<Model>,
        modification: Modification<Model>,
        model: Model
    ): EntryChange<Model> = traced(
        operation = "upsertOne"
    ) { span ->
        val result = super.upsertOne(condition, modification, model)
        span.enrich(MetricAttributes(mapOf("db.upserted" to if (result.new != null) 1L else 0L)))
        span.enrich(MetricAttributes(mapOf("db.was_insert" to if (result.old == null) 1L else 0L)))
        result
    }

    override suspend fun upsertOneIgnoringResult(
        condition: Condition<Model>,
        modification: Modification<Model>,
        model: Model
    ): Boolean = traced(
        operation = "upsertOneIgnoringResult"
    ) { span ->
        val result = super.upsertOneIgnoringResult(condition, modification, model)
        span.enrich(MetricAttributes(mapOf("db.was_update" to if (result) 1L else 0L)))
        result
    }

    override suspend fun updateOne(
        condition: Condition<Model>,
        modification: Modification<Model>,
        orderBy: List<SortPart<Model>>
    ): EntryChange<Model> = traced(
        operation = "updateOne"
    ) { span ->
        val result = super.updateOne(condition, modification, orderBy)
        span.enrich(MetricAttributes(mapOf("db.updated" to if (result.old != null) 1L else 0L)))
        result
    }

    override suspend fun updateOneIgnoringResult(
        condition: Condition<Model>,
        modification: Modification<Model>,
        orderBy: List<SortPart<Model>>
    ): Boolean = traced(
        operation = "updateOneIgnoringResult"
    ) { span ->
        val result = super.updateOneIgnoringResult(condition, modification, orderBy)
        span.enrich(MetricAttributes(mapOf("db.updated" to if (result) 1L else 0L)))
        result
    }

    override suspend fun updateMany(
        condition: Condition<Model>,
        modification: Modification<Model>
    ): CollectionChanges<Model> = traced(
        operation = "updateMany"
    ) { span ->
        val result = super.updateMany(condition, modification)
        span.enrich(MetricAttributes(mapOf("db.updated" to result.changes.size.toLong())))
        result
    }

    override suspend fun updateManyIgnoringResult(
        condition: Condition<Model>,
        modification: Modification<Model>
    ): Int = traced(
        operation = "updateManyIgnoringResult"
    ) { span ->
        val count = super.updateManyIgnoringResult(condition, modification)
        span.enrich(MetricAttributes(mapOf("db.updated" to count.toLong())))
        count
    }

    override suspend fun deleteOne(
        condition: Condition<Model>,
        orderBy: List<SortPart<Model>>
    ): Model? = traced(
        operation = "deleteOne"
    ) { span ->
        val result = super.deleteOne(condition, orderBy)
        span.enrich(MetricAttributes(mapOf("db.deleted" to if (result != null) 1L else 0L)))
        result
    }

    override suspend fun deleteOneIgnoringOld(
        condition: Condition<Model>,
        orderBy: List<SortPart<Model>>
    ): Boolean = traced(
        operation = "deleteOneIgnoringOld"
    ) { span ->
        val result = super.deleteOneIgnoringOld(condition, orderBy)
        span.enrich(MetricAttributes(mapOf("db.deleted" to if (result) 1L else 0L)))
        result
    }

    override suspend fun deleteMany(condition: Condition<Model>): List<Model> = traced(
        operation = "deleteMany"
    ) { span ->
        val result = super.deleteMany(condition)
        span.enrich(MetricAttributes(mapOf("db.deleted" to result.size.toLong())))
        result
    }

    override suspend fun deleteManyIgnoringOld(condition: Condition<Model>): Int = traced(
        operation = "deleteManyIgnoringOld"
    ) { span ->
        val count = super.deleteManyIgnoringOld(condition)
        span.enrich(MetricAttributes(mapOf("db.deleted" to count.toLong())))
        count
    }

    override suspend fun findSimilar(
        vectorField: DataClassPath<Model, Embedding>,
        params: DenseVectorSearchParams,
        condition: Condition<Model>,
        maxQueryMs: Long
    ): Flow<ScoredResult<Model>> = flow {
        val results = traced(
            operation = "findSimilar",
            extra = buildMap {
                put("db.vectorField", vectorField.toString())
                put("db.metric", params.metric.toString())
                put("db.limit", params.limit.toLong())
                params.minScore?.let { put("db.minScore", it.toDouble()) }
            }
        ) { span ->
            val results = super.findSimilar(vectorField, params, condition, maxQueryMs).toList()
            span.enrich(MetricAttributes(mapOf("db.result_count" to results.size.toLong())))
            results
        }
        results.forEach { emit(it) }
    }

    override suspend fun findSimilarSparse(
        vectorField: DataClassPath<Model, SparseEmbedding>,
        params: SparseVectorSearchParams,
        condition: Condition<Model>,
        maxQueryMs: Long
    ): Flow<ScoredResult<Model>> = flow {
        val results = traced(
            operation = "findSimilarSparse",
            extra = buildMap {
                put("db.vectorField", vectorField.toString())
                put("db.metric", params.metric.toString())
                put("db.limit", params.limit.toLong())
                params.minScore?.let { put("db.minScore", it.toDouble()) }
            }
        ) { span ->
            val results = super.findSimilarSparse(vectorField, params, condition, maxQueryMs).toList()
            span.enrich(MetricAttributes(mapOf("db.result_count" to results.size.toLong())))
            results
        }
        results.forEach { emit(it) }
    }
}