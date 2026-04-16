package com.lightningkite.services.ai.ollama.integration.ollama

import com.lightningkite.services.ai.LlmAccess
import com.lightningkite.services.ai.LlmModelId
import com.lightningkite.services.ai.test.ErrorHandlingTests

/**
 * Live error-handling suite against Ollama. [unknownModelFails] runs; [invalidApiKeyFails]
 * skips naturally because Ollama is a local-first runtime with no API-key concept — we
 * leave [invalidCredentialsService] as null and the abstract test prints SKIP.
 */
class OllamaErrorHandlingIntegrationTest : ErrorHandlingTests() {
    override val service: LlmAccess get() = OllamaTestConfig.service
    override val cheapModel: LlmModelId get() = OllamaTestConfig.cheapModel
    override val servicePresent: Boolean get() = OllamaTestConfig.servicePresent

    // invalidCredentialsService intentionally left at the default (null). Ollama ignores
    // Authorization headers locally, so there is no "bad-key" state to observe.
}
