package com.lightningkite.services.ai.anthropic.integration

import com.lightningkite.services.ai.LlmAccess
import com.lightningkite.services.ai.LlmModelId
import com.lightningkite.services.ai.test.ToolChoiceTests

/**
 * Live-API tool-choice suite against Anthropic. Anthropic honors all four tool-choice modes
 * (auto, required, specific, none) as of API version 2023-06-01, so every test runs.
 */
class AnthropicToolChoiceIntegrationTest : ToolChoiceTests() {
    override val service: LlmAccess get() = AnthropicTestConfig.service
    override val cheapModel: LlmModelId get() = AnthropicTestConfig.cheapModel
    override val servicePresent: Boolean get() = AnthropicTestConfig.servicePresent
}
