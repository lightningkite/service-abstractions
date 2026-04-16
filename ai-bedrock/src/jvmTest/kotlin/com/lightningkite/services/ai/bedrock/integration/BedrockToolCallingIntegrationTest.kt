package com.lightningkite.services.ai.bedrock.integration

import com.lightningkite.services.ai.LlmAccess
import com.lightningkite.services.ai.LlmModelId
import com.lightningkite.services.ai.test.ToolCallingTests

/**
 * Live-API tool-calling suite against Bedrock. Claude Haiku via Bedrock Converse supports
 * parallel tool calls, so [supportsParallelToolCalls] stays at its default of true.
 */
class BedrockToolCallingIntegrationTest : ToolCallingTests() {
    override val service: LlmAccess get() = BedrockTestConfig.service
    override val cheapModel: LlmModelId get() = BedrockTestConfig.cheapModel
    override val servicePresent: Boolean get() = BedrockTestConfig.servicePresent
}
