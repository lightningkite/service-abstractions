package com.lightningkite.services.ai.anthropic.integration

import com.lightningkite.services.ai.LlmAccess
import com.lightningkite.services.ai.LlmModelId
import com.lightningkite.services.ai.test.StreamingTests

/**
 * Live-API streaming suite against Anthropic. The provider natively uses SSE for all
 * responses (see [com.lightningkite.services.ai.anthropic.AnthropicLlmAccess.stream]),
 * so nothing needs adjusting for this suite.
 */
class AnthropicStreamingIntegrationTest : StreamingTests() {
    override val service: LlmAccess get() = AnthropicTestConfig.service
    override val cheapModel: LlmModelId get() = AnthropicTestConfig.cheapModel
    override val servicePresent: Boolean get() = AnthropicTestConfig.servicePresent
}
