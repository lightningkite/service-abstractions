package com.lightningkite.services.embedding.openai.integration

import com.lightningkite.services.embedding.EmbeddingModelId
import com.lightningkite.services.embedding.EmbeddingService
import com.lightningkite.services.embedding.test.BasicEmbeddingTests

/**
 * Live integration run of [BasicEmbeddingTests] against the real OpenAI API.
 * Skipped when [OpenAiEmbeddingTestConfig.apiKeyPresent] is false.
 */
class OpenAiBasicEmbeddingIntegrationTest : BasicEmbeddingTests() {
    override val service: EmbeddingService get() = OpenAiEmbeddingTestConfig.service
    override val defaultModel: EmbeddingModelId get() = OpenAiEmbeddingTestConfig.defaultModel
    override val servicePresent: Boolean get() = OpenAiEmbeddingTestConfig.apiKeyPresent
}
