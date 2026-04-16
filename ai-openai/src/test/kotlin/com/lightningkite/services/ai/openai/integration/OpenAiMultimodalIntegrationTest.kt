package com.lightningkite.services.ai.openai.integration

import com.lightningkite.services.ai.LlmAccess
import com.lightningkite.services.ai.LlmModelId
import com.lightningkite.services.ai.test.MultimodalTests

/**
 * Live integration run of the shared [MultimodalTests] suite against the real OpenAI API.
 * Uses `gpt-4o-mini`, which supports vision natively. Skipped when
 * [OpenAiTestConfig.apiKeyPresent] is false.
 */
class OpenAiMultimodalIntegrationTest : MultimodalTests() {
    override val service: LlmAccess get() = OpenAiTestConfig.service
    override val cheapModel: LlmModelId get() = OpenAiTestConfig.cheapModel
    override val visionModel: LlmModelId get() = OpenAiTestConfig.visionModel
    override val servicePresent: Boolean get() = OpenAiTestConfig.apiKeyPresent
}
