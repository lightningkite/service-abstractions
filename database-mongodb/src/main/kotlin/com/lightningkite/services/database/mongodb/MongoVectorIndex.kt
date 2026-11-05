package com.lightningkite.services.database.mongodb

import com.lightningkite.services.database.*
import com.mongodb.MongoCommandException
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Projections
import com.mongodb.client.model.ReplaceOneModel
import com.mongodb.client.model.ReplaceOptions
import com.mongodb.client.model.SearchIndexModel
import com.mongodb.client.model.SearchIndexType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import org.bson.BsonDocument
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

@Serializable
private data class IdRow<ID>(val _id: ID)

@Serializable
private data class ScoredRow<ID>(val _id: ID, val score: Double)

/** An Atlas `$vectorSearch` index over [table]'s collection. */
internal class MongoVectorIndex<ID : Comparable<ID>>(
    private val table: MongoTable<VectorIndexEntry<ID>>,
    override val space: VectorSpace,
) : VectorIndex<ID> {
    private val id = table.serializer._id()
    private val searchIndexName = "embedding_vector_index"

    /** Creates the search index, then waits for Atlas to make it queryable. */
    suspend fun prepareSearchIndex() = table.access.run {
        if (listSearchIndexes().name(searchIndexName).toList().isEmpty()) {
            val definition = documentOf(
                "fields" to listOf(documentOf("type" to "vector", "path" to "embedding", "numDimensions" to space.dimensions, "similarity" to "cosine"))
            )
            val model = SearchIndexModel(searchIndexName, definition, SearchIndexType.vectorSearch())
            try {
                createSearchIndexes(listOf(model)).toList()
            } catch (e: MongoCommandException) {
                // 26 is NamespaceNotFound: Atlas only indexes a collection that exists.
                if (e.errorCode != 26) throw e
                table.access.wholeDb { createCollection(namespace.collectionName) }
                createSearchIndexes(listOf(model)).toList()
            }
        }

        val started = TimeSource.Monotonic.markNow()
        while (listSearchIndexes().name(searchIndexName).toList().firstOrNull()?.getBoolean("queryable", false) != true) {
            if (started.elapsedNow() > 2.minutes) {
                // Not thrown: the index is still building, and searches work once it's done.
                table.context.reportException(IllegalStateException("Vector search index on ${namespace.fullName} isn't queryable after 2 minutes."))
                return@run
            }
            delay(500.milliseconds)
        }
    }

    override suspend fun upsert(entries: Collection<VectorIndexEntry<ID>>) {
        for (entry in entries) space.requireDimensions(entry.embedding, "The embedding of ${entry._id}")
        if (entries.isEmpty()) return
        val writes = entries.map {
            val document = table.bson.stringify(table.serializer, it)
            ReplaceOneModel<BsonDocument>(Filters.eq("_id", document["_id"]), document, ReplaceOptions().upsert(true))
        }
        table.access.run { bulkWrite(writes) }
    }

    override suspend fun delete(ids: Collection<ID>) {
        table.deleteManyIgnoringOld(Condition.OnField(id, Condition.Inside(ids.toSet())))
    }

    override suspend fun get(ids: Collection<ID>): List<VectorIndexEntry<ID>> = table.getMany(ids)

    override fun ids(): Flow<ID> = flow {
        val rows = IdRow.serializer(id.serializer)
        table.access.run { find().projection(Projections.include("_id")).toList() }
            .forEach { emit(table.bson.parse(rows, it)._id) }
    }

    override suspend fun search(query: Embedding, limit: Int): List<ScoredResult<ID>> {
        space.requireDimensions(query, "The query")
        val vectorSearch = documentOf(
            "index" to searchIndexName,
            "path" to "embedding",
            "queryVector" to query.values.toList(),
            "numCandidates" to (limit * 10).coerceAtMost(10_000),
            "limit" to limit,
        )
        val pipeline = listOf(
            documentOf("\$vectorSearch" to vectorSearch),
            documentOf("\$project" to documentOf("_id" to 1, "score" to documentOf("\$meta" to "vectorSearchScore"))),
        )
        val rows = ScoredRow.serializer(id.serializer)
        return table.access.run { aggregate<BsonDocument>(pipeline).toList() }
            .map { table.bson.parse(rows, it).let { row -> ScoredResult(row._id, row.score.toFloat()) } }
    }
}
