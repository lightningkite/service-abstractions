package com.lightningkite.services.ai.koog

import kotlin.test.*

/**
 * Tests for LLMClientAndModel.Settings focusing on URL parsing and serialization.
 * Note: Tests that require actual LLM API calls are skipped to avoid dependency on external services.
 */
class LLMClientAndModelSettingsTest {

    @Test
    fun testSerializationOpenAI() {
        val url = "openai://gpt-4o?apiKey=test-key-123"
        val settings = LLMClientAndModel.Settings(url)

        assertEquals(url, settings.url)
    }

    @Test
    fun testSerializationAnthropic() {
        val url = "anthropic://claude-sonnet-4-5?apiKey=test-anthropic-key"
        val settings = LLMClientAndModel.Settings(url)

        assertEquals(url, settings.url)
    }

    @Test
    fun testSerializationGoogle() {
        val url = "google://gemini-2.5-pro?apiKey=test-google-key"
        val settings = LLMClientAndModel.Settings(url)

        assertEquals(url, settings.url)
    }

    @Test
    fun testSerializationOllama() {
        val url = "ollama://llama-3.2"
        val settings = LLMClientAndModel.Settings(url)

        assertEquals(url, settings.url)
    }

    @Test
    fun testSerializationOllamaWithCustomBaseUrl() {
        val url = "ollama://llama-3.2?baseUrl=http://custom-host:11434"
        val settings = LLMClientAndModel.Settings(url)

        assertEquals(url, settings.url)
    }

    @Test
    fun testSerializationOpenRouter() {
        val url = "openrouter://gpt-5?apiKey=test-openrouter-key"
        val settings = LLMClientAndModel.Settings(url)

        assertEquals(url, settings.url)
    }

    @Test
    fun testSerializationWithEnvVarPlaceholder() {
        val url = "openai://gpt-4o?apiKey=\${MY_OPENAI_KEY}"
        val settings = LLMClientAndModel.Settings(url)

        assertEquals(url, settings.url)
    }

    @Test
    fun testSerializationWithMultipleParams() {
        val url = "openai://gpt-4o?apiKey=test-key&param2=value"
        val settings = LLMClientAndModel.Settings(url)

        assertEquals(url, settings.url)
    }

    @Test
    fun testKnownModelsContainsExpectedModels() {
        val knownModels = LLMClientAndModel.Settings.knownModels

        assertTrue(knownModels.isNotEmpty())

        // Verify some expected models are present
        assertTrue(knownModels.values.any { it.id == "gpt-4o" })
        assertTrue(knownModels.values.any { it.id == "claude-sonnet-4-5" })
        assertTrue(knownModels.values.any { it.id == "gemini-2.5-pro" })
        assertTrue(knownModels.values.any { it.id == "text-embedding-3-small" })
        assertTrue(knownModels.values.any { it.id == "text-embedding-3-large" })
    }

    @Test
    fun testKnownModelsHasOpenAIModels() {
        val knownModels = LLMClientAndModel.Settings.knownModels

        val openAIModels = knownModels.values.filter { it.id.contains("gpt") || it.id.contains("text-embedding") }
        assertTrue(openAIModels.isNotEmpty(), "Should have OpenAI models")
    }

    @Test
    fun testKnownModelsHasAnthropicModels() {
        val knownModels = LLMClientAndModel.Settings.knownModels

        val anthropicModels = knownModels.values.filter { it.id.contains("claude") }
        assertTrue(anthropicModels.isNotEmpty(), "Should have Anthropic models")
    }

    @Test
    fun testKnownModelsHasGoogleModels() {
        val knownModels = LLMClientAndModel.Settings.knownModels

        val googleModels = knownModels.values.filter { it.id.contains("gemini") }
        assertTrue(googleModels.isNotEmpty(), "Should have Google models")
    }

    @Test
    fun testKnownModelsHasOllamaModels() {
        val knownModels = LLMClientAndModel.Settings.knownModels

        val ollamaModels = knownModels.values.filter { it.id.contains("llama") || it.id.contains("qwen") }
        assertTrue(ollamaModels.isNotEmpty(), "Should have Ollama models")
    }
}
