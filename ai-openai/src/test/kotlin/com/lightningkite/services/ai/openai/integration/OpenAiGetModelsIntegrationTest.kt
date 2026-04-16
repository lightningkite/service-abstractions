package com.lightningkite.services.ai.openai.integration

import com.lightningkite.services.ai.LlmAccess
import com.lightningkite.services.ai.LlmModelId
import com.lightningkite.services.ai.test.GetModelsTests

/**
 * Live integration run of the shared [GetModelsTests] suite against the real OpenAI API.
 * OpenAI returns an entitlement-scoped model list, so `gpt-4o-mini` reliably appears for
 * any key that has access to it. Skipped when [OpenAiTestConfig.apiKeyPresent] is false.
 */
class OpenAiGetModelsIntegrationTest : GetModelsTests() {
    override val service: LlmAccess get() = OpenAiTestConfig.service
    override val cheapModel: LlmModelId get() = OpenAiTestConfig.cheapModel
    override val visionModel: LlmModelId get() = OpenAiTestConfig.visionModel
    override val servicePresent: Boolean get() = OpenAiTestConfig.apiKeyPresent
}
