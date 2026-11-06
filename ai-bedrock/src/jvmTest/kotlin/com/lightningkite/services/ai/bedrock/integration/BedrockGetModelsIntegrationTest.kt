package com.lightningkite.services.ai.bedrock.integration

import com.lightningkite.services.ai.LlmAccess
import com.lightningkite.services.ai.LlmModelId
import com.lightningkite.services.ai.test.GetModelsTests

/**
 * Live-API getModels suite against Bedrock. Runs once (not per matrix model) because
 * `BedrockLlmAccess.getModels` returns the locally-curated `KNOWN_MODELS` catalogue regardless of
 * which model the service was configured with — AWS exposes no cheap SigV4 "list models" endpoint.
 *
 * TODO(inference-profiles): Claude is listed by its invocable `global.` profile, but the Meta ids in
 * `KNOWN_MODELS` are bare and need an inference profile in most regions.
 */
class BedrockGetModelsIntegrationTest : GetModelsTests() {
    private val profile = BedrockModelProfile(id = "global.anthropic.claude-haiku-4-5-20251001-v1:0")
    override val service: LlmAccess get() = profile.service
    override val cheapModel: LlmModelId get() = profile.model
    override val servicePresent: Boolean get() = profile.servicePresent
}
