package com.lightningkite.services.embedding.bedrock.integration

import com.lightningkite.services.embedding.EmbeddingModelId
import com.lightningkite.services.embedding.EmbeddingService
import com.lightningkite.services.embedding.test.ModelListingTests

/**
 * Live integration run of [ModelListingTests] against the real Bedrock API.
 * Skipped when AWS credentials are not available.
 */
class BedrockModelListingIntegrationTest : ModelListingTests() {
    override val service: EmbeddingService get() = BedrockEmbeddingTestConfig.service
    override val defaultModel: EmbeddingModelId get() = BedrockEmbeddingTestConfig.defaultModel
    override val servicePresent: Boolean get() = BedrockEmbeddingTestConfig.servicePresent
}
