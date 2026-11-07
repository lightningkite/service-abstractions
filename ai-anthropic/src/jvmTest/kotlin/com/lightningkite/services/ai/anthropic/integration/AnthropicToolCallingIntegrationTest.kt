package com.lightningkite.services.ai.anthropic.integration

import com.lightningkite.services.ai.LlmAccess
import com.lightningkite.services.ai.LlmModelId
import com.lightningkite.services.ai.test.ToolCallingTests

/**
 * Live-API tool-calling suite against Anthropic. Anthropic supports parallel tool calls,
 * so [supportsParallelToolCalls] stays at its default of true.
 */
class AnthropicToolCallingIntegrationTest : ToolCallingTests() {
    override val service: LlmAccess get() = AnthropicTestConfig.service
    override val cheapModel: LlmModelId get() = AnthropicTestConfig.cheapModel
    override val servicePresent: Boolean get() = AnthropicTestConfig.servicePresent
}
