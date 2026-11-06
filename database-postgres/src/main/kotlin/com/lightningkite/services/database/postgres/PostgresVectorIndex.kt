package com.lightningkite.services.database.postgres

import com.lightningkite.services.database.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.batchUpsert
import org.jetbrains.exposed.v1.jdbc.select

/**
 * A pgvector index over [collection]'s table. Embeddings are stored as `real[]` and cast to `vector(n)` in queries,
 * matching the HNSW index's expression so Postgres uses it.
 */
internal class PostgresVectorIndex<ID : Comparable<ID>>(
    private val collection: PostgresCollection<VectorIndexEntry<ID>>,
    override val space: VectorSpace,
) : VectorIndex<ID> {
    private val id = collection.serializer._id()
    private val idColumns = collection.table.columnsByDotPath.getValue(listOf("_id"))
    private val embedding = collection.table.col.getValue("embedding")

    suspend fun prepare() {
        val indexName = "${collection.table.tableName}_embedding_hnsw"
        // Postgres silently truncates longer names, so two indexes could end up with the same one.
        require(indexName.length <= 63) { "The vector index name $indexName is over Postgres's 63 characters; shorten the index's name." }
        collection.prepare.await()
        collection.t {
            exec("CREATE EXTENSION IF NOT EXISTS vector")
            exec("CREATE INDEX IF NOT EXISTS ${db.identifierManager.quoteIfNecessary(indexName)} ON ${identity(collection.table)} USING hnsw ((${identity(embedding)}::vector(${space.dimensions})) vector_cosine_ops)")
        }
    }

    override suspend fun upsert(entries: Collection<VectorIndexEntry<ID>>) {
        for (entry in entries) space.requireDimensions(entry.embedding, "The embedding of ${entry._id}")
        if (entries.isEmpty()) return
        collection.t {
            collection.table.batchUpsert(entries) { collection.format.encode(collection.serializer, it, this) }
        }
    }

    override suspend fun delete(ids: Collection<ID>) {
        collection.deleteManyIgnoringOld(Condition.OnField(id, Condition.Inside(ids.toSet())))
    }

    override suspend fun get(ids: Collection<ID>): List<VectorIndexEntry<ID>> = collection.getMany(ids)

    override fun ids(): Flow<ID> = flow {
        collection.t { collection.table.select(idColumns).toList() }
            .forEach { emit(collection.format.decode(id.serializer, it, listOf("_id"))) }
    }

    override suspend fun search(query: Embedding, limit: Int): List<ScoredResult<ID>> {
        space.requireDimensions(query, "The query")
        val distance = object : ExpressionWithColumnType<Float>() {
            override val columnType: IColumnType<Float> = FloatColumnType()
            override fun toQueryBuilder(queryBuilder: QueryBuilder) = queryBuilder {
                append("(", embedding, "::vector(${space.dimensions})) <=> (")
                registerArgument(TextColumnType(), query.values.joinToString(",", "[", "]"))
                append("::vector(${space.dimensions}))")
            }
        }
        val rows = collection.t {
            collection.table.select(idColumns + distance)
                .orderBy(distance to SortOrder.ASC)
                .limit(limit)
                .toList()
        }
        // Cosine distance is 1 - cos, so (1 + cos) / 2 is 1 - distance / 2.
        return rows.map { ScoredResult(collection.format.decode(id.serializer, it, listOf("_id")), 1f - it[distance] / 2f) }
    }
}
