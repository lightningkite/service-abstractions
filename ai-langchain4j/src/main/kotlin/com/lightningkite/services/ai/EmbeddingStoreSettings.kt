package com.lightningkite.services.ai

import com.lightningkite.services.Setting
import com.lightningkite.services.SettingContext
import com.lightningkite.services.UrlSettingParser
import dev.langchain4j.data.embedding.Embedding
import dev.langchain4j.store.embedding.EmbeddingStore
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Settings for instantiating a LangChain4J [EmbeddingStore].
 *
 * EmbeddingStore provides vector database storage and similarity search capabilities
 * for RAG (Retrieval-Augmented Generation) applications.
 *
 * The URL scheme determines which vector database implementation to use:
 * - `pinecone://index-name?apiKey=...&environment=...` - Pinecone
 * - `qdrant://host:port/collection?apiKey=...` - Qdrant
 * - `pgvector://host:port/database?table=...&user=...&password=...` - PostgreSQL pgvector
 * - `mongodb-atlas://host/database?collection=...` - MongoDB Atlas Vector Search
 * - `in-memory-embedding://?maxResults=1000` - In-memory (for testing)
 * - Other schemes registered by implementation modules
 *
 * Example usage:
 * ```kotlin
 * val settings = EmbeddingStoreSettings("pinecone://my-index?apiKey=\${PINECONE_API_KEY}&environment=us-east-1")
 * val store: EmbeddingStore<TextSegment> = settings("my-vector-db", context)
 * store.add(embedding, textSegment)
 * val relevant = store.findRelevant(queryEmbedding, maxResults = 5)
 * ```
 *
 * @property url Connection string defining the vector database and configuration
 */
@Serializable
@JvmInline
public value class EmbeddingStoreSettings(
    public val url: String
) : Setting<EmbeddingStore<*>> {

    public companion object : UrlSettingParser<EmbeddingStore<*>>()

    override fun invoke(name: String, context: SettingContext): EmbeddingStore<*> {
        return parse(name, url, context)
    }
}
