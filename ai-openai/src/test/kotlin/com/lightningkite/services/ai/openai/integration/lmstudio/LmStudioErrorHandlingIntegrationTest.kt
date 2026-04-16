package com.lightningkite.services.ai.openai.integration.lmstudio

import com.lightningkite.services.ai.LlmAccess
import com.lightningkite.services.ai.LlmModelId
import com.lightningkite.services.ai.test.ErrorHandlingTests

/**
 * Live error-handling suite against LM Studio. LM Studio ignores the Authorization header
 * entirely, so [invalidCredentialsService] stays at its default (null) — the invalid-API-
 * key test skips naturally.
 */
class LmStudioErrorHandlingIntegrationTest : ErrorHandlingTests() {
    override val service: LlmAccess get() = LmStudioTestConfig.service
    override val cheapModel: LlmModelId get() = LmStudioTestConfig.cheapModel
    override val visionModel: LlmModelId? get() = LmStudioTestConfig.visionModel
    override val servicePresent: Boolean get() = LmStudioTestConfig.servicePresent

    override val testMaxTokens: Int? = LmStudioTestConfig.testMaxTokens

    // invalidCredentialsService intentionally left at the default (null) — LM Studio
    // ignores the Authorization header entirely.
}
