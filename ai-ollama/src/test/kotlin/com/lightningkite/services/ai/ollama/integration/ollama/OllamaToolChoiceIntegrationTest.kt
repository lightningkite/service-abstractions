package com.lightningkite.services.ai.ollama.integration.ollama

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.ai.LlmAccess
import com.lightningkite.services.ai.LlmModelId
import com.lightningkite.services.ai.ollama.OllamaSchemeRegistrar
import com.lightningkite.services.ai.test.ToolChoiceTests

/**
 * Live tool-choice suite against Ollama. Like [OllamaToolCallingIntegrationTest], this
 * suite skips entirely unless `OLLAMA_TOOL_MODEL` is set to an installed tool-capable
 * model.
 *
 * When the suite does run, [supportsToolChoiceNone] and [supportsToolChoiceForced] are
 * both downgraded to false: Ollama forwards `tool_choice` over the OpenAI-compatible
 * wrapper but local models do not reliably obey None / Required / Specific. The abstract
 * suite then merely verifies the provider accepts each choice without error.
 */
class OllamaToolChoiceIntegrationTest : ToolChoiceTests() {

    override val service: LlmAccess by lazy {
        OllamaSchemeRegistrar.ensureRegistered()
        LlmAccess.Settings(
            "ollama://${(OllamaTestConfig.toolModel ?: OllamaTestConfig.cheapModel).id}?baseUrl=${OllamaTestConfig.baseUrl}",
        )("ollama-integration-toolchoice", TestSettingContext())
    }

    override val cheapModel: LlmModelId
        get() = OllamaTestConfig.toolModel ?: OllamaTestConfig.cheapModel

    override val servicePresent: Boolean
        get() = OllamaTestConfig.servicePresent && OllamaTestConfig.toolModelAvailable

    /** Local models don't reliably suppress tool calls when None is requested. */
    override val supportsToolChoiceNone: Boolean = false

    /** Local models don't reliably force a specific tool via Required / Specific. */
    override val supportsToolChoiceForced: Boolean = false
}
