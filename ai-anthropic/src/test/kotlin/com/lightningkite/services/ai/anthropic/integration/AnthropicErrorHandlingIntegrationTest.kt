package com.lightningkite.services.ai.anthropic.integration

import com.lightningkite.services.ai.LlmAccess
import com.lightningkite.services.ai.LlmModelId
import com.lightningkite.services.ai.test.ErrorHandlingTests

/**
 * Live-API error-handling suite against Anthropic. An invalid-key variant is supplied so
 * the [invalidApiKeyFails] test runs (not skipped); `unknownModelFails` exercises the
 * real 404 path from Anthropic's Messages API.
 */
class AnthropicErrorHandlingIntegrationTest : ErrorHandlingTests() {
    override val service: LlmAccess get() = AnthropicTestConfig.service
    override val cheapModel: LlmModelId get() = AnthropicTestConfig.cheapModel
    override val servicePresent: Boolean get() = AnthropicTestConfig.servicePresent
    override val invalidCredentialsService: LlmAccess
        get() = AnthropicTestConfig.invalidCredentialsService
}
