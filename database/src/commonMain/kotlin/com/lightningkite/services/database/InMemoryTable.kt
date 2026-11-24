package com.lightningkite.services.database

import com.lightningkite.services.database.optics.*

import com.lightningkite.services.data.IndexUniqueness
import com.lightningkite.services.data.TextIndex
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
) : Table<Model> {

    private val lock = ReentrantLock()

    // The in-memory table does no I/O, so its operations are not traced. This passes the block
    // straight through, keeping operation bodies (which use `return@traced`) unchanged.
    private inline fun <R> traced(block: () -> R): R = block()

    @Suppress("UNCHECKED_CAST")
    private val idProp: Field<Model, Any?> =
        serializer.fields!!.first { it.name == "_id" } as Field<Model, Any?>

    private fun idOf(model: Model): Any? = idProp.get(model)

    private val textIndexFields: List<AnyOptic<Model>>? =
        serializer.descriptor.annotations.filterIsInstance<TextIndex>().firstOrNull()?.fields
            ?.map { AnyOpticSerializer(serializer).fromString(it) }

    private inner class UniqueIndex(
        val fields: List<Field<Model, *>>,
        val nullSparse: Boolean,
    ) {
        // A set of ids rather than one, because [preload] skips the checks and may place rows that share a key.
        val ids = HashMap<List<Any?>, MutableSet<Any?>>()

        fun keyOf(model: Model): List<Any?>? {
            val key = fields.map { it.get(model) }
            return if (nullSparse && key.any { it == null }) null else key
        }
    }

    private val uniqueIndexes: List<UniqueIndex> = serializer.descriptor.indexes()
        .filter { it.unique == IndexUniqueness.Unique || it.unique == IndexUniqueness.UniqueNullSparse }
        .map { index ->
            UniqueIndex(
                fields = serializer.fields!!.filter { index.fields.contains(it.name) },
                nullSparse = index.unique == IndexUniqueness.UniqueNullSparse,
            )
        }

    init {
        data.values.forEach { index(it) }
    }

    private fun index(model: Model) {
        for (unique in uniqueIndexes) {
            val key = unique.keyOf(model) ?: continue
            unique.ids.getOrPut(key) { HashSet() }.add(idOf(model))
        }
    }

    private fun unindex(model: Model) {
        for (unique in uniqueIndexes) {
            val key = unique.keyOf(model) ?: continue
            val ids = unique.ids[key] ?: continue
            ids.remove(idOf(model))
            if (ids.isEmpty()) unique.ids.remove(key)
        }
    }

    // Checks every unique index and the primary key before touching anything, so a violation leaves no trace.
    private fun write(old: Model?, new: Model) {
        for (unique in uniqueIndexes) {
            if (old != null && unique.fields.all { it.get(old) == it.get(new) }) continue
            val key = unique.keyOf(new) ?: continue
            val holders = unique.ids[key] ?: continue
            if (holders.any { old == null || it != idOf(old) }) throw UniqueViolationException(
                table = serializer.descriptor.serialName,
                key = unique.fields.joinToString { it.name },
                cause = IllegalStateException()
            )
        }
        val existing = data[idOf(new)]
        if (existing != null && existing !== old) throw UniqueViolationException(
            table = serializer.descriptor.serialName,
            key = "_id",
            cause = IllegalStateException()
        )
        if (old != null) {
            unindex(old)
            if (idOf(old) != idOf(new)) data.remove(idOf(old))
        }
        data[idOf(new)] = new
        index(new)
    }

    private fun delete(old: Model) {
        unindex(old)
        data.remove(idOf(old))
    }

    /** Place items into [data] keyed by `_id`. Bypasses unique checks; intended for trusted preload paths. */
    public fun preload(items: Iterable<Model>) {
        lock.withLock {
            for (item in items) {
                data.put(idOf(item), item)?.let { unindex(it) }
                index(item)
            }
        }
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
    ) : this(HashMap<Any?, Model>(), serializer, tableName) {
        preload(data)
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
        val result = traced {
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

    override suspend fun findByTextIndex(
        query: String,
        condition: Condition<Model>,
        skip: Int,
        limit: Int,
        maxQueryMs: Long,
    ): Flow<ScoredResult<Model>> {
        val fields = checkNotNull(textIndexFields) { "findByTextIndex needs a @TextIndex on ${serializer.descriptor.serialName}" }
        val textQuery = TextQuery.fromString(query)
        return flow {
            val results = traced {
                lock.withLock {
                    // Claude: scored against every row, since word rarity and average length are table-wide.
                    val models = data.values.toList()
                    val scores = textQuery.scores(models.map { model -> fields.map { it.get(model)?.toString() ?: "" } })
                    models.zip(scores)
                        .mapNotNull { (model, score) -> if (score != null && condition(model)) ScoredResult(model, score) else null }
                        .sortedByDescending { it.score }
                        .drop(skip)
                        .take(limit)
                        .toList()
                }
            }
            results.forEach { emit(it) }
        }
    }

    override suspend fun count(condition: Condition<Model>): Int = traced {
        lock.withLock {
            candidatesFor(condition).count { condition(it) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun <Key> groupCount(
        condition: Condition<Model>,
        groupBy: Optic<Model, Key>,
    ): Map<Key, Int> = traced {
        lock.withLock {
            val groups = data.values.asSequence().filter { condition(it) }
                .groupingBy { groupBy.get(it) }.eachCount() as Map<Key, Int>
            if (groupBy.serializer.descriptor.isNullable) groups else groups.filterKeys { it != null }
        }
    }

    override suspend fun aggregate(
        aggregate: Aggregate,
        condition: Condition<Model>,
        property: Optic<Model, out Number?>,
    ): Double? = traced {
        lock.withLock {
            data.values.asSequence()
                .filter { condition(it) }
                .aggregateOfNotNull(aggregate) { property.get(it)?.toDouble() }
        }
    }

    override suspend fun <Key> groupAggregate(
        aggregate: Aggregate,
        condition: Condition<Model>,
        groupBy: Optic<Model, Key>,
        property: Optic<Model, out Number?>,
    ): Map<Key, Double?> = traced {
        lock.withLock {
            val groups = data.values.asSequence()
                .filter { condition(it) }
                .groupAggregateNotNull(aggregate) {
                    @Suppress("UNCHECKED_CAST")
                    Pair(
                        groupBy.get(it) as Key,
                        property.get(it)?.toDouble() ?: return@groupAggregateNotNull null
                    )
                }
            if (groupBy.serializer.descriptor.isNullable) groups else groups.filterKeys { it != null }
        }
    }

    override suspend fun insert(models: Iterable<Model>): List<Model> = traced {
        val list = models.toList()
        lock.withLock {
            // Checked and written one at a time so each row is validated against the rows already
            // placed by this same call. Checking the whole batch up front compared every new row
            // only against previously committed data, so two conflicting rows inserted together
            // never saw each other and both went in.
            for (m in list) write(null, m)
            return@traced list
        }
    }

    override suspend fun replaceOne(
        condition: Condition<Model>,
        model: Model,
        orderBy: List<SortPart<Model>>,
    ): EntryChange<Model> = traced {
        lock.withLock {
            for (old in snapshotFor(condition, orderBy)) {
                write(old, model)
                return@traced EntryChange(old, model)
            }
            return@traced EntryChange(null, null)
        }
    }

    override suspend fun upsertOne(
        condition: Condition<Model>,
        modification: Modification<Model>,
        model: Model,
    ): EntryChange<Model> = traced {
        lock.withLock {
            for (old in snapshotFor(condition, emptyList())) {
                val new = modification(old)
                write(old, new)
                return@traced EntryChange(old, new)
            }
            write(null, model)
            return@traced EntryChange(null, model)
        }
    }

    override suspend fun updateOne(
        condition: Condition<Model>,
        modification: Modification<Model>,
        orderBy: List<SortPart<Model>>,
    ): EntryChange<Model> = traced {
        lock.withLock {
            for (old in snapshotFor(condition, orderBy)) {
                val new = modification(old)
                write(old, new)
                return@traced EntryChange(old, new)
            }
            return@traced EntryChange(null, null)
        }
    }

    override suspend fun updateMany(
        condition: Condition<Model>,
        modification: Modification<Model>,
    ): CollectionChanges<Model> = traced {
        lock.withLock {
            val matches = snapshotFor(condition, emptyList())
                .map { old -> EntryChange(old, modification(old)) }
            // Same reason as [insert]: checked and written per row, so a modification that makes two
            // of the matched rows collide with each other is caught rather than silently applied.
            for (change in matches) write(change.old!!, change.new!!)
            return@traced CollectionChanges(changes = matches)
        }
    }

    override suspend fun deleteOne(condition: Condition<Model>, orderBy: List<SortPart<Model>>): Model? = traced {
        lock.withLock {
            for (old in snapshotFor(condition, orderBy)) {
                delete(old)
                return@traced old
            }
            return@traced null
        }
    }

    override suspend fun deleteMany(condition: Condition<Model>): List<Model> = traced {
        lock.withLock {
            val removed = snapshotFor(condition, emptyList()).toList()
            for (m in removed) delete(m)
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
        lock.withLock {
            data.clear()
            uniqueIndexes.forEach { it.ids.clear() }
        }
    }
}
