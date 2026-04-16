package com.lightningkite.services.ai.anthropic.integration

import com.lightningkite.services.ai.LlmAccess
import com.lightningkite.services.ai.LlmModelId
import com.lightningkite.services.ai.test.GetModelsTests

/**
 * Live-API getModels suite against Anthropic. Anthropic's /v1/models endpoint lists every
 * publicly announced model, so [cheapModelAppearsInList] should pass with its default true.
 */
class AnthropicGetModelsIntegrationTest : GetModelsTests() {
    override val service: LlmAccess get() = AnthropicTestConfig.service
    override val cheapModel: LlmModelId get() = AnthropicTestConfig.cheapModel
    override val servicePresent: Boolean get() = AnthropicTestConfig.servicePresent
}
