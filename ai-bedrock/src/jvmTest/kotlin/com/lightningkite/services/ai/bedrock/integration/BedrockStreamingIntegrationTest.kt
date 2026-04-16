package com.lightningkite.services.ai.bedrock.integration

import com.lightningkite.services.ai.LlmAccess
import com.lightningkite.services.ai.LlmModelId
import com.lightningkite.services.ai.test.StreamingTests

/**
 * Live-API streaming suite against Bedrock. [BedrockLlmAccess.stream] uses the Bedrock
 * ConverseStream event-stream protocol; the base suite handles both the streaming and the
 * [com.lightningkite.services.ai.inference] convenience that collects the flow.
 */
class BedrockStreamingIntegrationTest : StreamingTests() {
    override val service: LlmAccess get() = BedrockTestConfig.service
    override val cheapModel: LlmModelId get() = BedrockTestConfig.cheapModel
    override val servicePresent: Boolean get() = BedrockTestConfig.servicePresent
}
