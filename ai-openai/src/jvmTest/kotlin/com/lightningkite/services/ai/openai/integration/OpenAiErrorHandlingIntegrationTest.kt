package com.lightningkite.services.ai.openai.integration

import com.lightningkite.services.ai.LlmAccess
import com.lightningkite.services.ai.LlmModelId
import com.lightningkite.services.ai.test.ErrorHandlingTests

/**
 * Live integration run of the shared [ErrorHandlingTests] suite against the real OpenAI API.
 * Exercises both the bogus-model-id path and an invalid-API-key path (using
 * [OpenAiTestConfig.invalidCredentialsService]). Skipped when
 * [OpenAiTestConfig.apiKeyPresent] is false.
 */
class OpenAiErrorHandlingIntegrationTest : ErrorHandlingTests() {
    override val service: LlmAccess get() = OpenAiTestConfig.service
    override val cheapModel: LlmModelId get() = OpenAiTestConfig.cheapModel
    override val visionModel: LlmModelId get() = OpenAiTestConfig.visionModel
    override val servicePresent: Boolean get() = OpenAiTestConfig.apiKeyPresent
    override val invalidCredentialsService: LlmAccess get() = OpenAiTestConfig.invalidCredentialsService
}
