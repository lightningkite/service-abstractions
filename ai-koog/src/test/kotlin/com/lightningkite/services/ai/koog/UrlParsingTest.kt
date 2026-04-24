package com.lightningkite.services.ai.koog

import kotlin.test.*

/**
 * Tests for URL parsing and serialization of settings classes.
 * These tests don't require actual API keys or external services.
 */
class UrlParsingTest {

    @Test
    fun testLLMClientAndModelSettingsSerialization() {
        val url = "openai://gpt-4o?apiKey=test-key"
        val settings = LLMClientAndModel.Settings(url)

        assertEquals(url, settings.url)
    }

    @Test
    fun testLLMClientAndModelSettingsUrlWithoutQueryParams() {
        val url = "anthropic://claude-sonnet-4-5"
        val settings = LLMClientAndModel.Settings(url)

        assertEquals(url, settings.url)
    }

    @Test
    fun testLLMClientAndModelSettingsWithMultipleParams() {
        val url = "openai://gpt-4o?apiKey=test-key&param2=value2"
        val settings = LLMClientAndModel.Settings(url)

        assertEquals(url, settings.url)
    }

    @Test
    fun testKnownModelsMapIsPopulated() {
        val knownModels = LLMClientAndModel.Settings.knownModels

        assertNotNull(knownModels)
        assertTrue(knownModels.isNotEmpty(), "Known models map should not be empty")

        // Verify some expected models exist
        assertTrue(knownModels.values.any { it.id == "gpt-4o" }, "Should contain gpt-4o")
        assertTrue(knownModels.values.any { it.id == "claude-sonnet-4-5" }, "Should contain claude-sonnet-4-5")
        assertTrue(knownModels.values.any { it.id == "gemini-2.5-pro" }, "Should contain gemini-2.5-pro")
        assertTrue(knownModels.values.any { it.id.contains("llama") }, "Should contain at least one llama model")
    }

    @Test
    fun testKnownModelsContainsProviderInformation() {
        val knownModels = LLMClientAndModel.Settings.knownModels

        // Verify that keys contain provider information
        val keys = knownModels.keys
        assertTrue(keys.isNotEmpty(), "Should have model keys")

        // Each key should be a Pair of (LLMProvider, String)
        val firstKey = keys.first()
        assertNotNull(firstKey.first, "Provider should not be null")
        assertNotNull(firstKey.second, "Model ID should not be null")
    }

    @Test
    fun testAllKnownModelsHaveValidIds() {
        val knownModels = LLMClientAndModel.Settings.knownModels

        knownModels.values.forEach { model ->
            assertTrue(model.id.isNotBlank(), "Model ID should not be blank: $model")
        }
    }
}
