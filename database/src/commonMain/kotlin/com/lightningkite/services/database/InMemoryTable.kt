package com.lightningkite.services.database

import com.lightningkite.services.OpenTelemetry
import com.lightningkite.services.data.IndexUniqueness
import kotlinx.atomicfu.*
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.KSerializer

/**
 * A [Table] backed by an in-process [MutableMap] keyed by the model's `_id`.
 *
 * Useful for unit tests, fast prototypes, or as the storage tier of file-backed
 * implementations such as `JsonFileTable`.
 *
 * The backing [data] map can be supplied by the caller, allowing JVM users to
 * pass a `java.util.concurrent.ConcurrentHashMap` (or any other concrete map)
 * to control concurrency/storage characteristics.
 *
 * Lookups by `_id` (the most common case via [Table] extensions) are O(1).
 *
 * Note: iteration order is unspecified. Callers that depend on deterministic
 * ordering should pass an `orderBy` to [find].
 */
public open class InMemoryTable<Model : Any>(
    public val data: MutableMap<Any?, Model> = HashMap(),
    override val serializer: KSerializer<Model>,
    private val tableName: String = "unknown",
    private val tracer: OpenTelemetry? = null,
) : Table<Model> {

    private val lock = ReentrantLock()

    @Suppress("UNCHECKED_CAST")
    private val idProp: SerializableProperty<Model, Any?> =
        serializer.serializableProperties!!.first { it.name == "_id" } as SerializableProperty<Model, Any?>

    private fun idOf(model: Model): Any? = idProp.get(model)

    /** Place items into [data] keyed by `_id`. Bypasses unique checks; intended for trusted preload paths. */
    public fun preload(items: Iterable<Model>) {
        for (item in items) data[idOf(item)] = item
    }

    /**
     * Source-compatibility constructor for callers written against the previous list-backed
     * [InMemoryTable]. The supplied [data] list is read once to seed the table and is **not**
     * retained or kept in sync — the table is now keyed by `_id` in a [MutableMap]. Prefer the
     * primary constructor (pass a map) or [preload].
     */
    @Deprecated(
        "InMemoryTable is now keyed by _id and backed by a MutableMap. The list is only used as " +
            "initial contents and is not retained; use the primary constructor or preload() instead.",
        level = DeprecationLevel.WARNING,
    )
    public constructor(
        data: MutableList<Model>,
        serializer: KSerializer<Model>,
        tableName: String = "unknown",
        tracer: OpenTelemetry? = null,
    ) : this(HashMap<Any?, Model>(), serializer, tableName, tracer) {
        preload(data)
    }

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
                    val isPrimaryKey = index.fields.size == 1 && index.fields[0] == "_id"
                    uniqueIndexChecks.update { it ->
                        it.plus({ changes: List<EntryChange<Model>> ->
                            val fieldChanges = changes.mapNotNull { entryChange ->
                                if (
                                    (entryChange.old == null && entryChange.new != null) ||
                                    (entryChange.old != null &&
                                            entryChange.new != null &&
                                            fields.any { it.get(entryChange.old!!) != it.get(entryChange.new!!) })
                                )
                                    entryChange to fields.map { it to it.get(entryChange.new!!) }
                                else
                                    null
                            }
                            fieldChanges.forEach { (entryChange, fieldValues) ->
                                if (isPrimaryKey) {
                                    val newId = idOf(entryChange.new!!)
                                    val existing = data[newId]
                                    if (existing != null && existing !== entryChange.old) {
                                        throw UniqueViolationException(
                                            table = serializer.descriptor.serialName,
                                            key = "_id",
                                            cause = IllegalStateException()
                                        )
                                    }
                                } else if (index.unique == IndexUniqueness.Unique) {
                                    if (data.values.any { fromDb ->
                                            fromDb !== entryChange.old && fieldValues.all { (property, value) ->
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
                                    if (data.values.any { fromDb ->
                                            fromDb !== entryChange.old && fieldValues.all { (property, value) ->
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

    /**
     * If [c] is structurally `Condition.OnField(_id, Equal(x))` or `Condition.OnField(_id, Inside(xs))`,
     * return the candidate keys to look up directly. Otherwise null (caller falls back to scan).
     */
    private fun idEqualityKeys(c: Condition<Model>): List<Any?>? = when (c) {
        is Condition.OnField<*, *> ->
            if (c.key.name == "_id") when (val inner = c.condition) {
                is Condition.Equal<*> -> listOf(inner.value)
                is Condition.Inside<*> -> inner.values.toList()
                else -> null
            } else null
        else -> null
    }

    private fun candidatesFor(condition: Condition<Model>): Sequence<Model> {
        idEqualityKeys(condition)?.let { keys ->
            return keys.asSequence().mapNotNull { data[it] }
        }
        return data.values.asSequence()
    }

    private fun snapshotFor(condition: Condition<Model>, orderBy: List<SortPart<Model>>): Iterable<Model> {
        val seq = candidatesFor(condition).filter { condition(it) }
        return orderBy.comparator?.let { c -> seq.sortedWith(c).toList() } ?: seq.toList()
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
                snapshotFor(condition, orderBy)
                    .asSequence()
                    .drop(skip)
                    .take(limit)
                    .toList()
            }
        }
        result.forEach { emit(it) }
    }

    override suspend fun count(condition: Condition<Model>): Int = traced(
        tracer = tracer,
        operation = "count",
        tableName = tableName
    ) {
        lock.withLock {
            candidatesFor(condition).count { condition(it) }
        }
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
        lock.withLock {
            data.values.asSequence().filter { condition(it) }
                .groupingBy { groupBy.get(it) }.eachCount().minus(null) as Map<Key, Int>
        }
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
        lock.withLock {
            data.values.asSequence()
                .filter { condition(it) }
                .aggregateOfNotNull(aggregate) { property.get(it)?.toDouble() }
        }
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
        lock.withLock {
            data.values.asSequence()
                .filter { condition(it) }
                .groupAggregateNotNull(aggregate) {
                    @Suppress("UNCHECKED_CAST")
                    Pair(
                        groupBy.get(it) as Key,
                        property.get(it)?.toDouble() ?: return@groupAggregateNotNull null
                    )
                }
        }
    }

    override suspend fun insert(models: Iterable<Model>): List<Model> = traced(
        tracer = tracer,
        operation = "insert",
        tableName = tableName
    ) {
        val list = models.toList()
        lock.withLock {
            uniqueCheck(list.map { EntryChange(null, it) })
            for (m in list) data[idOf(m)] = m
            return@traced list
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
            for (old in snapshotFor(condition, orderBy)) {
                val changed = EntryChange(old, model)
                uniqueCheck(changed)
                val oldId = idOf(old)
                val newId = idOf(model)
                if (oldId != newId) data.remove(oldId)
                data[newId] = model
                return@traced changed
            }
            return@traced EntryChange(null, null)
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
            for (old in snapshotFor(condition, emptyList())) {
                val new = modification(old)
                val changed = EntryChange(old, new)
                uniqueCheck(changed)
                val oldId = idOf(old)
                val newId = idOf(new)
                if (oldId != newId) data.remove(oldId)
                data[newId] = new
                return@traced changed
            }
            uniqueCheck(EntryChange(null, model))
            data[idOf(model)] = model
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
            for (old in snapshotFor(condition, orderBy)) {
                val new = modification(old)
                val changed = EntryChange(old, new)
                uniqueCheck(changed)
                val oldId = idOf(old)
                val newId = idOf(new)
                if (oldId != newId) data.remove(oldId)
                data[newId] = new
                return@traced changed
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
            val matches = snapshotFor(condition, emptyList())
                .map { old -> EntryChange(old, modification(old)) }
            uniqueCheck(matches)
            for (change in matches) {
                val oldId = idOf(change.old!!)
                val newId = idOf(change.new!!)
                if (oldId != newId) data.remove(oldId)
                data[newId] = change.new!!
            }
            return@traced CollectionChanges(changes = matches)
        }
    }

    override suspend fun deleteOne(condition: Condition<Model>, orderBy: List<SortPart<Model>>): Model? = traced(
        tracer = tracer,
        operation = "deleteOne",
        tableName = tableName
    ) {
        lock.withLock {
            for (old in snapshotFor(condition, orderBy)) {
                data.remove(idOf(old))
                return@traced old
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
            val removed = snapshotFor(condition, emptyList()).toList()
            for (m in removed) data.remove(idOf(m))
            return@traced removed
        }
    }

    override suspend fun replaceOneIgnoringResult(
        condition: Condition<Model>,
        model: Model,
        orderBy: List<SortPart<Model>>,
    ): Boolean = replaceOne(condition, model, orderBy).new != null

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
                data.values.asSequence()
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
                data.values.asSequence()
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
}
