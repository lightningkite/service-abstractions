package com.lightningkite.services.ai.ollama.integration.ollama

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.ai.LlmAccess
import com.lightningkite.services.ai.LlmModelId
import com.lightningkite.services.ai.ollama.OllamaSchemeRegistrar
import com.lightningkite.services.ai.test.ToolCallingTests

/**
 * Live tool-calling suite against Ollama. SKIPS ENTIRELY unless the developer sets
 * `OLLAMA_TOOL_MODEL` to a model that is actually installed locally — Ollama's tool-call
 * support is model-dependent, and the default tiny cheap model (`llama3.2:1b`) does not
 * reliably emit valid tool calls. Running it anyway would produce noisy failures on a
 * stock developer machine with no useful signal.
 *
 * To run these tests: `ollama pull qwen2.5:7b` (or any other tool-capable 7B+ model),
 * then set `OLLAMA_TOOL_MODEL=qwen2.5:7b` and rerun `./gradlew :ai-ollama:test`.
 */
class OllamaToolCallingIntegrationTest : ToolCallingTests() {

    /** Dedicated service instance so a larger [OllamaTestConfig.toolModel] can be used. */
    override val service: LlmAccess by lazy {
        OllamaSchemeRegistrar.ensureRegistered()
        LlmAccess.Settings(
            "ollama://${(OllamaTestConfig.toolModel ?: OllamaTestConfig.cheapModel).id}?baseUrl=${OllamaTestConfig.baseUrl}",
        )("ollama-integration-tools", TestSettingContext())
    }

    override val cheapModel: LlmModelId
        get() = OllamaTestConfig.toolModel ?: OllamaTestConfig.cheapModel

    /**
     * Only true when BOTH the Ollama server is reachable AND the developer has opted in
     * to tool testing with `OLLAMA_TOOL_MODEL`. Without the env var set every test skips.
     */
    override val servicePresent: Boolean
        get() = OllamaTestConfig.servicePresent && OllamaTestConfig.toolModelAvailable
}
