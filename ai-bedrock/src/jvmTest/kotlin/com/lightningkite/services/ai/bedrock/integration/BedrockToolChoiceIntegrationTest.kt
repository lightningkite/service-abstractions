package com.lightningkite.services.ai.bedrock.integration

import com.lightningkite.services.ai.LlmAccess
import com.lightningkite.services.ai.LlmModelId
import com.lightningkite.services.ai.test.ToolChoiceTests

/**
 * Live-API tool-choice suite against Bedrock. The Bedrock Converse API surfaces
 * `toolChoice` directly, so the full suite runs at default strictness.
 */
class BedrockToolChoiceIntegrationTest : ToolChoiceTests() {
    override val service: LlmAccess get() = BedrockTestConfig.service
    override val cheapModel: LlmModelId get() = BedrockTestConfig.cheapModel
    override val servicePresent: Boolean get() = BedrockTestConfig.servicePresent
}
