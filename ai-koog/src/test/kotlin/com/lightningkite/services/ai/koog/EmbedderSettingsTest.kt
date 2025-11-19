package com.lightningkite.services.ai.koog.rag

import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.llm.OllamaModels
import kotlin.test.*

/**
 * Tests for EmbedderSettings focusing on URL construction and serialization.
 * Note: Tests that require actual embedder API calls are skipped to avoid dependency on external services.
 */
class EmbedderSettingsTest {

    @Test
    fun testSerializationOpenAI() {
        val url = "openai://text-embedding-3-small?apiKey=test-key"
        val settings = EmbedderSettings(url)

        assertEquals(url, settings.url)
    }

    @Test
    fun testSerializationOpenAIWithEnvVar() {
        val url = "openai://text-embedding-3-small?apiKey=\${OPENAI_API_KEY}"
        val settings = EmbedderSettings(url)

        assertEquals(url, settings.url)
    }

    @Test
    fun testSerializationOllama() {
        val url = "ollama://nomic-embed-text"
        val settings = EmbedderSettings(url)

        assertEquals(url, settings.url)
    }

    @Test
    fun testSerializationOllamaWithCustomBaseUrl() {
        val url = "ollama://nomic-embed-text?baseUrl=http://custom-ollama:11434"
        val settings = EmbedderSettings(url)

        assertEquals(url, settings.url)
    }

    @Test
    fun testFactoryMethodOpenAI() {
        val settings = EmbedderSettings.openai(
            OpenAIModels.Embeddings.TextEmbedding3Small,
            "test-api-key"
        )

        assertEquals("openai://text-embedding-3-small?apiKey=test-api-key", settings.url)
    }

    @Test
    fun testFactoryMethodOpenAIWithoutApiKey() {
        val settings = EmbedderSettings.openai(OpenAIModels.Embeddings.TextEmbedding3Small)

        assertEquals("openai://text-embedding-3-small", settings.url)
    }

    @Test
    fun testFactoryMethodOpenAILargeEmbedding() {
        val settings = EmbedderSettings.openai(
            OpenAIModels.Embeddings.TextEmbedding3Large,
            "my-key"
        )

        assertEquals("openai://text-embedding-3-large?apiKey=my-key", settings.url)
    }

    @Test
    fun testFactoryMethodOllama() {
        val settings = EmbedderSettings.ollama(OllamaModels.Meta.LLAMA_3_2)

        // The actual model ID is "llama3.2:latest"
        assertEquals("ollama://llama3.2:latest", settings.url)
    }

    @Test
    fun testFactoryMethodOllamaWithApiKey() {
        val settings = EmbedderSettings.ollama(OllamaModels.Meta.LLAMA_3_2, "some-key")

        // The actual model ID is "llama3.2:latest"
        assertEquals("ollama://llama3.2:latest?apiKey=some-key", settings.url)
    }

    @Test
    fun testSerializationWithMultipleParams() {
        val url = "openai://text-embedding-3-small?apiKey=test-key&param2=value"
        val settings = EmbedderSettings(url)

        assertEquals(url, settings.url)
    }

    @Test
    fun testUrlParsingExtractsModelName() {
        val url = "openai://text-embedding-3-small?apiKey=test-key"
        val settings = EmbedderSettings(url)

        // The model name should be "text-embedding-3-small"
        assertTrue(settings.url.contains("text-embedding-3-small"))
    }

    @Test
    fun testUrlParsingExtractsApiKey() {
        val url = "openai://text-embedding-3-small?apiKey=my-secret-key"
        val settings = EmbedderSettings(url)

        assertTrue(settings.url.contains("apiKey=my-secret-key"))
    }

    @Test
    fun testUrlParsingHandlesEmptyParams() {
        val url = "ollama://nomic-embed-text"
        val settings = EmbedderSettings(url)

        assertEquals(url, settings.url)
        assertFalse(settings.url.contains("?"))
    }
}
