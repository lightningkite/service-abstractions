package com.lightningkite.services.ai.anthropic.integration

import com.lightningkite.services.ai.LlmAccess
import com.lightningkite.services.ai.LlmModelId
import com.lightningkite.services.ai.test.CachingTests

/**
 * Live-API prompt-caching suite against Anthropic's Messages API.
 * Silently skips every test when `ANTHROPIC_API_KEY` is absent.
 */
class AnthropicCachingIntegrationTest : CachingTests() {
    override val service: LlmAccess get() = AnthropicTestConfig.service
    override val cheapModel: LlmModelId get() = AnthropicTestConfig.cheapModel
    override val servicePresent: Boolean get() = AnthropicTestConfig.servicePresent
    override val supportsPromptCaching: Boolean = true
}
