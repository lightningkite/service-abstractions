package com.lightningkite.services.ai.koog.rag

import ai.koog.embeddings.base.Embedder
import ai.koog.rag.vector.*
import com.lightningkite.services.Setting
import com.lightningkite.services.SettingContext
import kotlinx.serialization.Serializable
import java.nio.file.Path

/**
 * Settings for creating a complete RAG (Retrieval-Augmented Generation) system for text files.
 *
 * This combines an [Embedder] with [VectorStorage] to create an [EmbeddingBasedDocumentStorage]
 * specifically for JVM file paths (text documents).
 *
 * Example usage:
 * ```kotlin
 * @Serializable
 * data class AppSettings(
 *     val rag: TextFileRAGSettings = TextFileRAGSettings(
 *         embedder = EmbedderSettings("embedder-ollama://nomic-embed-text"),
 *         storage = VectorStorageSettings("rag-file://./vectors")
 *     )
 * )
 *
 * // Instantiate
 * val ragStorage = settings.rag("my-rag", context)
 *
 * // Store documents
 * ragStorage.store(Path.of("./docs/document.txt"))
 *
 * // Find relevant documents
 * val relevant = ragStorage.mostRelevantDocuments("query text", count = 5)
 * ```
 *
 * @property embedder Settings for the embedder (converts text to vectors)
 * @property storage Settings for the vector storage backend
 */
@Serializable
public data class EmbeddingBasedDocumentStorageSettings(
    public val embedder: EmbedderSettings,
    public val storage: VectorStorageSettings<Path>
) : Setting<EmbeddingBasedDocumentStorage<Path>> {

    override fun invoke(
        name: String,
        context: SettingContext
    ): EmbeddingBasedDocumentStorage<Path> {
        // Instantiate the embedder
        val embedderInstance: Embedder = embedder("$name-embedder", context)

        // Instantiate the vector storage
        val storageInstance: VectorStorage<Path> = storage("$name-storage", context)

        // Create a text document embedder (JVM-specific for file paths)
        val documentEmbedder = JVMTextDocumentEmbedder(embedderInstance)

        // Combine them into an EmbeddingBasedDocumentStorage
        return EmbeddingBasedDocumentStorage(documentEmbedder, storageInstance)
    }
}
