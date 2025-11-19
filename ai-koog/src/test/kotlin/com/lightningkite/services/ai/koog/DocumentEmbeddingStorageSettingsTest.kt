package com.lightningkite.services.ai.koog.rag

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Tests for DocumentEmbeddingStorageSettings focusing on configuration and serialization.
 */
class DocumentEmbeddingStorageSettingsTest {

    @Test
    fun testSerializationWithOllamaAndMemory() {
        val settings = EmbeddingBasedDocumentStorageSettings(
            embedder = EmbedderSettings("ollama://nomic-embed-text"),
            storage = VectorStorageSettings("rag-memory://")
        )

        assertEquals("ollama://nomic-embed-text", settings.embedder.url)
        assertEquals("rag-memory://", settings.storage.url)
    }

    @Test
    fun testSerializationWithOpenAIAndFile() {
        val settings = EmbeddingBasedDocumentStorageSettings(
            embedder = EmbedderSettings("openai://text-embedding-3-small?apiKey=test-key"),
            storage = VectorStorageSettings("rag-file://./local/test-rag-vectors")
        )

        assertEquals("openai://text-embedding-3-small?apiKey=test-key", settings.embedder.url)
        assertEquals("rag-file://./local/test-rag-vectors", settings.storage.url)
    }

    @Test
    fun testSettingsComposition() {
        val embedderSettings = EmbedderSettings("ollama://nomic-embed-text")
        val storageSettings = VectorStorageSettings<java.nio.file.Path>("rag-memory://")

        val documentSettings = EmbeddingBasedDocumentStorageSettings(
            embedder = embedderSettings,
            storage = storageSettings
        )

        assertNotNull(documentSettings.embedder)
        assertNotNull(documentSettings.storage)
        assertEquals(embedderSettings.url, documentSettings.embedder.url)
        assertEquals(storageSettings.url, documentSettings.storage.url)
    }

    @Test
    fun testDifferentStorageBackends() {
        val memoryBased = EmbeddingBasedDocumentStorageSettings(
            embedder = EmbedderSettings("ollama://nomic-embed-text"),
            storage = VectorStorageSettings("rag-memory://")
        )

        val fileBased = EmbeddingBasedDocumentStorageSettings(
            embedder = EmbedderSettings("ollama://nomic-embed-text"),
            storage = VectorStorageSettings("rag-file://./vectors")
        )

        assertEquals("rag-memory://", memoryBased.storage.url)
        assertEquals("rag-file://./vectors", fileBased.storage.url)
    }

    @Test
    fun testDifferentEmbedders() {
        val ollamaBased = EmbeddingBasedDocumentStorageSettings(
            embedder = EmbedderSettings("ollama://nomic-embed-text"),
            storage = VectorStorageSettings("rag-memory://")
        )

        val openAIBased = EmbeddingBasedDocumentStorageSettings(
            embedder = EmbedderSettings("openai://text-embedding-3-small?apiKey=key"),
            storage = VectorStorageSettings("rag-memory://")
        )

        assertEquals("ollama://nomic-embed-text", ollamaBased.embedder.url)
        assertEquals("openai://text-embedding-3-small?apiKey=key", openAIBased.embedder.url)
    }

    @Test
    fun testFullConfiguration() {
        val settings = EmbeddingBasedDocumentStorageSettings(
            embedder = EmbedderSettings("ollama://nomic-embed-text?baseUrl=http://localhost:11434"),
            storage = VectorStorageSettings("rag-file://./local/rag-data?param=value")
        )

        // Verify full URLs are preserved
        assertTrue(settings.embedder.url.contains("baseUrl=http://localhost:11434"))
        assertTrue(settings.storage.url.contains("param=value"))
    }

    private fun assertTrue(condition: Boolean, message: String = "") {
        kotlin.test.assertTrue(condition, message)
    }
}
