package com.lightningkite.services.ai.koog.rag

import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Tests for DocumentEmbeddingStorageSettings focusing on configuration and serialization.
 * Note: Tests for the actual `invoke` method require running Ollama/OpenAI services
 * and are not included here as unit tests.
 * by Claude
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
        val storageSettings = VectorStorageSettings<Path>("rag-memory://")

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

    /**
     * Tests that JSON serialization and deserialization round-trips correctly.
     * by Claude
     */
    @Test
    fun testJsonSerializationRoundTrip() {
        val original = EmbeddingBasedDocumentStorageSettings(
            embedder = EmbedderSettings("ollama://nomic-embed-text"),
            storage = VectorStorageSettings("rag-memory://")
        )

        val json = Json.encodeToString(original)
        val deserialized = Json.decodeFromString<EmbeddingBasedDocumentStorageSettings>(json)

        assertEquals(original.embedder.url, deserialized.embedder.url)
        assertEquals(original.storage.url, deserialized.storage.url)
    }

    /**
     * Tests JSON serialization structure matches expected format.
     * by Claude
     */
    @Test
    fun testJsonStructure() {
        val settings = EmbeddingBasedDocumentStorageSettings(
            embedder = EmbedderSettings("openai://text-embedding-3-small"),
            storage = VectorStorageSettings("rag-file://./vectors")
        )

        val json = Json.encodeToString(settings)

        // Verify JSON contains expected key structure
        assertTrue(json.contains("\"embedder\""))
        assertTrue(json.contains("\"storage\""))
        assertTrue(json.contains("openai://text-embedding-3-small"))
        assertTrue(json.contains("rag-file://./vectors"))
    }

    /**
     * Tests that data class equality works as expected.
     * by Claude
     */
    @Test
    fun testDataClassEquality() {
        val settings1 = EmbeddingBasedDocumentStorageSettings(
            embedder = EmbedderSettings("ollama://nomic-embed-text"),
            storage = VectorStorageSettings("rag-memory://")
        )

        val settings2 = EmbeddingBasedDocumentStorageSettings(
            embedder = EmbedderSettings("ollama://nomic-embed-text"),
            storage = VectorStorageSettings("rag-memory://")
        )

        val settings3 = EmbeddingBasedDocumentStorageSettings(
            embedder = EmbedderSettings("ollama://different-model"),
            storage = VectorStorageSettings("rag-memory://")
        )

        assertEquals(settings1, settings2)
        assertTrue(settings1 != settings3)
    }

    /**
     * Tests data class copy functionality.
     * by Claude
     */
    @Test
    fun testDataClassCopy() {
        val original = EmbeddingBasedDocumentStorageSettings(
            embedder = EmbedderSettings("ollama://nomic-embed-text"),
            storage = VectorStorageSettings("rag-memory://")
        )

        val modified = original.copy(
            embedder = EmbedderSettings("openai://text-embedding-3-small")
        )

        assertEquals("ollama://nomic-embed-text", original.embedder.url)
        assertEquals("openai://text-embedding-3-small", modified.embedder.url)
        assertEquals(original.storage.url, modified.storage.url)
    }

    private fun assertTrue(condition: Boolean, message: String = "") {
        kotlin.test.assertTrue(condition, message)
    }
}
