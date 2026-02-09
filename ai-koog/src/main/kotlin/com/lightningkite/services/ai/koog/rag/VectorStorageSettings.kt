package com.lightningkite.services.ai.koog.rag

import ai.koog.rag.vector.InMemoryVectorStorage
import ai.koog.rag.vector.JVMFileVectorStorage
import ai.koog.rag.vector.VectorStorage
import com.lightningkite.services.Setting
import com.lightningkite.services.SettingContext
import com.lightningkite.services.UrlSettingParser
import kotlinx.serialization.Serializable
import kotlin.io.path.Path
import kotlin.jvm.JvmInline

/**
 * Settings for instantiating Koog's [VectorStorage].
 *
 * VectorStorage provides low-level vector storage and retrieval capabilities.
 * For most use cases, consider using [EmbeddingBasedDocumentStorageSettings] instead, which
 * combines vector storage with document embedding for a complete RAG solution.
 *
 * The URL scheme determines which storage backend to use:
 * - `rag-memory://` - In-memory storage (for testing and development)
 * - `rag-file://path` - File-based persistent storage
 *
 * Example usage:
 * ```kotlin
 * val settings = VectorStorageSettings<MyDocument>("rag-memory://")
 * val storage = settings("my-vector-storage", context)
 *
 * // Store documents with embeddings (where vectorEmbedding is an ai.koog.embeddings.base.Vector)
 * storage.store(document, vectorEmbedding)
 *
 * // Retrieve all documents with their embeddings
 * storage.allDocumentsWithPayload().collect { (doc, vector) ->
 *     // Process document and its embedding
 * }
 * ```
 *
 * Note: VectorStorage does not provide similarity search directly. For similarity search,
 * use [EmbeddingBasedDocumentStorageSettings] which wraps VectorStorage with an Embedder
 * and provides the `rankDocuments(query)` method for similarity-based retrieval.
 *
 * @property url Connection string defining the storage backend and configuration
 */
@Serializable
@JvmInline
public value class VectorStorageSettings<Document>(
    public val url: String
) : Setting<VectorStorage<Document>> {

    public companion object : UrlSettingParser<VectorStorage<*>>() {
        init {
            // Register In-Memory Vector Storage
            register("rag-memory") { name, url, context ->
                InMemoryVectorStorage<Any>()
            }

            // Register File-Based Vector Storage
            register("rag-file") { name, url, context ->
                val path = extractPathFromUrl(url)
                JVMFileVectorStorage(Path(path))
            }
        }
    }

    override fun invoke(
        name: String,
        context: SettingContext
    ): VectorStorage<Document> {
        @Suppress("UNCHECKED_CAST")
        return (parse(name, url, context) as VectorStorage<Document>)
    }
}
/**
 * Extracts the path from a URL (the authority/host part).
 * For example: "rag-file://./data/vectors" -> "./data/vectors"
 */
private fun extractPathFromUrl(url: String): String {
    val withoutScheme = url.substringAfter("://")
    val pathPart = withoutScheme.substringBefore("?")
    return if (pathPart.isEmpty()) "." else pathPart
}

