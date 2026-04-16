package com.lightningkite.services.ai.ollama.integration.ollama

import com.lightningkite.services.ai.LlmAccess
import com.lightningkite.services.ai.LlmModelId
import com.lightningkite.services.ai.test.GetModelsTests

/**
 * Live `getModels()` suite against Ollama. Developers running these tests may have any
 * subset of models pulled, so [cheapModelExpectedInList] can't be strictly asserted when
 * `OLLAMA_CHEAP_MODEL` points at something not yet installed — default behavior still
 * runs the check since the suite-wide convention is "cheap model is expected locally."
 */
class OllamaGetModelsIntegrationTest : GetModelsTests() {
    override val service: LlmAccess get() = OllamaTestConfig.service
    override val cheapModel: LlmModelId get() = OllamaTestConfig.cheapModel
    override val servicePresent: Boolean get() = OllamaTestConfig.servicePresent
}
