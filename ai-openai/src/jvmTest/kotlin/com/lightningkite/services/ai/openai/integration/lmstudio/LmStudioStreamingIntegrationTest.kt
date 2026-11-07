package com.lightningkite.services.ai.openai.integration.lmstudio

import com.lightningkite.services.ai.LlmAccess
import com.lightningkite.services.ai.LlmModelId
import com.lightningkite.services.ai.test.StreamingTests

/**
 * Live streaming suite against LM Studio via the OpenAI-compatible SSE path in
 * `:ai-openai`.
 */
class LmStudioStreamingIntegrationTest : StreamingTests() {
    override val service: LlmAccess get() = LmStudioTestConfig.service
    override val cheapModel: LlmModelId get() = LmStudioTestConfig.cheapModel
    override val visionModel: LlmModelId? get() = LmStudioTestConfig.visionModel
    override val servicePresent: Boolean get() = LmStudioTestConfig.servicePresent

    override val supportsReasoningContent: Boolean = LmStudioTestConfig.supportsReasoningContent

    override val testMaxTokens: Int? = LmStudioTestConfig.testMaxTokens
}
