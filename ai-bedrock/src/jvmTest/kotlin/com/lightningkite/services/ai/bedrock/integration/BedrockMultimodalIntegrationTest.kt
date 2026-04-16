package com.lightningkite.services.ai.bedrock.integration

import com.lightningkite.services.ai.LlmAccess
import com.lightningkite.services.ai.LlmModelId
import com.lightningkite.services.ai.test.MultimodalTests

/**
 * Live-API multimodal suite against Bedrock. The Bedrock Converse API only accepts inline
 * image bytes (base64) — not URL references — so [supportsUrlAttachments] is false, which
 * skips the URL test and runs only the base64 path.
 */
class BedrockMultimodalIntegrationTest : MultimodalTests() {
    override val service: LlmAccess get() = BedrockTestConfig.service
    override val cheapModel: LlmModelId get() = BedrockTestConfig.cheapModel
    override val visionModel: LlmModelId get() = BedrockTestConfig.visionModel
    override val servicePresent: Boolean get() = BedrockTestConfig.servicePresent
    override val supportsUrlAttachments: Boolean get() = false
}
