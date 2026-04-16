package com.lightningkite.services.embedding.openai.integration

import com.lightningkite.services.embedding.EmbeddingModelId
import com.lightningkite.services.embedding.EmbeddingService
import com.lightningkite.services.embedding.test.ModelListingTests

/**
 * Live integration run of [ModelListingTests] against the real OpenAI API.
 * Skipped when [OpenAiEmbeddingTestConfig.apiKeyPresent] is false.
 */
class OpenAiModelListingIntegrationTest : ModelListingTests() {
    override val service: EmbeddingService get() = OpenAiEmbeddingTestConfig.service
    override val defaultModel: EmbeddingModelId get() = OpenAiEmbeddingTestConfig.defaultModel
    override val servicePresent: Boolean get() = OpenAiEmbeddingTestConfig.apiKeyPresent
}
