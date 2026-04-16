package com.lightningkite.services.ai.bedrock.integration

import com.lightningkite.services.ai.LlmAccess
import com.lightningkite.services.ai.LlmModelId
import com.lightningkite.services.ai.test.TextGenerationTests

/**
 * Live-API text-generation suite against Bedrock's Converse API.
 * Silently skips every test when AWS credentials are absent.
 */
class BedrockTextGenerationIntegrationTest : TextGenerationTests() {
    override val service: LlmAccess get() = BedrockTestConfig.service
    override val cheapModel: LlmModelId get() = BedrockTestConfig.cheapModel
    override val servicePresent: Boolean get() = BedrockTestConfig.servicePresent
}
