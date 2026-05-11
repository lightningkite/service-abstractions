package com.lightningkite.services.embedding.ollama.integration

import com.lightningkite.services.embedding.EmbeddingModelId
import com.lightningkite.services.embedding.EmbeddingService
import com.lightningkite.services.embedding.test.ErrorHandlingTests

/**
 * Live integration run of [ErrorHandlingTests] against a local Ollama server.
 * Skipped when [OllamaEmbeddingTestConfig.servicePresent] is false.
 *
 * Ollama has no authentication, so [invalidCredentialsService] is null and the
 * `invalidApiKeyFails` test is skipped.
 */
class OllamaErrorHandlingIntegrationTest : ErrorHandlingTests() {
    override val service: EmbeddingService get() = OllamaEmbeddingTestConfig.service
    override val defaultModel: EmbeddingModelId get() = OllamaEmbeddingTestConfig.defaultModel
    override val servicePresent: Boolean get() = OllamaEmbeddingTestConfig.servicePresent
    // Ollama has no auth -- leave invalidCredentialsService as null so the test skips.
}
