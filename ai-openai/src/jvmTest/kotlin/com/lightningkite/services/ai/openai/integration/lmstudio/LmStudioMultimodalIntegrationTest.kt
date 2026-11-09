package com.lightningkite.services.ai.openai.integration.lmstudio

import com.lightningkite.services.ai.LlmAccess
import com.lightningkite.services.ai.LlmModelId
import com.lightningkite.services.ai.test.MultimodalTests

/**
 * Live multimodal suite against LM Studio. [LmStudioTestConfig.visionModel] autodetects a
 * listed model whose id contains "llava", "vision", "vl", or "image". The whole suite
 * skips when no such model is loaded.
 */
class LmStudioMultimodalIntegrationTest : MultimodalTests() {
    override val service: LlmAccess get() = LmStudioTestConfig.service
    override val cheapModel: LlmModelId get() = LmStudioTestConfig.cheapModel
    override val visionModel: LlmModelId? get() = LmStudioTestConfig.visionModel
    override val servicePresent: Boolean get() = LmStudioTestConfig.servicePresent

    // Local models don't fetch URLs; only base64-attached images are supported.
    override val supportsUrlAttachments: Boolean = false

    override val testMaxTokens: Int? = LmStudioTestConfig.testMaxTokens
}
