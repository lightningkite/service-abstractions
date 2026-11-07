package com.lightningkite.services.ai.anthropic.integration

import com.lightningkite.services.ai.LlmAccess
import com.lightningkite.services.ai.LlmModelId
import com.lightningkite.services.ai.test.MultimodalTests

/**
 * Live-API multimodal suite against Anthropic. Haiku 4.5 accepts both base64 and URL image
 * attachments, so the full suite runs.
 */
class AnthropicMultimodalIntegrationTest : MultimodalTests() {
    override val service: LlmAccess get() = AnthropicTestConfig.service
    override val cheapModel: LlmModelId get() = AnthropicTestConfig.cheapModel
    override val visionModel: LlmModelId get() = AnthropicTestConfig.visionModel
    override val servicePresent: Boolean get() = AnthropicTestConfig.servicePresent
}
