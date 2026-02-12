package com.lightningkite.services.ai.koog

import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.bedrock.BedrockModels
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.clients.openrouter.OpenRouterModels
import ai.koog.prompt.llm.OllamaModels
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

    // Tests added by Claude during code review - Factory method tests

    @Test
    fun testFactoryMethodOpenAI() {
        val settings = LLMClientAndModel.Settings.openai(OpenAIModels.Chat.GPT4o, "test-api-key")
        assertEquals("openai://gpt-4o?apiKey=test-api-key", settings.url)
    }

    @Test
    fun testFactoryMethodOpenAIWithoutApiKey() {
        val settings = LLMClientAndModel.Settings.openai(OpenAIModels.Chat.GPT4o)
        assertEquals("openai://gpt-4o", settings.url)
    }

    @Test
    fun testFactoryMethodAnthropic() {
        val settings = LLMClientAndModel.Settings.anthropic(AnthropicModels.Sonnet_4_5, "test-api-key")
        assertEquals("anthropic://claude-sonnet-4-5?apiKey=test-api-key", settings.url)
    }

    @Test
    fun testFactoryMethodAnthropicWithoutApiKey() {
        val settings = LLMClientAndModel.Settings.anthropic(AnthropicModels.Sonnet_4_5)
        assertEquals("anthropic://claude-sonnet-4-5", settings.url)
    }

    @Test
    fun testFactoryMethodGoogle() {
        val settings = LLMClientAndModel.Settings.google(GoogleModels.Gemini2_5Pro, "test-api-key")
        assertEquals("google://gemini-2.5-pro?apiKey=test-api-key", settings.url)
    }

    @Test
    fun testFactoryMethodGoogleWithoutApiKey() {
        val settings = LLMClientAndModel.Settings.google(GoogleModels.Gemini2_5Pro)
        assertEquals("google://gemini-2.5-pro", settings.url)
    }

    @Test
    fun testFactoryMethodOllama() {
        val settings = LLMClientAndModel.Settings.ollama(OllamaModels.Meta.LLAMA_3_2)
        assertEquals("ollama://llama3.2:latest", settings.url)
    }

    @Test
    fun testFactoryMethodOllamaWithApiKey() {
        val settings = LLMClientAndModel.Settings.ollama(OllamaModels.Meta.LLAMA_3_2, "some-key")
        assertEquals("ollama://llama3.2:latest?apiKey=some-key", settings.url)
    }

    @Test
    fun testFactoryMethodOllamaAuto() {
        val settings = LLMClientAndModel.Settings.ollamaAuto(OllamaModels.Meta.LLAMA_3_2)
        assertEquals("ollama-auto://llama3.2:latest", settings.url)
    }

    @Test
    fun testFactoryMethodOllamaAutoWithBaseUrl() {
        val settings = LLMClientAndModel.Settings.ollamaAuto(
            OllamaModels.Meta.LLAMA_3_2,
            "http://custom-server:11434"
        )
        assertEquals("ollama-auto://llama3.2:latest?baseUrl=http://custom-server:11434", settings.url)
    }

    @Test
    fun testFactoryMethodOpenRouter() {
        val settings = LLMClientAndModel.Settings.openrouter(OpenRouterModels.GPT5, "test-api-key")
        assertEquals("openrouter://openai/gpt-5?apiKey=test-api-key", settings.url)
    }

    @Test
    fun testFactoryMethodOpenRouterWithoutApiKey() {
        val settings = LLMClientAndModel.Settings.openrouter(OpenRouterModels.GPT5)
        assertEquals("openrouter://openai/gpt-5", settings.url)
    }

    @Test
    fun testFactoryMethodBedrock() {
        val model = BedrockModels.AnthropicClaude4Sonnet
        val settings = LLMClientAndModel.Settings.bedrock(model)
        assertEquals("bedrock://${model.id}", settings.url)
    }

    @Test
    fun testFactoryMethodBedrockWithRegion() {
        val model = BedrockModels.AnthropicClaude4Sonnet
        val settings = LLMClientAndModel.Settings.bedrock(model, region = "us-west-2")
        assertEquals("bedrock://${model.id}?region=us-west-2", settings.url)
    }

    @Test
    fun testFactoryMethodBedrockWithStaticCredentials() {
        val model = BedrockModels.AnthropicClaude4Sonnet
        val settings = LLMClientAndModel.Settings.bedrock(
            model,
            accessKeyId = "AKIATEST",
            secretAccessKey = "secret123"
        )
        assertEquals("bedrock://AKIATEST:secret123@${model.id}", settings.url)
    }

    @Test
    fun testFactoryMethodBedrockWithStaticCredentialsAndRegion() {
        val model = BedrockModels.AnthropicClaude4Sonnet
        val settings = LLMClientAndModel.Settings.bedrock(
            model,
            accessKeyId = "AKIATEST",
            secretAccessKey = "secret123",
            region = "eu-west-1"
        )
        assertEquals("bedrock://AKIATEST:secret123@${model.id}?region=eu-west-1", settings.url)
    }

    // Tests added by Claude during code review - URL scheme registration tests

    @Test
    fun testRegisteredSchemesIncludeAllProviders() {
        val options = LLMClientAndModel.Settings.options

        assertTrue(options.contains("openai"), "Should support openai scheme")
        assertTrue(options.contains("anthropic"), "Should support anthropic scheme")
        assertTrue(options.contains("google"), "Should support google scheme")
        assertTrue(options.contains("ollama"), "Should support ollama scheme")
        assertTrue(options.contains("ollama-auto"), "Should support ollama-auto scheme")
        assertTrue(options.contains("openrouter"), "Should support openrouter scheme")
        assertTrue(options.contains("bedrock"), "Should support bedrock scheme")
        assertTrue(options.contains("mock"), "Should support mock scheme")
    }

    @Test
    fun testSupportsMethodOpenAI() {
        assertTrue(LLMClientAndModel.Settings.supports("openai"))
    }

    @Test
    fun testSupportsMethodAnthropic() {
        assertTrue(LLMClientAndModel.Settings.supports("anthropic"))
    }

    @Test
    fun testSupportsMethodGoogle() {
        assertTrue(LLMClientAndModel.Settings.supports("google"))
    }

    @Test
    fun testSupportsMethodOllama() {
        assertTrue(LLMClientAndModel.Settings.supports("ollama"))
    }

    @Test
    fun testSupportsMethodOllamaAuto() {
        assertTrue(LLMClientAndModel.Settings.supports("ollama-auto"))
    }

    @Test
    fun testSupportsMethodOpenRouter() {
        assertTrue(LLMClientAndModel.Settings.supports("openrouter"))
    }

    @Test
    fun testSupportsMethodBedrock() {
        assertTrue(LLMClientAndModel.Settings.supports("bedrock"))
    }

    @Test
    fun testSupportsMethodMock() {
        assertTrue(LLMClientAndModel.Settings.supports("mock"))
    }

    @Test
    fun testUnsupportedSchemeNotSupported() {
        assertFalse(LLMClientAndModel.Settings.supports("unsupported-scheme"))
    }

    // Tests added by Claude during code review - Bedrock URL format tests

    @Test
    fun testSerializationBedrock() {
        val url = "bedrock://anthropic.claude-sonnet-4-20250514-v1:0?region=us-east-1"
        val settings = LLMClientAndModel.Settings(url)
        assertEquals(url, settings.url)
    }

    @Test
    fun testSerializationBedrockWithCredentials() {
        val url = "bedrock://AKIATEST:secretkey@anthropic.claude-sonnet-4-20250514-v1:0?region=us-west-2"
        val settings = LLMClientAndModel.Settings(url)
        assertEquals(url, settings.url)
    }

    @Test
    fun testSerializationMock() {
        val url = "mock://test"
        val settings = LLMClientAndModel.Settings(url)
        assertEquals(url, settings.url)
    }

    // Tests added by Claude during code review - Ollama-auto URL format tests

    @Test
    fun testSerializationOllamaAuto() {
        val url = "ollama-auto://llama3.2:latest"
        val settings = LLMClientAndModel.Settings(url)
        assertEquals(url, settings.url)
    }

    @Test
    fun testSerializationOllamaAutoWithParams() {
        val url = "ollama-auto://llama3.2:latest?baseUrl=http://localhost:11434&autoStart=true&autoPull=false"
        val settings = LLMClientAndModel.Settings(url)
        assertEquals(url, settings.url)
    }

    // Tests added by Claude during code review - Edge cases

    @Test
    fun testEmptyUrl() {
        val settings = LLMClientAndModel.Settings("")
        assertEquals("", settings.url)
    }

    @Test
    fun testUrlWithOnlyScheme() {
        val settings = LLMClientAndModel.Settings("openai://")
        assertEquals("openai://", settings.url)
    }

    @Test
    fun testUrlWithSpecialCharactersInModelName() {
        val url = "ollama://model-name:v1.2.3"
        val settings = LLMClientAndModel.Settings(url)
        assertEquals(url, settings.url)
    }

    // Tests added by Claude during code review - Known models coverage

    @Test
    fun testKnownModelsHasBedrockModels() {
        val knownModels = LLMClientAndModel.Settings.knownModels

        val bedrockModels = knownModels.filter { it.key.first.id == "bedrock" }
        assertTrue(bedrockModels.isNotEmpty(), "Should have Bedrock models")
    }

    @Test
    fun testKnownModelsHasOpenRouterModels() {
        val knownModels = LLMClientAndModel.Settings.knownModels

        // OpenRouter models have provider ID "openrouter" based on OpenRouterModels
        val openRouterModels = knownModels.filter {
            it.key.first.id == "openrouter" || it.key.first.id == "open-router"
        }
        assertTrue(openRouterModels.isNotEmpty(), "Should have OpenRouter models")
    }

    @Test
    fun testKnownModelsHasDeepSeekModels() {
        val knownModels = LLMClientAndModel.Settings.knownModels

        val deepSeekModels = knownModels.values.filter { it.id.contains("deepseek") }
        assertTrue(deepSeekModels.isNotEmpty(), "Should have DeepSeek models")
    }

    @Test
    fun testKnownModelsContainsEmbeddingModels() {
        val knownModels = LLMClientAndModel.Settings.knownModels

        val embeddingModels = knownModels.values.filter { it.id.contains("embed") }
        assertTrue(embeddingModels.isNotEmpty(), "Should have embedding models")
    }

    @Test
    fun testKnownModelsKeyStructure() {
        val knownModels = LLMClientAndModel.Settings.knownModels

        knownModels.forEach { (key, model) ->
            val (provider, modelId) = key
            assertNotNull(provider, "Provider should not be null")
            assertEquals(model.id, modelId, "Key model ID should match model's ID")
            assertEquals(model.provider, provider, "Key provider should match model's provider")
        }
    }

    @Test
    fun testDeprecatedTypeAlias() {
        // Verify the deprecated type alias still works
        val url = "openai://gpt-4o?apiKey=test"
        @Suppress("DEPRECATION")
        val settings: LLMClientAndModelSettings = LLMClientAndModelSettings(url)
        assertEquals(url, settings.url)
    }
}
