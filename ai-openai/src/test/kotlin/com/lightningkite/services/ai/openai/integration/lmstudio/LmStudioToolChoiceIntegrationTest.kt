package com.lightningkite.services.ai.openai.integration.lmstudio

import com.lightningkite.services.ai.LlmAccess
import com.lightningkite.services.ai.LlmModelId
import com.lightningkite.services.ai.test.ToolChoiceTests

/**
 * Live tool-choice suite against LM Studio. Like most local runtimes, LM Studio's tool-
 * choice honoring is model-dependent; forced and None modes are declared unsupported so
 * the corresponding tests skip (via `Assume.assumeTrue`) rather than fail. Only the Auto
 * baseline test runs.
 */
class LmStudioToolChoiceIntegrationTest : ToolChoiceTests() {
    override val service: LlmAccess get() = LmStudioTestConfig.service
    override val cheapModel: LlmModelId get() = LmStudioTestConfig.cheapModel
    override val visionModel: LlmModelId? get() = LmStudioTestConfig.visionModel
    override val servicePresent: Boolean get() = LmStudioTestConfig.servicePresent

    override val supportsToolChoiceNone: Boolean = false
    override val supportsToolChoiceForced: Boolean = false

    override val testMaxTokens: Int? = LmStudioTestConfig.testMaxTokens
}
