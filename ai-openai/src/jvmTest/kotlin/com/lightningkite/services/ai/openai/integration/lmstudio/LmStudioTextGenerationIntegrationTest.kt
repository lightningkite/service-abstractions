package com.lightningkite.services.ai.openai.integration.lmstudio

import com.lightningkite.services.ai.LlmAccess
import com.lightningkite.services.ai.LlmModelId
import com.lightningkite.services.ai.test.TextGenerationTests

/**
 * Live text-generation suite against a local LM Studio server via the `openai://` scheme
 * with a `baseUrl` override. Silently skips when the server is unreachable or no model is
 * loaded — see [LmStudioTestConfig].
 */
class LmStudioTextGenerationIntegrationTest : TextGenerationTests() {
    override val service: LlmAccess get() = LmStudioTestConfig.service
    override val cheapModel: LlmModelId get() = LmStudioTestConfig.cheapModel
    override val visionModel: LlmModelId? get() = LmStudioTestConfig.visionModel
    override val servicePresent: Boolean get() = LmStudioTestConfig.servicePresent

    // Local models don't fetch URLs; only base64-attached images are supported.
    override val supportsUrlAttachments: Boolean = false

    override val supportsStopSequences: Boolean = LmStudioTestConfig.supportsStopSequences

    override val supportsReasoningContent: Boolean = LmStudioTestConfig.supportsReasoningContent

    override val testMaxTokens: Int? = LmStudioTestConfig.testMaxTokens
}
