package com.lightningkite.services.embedding.bedrock.integration

import com.lightningkite.services.embedding.EmbeddingModelId
import com.lightningkite.services.embedding.EmbeddingService
import com.lightningkite.services.embedding.test.ErrorHandlingTests

/**
 * Live integration run of [ErrorHandlingTests] against the real Bedrock API.
 * Skipped when AWS credentials are not available.
 */
class BedrockErrorHandlingIntegrationTest : ErrorHandlingTests() {
    override val service: EmbeddingService get() = BedrockEmbeddingTestConfig.service
    override val defaultModel: EmbeddingModelId get() = BedrockEmbeddingTestConfig.defaultModel
    override val servicePresent: Boolean get() = BedrockEmbeddingTestConfig.servicePresent
    override val invalidCredentialsService: EmbeddingService get() = BedrockEmbeddingTestConfig.invalidCredentialsService
}
