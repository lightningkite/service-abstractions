package com.lightningkite.services.database

import kotlinx.atomicfu.AtomicRef
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.atomicfu.update
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.KSerializer

/**
 * A FieldCollection who's underlying implementation is actually manipulating a MutableList.
 * This is useful for times that an actual database is not needed, and you need to move fast, such as during Unit Tests.
 */
open class InMemoryFieldCollection<Model : Any>(
    val data: MutableList<Model> = ArrayList(),
    override val serializer: KSerializer<Model>
) : FieldCollection<Model> {

    private val lock = ReentrantLock()

    private val uniqueIndexChecks: AtomicRef<List<(List<EntryChange<Model>>) -> Unit>> = atomic(emptyList())

    private fun uniqueCheck(changed: EntryChange<Model>) = uniqueCheck(listOf(changed))
    private fun uniqueCheck(changes: List<EntryChange<Model>>) =
        lock.withLock { uniqueIndexChecks.value.forEach { it(changes) } }

    init {
        serializer.descriptor.indexes().plus(NeededIndex(fields = listOf("_id"), true, "primary key"))
            .forEach { index: NeededIndex ->
                if (index.unique) {
                    val fields = serializer.serializableProperties!!.filter { index.fields.contains(it.name) }
                    uniqueIndexChecks.update { it ->
                        it.plus({ changes: List<EntryChange<Model>> ->
                            val fieldChanges = changes.mapNotNull { entryChange ->
                                if (
                                    (entryChange.old == null && entryChange.new != null) ||
                                    (entryChange.old != null &&
                                            entryChange.new != null &&
                                            fields.any { it.get(entryChange.old) != it.get(entryChange.new) })
                                )
                                    fields.map { it to it.get(entryChange.new) }
                                else
                                    null
                            }
                            fieldChanges.forEach { fieldValues ->
                                if (data.any { fromDb ->
                                        fieldValues.all { (property, value) ->
                                            property.get(fromDb) == value
                                        }
                                    }) {
                                    throw UniqueViolationException(
                                        collection = serializer.descriptor.serialName,
                                        key = fields.joinToString { it.name },
                                        cause = IllegalStateException()
                                    )
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
        val result = lock.withLock {
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
        result
            .forEach {
                emit(it)
            }
    }

    override suspend fun count(condition: Condition<Model>): Int = data.count { condition(it) }

    @Suppress("UNCHECKED_CAST")
    override suspend fun <Key> groupCount(
        condition: Condition<Model>,
        groupBy: DataClassPath<Model, Key>,
    ): Map<Key, Int> =
        data.filter { condition(it) }.groupingBy { groupBy.get(it) }.eachCount().minus(null) as Map<Key, Int>

    override suspend fun <N : Number?> aggregate(
        aggregate: Aggregate,
        condition: Condition<Model>,
        property: DataClassPath<Model, N>,
    ): Double? =
        data.asSequence().filter { condition(it) }.mapNotNull { property.get(it)?.toDouble() }.aggregate(aggregate)

    override suspend fun <N : Number?, Key> groupAggregate(
        aggregate: Aggregate,
        condition: Condition<Model>,
        groupBy: DataClassPath<Model, Key>,
        property: DataClassPath<Model, N>,
    ): Map<Key, Double?> = data.asSequence().filter { condition(it) }
        .mapNotNull {
            (groupBy.get(it) ?: return@mapNotNull null) to (property.get(it)?.toDouble() ?: return@mapNotNull null)
        }.aggregate(aggregate)

    override suspend fun insert(models: Iterable<Model>): List<Model> = lock.withLock {
        uniqueCheck(models.map { EntryChange(null, it) })
        data.addAll(models)
        return models.toList()
    }

    override suspend fun replaceOne(
        condition: Condition<Model>,
        model: Model,
        orderBy: List<SortPart<Model>>,
    ): EntryChange<Model> = lock.withLock {
        for (it in sortIndices(orderBy)) {
            val old = data[it]
            if (condition(old)) {
                val changed = EntryChange(old, model)
                uniqueCheck(changed)
                data[it] = model
                return changed
            }
        }
        return EntryChange(null, null)
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
    ): EntryChange<Model> = lock.withLock {
        for (it in data.indices) {
            val old = data[it]
            if (condition(old)) {
                val new = modification(old)
                val changed = EntryChange(old, new)
                uniqueCheck(changed)
                data[it] = new
                return changed
            }
        }
        data.add(model)
        return EntryChange(null, model)
    }

    override suspend fun updateOne(
        condition: Condition<Model>,
        modification: Modification<Model>,
        orderBy: List<SortPart<Model>>,
    ): EntryChange<Model> = lock.withLock {
        for (it in sortIndices(orderBy)) {
            val old = data[it]
            if (condition(old)) {
                val new = modification(old)
                val changed = EntryChange(old, new)
                uniqueCheck(changed)
                data[it] = new
                return changed
            }
        }
        return EntryChange(null, null)
    }

    override suspend fun updateMany(
        condition: Condition<Model>,
        modification: Modification<Model>,
    ): CollectionChanges<Model> = lock.withLock {
        return data.indices
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

    override suspend fun deleteOne(condition: Condition<Model>, orderBy: List<SortPart<Model>>): Model? =
        lock.withLock {
            for (it in sortIndices(orderBy)) {
                val old = data[it]
                if (condition(old)) {
                    data.removeAt(it)
                    return old
                }
            }
            return null
        }

    override suspend fun deleteMany(condition: Condition<Model>): List<Model> = lock.withLock {
        val removed = ArrayList<Model>()
        data.removeAll {
            if (condition(it)) {
                removed.add(it)
                true
            } else {
                false
            }
        }
        return removed
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

    fun drop() {
        data.clear()
    }
}

