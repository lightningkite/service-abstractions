package com.lightningkite.services.database

import com.lightningkite.services.OpenTelemetry
import com.lightningkite.services.data.IndexUniqueness
import kotlinx.atomicfu.AtomicRef
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.atomicfu.update
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.chunked
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule

/**
 * A FieldCollection who's underlying implementation is actually manipulating a MutableList.
 * This is useful for times that an actual database is not needed, and you need to move fast, such as during Unit Tests.
 */
public open class InMemoryTable<Model : Any>(
    public val data: MutableList<Model> = ArrayList(),
    override val serializer: KSerializer<Model>,
    private val tableName: String = "unknown",
    private val tracer: OpenTelemetry? = null,
) : Table<Model> {

    private val lock = ReentrantLock()

    private val uniqueIndexChecks: AtomicRef<List<(List<EntryChange<Model>>) -> Unit>> = atomic(emptyList())

    private fun uniqueCheck(changed: EntryChange<Model>) = uniqueCheck(listOf(changed))
    private fun uniqueCheck(changes: List<EntryChange<Model>>) =
        lock.withLock { uniqueIndexChecks.value.forEach { it(changes) } }

    init {
        serializer.descriptor.indexes().plus(NeededIndex(fields = listOf("_id"), IndexUniqueness.Unique, "primary key"))
            .forEach { index: NeededIndex ->
                if (index.unique in
                    setOf(IndexUniqueness.Unique, IndexUniqueness.UniqueNullSparse)
                ) {
                    val fields = serializer.serializableProperties!!.filter { index.fields.contains(it.name) }
                    uniqueIndexChecks.update { it ->
                        it.plus({ changes: List<EntryChange<Model>> ->
                            val fieldChanges = changes.mapNotNull { entryChange ->
                                if (
                                    (entryChange.old == null && entryChange.new != null) ||
                                    (entryChange.old != null &&
                                            entryChange.new != null &&
                                            fields.any { it.get(entryChange.old!!) != it.get(entryChange.new!!) })
                                )
                                    fields.map { it to it.get(entryChange.new!!) }
                                else
                                    null
                            }
                            fieldChanges.forEach { fieldValues ->
                                if (index.unique == IndexUniqueness.Unique) {
                                    if (data.any { fromDb ->
                                            fieldValues.all { (property, value) ->
                                                property.get(fromDb) == value
                                            }
                                        }) {
                                        throw UniqueViolationException(
                                            table = serializer.descriptor.serialName,
                                            key = fields.joinToString { it.name },
                                            cause = IllegalStateException()
                                        )
                                    }
                                } else {
                                    if (data.any { fromDb ->
                                            fieldValues
                                                .all { (property, value) ->
                                                    value != null && property.get(fromDb) == value
                                                }
                                        }) {
                                        throw UniqueViolationException(
                                            table = serializer.descriptor.serialName,
                                            key = fields.joinToString { it.name },
                                            cause = IllegalStateException()
                                        )
                                    }
                                }
                            }
                        })
                    }
                }
            }
    }

    override suspend fun find(
        condition: Condition<Model>,
        orderBy: List<SortPart<Model>>,
        skip: Int,
        limit: Int,
        maxQueryMs: Long,
    ): Flow<Model> = flow {
        val result = traced(
            tracer = tracer,
            operation = "find",
            tableName = tableName,
            attributes = mapOf(
                "db.limit" to limit,
                "db.skip" to skip
            )
        ) {
            lock.withLock {
                data.asSequence()
                    .filter { condition(it) }
                    .let {
                        orderBy.comparator?.let { c ->
                            it.sortedWith(c)
                        } ?: it
                    }
                    .drop(skip)
                    .take(limit)
                    .toList()
            }
        }
        result
            .forEach {
                emit(it)
            }
    }

    override suspend fun count(condition: Condition<Model>): Int = traced(
        tracer = tracer,
        operation = "count",
        tableName = tableName
    ) {
        data.count { condition(it) }
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun <Key> groupCount(
        condition: Condition<Model>,
        groupBy: DataClassPath<Model, Key>,
    ): Map<Key, Int> = traced(
        tracer = tracer,
        operation = "groupCount",
        tableName = tableName
    ) {
        data.filter { condition(it) }.groupingBy { groupBy.get(it) }.eachCount().minus(null) as Map<Key, Int>
    }

    override suspend fun <N : Number?> aggregate(
        aggregate: Aggregate,
        condition: Condition<Model>,
        property: DataClassPath<Model, N>,
    ): Double? = traced(
        tracer = tracer,
        operation = "aggregate",
        tableName = tableName,
        attributes = mapOf("db.aggregate" to aggregate.toString())
    ) {
        data.asSequence().filter { condition(it) }.mapNotNull { property.get(it)?.toDouble() }.aggregate(aggregate)
    }

    override suspend fun <N : Number?, Key> groupAggregate(
        aggregate: Aggregate,
        condition: Condition<Model>,
        groupBy: DataClassPath<Model, Key>,
        property: DataClassPath<Model, N>,
    ): Map<Key, Double?> = traced(
        tracer = tracer,
        operation = "groupAggregate",
        tableName = tableName,
        attributes = mapOf("db.aggregate" to aggregate.toString())
    ) {
        data.asSequence().filter { condition(it) }
            .mapNotNull {
                (groupBy.get(it) ?: return@mapNotNull null) to (property.get(it)?.toDouble() ?: return@mapNotNull null)
            }.aggregate(aggregate)
    }

    override suspend fun insert(models: Iterable<Model>): List<Model> = traced(
        tracer = tracer,
        operation = "insert",
        tableName = tableName
    ) {
        lock.withLock {
            uniqueCheck(models.map { EntryChange(null, it) })
            data.addAll(models)
            return@traced models.toList()
        }
    }

    override suspend fun replaceOne(
        condition: Condition<Model>,
        model: Model,
        orderBy: List<SortPart<Model>>,
    ): EntryChange<Model> = traced(
        tracer = tracer,
        operation = "replaceOne",
        tableName = tableName
    ) {
        lock.withLock {
            for (it in sortIndices(orderBy)) {
                val old = data[it]
                if (condition(old)) {
                    val changed = EntryChange(old, model)
                    uniqueCheck(changed)
                    data[it] = model
                    return@traced changed
                }
            }
            return@traced EntryChange(null, null)
        }
    }

    private fun sortIndices(orderBy: List<SortPart<Model>>): Iterable<Int> {
        return data.indices.let {
            orderBy.comparator?.let { c ->
                it.sortedWith { a, b -> c.compare(data[a], data[b]) }
            } ?: it
        }
    }

    override suspend fun upsertOne(
        condition: Condition<Model>,
        modification: Modification<Model>,
        model: Model,
    ): EntryChange<Model> = traced(
        tracer = tracer,
        operation = "upsertOne",
        tableName = tableName
    ) {
        lock.withLock {
            for (it in data.indices) {
                val old = data[it]
                if (condition(old)) {
                    val new = modification(old)
                    val changed = EntryChange(old, new)
                    uniqueCheck(changed)
                    data[it] = new
                    return@traced changed
                }
            }
            data.add(model)
            return@traced EntryChange(null, model)
        }
    }

    override suspend fun updateOne(
        condition: Condition<Model>,
        modification: Modification<Model>,
        orderBy: List<SortPart<Model>>,
    ): EntryChange<Model> = traced(
        tracer = tracer,
        operation = "updateOne",
        tableName = tableName
    ) {
        lock.withLock {
            for (it in sortIndices(orderBy)) {
                val old = data[it]
                if (condition(old)) {
                    val new = modification(old)
                    val changed = EntryChange(old, new)
                    uniqueCheck(changed)
                    data[it] = new
                    return@traced changed
                }
            }
            return@traced EntryChange(null, null)
        }
    }

    override suspend fun updateMany(
        condition: Condition<Model>,
        modification: Modification<Model>,
    ): CollectionChanges<Model> = traced(
        tracer = tracer,
        operation = "updateMany",
        tableName = tableName
    ) {
        lock.withLock {
            return@traced data.indices
                .mapNotNull {
                    val old = data[it]
                    if (condition(old)) {
                        val new = modification(old)
                        it to EntryChange(old, new)
                    } else null
                }
                .let {
                    val changes = it.map { it.second }
                    uniqueCheck(changes)
                    it.forEach { (index, change) -> data[index] = change.new!! }
                    CollectionChanges(changes = changes)
                }
        }
    }

    override suspend fun deleteOne(condition: Condition<Model>, orderBy: List<SortPart<Model>>): Model? = traced(
        tracer = tracer,
        operation = "deleteOne",
        tableName = tableName
    ) {
        lock.withLock {
            for (it in sortIndices(orderBy)) {
                val old = data[it]
                if (condition(old)) {
                    data.removeAt(it)
                    return@traced old
                }
            }
            return@traced null
        }
    }

    override suspend fun deleteMany(condition: Condition<Model>): List<Model> = traced(
        tracer = tracer,
        operation = "deleteMany",
        tableName = tableName
    ) {
        lock.withLock {
            val removed = ArrayList<Model>()
            data.removeAll {
                if (condition(it)) {
                    removed.add(it)
                    true
                } else {
                    false
                }
            }
            return@traced removed
        }
    }

    override suspend fun replaceOneIgnoringResult(
        condition: Condition<Model>,
        model: Model,
        orderBy: List<SortPart<Model>>,
    ): Boolean = replaceOne(
        condition,
        model,
        orderBy
    ).new != null

    override suspend fun upsertOneIgnoringResult(
        condition: Condition<Model>,
        modification: Modification<Model>,
        model: Model,
    ): Boolean = upsertOne(condition, modification, model).old != null

    override suspend fun updateOneIgnoringResult(
        condition: Condition<Model>,
        modification: Modification<Model>,
        orderBy: List<SortPart<Model>>,
    ): Boolean = updateOne(condition, modification, orderBy).new != null

    override suspend fun updateManyIgnoringResult(
        condition: Condition<Model>,
        modification: Modification<Model>,
    ): Int = updateMany(condition, modification).changes.size

    override suspend fun deleteOneIgnoringOld(
        condition: Condition<Model>,
        orderBy: List<SortPart<Model>>,
    ): Boolean = deleteOne(condition, orderBy) != null

    override suspend fun deleteManyIgnoringOld(condition: Condition<Model>): Int = deleteMany(condition).size

    public fun drop() {
        data.clear()
    }

    // ===== Vector Search Implementation =====

    override suspend fun findSimilar(
        vectorField: DataClassPath<Model, Embedding>,
        params: DenseVectorSearchParams,
        condition: Condition<Model>,
        maxQueryMs: Long,
    ): Flow<ScoredResult<Model>> = flow {
        val results = traced(
            tracer = tracer,
            operation = "findSimilar",
            tableName = tableName,
            attributes = mapOf(
                "db.limit" to params.limit,
                "db.similarity_metric" to params.metric.toString()
            )
        ) {
            val minScore = params.minScore
            lock.withLock {
                data.asSequence()
                    .filter { condition(it) }
                    .mapNotNull { model ->
                        val embedding = vectorField.get(model) ?: return@mapNotNull null
                        val score = EmbeddingSimilarity.similarity(embedding, params.queryVector, params.metric)
                        if (minScore != null && score < minScore) return@mapNotNull null
                        ScoredResult(model, score)
                    }
                    .sortedByDescending { it.score }
                    .take(params.limit)
                    .toList()
            }
        }
        results.forEach { emit(it) }
    }

    override suspend fun findSimilarSparse(
        vectorField: DataClassPath<Model, SparseEmbedding>,
        params: SparseVectorSearchParams,
        condition: Condition<Model>,
        maxQueryMs: Long,
    ): Flow<ScoredResult<Model>> = flow {
        val results = traced(
            tracer = tracer,
            operation = "findSimilarSparse",
            tableName = tableName,
            attributes = mapOf(
                "db.limit" to params.limit,
                "db.similarity_metric" to params.metric.toString()
            )
        ) {
            val minScore = params.minScore
            lock.withLock {
                data.asSequence()
                    .filter { condition(it) }
                    .mapNotNull { model ->
                        val embedding = vectorField.get(model) ?: return@mapNotNull null
                        val score = EmbeddingSimilarity.similarity(embedding, params.queryVector, params.metric)
                        if (minScore != null && score < minScore) return@mapNotNull null
                        ScoredResult(model, score)
                    }
                    .sortedByDescending { it.score }
                    .take(params.limit)
                    .toList()
            }
        }
        results.forEach { emit(it) }
    }

    public fun export(module: SerializersModule): TableExport {
        val json = Json { serializersModule = module }

        return TableExport(
            tableName,
            data.asFlow().map { json.encodeToJsonElement(serializer, it) }
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    public suspend fun import(data: TableExport, module: SerializersModule) {
        if (data.tableName != tableName) throw IllegalArgumentException("Table names do not match, expected $tableName got ${data.tableName}")
        val json = Json { serializersModule = module }

        data.items
            .map { json.decodeFromJsonElement(serializer, it) }
            .chunked(1000)
            .collect { items ->
                insert(items)
            }
    }
}

