package com.lightningkite.services.ai.ollama.integration.ollama

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.ai.LlmAccess
import com.lightningkite.services.ai.LlmModelId
import com.lightningkite.services.ai.ollama.OllamaSchemeRegistrar
import com.lightningkite.services.ai.test.StreamingTests

/**
 * Live streaming suite against Ollama. Uses the native `/api/chat` NDJSON stream path in
 * [com.lightningkite.services.ai.ollama.OllamaLlmAccess.stream]; tool-call events come back
 * as complete JSON objects per frame rather than progressive argument deltas.
 *
 * One test in this suite — [streamingToolCallComplete] — requires a tool-capable model,
 * so the entire class skips unless the developer sets `OLLAMA_TOOL_MODEL` to an installed
 * model. The other streaming tests would otherwise succeed against the plain cheap model,
 * but we keep them gated together so a partial pass isn't mistaken for full coverage.
 *
 * When the suite runs, we use [OllamaTestConfig.toolModel] (forced to be non-null by the
 * `servicePresent` gate), so every test sees the same model.
 */
class OllamaStreamingIntegrationTest : StreamingTests() {

    override val service: LlmAccess by lazy {
        OllamaSchemeRegistrar.ensureRegistered()
        val model = OllamaTestConfig.toolModel ?: OllamaTestConfig.cheapModel
        LlmAccess.Settings(
            "ollama://${model.asString}?baseUrl=${OllamaTestConfig.baseUrl}",
        )("ollama-integration-streaming", TestSettingContext())
    }

    override val cheapModel: LlmModelId
        get() = OllamaTestConfig.toolModel ?: OllamaTestConfig.cheapModel

    override val servicePresent: Boolean
        get() = OllamaTestConfig.servicePresent && OllamaTestConfig.toolModelAvailable
}
