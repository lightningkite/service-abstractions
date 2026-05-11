package com.lightningkite.services.embedding.ollama.integration

import com.lightningkite.services.embedding.EmbeddingModelId
import com.lightningkite.services.embedding.EmbeddingService
import com.lightningkite.services.embedding.test.BasicEmbeddingTests

/**
 * Live integration run of [BasicEmbeddingTests] against a local Ollama server.
 * Skipped when [OllamaEmbeddingTestConfig.servicePresent] is false.
 */
class OllamaBasicEmbeddingIntegrationTest : BasicEmbeddingTests() {
    override val service: EmbeddingService get() = OllamaEmbeddingTestConfig.service
    override val defaultModel: EmbeddingModelId get() = OllamaEmbeddingTestConfig.defaultModel
    override val servicePresent: Boolean get() = OllamaEmbeddingTestConfig.servicePresent
}
