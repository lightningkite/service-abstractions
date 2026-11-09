package com.lightningkite.services.ai.openai.integration

import com.lightningkite.services.ai.LlmAccess
import com.lightningkite.services.ai.LlmModelId
import com.lightningkite.services.ai.test.StreamingTests

/**
 * Live integration run of the shared [StreamingTests] suite against the real OpenAI API.
 * Skipped when [OpenAiTestConfig.apiKeyPresent] is false.
 */
class OpenAiStreamingIntegrationTest : StreamingTests() {
    override val service: LlmAccess get() = OpenAiTestConfig.service
    override val cheapModel: LlmModelId get() = OpenAiTestConfig.cheapModel
    override val visionModel: LlmModelId get() = OpenAiTestConfig.visionModel
    override val servicePresent: Boolean get() = OpenAiTestConfig.apiKeyPresent
}
