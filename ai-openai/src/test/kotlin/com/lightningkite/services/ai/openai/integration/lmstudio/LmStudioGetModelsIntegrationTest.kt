package com.lightningkite.services.ai.openai.integration.lmstudio

import com.lightningkite.services.ai.LlmAccess
import com.lightningkite.services.ai.LlmModelId
import com.lightningkite.services.ai.test.GetModelsTests

/**
 * Live `getModels()` suite against LM Studio. [LmStudioTestConfig.cheapModel] is always
 * autodetected from `/v1/models`, so it is guaranteed to appear in the list.
 */
class LmStudioGetModelsIntegrationTest : GetModelsTests() {
    override val service: LlmAccess get() = LmStudioTestConfig.service
    override val cheapModel: LlmModelId get() = LmStudioTestConfig.cheapModel
    override val visionModel: LlmModelId? get() = LmStudioTestConfig.visionModel
    override val servicePresent: Boolean get() = LmStudioTestConfig.servicePresent

    override val testMaxTokens: Int? = LmStudioTestConfig.testMaxTokens
}
