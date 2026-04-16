package com.lightningkite.services.embedding.openai.integration

import com.lightningkite.services.embedding.EmbeddingModelId
import com.lightningkite.services.embedding.EmbeddingService
import com.lightningkite.services.embedding.test.ErrorHandlingTests

/**
 * Live integration run of [ErrorHandlingTests] against the real OpenAI API.
 * Skipped when [OpenAiEmbeddingTestConfig.apiKeyPresent] is false.
 */
class OpenAiErrorHandlingIntegrationTest : ErrorHandlingTests() {
    override val service: EmbeddingService get() = OpenAiEmbeddingTestConfig.service
    override val defaultModel: EmbeddingModelId get() = OpenAiEmbeddingTestConfig.defaultModel
    override val servicePresent: Boolean get() = OpenAiEmbeddingTestConfig.apiKeyPresent
    override val invalidCredentialsService: EmbeddingService get() = OpenAiEmbeddingTestConfig.invalidCredentialsService
}
