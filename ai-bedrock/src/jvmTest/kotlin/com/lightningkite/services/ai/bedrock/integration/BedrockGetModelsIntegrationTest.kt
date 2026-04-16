package com.lightningkite.services.ai.bedrock.integration

import com.lightningkite.services.ai.LlmAccess
import com.lightningkite.services.ai.LlmModelId
import com.lightningkite.services.ai.test.GetModelsTests

/**
 * Live-API getModels suite against Bedrock.
 *
 * `BedrockLlmAccess.getModels` returns the locally-curated `KNOWN_MODELS` list (AWS does not
 * expose a cheap Bedrock "list models" endpoint through SigV4 without IAM permissions that
 * callers often lack), so this is effectively an in-process test — but it runs through the
 * real `LlmAccess.Settings` URL parser and confirms the cheapModel id is in the catalogue.
 */
class BedrockGetModelsIntegrationTest : GetModelsTests() {
    override val service: LlmAccess get() = BedrockTestConfig.service
    override val cheapModel: LlmModelId get() = BedrockTestConfig.cheapModel
    override val servicePresent: Boolean get() = BedrockTestConfig.servicePresent
}
