package com.lightningkite.services.embedding.ollama.integration

import com.lightningkite.services.embedding.EmbeddingModelId
import com.lightningkite.services.embedding.EmbeddingService
import com.lightningkite.services.embedding.test.EmbeddingServiceTests
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Model listing tests for Ollama.
 *
 * Does not extend [com.lightningkite.services.embedding.test.ModelListingTests] because
 * Ollama's `/api/tags` does not report embedding dimensions, so `modelsHaveDimensions`
 * would always fail. Instead we test just the non-empty contract directly.
 */
class OllamaModelListingIntegrationTest : EmbeddingServiceTests() {
    override val service: EmbeddingService get() = OllamaEmbeddingTestConfig.service
    override val defaultModel: EmbeddingModelId get() = OllamaEmbeddingTestConfig.defaultModel
    override val servicePresent: Boolean get() = OllamaEmbeddingTestConfig.servicePresent

    @Test
    fun getModelsReturnsNonEmpty() = runTest(timeout = 60.seconds) {
        skipIfServiceAbsent()
        val models = service.getModels()
        assertTrue(models.isNotEmpty(), "getModels() should return at least one model")
    }

    @Test
    fun modelsHaveZeroCost() = runTest(timeout = 60.seconds) {
        skipIfServiceAbsent()
        val models = service.getModels()
        for (model in models) {
            assertTrue(
                model.usdPerMillionTokens == 0.0,
                "Ollama models should be free (local); ${model.id.id} has cost ${model.usdPerMillionTokens}",
            )
        }
    }
}
