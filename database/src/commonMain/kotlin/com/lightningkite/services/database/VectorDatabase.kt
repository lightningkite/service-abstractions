package com.lightningkite.services.database

import com.lightningkite.services.Service
import com.lightningkite.services.Setting
import com.lightningkite.services.SettingContext
import com.lightningkite.services.UrlSettingParser
import com.lightningkite.services.embedding.EmbeddingModelId
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * The embedding model behind an index's vectors. Vectors from different models can't be compared.
 *
 * Every index compares vectors by cosine similarity, scored from 0 (opposite) to 1 (same direction): `(1 + cos) / 2`.
 */
@Serializable
public data class VectorSpace(val model: EmbeddingModelId, val dimensions: Int) {
    /** Throws unless [embedding], described by [what] in the message, belongs in this space. */
    public fun requireDimensions(embedding: Embedding, what: String) {
        require(embedding.dimensions == dimensions) { "$what has ${embedding.dimensions} dimensions; the index needs $dimensions." }
    }
}

public class VectorIndexDefinition<ID : Comparable<ID>>(
    public val name: String,
    public val idSerializer: KSerializer<ID>,
    public val space: VectorSpace,
) {
    /**
     * Where drivers store the index. It includes the space, so changing the embedding model starts an empty index
     * beside the old one, to fill with `rebuild`.
     */
    public val storageName: String =
        "${name}_${space.model.id}_${space.dimensions}".map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
}

/**
 * Stores [VectorIndex]es, apart from the models they search.
 *
 * `ram` is [InMemoryDatabase]. database-mongodb registers `mongodb`
 * and `mongodb+srv` (Atlas `$vectorSearch`), and database-postgres registers `postgresql` (pgvector): those
 * databases are vector databases too.
 */
public interface VectorDatabase : Service {
    public fun <ID : Comparable<ID>> index(definition: VectorIndexDefinition<ID>): VectorIndex<ID>

    /** Creates the index's storage and search structures; call once per deploy. */
    public suspend fun <ID : Comparable<ID>> prepare(definition: VectorIndexDefinition<ID>): VectorIndex<ID>

    @Serializable
    @JvmInline
    public value class Settings(public val url: String = "ram") : Setting<VectorDatabase> {
        public companion object : UrlSettingParser<VectorDatabase>() {
            init {
                register("ram") { name, _, context -> InMemoryDatabase(name, context = context) }
            }
        }

        override fun invoke(name: String, context: SettingContext): VectorDatabase = parse(name, url, context)
    }
}

/**
 * Writes don't touch the source rows; see [vectorIndexed] for keeping an index in step with a table.
 */
public interface VectorIndex<ID : Comparable<ID>> {
    public val space: VectorSpace
    public suspend fun upsert(entries: Collection<VectorIndexEntry<ID>>)
    public suspend fun delete(ids: Collection<ID>)
    public suspend fun get(ids: Collection<ID>): List<VectorIndexEntry<ID>>
    public fun ids(): Flow<ID>

    /**
     * The IDs of the entries closest to [query], closest first.
     *
     * Atlas and Postgres's HNSW index search approximately, so they can occasionally miss a close entry.
     */
    public suspend fun search(query: Embedding, limit: Int): List<ScoredResult<ID>>
}
