package com.lightningkite.services.ai.openai.integration.lmstudio

import com.lightningkite.services.ai.LlmAccess
import com.lightningkite.services.ai.LlmModelId
import com.lightningkite.services.ai.test.ToolCallingTests
import kotlin.test.Test

/**
 * Live tool-calling suite against LM Studio. Whether tool calls work depends entirely on
 * which model the user has loaded; small / base models typically emit unparseable output.
 * Tests will fail (not skip) when an inadequate model is loaded — that's an explicit
 * signal rather than a silent degradation.
 */
class LmStudioToolCallingIntegrationTest : ToolCallingTests() {
    @Test
    fun test(){}
    override val service: LlmAccess get() = LmStudioTestConfig.service
    override val cheapModel: LlmModelId get() = LmStudioTestConfig.cheapModel
    override val visionModel: LlmModelId? get() = LmStudioTestConfig.visionModel
    override val servicePresent: Boolean get() = LmStudioTestConfig.servicePresent

    override val testMaxTokens: Int? = LmStudioTestConfig.testMaxTokens
}
