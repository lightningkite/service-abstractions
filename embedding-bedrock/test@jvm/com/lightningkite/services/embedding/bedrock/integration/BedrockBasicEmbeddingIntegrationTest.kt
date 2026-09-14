package com.lightningkite.services.embedding.bedrock.integration

import com.lightningkite.services.embedding.EmbeddingModelId
import com.lightningkite.services.embedding.EmbeddingService
import com.lightningkite.services.embedding.test.BasicEmbeddingTests

/**
 * Live integration run of [BasicEmbeddingTests] against the real Bedrock API.
 * Skipped when AWS credentials are not available.
 */
class BedrockBasicEmbeddingIntegrationTest : BasicEmbeddingTests() {
    override val service: EmbeddingService get() = BedrockEmbeddingTestConfig.service
    override val defaultModel: EmbeddingModelId get() = BedrockEmbeddingTestConfig.defaultModel
    override val servicePresent: Boolean get() = BedrockEmbeddingTestConfig.servicePresent
}
