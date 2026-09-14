package com.lightningkite.services.ai.bedrock.integration

import com.lightningkite.services.ai.LlmAccess
import com.lightningkite.services.ai.LlmModelId
import com.lightningkite.services.ai.test.GetModelsTests

/**
 * Live-API getModels suite against Bedrock. Runs once (not per matrix model) because
 * `BedrockLlmAccess.getModels` returns the locally-curated `KNOWN_MODELS` catalogue regardless of
 * which model the service was configured with — AWS exposes no cheap SigV4 "list models" endpoint.
 *
 * [cheapModelExpectedInList] is false: the matrix invokes Claude via its cross-region
 * inference-profile id (`us.anthropic.claude-haiku-4-5-…`), while `KNOWN_MODELS` lists the
 * canonical foundation-model id (`anthropic.claude-haiku-4-5-…`). Those legitimately differ.
 *
 * TODO(inference-profiles): `KNOWN_MODELS` currently ships bare Anthropic/Meta ids that are NOT
 * invocable on-demand in most regions (they require an inference profile). getModels() should
 * eventually emit region-appropriate inference-profile ids so a caller can invoke what it lists.
 */
class BedrockGetModelsIntegrationTest : GetModelsTests() {
    private val profile = BedrockModelProfile(id = "us.anthropic.claude-haiku-4-5-20251001-v1:0")
    override val service: LlmAccess get() = profile.service
    override val cheapModel: LlmModelId get() = profile.model
    override val servicePresent: Boolean get() = profile.servicePresent
    override val cheapModelExpectedInList: Boolean = false
}
