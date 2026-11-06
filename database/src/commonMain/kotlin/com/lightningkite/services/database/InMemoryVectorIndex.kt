package com.lightningkite.services.database

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList

/** Searches by comparing the query with every entry; any [Table] can hold the entries. */
public class InMemoryVectorIndex<ID : Comparable<ID>>(
    private val table: Table<VectorIndexEntry<ID>>,
    override val space: VectorSpace,
) : VectorIndex<ID> {
    private val id = table.serializer._id()

    override suspend fun upsert(entries: Collection<VectorIndexEntry<ID>>) {
        for (entry in entries) space.requireDimensions(entry.embedding, "The embedding of ${entry._id}")
        for (entry in entries) table.upsertOneById(entry._id, entry)
    }

    override suspend fun delete(ids: Collection<ID>) {
        table.deleteManyIgnoringOld(Condition.OnField(id, Condition.Inside(ids.toSet())))
    }

    override suspend fun get(ids: Collection<ID>): List<VectorIndexEntry<ID>> = table.getMany(ids)

    override fun ids(): Flow<ID> = flow { table.find(Condition.Always).map { it._id }.collect { emit(it) } }

    override suspend fun search(query: Embedding, limit: Int): List<ScoredResult<ID>> {
        space.requireDimensions(query, "The query")
        return table.find(Condition.Always).toList()
            .map { ScoredResult(it._id, EmbeddingSimilarity.cosineSimilarity(query, it.embedding)) }
            .sortedByDescending { it.score }
            .take(limit)
    }
}
