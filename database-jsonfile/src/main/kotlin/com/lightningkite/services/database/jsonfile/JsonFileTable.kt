package com.lightningkite.services.database.jsonfile

import com.lightningkite.services.data.KFile
import com.lightningkite.services.database.*
import io.github.oshai.kotlinlogging.KotlinLogging
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanBuilder
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.extension.kotlin.asContextElement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ObsoleteCoroutinesApi
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
import java.util.Collections

/**
 * An InMemoryFieldCollection with the added feature of loading data from a file at creation
 * and writing the collection data into a file when closing.
 */
public class JsonFileTable<Model : Any>(
    public val encoding: StringFormat,
    serializer: KSerializer<Model>,
    public val file: KFile,
    public val tableName: String,
    private val tracer: Tracer?
) : InMemoryTable<Model>(
    data = Collections.synchronizedList(ArrayList()),
    serializer = serializer
), Closeable {
    public companion object {
        internal val logger = KotlinLogging.logger("com.lightningkite.services.database.jsonfile.JsonFileTable")
    }

    private suspend inline fun <R> traced(
        operation: String,
        crossinline attributes: SpanBuilder.() -> Unit = {},
        crossinline block: suspend (Span?) -> R
    ): R {
        return if (tracer != null) {
            val span = tracer.spanBuilder("jsonfile.$operation")
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("db.system", "jsonfile")
                .setAttribute("db.operation", operation)
                .setAttribute("db.collection", tableName)
                .apply { attributes() }
                .startSpan()
            try {
                withContext(span.asContextElement()) {
                    val result = block(span)
                    span.setStatus(StatusCode.OK)
                    result
                }
            } catch (t: CancellationException) {
                span.addEvent("Cancelled")
                throw t
            } catch (t: Throwable) {
                span.setStatus(StatusCode.ERROR)
                span.recordException(t)
                throw t
            } finally {
                span.end()
            }
        } else {
            block(null)
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO)

    @OptIn(ObsoleteCoroutinesApi::class)
    private val saveScope = scope.actor<Unit>(start = CoroutineStart.LAZY) {
        handleCollectionDump()
    }

    init {
        data.addAll(
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

    public fun handleCollectionDump() {
        val temp = file.parent!!.then(file.name + ".saving")
        temp.writeString(encoding.encodeToString(ListSerializer(serializer), data.toList()))
        temp.atomicMove(file)
        logger.debug { "Saved $file" }
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
            attributes = {
                setAttribute("db.limit", limit.toLong())
                setAttribute("db.skip", skip.toLong())
            }
        ) { span ->
            val results = super.find(condition, orderBy, skip, limit, maxQueryMs).toList()
            span?.setAttribute("db.result_count", results.size.toLong())
            results
        }
        results.forEach { emit(it) }
    }

    override suspend fun count(condition: Condition<Model>): Int = traced(
        operation = "count"
    ) { span ->
        val result = super.count(condition)
        span?.setAttribute("db.count", result.toLong())
        result
    }

    override suspend fun <Key> groupCount(
        condition: Condition<Model>,
        groupBy: DataClassPath<Model, Key>
    ): Map<Key, Int> = traced(
        operation = "groupCount",
        attributes = {
            setAttribute("db.groupBy", groupBy.toString())
        }
    ) { span ->
        val result = super.groupCount(condition, groupBy)
        span?.setAttribute("db.groups", result.size.toLong())
        result
    }

    override suspend fun <N : Number?> aggregate(
        aggregate: Aggregate,
        condition: Condition<Model>,
        property: DataClassPath<Model, N>
    ): Double? = traced(
        operation = "aggregate",
        attributes = {
            setAttribute("db.aggregate", aggregate.toString())
            setAttribute("db.property", property.toString())
        }
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
        attributes = {
            setAttribute("db.aggregate", aggregate.toString())
            setAttribute("db.groupBy", groupBy.toString())
            setAttribute("db.property", property.toString())
        }
    ) { span ->
        val result = super.groupAggregate(aggregate, condition, groupBy, property)
        span?.setAttribute("db.groups", result.size.toLong())
        result
    }

    override suspend fun insert(models: Iterable<Model>): List<Model> = traced(
        operation = "insert"
    ) { span ->
        val modelsList = models.toList()
        span?.setAttribute("db.insert_count", modelsList.size.toLong())
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
        span?.setAttribute("db.replaced", if (result.old != null) 1L else 0L)
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
        span?.setAttribute("db.replaced", if (result) 1L else 0L)
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
        span?.setAttribute("db.upserted", if (result.new != null) 1L else 0L)
        span?.setAttribute("db.was_insert", if (result.old == null) 1L else 0L)
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
        span?.setAttribute("db.was_update", if (result) 1L else 0L)
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
        span?.setAttribute("db.updated", if (result.old != null) 1L else 0L)
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
        span?.setAttribute("db.updated", if (result) 1L else 0L)
        result
    }

    override suspend fun updateMany(
        condition: Condition<Model>,
        modification: Modification<Model>
    ): CollectionChanges<Model> = traced(
        operation = "updateMany"
    ) { span ->
        val result = super.updateMany(condition, modification)
        span?.setAttribute("db.updated", result.changes.size.toLong())
        result
    }

    override suspend fun updateManyIgnoringResult(
        condition: Condition<Model>,
        modification: Modification<Model>
    ): Int = traced(
        operation = "updateManyIgnoringResult"
    ) { span ->
        val count = super.updateManyIgnoringResult(condition, modification)
        span?.setAttribute("db.updated", count.toLong())
        count
    }

    override suspend fun deleteOne(
        condition: Condition<Model>,
        orderBy: List<SortPart<Model>>
    ): Model? = traced(
        operation = "deleteOne"
    ) { span ->
        val result = super.deleteOne(condition, orderBy)
        span?.setAttribute("db.deleted", if (result != null) 1L else 0L)
        result
    }

    override suspend fun deleteOneIgnoringOld(
        condition: Condition<Model>,
        orderBy: List<SortPart<Model>>
    ): Boolean = traced(
        operation = "deleteOneIgnoringOld"
    ) { span ->
        val result = super.deleteOneIgnoringOld(condition, orderBy)
        span?.setAttribute("db.deleted", if (result) 1L else 0L)
        result
    }

    override suspend fun deleteMany(condition: Condition<Model>): List<Model> = traced(
        operation = "deleteMany"
    ) { span ->
        val result = super.deleteMany(condition)
        span?.setAttribute("db.deleted", result.size.toLong())
        result
    }

    override suspend fun deleteManyIgnoringOld(condition: Condition<Model>): Int = traced(
        operation = "deleteManyIgnoringOld"
    ) { span ->
        val count = super.deleteManyIgnoringOld(condition)
        span?.setAttribute("db.deleted", count.toLong())
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
            attributes = {
                setAttribute("db.vectorField", vectorField.toString())
                setAttribute("db.metric", params.metric.toString())
                setAttribute("db.limit", params.limit.toLong())
                params.minScore?.let { setAttribute("db.minScore", it.toDouble()) }
            }
        ) { span ->
            val results = super.findSimilar(vectorField, params, condition, maxQueryMs).toList()
            span?.setAttribute("db.result_count", results.size.toLong())
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
            attributes = {
                setAttribute("db.vectorField", vectorField.toString())
                setAttribute("db.metric", params.metric.toString())
                setAttribute("db.limit", params.limit.toLong())
                params.minScore?.let { setAttribute("db.minScore", it.toDouble()) }
            }
        ) { span ->
            val results = super.findSimilarSparse(vectorField, params, condition, maxQueryMs).toList()
            span?.setAttribute("db.result_count", results.size.toLong())
            results
        }
        results.forEach { emit(it) }
    }
}